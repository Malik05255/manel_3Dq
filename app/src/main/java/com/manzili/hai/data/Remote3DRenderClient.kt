package com.manzili.hai.data

import android.content.Context
import com.manzili.hai.model.FloorPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Requests a canonical Blender-built GLB from the backend. The phone is a viewer, not the mesh factory. */
class Remote3DRenderClient(context: Context) {
    private val settings = HaiSettings(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(7, TimeUnit.MINUTES)
        .build()

    data class RenderedModel(
        val bytes: ByteArray,
        val renderer: String
    )

    suspend fun render(plan: FloorPlan): Result<RenderedModel> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.backendConfigured) { "خادم HAI غير مربوط" }
            val payload = JSONObject()
                .put("project_id", JSONObject.NULL)
                .put("plan", PlanStorageCodec.encode(plan))
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${settings.backendBaseUrl.trimEnd('/')}/v1/render-3d")
                .header("Authorization", "Bearer ${settings.backendAuthToken}")
                .header("Accept", "model/gltf-binary")
                .post(payload)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().take(800)
                    error("3D server ${response.code}: $detail")
                }
                val bytes = response.body?.bytes() ?: error("خادم 3D أعاد ملفًا فارغًا")
                require(bytes.size >= 20 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() && bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
                    "الملف المستلم ليس GLB صالحًا"
                }
                RenderedModel(
                    bytes = bytes,
                    renderer = response.header("X-Manzili-Renderer").orEmpty().ifBlank { "server-blender" }
                )
            }
        }
    }
}
