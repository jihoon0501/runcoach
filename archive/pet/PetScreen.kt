package io.github.jihoon0501.runcoach

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private class Species(val id: String, val label: String, val stages: List<String>)

private val SPECIES = listOf(
    Species("PLANT", "새싹", listOf("🌱", "🌿", "🪴", "🌳", "🌸")),
    Species("DOG", "강아지", listOf("🐶", "🐶", "🐕", "🐕", "🦮")),
    Species("CAT", "고양이", listOf("🐱", "🐱", "🐈", "🐈", "🐈")),
    Species("TURTLE", "거북이", listOf("🐢", "🐢", "🐢", "🐢", "🐢")),
)

private fun wallColors(id: String?): List<Color> = when (id) {
    "wall_sky" -> listOf(Color(0xFF7CC4FF), Color(0xFFD8EEFF))
    "wall_sunset" -> listOf(Color(0xFFFF8A65), Color(0xFFFFD28A))
    "wall_forest" -> listOf(Color(0xFF2E7D5B), Color(0xFF8FD3A8))
    "wall_night" -> listOf(Color(0xFF141B4D), Color(0xFF4B3F8F))
    else -> listOf(Color(0xFF2A2F4A), Color(0xFF3B4166))
}

private fun floorColor(id: String?): Color = when (id) {
    "floor_grass" -> Color(0xFF4CAF50)
    "floor_sand" -> Color(0xFFE6C98B)
    "floor_carpet" -> Color(0xFFB5475C)
    else -> Color(0xFF8D6E4C)
}

private val SHOP_TABS = listOf(
    "먹이" to listOf(PetCatalog.FOOD),
    "꾸미기" to listOf(PetCatalog.HAT, PetCatalog.FACE, PetCatalog.NECK, PetCatalog.FEET),
    "집" to listOf(PetCatalog.WALL, PetCatalog.FLOOR, PetCatalog.FURN),
)

private fun slotLabel(slot: String): String = when (slot) {
    PetCatalog.HAT -> "머리"
    PetCatalog.FACE -> "얼굴"
    PetCatalog.NECK -> "목"
    PetCatalog.FEET -> "발"
    PetCatalog.WALL -> "벽"
    PetCatalog.FLOOR -> "바닥"
    PetCatalog.FURN -> "가구"
    else -> ""
}

/**
 * 펫 탭: 달리고, 쉬고, 기록하면 코인을 모아 친구를 키우고 꾸민다.
 * 코인은 습관(계획 훈련, 쉬는 날, 회복 걷기, 주·월 목표, 업적)에서만 나오고, 체중 같은 몸 수치는 보상에 쓰지 않는다.
 */
