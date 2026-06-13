package com.example.childkiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.childkiosk.data.AppDatabase
import com.example.childkiosk.ui.AdminConsoleScreen
import com.example.childkiosk.ui.KioskMainScreen
import com.example.childkiosk.ui.theme.ChildKioskTheme
import com.example.childkiosk.util.KioskPrefs
import com.example.childkiosk.util.SystemUiHelper

class MainActivity : ComponentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var isSoftLockDeferred = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 0. 早期屏幕方向设置，避免启动闪烁
        val orientationMode = KioskPrefs.getOrientationMode(this)
        requestedOrientation = when (orientationMode) {
            KioskPrefs.ORIENTATION_PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            KioskPrefs.ORIENTATION_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        super.onCreate(savedInstanceState)

        // 1. 设置 FLAG_SECURE 防截屏逃逸 (根据配置)
        if (KioskPrefs.isLimitFlagSecureEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // 2. 沉浸式全屏
        SystemUiHelper.enterImmersive(this)

        // 3. 监听 System UI / Window 边距变化，防止状态栏灰色半透明条卡死，3秒自动收回
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val isVisible = insets.isVisible(android.view.WindowInsets.Type.statusBars()) || 
                                insets.isVisible(android.view.WindowInsets.Type.navigationBars())
                if (isVisible) {
                    view.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            SystemUiHelper.enterImmersive(this@MainActivity)
                        }
                    }, 3000)
                }
                view.onApplyWindowInsets(insets)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    window.decorView.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            SystemUiHelper.enterImmersive(this@MainActivity)
                        }
                    }, 3000)
                }
            }
        }

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        val db = AppDatabase.getInstance(this)

        setContent {
            ChildKioskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("MAIN") }
                    val systemConfig by db.systemConfigDao()
                        .getSystemConfigFlow()
                        .collectAsState(initial = null)

                    val iconSizeMode = remember(currentScreen) {
                        KioskPrefs.getIconSizeMode(this@MainActivity)
                    }

                    when (currentScreen) {
                        "MAIN" -> KioskMainScreen(
                            config = systemConfig,
                            iconSizeMode = iconSizeMode,
                            onEnterAdmin = { currentScreen = "ADMIN" },
                            onExitKiosk = { stopLockTaskMode() }
                        )
                        "ADMIN" -> AdminConsoleScreen(
                            config = systemConfig,
                            isDeviceOwner = dpm.isDeviceOwnerApp(packageName),
                            onBack = { currentScreen = "MAIN" },
                            onExitKiosk = { stopLockTaskMode() },
                            onGoToHomeSettings = { openHomeSettings() },
                            onProtectionModeChanged = { mode ->
                                if (mode == KioskPrefs.MODE_SOFT_LOCK) {
                                    isSoftLockDeferred = false
                                    triggerKioskIfNeeded()
                                }
                            },
                            onSandboxLimitsChanged = {
                                applySandboxLimits()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SystemUiHelper.enterImmersive(this)
        triggerKioskIfNeeded()
    }

    private fun triggerKioskIfNeeded() {
        when {
            // Tier 1：Device Owner，企业级完全锁定
            dpm.isDeviceOwnerApp(packageName) -> setupAndStartKiosk()
            // Tier 2：无 Device Owner，按家长配置进入屏幕固定软锁
            KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_SOFT_LOCK -> {
                if (!isSoftLockDeferred && !isInLockTaskMode()) {
                    startSoftLock()
                }
            }
            // Tier 3：纯沉浸式，无系统级锁定
            else -> { /* 仅依赖沉浸式全屏 + 自定义 Launcher + 家长验证退出 */ }
        }
    }

    private fun isInLockTaskMode(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            SystemUiHelper.enterImmersive(this)
            // 如果重新获得焦点，且当前需要软锁锁定，但实际上未进入锁定状态，且并非 Device Owner，
            // 说明用户点击了“不用了”或者主动解除了屏幕固定。
            // 此时我们设置 isSoftLockDeferred = true 避免循环弹窗提示。
            if (KioskPrefs.getProtectionMode(this) == KioskPrefs.MODE_SOFT_LOCK &&
                !dpm.isDeviceOwnerApp(packageName) &&
                !isInLockTaskMode()) {
                isSoftLockDeferred = true
            }
        }
    }

    private fun applySandboxLimits() {
        if (KioskPrefs.isLimitFlagSecureEnabled(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (!dpm.isDeviceOwnerApp(packageName)) {
            return
        }

        val admin = adminComponent
        applyUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES, KioskPrefs.isLimitAdbEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT, KioskPrefs.isLimitSafeBootEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET, KioskPrefs.isLimitFactoryResetEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_ADD_USER, KioskPrefs.isLimitAddUserEnabled(this))
        applyUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER, KioskPrefs.isLimitUsbTransferEnabled(this))

        runCatching {
            dpm.setScreenCaptureDisabled(admin, KioskPrefs.isLimitScreenshotEnabled(this))
        }
        runCatching {
            dpm.setStatusBarDisabled(admin, KioskPrefs.isLimitStatusBarEnabled(this))
        }
        runCatching {
            dpm.setKeyguardDisabled(admin, KioskPrefs.isLimitKeyguardEnabled(this))
        }
    }

    private fun applyUserRestriction(admin: ComponentName, restriction: String, enabled: Boolean) {
        runCatching {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction)
            } else {
                dpm.clearUserRestriction(admin, restriction)
            }
        }
    }

    /**
     * 激活 Kiosk 模式 (Lock Task Mode) 并在 Device Owner 模式下进行多维度安全限制加固
     */
    private fun setupAndStartKiosk() {
        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                )
            }

            // 限制语音助手、未知来源等多维度逃逸路径
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.addUserRestriction(adminComponent, "no_voice_assistants")
                }
            }
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }

            // 应用可配置的沙箱限制
            applySandboxLimits()

            startLockTask()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "已经处于 Lock Task 状态，无需重复启动: ${e.message}")
        } catch (e: SecurityException) {
            Toast.makeText(this, "权限不足，部分系统加固未生效: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Kiosk 锁定启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Tier 2：非 Device Owner 时的「屏幕固定」软锁。
     *
     * 普通应用调用 [startLockTask] 会触发系统级 Screen Pinning（屏幕固定），
     * 拦截 Home / 最近任务键。首次在部分 OEM 上可能弹出系统确认框，这是预期行为。
     * 软锁可被「长按 返回+最近任务」等系统手势解除，因此防护强度低于 Device Owner，
     * 但无需恢复出厂即可让普通侧载用户开箱使用。
     */
    private fun startSoftLock() {
        try {
            startLockTask()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "已处于屏幕固定状态，无需重复启动: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "屏幕固定软锁启动失败（设备可能不支持）: ${e.message}")
        }
    }

    /**
     * 退出锁定并重置限制，使家长能够切回原生桌面。
     * Device Owner 与屏幕固定软锁两种场景统一走此流程；非 Device Owner 时
     * 用户限制相关调用会因无权限被 runCatching 静默忽略。
     */
    private fun stopLockTaskMode() {
        try {
            runCatching { stopLockTask() }

            if (dpm.isDeviceOwnerApp(packageName)) {
                runCatching { dpm.clearUserRestriction(adminComponent, "no_voice_assistants") }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES) }
                runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER) }
                runCatching { dpm.setScreenCaptureDisabled(adminComponent, false) }
                runCatching { dpm.setStatusBarDisabled(adminComponent, false) }
                runCatching { dpm.setKeyguardDisabled(adminComponent, false) }
            }

            Toast.makeText(this, "锁定已安全解除", Toast.LENGTH_SHORT).show()

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "解锁退出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openHomeSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法自动打开设置，请手动将本应用设为默认桌面", Toast.LENGTH_LONG).show()
        }
    }



    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KioskPrefs.isLimitVolumeKeysEnabled(this)) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Toast.makeText(this, "音量按键已被家长控制锁定", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 阻断物理 Back 键，让 Compose BackHandler 接管 */
    override fun onBackPressed() {
        // 主屏幕禁止退出，由内部隐藏手势触发家长验证后才允许
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
