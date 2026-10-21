package com.ps.stashit.core.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder as MpTextEmbedder


class LocalRagTextEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) : TextEmbedder {

    override val dimension: Int = 512
    private val mutex = Mutex()

    private val embedder: MpTextEmbedder by lazy {
        val absoluteStoragePath = getSafeModelPath(context)
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(absoluteStoragePath)
            .build()
        val options = MpTextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(baseOptions)

            .setL2Normalize(true)
            .build()

        MpTextEmbedder.createFromOptions(context, options)
    }

    override suspend fun embedDocument(text: String): FloatArray {
        val prompt = "title: | text: ${text.trim()}"
        return embed(prompt)
    }

    override suspend fun embedQuery(text: String): FloatArray {
        val prompt = "task: search result | query: ${text.trim()}"
        return embed(prompt)
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val raw = mutex.withLock {
            embedder.embed(text)
                .embeddingResult()
                .embeddings()
                .first()
                .floatEmbedding()
        }
        raw.copyOf(dimension)
    }

    fun getSafeModelPath(context: Context): String {
        // Save to app internal cache storage directory
        val outputFile = File(context.cacheDir, "embedding_gemma.task")

        // Only copy the large binary payload if it doesn't exist on disk yet
        if (!outputFile.exists() || outputFile.length() == 0L) {
            Log.d("TAG", "Copying large model asset to local storage...")
            context.assets.open("models/embedding_gemma.task").use { inputStream ->
                outputFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d("TAG", "Model asset copy complete.")
        }
        return outputFile.absolutePath
    }
}