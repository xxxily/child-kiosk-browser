package site.anzz.childkiosk

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.anzz.childkiosk.data.AppDatabase
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.ui.AddEditWebAppDialog
import site.anzz.childkiosk.ui.BrowserHistoryScreen
import site.anzz.childkiosk.ui.normalizeHistoryUrl
import site.anzz.childkiosk.ui.theme.ChildKioskTheme
import site.anzz.childkiosk.util.KioskPrefs
import site.anzz.childkiosk.util.SystemUiHelper
import site.anzz.childkiosk.util.WebAppIconCache

class BrowserHistoryActivity : ComponentActivity() {
    private var normalSystemBars = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = KioskPrefs.getRequestedOrientation(this)
        super.onCreate(savedInstanceState)

        normalSystemBars = intent.getBooleanExtra(EXTRA_NORMAL_SYSTEM_BARS, false)
        if (KioskPrefs.isLimitFlagSecureEnabled(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        applySystemUiMode()

        setContent {
            ChildKioskTheme {
                val db = remember { AppDatabase.getInstance(this@BrowserHistoryActivity) }
                val history by db.browserHistoryDao()
                    .getRecentHistoryFlow(HISTORY_DISPLAY_LIMIT)
                    .collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                var editingWebApp by remember { mutableStateOf<WebAppEntity?>(null) }
                var initialWebApp by remember { mutableStateOf<WebAppEntity?>(null) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("浏览历史") },
                                navigationIcon = {
                                    IconButton(onClick = ::finish) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowBack,
                                            contentDescription = "返回"
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    ) { contentPadding ->
                        BrowserHistoryScreen(
                            history = history,
                            onOpen = { item ->
                                setResult(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(EXTRA_SELECTED_URL, item.url)
                                )
                                finish()
                            },
                            onAddToWhitelist = { item ->
                                scope.launch {
                                    val existing = withContext(Dispatchers.IO) {
                                        db.webAppDao().getAllWebApps().firstOrNull { app ->
                                            normalizeHistoryUrl(app.url) == normalizeHistoryUrl(item.url)
                                        }
                                    }
                                    if (existing != null) {
                                        editingWebApp = existing
                                    } else {
                                        initialWebApp = WebAppEntity(
                                            title = item.title.ifBlank { item.host },
                                            url = item.url,
                                            iconPath = "icon_public",
                                            isPreset = false,
                                            isEnabled = true,
                                            category = WebAppEntity.CATEGORY_OTHER,
                                            sourceType = WebAppEntity.SOURCE_LOCAL
                                        )
                                    }
                                }
                            },
                            onDelete = { item ->
                                scope.launch(Dispatchers.IO) {
                                    db.browserHistoryDao().deleteById(item.id)
                                }
                            },
                            onClearAll = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.browserHistoryDao().clearAll()
                                    }
                                    Toast.makeText(
                                        this@BrowserHistoryActivity,
                                        "历史已清空",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.padding(contentPadding)
                        )
                    }

                    val editorApp = editingWebApp
                    val starterApp = initialWebApp
                    if (editorApp != null || starterApp != null) {
                        AddEditWebAppDialog(
                            app = editorApp,
                            initialTitle = starterApp?.title.orEmpty(),
                            initialUrl = starterApp?.url.orEmpty(),
                            initialCategory = starterApp?.category ?: WebAppEntity.CATEGORY_OTHER,
                            onDismiss = {
                                editingWebApp = null
                                initialWebApp = null
                            },
                            onSave = { title, url, icon, category ->
                                scope.launch(Dispatchers.IO) {
                                    val frozenIcon = WebAppIconCache.freezeNetworkIcon(
                                        this@BrowserHistoryActivity,
                                        icon,
                                        url
                                    )
                                    if (editorApp == null) {
                                        db.webAppDao().insertWebApp(
                                            WebAppEntity(
                                                title = title,
                                                url = url,
                                                iconPath = frozenIcon,
                                                isPreset = false,
                                                category = category
                                            )
                                        )
                                    } else {
                                        val keepSiteIcon = normalizeHistoryUrl(editorApp.url) ==
                                            normalizeHistoryUrl(url)
                                        db.webAppDao().updateWebApp(
                                            editorApp.copy(
                                                title = title,
                                                url = url,
                                                iconPath = frozenIcon,
                                                siteIconPath = editorApp.siteIconPath.takeIf { keepSiteIcon },
                                                category = category
                                            )
                                        )
                                    }
                                }
                                editingWebApp = null
                                initialWebApp = null
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemUiMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemUiMode()
    }

    private fun applySystemUiMode() {
        if (normalSystemBars) {
            SystemUiHelper.enterNormal(
                this,
                showStatusBar = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            )
        } else {
            SystemUiHelper.enterImmersive(this)
        }
    }

    companion object {
        private const val EXTRA_NORMAL_SYSTEM_BARS = "NORMAL_SYSTEM_BARS"
        private const val EXTRA_SELECTED_URL = "SELECTED_HISTORY_URL"
        private const val HISTORY_DISPLAY_LIMIT = 200

        fun createIntent(context: android.content.Context, normalSystemBars: Boolean): Intent {
            return Intent(context, BrowserHistoryActivity::class.java).apply {
                putExtra(EXTRA_NORMAL_SYSTEM_BARS, normalSystemBars)
            }
        }

        fun selectedUrl(resultIntent: Intent?): String {
            return resultIntent?.getStringExtra(EXTRA_SELECTED_URL).orEmpty()
        }
    }
}
