package com.ps.stashit.feature.library

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ps.stashit.core.ui.BaseViewModel
import com.ps.stashit.data.domain.SavedItem
import com.ps.stashit.data.repository.SavedItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: SavedItemRepository
) : BaseViewModel<LibraryState, LibraryEvent, LibraryEffect>(LibraryState()) {
    init {
        observeItems()
    }

    override fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.Refresh -> Unit
            is LibraryEvent.DeleteItem -> deleteItem(event.item)
            is LibraryEvent.OpenItem -> sendEffect(LibraryEffect.OpenUrl(event.item.url))
        }
    }

    private fun observeItems() {
        viewModelScope.launch {
            repository.observeAll().collect { items ->
                setState { copy(items = items, isLoading = false) }
            }
        }
    }

    private fun deleteItem(item: SavedItem) {
        viewModelScope.launch {
            repository.delete(item)
            sendEffect(LibraryEffect.ShowUndoDelete(item))
        }
    }
}