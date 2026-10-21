package com.ps.stashit.feature.search

import com.ps.stashit.core.ui.UiEffect
import com.ps.stashit.core.ui.UiEvent
import com.ps.stashit.core.ui.UiState
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.domain.SearchResult

data class SearchState(
    val query: String,
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false
): UiState

sealed interface SearchEvent: UiEvent {
    data class QueryChanged(val query: String): SearchEvent
    data class OpenResult(val item: SavedItem): SearchEvent
}

sealed interface SearchEffect: UiEffect {
    data class OpenUrl(val url: String): SearchEffect
}