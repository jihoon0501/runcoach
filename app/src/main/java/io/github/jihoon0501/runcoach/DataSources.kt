package io.github.jihoon0501.runcoach

import java.time.Instant

/**
 * 러닝·걷기 기록을 가져오는 곳. 삼성 헬스(SamsungRepo)와 Health Connect(HealthConnectRepo)가 같은 모양으로 구현한다.
 * 앱의 나머지 부분(저장, 계획, 컨디션)은 어느 쪽에서 왔는지 상관없이 같은 RunSession/WalkSession을 쓴다.
 */
interface RunSource {
    /** 기록을 읽을 권한이 있는지 */
    suspend fun hasPermission(): Boolean

    /**
     * [start]~[end] 러닝과 걷기를 30일 단위로 (최근 → 과거 순) 읽는다.
     * 한 단위를 읽을 때마다 지금까지 읽은 목록을 [onChunk]로 전달한다.
     */
    suspend fun fetchAll(
        start: Instant,
        end: Instant,
        onChunk: ((List<RunSession>, List<WalkSession>) -> Unit)? = null,
    ): Pair<List<RunSession>, List<WalkSession>>
}

/** 사용자가 고른 데이터 소스 (Prefs.dataSource에 저장) */
object DataSourceKind {
    const val SAMSUNG = "SAMSUNG"
    const val HEALTH_CONNECT = "HC"
}
