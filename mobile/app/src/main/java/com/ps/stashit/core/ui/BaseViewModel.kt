package com.ps.stashit.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface UiState
interface UiEffect
interface UiEvent

abstract class BaseViewModel<S : UiState, E : UiEvent, F : UiEffect>(initialState: S) :
    ViewModel() {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effect = Channel<F>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    abstract fun onEvent(event: E)

    protected fun setState(reducer: S.() -> S) {
        _state.update(reducer)
    }

    protected fun sendEffect(effect: F) {
        viewModelScope.launch { _effect.send(effect) }
    }
}