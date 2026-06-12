package com.example.childkiosk

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
import com.example.childkiosk.util.SystemUiHelper

class MainActivity : ComponentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 设置 FLAG_SECURE 防截屏逃逸
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // 2. 沉浸式全屏
        SystemUiHelper.enterImmersive(this)

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

                    when (currentScreen) {
                        "MAIN" -> KioskMainScreen(
                            isDeviceOwner = dpm.isDeviceOwnerApp(packageName),
                            config = systemConfig,
                            onEnterAdmin = { currentScreen = "ADMIN" },
                            onExitKiosk = { stopLockTaskMode() },
                            onGoToHomeSettings = { openHomeSettings() }
                        )
                        "ADMIN" -> AdminConsoleScreen(
                            config = systemConfig,
                            onBack = { currentScreen = "MAIN" }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SystemUiHelper.enterImmersive(this)
        if (dpm.isDeviceOwnerApp(packageName)) {
            setupAndStartKiosk()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemUiHelper.enterImmersive(this)
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

            // 限制语音助手、调试、屏幕截图等多维度逃逸路径
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dpm.addUserRestriction(adminComponent, "no_voice_assistants")
                }
            }
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            }
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            }
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            }
            runCatching {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
            }
            runCatching {
                dpm.setScreenCaptureDisabled(adminComponent, true)
            }
            runCatching {
                dpm.setStatusBarDisabled(adminComponent, true)
            }
            runCatching {
                dpm.setKeyguardDisabled(adminComponent, true)
            }

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
     * 退出 Kiosk 模式并重置限制，使家长能够切回原生桌面
     */
    private fun stopLockTaskMode() {
        try {
            stopLockTask()

            runCatching { dpm.clearUserRestriction(adminComponent, "no_voice_assistants") }
            runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES) }
            runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER) }
            runCatching { dpm.setScreenCaptureDisabled(adminComponent, false) }
            runCatching { dpm.setStatusBarDisabled(adminComponent, false) }
            runCatching { dpm.setKeyguardDisabled(adminComponent, false) }

            Toast.makeText(this, "Kiosk 锁定已安全解除", Toast.LENGTH_SHORT).show()

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

    /** 拦截音量物理键，避免儿童误触造成系统音量条弹出 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    /** 阻断物理 Back 键，让 Compose BackHandler 接管 */
    override fun onBackPressed() {
        // 主屏幕禁止退出，由内部隐藏手势触发家长验证后才允许
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
