package io.github.jihoon0501.runcoach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.MarkerState
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 경로 플레이백(플라이오버).
 * 카메라가 실제로 달린 시간 흐름대로 경로를 따라 이동한다.
 * 빠르게 달린 구간은 빠르게, 느린 구간은 느리게 지나간다.
 */

/** 재생 배속. 실제 달린 시간 대비 몇 배로 재생할지 (65분 러닝 기준 40x ≈ 1분 40초). */
internal val FLY_SPEEDS = intArrayOf(10, 20, 40, 80)

@Stable
class FlyoverState {
    var active by mutableStateOf(false)     // 플라이오버 화면 모드 (3D 시점)
    var playing by mutableStateOf(false)    // 재생 중
    var progress by mutableStateOf(0f)      // 0~1 (재생 타임라인 기준)
    var speedIdx by mutableStateOf(2)       // FLY_SPEEDS 인덱스 (기본 40x)
    var bearing: Double = Double.NaN        // 카메라 방향 (부드럽게 회전시키려고 유지)
    var camLat: Double = Double.NaN         // 필터를 거친 카메라 중심
    var camLng: Double = Double.NaN
    var camZoom: Double = Double.NaN

    fun resetCamera() {
        bearing = Double.NaN; camLat = Double.NaN; camLng = Double.NaN; camZoom = Double.NaN
    }
}

class FlyInfo(val km: Double, val rtSec: Double, val pace: Double, val hr: Int)

/** GPS 경로를 시간축으로 정리한 데이터 */
class FlyTrack(
    val lat: DoubleArray,
    val lng: DoubleArray,
    val pt: DoubleArray,     // 재생 시간(초) — 일시정지 같은 긴 공백은 압축
    val rt: DoubleArray,     // 실제 경과 시간(초) — 화면 표시용
    val km: DoubleArray,     // 누적 거리
    val hr: IntArray,        // 심박 (0 = 없음)
    val pace: DoubleArray,   // 순간 페이스 초/km (0 = 계산 불가)
    val slat: DoubleArray,   // 카메라용 평활화 위도 (GPS 흔들림 제거)
    val slng: DoubleArray,   // 카메라용 평활화 경도
) {
    val size: Int get() = lat.size
    val totalPt: Double get() = pt[pt.size - 1]

    /** 재생 시간 t(초)가 속한 구간의 시작 인덱스 (항상 0..size-2) */
    fun indexAt(t: Double): Int {
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (pt[mid] <= t) lo = mid else hi = mid
        }
        return lo
    }

    private fun frac(i: Int, t: Double): Double {
        val d = pt[i + 1] - pt[i]
        return if (d <= 1e-9) 0.0 else ((t - pt[i]) / d).coerceIn(0.0, 1.0)
    }

    fun latAt(t: Double): Double {
        val i = indexAt(t)
        return slat[i] + (slat[i + 1] - slat[i]) * frac(i, t)
    }

    fun lngAt(t: Double): Double {
        val i = indexAt(t)
        return slng[i] + (slng[i + 1] - slng[i]) * frac(i, t)
    }

    fun infoAt(t: Double): FlyInfo {
        val i = indexAt(t)
        val f = frac(i, t)
        val kmV = km[i] + (km[i + 1] - km[i]) * f
        val rtV = rt[i] + (rt[i + 1] - rt[i]) * f
        val p = if (f < 0.5) pace[i] else pace[i + 1]
        val h = if (f < 0.5) hr[i] else hr[i + 1]
        return FlyInfo(kmV, rtV, p, h)
    }
}

