package com.ps.stashit.core.metadata

import android.util.Log
import com.ps.stashit.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MetaDataNetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @YoutubeRetrofit
    fun provideYoutubeRetrofit(okhttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://www.youtube.com/")
            .client(okhttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideYoutubeOEmbedApi(@YoutubeRetrofit retrofit: Retrofit): YoutubeOEmbedApi {
        return retrofit.create(YoutubeOEmbedApi::class.java)
    }

    @Provides
    @Singleton
    @TiktokRetrofit
    fun provideTiktokRetrofit(okhttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://www.tiktok.com/")
            .client(okhttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideTiktokOEmbedApi(@TiktokRetrofit retrofit: Retrofit): TiktokOEmbedApi {
        return retrofit.create(TiktokOEmbedApi::class.java)
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class YoutubeRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TiktokRetrofit