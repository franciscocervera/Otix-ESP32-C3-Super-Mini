package com.mechrobotix.otix

enum class SpeedLevel(val wire: String, val displayName: String) {
    SLOW("V", "Lento"),
    MEDIUM("W", "Medio"),
    FAST("X", "Rápido")
}
