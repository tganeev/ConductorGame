package com.example.conductorgame

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

enum class HandGesture {
    NONE,        // Нет руки
    FIST,        // Кулак (Стоять)
    INDEX_FINGER,// Указательный палец (Идти)
    TWO_FINGERS, // Указательный + средний (Бежать)
    THUMB        // Большой палец вверх (Прыгать)
}

object GestureAnalyzer {

    fun analyze(result: HandLandmarkerResult): HandGesture {
        if (result.landmarks().isEmpty()) return HandGesture.NONE

        val hand = result.landmarks()[0]

        // Проверяем вытянутость пальцев
        val isIndexExtended = isFingerExtended(hand[8], hand[5], hand[0])
        val isMiddleExtended = isFingerExtended(hand[12], hand[9], hand[0])
        val isRingExtended = isFingerExtended(hand[16], hand[13], hand[0])
        val isPinkyExtended = isFingerExtended(hand[20], hand[17], hand[0])

        // 1. Бег: Указательный и средний вытянуты, остальные сжаты
        if (isIndexExtended && isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            return HandGesture.TWO_FINGERS
        }

        // 2. Идти: Вытянут ТОЛЬКО указательный
        if (isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            return HandGesture.INDEX_FINGER
        }

        // 3. Прыжок: Большой палец отставлен, остальные сжаты (Класс)
        // Проверяем большой палец: кончик (4) далеко от основания указательного (5)
        val isThumbExtended = hypot(hand[4].x() - hand[5].x(), hand[4].y() - hand[5].y()) > 0.08f
        if (isThumbExtended && !isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended) {
            return HandGesture.THUMB
        }

        // 4. Во всех остальных случаях (все сжаты) - Кулак (Стоять)
        return HandGesture.FIST
    }

    private fun isFingerExtended(tip: NormalizedLandmark, mcp: NormalizedLandmark, wrist: NormalizedLandmark): Boolean {
        val distTip = hypot(tip.x() - wrist.x(), tip.y() - wrist.y())
        val distMcp = hypot(mcp.x() - wrist.x(), mcp.y() - wrist.y())
        return distTip > distMcp * 1.2f
    }
}