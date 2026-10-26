package com.ps.stashit.testutil

import com.ps.stashit.core.ml.TextEmbedder

/**
 * Returns pre-registered vectors per input text. Unregistered text gets [default].
 * Records every call so tests can assert on what was embedded.
 */
class StubTextEmbedder(
    override val dimension: Int = 3,
    private val default: FloatArray = FloatArray(dimension)
) : TextEmbedder {
    private val documentVectors = mutableMapOf<String, FloatArray>()
    private val queryVectors = mutableMapOf<String, FloatArray>()

    val embeddedDocuments = mutableListOf<String>()
    val embeddedQueries = mutableListOf<String>()

    fun givenDocument(text: String, vector: FloatArray) {
        documentVectors[text] = vector
    }

    fun givenQuery(text: String, vector: FloatArray) {
        queryVectors[text] = vector
    }

    override suspend fun embedDocument(text: String): FloatArray {
        embeddedDocuments += text
        return documentVectors[text] ?: default
    }

    override suspend fun embedQuery(text: String): FloatArray {
        embeddedQueries += text
        return queryVectors[text] ?: default
    }

    override suspend fun embed(text: String): FloatArray = documentVectors[text] ?: default
}
