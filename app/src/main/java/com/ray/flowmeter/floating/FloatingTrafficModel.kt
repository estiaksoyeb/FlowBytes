package com.ray.flowmeter.floating

import androidx.compose.ui.graphics.ImageBitmap

data class ActiveAppTraffic(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap?,
    val rxSpeed: Long,
    val txSpeed: Long,
    val totalSpeed: Long,
    val rxRatio: Float = 0f,
    val txRatio: Float = 0f
)

data class FloatingTrafficState(
    val rxSpeed: Long = 0L,
    val txSpeed: Long = 0L,
    val totalSpeed: Long = 0L,
    val speedUnit: String = "BYTES",
    val activeApps: List<ActiveAppTraffic> = emptyList(),
    val isExpanded: Boolean = true
)
