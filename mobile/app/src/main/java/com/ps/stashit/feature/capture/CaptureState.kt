package com.ps.stashit.feature.capture

import com.ps.stashit.core.ui.UiEffect
import com.ps.stashit.core.ui.UiEvent
import com.ps.stashit.core.ui.UiState

data class CaptureState(
    val status: CaptureStatus = CaptureStatus.Processing
) : UiState


enum class CaptureStatus {Processing, Saved, Failed}

sealed interface CaptureEvent: UiEvent {
    data class ShareReceived(val sharedText: String): CaptureEvent
}

sealed interface CaptureEffect: UiEffect {
    data object Dismiss: CaptureEffect
}