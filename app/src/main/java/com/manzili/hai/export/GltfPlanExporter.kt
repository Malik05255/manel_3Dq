package com.manzili.hai.export

import com.manzili.hai.BuildConfig
import com.manzili.hai.engine.ProductionSceneEngine
import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

object GltfPlanExporter {
    private data class Slice(val offset:Int,val length:Int)
    private data class Built(val json:JSONObject,val bin:ByteArray)

    fun renderGltf(plan:FloorPlan):String {
        val built=build(plan)
        built.json.getJSONArray("buffers").getJSONObject(0).put("uri","data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(built.bin)}")
        return built.json.toString(2)
    }

    fun renderGlb(plan:FloorPlan):ByteArray {
        val built=build(plan)
        val json=built.json.toString().toByteArray(Charsets.UTF_8).pad4(0x20)
        val bin=built.bin.pad4(0x00)
        val total=12+8+json.size+8+bin.size
        val out=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67);out.putInt(2);out.putInt(total)
        out.putInt(json.size);out.putInt(0x4E4F534A);out.put(json)
        out.putInt(bin.size);out.putInt(0x004E4942);out.put(bin)
        return out.array()
    }

    private fun build(plan:FloorPlan):Built {
        val scene=ProductionSceneEngine.build(plan)
        val bin=ByteArrayOutputStream();val bufferViews=JSONArray();val accessors=JSONArray();val meshesJson=JSONArray();val nodes=JSONArray();val materials=materialLibrary()
        scene.meshes.forEach{mesh->
            if(mesh.vertices.isEmpty())return@forEach
            val converted=mesh.vertices.map{v->floatArrayOf(v.x.toFloat(),v.z.toFloat(),(-v.y).toFloat())}
            val triangles=triangulate(mesh.faces);if(triangles.isEmpty())return@forEach
            val posBytes=ByteBuffer.allocate(converted.size*12).order(ByteOrder.LITTLE_ENDIAN);converted.forEach{p->p.forEach(posBytes::putFloat)}
            val posSlice=appendAligned(bin,posBytes.array());val posView=bufferViews.length();bufferViews.put(JSONObject().put("buffer",0).put("byteOffset",posSlice.offset).put("byteLength",posSlice.length).put("target",34962))
            val posAccessor=accessors.length();val xs=converted.map{it[0]};val ys=converted.map{it[1]};val zs=converted.map{it[2]}
            accessors.put(JSONObject().put("bufferView",posView).put("componentType",5126).put("count",converted.size).put("type","VEC3").put("min",JSONArray(listOf(xs.minOrNull()?:0f,ys.minOrNull()?:0f,zs.minOrNull()?:0f))).put("max",JSONArray(listOf(xs.maxOrNull()?:0f,ys.maxOrNull()?:0f,zs.maxOrNull()?:0f))))
            val normals=smoothNormals(converted,triangles);val normalBytes=ByteBuffer.allocate(normals.size*12).order(ByteOrder.LITTLE_ENDIAN);normals.forEach{n->n.forEach(normalBytes::putFloat)}
            val normalSlice=appendAligned(bin,normalBytes.array());val normalView=bufferViews.length();bufferViews.put(JSONObject().put("buffer",0).put("byteOffset",normalSlice.offset).put("byteLength",normalSlice.length).put("target",34962))
            val normalAccessor=accessors.length();accessors.put(JSONObject().put("bufferView",normalView).put("componentType",5126).put("count",normals.size).put("type","VEC3"))
            val indexBytes=ByteBuffer.allocate(triangles.size*4).order(ByteOrder.LITTLE_ENDIAN);triangles.forEach(indexBytes::putInt)
            val idxSlice=appendAligned(bin,indexBytes.array());val idxView=bufferViews.length();bufferViews.put(JSONObject().put("buffer",0).put("byteOffset",idxSlice.offset).put("byteLength",idxSlice.length).put("target",34963))
            val idxAccessor=accessors.length();accessors.put(JSONObject().put("bufferView",idxView).put("componentType",5125).put("count",triangles.size).put("type","SCALAR"))
            val primitive=JSONObject().put("attributes",JSONObject().put("POSITION",posAccessor).put("NORMAL",normalAccessor)).put("indices",idxAccessor).put("material",materialIndex(mesh.kind)).put("mode",4)
            val meshIndex=meshesJson.length();meshesJson.put(JSONObject().put("name",mesh.name).put("primitives",JSONArray().put(primitive)))
            nodes.put(JSONObject().put("name",mesh.id).put("mesh",meshIndex).put("extras",JSONObject().put("kind",mesh.kind).put("floorId",mesh.floorId).put("sourceId",mesh.sourceId)))
        }
        val rootNodes=JSONArray((0 until nodes.length()).toList())
        val json=JSONObject()
            .put("asset",JSONObject().put("version","2.0").put("generator","Manzili HAI ${BuildConfig.VERSION_NAME}"))
            .put("extensionsUsed",JSONArray(listOf("KHR_materials_transmission","KHR_materials_ior","KHR_materials_clearcoat")))
            .put("scene",0).put("scenes",JSONArray().put(JSONObject().put("nodes",rootNodes).put("name",scene.title)))
            .put("nodes",nodes).put("meshes",meshesJson).put("materials",materials).put("bufferViews",bufferViews).put("accessors",accessors)
            .put("buffers",JSONArray().put(JSONObject().put("byteLength",bin.size())))
            .put("extras",JSONObject().put("metricReady",scene.metricReady).put("units",scene.units).put("pbrReady",true).put("ultraPbr",true).put("geometrySource","Geometry V3").put("facadeGeometry",true).put("siteContext",true).put("productionScene",true))
        return Built(json,bin.toByteArray())
    }

    private fun smoothNormals(vertices:List<FloatArray>,triangles:IntArray):List<FloatArray>{
        val sums=Array(vertices.size){FloatArray(3)};var i=0
        while(i+2<triangles.size){val ia=triangles[i];val ib=triangles[i+1];val ic=triangles[i+2];i+=3;if(ia !in vertices.indices||ib !in vertices.indices||ic !in vertices.indices)continue
            val a=vertices[ia];val b=vertices[ib];val c=vertices[ic];val abx=b[0]-a[0];val aby=b[1]-a[1];val abz=b[2]-a[2];val acx=c[0]-a[0];val acy=c[1]-a[1];val acz=c[2]-a[2]
            val nx=aby*acz-abz*acy;val ny=abz*acx-abx*acz;val nz=abx*acy-aby*acx
            for(idx in intArrayOf(ia,ib,ic)){sums[idx][0]+=nx;sums[idx][1]+=ny;sums[idx][2]+=nz}
        }
        return sums.map{n->val length=sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(length>0.000001f)floatArrayOf(n[0]/length,n[1]/length,n[2]/length)else floatArrayOf(0f,1f,0f)}
    }

    private fun materialLibrary():JSONArray=JSONArray()
        .put(material("Saudi Plaster",0.90,0.87,0.80,1.0,0.58,clearcoat=0.06))
        .put(material("Concrete Slab",0.58,0.59,0.57,1.0,0.82))
        .put(material("Structure",0.48,0.38,0.29,1.0,0.76))
        .put(material("Timber Door",0.32,0.17,0.08,1.0,0.34,clearcoat=0.18))
        .put(material("Architectural Glass",0.20,0.46,0.62,1.0,0.035,transmission=0.92,ior=1.45,clearcoat=0.12,doubleSided=true))
        .put(material("Roof / Parapet",0.62,0.59,0.54,1.0,0.72))
        .put(material("Architectural Accent",0.68,0.64,0.57,1.0,0.52,clearcoat=0.08))
        .put(material("Saudi Limestone",0.76,0.69,0.57,1.0,0.56,clearcoat=0.04))
        .put(material("Shade Metal",0.20,0.21,0.20,1.0,0.22,metallic=0.72,clearcoat=0.12))
        .put(material("Hijazi Screen",0.42,0.27,0.15,1.0,0.40,metallic=0.10,clearcoat=0.08))
        .put(material("Warm Site Ground",0.50,0.45,0.37,1.0,0.93))
        .put(material("Saudi Paving",0.66,0.62,0.54,1.0,0.68))
        .put(material("Parking Concrete",0.43,0.44,0.43,1.0,0.84))
        .put(material("Planting Soil",0.24,0.21,0.13,1.0,0.98))

    private fun material(
        name:String,r:Double,g:Double,b:Double,a:Double,roughness:Double,
        metallic:Double=0.0,transmission:Double=0.0,ior:Double=1.5,clearcoat:Double=0.0,doubleSided:Boolean=false
    ):JSONObject=JSONObject()
        .put("name",name)
        .put("pbrMetallicRoughness",JSONObject().put("baseColorFactor",JSONArray(listOf(r,g,b,a))).put("metallicFactor",metallic).put("roughnessFactor",roughness))
        .apply {
            val extensions=JSONObject()
            if(transmission>0.0) extensions.put("KHR_materials_transmission",JSONObject().put("transmissionFactor",transmission.coerceIn(0.0,1.0)))
            if(transmission>0.0) extensions.put("KHR_materials_ior",JSONObject().put("ior",ior.coerceIn(1.0,2.5)))
            if(clearcoat>0.0) extensions.put("KHR_materials_clearcoat",JSONObject().put("clearcoatFactor",clearcoat.coerceIn(0.0,1.0)).put("clearcoatRoughnessFactor",(roughness*.35).coerceIn(0.0,1.0)))
            if(extensions.length()>0) put("extensions",extensions)
            if(doubleSided) put("doubleSided",true)
        }

    private fun materialIndex(kind:String):Int=when(kind){
        "wall"->0;"slab"->1;"structural"->2;"door"->3;"window"->4;"roof","saudi-parapet"->5
        "facade-stone"->7;"facade-shade","facade-frame"->8;"facade-screen"->9;"facade-accent"->6
        "site-ground"->10;"site-paving"->11;"site-parking"->12;"site-planting"->13;else->6
    }

    private fun triangulate(faces:List<Semantic3DEngine.Face>):IntArray{val out=mutableListOf<Int>();faces.forEach{face->val idx=face.indices;if(idx.size>=3)for(i in 1 until idx.size-1){out+=idx[0];out+=idx[i];out+=idx[i+1]}};return out.toIntArray()}
    private fun appendAligned(out:ByteArrayOutputStream,bytes:ByteArray):Slice{while(out.size()%4!=0)out.write(0);val offset=out.size();out.write(bytes);return Slice(offset,bytes.size)}
    private fun ByteArray.pad4(fill:Int):ByteArray{val padded=(size+3)and -4;if(padded==size)return this;return copyOf(padded).also{a->for(i in size until padded)a[i]=fill.toByte()}}
}
