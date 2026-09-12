package com.manzili.hai.export

import com.manzili.hai.engine.Architectural3DEnhancementEngine
import com.manzili.hai.engine.SaudiPhotorealisticRenderEngine
import com.manzili.hai.engine.SaudiSitePresentationEngine
import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.model.FloorPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Runtime GLB for SceneView: Geometry V3 + embedded PNG textures + UVs + site presentation. */
object TexturedPbrGlbExporter {
    private data class Slice(val offset:Int,val length:Int)

    fun render(plan:FloorPlan):ByteArray {
        val base=Architectural3DEnhancementEngine.build(plan)
        val site=SaudiSitePresentationEngine.build(plan,base)
        val scene=base.copy(meshes=base.meshes+site.meshes)
        val profile=SaudiPhotorealisticRenderEngine.build(plan)
        val bin=ByteArrayOutputStream();val views=JSONArray();val accessors=JSONArray();val meshes=JSONArray();val nodes=JSONArray()
        scene.meshes.forEach{mesh->
            if(mesh.vertices.isEmpty())return@forEach
            val v=mesh.vertices.map{p->floatArrayOf(p.x.toFloat(),p.z.toFloat(),(-p.y).toFloat())}
            val tri=triangulate(mesh.faces);if(tri.isEmpty())return@forEach
            val pos=writeFloat3(bin,views,v);val normal=writeFloat3(bin,views,normals(v,tri));val uv=writeFloat2(bin,views,uvs(v,profile.textureScaleM));val idx=writeIndices(bin,views,tri)
            val pa=accessors.length();accessors.put(positionAccessor(pos,v))
            val na=accessors.length();accessors.put(JSONObject().put("bufferView",normal).put("componentType",5126).put("count",v.size).put("type","VEC3"))
            val ua=accessors.length();accessors.put(JSONObject().put("bufferView",uv).put("componentType",5126).put("count",v.size).put("type","VEC2"))
            val ia=accessors.length();accessors.put(JSONObject().put("bufferView",idx).put("componentType",5125).put("count",tri.size).put("type","SCALAR"))
            val primitive=JSONObject().put("attributes",JSONObject().put("POSITION",pa).put("NORMAL",na).put("TEXCOORD_0",ua)).put("indices",ia).put("material",materialIndex(mesh.kind)).put("mode",4)
            val mi=meshes.length();meshes.put(JSONObject().put("name",mesh.name).put("primitives",JSONArray().put(primitive)))
            nodes.put(JSONObject().put("name",mesh.id).put("mesh",mi).put("extras",JSONObject().put("kind",mesh.kind).put("floorId",mesh.floorId).put("sourceId",mesh.sourceId)))
        }
        val images=JSONArray();val textures=JSONArray()
        PbrTextureLibrary.textures().forEachIndexed{i,t->
            val s=append(bin,t.bytes);val vi=views.length();views.put(JSONObject().put("buffer",0).put("byteOffset",s.offset).put("byteLength",s.length))
            images.put(JSONObject().put("name",t.name).put("mimeType",t.mimeType).put("bufferView",vi));textures.put(JSONObject().put("sampler",0).put("source",i))
        }
        val json=JSONObject().put("asset",JSONObject().put("version","2.0").put("generator","Manzili HAI 0.60 textured PBR"))
            .put("scene",0).put("scenes",JSONArray().put(JSONObject().put("nodes",JSONArray((0 until nodes.length()).toList())).put("name",scene.title)))
            .put("nodes",nodes).put("meshes",meshes).put("materials",materials()).put("images",images).put("textures",textures)
            .put("samplers",JSONArray().put(JSONObject().put("magFilter",9729).put("minFilter",9987).put("wrapS",10497).put("wrapT",10497)))
            .put("bufferViews",views).put("accessors",accessors).put("buffers",JSONArray().put(JSONObject().put("byteLength",bin.size())))
            .put("extras",JSONObject().put("geometrySource","Geometry V3").put("embeddedTextures",4).put("renderProfile",profile.qualityLabel).put("sitePresentationMeshes",site.meshes.size))
        return glb(json,bin.toByteArray())
    }

    private fun materials()=JSONArray()
        .put(mat("Saudi plaster",.93,.91,.86,1.0,.78,0)).put(mat("Concrete",.70,.69,.66,1.0,.90,3))
        .put(mat("Structure",.58,.51,.43,1.0,.84,1)).put(mat("Timber",.62,.42,.25,1.0,.48,2))
        .put(mat("Glass",.36,.64,.73,.34,.08,null,true)).put(mat("Roof",.72,.70,.66,1.0,.86,3))
        .put(mat("Accent",.78,.73,.65,1.0,.68,0)).put(mat("Limestone",.84,.78,.67,1.0,.72,1))
        .put(mat("Metal shade",.20,.21,.20,1.0,.32,null)).put(mat("Hijazi screen",.50,.34,.21,1.0,.50,2))
        .put(mat("Site paving",.58,.57,.54,1.0,.94,3)).put(mat("Landscape",.26,.43,.24,1.0,.96,null))

