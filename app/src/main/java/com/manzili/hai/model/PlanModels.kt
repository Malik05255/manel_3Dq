package com.manzili.hai.model

data class Room(
    val id: String,
    val name: String,
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val areaM2: Double,
    val confidence: Int = 100,
    val locked: Boolean = false
)

data class FloorPlan(
    val title: String = "مشروعي",
    val widthM: Double? = null,
    val heightM: Double? = null,
    val rooms: List<Room> = emptyList(),
    val observations: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
    val sourceSummary: String = ""
)

data class ArchitectMessage(val fromUser: Boolean, val text: String)
