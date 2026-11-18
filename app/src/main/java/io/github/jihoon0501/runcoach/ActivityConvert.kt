package io.github.jihoon0501.runcoach

import java.time.Instant

/** 파일에서 읽은 활동([ParsedActivity])을 앱의 러닝/걷기 기록으로 바꾼다. 안드로이드에 의존하지 않는다. */
object ActivityConverter {

    /**
     * 속도(m/s) 시계열. 파일에 속도가 있으면 그대로(절반 이상의 점에 있을 때), 아니면 누적 거리 차이로 계산한다.
     * 30초 넘게 비는 구간(일시정지)은 샘플을 만들지 않는다.
     */
    private fun speedSeries(points: List<FilePoint>): List<Pair<Long, Double>> {
        if (points.size < 2) return emptyList()
        val withSpeed = points.count { it.speed != null }
        val out = ArrayList<Pair<Long, Double>>(points.size)
        if (withSpeed >= points.size / 2) {
            for (p in points) p.speed?.let { out += p.tMs to it }
            return out
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d0 = a.distM
            val d1 = b.distM
            if (d0 == null || d1 == null) continue
            val dt = (b.tMs - a.tMs) / 1000.0
            val dd = d1 - d0
            if (dt > 0.0 && dt <= 30.0 && dd >= 0.0) out += b.tMs to dd / dt
        }
        return out
    }

    fun toRun(a: ParsedActivity): RunSession? {
        if (a.sport != FileSport.RUN) return null
        val durSec = ((a.endMs - a.startMs) / 1000).coerceAtLeast(0)
        if (durSec < 60) return null
        val speed = speedSeries(a.points)
        val hr = a.points.mapNotNull { p -> p.hr?.takeIf { it in 30..250 }?.let { p.tMs to it.toDouble() } }
        val an = RunAnalyzer.analyze(speed, hr)
        val distKm = ((a.distanceM ?: 0.0) / 1000.0).takeIf { it > 0.0 } ?: an.segments.sumOf { it.distanceKm }
        if (distKm < 0.1) return null
        // 달리기 케이던스는 파일에 '한 발 기준'(보통 80~100)으로 적힌 경우가 많아서 120 미만이면 2배로 바꾼다
        val rawCad = a.avgCadence ?: a.points.mapNotNull { it.cadence }.filter { it > 0 }.let { if (it.isEmpty()) null else it.average().toInt() }
        val cadence = rawCad?.let { if (it in 1..119) it * 2 else it }
        return RunSession(
            start = Instant.ofEpochMilli(a.startMs),
            durationSec = durSec,
            distanceKm = distKm,
            avgHr = a.avgHr ?: an.avgHr ?: hr.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.toInt(),
            maxHr = a.maxHr,
            cadence = cadence,
            lapCount = 0,
            title = a.title,
            splits = an.splits,
            mainKm = an.mainKm,
            mainSec = an.mainSec,
            segments = an.segments,
        )
    }

    fun toWalk(a: ParsedActivity): WalkSession? {
        if (a.sport != FileSport.WALK) return null
        val durSec = ((a.endMs - a.startMs) / 1000).coerceAtLeast(0)
        val km = (a.distanceM ?: 0.0) / 1000.0
        if (durSec < 300 || km < 0.3) return null
        return WalkSession(Instant.ofEpochMilli(a.startMs), durSec, km, a.avgHr)
    }
}
