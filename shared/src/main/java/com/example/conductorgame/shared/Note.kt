package com.example.conductorgame.shared

data class Note(
    var x: Float,
    var y: Float,
    val channel: EmgState,
    val row: Int // 1 - нижний, 2 - средний, 3 - верхний (мужчины)
)