package io.github.jihoon0501.runcoach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch

/**
 * 러닝 경로 지도 화면 — 2D / 3D 탭 전환 + 고도 프로필 + 구간 타임라인
 *
 * 의존성 (app/build.gradle.kts):
 *   implementation("com.google.maps.android:maps-compose:4.3.3")
 *   implementation("com.google.android.gms:play-services-maps:19.0.0")
 *
 * AndroidManifest.xml:
 *   <meta-data android:name="com.google.android.geo.API_KEY"
 *              android:value="YOUR_API_KEY"/>
 */
@Composable
fun RouteMapScreen(
    run: RunSession,
    onBack: () -> Unit = {},
) {
    // lat=0/lng=0 이거나 비정상 포인트 제거 (이런 포인트가 있으면 LatLngBounds 크래시)
    val route = run.route.filter {
        it.lat != 0.0 && it.lng != 0.0
        && it.lat in -90.0..90.0 && it.lng in -180.0..180.0
    }
    val hasRoute = route.size >= 2
    // 크래시 진단용 로그
    android.util.Log.d("RouteMap", "진입: route=${route.size}pts hasRoute=$hasRoute")
    if (route.isNotEmpty()) {
        android.util.Log.d("RouteMap", "첫 포인트: lat=${route.first().lat} lng=${route.first().lng}")
        android.util.Log.d("RouteMap", "끝 포인트: lat=${route.last().lat} lng=${route.last().lng}")
    }
    var is3D by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var selectedKmIdx by remember { mutableStateOf<Int?>(null) }
    var chartMode by remember { mutableStateOf(0) }  // 0=고도 1=페이스 2=심박 3=케이던스
    var isDarkMap by remember { mutableStateOf(true) }  // 지도 다크모드
    val fly = remember { FlyoverState() }               // 경로 플레이백 상태
    val track = remember(run.start, route.size) { buildFlyTrack(route, run.durationSec) }

    Column(
        Modifier
            .fillMaxSize()
            .background(RunColors.Bg)
            .statusBarsPadding(),  // 상태바 아래로 밀어내기
    ) {

        // ── 상단 바 1줄: 뒤로 | 날짜·거리 | 다크·크게보기 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 뒤로가기 — 넓은 터치 영역
            TextButton(
                onClick = onBack,
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("← 뒤로", color = RunColors.Violet, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            // 날짜·거리
            Text(
                "${run.date.monthValue}/${run.date.dayOfMonth}  %.1fkm".format(run.distanceKm),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            if (hasRoute) {
                // 다크/라이트 토글
                IconButton(
                    onClick = { isDarkMap = !isDarkMap },
                    modifier = Modifier.size(44.dp),
                ) {
                    Text(
                        if (isDarkMap) "☀" else "🌙",
                        color = if (isDarkMap) Color(0xFFFECA57) else Color(0xFF54A0FF),
                        fontSize = 18.sp,
                    )
                }
                // 크게보기 토글
                IconButton(
                    onClick = { isFullscreen = !isFullscreen },
                    modifier = Modifier.size(44.dp),
                ) {
                    Text(
                        if (isFullscreen) "⊡" else "⤢",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 18.sp,
                    )
                }
            }
        }

        // ── 상단 바 2줄: 2D / 3D 탭 ──
        if (hasRoute) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("2D", "3D").forEach { label ->
                        val selected = (label == "3D") == is3D
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) RunColors.Violet else Color.Transparent)
                                .fillMaxHeight()
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable { is3D = label == "3D" },
                            )
                        }
                    }
                }
            }
        }

        // ── 지도 영역 ──
        if (!hasRoute) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                GlassCard(Modifier.padding(24.dp)) {
                    Text("GPS 경로 데이터가 없어요.", color = RunColors.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Galaxy Watch로 달리면 다음 동기화에서 경로가 불러와져요. 또는 삼성 헬스 앱에서 FIT 파일을 내보내 가져오기 하면 돼요.",
                        color = RunColors.TextDim, fontSize = 11.sp, lineHeight = 16.sp,
                    )
                }
            }
        } else {
            var mapError by remember { mutableStateOf<String?>(null) }
            if (mapError != null) {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    GlassCard(Modifier.padding(24.dp)) {
                        Text("지도를 불러올 수 없어요", color = RunColors.Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(mapError ?: "", color = RunColors.TextDim, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Google Play 서비스 업데이트 후 재시도해 주세요.", color = RunColors.TextDim, fontSize = 11.sp)
                    }
                }
            } else {
            val latLngs = remember(route) { route.map { LatLng(it.lat, it.lng) } }
            val bounds = remember(latLngs) {
                runCatching {
                    LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
                }.getOrElse { e ->
                    android.util.Log.e("RouteMap", "bounds 오류: $e")
                    LatLngBounds(latLngs.first(), latLngs.first())
                }
            }
            val cameraState = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(bounds.center, 15f)
            }

            // 2D ↔ 3D 전환 시 카메라 애니메이션 — 지도 완전히 로드된 후에만 실행
            var mapLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(bounds, is3D, mapLoaded) {
                if (!mapLoaded) return@LaunchedEffect
                if (fly.active) return@LaunchedEffect   // 플레이백 중에는 카메라를 건드리지 않음
                runCatching {
                    if (is3D) {
                        val bearing = run.route.zipWithNext()
                            .lastOrNull()
                            ?.let { (a, b) ->
                                Math.toDegrees(
                                    Math.atan2(b.lng - a.lng, b.lat - a.lat)
                                ).toFloat()
                            } ?: 0f
                        cameraState.animate(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(bounds.center)
                                    .zoom(16f)
                                    .tilt(50f)
                                    .bearing(bearing)
                                    .build()
                            ), 800
                        )
                    } else {
                        cameraState.animate(
                            CameraUpdateFactory.newLatLngBounds(bounds, 32), 600
                        )
                    }
                }
            }

            // ── 경로 플레이백 (실제 시간 기준) ──
            val runnerState = remember { MarkerState(latLngs.first()) }
            val flyScope = rememberCoroutineScope()

            LaunchedEffect(fly.playing, track) {
                val tr = track ?: return@LaunchedEffect
                if (!fly.playing) return@LaunchedEffect
                if (fly.progress >= 0.999f) fly.progress = 0f
                var tNow = fly.progress.toDouble() * tr.totalPt
                var lastWritten = fly.progress
                var lastUi = 0L
                var last = 0L
                fly.resetCamera()
                applyFlyFrame(tr, tNow, 0.0, fly, cameraState, runnerState, true)
                while (fly.playing) {
                    val now = withFrameNanos { it }
                    if (last == 0L) { last = now; lastUi = now; continue }
                    // 프레임이 밀려도 한 번에 크게 점프하지 않도록 dt 상한
                    val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(0.05)
                    last = now
                    var snap = false
                    if (fly.progress != lastWritten) {
                        // 슬라이더로 직접 이동함
                        tNow = fly.progress.toDouble() * tr.totalPt
                        lastWritten = fly.progress
                        snap = true
                    } else {
                        tNow = (tNow + dt * FLY_SPEEDS[fly.speedIdx]).coerceAtMost(tr.totalPt)
                    }
                    applyFlyFrame(tr, tNow, dt, fly, cameraState, runnerState, snap)
                    val ended = tNow >= tr.totalPt
                    // 슬라이더·정보 칩은 0.1초마다만 갱신 (매 프레임 다시 그리면 카메라가 끊김)
                    if (ended || now - lastUi >= 100_000_000L) {
                        fly.progress = (tNow / tr.totalPt).toFloat()
                        lastWritten = fly.progress
                        lastUi = now
                    }
                    if (ended) fly.playing = false
                }
            }

            val stopFly: () -> Unit = {
                fly.playing = false
                fly.active = false
                fly.progress = 0f
                fly.resetCamera()
                flyScope.launch {
                    runCatching {
                        if (is3D) {
                            cameraState.animate(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(bounds.center).zoom(16f).tilt(50f).build()
                                ), 600
                            )
                        } else {
                            cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 32), 600)
                        }
                    }
                }
            }
            val togglePlay: () -> Unit = {
                if (fly.playing) {
                    fly.playing = false
                } else {
                    if (!fly.active) { fly.active = true; fly.progress = 0f }
                    fly.playing = true
                }
            }
            val seekFly: (Float) -> Unit = { v ->
                if (!fly.active) fly.active = true
                fly.progress = v
                track?.let {
                    applyFlyFrame(it, v.toDouble() * it.totalPt, 0.0, fly, cameraState, runnerState, true)
                }
            }
            // 재생 중 뒤로가기 → 화면을 나가지 않고 플레이백만 종료
            BackHandler(enabled = fly.active) { stopFly() }

            // GoogleMap 단일 인스턴스 유지 — is3D 전환 시 재생성하지 않음 (크래시 방지)
            Box(
                Modifier.fillMaxWidth().let { m ->
                    if (isFullscreen) m.fillMaxHeight()
                    else m.height(if (is3D || fly.active) 320.dp else 260.dp).heightIn(min = 200.dp)
                }
            ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                onMapLoaded = { mapLoaded = true },
                cameraPositionState = cameraState,
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    tiltGesturesEnabled = is3D || fly.active,
                    scrollGesturesEnabled = !fly.playing,
                    zoomGesturesEnabled = !fly.playing,
                    rotationGesturesEnabled = !fly.playing,
                ),
                properties = MapProperties(
                    mapType = if (is3D) MapType.HYBRID else MapType.NORMAL,
                    isBuildingEnabled = is3D || fly.active,
                    // DARK_MAP_STYLE 임시 비활성화 — 크래시 원인 격리 목적
                mapStyleOptions = null,
                ),
            ) {
                if (run.segments.isNotEmpty()) {
                    PaceGradientPolyline(route, run.segments, selectedKmIdx, run.splits, run.mainSec)
                } else {
                    Polyline(points = latLngs, color = RunColors.Violet, width = 10f)
                }
                // 선택 km 구간 경계 마커
                if (selectedKmIdx != null) {
                    val (kStart, kEnd) = kmRangeMs(route, run.splits, run.mainSec, selectedKmIdx!!)
                    val kPts = route.filter { it.timestampMs in kStart..kEnd }
                    kPts.firstOrNull()?.let { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "${selectedKmIdx!! + 1}km 시작") }
                    kPts.lastOrNull()?.let  { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "${selectedKmIdx!! + 1}km 끝") }
                }
                Marker(state = MarkerState(latLngs.first()), title = "출발 🏃")
                Marker(state = MarkerState(latLngs.last()),  title = "도착 🏁")
                if (fly.active) {
                    Marker(
                        state = runnerState, title = "러너", zIndex = 5f,
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                    )
                }
            }
            if (track != null) {
                FlyoverOverlay(
                    fly = fly,
                    track = track,
                    onPlayPause = togglePlay,
                    onStop = stopFly,
                    onSeek = seekFly,
                )
            }
            }  // Box (지도 + 플레이백 오버레이)
        }

        // ── 요약 바 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RouteStatItem("거리",    "%.2fkm".format(run.distanceKm))
            RouteStatItem("시간",    formatDuration(run.durationSec))
            RouteStatItem("페이스",  formatPace(run.paceSecPerKm))
            run.avgHr?.let { RouteStatItem("평균 심박", "${it}bpm") }
        }

        // ── 고도 프로필 (GPS 있을 때만) ──
        val hasAlt = route.any { it.altitudeM > 0.5 }
        if (hasAlt) {
            Divider(color = Color.White.copy(alpha = 0.08f))
            DataChart(
                route   = route,
                session = run,
                mode    = chartMode,
                onClick = { chartMode = (chartMode + 1) % 4 },
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        Divider(color = Color.White.copy(alpha = 0.08f))

        // ── 구간 타임라인 ──
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (run.segments.isNotEmpty()) {
                item { Text("구간 타임라인", color = RunColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp)) }
                items(run.segments) { seg -> SegmentTimelineRow(seg) }
            }
            if (run.splits.size >= 2) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (selectedKmIdx != null) "${selectedKmIdx!! + 1}km 구간 선택됨 — 경로 하이라이트 중"
                        else "km 페이스  (구간 탭 → 경로 강조)",
                        color = if (selectedKmIdx != null) RunColors.Violet else RunColors.TextDim,
                        fontSize = 11.sp,
                        fontWeight = if (selectedKmIdx != null) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(4.dp))
                    SplitBarsSelectable(
                        splits      = run.splits,
                        selectedIdx = selectedKmIdx,
                        onSelect    = { idx -> selectedKmIdx = if (selectedKmIdx == idx) null else idx },
                    )
                }
            }
            }  // mapError else
        }
    }
}

