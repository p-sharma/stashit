package com.ps.stashit.core.ml

import kotlin.math.sqrt
import kotlin.random.Random

class FakeTextEmbedder(
    override val dimension: Int = 384
) : TextEmbedder {
    override suspend fun embedDocument(text: String): FloatArray {
        return embed(text)
    }

    override suspend fun embedQuery(text: String): FloatArray {
        return embed(text)
    }

    override suspend fun embed(text: String): FloatArray {
        val words = text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return FloatArray(dimension)

        val vector = FloatArray(dimension)
        for (word in words) {
            val wordVector = vectorForWord(word)
            for (i in 0 until dimension) vector[i] += wordVector[i]
        }

        return normalize(vector)
    }

    private fun vectorForWord(word: String): FloatArray {
        val rng = Random(word.hashCode().toLong())
        return FloatArray(dimension) { rng.nextFloat() * 2f - 1f }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }
}