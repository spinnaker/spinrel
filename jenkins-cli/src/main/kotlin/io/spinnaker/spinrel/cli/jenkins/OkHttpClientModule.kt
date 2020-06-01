package io.spinnaker.spinrel.cli.jenkins

import dagger.Module
import dagger.Provides
import okhttp3.OkHttpClient

@Module
internal interface OkHttpClientModule {

    companion object {
        @Provides
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
    }
}
