package com.example.conductorgame.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmgEmulator : InputProvider {
    private val _currentState = MutableStateFlow(EmgState.NONE)
    override val currentState: StateFlow<EmgState> = _currentState.asStateFlow()

    override fun start() {}
    override fun stop() { _currentState.value = EmgState.NONE }

    fun setChannels(isCh1Active: Boolean, isCh2Active: Boolean) {
        _currentState.value = when {
            isCh1Active && isCh2Active -> EmgState.BOTH
            isCh1Active -> EmgState.CH1
            isCh2Active -> EmgState.CH2
            else -> EmgState.NONE
        }
    }
}