@Composable
fun PetScreen(runs: List<RunSession>, walks: List<WalkSession>, plan: List<PlanItem>, today: LocalDate) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val store = remember { PetStore(context.applicationContext) }
    var state by remember { mutableStateOf(store.load()) }
    var msg by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(QuestPeriod.DAY) }
    var shopTab by remember { mutableStateOf(0) }
    var showRename by remember { mutableStateOf(false) }

    fun update(n: PetState) {
        state = n
        store.save(n)
    }

    if (!state.chosen) {
        ChooseSpecies { id -> update(state.copy(chosen = true, species = id)) }
        return
    }

    val bodyDates = remember { BodyStore(context.applicationContext, prefs.dataSource).load().map { it.date }.toSet() }
    val input = remember(plan, runs, walks, bodyDates, today) { PetInputBuilder.build(plan, runs, walks, bodyDates, today) }
    val quests = remember(input, state.claimed) { PetEngine.quests(input, state.claimed) }
    val level = PetEngine.levelOf(state.earned)
    val fullness = PetEngine.fullness(state, today)
    val todayPlanned = today in input.plannedRunDates
    val todayDone = today in input.doneRunDates

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        // ── 펫과 집 ──
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                PetScene(state, level)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${state.name}  Lv.$level ${PetEngine.stageName(level)}",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        )
                        Text(
                            PetEngine.message(todayPlanned, todayDone, fullness, runs.isNotEmpty()),
                            fontSize = 11.sp, color = RunColors.TextDim, lineHeight = 16.sp,
                        )
                    }
                    Text("🪙 ${state.balance}", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = RunColors.Lime)
                }
                Spacer(Modifier.height(8.dp))
                val next = PetEngine.nextLevelAt(state.earned)
                val frac = if (next == null) 1f
                else ((state.earned - PetEngine.levelStart(state.earned)).toFloat() / (next - PetEngine.levelStart(state.earned)).toFloat()).coerceIn(0f, 1f)
                Bar("성장", frac, RunColors.Lime, if (next == null) "최고 레벨" else "${state.earned}/${next}")
                Spacer(Modifier.height(4.dp))
                Bar("배부름", fullness / 100f, RunColors.Orange, "$fullness")
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { showRename = true }) { Text("이름 바꾸기", fontSize = 11.sp, color = RunColors.TextDim) }
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 11.sp, color = Color.White)
            }
        }

        // ── 보상 받기 ──
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("보상 받기")
                Spacer(Modifier.height(2.dp))
                Text(
                    "계획대로 달리고, 쉬는 날 쉬고, 기록을 남기면 코인을 받아요. 놓친 보상은 조용히 사라질 뿐 불이익은 없어요.",
                    fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuestPeriod.values().forEach { p ->
                        val n = quests.count { it.period == p && it.claimable }
                        OutlinedButton(
                            onClick = { period = p },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text(
                                if (n > 0) "${p.label} $n" else p.label,
                                fontSize = 11.sp, color = if (period == p) RunColors.Lime else RunColors.TextDim,
                            )
                        }
                    }
                }
                val claimableAll = quests.filter { it.claimable }
                if (claimableAll.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val (ns, total) = PetEngine.claimAll(state, quests)
                            update(ns)
                            msg = "코인 +$total 을 모두 받았어요!"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                        modifier = Modifier.height(34.dp),
                    ) { Text("모두 받기 +${claimableAll.sumOf { it.points }}", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                val shown = quests.filter { it.period == period }
                if (shown.isEmpty()) {
                    Text("지금 받을 수 있는 ${period.label} 보상이 없어요", fontSize = 11.sp, color = RunColors.TextDim)
                }
                shown.forEach { q ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.title, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(q.detail, fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            q.claimed -> Text("✓ 받음", fontSize = 11.sp, color = RunColors.TextDim)
                            q.claimable -> Button(
                                onClick = {
                                    PetEngine.claim(state, q)?.let {
                                        update(it)
                                        msg = "코인 +${q.points} 을 받았어요!"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg),
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            ) { Text("받기 +${q.points}", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            else -> Text("+${q.points}", fontSize = 11.sp, color = RunColors.TextDim)
                        }
                    }
                }
            }
        }

        // ── 상점 ──
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionTitle("상점")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SHOP_TABS.forEachIndexed { i, t ->
                        OutlinedButton(
                            onClick = { shopTab = i },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text(t.first, fontSize = 11.sp, color = if (shopTab == i) RunColors.Lime else RunColors.TextDim) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                PetCatalog.items.filter { it.slot in SHOP_TABS[shopTab].second }.forEach { item ->
                    ShopRow(state, item, level, today,
                        onUpdate = { n, note ->
                            update(n)
                            if (note.isNotEmpty()) msg = note
                        })
                }
            }
        }

        item {
            Text(
                "코인은 앱 안에서 습관으로만 모으고, 현금으로 살 수 없어요. 체중·체지방 같은 몸 수치의 변화는 보상에 쓰지 않아요. " +
                    "몸이 안 좋을 땐 쉬어도 친구는 아프지 않아요.",
                fontSize = 10.sp, color = RunColors.TextDim, lineHeight = 15.sp,
            )
        }
    }

    if (showRename) {
        var text by remember { mutableStateOf(state.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("이름 바꾸기") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(8) },
                    singleLine = true,
                    label = { Text("이름 (8자까지)") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = text.trim()
                    if (n.isNotEmpty()) update(state.copy(name = n))
                    showRename = false
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ChooseSpecies(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("함께 달릴 친구를 골라 주세요", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "달리고, 쉬고, 기록하면 코인을 모아 친구를 키우고 꾸밀 수 있어요. 안 달려도 친구는 아프지 않아요.",
            fontSize = 12.sp, color = RunColors.TextDim, textAlign = TextAlign.Center, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SPECIES.forEach { sp ->
                GlassCard(Modifier.weight(1f), onClick = { onPick(sp.id) }) {
                    Text(sp.stages[0], fontSize = 34.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(sp.label, fontSize = 12.sp, color = Color.White, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** 벽, 바닥, 가구가 있는 집 안에서 펫이 살짝 위아래로 움직인다. 착용한 악세사리는 펫 위에 겹쳐 그린다 */
@Composable
private fun PetScene(state: PetState, level: Int) {
    val sp = SPECIES.firstOrNull { it.id == state.species } ?: SPECIES[0]
    val stage = PetEngine.stageIndex(level)
    val emoji = sp.stages[stage]
    val size = 54 + stage * 12
    val t = rememberInfiniteTransition(label = "bob")
    val dy by t.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(wallColors(state.equipped[PetCatalog.WALL]))),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.BottomCenter)
                .background(floorColor(state.equipped[PetCatalog.FLOOR])),
        )
        // 가구는 최대 3개: 왼쪽 바닥, 오른쪽 바닥, 오른쪽 위(벽)
        state.furniture.forEachIndexed { i, id ->
            val f = PetCatalog.byId(id) ?: return@forEachIndexed
            val mod = when (i) {
                0 -> Modifier.align(Alignment.BottomStart).offset(x = 14.dp, y = (-30).dp)
                1 -> Modifier.align(Alignment.BottomEnd).offset(x = (-14).dp, y = (-30).dp)
                else -> Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = 14.dp)
            }
            Text(f.emoji, fontSize = 34.sp, modifier = mod)
        }
        Box(Modifier.align(Alignment.Center).offset(y = dy.dp), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = size.sp)
            state.equipped[PetCatalog.FEET]?.let { PetCatalog.byId(it) }?.let {
                Text(it.emoji, fontSize = (size * 0.45f).sp, modifier = Modifier.offset(y = (size * 0.66f).dp))
            }
            state.equipped[PetCatalog.NECK]?.let { PetCatalog.byId(it) }?.let {
                Text(it.emoji, fontSize = (size * 0.4f).sp, modifier = Modifier.offset(y = (size * 0.42f).dp))
            }
            state.equipped[PetCatalog.FACE]?.let { PetCatalog.byId(it) }?.let {
                Text(it.emoji, fontSize = (size * 0.5f).sp, modifier = Modifier.offset(y = (-size * 0.04f).dp))
            }
            state.equipped[PetCatalog.HAT]?.let { PetCatalog.byId(it) }?.let {
                Text(it.emoji, fontSize = (size * 0.5f).sp, modifier = Modifier.offset(y = (-size * 0.55f).dp))
            }
            if (stage == 4) Text("✨", fontSize = (size * 0.3f).sp, modifier = Modifier.offset(x = (size * 0.55f).dp, y = (-size * 0.45f).dp))
        }
    }
}

@Composable
private fun Bar(label: String, frac: Float, color: Color, right: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = RunColors.TextDim, modifier = Modifier.width(44.dp))
        Box(
            Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.1f)),
        ) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        Text(right, fontSize = 10.sp, color = RunColors.TextDim, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ShopRow(state: PetState, item: PetItem, level: Int, today: LocalDate, onUpdate: (PetState, String) -> Unit) {
    val owned = item.id in state.owned
    val equipped = state.equipped[item.slot] == item.id || item.id in state.furniture
    val locked = level < item.minLevel
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.emoji, fontSize = 22.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    locked -> "Lv.${item.minLevel} 이상이 되면 살 수 있어요"
                    item.slot == PetCatalog.FOOD -> "배부름 +${item.value}"
                    else -> slotLabel(item.slot)
                },
                fontSize = 10.sp, color = RunColors.TextDim,
            )
        }
        Spacer(Modifier.width(8.dp))
        val lime = ButtonDefaults.buttonColors(containerColor = RunColors.Lime, contentColor = RunColors.Bg)
        val pad = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        when {
            item.slot == PetCatalog.FOOD -> Button(
                onClick = {
                    PetEngine.feed(state, item, today)?.let { onUpdate(it, "${state.name}이(가) ${item.name}을(를) 맛있게 먹었어요 😋") }
                },
                enabled = PetEngine.canBuy(state, item),
                colors = lime, modifier = Modifier.height(30.dp), contentPadding = pad,
            ) { Text("🪙${item.price} 주기", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            !owned -> Button(
                onClick = { PetEngine.buy(state, item)?.let { onUpdate(it, "${item.name}을(를) 샀어요!") } },
                enabled = PetEngine.canBuy(state, item),
                colors = lime, modifier = Modifier.height(30.dp), contentPadding = pad,
            ) { Text("🪙${item.price} 구매", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            equipped && (item.slot == PetCatalog.WALL || item.slot == PetCatalog.FLOOR) ->
                Text("사용 중", fontSize = 11.sp, color = RunColors.TextDim)
            equipped -> OutlinedButton(
                onClick = { onUpdate(PetEngine.unequip(state, item), "") },
                modifier = Modifier.height(30.dp), contentPadding = pad,
            ) { Text("벗기", fontSize = 11.sp, color = RunColors.TextDim) }
            else -> OutlinedButton(
                onClick = { onUpdate(PetEngine.equip(state, item), "") },
                modifier = Modifier.height(30.dp), contentPadding = pad,
            ) { Text("착용", fontSize = 11.sp, color = RunColors.Lime) }
        }
    }
}
