package com.manzili.hai.data

import android.content.Context
import com.manzili.hai.model.FloorPlan
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class ReaderCorrectionDelta(
    val roomsChanged: Int,
    val wallsChanged: Int,
    val openingsChanged: Int,
    val dimensionsChanged: Int,
    val scaleChanged: Boolean
) {
    val totalChanges: Int
        get() = roomsChanged + wallsChanged + openingsChanged + dimensionsChanged + if (scaleChanged) 1 else 0

    val hasMeaningfulChange: Boolean
        get() = totalChanges > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("rooms_changed", roomsChanged)
        put("walls_changed", wallsChanged)
        put("openings_changed", openingsChanged)
        put("dimensions_changed", dimensionsChanged)
        put("scale_changed", scaleChanged)
        put("total_changes", totalChanges)
    }
}

/**
 * Compares the reader result with the plan explicitly approved by the user.
 * Revision/confidence metadata alone is ignored; only geometry/labels/scale changes count.
 */
object ReaderCorrectionDiff {
    fun summarize(before: FloorPlan, after: FloorPlan): ReaderCorrectionDelta = ReaderCorrectionDelta(
        roomsChanged = changedById(before.rooms, after.rooms, { it.id }) { a, b ->
            a.name == b.name && a.type == b.type && a.x == b.x && a.y == b.y &&
                a.width == b.width && a.height == b.height && a.areaM2 == b.areaM2 && a.polygon == b.polygon
        },
        wallsChanged = changedById(before.walls, after.walls, { it.id }) { a, b ->
            a.start == b.start && a.end == b.end && a.thicknessCm == b.thicknessCm && a.kind == b.kind
        },
        openingsChanged = changedById(before.openings, after.openings, { it.id }) { a, b ->
            a.type == b.type && a.x == b.x && a.y == b.y && a.width == b.width &&
                a.rotationDeg == b.rotationDeg && a.wallId == b.wallId
        },
        dimensionsChanged = changedById(before.dimensions, after.dimensions, { it.id }) { a, b ->
            a.label == b.label && a.valueM == b.valueM && a.axis == b.axis &&
                a.start == b.start && a.end == b.end
        },
        scaleChanged = before.widthM != after.widthM || before.heightM != after.heightM
    )

    private fun <T> changedById(
        before: List<T>,
        after: List<T>,
        id: (T) -> String,
        sameMeaning: (T, T) -> Boolean
    ): Int {
        val left = before.associateBy(id)
        val right = after.associateBy(id)
        return (left.keys + right.keys).count { key ->
            val a = left[key]
            val b = right[key]
            a == null || b == null || !sameMeaning(a, b)
        }
    }
}

/**
 * App-private queue of corrected reader cases.
 *
 * Privacy contract:
 * - no floor-plan source bytes are copied here;
 * - no source URI is written here;
 * - only the internal project id links the case to ProjectSourceStore, whose URI mapping is encrypted;
 * - nothing is uploaded automatically.
 *
 * A later explicit export/consent flow can resolve the project source and turn selected candidates into
 * licensed/de-identified benchmark or training material.
 */
class ReaderCorrectionStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    fun capture(projectId: String, readerResult: FloorPlan, approvedReference: FloorPlan): String? {
        if (projectId.isBlank()) return null
        val delta = ReaderCorrectionDiff.summarize(readerResult, approvedReference)
        if (!delta.hasMeaningfulChange) return null

        val id = "correction-${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("id", id)
            put("project_id", projectId)
            put("created_at_utc", Instant.now().toString())
            put("reader_model", READER_MODEL)
            put("status", "local-private-candidate")
            put("privacy", JSONObject().apply {
                put("source_bytes_stored", false)
                put("source_uri_stored", false)
                put("automatic_upload", false)
                put("requires_explicit_consent_for_export", true)
            })
            put("delta", delta.toJson())
            put("reader_result", PlanStorageCodec.encode(readerResult))
            put("approved_reference", PlanStorageCodec.encode(approvedReference))
        }

        atomicWrite(File(root, "$id.json"), payload.toString(2))
        trimOldCandidates()
        return id
    }

    fun candidateCount(): Int = root.listFiles { file -> file.isFile && file.extension == "json" }?.size ?: 0

    private fun trimOldCandidates() {
        val files = root.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.drop(MAX_LOCAL_CASES).forEach { it.delete() }
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        const val READER_MODEL = "cubicasa-unet-resnet34-v3"
        private const val DIRECTORY = "reader_learning_candidates_v1"
        private const val MAX_LOCAL_CASES = 200
    }
}
