package io.spinnaker.spinrel

import com.google.common.base.Suppliers
import com.google.common.io.ByteStreams
import dagger.Module
import dagger.Provides
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationTarget.FIELD
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class GoogleAccessToken

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class ForGoogleApis

@Module
object GoogleAuthModule {
    @Singleton
    @Provides
    @GoogleAccessToken
    fun provideGoogleAccessToken(): Supplier<String> {
        // An access token lasts an hour, but we'll expire a little early just to be safe.
        return Suppliers.memoizeWithExpiration({ getAccessToken() }, 55, TimeUnit.MINUTES)
    }

    private fun getAccessToken(): String {
        val process = ProcessBuilder().command("gcloud", "auth", "print-access-token").start()
        val returnCode = process.waitFor()
        if (returnCode != 0) {
            val errorMessage = String(ByteStreams.toByteArray(process.errorStream))
            throw IOException(
                "gcloud auth command exited with return code $returnCode: $errorMessage"
            )
        }
        return String(ByteStreams.toByteArray(process.inputStream)).trim()
    }
}

@Module
object GoogleApiHttpClientModule {
        @Provides
        @ForGoogleApis
        fun provideOkHttpClient(@GoogleAccessToken googleAccessTokenSupplier: Supplier<String>): OkHttpClient {
            return OkHttpClient.Builder()
                .addInterceptor(object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): Response {
                        val newRequest = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer ${googleAccessTokenSupplier.get()}")
                            .build()
                        return chain.proceed(newRequest)
                    }
                }).build()
        }
    }
