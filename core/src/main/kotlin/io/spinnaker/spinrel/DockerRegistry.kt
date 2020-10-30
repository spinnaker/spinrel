package io.spinnaker.spinrel

import com.google.common.annotations.VisibleForTesting
import dagger.Binds
import dagger.Module
import dagger.Provides
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import kotlin.annotation.AnnotationTarget.FIELD
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER

const val DOCKER_SCHEMA = "application/vnd.docker.distribution.manifest.v2+json"

interface DockerRegistry {
    fun addTag(service: String, existingTag: String, newTag: String)
}

fun interface DockerRegistryFactory {
    fun create(dockerRegistry: String): DockerRegistry
}

/**
 * A [DockerRegistryFactory] that uses GCP authentication for its constructed [DockerRegistry].
 */
class GoogleDockerRegistryFactory @Inject constructor(
    @ForGoogleDockerService private val okHttpClient: OkHttpClient
) : DockerRegistryFactory {

    override fun create(dockerRegistry: String): DockerRegistry {
        val dockerService = Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl(getDockerApiBase(dockerRegistry))
            .build()
            .create(DockerService::class.java)
        return DockerRegistryImpl(dockerService)
    }

    companion object {
        @VisibleForTesting
        internal fun getDockerApiBase(dockerRegistry: String): HttpUrl {
            val registryWithScheme: String
            if (Regex("^https?://").find(dockerRegistry) == null) {
                registryWithScheme = "https://$dockerRegistry"
            } else {
                registryWithScheme = dockerRegistry
            }
            return getDockerApiBase(registryWithScheme.toHttpUrl())
        }

        internal fun getDockerApiBase(dockerRegistry: HttpUrl): HttpUrl {
            val encodedPath: String
            if (dockerRegistry.encodedPath.endsWith('/')) {
                encodedPath = dockerRegistry.encodedPath
            } else {
                encodedPath = "${dockerRegistry.encodedPath}/"
            }
            return dockerRegistry.newBuilder().encodedPath("/v2$encodedPath").build()
        }
    }
}

class DockerRegistryImpl @Inject constructor(private val dockerService: DockerService) : DockerRegistry {

    override fun addTag(service: String, existingTag: String, newTag: String) {
        val manifestResponse = dockerService.retrieveManifest(service, existingTag).execute()
        val manifestRequest = manifestResponse.body()!!.string().toRequestBody(DOCKER_SCHEMA.toMediaType())
        dockerService.storeManifest(service, newTag, manifestRequest).execute()
    }
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class ForGoogleDockerService

interface DockerService {

    // e.g. https://gcr.io/v2/spinnaker-marketplace/clouddriver/manifests/6.6.0-20200228142642
    @GET("{imageName}/manifests/{tag}")
    // Without this header it sends us some "prettyjws" format that can't be resubmitted in the PUT request
    @Headers("Accept: $DOCKER_SCHEMA")
    fun retrieveManifest(@Path("imageName") imageName: String, @Path("tag") tag: String): Call<ResponseBody>

    @PUT("{imageName}/manifests/{tag}")
    fun storeManifest(
        @Path("imageName") imageName: String,
        @Path("tag") tag: String,
        @Body manifest: RequestBody
    ): Call<ResponseBody>
}

@Module
abstract class GoogleDockerRegistryModule {
    @Binds
    abstract fun bindDockerRegistryFactory(dockerRegistryFactory: GoogleDockerRegistryFactory): DockerRegistryFactory

    companion object {
        @Provides
        @ForGoogleDockerService
        fun provideOkHttpClient(@ForGoogleApis googleOkHttpClient: OkHttpClient): OkHttpClient {
            return googleOkHttpClient.newBuilder()
                .addNetworkInterceptor(HttpLoggingInterceptor().apply { level = BASIC })
                .addInterceptor(
                    object : Interceptor {
                        override fun intercept(chain: Interceptor.Chain): Response {
                            val response = chain.proceed(chain.request())
                            if (!response.isSuccessful) {
                                throw IOException(
                                    "${chain.request().method} ${chain.request().url} received ${response.code} code: ${response.message}"
                                )
                            }
                            return response
                        }
                    }
                )
                .addInterceptor(TemporaryErrorRetryingInterceptor())
                .build()
        }
    }
}

/**
 * An interceptor that retries requests with status codes 502, 503, and 504. The <a
 * href="https://docs.docker.com/registry/spec/api/">Docker API spec</a> says that these should be considered a
 * temporary condition and retried. We've seen 504 errors in the wild, too.
 */
class TemporaryErrorRetryingInterceptor : Interceptor {

    companion object {
        const val MAX_ATTEMPTS = 10
        const val INITIAL_DELAY_MS: Long = 10
        const val MAX_DELAY_MS: Long = 1000
    }

    override fun intercept(chain: Interceptor.Chain): Response {

        var attempts = 0
        var delay = INITIAL_DELAY_MS
        var response: Response
        while (true) {
            response = chain.proceed(chain.request())
            ++attempts
            if (attempts >= MAX_ATTEMPTS || response.code !in 502..504) {
                return response
            } else {
                response.close()
                TimeUnit.MILLISECONDS.sleep(delay)
                delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
            }
        }
    }
}
