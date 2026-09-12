package com.manzili.hai

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.manzili.hai.ai.HaiArchitectClient
import com.manzili.hai.engine.ArchitecturalEngine
import com.manzili.hai.engine.GeometrySolver
import com.manzili.hai.engine.StructuralGeometryEngine
import com.manzili.hai.model.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

private val ESand = Color(0xFFF7F4EE)
private val EPaper = Color(0xFFFFFEFA)
private val EInk = Color(0xFF20211E)
private val EBronze = Color(0xFF9A7447)
private val EMist = Color(0xFFE9E5DC)
private val EDeep = Color(0xFF27312C)
private val ESage = Color(0xFF64756B)

@Composable
fun EnhancedEditor(nav: NavHostController, plan: FloorPlan?, setPlan: (FloorPlan) -> Unit) {
    val context = LocalContext.current
    val client = remember { HaiArchitectClient(context) }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PlanProposal?>(null) }
    var history by remember { mutableStateOf<List<FloorPlan>>(emptyList()) }
    var selection by remember { mutableStateOf<PlanSelection?>(null) }
    var solverOptions by remember { mutableStateOf<List<GeometrySolver.Candidate>>(emptyList()) }
    var livePreview by remember { mutableStateOf<FloorPlan?>(null) }
    var messages by remember {
        mutableStateOf(listOf(ArchitectMessage(false, plan?.sourceSummary?.takeIf { it.isNotBlank() } ?: "راجعت المخطط. اضغط على عنصر ثم اسحبه أو استخدم أوامر HAI.")))
    }

    if (plan == null) {
        EditorPage { EditorTop(nav); Text("لا يوجد مخطط مفتوح") }
        return
    }

    val score = ArchitecturalEngine.score(plan)
    val structure = StructuralGeometryEngine.inspect(plan)
    val validation = pending?.let { ArchitecturalEngine.validate(plan, it) }
    val renderPlan = livePreview ?: pending?.updatedPlan ?: plan
    val proactive = ArchitecturalEngine.proactiveSuggestions(plan).take(3)

    fun commitCandidate(candidate: GeometrySolver.Candidate) {
        livePreview = null
        solverOptions = emptyList()
        pending = GeometrySolver.toProposal(plan, candidate)
        candidate.review.objections.firstOrNull()?.let { messages = messages + ArchitectMessage(false, it) }
    }

    fun openSolver(action: String) {
        val selected = selection ?: return
        val options = GeometrySolver.actionCandidates(plan, selected.kind, selected.id, action)
        if (options.isEmpty()) {
            messages = messages + ArchitectMessage(false, "عندي اعتراض على تنفيذ الحركة بهذه الطريقة: العنصر قد يكون مرتبطًا بحد خارجي، مقفلًا، أو بياناته غير كافية. لن أغيّره بالتخمين.")
        } else {
            pending = null
            solverOptions = options
        }
    }

    EditorPage {
        EditorTop(nav)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plan.title, fontSize = 21.sp, fontWeight = FontWeight.Black)
                Text("نسخة ${plan.revision} • ${plan.rooms.size} غرفة • ${plan.walls.size} جدار • ${plan.openings.size} فتحة", color = Color.Gray, fontSize = 10.5.sp)
            }
            if (history.isNotEmpty()) TextButton(onClick = {
                val previous = history.last()
                history = history.dropLast(1)
                pending = null
                solverOptions = emptyList()
                livePreview = null
                selection = null
                setPlan(previous.copy(revision = plan.revision + 1))
            }) { Icon(Icons.Rounded.Undo, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("تراجع") }
        }

        EditorScoreBar(score)
        Spacer(Modifier.height(6.dp))
        EditorStructureBar(structure)

        if (proactive.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(proactive) { suggestion ->
                    AssistChip(
                        onClick = {
                            val target = suggestion.targetIds.firstOrNull()
                            when (suggestion.actionKind) {
                                "EXPAND_ROOM" -> if (target != null) {
                                    selection = PlanSelection("room", target)
                                    solverOptions = GeometrySolver.actionCandidates(plan, "room", target, "EXPAND")
                                } else input = "نفّذ أفضل حل لهذه الملاحظة: ${suggestion.title} — ${suggestion.reason}"
                                "SHRINK_ROOM" -> if (target != null) {
                                    selection = PlanSelection("room", target)
                                    solverOptions = GeometrySolver.actionCandidates(plan, "room", target, "SHRINK")
                                } else input = "نفّذ أفضل حل لهذه الملاحظة: ${suggestion.title} — ${suggestion.reason}"
                                "MOVE_OPENING" -> if (target != null) {
                                    selection = PlanSelection("opening", target)
                                    solverOptions = GeometrySolver.actionCandidates(plan, "opening", target, "MOVE")
                                } else input = "اقترح أفضل إعادة تموضع للمدخل بسبب: ${suggestion.reason}"
                                else -> input = "راجع هذه الملاحظة ونفّذ أفضل حل إن كان مفيدًا: ${suggestion.title} — ${suggestion.reason}"
                            }
                        },
                        label = { Text(suggestion.title, fontSize = 9.5.sp, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(13.dp), tint = EBronze) }
                    )
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(285.dp)) {
            PlanCanvas(
                plan = renderPlan,
                modifier = Modifier.matchParentSize(),
                previewMode = pending != null || livePreview != null,
                selected = selection,
                onSelect = {}
            )
            DirectManipulationLayer(
                modifier = Modifier.matchParentSize(),
                basePlan = plan,
                selected = selection,
                enabled = pending == null && solverOptions.isEmpty(),
                onSelect = { selection = it },
                onPreview = { livePreview = it },
                onCommit = { if (it != null) commitCandidate(it) },
                onUnavailable = {
                    livePreview = null
                    messages = messages + ArchitectMessage(false, "اعتراضي هنا أن السحب لا يمكن تنفيذه بأمان من البيانات الحالية؛ لن أحرّك العنصر بصمت.")
                }
            )
        }

        if (pending != null || livePreview != null) Text("معاينة هندسية — لم تُعتمد بعد", color = EBronze, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

        selection?.let { selected ->
            Spacer(Modifier.height(6.dp))
            EditorInspector(
                plan = plan,
                selection = selected,
                onUpdate = { updated ->
                    history = history + plan
                    pending = null
                    solverOptions = emptyList()
                    livePreview = null
                    setPlan(updated.copy(revision = plan.revision + 1))
                },
                onAction = { action ->
                    if (action == "ASK") {
                        input = "راجع ${selectionLabel(plan, selected)} كمعماري متمرس. إذا عندك تحسين حقيقي اقترحه، وإذا وضعه الحالي جيد فلا تغيّره."
                    } else openSolver(action)
                }
            )
        }

        if (solverOptions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SolverOptionsCard(solverOptions, onChoose = { commitCandidate(it) }, onClose = { solverOptions = emptyList() })
        }

        if (plan.uncertainties.isNotEmpty()) Text("⚠ ${plan.uncertainties.first()}", fontSize = 10.5.sp, color = EBronze, modifier = Modifier.padding(vertical = 4.dp))

        pending?.let { proposal ->
            EditorProposalCard(
                proposal = proposal,
                validation = validation!!,
                onReject = { pending = null; livePreview = null },
                onApply = {
                    val next = proposal.updatedPlan ?: return@EditorProposalCard
                    history = history + plan
                    setPlan(next.copy(revision = plan.revision + 1))
                    pending = null
                    solverOptions = emptyList()
                    livePreview = null
                    selection = null
                }
            )
        }

        HorizontalDivider(color = EMist, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Engineering, null, tint = EBronze, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("مهندس HAI", fontWeight = FontWeight.Black, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("يعترض عند الضرر • يسكت إذا القرار سليم", color = Color.Gray, fontSize = 9.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { messages.takeLast(6).forEach { EditorBubble(it) } }

        Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("مثال: انقل الباب للمكان الأفضل إذا ما يضر المدخل") },
                shape = RoundedCornerShape(20.dp),
                maxLines = 4
            )
            Spacer(Modifier.width(7.dp))
            FilledIconButton(
                enabled = input.isNotBlank() && !busy && pending == null,
                onClick = {
                    val q = input.trim()
                    input = ""
                    messages = messages + ArchitectMessage(true, q)
                    busy = true
                    scope.launch {
                        runCatching { client.proposeChange(plan, q) }
                            .onSuccess { proposal ->
                                if (proposal.message.isNotBlank()) messages = messages + ArchitectMessage(false, proposal.message)
                                if (proposal.updatedPlan != null) pending = proposal
                            }
                            .onFailure { messages = messages + ArchitectMessage(false, "تعذر التحليل: ${it.message}") }
                        busy = false
                    }
                },
                modifier = Modifier.size(50.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Rounded.ArrowUpward, null)
            }
        }
    }
}

