package site.anzz.childkiosk.performance

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import site.anzz.childkiosk.util.ProcessUtils
import java.util.UUID

enum class HighPerformanceActivityState {
    CREATED,
    STARTED,
    RESUMED,
    STOPPED,
    DESTROYED
}

data class HighPerformanceProcessSnapshot(
    val processInstanceId: String,
    val processName: String,
    val pid: Int,
    val processStartedAt: Long,
    val isWebViewProcess: Boolean,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdkInt: Int,
    val manufacturer: String,
    val model: String,
    val webViewPackageName: String?,
    val webViewVersionName: String?,
    val notificationPermissionGranted: Boolean,
    val notificationsVisible: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val screenInteractive: Boolean,
    val keyguardShowing: Boolean,
    val keyguardSecure: Boolean,
    val keyguardReadyForScreenOff: Boolean,
    val foregroundServiceDeclared: Boolean,
    val specialUseTypeDeclared: Boolean
)

/** Process and system facts which must never be persisted as a long-lived "ready" boolean. */
object HighPerformanceProcessState {
    val processInstanceId: String = UUID.randomUUID().toString()
    val processStartedAt: Long = System.currentTimeMillis()

    fun collect(context: Context): HighPerformanceProcessSnapshot {
        val appContext = context.applicationContext
        val notificationPermissionGranted = notificationPermissionGranted(appContext)
        val notificationsVisible = notificationPermissionGranted &&
            NotificationManagerCompat.from(appContext).areNotificationsEnabled() &&
            notificationChannelVisible(appContext)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val keyguardReadyForScreenOff = keyguardManager != null &&
            !keyguardManager.isKeyguardSecure &&
            !keyguardManager.isKeyguardLocked
        val declaration = foregroundServiceDeclaration(appContext)
        val appPackageInfo = appPackageInfo(appContext)
        val webViewPackageInfo = runCatching {
            WebViewCompat.getCurrentWebViewPackage(appContext)
        }.getOrNull()
        return HighPerformanceProcessSnapshot(
            processInstanceId = processInstanceId,
            processName = ProcessUtils.currentProcessName(appContext),
            pid = Process.myPid(),
            processStartedAt = processStartedAt,
            isWebViewProcess = ProcessUtils.isWebViewProcess(appContext),
            appVersionName = appPackageInfo?.versionName.orEmpty().ifBlank { "unknown" },
            appVersionCode = appPackageInfo?.longVersionCode ?: 0L,
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            androidSdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty().trim().ifBlank { "unknown" },
            model = Build.MODEL.orEmpty().trim().ifBlank { "unknown" },
            webViewPackageName = webViewPackageInfo?.packageName?.takeIf { it.isNotBlank() },
            webViewVersionName = webViewPackageInfo?.versionName?.takeIf { it.isNotBlank() },
            notificationPermissionGranted = notificationPermissionGranted,
            notificationsVisible = notificationsVisible,
            ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            screenInteractive = powerManager?.isInteractive != false,
            keyguardShowing = keyguardManager?.isKeyguardLocked == true,
            keyguardSecure = keyguardManager?.isKeyguardSecure == true,
            keyguardReadyForScreenOff = keyguardReadyForScreenOff,
            foregroundServiceDeclared = declaration.first,
            specialUseTypeDeclared = declaration.second
        )
    }

    fun notificationPermissionGranted(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isRecordedProcessAlive(context: Context, status: HighPerformanceRuntimeStatus): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        return manager.runningAppProcesses.orEmpty().any { process ->
            process.pid == status.pid && process.processName == status.processName
        }
    }

    private fun appPackageInfo(context: Context) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }.getOrNull()

    private fun notificationChannelVisible(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val channel = manager.getNotificationChannel(HighPerformanceForegroundService.NOTIFICATION_CHANNEL_ID)
            ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun foregroundServiceDeclaration(context: Context): Pair<Boolean, Boolean> {
        return runCatching {
            val component = ComponentName(context, HighPerformanceForegroundService::class.java)
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getServiceInfo(component, 0)
            }
            val specialUse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0
            } else {
                true
            }
            true to specialUse
        }.getOrDefault(false to false)
    }
}