    private fun mat(name:String,r:Double,g:Double,b:Double,a:Double,rough:Double,texture:Int?,blend:Boolean=false):JSONObject{
        val p=JSONObject().put("baseColorFactor",JSONArray(listOf(r,g,b,a))).put("metallicFactor",0.0).put("roughnessFactor",rough)
        texture?.let{p.put("baseColorTexture",JSONObject().put("index",it))}
        return JSONObject().put("name",name).put("pbrMetallicRoughness",p).apply{if(blend){put("alphaMode","BLEND");put("doubleSided",true)}}
    }
    private fun materialIndex(k:String)=when(k){"wall"->0;"slab"->1;"structural"->2;"door"->3;"window"->4;"roof","saudi-parapet"->5;"facade-stone"->7;"facade-shade","facade-frame"->8;"facade-screen"->9;"site-ground"->10;"landscape"->11;else->6}

    private fun writeFloat3(out:ByteArrayOutputStream,views:JSONArray,data:List<FloatArray>):Int{val b=ByteBuffer.allocate(data.size*12).order(ByteOrder.LITTLE_ENDIAN);data.forEach{it.forEach(b::putFloat)};return view(out,views,b.array(),34962)}
    private fun writeFloat2(out:ByteArrayOutputStream,views:JSONArray,data:List<FloatArray>):Int{val b=ByteBuffer.allocate(data.size*8).order(ByteOrder.LITTLE_ENDIAN);data.forEach{it.forEach(b::putFloat)};return view(out,views,b.array(),34962)}
    private fun writeIndices(out:ByteArrayOutputStream,views:JSONArray,data:IntArray):Int{val b=ByteBuffer.allocate(data.size*4).order(ByteOrder.LITTLE_ENDIAN);data.forEach(b::putInt);return view(out,views,b.array(),34963)}
    private fun view(out:ByteArrayOutputStream,views:JSONArray,bytes:ByteArray,target:Int):Int{val s=append(out,bytes);val i=views.length();views.put(JSONObject().put("buffer",0).put("byteOffset",s.offset).put("byteLength",s.length).put("target",target));return i}
    private fun positionAccessor(view:Int,v:List<FloatArray>):JSONObject{val x=v.map{it[0]};val y=v.map{it[1]};val z=v.map{it[2]};return JSONObject().put("bufferView",view).put("componentType",5126).put("count",v.size).put("type","VEC3").put("min",JSONArray(listOf(x.minOrNull()?:0f,y.minOrNull()?:0f,z.minOrNull()?:0f))).put("max",JSONArray(listOf(x.maxOrNull()?:0f,y.maxOrNull()?:0f,z.maxOrNull()?:0f)))}
    private fun uvs(v:List<FloatArray>,scale:Float):List<FloatArray>{if(v.isEmpty())return emptyList();val minX=v.minOf{it[0]};val maxX=v.maxOf{it[0]};val minY=v.minOf{it[1]};val maxY=v.maxOf{it[1]};val minZ=v.minOf{it[2]};val maxZ=v.maxOf{it[2]};val xs=maxX-minX;val ys=maxY-minY;val zs=maxZ-minZ;val s=scale.coerceAtLeast(.25f);return v.map{p->if(ys<.08f&&xs>.08f&&zs>.08f)floatArrayOf((p[0]-minX)/s,(p[2]-minZ)/s)else if(xs>=zs)floatArrayOf((p[0]-minX)/s,(p[1]-minY)/s)else floatArrayOf((p[2]-minZ)/s,(p[1]-minY)/s)}}
    private fun normals(v:List<FloatArray>,t:IntArray):List<FloatArray>{val s=Array(v.size){FloatArray(3)};var i=0;while(i+2<t.size){val a=t[i++];val b=t[i++];val c=t[i++];if(a !in v.indices||b !in v.indices||c !in v.indices)continue;val p=v[a];val q=v[b];val r=v[c];val ux=q[0]-p[0];val uy=q[1]-p[1];val uz=q[2]-p[2];val vx=r[0]-p[0];val vy=r[1]-p[1];val vz=r[2]-p[2];val nx=uy*vz-uz*vy;val ny=uz*vx-ux*vz;val nz=ux*vy-uy*vx;for(k in intArrayOf(a,b,c)){s[k][0]+=nx;s[k][1]+=ny;s[k][2]+=nz}};return s.map{n->val l=sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);if(l>.000001f)floatArrayOf(n[0]/l,n[1]/l,n[2]/l)else floatArrayOf(0f,1f,0f)}}
    private fun triangulate(f:List<Semantic3DEngine.Face>):IntArray{val o=mutableListOf<Int>();f.forEach{x->if(x.indices.size>=3)for(i in 1 until x.indices.size-1){o+=x.indices[0];o+=x.indices[i];o+=x.indices[i+1]}};return o.toIntArray()}
    private fun append(out:ByteArrayOutputStream,b:ByteArray):Slice{while(out.size()%4!=0)out.write(0);val o=out.size();out.write(b);return Slice(o,b.size)}
    private fun glb(j:JSONObject,b0:ByteArray):ByteArray{val j0=j.toString().toByteArray().pad4(0x20);val b=b0.pad4(0);val total=12+8+j0.size+8+b.size;return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply{putInt(0x46546C67);putInt(2);putInt(total);putInt(j0.size);putInt(0x4E4F534A);put(j0);putInt(b.size);putInt(0x004E4942);put(b)}.array()}
    private fun ByteArray.pad4(fill:Int):ByteArray{val n=(size+3)and -4;if(n==size)return this;return copyOf(n).also{a->for(i in size until n)a[i]=fill.toByte()}}
}