@Composable
private fun EditorPage(content: @Composable ColumnScope.() -> Unit) = Surface(color = ESand, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp), content = content)
}

@Composable
private fun EditorTop(nav: NavHostController) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Rounded.ArrowForward, "رجوع") }
        Box(Modifier.size(40.dp).background(EDeep, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text("H", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Spacer(Modifier.width(9.dp))
        Column { Text("منزلي HAI", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp); Text("المحرر الهندسي", color = Color.Gray, fontSize = 9.5.sp) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Rounded.Tune, "إعدادات") }
    }
}

@Composable
private fun DirectManipulationLayer(
    modifier: Modifier,
    basePlan: FloorPlan,
    selected: PlanSelection?,
    enabled: Boolean,
    onSelect: (PlanSelection?) -> Unit,
    onPreview: (FloorPlan?) -> Unit,
    onCommit: (GeometrySolver.Candidate?) -> Unit,
    onUnavailable: () -> Unit
) {
    Box(
        modifier
            .pointerInput(basePlan, selected) {
                detectTapGestures { tap ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val px = (tap.x / w * 100f).coerceIn(0f, 100f)
                    val py = (tap.y / h * 100f).coerceIn(0f, 100f)
                    val opening = basePlan.openings.minByOrNull { hypot((it.x - px).toDouble(), (it.y - py).toDouble()) }
                    if (opening != null && hypot((opening.x - px).toDouble(), (opening.y - py).toDouble()) <= 4.8) {
                        onSelect(PlanSelection("opening", opening.id)); return@detectTapGestures
                    }
                    val wall = basePlan.walls.minByOrNull { pointSegment(px, py, it.start.x, it.start.y, it.end.x, it.end.y) }
                    if (wall != null && pointSegment(px, py, wall.start.x, wall.start.y, wall.end.x, wall.end.y) <= 2.5) {
                        onSelect(PlanSelection("wall", wall.id)); return@detectTapGestures
                    }
                    val room = basePlan.rooms.lastOrNull { px >= it.x && px <= it.x + it.width && py >= it.y && py <= it.y + it.height }
                    onSelect(room?.let { PlanSelection("room", it.id) })
                }
            }
            .pointerInput(basePlan, selected, enabled) {
                if (!enabled || selected == null) return@pointerInput
                var totalX = 0f
                var totalY = 0f
                var last: GeometrySolver.Candidate? = null
                var moved = false
                detectDragGestures(
                    onDragStart = { totalX = 0f; totalY = 0f; last = null; moved = false },
                    onDragCancel = { onPreview(null) },
                    onDragEnd = {
                        onPreview(null)
                        if (moved) {
                            if (last != null) onCommit(last) else onUnavailable()
                        }
                    },
                    onDrag = { _, drag ->
                        totalX += drag.x
                        totalY += drag.y
                        if (abs(totalX) + abs(totalY) > 8f) moved = true
                        val dx = totalX / size.width.toFloat().coerceAtLeast(1f) * 100f
                        val dy = totalY / size.height.toFloat().coerceAtLeast(1f) * 100f
                        last = GeometrySolver.drag(basePlan, selected.kind, selected.id, dx, dy)
                        onPreview(last?.plan)
                    }
                )
            }
    )
}

