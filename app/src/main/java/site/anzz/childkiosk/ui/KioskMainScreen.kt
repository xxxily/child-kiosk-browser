@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package site.anzz.childkiosk.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import android.content.res.Configuration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import site.anzz.childkiosk.WebViewActivity
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.SystemConfigEntity
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.util.KioskPrefs
import kotlinx.coroutines.launch
import androidx.compose.foundation.border

data class WallpaperModel(
    val id: String,
    val label: String,
    val isDark: Boolean,
    val brush: Brush?,
    val color: Color?
)

val WallpaperPresets = listOf(
    WallpaperModel("YELLOW", "温暖明黄 (渐变)", false, Brush.verticalGradient(listOf(Color(0xFFFFEE58), Color(0xFFFDD835))), null),
    WallpaperModel("MORANDI_BLUE", "莫兰迪蓝 (渐变)", false, Brush.verticalGradient(listOf(Color(0xFFECEFF1), Color(0xFFCFD8DC))), null),
    WallpaperModel("FOREST_GREEN", "森林绿意 (渐变)", false, Brush.verticalGradient(listOf(Color(0xFFE8F5E9), Color(0xFFC8E6C9))), null),
    WallpaperModel("LAVENDER_PURPLE", "薰衣草紫 (渐变)", false, Brush.verticalGradient(listOf(Color(0xFFF3E5F5), Color(0xFFE1BEE7))), null),
    WallpaperModel("SUNSET_ORANGE", "落日橘红 (渐变)", false, Brush.verticalGradient(listOf(Color(0xFFFFF3E0), Color(0xFFFFE0B2))), null),
    WallpaperModel("PURE_BLACK", "极简纯黑 (纯色)", true, null, Color(0xFF000000)),
    WallpaperModel("PURE_WHITE", "极简白 (纯色)", false, null, Color(0xFFF5F5F5)),
    WallpaperModel("PURE_BLUE", "深邃蓝 (纯色)", true, null, Color(0xFF102A43)),
    WallpaperModel("PURE_GREEN", "翡翠绿 (纯色)", true, null, Color(0xFF0A2E24)),
    WallpaperModel("PURE_RED", "朱砂红 (纯色)", true, null, Color(0xFF8B0000))
)