// ─── 고도 프로필 차트 ───────────────────────────────────────
@Composable
private fun AltitudeProfile(route: List<RoutePoint>, modifier: Modifier = Modifier) {
    val alts = route.map { it.altitudeM }
    val minA = alts.min(); val maxA = alts.max()
    val range = (maxA - minA).coerceAtLeast(1.0)
    val gain  = alts.zipWithNext().sumOf { (a, b) -> if (b > a) b - a else 0.0 }  // 누적 상승고도
    val mid   = (minA + maxA) / 2.0

    Column(modifier) {
        // 스케일 텍스트 행 (최고 / 중간 / 최저)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Canvas — 차트 본체
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val step = w / (alts.size - 1).coerceAtLeast(1)
                val pts = alts.mapIndexed { i, a ->
                    androidx.compose.ui.geometry.Offset(
                        i * step,
                        h * (1f - ((a - minA) / range).toFloat()).coerceIn(0f, 1f),
                    )
                }
                // 채우기
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h); close()
                }
                drawPath(path, androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(RunColors.Violet.copy(alpha = 0.55f), RunColors.Violet.copy(alpha = 0.05f))
                ))
                // 선
                for (i in 0 until pts.lastIndex) {
                    drawLine(RunColors.Violet, pts[i], pts[i + 1], strokeWidth = 2.dp.toPx())
                }
                // 중간 눈금선 (점선)
                val midY = h * 0.5f
                var x = 0f
                while (x < w) {
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f),
                        start = androidx.compose.ui.geometry.Offset(x, midY),
                        end   = androidx.compose.ui.geometry.Offset(x + 6.dp.toPx(), midY),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += 12.dp.toPx()
                }
            }
            // 최고 고도 — 왼쪽 상단
            Text(
                "▲ ${"%.0f".format(maxA)}m",
                color = RunColors.Violet.copy(alpha = 0.9f),
                fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // 최저 고도 — 왼쪽 하단
            Text(
                "▼ ${"%.0f".format(minA)}m",
                color = RunColors.TextDim,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            // 누적 상승 — 오른쪽 상단
            Text(
                "+${"%.0f".format(gain)}m",
                color = RunColors.Lime.copy(alpha = 0.85f),
                fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            // 고도차 — 오른쪽 하단
            Text(
                "↕ ${"%.0f".format(maxA - minA)}m",
                color = RunColors.TextDim,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        // 하단 레이블
        Spacer(Modifier.height(1.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("고도 프로필", color = RunColors.TextDim, fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("범위 ${"%.0f".format(minA)}–${"%.0f".format(maxA)}m", color = RunColors.TextDim, fontSize = 8.sp)
        }
    }
}

// ─── 구간 타임라인 행 ───────────────────────────────────────
@Composable
private fun SegmentTimelineRow(seg: RunSegment) {
    // 경로 그라데이션과 동일한 색상 체계
    val kindColor = when (seg.kind) {
        SegKind.REST -> RunColors.TextDim
        SegKind.WALK -> PACE_COLORS[6]
        SegKind.JOG  -> if (seg.paceSecPerKm > 0) PACE_COLORS[paceBucket(seg.paceSecPerKm)]
                        else PACE_COLORS[5]
        SegKind.RUN  -> if (seg.paceSecPerKm > 0) PACE_COLORS[paceBucket(seg.paceSecPerKm)]
                        else PACE_COLORS[3]
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(32.dp).background(kindColor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${seg.role.label} · ${seg.kind.label}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text("%.1fkm · %s".format(seg.distanceKm, formatDuration(seg.durSec)), color = RunColors.TextDim, fontSize = 10.sp)
        }
        seg.avgHr?.let { Text("♥$it", color = RunColors.Pink, fontSize = 11.sp) }
        Spacer(Modifier.width(8.dp))
        if (seg.paceSecPerKm > 0) Text(formatPace(seg.paceSecPerKm), color = RunColors.TextDim, fontSize = 10.sp)
    }
}

// ─── GPS 페이스 그라데이션 ───────────────────────────────────────
// 같은 색상 구간을 하나의 Polyline으로 묶어 렌더링 (OOM 방지)

/** 두 위도·경도 간 거리(km) — Haversine 근사 */
private fun haversineKm(a: LatLng, b: LatLng): Double {
    val R = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.latitude)) *
            Math.cos(Math.toRadians(b.latitude)) *
            Math.sin(dLng / 2).let { it * it }
    return R * 2 * Math.asin(Math.sqrt(h))
}

/** 페이스(초/km) → 색상 버킷 인덱스 (0~7) */
private fun paceBucket(paceSecPerKm: Double): Int = when {
    paceSecPerKm < 240 -> 0  // < 4:00  빨강
    paceSecPerKm < 270 -> 1  // < 4:30  주황빨강
    paceSecPerKm < 300 -> 2  // < 5:00  주황
    paceSecPerKm < 330 -> 3  // < 5:30  노랑
    paceSecPerKm < 360 -> 4  // < 6:00  민트
    paceSecPerKm < 420 -> 5  // < 7:00  파랑
    paceSecPerKm < 480 -> 6  // < 8:00  보라
    else               -> 7  // 느림    연보라
}

private val PACE_COLORS = listOf(
    Color(0xFFFF4757), Color(0xFFFF6B35), Color(0xFFFF9F43), Color(0xFFFECA57),
    Color(0xFF1DD1A1), Color(0xFF54A0FF), Color(0xFF5F27CD), Color(0xFFA29BFE),
)

/**
 * 포인트별 페이스 버킷을 계산한 뒤 같은 버킷끼리 묶어 Polyline 하나로 그림.
 * 최대 Polyline 수 = 색상 수(8) × 연속 구간 수 — 수천 개 개별 Polyline을 방지.
 */
@Composable
private fun PaceGradientPolyline(
    route: List<RoutePoint>,
    segments: List<RunSegment>,
    selectedKmIdx: Int? = null,
    splits: List<Int> = emptyList(),
    mainSec: Long = 0L,
) {
    if (route.size < 2) {
        Polyline(points = route.map { LatLng(it.lat, it.lng) }, color = RunColors.Violet, width = 10f)
        return
    }
    // 비정상 포인트(lat/lng=0, NaN, Inf) 한 번 더 걸러내기
    val safeRoute = route.filter {
        it.lat.isFinite() && it.lng.isFinite() && it.lat != 0.0 && it.lng != 0.0
    }
    if (safeRoute.size < 2) {
        Polyline(points = route.map { LatLng(it.lat, it.lng) }, color = RunColors.Violet, width = 10f)
        return
    }

    val hasTs = safeRoute.any { it.timestampMs > 0 }

    if (hasTs) {
        // ── GPS 타임스탬프 기반: 슬라이딩 윈도우 페이스 계산 ──
        val W = 12  // 평활화 윈도우(포인트)
        // 포인트별 버킷 배열 계산 (Composable 밖 로직)
        val buckets = IntArray(safeRoute.size) { 7 }
        for (i in safeRoute.indices) {
            val ws = maxOf(0, i - W / 2)
            val we = minOf(safeRoute.size - 1, i + W / 2)
            val seg = safeRoute.subList(ws, we + 1)
            val dt = (seg.last().timestampMs - seg.first().timestampMs) / 1000.0
            val dk = (0 until seg.size - 1).sumOf { j ->
                haversineKm(LatLng(seg[j].lat, seg[j].lng), LatLng(seg[j+1].lat, seg[j+1].lng))
            }
            buckets[i] = paceBucket(if (dk > 0.001 && dt > 0) dt / dk else 999.0)
        }
        // 같은 버킷 연속 구간을 하나의 Polyline으로
        var segStart = 0
        while (segStart < route.size - 1) {
            val b = buckets[segStart]
            var segEnd = segStart + 1
            while (segEnd < route.size && buckets[segEnd] == b) segEnd++
            // segStart..segEnd-1 범위 (경계 포인트 공유로 이음매 없애기)
            val pts = route.subList(segStart, minOf(segEnd + 1, route.size))
                .map { LatLng(it.lat, it.lng) }
            if (pts.size >= 2) Polyline(points = pts, color = PACE_COLORS[b], width = 12f)
            segStart = segEnd
        }
    } else if (segments.isNotEmpty()) {
        // ── 세그먼트 기반 fallback ──
        val totalSec = segments.sumOf { it.durSec }.toDouble().coerceAtLeast(1.0)
        var ptIdx = 0
        segments.forEach { seg ->
            val count = ((seg.durSec / totalSec) * route.size).toInt().coerceAtLeast(2)
            val end = minOf(ptIdx + count, route.size)
            val pts = route.subList(ptIdx, end).map { LatLng(it.lat, it.lng) }
            if (pts.size >= 2) {
                val pace = if (seg.distanceKm > 0) seg.durSec / seg.distanceKm else 999.0
                Polyline(points = pts, color = PACE_COLORS[paceBucket(pace)], width = 12f)
            }
            ptIdx = end - 1  // 이음매 공유
        }
    } else {
        Polyline(points = route.map { LatLng(it.lat, it.lng) }, color = RunColors.Violet, width = 12f)
    }
    // km 구간 선택 시: 미선택 구간 회색 오버레이 → 선택 구간 강조
    if (selectedKmIdx != null && splits.isNotEmpty() && route.any { it.timestampMs > 0 }) {
        // 전체 경로 위에 회색 반투명 오버레이 (기존 그라데이션 덮기)
        val allPts = route.map { LatLng(it.lat, it.lng) }
        Polyline(points = allPts, color = Color(0xFF888888).copy(alpha = 0.55f), width = 12f)
        // 선택 구간만 원래 페이스 색 + 흰 테두리로 강조
        val (kStart, kEnd) = kmRangeMs(safeRoute, splits, mainSec, selectedKmIdx)
        val kPts = safeRoute.filter { it.timestampMs in kStart..kEnd }.map { LatLng(it.lat, it.lng) }
        if (kPts.size >= 2) {
            Polyline(points = kPts, color = Color.White.copy(alpha = 0.95f), width = 20f)
            val sec = splits.getOrElse(selectedKmIdx) { 300 }.toDouble()
            Polyline(points = kPts, color = PACE_COLORS[paceBucket(sec)], width = 14f)
        }
    }
}

// ─── 통계 항목 ──────────────────────────────────────────────
@Composable
private fun RouteStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = RunColors.TextDim, fontSize = 10.sp)
    }
}

