package com.manzili.hai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
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
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp)) {
            Row {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column { Text("التصدير الهندسي", fontSize = 22.sp, fontWeight = FontWeight.Black); Text("PDF + SVG Vector", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp) }
            }
            Spacer(Modifier.height(18.dp))
            if (plan == null) Text("افتح مشروعًا أولًا") else {
                PlanCanvas(plan, Modifier.fillMaxWidth().height(280.dp), previewMode = true, onSelect = {})
                Spacer(Modifier.height(12.dp))
                Text(if (plan.widthM != null && plan.heightM != null) "${"%.2f".format(plan.widthM)}م × ${"%.2f".format(plan.heightM)}م • ثقة المقياس ${plan.scaleConfidence}%" else "المقياس غير مؤكد؛ سيظهر ذلك في الملف.", fontSize = 11.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { pdf.launch("manzili-plan.pdf") }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Rounded.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text("تصدير PDF") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { svg.launch("manzili-plan.svg") }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text("تصدير SVG") }
            }
            if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("المخرجات لا تمثل اعتمادًا إنشائيًا أو بلديًا.", fontSize = 9.sp, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
