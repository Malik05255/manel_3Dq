package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.PbrSceneFramingEngine
import com.manzili.hai.engine.ProductionSceneEngine
import com.manzili.hai.engine.SaudiResidentialEngine
import com.manzili.hai.engine.SaudiVisualRenderEngine
import com.manzili.hai.export.GltfPlanExporter
import com.manzili.hai.model.FloorPlan
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Production3DScreenV3(nav: NavHostController, plan: FloorPlan?) {
    if (plan == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("المجسم", fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Rounded.ArrowBack, "رجوع")
                        }
                    }
                )
            }
        ) { pad ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ViewInAr, null, tint = Color(0xFFB0AAA2), modifier = Modifier.size(48.dp))
            }
        }
        return
    }

    var compatibilityMode by rememberSaveable { mutableStateOf(false) }
    if (compatibilityMode) {
        Production3DScreenV2(nav, plan)
        return
    }

    val semanticScene = remember(plan) { ProductionSceneEngine.build(plan) }
    val visual = remember(plan) { SaudiVisualRenderEngine.build(plan, SaudiVisualRenderEngine.Quality.HIGH) }
    val frame = remember(semanticScene) { PbrSceneFramingEngine.frame(semanticScene) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val lightIntensity = (115_000f * visual.sun.intensity).coerceIn(80_000f, 145_000f)
    val mainLight = rememberMainLightNode(engine) { intensity = lightIntensity }
    val cameraManipulator = rememberCameraManipulator(
        orbitHomePosition = Position(frame.cameraX, frame.cameraY, frame.cameraZ),
        targetPosition = Position(frame.targetX, frame.targetY, frame.targetZ)
    )
    val background = when (SaudiResidentialEngine.context(plan.site.city).climate) {
        SaudiResidentialEngine.Climate.HOT_DRY -> Color(0xFFE9E1D2)
        SaudiResidentialEngine.Climate.HOT_HUMID -> Color(0xFFE0E7E5)
        SaudiResidentialEngine.Climate.HIGHLAND_MILD -> Color(0xFFE4E7DF)
        SaudiResidentialEngine.Climate.DESERT_CONTINENTAL -> Color(0xFFE8E2D8)
    }

    val glb by produceState<ByteArray?>(initialValue = null, plan) {
        value = withContext(Dispatchers.Default) { GltfPlanExporter.renderGlb(plan) }
    }
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
    val renderError = modelLoad?.exceptionOrNull()

    Scaffold(
        containerColor = Color(0xFFF8F6F2),
        topBar = {
            TopAppBar(
                title = { Text("المجسم", fontSize = 26.sp, fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, "رجوع")
                    }
                },
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF6353D9).copy(alpha = 0.10f),
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        IconButton(onClick = { nav.navigate("walkthrough") }) {
                            Icon(Icons.Rounded.DirectionsWalk, "جولة", tint = Color(0xFF4F40B8))
                        }
                    }
                }
            )
        }
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .background(background)
        ) {
            if (renderError == null) {
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    mainLightNode = mainLight,
                    cameraManipulator = cameraManipulator,
                    childNodes = childNodes
                )
            }

            when {
                glb == null -> Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.94f),
                    shadowElevation = 4.dp
                ) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = Color(0xFF6353D9)
                        )
                    }
                }

                renderError != null -> Card(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.ViewInAr, null, tint = Color(0xFFE28B5A), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("تعذر العرض", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { compatibilityMode = true },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F40B8))
                        ) {
                            Text("عرض بديل")
                        }
                    }
                }
            }
        }
    }
}