@Composable
private fun EditorInspector(plan: FloorPlan, selection: PlanSelection, onUpdate: (FloorPlan) -> Unit, onAction: (String) -> Unit) {
    val room = plan.rooms.firstOrNull { selection.kind == "room" && it.id == selection.id }
    val wall = plan.walls.firstOrNull { selection.kind == "wall" && it.id == selection.id }
    val opening = plan.openings.firstOrNull { selection.kind == "opening" && it.id == selection.id }
    val title: String
    val subtitle: String
    val confidence: Int
    val locked: Boolean
    when {
        room != null -> { title = room.name; subtitle = if (room.areaM2 > 0) "غرفة • ${"%.1f".format(room.areaM2)}م²" else "غرفة"; confidence = room.confidence; locked = room.locked }
        wall != null -> { title = "جدار ${wall.id}"; subtitle = wall.kind; confidence = wall.confidence; locked = wall.locked }
        opening != null -> { val window = opening.type.lowercase().contains("window") || opening.type.contains("ناف"); title = if (window) "نافذة ${opening.id}" else "باب ${opening.id}"; subtitle = "فتحة • ${opening.connectsRoomIds.size} اتصال"; confidence = opening.confidence; locked = opening.locked }
        else -> return
    }

    Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = EPaper), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.TouchApp, null, tint = if (locked) EBronze else EDeep, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Black, fontSize = 12.5.sp)
                    Text("$subtitle • ثقة $confidence%", color = Color.Gray, fontSize = 9.8.sp)
                    Text(if (locked) "مقفل" else "اسحب العنصر مباشرة أو اختر أمرًا", color = if (locked) EBronze else ESage, fontSize = 9.sp)
                }
                TextButton(onClick = {
                    val next = when {
                        room != null -> plan.copy(rooms = plan.rooms.map { if (it.id == room.id) it.copy(locked = !it.locked) else it })
                        wall != null -> plan.copy(walls = plan.walls.map { if (it.id == wall.id) it.copy(locked = !it.locked) else it })
                        opening != null -> plan.copy(openings = plan.openings.map { if (it.id == opening.id) it.copy(locked = !it.locked) else it })
                        else -> plan
                    }
                    onUpdate(next)
                }) { Text(if (locked) "فتح" else "قفل", fontSize = 10.sp) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (room != null) {
                    item { ActionButton("تحريك", !locked) { onAction("MOVE") } }
                    item { ActionButton("تكبير", !locked) { onAction("EXPAND") } }
                    item { ActionButton("تصغير", !locked) { onAction("SHRINK") } }
                }
                if (opening != null || wall != null) item { ActionButton("تحريك", !locked) { onAction("MOVE") } }
                item { ActionButton("اسأل HAI", true) { onAction("ASK") } }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)) { Text(label, fontSize = 9.5.sp) }
}

