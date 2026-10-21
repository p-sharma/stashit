package com.ps.stashit.feature.capture

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ps.stashit.core.ui.BaseViewModel
import com.ps.stashit.data.repository.SavedItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: SavedItemRepository
): BaseViewModel<CaptureState, CaptureEvent, CaptureEffect>(CaptureState()) {
    override fun onEvent(event: CaptureEvent) {
        when(event) {
            is CaptureEvent.ShareReceived -> handleShare(event.sharedText)
        }
    }

    private fun handleShare(sharedText: String) {
        viewModelScope.launch {
            val parsed = ShareTextParser.parse(sharedText)

            if(parsed == null) {
                setState { copy(status = CaptureStatus.Failed) }
                delay(1200.milliseconds)
                sendEffect(CaptureEffect.Dismiss)
                return@launch
            }

            repository.saveItem(
                url = parsed.url,
                platform = parsed.platform,
                caption = parsed.caption
            )

            setState { copy(status = CaptureStatus.Saved) }
            delay(800.milliseconds)
            sendEffect(CaptureEffect.Dismiss)
        }
    }

}