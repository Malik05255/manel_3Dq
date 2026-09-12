package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
        Scaffold(topBar = { TopAppBar(title = { Text("3D PBR") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) } }) }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("لا يوجد مشروع مفتوح") }
        }
        return
    }

    var compatibilityMode by rememberSaveable { mutableStateOf(false) }
    if (compatibilityMode) {
        Production3DScreenV2(nav, plan)
        return
    }

    val semanticScene = remember(plan) { ProductionSceneEngine.build(plan) }
    val visual = remember(plan) { SaudiVisualRenderEngine.build(plan,SaudiVisualRenderEngine.Quality.HIGH) }
    val frame = remember(semanticScene) { PbrSceneFramingEngine.frame(semanticScene) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val lightIntensity=(115_000f*visual.sun.intensity).coerceIn(80_000f,145_000f)
    val mainLight = rememberMainLightNode(engine) { intensity = lightIntensity }
    val cameraManipulator = rememberCameraManipulator(
        orbitHomePosition = Position(frame.cameraX, frame.cameraY, frame.cameraZ),
        targetPosition = Position(frame.targetX, frame.targetY, frame.targetZ)
    )
    val background=when(SaudiResidentialEngine.context(plan.site.city).climate){
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("3D واقعي • PBR", fontWeight = FontWeight.Black)
                        Text("Filament • Geometry V3 • ${visual.facade.style}", fontSize = 10.sp, color = Color.Gray)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, null) } },
                actions = {
                    TextButton(onClick = { nav.navigate("walkthrough") }) {
                        Icon(Icons.Rounded.DirectionsWalk, null)
                        Spacer(Modifier.width(4.dp))
                        Text("جولة")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(background)) {
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
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp
                ) { Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("يبني مجسم PBR وسياق الموقع…") } }

                renderError != null -> Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.ViewInAr, null)
                        Spacer(Modifier.height(8.dp))
                        Text("تعذر تشغيل Filament على هذا الجهاز", fontWeight = FontWeight.Bold)
                        Text(renderError.message.orEmpty().take(160), fontSize = 10.sp, color = Color.Gray)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { compatibilityMode = true }) { Text("فتح العرض المتوافق") }
                    }
                }

                modelNode != null -> {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        color = Color(0xEFFFFFFF),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("اسحب للدوران • إصبعين للتحريك والتقريب", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (semanticScene.metricReady) "PBR + ظلال + واجهة هندسية + سياق موقع بالمتر" else "PBR + واجهة وسياق نسبي حتى تأكيد المقياس",
                                fontSize = 9.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