private fun flyHaversineKm(a1: Double, o1: Double, a2: Double, o2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(a2 - a1)
    val dLng = Math.toRadians(o2 - o1)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a1)) * cos(Math.toRadians(a2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

private fun flyBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lng2 - lng1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun flyLerpAngle(a: Double, b: Double, f: Double): Double {
    val d = ((b - a + 540.0) % 360.0) - 180.0
    return (a + d * f + 360.0) % 360.0
}

fun flyClock(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

/**
 * 경로 포인트를 재생용 시간축으로 변환한다.
 * 포인트에 시간이 모두 있으면 실제 시간을 쓰고, 하나라도 없으면 운동 시간을 균등 배분한다.
 */
fun buildFlyTrack(route: List<RoutePoint>, fallbackDurSec: Long): FlyTrack? {
    val n = route.size
    if (n < 2) return null

    val lat = DoubleArray(n) { route[it].lat }
    val lng = DoubleArray(n) { route[it].lng }

    val tsOk = route.all { it.timestampMs > 0 } &&
        route.last().timestampMs > route.first().timestampMs
    val rt = DoubleArray(n)
    if (tsOk) {
        val t0 = route.first().timestampMs
        for (i in 0 until n) {
            val v = (route[i].timestampMs - t0) / 1000.0
            rt[i] = if (i == 0) 0.0 else max(v, rt[i - 1])
        }
    } else {
        val dur = if (fallbackDurSec > 0) fallbackDurSec.toDouble() else n.toDouble()
        for (i in 0 until n) rt[i] = dur * i / (n - 1)
    }

    // 재생 시간: 20초를 넘는 공백(일시정지 등)은 2초로 줄여서 건너뛴다
    val pt = DoubleArray(n)
    for (i in 1 until n) {
        val gap = rt[i] - rt[i - 1]
        pt[i] = pt[i - 1] + (if (gap > 20.0) 2.0 else gap)
    }
    if (pt[n - 1] <= 0.0) {
        for (i in 0 until n) pt[i] = i.toDouble()
    }

    val km = DoubleArray(n)
    for (i in 1 until n) {
        km[i] = km[i - 1] + flyHaversineKm(lat[i - 1], lng[i - 1], lat[i], lng[i])
    }

    // 순간 페이스: 약 10초 창으로 계산해 GPS 흔들림을 줄인다
    val pace = DoubleArray(n)
    var j = 0
    for (i in 0 until n) {
        while (j < i - 1 && rt[i] - rt[j + 1] >= 10.0) j++
        val dt = rt[i] - rt[j]
        val dk = km[i] - km[j]
        val p = if (dt >= 5.0 && dk > 0.005) dt / dk else 0.0
        pace[i] = if (p in 1.0..1200.0) p else 0.0
    }

    // 카메라용 위치: 앞뒤 8초 평균으로 GPS 흔들림을 줄인다 (양 끝은 원래 위치 유지)
    val preLat = DoubleArray(n + 1)
    val preLng = DoubleArray(n + 1)
    for (i in 0 until n) {
        preLat[i + 1] = preLat[i] + lat[i]
        preLng[i + 1] = preLng[i] + lng[i]
    }
    val slat = DoubleArray(n)
    val slng = DoubleArray(n)
    var lo = 0
    var hi = 0
    for (i in 0 until n) {
        val hw = min(8.0, min(pt[i] - pt[0], pt[n - 1] - pt[i]))
        while (lo < i && pt[lo] < pt[i] - hw - 1e-9) lo++
        while (hi + 1 < n && pt[hi + 1] <= pt[i] + hw + 1e-9) hi++
        val cnt = (hi - lo + 1).toDouble()
        slat[i] = (preLat[hi + 1] - preLat[lo]) / cnt
        slng[i] = (preLng[hi + 1] - preLng[lo]) / cnt
    }

    val hr = IntArray(n) { route[it].hr ?: 0 }
    return FlyTrack(lat, lng, pt, rt, km, hr, pace, slat, slng)
}

/** 배속이 빠를수록 멀리서 본다. 지도 타일이 따라올 수 있는 속도를 유지하려는 목적. */
private fun flyZoomFor(mult: Int): Double =
    (17.2 - 0.75 * ln(mult / 10.0) / ln(2.0)).coerceIn(13.5, 17.5)

/**
 * 재생 시간 [t]에 맞춰 카메라와 러너 마커를 이동한다.
 * [dt]는 직전 프레임과의 간격(초). 카메라 중심·방향·줌은 dt 기반 지수 필터로 부드럽게 따라간다.
 * [snap]이 true면 필터 없이 바로 이동한다 (슬라이더로 이동할 때).
 */
fun applyFlyFrame(
    track: FlyTrack,
    t: Double,
    dt: Double,
    fly: FlyoverState,
    camera: CameraPositionState,
    runner: MarkerState,
    snap: Boolean,
) {
    val tt = t.coerceIn(0.0, track.totalPt)
    val la = track.latAt(tt)
    val lo = track.lngAt(tt)

    // 방향: 경로상 20초(재생 시간) 앞 지점을 바라본다
    val tl = min(tt + 20.0, track.totalPt)
    val movedM = flyHaversineKm(la, lo, track.latAt(tl), track.lngAt(tl)) * 1000.0
    val targetB = if (movedM >= 5.0) {
        flyBearing(la, lo, track.latAt(tl), track.lngAt(tl))
    } else {
        fly.bearing
    }
    fly.bearing = when {
        targetB.isNaN() -> 0.0
        fly.bearing.isNaN() || snap -> targetB
        else -> flyLerpAngle(fly.bearing, targetB, 1.0 - exp(-dt / 0.7))
    }

    // 중심: 목표 위치를 천천히 따라간다 (프레임 속도와 무관하게 같은 느낌)
    if (fly.camLat.isNaN() || snap) {
        fly.camLat = la
        fly.camLng = lo
    } else {
        val a = 1.0 - exp(-dt / 0.18)
        fly.camLat += (la - fly.camLat) * a
        fly.camLng += (lo - fly.camLng) * a
    }

    // 줌: 배속에 맞춰 천천히 조정
    val wantZoom = flyZoomFor(FLY_SPEEDS[fly.speedIdx])
    fly.camZoom = if (fly.camZoom.isNaN()) {
        wantZoom
    } else {
        fly.camZoom + (wantZoom - fly.camZoom) * (1.0 - exp(-dt / 0.6))
    }

    val pos = LatLng(fly.camLat, fly.camLng)
    runner.position = pos
    camera.position = CameraPosition.Builder()
        .target(pos)
        .zoom(fly.camZoom.toFloat())
        .tilt(55f)
        .bearing(fly.bearing.toFloat())
        .build()
}

// ─────────────────────────── 화면 오버레이 ───────────────────────────

@Composable
fun BoxScope.FlyoverOverlay(
    fly: FlyoverState,
    track: FlyTrack,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    // 재생 중 실시간 정보
    if (fly.active) {
        val info = track.infoAt(fly.progress.toDouble() * track.totalPt)
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlyStat("거리", "%.2fkm".format(info.km))
            FlyStat("시간", flyClock(info.rtSec))
            FlyStat("페이스", if (info.pace > 0.0) formatPace(info.pace) else "-")
            if (info.hr > 0) FlyStat("심박", "${info.hr}")
        }
    }

    // 하단 컨트롤: ▶/⏸ | 진행 슬라이더 | 배속 | ✕
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (fly.playing) "⏸" else "▶", color = Color.White, fontSize = 18.sp)
        }
        Slider(
            value = fly.progress,
            onValueChange = onSeek,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = RunColors.Violet,
                activeTrackColor = RunColors.Violet,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { fly.speedIdx = (fly.speedIdx + 1) % FLY_SPEEDS.size }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${FLY_SPEEDS[fly.speedIdx]}x",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
        }
        if (fly.active) {
            Box(
                Modifier.size(40.dp).clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun FlyStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
    }
}
