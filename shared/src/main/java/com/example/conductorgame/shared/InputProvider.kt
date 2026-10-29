package com.example.conductorgame.shared

import kotlinx.coroutines.flow.StateFlow

interface InputProvider {
    val currentState: StateFlow<EmgState>
    fun start()
    fun stop()
}