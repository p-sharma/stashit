package com.ps.stashit.core.captions

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

sealed interface CaptionResult {
    data class Success(val fullText: String) : CaptionResult

    // The service will never have captions for this url: unsupported link, too long, or nothing spoken.
    data object Unavailable : CaptionResult

    // Worth retrying later: network error, timeout, or a 5xx from the service.
    data object TransientFailure : CaptionResult
}

class CaptionFetcher @Inject constructor(
    private val api: CaptionApi
) {
    suspend fun fetch(url: String): CaptionResult = try {
        val fullText = api.getCaptions(CaptionRequest(url)).fullText?.trim()
        if (fullText.isNullOrEmpty()) CaptionResult.Unavailable else CaptionResult.Success(fullText)
    } catch (e: HttpException) {
        Log.w(TAG, "Caption request for $url failed with HTTP ${e.code()}")
        if (e.code() in PERMANENT_FAILURE_CODES) CaptionResult.Unavailable else CaptionResult.TransientFailure
    } catch (e: IOException) {
        Log.w(TAG, "Caption request for $url failed", e)
        CaptionResult.TransientFailure
    }

    private companion object {
        const val TAG = "CaptionFetcher"
        val PERMANENT_FAILURE_CODES = setOf(404, 422)
    }
}
