package com.mohirzv.sensormqtt

import com.google.gson.annotations.SerializedName

data class SensorData(
    var accelerometer: SensorAxis,
    var gyroscope: SensorAxis,
    var magnetometer: SensorAxis,
    var gps: GpsCoords,
    var deviceName: String = "laptop",
    var geohash: String = ""
)

data class SensorAxis(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float,
    @SerializedName("z") val z: Float
)

data class GpsCoords(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lon") val lon: Double
)