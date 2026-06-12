@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.example.childkiosk.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.childkiosk.WebViewActivity
import com.example.childkiosk.data.AppDatabase
import com.example.childkiosk.data.SystemConfigEntity
import com.example.childkiosk.data.WebAppEntity

@Composable
fun KioskMainScreen(
    isDeviceOwner: Boolean,
    config: SystemConfigEntity?,
    onEnterAdmin: () -> Unit,
    onExitKiosk: () -> Unit,
    onGoToHomeSettings: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val db = remember { AppDatabase.getInstance(context) }
    
    var webApps by remember { mutableStateOf<List<WebAppEntity>>(emptyList()) }
    
    // 家长隐藏手势触发状态
    var showMenuDialog by remember { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var nextActionAfterVerify by remember { mutableStateOf("") } // "ADMIN" or "EXIT"
    
    val clicks = remember { mutableStateListOf<Long>() }

    // 载入应用白名单
    LaunchedEffect(Unit) {
        db.webAppDao().getAllWebAppsFlow().collect { list ->
            webApps = list
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFEE58), Color(0xFFFDD835)) // 儿童友好明黄色彩
                )
            )
    ) {
        if (!isDeviceOwner) {
            // 未激活 Device Owner，展示引导台
            DeviceOwnerGuideView(
                onCopyScript = {
                    clipboardManager.setText(
                        AnnotatedString("adb shell dpm set-device-owner com.example.childkiosk/.MyDeviceAdminReceiver")
                    )
                    Toast.makeText(context, "ADB 激活脚本已复制到剪贴板！", Toast.LENGTH_SHORT).show()
                },
                onGoToHomeSettings = onGoToHomeSettings
            )
        } else {
            // 已激活：全屏网格列表
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部 Title
                Text(
                    text = "🌟 我的游戏乐园 🌟",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF4E342E),
                    modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                )

                if (webApps.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "这里空空如也，请联系家长在管理后台添加游戏！",
                            fontSize = 18.sp,
                            color = Color(0xFF8D6E63),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        items(webApps) { app ->
                            AppGridItem(
                                app = app,
                                onClick = {
                                    val intent = Intent(context, WebViewActivity::class.java).apply {
                                        putExtra("WEB_APP_ID", app.id)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }
        }

        // 右上角隐藏点击手势区域 (80dp x 80dp)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(80.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    val now = System.currentTimeMillis()
                    clicks.add(now)
                    if (clicks.size > 5) {
                        clicks.removeAt(0)
                    }
                    if (clicks.size == 5 && (now - clicks[0]) <= 2000) {
                        clicks.clear()
                        // 弹窗家长验证
                        nextActionAfterVerify = "MENU"
                        showVerifyDialog = true
                    }
                }
        )

        // 验证弹窗
        if (showVerifyDialog) {
            ParentVerificationDialog(
                config = config,
                onDismiss = { showVerifyDialog = false },
                onVerified = {
                    showVerifyDialog = false
                    if (nextActionAfterVerify == "MENU") {
                        showMenuDialog = true
                    } else if (nextActionAfterVerify == "ADMIN") {
                        onEnterAdmin()
                    } else if (nextActionAfterVerify == "EXIT") {
                        onExitKiosk()
                    }
                }
            )
        }

        // 家长选择菜单弹窗
        if (showMenuDialog) {
            ParentMenuDialog(
                onDismiss = { showMenuDialog = false },
                onEnterAdmin = {
                    showMenuDialog = false
                    onEnterAdmin()
                },
                onExitKiosk = {
                    showMenuDialog = false
                    onExitKiosk()
                }
            )
        }
    }
}

@Composable
fun DeviceOwnerGuideView(
    onCopyScript: () -> Unit,
    onGoToHomeSettings: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "未激活",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "系统未完全锁死 (缺少所有者权限)",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "为防止儿童通过重启、下拉状态栏或按键逃逸，请通过 USB 连接电脑执行以下脚本激活 Device Owner 企业锁定级别：",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 可复制的 ADB 命令展示区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp)
            ) {
                Text(
                    text = "adb shell dpm set-device-owner com.example.childkiosk/.MyDeviceAdminReceiver",
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onCopyScript,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)
                ) {
                    Text("复制激活脚本")
                }
                
                Button(
                    onClick = onGoToHomeSettings,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 72.dp)
                ) {
                    Text("设置本应用为默认主屏幕")
                }
            }
        }
    }
}

@Composable
fun AppGridItem(
    app: WebAppEntity,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1.0f, label = "scale")
    val haptic = LocalHapticFeedback.current
    
    val iconVector = getIconVector(app.iconPath)

    Card(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .scale(scale)
            .sizeIn(minWidth = 140.dp, minHeight = 140.dp) // 满足 72dp+ 大触控目标区域规范
            .aspectRatio(1f),
        shape = RoundedCornerShape(24.dp), // 大圆角
        colors = CardDefaults.cardColors(
            containerColor = Color.White,
            contentColor = Color(0xFF4E342E)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFFF9C4)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = app.title,
                    tint = Color(0xFFFBC02D),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = app.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ParentMenuDialog(
    onDismiss: () -> Unit,
    onEnterAdmin: () -> Unit,
    onExitKiosk: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "⚙️ 家长控制中心",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                QButton(
                    onClick = onEnterAdmin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("进入系统白名单及时间配置后台", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                QButton(
                    onClick = onExitKiosk,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("退出并安全解锁（返回系统桌面）", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = onDismiss) {
                    Text("返回乐园")
                }
            }
        }
    }
}