@Composable
fun KioskMainScreen(
    config: SystemConfigEntity?,
    iconSizeMode: String,
    wallpaperPreset: String,
    normalSystemBars: Boolean,
    onEnterAdmin: () -> Unit,
    onExitKiosk: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    // 拦截物理 Back 键与返回手势，防止通过返回键退出主屏
    BackHandler(enabled = true) {
        // 空实现以阻断返回手势
    }
    
    var webApps by remember { mutableStateOf<List<WebAppEntity>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("ALL") }

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val screenHorizontalPadding = if (isPortrait) 10.dp else 24.dp
    val screenVerticalPadding = if (isPortrait) 16.dp else 12.dp
    val gridContentPadding = if (isPortrait) 8.dp else 16.dp

    val titlePaddingTop = if (isPortrait) 6.dp else 4.dp
    val titlePaddingBottom = if (isPortrait) 8.dp else 6.dp
    val spacerHeightAfterTitle = if (isPortrait) 6.dp else 4.dp
    val tabPaddingVertical = if (isPortrait) 2.dp else 2.dp
    val spacerHeightAfterTabs = if (isPortrait) 6.dp else 6.dp

    val filteredApps = remember(webApps, selectedCategory) {
        if (selectedCategory == "ALL") {
            webApps
        } else {
            webApps.filter { it.category == selectedCategory }
        }
    }
    
    // 隐藏手势触发状态
    var showMenuDialog by remember { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var nextActionAfterVerify by remember { mutableStateOf("") } // "ADMIN" or "EXIT"
    
    val clicks = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()

    // 载入应用白名单
    LaunchedEffect(Unit) {
        db.webAppDao().getAllWebAppsFlow().collect { list ->
            webApps = list.filter { it.isEnabled }
        }
    }

    // 后台空闲准备 WebView 热备；具体 URL 预加载默认关闭，避免无感占用网络和内存。
    LaunchedEffect(webApps) {
        val isPreloadEnabled = site.anzz.childkiosk.util.KioskPrefs.getWebPreloadEnabled(context)
        val isWarmPoolEnabled = site.anzz.childkiosk.util.KioskPrefs.getWebViewWarmPoolEnabled(context)
        if ((!isPreloadEnabled || webApps.isEmpty()) && !isWarmPoolEnabled) return@LaunchedEffect

        android.os.Looper.myQueue().addIdleHandler {
            scope.launch {
                if (isWarmPoolEnabled) {
                    site.anzz.childkiosk.util.WebViewPool.warmupBlank()
                }
                if (isPreloadEnabled && webApps.isNotEmpty()) {
                    webApps.take(2).forEach { app ->
                        kotlinx.coroutines.delay(1000) // 间隔预加载，分流网络和内存开销
                        site.anzz.childkiosk.util.WebViewPool.preload(app.url)
                    }
                }
            }
            false
        }
    }

    val mainTitleText = remember {
        site.anzz.childkiosk.util.KioskPrefs.getMainTitleText(context)
    }
    val hideMainTitle = remember {
        site.anzz.childkiosk.util.KioskPrefs.getHideMainTitle(context)
    }
    val hideAdminIcon = remember {
        site.anzz.childkiosk.util.KioskPrefs.getHideAdminIcon(context)
    }
    val adminQuickOpen = remember {
        site.anzz.childkiosk.util.KioskPrefs.getAdminQuickOpen(context)
    }
    val adminIconAlpha = remember {
        site.anzz.childkiosk.util.KioskPrefs.getAdminIconAlpha(context)
    }

    val currentWallpaper = remember(wallpaperPreset) {
        WallpaperPresets.firstOrNull { it.id == wallpaperPreset } ?: WallpaperPresets[0]
    }
    val isDarkWallpaper = currentWallpaper.isDark

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (currentWallpaper.brush != null) {
                    Modifier.background(currentWallpaper.brush)
                } else if (currentWallpaper.color != null) {
                    Modifier.background(currentWallpaper.color)
                } else {
                    Modifier.background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFEE58), Color(0xFFFDD835))
                        )
                    )
                }
            )
    ) {
        // 无论是否为 Device Owner，主网格始终可用。
        // 系统级锁定强度由 MainActivity 按防护等级（Device Owner / 屏幕固定 / 无）自动决定，
        // 不再以是否取得 Device Owner 作为「能否使用应用」的前提。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = screenHorizontalPadding, vertical = screenVerticalPadding)
        ) {
            val categories = listOf(
                "ALL" to "🌟 全部",
                WebAppEntity.CATEGORY_GAME to "🎮 游戏",
                WebAppEntity.CATEGORY_VIDEO to "📺 视频",
                WebAppEntity.CATEGORY_BOOK to "📚 绘本",
                WebAppEntity.CATEGORY_STUDY to "✍️ 学习",
                WebAppEntity.CATEGORY_TOOL to "🧰 工具",
                WebAppEntity.CATEGORY_OTHER to "⚙️ 其他"
            )
            val minGridSize = when (iconSizeMode) {
                "SMALL" -> 64.dp
                "LARGE" -> 130.dp
                else -> 96.dp
            }
            val gridSpacing = when (iconSizeMode) {
                "SMALL" -> 10.dp
                "LARGE" -> 16.dp
                else -> 12.dp
            }
            val columnCount = if (isPortrait) {
                when (iconSizeMode) {
                    "SMALL" -> 4
                    "LARGE" -> 2
                    else -> 3
                }
            } else {
                ((maxWidth.value + gridSpacing.value) / (minGridSize.value + gridSpacing.value))
                    .toInt()
                    .coerceAtLeast(1)
            }
            val appRows = remember(filteredApps, columnCount) {
                filteredApps.chunked(columnCount)
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = gridContentPadding)
            ) {
                item(key = "main_title") {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!hideMainTitle) {
                            Text(
                                text = "🌟 $mainTitleText 🌟",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDarkWallpaper) Color.White else Color(0xFF4E342E),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = titlePaddingTop, bottom = titlePaddingBottom)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(spacerHeightAfterTitle))
                        }
                    }
                }

                stickyHeader(key = "category_tabs") {
                    CategoryStickyTabs(
                        categories = categories,
                        selectedCategory = selectedCategory,
                        isDarkWallpaper = isDarkWallpaper,
                        onCategorySelected = { selectedCategory = it },
                        tabPaddingVertical = tabPaddingVertical
                    )
                }

                item(key = "after_tabs_spacer") {
                    Spacer(modifier = Modifier.height(spacerHeightAfterTabs))
                }

                if (filteredApps.isEmpty()) {
                    item(key = "empty_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (webApps.isEmpty()) "这里空空如也，请在配置后台添加应用！"
                                       else "此分类下还没有应用哦，去看看其他分类吧！",
                                fontSize = 18.sp,
                                color = if (isDarkWallpaper) Color.White.copy(alpha = 0.7f) else Color(0xFF8D6E63),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                } else {
                    items(
                        items = appRows,
                        key = { row -> row.joinToString(separator = "-") { it.id.toString() } }
                    ) { rowApps ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = gridContentPadding)
                                .padding(bottom = gridSpacing),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing)
                        ) {
                            rowApps.forEach { app ->
                                AppGridItem(
                                    app = app,
                                    iconSizeMode = iconSizeMode,
                                    isDarkWallpaper = isDarkWallpaper,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val intent = Intent(context, WebViewActivity::class.java).apply {
                                            putExtra(WebViewActivity.EXTRA_WEB_APP_ID, app.id)
                                            putExtra(
                                                WebViewActivity.EXTRA_ORIENTATION_MODE,
                                                KioskPrefs.getOrientationMode(context)
                                            )
                                            KioskPrefs.putWebViewRuntimeConfig(this, context, normalSystemBars)
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }
                            repeat(columnCount - rowApps.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
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
                    if (adminQuickOpen) {
                        clicks.clear()
                        if (site.anzz.childkiosk.util.KioskPrefs.getVerifyAdminActions(context)) {
                            nextActionAfterVerify = "MENU"
                            showVerifyDialog = true
                        } else {
                            showMenuDialog = true
                        }
                    } else {
                        clicks.add(now)
                        if (clicks.size > 5) {
                            clicks.removeAt(0)
                        }
                        if (clicks.size == 5 && (now - clicks[0]) <= 2000) {
                            clicks.clear()
                            if (site.anzz.childkiosk.util.KioskPrefs.getVerifyAdminActions(context)) {
                                // 弹窗认证
                                nextActionAfterVerify = "MENU"
                                showVerifyDialog = true
                            } else {
                                // 免验证直接进入菜单
                                showMenuDialog = true
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (!hideAdminIcon) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkWallpaper) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.8f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.size(48.dp).alpha(adminIconAlpha)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "配置入口",
                            tint = if (isDarkWallpaper) Color.White else Color(0xFF4E342E),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

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

        // 配置菜单弹窗
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
private fun CategoryStickyTabs(
    categories: List<Pair<String, String>>,
    selectedCategory: String,
    isDarkWallpaper: Boolean,
    onCategorySelected: (String) -> Unit,
    tabPaddingVertical: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = tabPaddingVertical)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { (catKey, catName) ->
                val isSelected = selectedCategory == catKey
                Card(
                    onClick = { onCategorySelected(catKey) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            if (isDarkWallpaper) Color.White else Color(0xFF4E342E)
                        } else {
                            if (isDarkWallpaper) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.9f)
                        },
                        contentColor = if (isSelected) {
                            if (isDarkWallpaper) Color(0xFF1E1E1E) else Color.White
                        } else {
                            if (isDarkWallpaper) Color.White.copy(alpha = 0.8f) else Color(0xFF4E342E)
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        text = catName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AppGridItem(
    app: WebAppEntity,
    iconSizeMode: String,
    isDarkWallpaper: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.92f else 1.0f, label = "scale")
    val haptic = LocalHapticFeedback.current
    
    val iconVector = getIconVector(app.iconPath)

    // 动态计算不同尺寸模式下的 UI 样式参数
    val iconBoxSize = when (iconSizeMode) {
        "SMALL" -> 48.dp
        "LARGE" -> 80.dp
        else -> 60.dp
    }
    val iconSize = when (iconSizeMode) {
        "SMALL" -> 24.dp
        "LARGE" -> 44.dp
        else -> 32.dp
    }
    val fontSize = when (iconSizeMode) {
        "SMALL" -> 11.sp
        "LARGE" -> 16.sp
        else -> 13.sp
    }
    val iconBoxCornerRadius = when (iconSizeMode) {
        "SMALL" -> 12.dp
        "LARGE" -> 20.dp
        else -> 16.dp
    }
    
    val textColor = if (isDarkWallpaper) Color.White else Color(0xFF263238)

    // 动态渐变底色
    val gradientBrush = remember(app.id) {
        val index = (app.id ?: 0L).toInt().coerceAtLeast(0) % 5
        when (index) {
            0 -> Brush.linearGradient(listOf(Color(0xFF80DEEA), Color(0xFFB39DDB)))
            1 -> Brush.linearGradient(listOf(Color(0xFFFFAB91), Color(0xFFFFCC80)))
            2 -> Brush.linearGradient(listOf(Color(0xFFA5D6A7), Color(0xFFE6EE9C)))
            3 -> Brush.linearGradient(listOf(Color(0xFFF48FB1), Color(0xFFFFCC80)))
            else -> Brush.linearGradient(listOf(Color(0xFF81D4FA), Color(0xFF80CBC4)))
        }
    }

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material.ripple.rememberRipple(bounded = true, color = textColor.copy(alpha = 0.15f)),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val iconPath = app.iconPath ?: ""
        val isNetworkIcon = iconPath.startsWith("http://", ignoreCase = true) || 
                            iconPath.startsWith("https://", ignoreCase = true)
        
        Box(
            modifier = Modifier
                .size(iconBoxSize)
                .clip(RoundedCornerShape(iconBoxCornerRadius))
                .background(if (isNetworkIcon) Color.Transparent else Color.White)
                .run {
                    if (!isNetworkIcon) {
                        this.background(gradientBrush)
                    } else this
                }
                .run {
                    val borderColor = if (isDarkWallpaper) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
                    this.border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(iconBoxCornerRadius))
                },
            contentAlignment = Alignment.Center
        ) {
            if (isNetworkIcon) {
                coil.compose.AsyncImage(
                    model = iconPath,
                    contentDescription = app.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Star)
                )
            } else {
                Icon(
                    imageVector = iconVector,
                    contentDescription = app.title,
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = app.title,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
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
                    text = "⚙️ 配置中心",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                QButton(
                    onClick = onEnterAdmin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("进入配置后台", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                QButton(
                    onClick = onExitKiosk,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("返回系统桌面", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = onDismiss) {
                    Text("返回空间")
                }
            }
        }
    }
}
