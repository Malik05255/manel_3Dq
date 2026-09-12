package com.manzili.hai.engine

import kotlin.math.max
import kotlin.math.sqrt

/** Pure framing math for the Filament viewer. It never modifies plan/model geometry. */
object PbrSceneFramingEngine {
    data class Frame(
        val targetX: Float,
        val targetY: Float,
        val targetZ: Float,
        val cameraX: Float,
        val cameraY: Float,
        val cameraZ: Float,
        val span: Float
    )

    fun frame(scene: Semantic3DEngine.Scene): Frame {
        val vertices = scene.meshes.flatMap { it.vertices }
        if (vertices.isEmpty()) return Frame(0f, 1f, 0f, 8f, 7f, 12f, 10f)

        // Match GltfPlanExporter coordinate conversion: (semantic x, semantic z, -semantic y).
        val xs = vertices.map { it.x }
        val ys = vertices.map { it.z }
        val zs = vertices.map { -it.y }
        val minX = xs.minOrNull() ?: 0.0; val maxX = xs.maxOrNull() ?: 1.0
        val minY = ys.minOrNull() ?: 0.0; val maxY = ys.maxOrNull() ?: 1.0
        val minZ = zs.minOrNull() ?: 0.0; val maxZ = zs.maxOrNull() ?: 1.0
        val tx = ((minX + maxX) / 2.0).toFloat()
        val ty = ((minY + maxY) / 2.0).toFloat()
        val tz = ((minZ + maxZ) / 2.0).toFloat()
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        val span = max(max(dx, dy), dz).coerceAtLeast(1.0).toFloat()
        val distance = (sqrt(dx * dx + dz * dz).coerceAtLeast(span.toDouble()) * 1.25).toFloat()
        return Frame(
            targetX = tx,
            targetY = ty,
            targetZ = tz,
            cameraX = tx + distance * 0.62f,
            cameraY = maxY.toFloat() + span * 0.55f,
            cameraZ = tz + distance,
            span = span
        )
    }
}
