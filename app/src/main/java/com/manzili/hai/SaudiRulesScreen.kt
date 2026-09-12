package com.manzili.hai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.engine.SaudiRulesEngine
import com.manzili.hai.model.FloorPlan

@Composable
fun SaudiRulesScreen(nav: NavHostController, plan: FloorPlan?) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F4EE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
                Column {
                    Text("فحص قواعد السعودية", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text("SBC ${SaudiRulesEngine.EDITION} • ${SaudiRulesEngine.RESIDENTIAL_CODE}", color = Color.Gray, fontSize = 9.5.sp)
                }
            }
            if (plan == null) {
                Text("لا يوجد مشروع مفتوح")
                return@Column
            }
            val report = remember(plan) { SaudiRulesEngine.inspect(plan) }
            Text("يعرض ما تم فحصه وما يحتاج بيانات إضافية بدون افتراض أرقام غير موجودة.", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.checks.forEach { check ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(17.dp)) {
                        Column(Modifier.padding(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(ruleIcon(check.status), null, tint = ruleColor(check.status), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(check.title, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(check.status.name, color = ruleColor(check.status), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(check.detail, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                            Text("مرجع: ${check.source}", color = Color.Gray, fontSize = 8.5.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Text("النتيجة داخل التطبيق ليست تصريح بناء ولا توقيعًا مهنيًا.", color = MaterialTheme.colorScheme.secondary, fontSize = 9.5.sp, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

private fun ruleIcon(status: SaudiRulesEngine.Status) = when (status) {
    SaudiRulesEngine.Status.PASS -> Icons.Rounded.CheckCircle
    SaudiRulesEngine.Status.NEEDS_DATA -> Icons.Rounded.Help
    SaudiRulesEngine.Status.REVIEW -> Icons.Rounded.WarningAmber
    SaudiRulesEngine.Status.INFO -> Icons.Rounded.Info
    SaudiRulesEngine.Status.NOT_APPLICABLE -> Icons.Rounded.RemoveCircleOutline
}

private fun ruleColor(status: SaudiRulesEngine.Status) = when (status) {
    SaudiRulesEngine.Status.PASS -> Color(0xFF526D5A)
    SaudiRulesEngine.Status.NEEDS_DATA -> Color(0xFF9A7447)
    SaudiRulesEngine.Status.REVIEW -> Color(0xFFB36A3C)
    SaudiRulesEngine.Status.INFO -> Color.Gray
    SaudiRulesEngine.Status.NOT_APPLICABLE -> Color.Gray
}