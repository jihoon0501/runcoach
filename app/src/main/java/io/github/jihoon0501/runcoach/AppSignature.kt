package io.github.jihoon0501.runcoach

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 지금 설치된 앱의 서명 인증서 SHA-256 지문.
 * 삼성 헬스 파트너 등록에는 '배포되는 앱의 서명 지문'이 필요해서, 등록한 값과 실제 설치본이 같은지 확인할 때 쓴다.
 */
object AppSignature {
    fun sha256(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners
        if (signers.isNullOrEmpty()) {
            null
        } else {
            MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
                .joinToString(":") { "%02X".format(it) }
        }
    } catch (e: Exception) {
        null
    }
}
