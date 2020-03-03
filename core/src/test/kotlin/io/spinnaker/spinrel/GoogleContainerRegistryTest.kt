package io.spinnaker.spinrel

import com.google.common.base.Suppliers
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides
import java.io.IOException
import java.util.function.Supplier
import javax.inject.Singleton
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.endsWith
import strikt.assertions.failed
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull

@Module
object TestingModule {
    @Provides
    @GoogleAccessToken
    fun provideGoogleAccessToken(): Supplier<String> {
        // An access token lasts an hour, but we'll expire a little early just to be safe.
        return Suppliers.ofInstance("google-access-token")
    }

    @Provides
    fun provideGcrProject() = GcrProject("spinfun")
}

@Singleton
@Component(modules = [TestingModule::class, GoogleApiHttpClientModule::class, GoogleContainerRegistryModule::class])
interface TestingComponent {
    fun googleContainerRegistry(): GoogleContainerRegistry

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance @GcrBaseUrl baseUrl: String): TestingComponent
    }
}

class GoogleContainerRegistryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var gcr: GoogleContainerRegistry

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start(8080)
        gcr = DaggerTestingComponent.factory().create(mockWebServer.url("/api/").toString()).googleContainerRegistry()
    }

    @AfterEach
    fun tearDown() = mockWebServer.shutdown()

    @Test
    fun `echoes manifest back to PUT request`() {
        mockWebServer.enqueue(MockResponse().setBody("myManifest"))
        mockWebServer.enqueue(MockResponse())

        gcr.addTag("myservice", "existingTag", "newTag")

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

        expectCatching { gcr.addTag("myservice", "existingTag", "newTag") }
            .failed()
            .isA<IOException>()
            .and { get { message!! }.contains("404") }
    }

    @Test
    fun `throws exception on PUT failure`() {
        mockWebServer.enqueue(MockResponse())
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        expectCatching { gcr.addTag("myservice", "existingTag", "newTag") }
            .failed()
            .isA<IOException>()
            .and { get { message!! }.contains("500") }
    }

    @Test
    fun `sends Authorization header`() {
        mockWebServer.enqueue(MockResponse().setBody("myManifest"))
        mockWebServer.enqueue(MockResponse())

        gcr.addTag("myservice", "existingTag", "newTag")

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

        gcr.addTag("myservice", "existingTag", "newTag")

        with(mockWebServer.takeRequest()) {
            expectThat(getHeader("Accept"))
                .isNotNull()
                .and { endsWith("+json") }
        }
    }
}
