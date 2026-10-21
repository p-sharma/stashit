package com.ps.stashit.core.ml

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder as MpTextEmbedder

class RealTextEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) : TextEmbedder {

    override val dimension: Int = 512

    private val embedder: MpTextEmbedder by lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("bert_embedder.tflite")
            .build()
        val options = MpTextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
            .build()

        MpTextEmbedder.createFromOptions(context, options)
    }

    override suspend fun embedDocument(text: String): FloatArray = embed(text)

    override suspend fun embedQuery(text: String): FloatArray = embed(text)

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        embedder.embed(text).embeddingResult().embeddings().first().floatEmbedding()
    }
}