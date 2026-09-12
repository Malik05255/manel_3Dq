package com.manzili.hai.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.manzili.hai.data.HaiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One simple AI entry point for HAI.
 * Priority: strongest selected model -> weaker selected models -> local Gemini Nano.
 * API keys are supplied by the user and kept encrypted on-device; no provider key is bundled in the APK.
 */
class AiProviderRouter(private val context: Context) {
    enum class Provider { OPENROUTER, GOOGLE, NANO }

    data class ModelOption(
        val provider: Provider,
        val id: String,
        val name: String,
        val score: Int,
        val vision: Boolean = true
    )

    data class NanoState(val available: Boolean, val downloadable: Boolean, val label: String)

    private val settings = HaiSettings(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun discoverOpenRouterFree(apiKey: String): List<ModelOption> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("X-Title", "Manzili HAI")
            .get().build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("OpenRouter ${res.code}: ${body.take(180)}")
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val models = mutableListOf<ModelOption>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val pricing = item.optJSONObject("pricing")
                val free = id == "openrouter/free" || id.endsWith(":free") ||
                    (zeroPrice(pricing?.optString("prompt")) && zeroPrice(pricing?.optString("completion")))
                if (!free) continue
                val architecture = item.optJSONObject("architecture")
                val inputs = architecture?.optJSONArray("input_modalities")
                val vision = inputs?.let { arr -> (0 until arr.length()).any { arr.optString(it).equals("image", true) } } ?: true
                models += ModelOption(Provider.OPENROUTER, id, item.optString("name", id), AiRoutingPolicy.strength(id), vision)
            }
            if (models.none { it.id == "openrouter/free" }) {
                models += ModelOption(Provider.OPENROUTER, "openrouter/free", "OpenRouter Free Router", AiRoutingPolicy.strength("openrouter/free"), true)
            }
            models.distinctBy { it.id }.sortedByDescending { it.score }
        }
    }

    /** Gemini models.list has no free/billing flag, so only Flash/Lite free-tier candidates are surfaced. */
    suspend fun discoverGoogleFreeCandidates(apiKey: String): List<ModelOption> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=${apiKey.trim()}")
            .get().build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Google ${res.code}: ${body.take(180)}")
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val all = mutableListOf<ModelOption>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("name").removePrefix("models/")
                val methods = item.optJSONArray("supportedGenerationMethods")
                val canGenerate = methods?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } == true
                if (!canGenerate || !id.startsWith("gemini", true)) continue
                if (!id.contains("flash", true) && !id.contains("lite", true)) continue
                all += ModelOption(Provider.GOOGLE, id, item.optString("displayName", id), AiRoutingPolicy.strength(id), true)
            }
            all.distinctBy { it.id }.sortedByDescending { it.score }
        }
    }

    suspend fun nanoState(): NanoState = runCatching {
        when (Generation.getClient().checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoState(true, false, "Gemini Nano جاهز")
            FeatureStatus.DOWNLOADABLE -> NanoState(false, true, "Gemini Nano متاح للتنزيل")
            FeatureStatus.DOWNLOADING -> NanoState(false, true, "Gemini Nano قيد التنزيل")
            else -> NanoState(false, false, "Gemini Nano غير مدعوم على هذا الجهاز")
        }
    }.getOrElse { NanoState(false, false, "Gemini Nano غير متاح") }

    suspend fun prepareNano(): NanoState {
        val model = Generation.getClient()
        return when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoState(true, false, "Gemini Nano جاهز")
            FeatureStatus.DOWNLOADABLE -> {
                model.download().collect { }
                nanoState()
            }
            else -> nanoState()
        }
    }

    suspend fun complete(system: String, user: String, imageBase64: String? = null): String = withContext(Dispatchers.IO) {
        val candidates = configuredCandidates(imageBase64 != null)
        if (candidates.isEmpty()) error("لا يوجد نموذج ذكاء اصطناعي جاهز. أضف OpenRouter أو Google، أو استخدم Gemini Nano على جهاز مدعوم.")

        val failures = mutableListOf<String>()
        val ordered = if (settings.smartRoutingEnabled) candidates.sortedByDescending { it.score } else candidates.take(1)
        for (candidate in ordered) {
            val result = runCatching {
                when (candidate.provider) {
                    Provider.OPENROUTER -> callOpenRouter(candidate.id, system, user, imageBase64)
                    Provider.GOOGLE -> callGoogle(candidate.id, system, user, imageBase64)
                    Provider.NANO -> callNano(system, user, imageBase64)
                }
            }
            val text = result.getOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) {
                settings.lastWorkingProvider = candidate.provider.name
                settings.lastWorkingModel = candidate.id
                return@withContext text
            }
            failures += "${candidate.name}: ${result.exceptionOrNull()?.message.orEmpty().take(120)}"
        }
        error("تعذر تشغيل النماذج المتاحة. ${failures.take(3).joinToString(" | ")}")
    }

    fun configuredCandidates(needsVision: Boolean = false): List<ModelOption> {
        val out = mutableListOf<ModelOption>()
        if (settings.openRouterApiKey.isNotBlank()) {
            settings.openRouterModels.forEach { id -> out += ModelOption(Provider.OPENROUTER, id, id, AiRoutingPolicy.strength(id), true) }
        }
        if (settings.googleApiKey.isNotBlank()) {
            settings.googleModels.forEach { id -> out += ModelOption(Provider.GOOGLE, id, id, AiRoutingPolicy.strength(id), true) }
        }
        if (settings.nanoEnabled) out += ModelOption(Provider.NANO, "gemini-nano", "Gemini Nano", AiRoutingPolicy.strength("gemini-nano"), true)
        return out.filter { !needsVision || it.vision }.distinctBy { "${it.provider}:${it.id}" }
    }

    private fun callOpenRouter(model: String, system: String, user: String, imageBase64: String?): String {
        val userContent: Any = if (imageBase64.isNullOrBlank()) user else JSONArray()
            .put(JSONObject().put("type", "text").put("text", user))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.08)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", userContent)))
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${settings.openRouterApiKey}")
            .header("Content-Type", "application/json")
            .header("X-Title", "Manzili HAI")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("OpenRouter ${res.code}: ${body.take(220)}")
            return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun callGoogle(model: String, system: String, user: String, imageBase64: String?): String {
        val parts = JSONArray().put(JSONObject().put("text", user))
        if (!imageBase64.isNullOrBlank()) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", imageBase64)))
        }
        val payload = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.08))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model.removePrefix("models/")}:generateContent?key=${settings.googleApiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("Google ${res.code}: ${body.take(220)}")
            val partsOut = JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            return buildString { for (i in 0 until partsOut.length()) append(partsOut.optJSONObject(i)?.optString("text").orEmpty()) }
        }
    }

    private suspend fun callNano(system: String, user: String, imageBase64: String?): String {
        val model = Generation.getClient()
        if (model.checkStatus() != FeatureStatus.AVAILABLE) throw IOException("Gemini Nano غير جاهز على هذا الجهاز")
        val prompt = "$system\n\n$user"
        val response = if (imageBase64.isNullOrBlank()) {
            model.generateContent(prompt)
        } else {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("تعذر تجهيز الصورة للنموذج المحلي")
            model.generateContent(generateContentRequest(ImagePart(bitmap), TextPart(prompt)) { temperature = 0.08f })
        }
        return response.candidates.firstOrNull()?.text.orEmpty().ifBlank { throw IOException("Gemini Nano أعاد ردًا فارغًا") }
    }

    private fun zeroPrice(value: String?): Boolean = value?.toDoubleOrNull()?.let { it == 0.0 } == true

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}

/** Pure policy so ranking/fallback behavior stays regression-testable. */
object AiRoutingPolicy {
    fun strength(id: String): Int {
        val s = id.lowercase()
        var score = 100
        when {
            "ultra" in s -> score += 55
            "pro" in s -> score += 45
            "max" in s -> score += 40
            "reason" in s || "r1" in s -> score += 35
            "70b" in s || "72b" in s -> score += 30
            "32b" in s || "34b" in s -> score += 24
            "flash" in s -> score += 18
            "8b" in s || "7b" in s -> score += 8
            "mini" in s -> score -= 8
            "nano" in s -> score -= 25
        }
        if (s == "openrouter/free") score = 60
        return score
    }

    fun retryableHttp(code: Int): Boolean = code == 402 || code == 408 || code == 409 || code == 429 || code >= 500
}
