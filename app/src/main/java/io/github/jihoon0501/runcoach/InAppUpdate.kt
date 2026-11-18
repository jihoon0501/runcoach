package io.github.jihoon0501.runcoach

import android.app.Activity
import android.content.IntentSender
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed

/**
 * Play Store 유연한 인앱 업데이트(Flexible In-App Update) 도우미.
 *
 * 사용 흐름:
 *  1. MainActivity.onResume()에서 checkAndStart() 호출
 *  2. 업데이트가 있으면 백그라운드 다운로드 시작
 *  3. 다운로드 완료 → onReadyToInstall 콜백 호출
 *  4. 사용자가 "지금 다시 시작" 스낵바를 누르면 completeUpdate() 호출
 *  5. MainActivity.onStop()에서 unregister() 호출
 */
class InAppUpdateManager(private val activity: Activity) {

    private val manager = AppUpdateManagerFactory.create(activity)
    var onReadyToInstall: (() -> Unit)? = null

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            Log.d("InAppUpdate", "다운로드 완료 → 설치 준비")
            onReadyToInstall?.invoke()
        }
    }

    /** 앱 시작·재개 시 호출. 업데이트가 있으면 유연한 다운로드를 시작한다 */
    fun checkAndStart() {
        manager.registerListener(listener)
        manager.appUpdateInfo.addOnSuccessListener { info: AppUpdateInfo ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && info.isFlexibleUpdateAllowed
            ) {
                Log.d("InAppUpdate", "업데이트 사용 가능 — 다운로드 시작")
                try {
                    manager.startUpdateFlowForResult(
                        info,
                        activity,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        UPDATE_REQUEST_CODE,
                    )
                } catch (e: IntentSender.SendIntentException) {
                    Log.e("InAppUpdate", "업데이트 시작 실패", e)
                }
            } else if (info.installStatus() == InstallStatus.DOWNLOADED) {
                // 이전에 다운로드했는데 설치 전 종료한 경우
                Log.d("InAppUpdate", "이미 다운로드됨 → 설치 유도")
                onReadyToInstall?.invoke()
            }
        }
    }

    /** 다운로드가 끝난 업데이트를 설치하고 앱을 재시작한다 */
    fun completeUpdate() {
        manager.completeUpdate()
    }

    /** Activity.onStop()에서 호출해 리스너 누수를 방지한다 */
    fun unregister() {
        manager.unregisterListener(listener)
    }

    companion object {
        const val UPDATE_REQUEST_CODE = 7001
    }
}
