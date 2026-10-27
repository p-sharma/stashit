package com.ps.stashit.core.captions

import com.ps.stashit.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CaptionNetworkModule {

    // The service may download and transcribe audio with Whisper, bounded by its 300s request timeout,
    // so this client cannot share the default 10s read timeout of the oEmbed client.
    @Provides
    @Singleton
    @CaptionHttpClient
    fun provideCaptionOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .readTimeout(310, TimeUnit.SECONDS)
            .callTimeout(330, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @CaptionRetrofit
    fun provideCaptionRetrofit(@CaptionHttpClient okhttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.CAPTION_SERVICE_BASE_URL)
            .client(okhttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideCaptionApi(@CaptionRetrofit retrofit: Retrofit): CaptionApi {
        return retrofit.create(CaptionApi::class.java)
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CaptionHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CaptionRetrofit