@Composable
private fun SolverOptionsCard(options: List<GeometrySolver.Candidate>, onChoose: (GeometrySolver.Candidate) -> Unit, onClose: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = EPaper), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AccountTree, null, tint = EBronze, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("بدائل محسوبة", fontWeight = FontWeight.Black, fontSize = 12.sp)
                Spacer(Modifier.weight(1f)); IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, null, Modifier.size(15.dp)) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(options) { option ->
                    Card(onClick = { onChoose(option) }, colors = CardDefaults.cardColors(containerColor = EMist), shape = RoundedCornerShape(13.dp), modifier = Modifier.width(180.dp)) {
                        Column(Modifier.padding(9.dp)) {
                            Row { Text(option.title, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, modifier = Modifier.weight(1f)); Text("${option.score}/100", fontWeight = FontWeight.Black, fontSize = 9.5.sp, color = if (option.review.hasMaterialObjection) EBronze else ESage) }
                            Text(option.reason, fontSize = 9.sp, lineHeight = 13.sp, color = Color.DarkGray, modifier = Modifier.padding(top = 3.dp))
                            if (option.review.hasMaterialObjection) Text("HAI لديه اعتراض", color = EBronze, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorProposalCard(proposal: PlanProposal, validation: ValidationReport, onReject: () -> Unit, onApply: () -> Unit) {
    val caution = validation.valid && validation.warnings.isNotEmpty()
    val title = when { !validation.valid -> "مرفوض هندسيًا"; caution -> "HAI لديه ملاحظة"; else -> "التعديل سليم" }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = EPaper)) {
        Column(Modifier.padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (!validation.valid || caution) Icons.Rounded.Engineering else Icons.Rounded.FactCheck, null, tint = if (!validation.valid || caution) EBronze else ESage, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(title, fontWeight = FontWeight.Black, fontSize = 12.5.sp); Spacer(Modifier.weight(1f)); Text("${proposal.confidence}%", color = Color.Gray, fontSize = 9.5.sp)
            }
            proposal.changes.take(4).forEach { c -> Text("• ${c.roomName.ifBlank { "عنصر" }} — ${c.note}", fontSize = 9.8.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp)) }
            validation.errors.take(2).forEach { Text("✕ $it", color = MaterialTheme.colorScheme.error, fontSize = 9.8.sp) }
            validation.warnings.take(3).forEach { Text("⚠ $it", color = EBronze, fontSize = 9.8.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("ارفض") }
                Button(onClick = onApply, enabled = validation.valid && proposal.updatedPlan != null, modifier = Modifier.weight(1.4f), shape = RoundedCornerShape(13.dp)) { Text(if (caution) "اعتمد رغم الملاحظة" else "اعتمد") }
            }
        }
    }
}

