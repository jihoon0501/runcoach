package io.github.jihoon0501.runcoach

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

data class CalendarInfo(val id: Long, val name: String, val account: String, val isPrimary: Boolean)

/** added=새로 등록, updated=갱신, removed=계획에서 빠져 삭제, replacedOld=앱 밖에서 등록해 둔 옛 훈련 일정 정리 */
data class CalendarResult(val added: Int, val updated: Int, val removed: Int, val replacedOld: Int, val calendar: String)

/**
 * 훈련 계획을 폰에 연결된 구글 계정 캘린더에 등록한다.
 * 폰의 캘린더 저장소에 쓰면 구글 캘린더 앱과 웹으로 자동 동기화된다 (동기화에는 잠시 시간이 걸릴 수 있다).
 *
 * - 앱이 만든 일정은 '계획 id ↔ 일정 id'를 기억해서, 계획이 바뀌면 같은 일정을 수정하거나 지운다.
 * - 계획 기간 안에 있는 '🏃 [' / '🏁 [' 로 시작하는 옛 훈련 일정(앱 밖에서 등록한 것)은 새 계획으로 교체한다.
 */
object CalendarSync {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 쓸 수 있는 구글 계정 캘린더 목록 (기본 캘린더가 맨 앞) */
    fun listCalendars(context: Context): List<CalendarInfo> {
        val out = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString(), "com.google")
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(2) ?: ""
                val owner = c.getString(3) ?: ""
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: account,
                    account = account,
                    isPrimary = c.getInt(4) == 1 || account == owner,
                )
            }
        }
        return out.sortedByDescending { it.isPrimary }
    }

    fun resolveCalendar(context: Context, savedId: Long): CalendarInfo? {
        val all = listCalendars(context)
        return all.firstOrNull { it.id == savedId } ?: all.firstOrNull()
    }

    private fun durationMin(item: PlanItem): Int = when (item.type) {
        WorkoutType.INTERVAL -> 70
        WorkoutType.TEMPO -> 60
        WorkoutType.TEST -> 60
        WorkoutType.LONG -> (item.km * 6.4 + 8).roundToInt()
        WorkoutType.EASY -> (item.km * 6.4 + 4).roundToInt()
    }

    private fun values(item: PlanItem, hour: Int, minute: Int, calendarId: Long, packageName: String): ContentValues {
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.of(item.date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        val end = start + durationMin(item) * 60_000L
        val desc = buildString {
            append("목표 %.1fkm · 목표 페이스 %s/km\n".format(item.km, formatPace(PlanEngine.targetPace(item).toDouble())))
            item.tips.forEach { append("• $it\n") }
            if (item.rescheduled) append("(날짜가 변경된 훈련이에요)\n")
            append("\n— RunCoach 앱에서 만든 일정이에요. 계획이 바뀌면 앱에서 다시 공유하면 이 일정이 같이 바뀌어요.")
        }
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "${item.type.emoji} [${item.week}] ${item.title}")
            put(CalendarContract.Events.DESCRIPTION, desc)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI, "runlog://plan/${item.id}")
        }
    }

    /**
     * 오늘 이후의 계획을 캘린더에 반영한다. 캘린더를 찾지 못하면 null.
     * (백그라운드 스레드에서 호출할 것)
     */
    fun sync(context: Context, plan: List<PlanItem>, today: LocalDate, prefs: Prefs): CalendarResult? {
        val cal = resolveCalendar(context, prefs.calendarId) ?: return null
        prefs.calendarId = cal.id
        val resolver = context.contentResolver
        val zone = ZoneId.systemDefault()
        val map = prefs.loadCalendarMap().toMutableMap()
        val target = plan.filter { it.date >= today }
        val allIds = plan.map { it.id }.toSet()
        var added = 0
        var updated = 0
        var removed = 0
        var replaced = 0

        // 1) 앱 밖에서 등록해 둔 옛 훈련 일정 정리 (앱이 만든 일정은 CUSTOM_APP_URI가 있어서 제외)
        if (target.isNotEmpty()) {
            val from = target.minOf { it.date }.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = target.maxOf { it.date }.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val mappedEventIds = map.values.toSet()
            val oldIds = mutableListOf<Long>()
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.CUSTOM_APP_URI,
            )
            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} = 0"
            resolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection,
                arrayOf(cal.id.toString(), from.toString(), to.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1) ?: ""
                    val uri = c.getString(2)
                    if (uri == null && id !in mappedEventIds && (title.startsWith("🏃 [") || title.startsWith("🏁 ["))) {
                        oldIds += id
                    }
                }
            }
            oldIds.forEach {
                resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null)
                replaced++
            }
        }

        // 2) 계획에서 사라진 훈련의 일정 삭제
        val gone = map.filterKeys { it !in allIds }
        gone.values.forEach {
            resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it), null, null)
            removed++
        }
        gone.keys.forEach { map.remove(it) }

        // 3) 등록 또는 갱신
        for (item in target) {
            val v = values(item, prefs.calendarHour, prefs.calendarMinute, cal.id, context.packageName)
            val existing = map[item.id]
            if (existing != null) {
                val update = ContentValues(v).apply { remove(CalendarContract.Events.CALENDAR_ID) }
                val n = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), update, null, null)
                if (n > 0) {
                    updated++
                    continue
                }
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: continue
            val newId = ContentUris.parseId(uri)
            map[item.id] = newId
            added++
            val reminder = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, newId)
                put(CalendarContract.Reminders.MINUTES, 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
        }
        prefs.saveCalendarMap(map)
        return CalendarResult(added, updated, removed, replaced, "${cal.name} (${cal.account})")
    }
}
