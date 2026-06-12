package com.example.childkiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.childkiosk.data.AppDatabase
import com.example.childkiosk.data.SystemConfigEntity
import com.example.childkiosk.ui.AdminConsoleScreen
import com.example.childkiosk.ui.KioskMainScreen

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

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        val db = AppDatabase.getInstance(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("MAIN") } // "MAIN" or "ADMIN"
                    var systemConfig by remember { mutableStateOf<SystemConfigEntity?>(null) }

                    // 持续同步数据库中的系统配置
                    LaunchedEffect(Unit) {
                        db.systemConfigDao().getSystemConfigFlow().collect { config ->
                            systemConfig = config
                        }
                    }

                    when (currentScreen) {
                        "MAIN" -> {
                            KioskMainScreen(
                                isDeviceOwner = dpm.isDeviceOwnerApp(packageName),
                                config = systemConfig,
                                onEnterAdmin = { currentScreen = "ADMIN" },
                                onExitKiosk = { stopLockTaskMode() },
                                onGoToHomeSettings = { openHomeSettings() }
                            )
                        }
                        "ADMIN" -> {
                            AdminConsoleScreen(
                                config = systemConfig,
                                onBack = { currentScreen = "MAIN" }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 如果已激活 Device Owner，每次回到前台自动强行开启系统级锁死
        if (dpm.isDeviceOwnerApp(packageName)) {
            setupAndStartKiosk()
        }
    }

    /**
     * 激活 Kiosk 模式 (Lock Task Mode) 并在 Device Owner 模式下进行多维度安全限制加固
     */
    private fun setupAndStartKiosk() {
        try {
            // 1. 设置锁死任务白名单包名
            dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            
            // 2. 精细化控制 Lock Task 属性 (仅保留状态栏系统信息显示，屏蔽其余所有逃逸口)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO // 仅允许查看电量、时间、Wi-Fi，其余下拉栏通知全部禁用
                )
            }
            
            // 3. 限制语音助手，防范语音命令逃逸
            dpm.addUserRestriction(adminComponent, "no_voice_assistants")
            
            // 4. 禁用开发者选项与 USB 调试，防范 ADB 越权操作
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            
            // 5. 设备级别禁止屏幕截图
            dpm.setScreenCaptureDisabled(adminComponent, true)
            
            // 6. 开启锁死
            startLockTask()
            Toast.makeText(this, "安全沙箱已启动，系统已完成加固锁定", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Kiosk 锁定启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 退出 Kiosk 模式并重置限制，使家长能够切回原生桌面
     */
    private fun stopLockTaskMode() {
        try {
            // 1. 解除 Kiosk 锁死
            stopLockTask()
            
            // 2. 清除各项防逃逸限制
            dpm.clearUserRestriction(adminComponent, "no_voice_assistants")
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.setScreenCaptureDisabled(adminComponent, false)
            
            Toast.makeText(this, "Kiosk 锁定已安全解除", Toast.LENGTH_SHORT).show()

            // 3. 自动拉起系统桌面选择器，让家长可以选择原生桌面
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "解锁退出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 引导跳转到系统设置中的“默认桌面”配置页
     */
    private fun openHomeSettings() {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法自动打开设置，请在系统中手动将本应用设为默认桌面", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 拦截音量物理键，防止系统音量 UI 弹出造成截面闪烁
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            true // 消费事件
        } else {
            super.onKeyDown(keyCode, event)
        }
    }
}

