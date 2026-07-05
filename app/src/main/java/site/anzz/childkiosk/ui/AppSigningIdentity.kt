package site.anzz.childkiosk.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

internal data class AppSigningIdentity(
    val packageName: String,
    val sha1: String?,
    val error: String? = null
)

internal fun readCurrentAppSigningIdentity(context: Context): AppSigningIdentity {
    val packageName = context.packageName
    return runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = packageInfo.signingInfo
            when {
                signingInfo == null -> emptyArray()
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                signingInfo.apkContentsSigners.isNotEmpty() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory ?: emptyArray()
            }
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val signature = signatures.firstOrNull()
            ?: return AppSigningIdentity(packageName = packageName, sha1 = null, error = "未读取到 APK 签名证书")
        AppSigningIdentity(
            packageName = packageName,
            sha1 = sha1ColonHex(signature.toByteArray())
        )
    }.getOrElse { error ->
        AppSigningIdentity(
            packageName = packageName,
            sha1 = null,
            error = error.message ?: error::class.java.simpleName
        )
    }
}

private fun sha1ColonHex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
    return digest.joinToString(":") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }
}
