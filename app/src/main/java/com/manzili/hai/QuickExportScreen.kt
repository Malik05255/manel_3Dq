package com.manzili.hai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.Semantic3DEngine
import com.manzili.hai.export.DxfPlanExporter
import com.manzili.hai.export.IfcPlanExporter
import com.manzili.hai.export.ObjPlanExporter
import com.manzili.hai.export.PdfPlanExporter
import com.manzili.hai.export.SvgPlanExporter
import com.manzili.hai.model.FloorPlan

@Composable
fun QuickExportScreen(nav: NavHostController, plan: FloorPlan?) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null && plan != null) status = if (runCatching { context.contentResolver.openOutputStream(uri)?.use { PdfPlanExporter.write(plan, it) } }.isSuccess) "تم تصدير PDF" else "تعذر تصدير PDF"
    }
    val svg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null && plan != null) status = if (runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(SvgPlanExporter.render(plan)) } }.isSuccess) "تم تصدير SVG" else "تعذر تصدير SVG"
    }
    val dxf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
        if (uri != null && plan != null) status = if (runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(DxfPlanExporter.render(plan)) } }.isSuccess) "تم تصدير DXF" else "تعذر تصدير DXF"
    }
    val obj = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("model/obj")) { uri ->
        if (uri != null && plan != null) status = if (runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ObjPlanExporter.render(plan)) } }.isSuccess) "تم تصدير OBJ من نفس المجسم الهندسي" else "تعذر تصدير OBJ"
    }
    val ifc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-step")) { uri ->
        if (uri != null && plan != null) status = if (runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(IfcPlanExporter.render(plan)) } }.isSuccess) "تم تصدير IFC4 BIM" else "تعذر تصدير IFC؛ راجع المقياس وارتفاعات الفتحات"
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Row {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("التصدير الهندسي", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("PDF + SVG + DXF + OBJ + IFC4", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            if (plan == null) {
                Text("افتح مشروعًا أولًا")
            } else {
                PlanCanvas(plan, Modifier.fillMaxWidth().height(220.dp), previewMode = true, onSelect = {})
                Spacer(Modifier.height(12.dp))
                val scene = remember(plan) { Semantic3DEngine.build(plan) }
                val missingVertical = scene.openings.count { !it.wallId.isNullOrBlank() && !it.verticalVerified }
                val ifcReady = IfcPlanExporter.canExport(plan)
                Text(
                    if (plan.widthM != null && plan.heightM != null)
                        "${"%.2f".format(plan.widthM)}م × ${"%.2f".format(plan.heightM)}م • ثقة المقياس ${plan.scaleConfidence}%"
                    else "المقياس غير مؤكد؛ DXF/OBJ نسبيان وIFC المتري معطّل.",
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { pdf.launch("manzili-plan.pdf") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("تصدير PDF")
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = { svg.launch("manzili-plan.svg") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text("تصدير SVG")
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = { dxf.launch("manzili-plan.dxf") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text("تصدير DXF CAD")
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = { obj.launch("manzili-semantic-3d.obj") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Rounded.ViewInAr, null); Spacer(Modifier.width(6.dp)); Text("تصدير OBJ ثلاثي الأبعاد")
                }
                Spacer(Modifier.height(7.dp))
                Button(
                    onClick = { ifc.launch("manzili-bim.ifc") },
                    enabled = ifcReady,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Icon(Icons.Rounded.ViewInAr, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            ifcReady -> "تصدير IFC4 BIM"
                            !scene.metricReady -> "IFC ينتظر تأكيد المقياس"
                            missingVertical > 0 -> "IFC ينتظر ارتفاع $missingVertical فتحة"
                            else -> "IFC غير جاهز"
                        }
                    )
                }
                if (!ifcReady) {
                    Text(
                        when {
                            !scene.metricReady -> "لن يصدر HAI ملف BIM بأمتار مخترعة. أكد أبعاد/مقياس المخطط أولًا."
                            missingVertical > 0 -> "افتح شاشة 3D واضغط الفتحات التي تحمل «ارتفاع؟» وأكد القياسات المعروفة. القيم الافتراضية للمعاينة لا تدخل IFC."
                            else -> "راجع بيانات المشروع قبل تصدير BIM."
                        },
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
            if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text(
                "OBJ وIFC يُبنيان من نفس Wall / Opening / Floor / StructuralElement. التصدير الهندسي لا يعني اعتمادًا إنشائيًا أو بلديًا.",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
