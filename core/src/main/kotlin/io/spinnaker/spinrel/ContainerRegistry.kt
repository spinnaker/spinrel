package io.spinnaker.spinrel

import dagger.Binds
import dagger.Module
import dagger.Provides
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import kotlin.annotation.AnnotationTarget.FIELD
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER
import okhttp3.Interceptor
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

class GcrProject(private val name: String) {
    override fun toString() = name
}

interface ContainerRegistry {
    fun addTag(service: String, existingTag: String, newTag: String)
}

class GoogleContainerRegistry @Inject constructor(private val gcrService: GcrService, private val gcrProject: GcrProject) : ContainerRegistry {

    override fun addTag(service: String, existingTag: String, newTag: String) {
        val manifestResponse = gcrService.retrieveManifest(gcrProject, service, existingTag).execute()
        gcrService.storeManifest(gcrProject, service, newTag, manifestResponse.body()!!.string().toRequestBody())
            .execute()
    }
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class ForGcrService

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class GcrBaseUrl

interface GcrService {

    // e.g. https://gcr.io/v2/spinnaker-marketplace/clouddriver/manifests/6.6.0-20200228142642
    @GET("{project}/{imageName}/manifests/{tag}")
    // Without this header it sends us some "prettyjws" format that can't be resubmitted in the PUT request
    @Headers("Accept: application/vnd.docker.distribution.manifest.v2+json")
    fun retrieveManifest(@Path("project") project: GcrProject, @Path("imageName") imageName: String, @Path("tag") tag: String): Call<ResponseBody>

    @PUT("{project}/{imageName}/manifests/{tag}")
    fun storeManifest(@Path("project") project: GcrProject, @Path("imageName") imageName: String, @Path("tag") tag: String, @Body manifest: RequestBody): Call<ResponseBody>
}

@Module
abstract class GoogleContainerRegistryModule {
    @Binds
    abstract fun bindGoogleContainerRegistry(registry: GoogleContainerRegistry): ContainerRegistry

    companion object {
        @Provides
        fun provideGcrService(@ForGcrService okHttpClient: OkHttpClient, @GcrBaseUrl baseUrl: String): GcrService {
            return Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(baseUrl)
                .build()
                .create(GcrService::class.java)
        }

        @Provides
        @ForGcrService
        fun provideOkHttpClient(@ForGoogleApis googleOkHttpClient: OkHttpClient): OkHttpClient {
            return googleOkHttpClient.newBuilder()
                .addNetworkInterceptor(HttpLoggingInterceptor().apply { level = BASIC })
                .addInterceptor(object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): Response {
                        val response = chain.proceed(chain.request())
                        if (!response.isSuccessful) {
                            throw IOException(
                                "${chain.request().method} ${chain.request().url} received ${response.code} code: ${response.message}"
                            )
                        }
                        return response
                    }
                })
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
