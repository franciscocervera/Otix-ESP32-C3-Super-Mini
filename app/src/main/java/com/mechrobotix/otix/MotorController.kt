package com.mechrobotix.otix

interface MotorController {
    fun isReady(): Boolean
    fun send(command: MovementCommand): Boolean
    fun sendSpeed(speed: SpeedLevel): Boolean
    fun sendHeartbeat(): Boolean
    fun close()
}
