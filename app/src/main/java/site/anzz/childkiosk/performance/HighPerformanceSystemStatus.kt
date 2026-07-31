package site.anzz.childkiosk.performance

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

internal data class HighPerformanceSystemStatus(
    val notificationPermissionGranted: Boolean,
    val notificationsGloballyEnabled: Boolean,
    val notificationChannelEnabled: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val foregroundServicePermissionDeclared: Boolean,
    val specialUseForegroundServicePermissionDeclared: Boolean,
    val wakeLockPermissionDeclared: Boolean,
    val overlayPermissionGranted: Boolean,
    val manufacturer: String
) {
    val notificationsGranted: Boolean
        get() = notificationPermissionGranted &&
            notificationsGloballyEnabled &&
            notificationChannelEnabled

    val canStartFullForegroundProtection: Boolean
        get() = notificationsGranted &&
            foregroundServicePermissionDeclared &&
            specialUseForegroundServicePermissionDeclared &&
            wakeLockPermissionDeclared

    val isFullyReady: Boolean
        get() = canStartFullForegroundProtection && batteryOptimizationIgnored && overlayPermissionGranted

    val missingRequirements: Set<HighPerformanceSystemRequirement>
        get() = buildSet {
            if (!notificationsGranted) add(HighPerformanceSystemRequirement.NOTIFICATIONS)
            if (!batteryOptimizationIgnored) add(HighPerformanceSystemRequirement.BATTERY_OPTIMIZATION)
            if (!overlayPermissionGranted) add(HighPerformanceSystemRequirement.OVERLAY_PERMISSION)
            if (!foregroundServicePermissionDeclared) add(HighPerformanceSystemRequirement.FOREGROUND_SERVICE_DECLARATION)
            if (!specialUseForegroundServicePermissionDeclared) {
                add(HighPerformanceSystemRequirement.SPECIAL_USE_DECLARATION)
            }
            if (!wakeLockPermissionDeclared) add(HighPerformanceSystemRequirement.WAKE_LOCK_DECLARATION)
        }
}

internal enum class HighPerformanceSystemRequirement {
    NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
    OVERLAY_PERMISSION,
    FOREGROUND_SERVICE_DECLARATION,
    SPECIAL_USE_DECLARATION,
    WAKE_LOCK_DECLARATION
}

internal object HighPerformanceSystemStatusReader {
    fun read(context: Context): HighPerformanceSystemStatus {
        val appContext = context.applicationContext
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        val runtimeNotificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsGloballyEnabled = notificationManager?.areNotificationsEnabled() == true
        val notificationChannelEnabled = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> true
            notificationManager == null -> false
            else -> {
                val channel = notificationManager.getNotificationChannel(
                    HighPerformanceForegroundService.NOTIFICATION_CHANNEL_ID
                )
                channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
            }
        }
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val overlayGranted = Settings.canDrawOverlays(appContext)

        return HighPerformanceSystemStatus(
            notificationPermissionGranted = runtimeNotificationGranted,
            notificationsGloballyEnabled = notificationsGloballyEnabled,
            notificationChannelEnabled = notificationChannelEnabled,
            batteryOptimizationIgnored = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            foregroundServicePermissionDeclared = isPermissionDeclared(
                appContext,
                Manifest.permission.FOREGROUND_SERVICE
            ),
            specialUseForegroundServicePermissionDeclared = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                isPermissionDeclared(appContext, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE),
            wakeLockPermissionDeclared = isPermissionDeclared(appContext, Manifest.permission.WAKE_LOCK),
            overlayPermissionGranted = overlayGranted,
            manufacturer = Build.MANUFACTURER.orEmpty().trim().ifBlank { "unknown" }
        )
    }

    /** Opens the system-managed list; the project intentionally does not request direct exemption. */
    fun batteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun overlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        )
    }

    fun notificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun applicationDetailsSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")
        )
    }

    fun generalSettingsIntent(): Intent = Intent(Settings.ACTION_SETTINGS)

    private fun isPermissionDeclared(context: Context, permission: String): Boolean {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions.orEmpty().contains(permission)
        }.getOrDefault(false)
    }
}
