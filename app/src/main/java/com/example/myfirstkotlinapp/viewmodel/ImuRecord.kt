package com.example.myfirstkotlinapp.viewmodel

data class ImuRecord(
    val timestamp: Long,
    val accX: Float, val accY: Float, val accZ: Float,
    val gyroX: Float, val gyroY: Float, val gyroZ: Float,
    val magX: Float, val magY: Float, val magZ: Float,
    val roll: Float, val pitch: Float, val yaw: Float
) {
    fun toCsvString(): String {
        return "$timestamp,$accX,$accY,$accZ,$gyroX,$gyroY,$gyroZ,$magX,$magY,$magZ,$roll,$pitch,$yaw"
    }
}