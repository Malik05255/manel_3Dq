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
    val locked: Boolean = false,
    val minAreaM2: Double? = null,
    val preferredAreaM2: Double? = null,
    val polygon: List<PlanPoint> = emptyList()
)

data class PlanPoint(val x: Float, val y: Float)

data class Wall(
    val id: String,
    val start: PlanPoint,
    val end: PlanPoint,
    val thicknessCm: Double? = null,
    val kind: String = "unknown",
    val confidence: Int = 80,
    val locked: Boolean = false,
    val adjacentRoomIds: List<String> = emptyList()
)

data class Opening(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val rotationDeg: Float = 0f,
    val wallId: String? = null,
    val connectsRoomIds: List<String> = emptyList(),
    val confidence: Int = 80,
    val locked: Boolean = false
)

data class PlanDimension(
    val id: String,
    val label: String,
    val valueM: Double,
    val axis: String = "unknown",
    val start: PlanPoint? = null,
    val end: PlanPoint? = null,
    val confidence: Int = 70,
    val sourceText: String = "",
    val pageIndex: Int = 0
)

data class StructuralElement(
    val id: String,
    val type: String,
    val footprint: List<PlanPoint>,
    val rotationDeg: Float = 0f,
    val widthM: Double? = null,
    val depthM: Double? = null,
    val confidence: Int = 80,
    val locked: Boolean = false,
    val connectsFloorIds: List<String> = emptyList(),
    val notes: String = ""
)

data class RoadEdge(
    val id: String,
    val name: String = "شارع",
    val start: PlanPoint,
    val end: PlanPoint,
    val widthM: Double? = null,
    val classification: String = "unknown"
)

data class SiteContext(
    val countryCode: String = "SA",
    val city: String = "",
    val plotBoundary: List<PlanPoint> = emptyList(),
    val roads: List<RoadEdge> = emptyList(),
    val northDeg: Float? = null,
    val frontSetbackM: Double? = null,
    val rearSetbackM: Double? = null,
    val sideSetbackM: Double? = null
)

data class FloorLevel(
    val id: String,
    val name: String,
    val index: Int,
    val elevationM: Double = 0.0,
    val clearHeightM: Double? = null,
    val footprint: List<PlanPoint> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val walls: List<Wall> = emptyList(),
    val openings: List<Opening> = emptyList(),
    val elements: List<StructuralElement> = emptyList()
)

data class ProjectConstraint(
    val id: String,
    val kind: String,
    val text: String,
    val targetIds: List<String> = emptyList(),
    val value: Double? = null,
    val hard: Boolean = true,
    val priority: Int = 90,
    val active: Boolean = true
)

data class PlanPreferences(
    val privacyPriority: Int = 80,
    val circulationPriority: Int = 80,
    val daylightPriority: Int = 70,
    val futureFlexibilityPriority: Int = 60,
    val notes: List<String> = emptyList()
)

data class FloorPlan(
    val title: String = "مشروعي",
    val widthM: Double? = null,
    val heightM: Double? = null,
    val rooms: List<Room> = emptyList(),
    val walls: List<Wall> = emptyList(),
    val openings: List<Opening> = emptyList(),
    val observations: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
    val sourceSummary: String = "",
    val preferences: PlanPreferences = PlanPreferences(),
    val constraints: List<ProjectConstraint> = emptyList(),
    val revision: Int = 1,
    val footprint: List<PlanPoint> = emptyList(),
    val dimensions: List<PlanDimension> = emptyList(),
    val scaleConfidence: Int = 0,
    val northDeg: Float? = null,
    val site: SiteContext = SiteContext(),
    val floors: List<FloorLevel> = emptyList(),
    val activeFloorId: String? = null,
    val saudiRulesEnabled: Boolean = false,
    val elements: List<StructuralElement> = emptyList()
)

data class PlanChange(
    val roomId: String? = null,
    val roomName: String = "",
    val action: String = "MODIFY",
    val beforeAreaM2: Double? = null,
    val afterAreaM2: Double? = null,
    val note: String = ""
)

data class PlanProposal(
    val message: String,
    val updatedPlan: FloorPlan? = null,
    val changes: List<PlanChange> = emptyList(),
    val requiresConfirmation: Boolean = true,
    val confidence: Int = 0
)

data class PlanScore(
    val overall: Int,
    val efficiency: Int,
    val privacy: Int,
    val readingConfidence: Int,
    val geometry: Int,
    val notes: List<String> = emptyList()
)

data class ValidationReport(
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val before: PlanScore,
    val after: PlanScore?
)

data class ArchitectMessage(val fromUser: Boolean, val text: String)
