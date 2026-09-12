package com.manzili.hai.export

import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Compact IFC4 Reference View export built from Semantic3DEngine.
 *
 * IFC is deliberately disabled until plan scale is sufficiently confirmed. The exporter creates
 * spatial hierarchy, storeys, semantic spaces, wall/slab/structural products, closed BRep geometry,
 * opening elements and IfcRelVoidsElement relations back to their canonical walls.
 */
object IfcPlanExporter {
    private const val IFC64 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$"

    fun canExport(plan: FloorPlan): Boolean = Semantic3DEngine.build(plan).metricReady

    fun render(plan: FloorPlan): String {
        val scene = Semantic3DEngine.build(plan)
        require(scene.metricReady) { "IFC يحتاج مقياسًا متريًا مؤكدًا وأبعاد المخطط قبل التصدير." }
        return Writer(plan, scene).render()
    }

    private class Writer(
        private val plan: FloorPlan,
        private val scene: Semantic3DEngine.Scene
    ) {
        private val lines = mutableListOf<String>()
        private var nextId = 1
        private val floorEntity = mutableMapOf<String, Int>()
        private val floorPlacement = mutableMapOf<String, Int>()
        private val productBySource = mutableMapOf<Pair<String, String>, Int>()

        fun render(): String {
            val origin = entity("IFCCARTESIANPOINT((0.,0.,0.))")
            val world = entity("IFCAXIS2PLACEMENT3D(#$origin,$,$)")
            val context = entity("IFCGEOMETRICREPRESENTATIONCONTEXT($,'Model',3,1.E-05,#$world,$)")
            val metre = entity("IFCSIUNIT(*,.LENGTHUNIT.,$,.METRE.)")
            val sqm = entity("IFCSIUNIT(*,.AREAUNIT.,$,.SQUARE_METRE.)")
            val cbm = entity("IFCSIUNIT(*,.VOLUMEUNIT.,$,.CUBIC_METRE.)")
            val units = entity("IFCUNITASSIGNMENT((#$metre,#$sqm,#$cbm))")
            val project = entity("IFCPROJECT(${guid("project")},$,${str(plan.title)},$,$,$,$,(#$context),#$units)")

            val siteAxis = entity("IFCAXIS2PLACEMENT3D(#$origin,$,$)")
            val sitePlace = entity("IFCLOCALPLACEMENT($,#$siteAxis)")
            val site = entity("IFCSITE(${guid("site")},$,'Site',$,$,#$sitePlace,$,$,.ELEMENT.,$,$,$,$,$)")
            val buildingAxis = entity("IFCAXIS2PLACEMENT3D(#$origin,$,$)")
            val buildingPlace = entity("IFCLOCALPLACEMENT(#$sitePlace,#$buildingAxis)")
            val building = entity("IFCBUILDING(${guid("building")},$,'Building',$,$,#$buildingPlace,$,$,.ELEMENT.,$,$,$)")
            entity("IFCRELAGGREGATES(${guid("project-site")},$,$,$,#$project,(#$site))")
            entity("IFCRELAGGREGATES(${guid("site-building")},$,$,$,#$site,(#$building))")

            scene.floorIds.forEachIndexed { index, floorId ->
                val elevation = floorElevation(floorId, index)
                val p = entity("IFCCARTESIANPOINT((0.,0.,0.))")
                val axis = entity("IFCAXIS2PLACEMENT3D(#$p,$,$)")
                val placement = entity("IFCLOCALPLACEMENT(#$buildingPlace,#$axis)")
                floorPlacement[floorId] = placement
                val storey = entity(
                    "IFCBUILDINGSTOREY(${guid("storey-$floorId")},$,${str(floorName(floorId, index))},$,$,#$placement,$,$,.ELEMENT.,${f(elevation)})"
                )
                floorEntity[floorId] = storey
            }
            if (floorEntity.isNotEmpty()) {
                entity("IFCRELAGGREGATES(${guid("building-storeys")},$,$,$,#$building,(${floorEntity.values.joinToString(",") { "#$it" }}))")
            }

            createSpaces()
            createProducts(context)
            createOpenings()
            createContainment()

            val stamp = Instant.now().toString().substringBefore('.')
            val safeName = plan.title.replace(Regex("[^A-Za-z0-9_-]+"), "_").ifBlank { "manzili_hai" }.take(48)
            return buildString {
                appendLine("ISO-10303-21;")
                appendLine("HEADER;")
                appendLine("FILE_DESCRIPTION(('ViewDefinition [ReferenceView_V1.2]'),'2;1');")
                appendLine("FILE_NAME('$safeName.ifc','$stamp',('Manzili HAI'),('Manzili HAI'),'Manzili HAI 0.22','Manzili HAI','');")
                appendLine("FILE_SCHEMA(('IFC4'));")
                appendLine("ENDSEC;")
                appendLine("DATA;")
                lines.forEach { appendLine(it) }
                appendLine("ENDSEC;")
                appendLine("END-ISO-10303-21;")
            }
        }

        private fun createSpaces() {
            val byFloor = scene.rooms.groupBy { it.floorId }
            byFloor.forEach { (floorId, rooms) ->
                val storey = floorEntity[floorId] ?: return@forEach
                val placement = floorPlacement[floorId] ?: return@forEach
                val spaces = rooms.map { room ->
                    val center = if (room.polygon.isEmpty()) Semantic3DEngine.Vec3(0.0, 0.0, 0.0) else Semantic3DEngine.Vec3(
                        room.polygon.map { it.x }.average(),
                        room.polygon.map { it.y }.average(),
                        room.polygon.map { it.z }.average()
                    )
                    val point = entity("IFCCARTESIANPOINT((${f(center.x)},${f(center.y)},${f(center.z)}))")
                    val axis = entity("IFCAXIS2PLACEMENT3D(#$point,$,$)")
                    val local = entity("IFCLOCALPLACEMENT(#$placement,#$axis)")
                    entity("IFCSPACE(${guid("space-${room.floorId}-${room.id}")},$,${str(room.name)},$,${str(room.type)},#$local,$,$,.ELEMENT.,.INTERNAL.,$)")
                }
                if (spaces.isNotEmpty()) {
                    entity("IFCRELAGGREGATES(${guid("spaces-$floorId")},$,$,$,#$storey,(${spaces.joinToString(",") { "#$it" }}))")
                }
            }
        }

        private fun createProducts(context: Int) {
            val groups = scene.meshes.groupBy { Triple(it.floorId, it.kind, it.sourceId) }
            groups.forEach { (key, meshes) ->
                val floorId = key.first
                val kind = key.second
                val sourceId = key.third
                val placement = floorPlacement[floorId] ?: return@forEach
                val breps = meshes.mapNotNull { mesh -> brep(mesh) }
                if (breps.isEmpty()) return@forEach
                val shape = entity("IFCSHAPEREPRESENTATION(#$context,'Body','Brep',(${breps.joinToString(",") { "#$it" }}))")
                val pds = entity("IFCPRODUCTDEFINITIONSHAPE($,$,(#$shape))")
                val product = when (kind) {
                    "wall" -> entity("IFCWALL(${guid("wall-$floorId-$sourceId")},$,${str("Wall $sourceId")},$,$,#$placement,#$pds,$,.NOTDEFINED.)")
                    "slab" -> entity("IFCSLAB(${guid("slab-$floorId-$sourceId")},$,${str("Slab $sourceId")},$,$,#$placement,#$pds,$,.FLOOR.)")
                    else -> entity("IFCBUILDINGELEMENTPROXY(${guid("proxy-$floorId-$sourceId")},$,${str(meshes.first().name)},$,$,#$placement,#$pds,$,.NOTDEFINED.)")
                }
                productBySource[floorId to sourceId] = product
            }
        }

        private fun createOpenings() {
            scene.openings.filter { !it.wallId.isNullOrBlank() }.forEach { opening ->
                val wall = productBySource[opening.floorId to opening.wallId!!] ?: return@forEach
                val storeyPlace = floorPlacement[opening.floorId] ?: return@forEach
                val point = entity("IFCCARTESIANPOINT((${f(opening.center.x)},${f(opening.center.y)},${f(opening.center.z)}))")
                val axis = entity("IFCAXIS2PLACEMENT3D(#$point,$,$)")
                val placement = entity("IFCLOCALPLACEMENT(#$storeyPlace,#$axis)")
                val openingEntity = entity(
                    "IFCOPENINGELEMENT(${guid("opening-${opening.floorId}-${opening.id}")},$,${str("${opening.type} ${opening.id}")},$,$,#$placement,$,$,.OPENING.)"
                )
                entity("IFCRELVOIDSELEMENT(${guid("void-${opening.floorId}-${opening.id}")},$,$,$,#$wall,#$openingEntity)")
            }
        }

        private fun createContainment() {
            scene.floorIds.forEach { floorId ->
                val storey = floorEntity[floorId] ?: return@forEach
                val products = productBySource.filterKeys { it.first == floorId }.values.distinct()
                if (products.isNotEmpty()) {
                    entity("IFCRELCONTAINEDINSPATIALSTRUCTURE(${guid("contain-$floorId")},$,$,$,(${products.joinToString(",") { "#$it" }}),#$storey)")
                }
            }
        }

        private fun brep(mesh: Semantic3DEngine.Mesh): Int? {
            if (mesh.vertices.size < 4 || mesh.faces.isEmpty()) return null
            val points = mesh.vertices.map { v -> entity("IFCCARTESIANPOINT((${f(v.x)},${f(v.y)},${f(v.z)}))") }
            val faces = mesh.faces.mapNotNull { face ->
                if (face.indices.size < 3 || face.indices.any { it !in points.indices }) return@mapNotNull null
                val loop = entity("IFCPOLYLOOP((${face.indices.joinToString(",") { "#${points[it]}" }}))")
                val bound = entity("IFCFACEOUTERBOUND(#$loop,.T.)")
                entity("IFCFACE((#$bound))")
            }
            if (faces.size < 4) return null
            val shell = entity("IFCCLOSEDSHELL((${faces.joinToString(",") { "#$it" }}))")
            return entity("IFCFACETEDBREP(#$shell)")
        }

        private fun floorElevation(floorId: String, fallbackIndex: Int): Double =
            plan.floors.firstOrNull { it.id == floorId }?.elevationM ?: if (fallbackIndex == 0) 0.0 else fallbackIndex * 3.0

        private fun floorName(floorId: String, fallbackIndex: Int): String =
            plan.floors.firstOrNull { it.id == floorId }?.name ?: if (fallbackIndex == 0) "Ground Floor" else "Floor $fallbackIndex"

        private fun entity(body: String): Int {
            val id = nextId++
            lines += "#$id=$body;"
            return id
        }

        private fun guid(seed: String): String = "'${ifcGuid("${plan.title}|${plan.revision}|$seed")}'"
    }

    private fun ifcGuid(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        var value = BigInteger(1, digest)
        val base = BigInteger.valueOf(64)
        val chars = CharArray(22) { '0' }
        for (i in chars.lastIndex downTo 0) {
            val parts = value.divideAndRemainder(base)
            chars[i] = IFC64[parts[1].toInt()]
            value = parts[0]
        }
        return String(chars)
    }

    private fun f(value: Double): String {
        val text = String.format(Locale.US, "%.6f", value)
        return if ('.' in text) text.trimEnd('0').let { if (it.endsWith('.')) "${it}0" else it } else "$text.0"
    }

    private fun str(value: String): String {
        if (value.isEmpty()) return "''"
        val ascii = value.all { it.code in 32..126 && it != '\\' }
        if (ascii) return "'${value.replace("'", "''")}'"
        val hex = value.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return "'\\X2\\$hex\\X0\\'"
    }
}
