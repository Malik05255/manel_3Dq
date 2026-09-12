package com.manzili.hai.export

import com.manzili.hai.engine.Architectural3DEnhancementEngine
import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/**
 * Single-file glTF 2.0 / GLB export from the exact semantic 3D scene used by the app.
 *
 * Geometry stays owned by Geometry V3 / Architectural3DEnhancementEngine. The exporter only
 * prepares a render representation and now emits vertex normals so Filament can use its real
 * physically based lighting pipeline instead of a flat/unlit approximation.
 */
object GltfPlanExporter {
    private data class Slice(val offset: Int, val length: Int)
    private data class Built(val json: JSONObject, val bin: ByteArray)

    fun renderGltf(plan: FloorPlan): String {
        val built = build(plan)
        built.json.getJSONArray("buffers").getJSONObject(0).put(
            "uri", "data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(built.bin)}"
        )
        return built.json.toString(2)
    }

    fun renderGlb(plan: FloorPlan): ByteArray {
        val built = build(plan)
        val json = built.json.toString().toByteArray(Charsets.UTF_8).pad4(0x20)
        val bin = built.bin.pad4(0x00)
        val total = 12 + 8 + json.size + 8 + bin.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67)
        out.putInt(2)
        out.putInt(total)
        out.putInt(json.size)
        out.putInt(0x4E4F534A)
        out.put(json)
        out.putInt(bin.size)
        out.putInt(0x004E4942)
        out.put(bin)
        return out.array()
    }

    private fun build(plan: FloorPlan): Built {
        val scene = Architectural3DEnhancementEngine.build(plan)
        val bin = ByteArrayOutputStream()
        val bufferViews = JSONArray()
        val accessors = JSONArray()
        val meshesJson = JSONArray()
        val nodes = JSONArray()
        val materials = materialLibrary()

        scene.meshes.forEach { mesh ->
            if (mesh.vertices.isEmpty()) return@forEach
            val converted = mesh.vertices.map { v -> floatArrayOf(v.x.toFloat(), v.z.toFloat(), (-v.y).toFloat()) }
            val triangles = triangulate(mesh.faces)
            if (triangles.isEmpty()) return@forEach

            val posBytes = ByteBuffer.allocate(converted.size * 12).order(ByteOrder.LITTLE_ENDIAN)
            converted.forEach { p -> p.forEach(posBytes::putFloat) }
            val posSlice = appendAligned(bin, posBytes.array())
            val posView = bufferViews.length()
            bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", posSlice.offset).put("byteLength", posSlice.length).put("target", 34962))
            val posAccessor = accessors.length()
            val xs = converted.map { it[0] }
            val ys = converted.map { it[1] }
            val zs = converted.map { it[2] }
            accessors.put(
                JSONObject()
                    .put("bufferView", posView)
                    .put("componentType", 5126)
                    .put("count", converted.size)
                    .put("type", "VEC3")
                    .put("min", JSONArray(listOf(xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f, zs.minOrNull() ?: 0f)))
                    .put("max", JSONArray(listOf(xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f, zs.maxOrNull() ?: 0f)))
            )

            val normals = smoothNormals(converted, triangles)
            val normalBytes = ByteBuffer.allocate(normals.size * 12).order(ByteOrder.LITTLE_ENDIAN)
            normals.forEach { n -> n.forEach(normalBytes::putFloat) }
            val normalSlice = appendAligned(bin, normalBytes.array())
            val normalView = bufferViews.length()
            bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", normalSlice.offset).put("byteLength", normalSlice.length).put("target", 34962))
            val normalAccessor = accessors.length()
            accessors.put(
                JSONObject()
                    .put("bufferView", normalView)
                    .put("componentType", 5126)
                    .put("count", normals.size)
                    .put("type", "VEC3")
            )

            val indexBytes = ByteBuffer.allocate(triangles.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            triangles.forEach(indexBytes::putInt)
            val idxSlice = appendAligned(bin, indexBytes.array())
            val idxView = bufferViews.length()
            bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", idxSlice.offset).put("byteLength", idxSlice.length).put("target", 34963))
            val idxAccessor = accessors.length()
            accessors.put(JSONObject().put("bufferView", idxView).put("componentType", 5125).put("count", triangles.size).put("type", "SCALAR"))

            val primitive = JSONObject()
                .put("attributes", JSONObject().put("POSITION", posAccessor).put("NORMAL", normalAccessor))
                .put("indices", idxAccessor)
                .put("material", materialIndex(mesh.kind))
                .put("mode", 4)
            val meshIndex = meshesJson.length()
            meshesJson.put(JSONObject().put("name", mesh.name).put("primitives", JSONArray().put(primitive)))
            nodes.put(
                JSONObject()
                    .put("name", mesh.id)
                    .put("mesh", meshIndex)
                    .put("extras", JSONObject().put("kind", mesh.kind).put("floorId", mesh.floorId).put("sourceId", mesh.sourceId))
            )
        }

        val rootNodes = JSONArray((0 until nodes.length()).toList())
        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "Manzili HAI 0.44"))
            .put("scene", 0)
            .put("scenes", JSONArray().put(JSONObject().put("nodes", rootNodes).put("name", scene.title)))
            .put("nodes", nodes)
            .put("meshes", meshesJson)
            .put("materials", materials)
            .put("bufferViews", bufferViews)
            .put("accessors", accessors)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
            .put("extras", JSONObject().put("metricReady", scene.metricReady).put("units", scene.units).put("pbrReady", true))
        return Built(json, bin.toByteArray())
    }

    private fun smoothNormals(vertices: List<FloatArray>, triangles: IntArray): List<FloatArray> {
        val sums = Array(vertices.size) { FloatArray(3) }
        var i = 0
        while (i + 2 < triangles.size) {
            val ia = triangles[i]; val ib = triangles[i + 1]; val ic = triangles[i + 2]; i += 3
            if (ia !in vertices.indices || ib !in vertices.indices || ic !in vertices.indices) continue
            val a = vertices[ia]; val b = vertices[ib]; val c = vertices[ic]
            val abx = b[0] - a[0]; val aby = b[1] - a[1]; val abz = b[2] - a[2]
            val acx = c[0] - a[0]; val acy = c[1] - a[1]; val acz = c[2] - a[2]
            val nx = aby * acz - abz * acy
            val ny = abz * acx - abx * acz
            val nz = abx * acy - aby * acx
            for (idx in intArrayOf(ia, ib, ic)) {
                sums[idx][0] += nx; sums[idx][1] += ny; sums[idx][2] += nz
            }
        }
        return sums.map { n ->
            val length = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (length > 0.000001f) floatArrayOf(n[0] / length, n[1] / length, n[2] / length)
            else floatArrayOf(0f, 1f, 0f)
        }
    }

    private fun materialLibrary(): JSONArray = JSONArray()
        .put(material("Saudi Plaster", 0.84, 0.80, 0.73, 1.0, 0.82))
        .put(material("Concrete Slab", 0.58, 0.59, 0.57, 1.0, 0.91))
        .put(material("Structure", 0.47, 0.34, 0.22, 1.0, 0.79))
        .put(material("Timber Door", 0.35, 0.20, 0.11, 1.0, 0.62))
        .put(material("Architectural Glass", 0.32, 0.58, 0.70, 0.34, 0.12, true))
        .put(material("Roof / Parapet", 0.48, 0.45, 0.40, 1.0, 0.88))
        .put(material("Architectural Accent", 0.67, 0.64, 0.58, 1.0, 0.76))

    private fun material(name: String, r: Double, g: Double, b: Double, a: Double, roughness: Double, blend: Boolean = false): JSONObject =
        JSONObject()
            .put("name", name)
            .put(
                "pbrMetallicRoughness",
                JSONObject()
                    .put("baseColorFactor", JSONArray(listOf(r, g, b, a)))
                    .put("metallicFactor", 0.0)
                    .put("roughnessFactor", roughness)
            )
            .apply { if (blend) { put("alphaMode", "BLEND"); put("doubleSided", true) } }

    private fun materialIndex(kind: String): Int = when (kind) {
        "wall" -> 0
        "slab" -> 1
        "structural" -> 2
        "door" -> 3
        "window" -> 4
        "roof", "saudi-parapet" -> 5
        else -> 6
    }

    private fun triangulate(faces: List<Semantic3DEngine.Face>): IntArray {
        val out = mutableListOf<Int>()
        faces.forEach { face ->
            val idx = face.indices
            if (idx.size >= 3) {
                for (i in 1 until idx.size - 1) {
                    out += idx[0]
                    out += idx[i]
                    out += idx[i + 1]
                }
            }
        }
        return out.toIntArray()
    }

    private fun appendAligned(out: ByteArrayOutputStream, bytes: ByteArray): Slice {
        while (out.size() % 4 != 0) out.write(0)
        val offset = out.size()
        out.write(bytes)
        return Slice(offset, bytes.size)
    }

    private fun ByteArray.pad4(fill: Int): ByteArray {
        val padded = (size + 3) and -4
        if (padded == size) return this
        return copyOf(padded).also { a -> for (i in size until padded) a[i] = fill.toByte() }
    }
}
