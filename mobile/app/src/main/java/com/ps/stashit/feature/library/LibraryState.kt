package com.ps.stashit.feature.library

import com.ps.stashit.core.ui.UiEffect
import com.ps.stashit.core.ui.UiEvent
import com.ps.stashit.core.ui.UiState
import com.ps.stashit.data.domain.SavedItem

data class LibraryState(
    val items: List<SavedItem> = emptyList(),
    val isLoading: Boolean = true
) : UiState {
    val isEmpty: Boolean
        get() = !isLoading && items.isEmpty()
}

sealed interface LibraryEvent: UiEvent {
    data object Refresh: LibraryEvent
    data class DeleteItem(val item: SavedItem): LibraryEvent
    data class OpenItem(val item: SavedItem): LibraryEvent
}

sealed interface LibraryEffect: UiEffect {
    data class OpenUrl(val url: String): LibraryEffect
    data class ShowUndoDelete(val item: SavedItem): LibraryEffect
}