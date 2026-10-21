package com.ps.stashit.feature.search

import androidx.lifecycle.viewModelScope
import com.ps.stashit.core.ui.BaseViewModel
import com.ps.stashit.data.repository.SavedItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SavedItemRepository
) : BaseViewModel<SearchState, SearchEvent, SearchEffect>(SearchState("")) {

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(300.milliseconds)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if(query.isEmpty()) {
                      setState { copy(results = emptyList(), isSearching = false, hasSearched = true) }
                      return@collectLatest
                    }
                    setState { copy(isSearching = true) }
                    val results = repository.search(query)
                    setState { copy(results = results, isSearching = false, hasSearched = true) }
                }
        }
    }
    
    override fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> {
                setState { copy(query = event.query) }
                queryFlow.value = event.query
            }
            is SearchEvent.OpenResult -> sendEffect(SearchEffect.OpenUrl(event.item.url))
        }
    }

}