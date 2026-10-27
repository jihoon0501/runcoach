package io.github.jihoon0501.runcoach

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Reminder {
    const val CHANNEL_ID = "daily_run"
    const val ACTION = "io.github.jihoon0501.runcoach.DAILY_REMINDER"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "오늘의 러닝 알림", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "오늘 해야 할 러닝 훈련을 알려줘요"
            nm.createNotificationChannel(ch)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, 1001, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 설정한 시각의 다음 알림을 예약 (끄면 취소). 정확한 알람 권한이 필요 없는 방식이라 몇 분 오차가 있을 수 있어요. */
    fun schedule(context: Context) {
        val prefs = Prefs(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!prefs.reminderOn) {
            am.cancel(alarmIntent(context))
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(prefs.reminderHour, prefs.reminderMinute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context))
    }

    /** 오늘 계획이 있으면 알림을 띄운다. 알림을 띄웠으면 true */
    fun notifyToday(context: Context, force: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val today = LocalDate.now()
        val item = Prefs(context).loadPlan()?.firstOrNull { it.date == today }
        if (item == null && !force) return false

        ensureChannel(context)
        val title: String
        val body: String
        if (item != null) {
            title = "오늘의 러닝 · ${item.title}"
            body = "%.0fkm · 목표 %s/km\n%s".format(
                item.km,
                formatPace(PlanEngine.targetPace(item).toDouble()),
                item.tips.firstOrNull() ?: ""
            )
        } else {
            title = "오늘은 휴식일이에요"
            body = "예정된 러닝이 없어요. 가볍게 스트레칭해 보세요."
        }

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(context, 1002, open, PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2001, n)
        return true
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminder.schedule(context)
        } else {
            Reminder.notifyToday(context)
            Reminder.schedule(context)   // 다음 날 알림 예약
        }
    }
}
