package com.ps.stashit.core.ml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class ModelAssetInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun ensureInstalled(): Pair<String, String> {
        val modelFile = copyIfMissing("models/gecko_256_f32.tflite", "gecko_256_f32.tflite")
        val tokenizerFile = copyIfMissing("models/sentencepiece.model", "sentencepiece.model")
        return modelFile.absolutePath to tokenizerFile.absolutePath
    }

    fun copyIfMissing(assetPath: String, destFileName: String): File {
        val destFile = File(context.filesDir, destFileName)
        if (destFile.exists()) return destFile
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        return destFile
    }
}