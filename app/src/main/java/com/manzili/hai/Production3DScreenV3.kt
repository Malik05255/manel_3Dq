package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.data.Remote3DRenderClient
import com.manzili.hai.engine.PbrSceneFramingEngine
import com.manzili.hai.engine.PlanVerificationEngine
import com.manzili.hai.engine.ProductionSceneEngine
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.engine.SaudiVisualRenderEngine
import com.manzili.hai.model.FloorPlan
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import java.nio.ByteBuffer

private data class Server3DState(
    val loading: Boolean = true,
    val bytes: ByteArray? = null,
    val renderer: String = "server",
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Production3DScreenV3(nav: NavHostController, plan: FloorPlan?) {
    if (plan == null) {
        Scaffold(
            containerColor = H360Ivory,
            topBar = {
                TopAppBar(
                    title = { Text("المجسم", fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Rounded.ArrowBack, "رجوع")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = H360Paper)
                )
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ViewInAr, null, tint = H360Muted, modifier = Modifier.size(48.dp))
            }
        }
        return
    }

    var compatibilityMode by rememberSaveable { mutableStateOf(false) }
    if (compatibilityMode) {
        Production3DScreenV2(nav, plan)
        return
    }

    val context = LocalContext.current
    val verification = remember(plan) { PlanVerificationEngine.inspect(plan) }
    val renderAllowed = !verification.blocking && verification.scaleConfidence >= 65 && verification.readingConfidence >= 65
    val semanticScene = remember(plan) { ProductionSceneEngine.build(plan) }
    val visual = remember(plan) { SaudiVisualRenderEngine.build(plan, SaudiVisualRenderEngine.Quality.ULTRA) }
    val frame = remember(semanticScene) { PbrSceneFramingEngine.frame(semanticScene) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val lightIntensity = (132_000f * visual.sun.intensity).coerceIn(95_000f, 180_000f)
    val mainLight = rememberMainLightNode(engine) { intensity = lightIntensity }
    val cameraManipulator = rememberCameraManipulator(
        orbitHomePosition = Position(frame.cameraX, frame.cameraY, frame.cameraZ),
        targetPosition = Position(frame.targetX, frame.targetY, frame.targetZ)
    )
    val background = when (SaudiResidentialEngine.context(plan.site.city).climate) {
        SaudiResidentialEngine.Climate.HOT_DRY -> Color(0xFFF3EEE5)
        SaudiResidentialEngine.Climate.HOT_HUMID -> Color(0xFFEDF3F2)
        SaudiResidentialEngine.Climate.HIGHLAND_MILD -> Color(0xFFF0F3ED)
        SaudiResidentialEngine.Climate.DESERT_CONTINENTAL -> Color(0xFFF2EEE7)
    }

    val serverClient = remember(context) { Remote3DRenderClient(context) }
    val serverState by produceState(initialValue = Server3DState(), plan, renderAllowed) {
        if (!renderAllowed) {
            value = Server3DState(
                loading = false,
                error = "لا يمكن بناء 3D موثوق قبل تثبيت الهندسة والمقياس. راجع المخطط أولاً."
            )
        } else {
            val result = serverClient.render(plan)
            value = result.fold(
                onSuccess = { Server3DState(loading = false, bytes = it.bytes, renderer = it.renderer) },
                onFailure = { Server3DState(loading = false, error = it.message ?: "تعذر إنشاء المجسم على خادم Blender") }
            )
        }
    }
    val glb = serverState.bytes
    val modelLoad = remember(glb, modelLoader) {
        glb?.let { bytes ->
            runCatching {
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.flip()
                modelLoader.createModelInstance(buffer)
            }
        }
    }
    val modelNode = remember(modelLoad) {
        modelLoad?.getOrNull()?.let { instance ->
            ModelNode(modelInstance = instance, autoAnimate = false).apply {
                isShadowCaster = true
                isShadowReceiver = true
                isEditable = false
            }
        }
    }
    val childNodes = remember(modelNode) { listOfNotNull(modelNode) }
    val sceneError = modelLoad?.exceptionOrNull()?.message
    val renderError = serverState.error ?: sceneError

    Scaffold(
        containerColor = H360Ivory,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("المجسم", fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (glb != null) serverState.renderer.uppercase().take(24) else "BLENDER SERVER",
                            color = H360CyanDeep,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.0.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "رجوع")
                    }
                },
                actions = {
                    Surface(shape = CircleShape, color = H360Cyan, modifier = Modifier.padding(end = 10.dp)) {
                        IconButton(onClick = { nav.navigate("walkthrough") }, enabled = glb != null) {
                            Icon(Icons.Rounded.DirectionsWalk, "جولة", tint = if (glb != null) H360CyanDeep else H360Muted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = H360Paper)
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(background)) {
            if (renderError == null && modelNode != null) {
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    mainLightNode = mainLight,
                    cameraManipulator = cameraManipulator,
                    childNodes = childNodes,
                    isOpaque = false
                )
            }

            if (!renderAllowed) {
                Surface(
                    color = H360Paper.copy(alpha = .97f),
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 3.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = H360Amber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "3D متوقف حتى تصبح القراءة ≥65% والمقياس ≥65% بدون أخطاء هندسية",
                            color = H360Ink,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            when {
                serverState.loading -> Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(24.dp),
                    color = H360Paper.copy(alpha = 0.97f),
                    shadowElevation = 5.dp
                ) {
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = H360CyanDeep)
                        Spacer(Modifier.height(12.dp))
                        Text("Blender يبني المجسم…", fontWeight = FontWeight.Black, color = H360Ink)
                    }
                }

                renderError != null -> Card(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = H360Paper)
                ) {
                    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.ViewInAr, null, tint = H360Amber, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("المجسم غير جاهز", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(renderError.take(240), color = H360Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { compatibilityMode = true }, shape = RoundedCornerShape(18.dp)) {
                            Text("معاينة محلية مؤقتة")
                        }
                    }
                }
            }
        }
    }
}
