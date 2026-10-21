package com.ps.stashit.core.ml

import android.content.Context
import com.ps.stashit.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TextEmbedderModule {
    @Provides
    @Singleton
    fun provideTextEmbedder(
        @ApplicationContext context: Context,
    ): TextEmbedder {
        return LocalRagTextEmbedder(context)
    }
}