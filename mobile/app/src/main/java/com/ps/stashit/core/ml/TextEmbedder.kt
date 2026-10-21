package com.ps.stashit.core.ml

interface TextEmbedder {
    suspend fun embedDocument(text: String): FloatArray
    suspend fun embedQuery(text: String): FloatArray

    suspend fun embed(text: String): FloatArray
    val dimension: Int
}