// ─── km 구간 타임스탬프 범위 계산 ────────────────────────────────────────────
/**
 * splits(본운동 1km 구간 초)와 mainSec(본운동 총 시간)로
 * selectedKmIdx번째 km 구간의 [startMs, endMs] 반환.
 * route의 타임스탬프 기준으로 보정.
 */
private fun kmRangeMs(
    route: List<RoutePoint>,
    splits: List<Int>,
    mainSec: Long,
    idx: Int,
): Pair<Long, Long> {
    if (route.isEmpty() || splits.isEmpty()) return 0L to 0L
    val t0 = route.first().timestampMs
    val tEnd = route.last().timestampMs
    val totalMs = (tEnd - t0).coerceAtLeast(1L)
    // 본운동 시작 추정: 전체 시간에서 mainSec 비율로 오프셋
    val mainOffsetMs = totalMs - mainSec * 1000L
    val mainStartMs = (t0 + mainOffsetMs.coerceAtLeast(0L))
    // idx번째 km 구간 시작/끝 (초→ms)
    val segStartSec = splits.take(idx).sum().toLong()
    val segEndSec   = splits.take(idx + 1).sum().toLong()
    return (mainStartMs + segStartSec * 1000L) to (mainStartMs + segEndSec * 1000L)
}

// ─── km 구간 선택 가능한 막대 차트 ───────────────────────────────────────────
@Composable
fun SplitBarsSelectable(
    splits: List<Int>,
    selectedIdx: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (splits.size < 2) return
    val minS = splits.min()
    val maxS = splits.max()
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            splits.forEachIndexed { idx, sec ->
                val frac = if (maxS - minS < 3) 0.7f
                           else 0.35f + 0.65f * (maxS - sec).toFloat() / (maxS - minS).toFloat()
                val isSelected = idx == selectedIdx
                val barColor = PACE_COLORS[paceBucket(sec.toDouble())].let {
                    if (isSelected) it else it.copy(alpha = 0.45f)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(idx) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        "${sec / 60}:${"%02d".format(sec % 60)}",
                        fontSize = if (isSelected) 8.sp else 7.sp,
                        color = if (isSelected) RunColors.Violet else RunColors.TextDim,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (isSelected) 0.68f * frac + 0.08f else 0.68f * frac)
                            .clip(RoundedCornerShape(if (isSelected) 6.dp else 4.dp))
                            .background(barColor)
                            .then(
                                if (isSelected) Modifier.border(1.5.dp, RunColors.Violet, RoundedCornerShape(6.dp))
                                else Modifier
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            splits.indices.forEach { i ->
                Text(
                    "${i + 1}km",
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = if (i == selectedIdx) 8.sp else 7.sp,
                    color = if (i == selectedIdx) RunColors.Violet else RunColors.TextDim,
                    fontWeight = if (i == selectedIdx) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ─── 범용 데이터 차트 (고도·페이스·심박·케이던스 토글) ────────────────────────

private val CHART_LABELS = listOf("고도", "페이스", "심박수", "케이던스")
private val CHART_UNITS  = listOf("m", "'/km", "bpm", "spm")
private val CHART_COLORS = listOf(
    Color(0xFF9B59B6),  // 고도  — 보라
    Color(0xFFFF9F43),  // 페이스 — 주황
    Color(0xFFFF4757),  // 심박  — 빨강
    Color(0xFF1DD1A1),  // 케이던스 — 민트
)

/** 모드에 따라 RoutePoint에서 값 추출 */
private fun RoutePoint.chartValue(mode: Int): Double? = when (mode) {
    0 -> altitudeM.takeIf { it > 0.5 }
    1 -> speedMps?.takeIf { it > 0.1 }?.let { spd ->
            if (spd > 0) 1000.0 / (spd * 60.0) else null  // m/s → 분/km
         }
    2 -> hr?.toDouble()
    3 -> cadence?.toDouble()
    else -> null
}

@Composable
private fun DataChart(
    route: List<RoutePoint>,
    session: RunSession,
    mode: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = CHART_COLORS[mode]
    val label = CHART_LABELS[mode]
    val unit  = CHART_UNITS[mode]

    // 값 추출 — 없으면 세션 평균으로 fallback
    val vals: List<Double> = route.mapNotNull { it.chartValue(mode) }.let { pts ->
        if (pts.size >= 2) pts
        else when (mode) {
            2 -> session.avgHr?.let { listOf(it.toDouble(), it.toDouble()) } ?: emptyList()
            3 -> session.cadence?.let { listOf(it.toDouble(), it.toDouble()) } ?: emptyList()
            else -> emptyList()
        }
    }

    if (vals.isEmpty()) {
        // 데이터 없음 안내
        Box(
            modifier.clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text("$label 데이터 없음 — 탭하면 전환", color = RunColors.TextDim, fontSize = 10.sp)
        }
        return
    }

    // 이상값(GPS 오류, 신호대기 등) 클리핑 — 페이스는 5~95 퍼센타일 사용
    val sorted = vals.sorted()
    val p05 = sorted[(sorted.size * 0.05).toInt().coerceIn(0, sorted.size - 1)]
    val p95 = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
    // 페이스(mode=1)는 p05~p95 범위로, 나머지는 전체 범위 사용
    val minV = if (mode == 1) p05 else sorted.first()
    val maxV = if (mode == 1) p95 else sorted.last()
    val range = (maxV - minV).coerceAtLeast(0.1)

    // 페이스는 값이 클수록 느린 것 → Y축 반전
    val invert = mode == 1

    fun yFrac(v: Double): Float {
        val f = ((v - minV) / range).toFloat().coerceIn(0f, 1f)
        return if (invert) f else 1f - f
    }

    // 표시 포맷
    fun fmt(v: Double): String = when (mode) {
        1 -> { val t = v.toInt(); "${t / 1}:${"%02d".format(((v % 1) * 60).toInt())}" }
        else -> "%.0f".format(v)
    }

    Column(modifier.clickable { onClick() }) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val step = w / (vals.size - 1).coerceAtLeast(1)
                val pts = vals.mapIndexed { i, v ->
                    androidx.compose.ui.geometry.Offset(i * step, h * yFrac(v))
                }
                // 채우기
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts.first().x, h)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, h); close()
                }
                drawPath(path, androidx.compose.ui.graphics.Brush.verticalGradient(
                    if (invert) listOf(color.copy(alpha = 0.08f), color.copy(alpha = 0.5f))
                    else        listOf(color.copy(alpha = 0.5f),  color.copy(alpha = 0.08f))
                ))
                // 선
                for (i in 0 until pts.lastIndex) {
                    drawLine(color, pts[i], pts[i + 1], strokeWidth = 2.dp.toPx())
                }
                // 중간 눈금선
                val midY = h * 0.5f
                var x = 0f
                while (x < w) {
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f),
                        start = androidx.compose.ui.geometry.Offset(x, midY),
                        end   = androidx.compose.ui.geometry.Offset(x + 6.dp.toPx(), midY),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += 12.dp.toPx()
                }
            }
            // 최대값 — 페이스는 최소(빠른)를 위에
            val topVal = if (invert) minV else maxV
            val botVal = if (invert) maxV else minV
            Text("▲ ${fmt(topVal)}$unit", color = color.copy(alpha = 0.9f),
                fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart))
            Text("▼ ${fmt(botVal)}$unit", color = RunColors.TextDim,
                fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart))
            // 오른쪽: 평균
            val avg = vals.average()
            Text("평균 ${fmt(avg)}$unit", color = color.copy(alpha = 0.8f),
                fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd))
            // 차트 종류 표시 (탭 힌트)
            Text("$label  ▶", color = RunColors.TextDim.copy(alpha = 0.6f),
                fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(1.dp))
        // 하단 도트 인디케이터
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            CHART_LABELS.forEachIndexed { i, lbl ->
                val sel = i == mode
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (sel) 6.dp else 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) CHART_COLORS[i] else Color.White.copy(alpha = 0.2f))
                )
            }
        }
    }
}

// ─── Google Maps 다크 스타일 JSON ─────────────────────────────────────────────
private const val DARK_MAP_STYLE = """[
  {"elementType":"geometry","stylers":[{"color":"#212121"}]},
  {"elementType":"labels.icon","stylers":[{"visibility":"off"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#757575"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#212121"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#2c2c2c"}]},
  {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#8a8a8a"}]},
  {"featureType":"road.arterial","elementType":"geometry","stylers":[{"color":"#373737"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#3c3c3c"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#000000"}]},
  {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#3d3d3d"}]},
  {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#2d2d2d"}]},
  {"featureType":"poi","elementType":"labels.text.fill","stylers":[{"color":"#757575"}]},
  {"featureType":"transit","elementType":"geometry","stylers":[{"color":"#2f3948"}]}
]"""
