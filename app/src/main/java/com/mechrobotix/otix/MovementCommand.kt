package com.mechrobotix.otix

enum class MovementCommand(val wire: String, val displayName: String) {
    FORWARD_LEFT("J", "Adelante izquierda"),
    FORWARD("F", "Adelante"),
    FORWARD_RIGHT("Q", "Adelante derecha"),
    LEFT("L", "Izquierda"),
    STOP("S", "Detenido"),
    RIGHT("R", "Derecha"),
    BACKWARD_LEFT("M", "Atrás izquierda"),
    BACKWARD("B", "Atrás"),
    BACKWARD_RIGHT("H", "Atrás derecha")
}