@Composable
private fun EditorScoreBar(score: PlanScore) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { MetricPill("HAI", score.overall) }; item { MetricPill("الحركة", score.efficiency) }; item { MetricPill("الخصوصية", score.privacy) }; item { MetricPill("الهندسة", score.geometry) }
    }
}

@Composable
private fun EditorStructureBar(report: StructuralGeometryEngine.Report) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SmallPill("جدران ${report.wallCount}") }; item { SmallPill("أبواب ${report.doorCount}") }; item { SmallPill("نوافذ ${report.windowCount}") }; item { SmallPill("ثقة ${report.confidence}%") }
    }
}

@Composable
private fun MetricPill(label: String, value: Int) = Surface(color = EPaper, shape = RoundedCornerShape(50.dp)) { Text("$label  $value", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = if (value >= 75) ESage else EBronze) }

@Composable
private fun SmallPill(text: String) = Surface(color = EMist, shape = RoundedCornerShape(50.dp)) { Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 9.5.sp) }

@Composable
private fun EditorBubble(m: ArchitectMessage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (m.fromUser) Arrangement.Start else Arrangement.End) {
        Surface(color = if (m.fromUser) EDeep else EPaper, shape = RoundedCornerShape(17.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(m.text, color = if (m.fromUser) Color.White else EInk, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(11.dp))
        }
    }
}

private fun selectionLabel(plan: FloorPlan, s: PlanSelection): String = when (s.kind) {
    "room" -> plan.rooms.firstOrNull { it.id == s.id }?.let { "الغرفة «${it.name}»" } ?: "الغرفة ${s.id}"
    "opening" -> plan.openings.firstOrNull { it.id == s.id }?.let { if (it.type.lowercase().contains("window") || it.type.contains("ناف")) "النافذة ${it.id}" else "الباب ${it.id}" } ?: "الفتحة ${s.id}"
    "wall" -> "الجدار ${s.id}"
    else -> s.id
}

private fun pointSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
    val dx = bx - ax; val dy = by - ay
    if (abs(dx) < .0001f && abs(dy) < .0001f) return hypot((px - ax).toDouble(), (py - ay).toDouble())
    val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
    val x = ax + t * dx; val y = ay + t * dy
    return hypot((px - x).toDouble(), (py - y).toDouble())
}
