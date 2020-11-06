package io.spinnaker.spinrel

import com.google.common.base.Suppliers
import dagger.Component
import dagger.Module
import dagger.Provides
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.endsWith
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import strikt.assertions.isNotNull
import strikt.assertions.isSuccess
import java.io.IOException
import java.util.function.Supplier
import javax.inject.Singleton

@Module
object TestingModule {
    @Provides
    @GoogleAccessToken
    fun provideGoogleAccessToken(): Supplier<String> {
        return Suppliers.ofInstance("google-access-token")
    }
}

@Singleton
@Component(modules = [TestingModule::class, GoogleApiHttpClientModule::class, GoogleDockerRegistryModule::class])
interface TestingComponent {
    fun googleDockerRegistryFactory(): GoogleDockerRegistryFactory
}

class GoogleDockerRegistryFactoryTest {

    @Test
    fun `getDockerApiBase() without scheme`() {
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("my.docker.reg").toString())
            .isEqualTo("https://my.docker.reg/v2/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("my.docker.reg/").toString())
            .isEqualTo("https://my.docker.reg/v2/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("my.docker.reg/foo").toString())
            .isEqualTo("https://my.docker.reg/v2/foo/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("my.docker.reg/foo/").toString())
            .isEqualTo("https://my.docker.reg/v2/foo/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("my.docker.reg/foo/bar/baz").toString())
            .isEqualTo("https://my.docker.reg/v2/foo/bar/baz/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("my.docker.reg/foo/bar/baz/").toString())
            .isEqualTo("https://my.docker.reg/v2/foo/bar/baz/")
    }

    @Test
    fun `getDockerApiBase() with scheme`() {
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("http://my.docker.reg").toString())
            .isEqualTo("http://my.docker.reg/v2/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("http://my.docker.reg/").toString())
            .isEqualTo("http://my.docker.reg/v2/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("http://my.docker.reg/foo").toString())
            .isEqualTo("http://my.docker.reg/v2/foo/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("http://my.docker.reg/foo/").toString())
            .isEqualTo("http://my.docker.reg/v2/foo/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("http://my.docker.reg/foo/bar/baz").toString())
            .isEqualTo("http://my.docker.reg/v2/foo/bar/baz/")
        expectThat(GoogleDockerRegistryFactory.getDockerApiBase("http://my.docker.reg/foo/bar/baz/").toString())
            .isEqualTo("http://my.docker.reg/v2/foo/bar/baz/")
    }
}

class DockerRegistryImplTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var docker: DockerRegistry

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        val dockerFactory = DaggerTestingComponent.create().googleDockerRegistryFactory()
        docker = dockerFactory.create(mockWebServer.url("/docker/reg").toString())
    }

    @AfterEach
    fun tearDown() = mockWebServer.shutdown()

    @Test
    fun `echoes manifest back to PUT request`() {
        mockWebServer.enqueue(MockResponse().setBody("myManifest"))
        mockWebServer.enqueue(MockResponse())

        docker.addTag("myservice", "existingTag", "newTag")

        with(mockWebServer.takeRequest()) {
            expectThat(method!!).isEqualTo("GET")
                .and { path!! }.endsWith("existingTag")
        }
        with(mockWebServer.takeRequest()) {
            expectThat(method!!).isEqualTo("PUT")
                .and { path!! }.endsWith("newTag")
                .and { body.readUtf8() }.isEqualTo("myManifest")
        }
    }

    @Test
    fun `throws exception on GET failure`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        mockWebServer.enqueue(MockResponse())

        expectCatching { docker.addTag("myservice", "existingTag", "newTag") }
            .isFailure()
            .isA<IOException>()
            .and { get { message!! }.contains("404") }
    }

    @Test
    fun `throws exception on PUT failure`() {
        mockWebServer.enqueue(MockResponse())
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        expectCatching { docker.addTag("myservice", "existingTag", "newTag") }
            .isFailure()
            .isA<IOException>()
            .and { get { message!! }.contains("500") }
    }

    @Test
    fun `sends Authorization header`() {
        mockWebServer.enqueue(MockResponse().setBody("myManifest"))
        mockWebServer.enqueue(MockResponse())

        docker.addTag("myservice", "existingTag", "newTag")

        with(mockWebServer.takeRequest()) {
            expectThat(getHeader("Authorization"))
                .isNotNull()
                .and { contains("google-access-token") }
        }
    }

    @Test
    fun `sends Accept header on retrieveManifest`() {
        mockWebServer.enqueue(MockResponse().setBody("myManifest"))
        mockWebServer.enqueue(MockResponse())

        docker.addTag("myservice", "existingTag", "newTag")

        with(mockWebServer.takeRequest()) {
            expectThat(getHeader("Accept"))
                .isNotNull()
                .and { endsWith("+json") }
        }
    }

    @Test
    fun `retries on 502, 503, and 504 errors`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(502))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(504))
        mockWebServer.enqueue(MockResponse().setBody("GET success"))
        mockWebServer.enqueue(MockResponse().setResponseCode(502))
        mockWebServer.enqueue(MockResponse().setResponseCode(503))
        mockWebServer.enqueue(MockResponse().setResponseCode(504))
        mockWebServer.enqueue(MockResponse().setBody("PUT success"))

        expectCatching { docker.addTag("myservice", "existingTag", "newTag") }
            .isSuccess()
    }

    @Test
    fun `eventually gives up after retryable errors`() {
        for (i in 1..TemporaryErrorRetryingInterceptor.MAX_ATTEMPTS) {
            mockWebServer.enqueue(MockResponse().setResponseCode(502))
        }
        mockWebServer.enqueue(MockResponse().setBody("a success you'll never see"))

        expectCatching { docker.addTag("myservice", "existingTag", "newTag") }
            .isFailure()
            .isA<IOException>()
            .and { get { message!! }.contains("502") }
    }

    @Test
    fun `retries aren't cumulative across requests`() {
        for (i in 1 until TemporaryErrorRetryingInterceptor.MAX_ATTEMPTS) {
            mockWebServer.enqueue(MockResponse().setResponseCode(502))
        }
        mockWebServer.enqueue(MockResponse().setBody("GET success"))
        for (i in 1 until TemporaryErrorRetryingInterceptor.MAX_ATTEMPTS) {
            mockWebServer.enqueue(MockResponse().setResponseCode(503))
        }
        mockWebServer.enqueue(MockResponse().setBody("PUT success"))

        expectCatching { docker.addTag("myservice", "existingTag", "newTag") }
            .isSuccess()
    }
}
