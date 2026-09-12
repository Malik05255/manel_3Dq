package com.manzili.hai.export

import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import java.util.Locale

/** Wavefront OBJ export generated from the canonical semantic 3D scene. */
object ObjPlanExporter {
    fun render(plan: FloorPlan): String = renderScene(Semantic3DEngine.build(plan))

    fun renderScene(scene: Semantic3DEngine.Scene): String = buildString {
        appendLine("# Manzili HAI semantic 3D export")
        appendLine("# title=${sanitize(scene.title)}")
        appendLine("# units=${scene.units}")
        appendLine("# metric_ready=${scene.metricReady}")
        scene.warnings.forEach { appendLine("# warning=${sanitize(it)}") }
        appendLine("o ${sanitize(scene.title).ifBlank { "Manzili_HAI" }}")

        var offset = 1
        scene.meshes.forEach { mesh ->
            appendLine()
            appendLine("g ${sanitize(mesh.floorId)}_${sanitize(mesh.kind)}_${sanitize(mesh.sourceId)}")
            appendLine("# source=${sanitize(mesh.sourceId)} kind=${sanitize(mesh.kind)}")
            mesh.vertices.forEach { v ->
                appendLine("v ${f(v.x)} ${f(v.y)} ${f(v.z)}")
            }
            mesh.faces.forEach { face ->
                if (face.indices.size >= 3) {
                    append("f")
                    face.indices.forEach { local -> append(" ${offset + local}") }
                    appendLine()
                }
            }
            offset += mesh.vertices.size
        }

        appendLine()
        appendLine("# semantic openings")
        scene.openings.forEach { opening ->
            appendLine(
                "# opening id=${sanitize(opening.id)} type=${sanitize(opening.type)} wall=${sanitize(opening.wallId ?: "unlinked")}" +
                    " center=${f(opening.center.x)},${f(opening.center.y)},${f(opening.center.z)}" +
                    " width=${f(opening.width)} sill=${f(opening.sillHeight)} height=${f(opening.height)} confidence=${opening.confidence}"
            )
        }
    }

    private fun f(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun sanitize(value: String): String = value
        .trim()
        .replace(Regex("[^A-Za-z0-9_ء-ي-]+"), "_")
        .take(80)
}
