package site.anzz.childkiosk.ui.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import site.anzz.childkiosk.ui.WebAppSelectDialog
import androidx.core.view.isVisible
import site.anzz.childkiosk.R
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TabStateInfo(
    val id: String,
    val title: String,
    val url: String,
    val isActive: Boolean
)

data class FloatingBrowserControlsState(
    val currentUrl: String = "",
    val pageTitle: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val tabs: List<TabStateInfo> = emptyList(),
    val isHomeScreen: Boolean = false
)

data class FloatingBrowserControlsCallbacks(
    val onNavigateToUrl: (String) -> Unit = {},
    val onBack: () -> Unit = {},
    val onForward: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onForceRefresh: () -> Unit = {},
    val onStopLoading: () -> Unit = {},
    val onBookmarkCurrentPage: () -> Unit = {},
    val onPanelExpandedChanged: (Boolean) -> Unit = {},
    val onActionSelected: (String) -> Unit = {},
    val onNewTab: () -> Unit = {},
    val onCloseTab: (String) -> Unit = {},
    val onSwitchTab: (String) -> Unit = {},
    val onHome: () -> Unit = {},
    val onOpenWebApp: (site.anzz.childkiosk.data.WebAppEntity) -> Unit = {},
    val onShowSiteInfoPanel: (String) -> Unit = {}
)

enum class FloatingControlActionStyle {
    NORMAL,
    PRIMARY,
    DESTRUCTIVE
}

data class FloatingControlAction(
    val id: String,
    val title: String,
    @DrawableRes val iconRes: Int,
    val enabled: Boolean = true,
    val highlighted: Boolean = false,
    val style: FloatingControlActionStyle = FloatingControlActionStyle.NORMAL
)

data class FloatingControlSection(
    val id: String,
    val title: String,
    val actions: List<FloatingControlAction>,
    val helperText: String = ""
)

class FloatingBrowserControlsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeMargin = dp(12)
    private val visibleEdgeWidth = dp(20)
    private val bubbleSize = dp(44)
    private val panelMaxWidth = dp(380)
    private val panelMinWidth = dp(288)
    private val panelMargin = dp(16)
    private val idleHideDelayMs = 2000L
    private val animationDurationMs = 180L

    private var callbacks = FloatingBrowserControlsCallbacks()
    private var state = FloatingBrowserControlsState()
    private var extraSections: List<FloatingControlSection> = emptyList()
    private var panelExpanded = false
    private var isDraggingBubble = false
    private var isBubbleHiddenAtEdge = false
    private var isAttachedToRightEdge = true
    private var consumingOutsidePanelTap = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startBubbleX = 0f
    private var startBubbleY = 0f

    private val hideRunnable = Runnable {
        if (!panelExpanded && !isDraggingBubble && width > 0) {
            hideBubbleAtEdge(animated = true)
        }
    }

    private val titleView = TextView(context).apply {
        setTextColor(PanelMutedTextColor)
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val panelCloseButton = browserIconButton(
        iconRes = R.drawable.ic_browser_close_24,
        contentDescription = "收起"
    ) {
        handleAction(ACTION_PANEL_CLOSE)
    }

    private val infoButton = ImageButton(context).apply {
        contentDescription = "网站信息与权限配置"
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = roundedBackground(Color.TRANSPARENT, dp(10))
        minimumWidth = dp(36)
        minimumHeight = dp(36)
        setOnClickListener {
            callbacks.onShowSiteInfoPanel(state.currentUrl)
        }
    }

    private val urlInput = EditText(context).apply {
        minHeight = dp(44)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_GO
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        textSize = 14f
        setTextColor(PanelTextColor)
        setHintTextColor(PanelHintColor)
        hint = "输入网址"
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(12), dp(8))
        background = null
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitUrl()
                true
            } else {
                false
            }
        }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                revealBubble(animated = true)
                removeCallbacks(hideRunnable)
            } else {
                scheduleEdgeHide()
            }
        }
    }

    private val progressView = ProgressBar(
        context,
        null,
        android.R.attr.progressBarStyleHorizontal
    ).apply {
        max = 100
        progress = 0
        progressTintList = ColorStateList.valueOf(AccentColor)
        progressBackgroundTintList = ColorStateList.valueOf(StrokeColor)
        isVisible = false
    }

    private val goButton = browserIconButton(
        iconRes = R.drawable.ic_browser_go_24,
        contentDescription = "访问"
    ) {
        submitUrl()
    }

    private val sectionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val sectionsScrollView = HeightLimitedScrollView(context).apply {
        isFillViewport = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(
            sectionsContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private val panelView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = roundedBackground(PanelBackgroundColor, dp(22), StrokeColor, dp(1))
        elevation = dp(12).toFloat()
        alpha = 0f
        scaleX = 0.96f
        scaleY = 0.96f
        isClickable = true
        isVisible = false

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    titleView,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(
                    panelCloseButton,
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        leftMargin = dp(8)
                    }
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val urlContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedBackground(Color.WHITE, dp(12), StrokeColor, dp(1))
                    setPadding(dp(6), 0, 0, 0)
                    addView(
                        infoButton,
                        LinearLayout.LayoutParams(dp(36), dp(36))
                    )
                    addView(
                        urlInput,
                        LinearLayout.LayoutParams(
                            0,
                            dp(44),
                            1f
                        )
                    )
                }
                addView(
                    urlContainer,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(
                    goButton,
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        leftMargin = dp(8)
                    }
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addView(
            progressView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                topMargin = dp(8)
            }
        )

        addView(
            sectionsScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
    }

    private fun bubbleBackground(): android.graphics.drawable.Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = dp(21).toFloat()
            setStroke(dp(1), Color.rgb(224, 224, 224))
        }
        return android.graphics.drawable.InsetDrawable(circle, dp(1), dp(1), dp(1), dp(1))
    }

    private val bubbleButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_floating_browser_explore_24)
        imageTintList = ColorStateList.valueOf(Color.rgb(117, 117, 117))
        contentDescription = "浏览控制"
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = bubbleBackground()
        elevation = dp(3).toFloat()
        setOnTouchListener { _, event -> handleBubbleTouch(event) }
    }

    private var imeHeight = 0

    init {
        isClickable = false
        clipChildren = false
        clipToPadding = false

        addView(
            panelView,
            LayoutParams(panelMaxWidth, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        addView(
            bubbleButton,
            LayoutParams(bubbleSize, bubbleSize).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        renderSections()

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val keyboardHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            if (imeHeight != keyboardHeight) {
                imeHeight = keyboardHeight
                updatePanelWidth()
                sectionsScrollView.maxContentHeight = maxScrollableSectionsHeight()
                doOnLayout {
                    restoreBubbleAfterBoundsChange(animated = false)
                    positionPanel()
                }
            } else {
                doOnLayout {
                    restoreBubbleAfterBoundsChange(animated = false)
                    positionPanel()
                }
            }
            insets
        }

        doOnLayout {
            if (bubbleButton.x == 0f && bubbleButton.y == 0f) {
                placeInitialBubble()
            } else {
                restoreBubbleAfterBoundsChange(animated = false)
            }
        }
    }

    fun setCallbacks(callbacks: FloatingBrowserControlsCallbacks) {
        this.callbacks = callbacks
    }

    fun setExtraSections(sections: List<FloatingControlSection>) {
        extraSections = sections
        renderSections()
        positionPanel()
    }

    fun updateState(nextState: FloatingBrowserControlsState) {
        state = nextState.copy(progress = nextState.progress.coerceIn(0, 100))
        titleView.text = state.pageTitle.takeIf { it.isNotBlank() }
            ?: state.currentUrl.takeIf { it.isNotBlank() }
            ?: "浏览控制"
        if (!urlInput.hasFocus()) {
            urlInput.setText(state.currentUrl)
            urlInput.setSelection(0)
            urlInput.scrollTo(0, 0)
        }
        progressView.progress = state.progress
        progressView.isVisible = state.isLoading || state.progress in 1..99
        updateInfoButtonIcon(state.currentUrl)
        renderSections()
    }

    private fun updateInfoButtonIcon(url: String) {
        val trimmed = url.trim()
        val isHttps = trimmed.startsWith("https://", ignoreCase = true)
        val isHttp = trimmed.startsWith("http://", ignoreCase = true)

        val iconRes = when {
            isHttps -> R.drawable.ic_browser_secure_24
            isHttp -> R.drawable.ic_browser_warning_24
            else -> R.drawable.ic_browser_info_24
        }
        infoButton.setImageResource(iconRes)

        val tintColor = when {
            isHttps -> Color.parseColor("#4CAF50")
            isHttp -> Color.parseColor("#F44336")
            else -> PanelTextColor
        }
        infoButton.imageTintList = ColorStateList.valueOf(tintColor)
    }

    fun setPanelExpanded(expanded: Boolean, animated: Boolean = true) {
        if (panelExpanded == expanded && panelView.isVisible == expanded) return
        panelExpanded = expanded
        callbacks.onPanelExpandedChanged(expanded)
        removeCallbacks(hideRunnable)

        if (expanded) {
            val snapTabs = site.anzz.childkiosk.util.KioskPrefs.getTabsSnapshot(context)
            updateState(state.copy(tabs = snapTabs))
            revealBubble(animated = animated)
            syncInputFromStateIfNeeded()
            panelView.isVisible = true
            panelView.doOnLayout {
                positionPanel()
                animatePanel(show = true, animated = animated)
            }
            urlInput.clearFocus()
        } else {
            hideKeyboard()
            urlInput.clearFocus()
            animatePanel(show = false, animated = animated)
            scheduleEdgeHide()
        }
    }

    fun collapsePanel() {
        setPanelExpanded(expanded = false, animated = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePanelWidth()
        sectionsScrollView.maxContentHeight = maxScrollableSectionsHeight()
        if (w > 0 && h > 0) {
            if (oldw == 0 || oldh == 0) {
                placeInitialBubble()
            } else {
                restoreBubbleAfterBoundsChange(animated = false)
            }
            positionPanel()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        bubbleButton.animate().cancel()
        panelView.animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            panelExpanded &&
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            !isPointInsideView(panelView, event.x, event.y) &&
            !isPointInsideView(bubbleButton, event.x, event.y)
        ) {
            consumingOutsidePanelTap = true
            setPanelExpanded(expanded = false, animated = true)
            return true
        }
        if (consumingOutsidePanelTap) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumingOutsidePanelTap = false
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isPointInsideView(view: View, x: Float, y: Float): Boolean {
        if (!view.isVisible) return false
        return x >= view.x &&
            x <= view.x + view.width &&
            y >= view.y &&
            y <= view.y + view.height
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                bubbleButton.animate().cancel()
                bubbleButton.alpha = 1.0f
                removeCallbacks(hideRunnable)
                revealBubble(animated = true)
                isDraggingBubble = false
                downRawX = event.rawX
                downRawY = event.rawY
                startBubbleX = bubbleButton.x
                startBubbleY = bubbleButton.y
                bubbleButton.isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDraggingBubble && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDraggingBubble = true
                    if (panelExpanded) {
                        setPanelExpanded(expanded = false, animated = true)
                    }
                }
                if (isDraggingBubble) {
                    moveBubbleTo(startBubbleX + dx, startBubbleY + dy)
                    bubbleButton.alpha = 1.0f
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                bubbleButton.isPressed = false
                if (isDraggingBubble) {
                    snapBubbleToNearestEdge(animated = true)
                } else {
                    setPanelExpanded(!panelExpanded, animated = true)
                }
                isDraggingBubble = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                bubbleButton.isPressed = false
                isDraggingBubble = false
                snapBubbleToNearestEdge(animated = true)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun submitUrl() {
        val targetUrl = normalizeUrl(urlInput.text?.toString().orEmpty())
        if (targetUrl.isBlank()) return
        hideKeyboard()
        urlInput.clearFocus()
        callbacks.onNavigateToUrl(targetUrl)
        setPanelExpanded(expanded = false, animated = true)
        scheduleEdgeHide()
    }

    private fun normalizeUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""
        val lowerInput = trimmed.lowercase()
        return when {
            lowerInput.startsWith("http://") || lowerInput.startsWith("https://") -> trimmed
            lowerInput.contains("://") -> ""
            else -> "https://$trimmed"
        }
    }

    private fun renderSections() {
        sectionsContainer.removeAllViews()
        val sections = buildList {
            add(defaultBrowserSection())
            add(FloatingControlSection(id = SECTION_TABS, title = "", actions = emptyList()))
            addAll(extraSections.filter { it.actions.isNotEmpty() })
        }
        sections.forEachIndexed { index, section ->
            sectionsContainer.addView(sectionView(section, index == sections.lastIndex))
        }
        sectionsScrollView.maxContentHeight = maxScrollableSectionsHeight()
    }

    private fun defaultBrowserSection(): FloatingControlSection {
        val refreshTitle = if (state.isLoading) "停止" else "刷新"
        val refreshIcon = if (state.isLoading) {
            R.drawable.ic_browser_close_24
        } else {
            R.drawable.ic_browser_refresh_24
        }
        val isHomeScreen = state.isHomeScreen
        return FloatingControlSection(
            id = SECTION_BROWSER,
            title = "浏览",
            actions = listOf(
                FloatingControlAction(
                    id = ACTION_BROWSER_HOME,
                    title = "主页",
                    iconRes = R.drawable.ic_browser_home_24,
                    enabled = !isHomeScreen
                ),
                FloatingControlAction(
                    id = ACTION_BROWSER_BOOKMARK,
                    title = "收藏",
                    iconRes = R.drawable.ic_browser_bookmark_add_24,
                    enabled = isCurrentPageBookmarkable()
                ),
                FloatingControlAction(
                    id = ACTION_BROWSER_BACK,
                    title = "后退",
                    iconRes = R.drawable.ic_browser_back_24,
                    enabled = if (isHomeScreen) false else state.canGoBack
                ),
                FloatingControlAction(
                    id = ACTION_BROWSER_FORWARD,
                    title = "前进",
                    iconRes = R.drawable.ic_browser_forward_24,
                    enabled = if (isHomeScreen) false else state.canGoForward
                ),
                FloatingControlAction(
                    id = if (state.isLoading) ACTION_BROWSER_STOP else ACTION_BROWSER_REFRESH,
                    title = refreshTitle,
                    iconRes = refreshIcon,
                    highlighted = if (isHomeScreen) false else state.isLoading,
                    enabled = !isHomeScreen
                ),
                FloatingControlAction(
                    id = ACTION_BROWSER_FORCE_REFRESH,
                    title = "强刷",
                    iconRes = R.drawable.ic_browser_refresh_24,
                    style = FloatingControlActionStyle.NORMAL,
                    enabled = !isHomeScreen
                )
            )
        )
    }

    private fun sectionView(section: FloatingControlSection, isLast: Boolean): View {
        if (section.id == SECTION_TABS) {
            return customTabsSectionView()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (!isLast) {
                setPadding(0, 0, 0, dp(12))
            }
            addView(
                TextView(context).apply {
                    text = section.title
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(PanelMutedTextColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            )
            addView(actionStripView(section))
            if (section.helperText.isNotBlank()) {
                addView(
                    TextView(context).apply {
                        text = section.helperText
                        textSize = 12f
                        setTextColor(PanelMutedTextColor)
                        setPadding(0, dp(8), 0, 0)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    private fun customTabsSectionView(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(context).apply {
                            text = "标签页 (${state.tabs.size})"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(PanelMutedTextColor)
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    
                    addView(
                        ImageButton(context).apply {
                            setImageResource(R.drawable.ic_browser_add_24)
                            imageTintList = ColorStateList.valueOf(AccentColor)
                            contentDescription = "添加标签或应用"
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            background = roundedBackground(AccentSoftColor, dp(8))
                            setOnClickListener {
                                showAddTabDialog()
                            }
                        },
                        LinearLayout.LayoutParams(dp(28), dp(28))
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
            )
            
            val tabsCount = state.tabs.size
            val tabRowHeight = dp(38)
            val maxVisibleCount = 4
            
            val scrollView = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
                
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        state.tabs.forEach { tab ->
                            val isActive = tab.isActive
                            val cardBgColor = if (isActive) AccentSoftColor else ActionBackgroundColor
                            val textColor = if (isActive) AccentColor else PanelTextColor
                            
                            val cardView = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                background = roundedBackground(cardBgColor, dp(10), if (isActive) AccentColor else null, if (isActive) dp(1) else 0)
                                setPadding(dp(10), dp(4), dp(8), dp(4))
                                setOnClickListener {
                                    callbacks.onSwitchTab(tab.id)
                                    setPanelExpanded(expanded = false, animated = true)
                                }
                                
                                addView(
                                    TextView(context).apply {
                                        text = tab.title.takeIf { it.isNotBlank() } ?: tab.url.takeIf { it.isNotBlank() } ?: "新标签页"
                                        textSize = 11f
                                        setTextColor(textColor)
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.END
                                    },
                                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        rightMargin = dp(8)
                                    }
                                )
                                
                                addView(
                                    ImageButton(context).apply {
                                        setImageResource(R.drawable.ic_browser_close_24)
                                        imageTintList = ColorStateList.valueOf(textColor)
                                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                                        setPadding(dp(4), dp(4), dp(4), dp(4))
                                        background = null
                                        setOnClickListener {
                                            callbacks.onCloseTab(tab.id)
                                        }
                                    },
                                    LinearLayout.LayoutParams(dp(22), dp(22))
                                )
                            }
                            
                            addView(
                                cardView,
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(34)
                                ).apply {
                                    bottomMargin = dp(4)
                                }
                            )
                        }
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            
            val containerHeight = if (tabsCount > maxVisibleCount) {
                (tabRowHeight * 4.5).toInt()
            } else {
                LinearLayout.LayoutParams.WRAP_CONTENT
            }
            
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    containerHeight
                )
            )
        }
    }

    private fun createAppIconView(context: Context, app: site.anzz.childkiosk.data.WebAppEntity): View {
        return FrameLayout(context).apply {
            val size = dp(24)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            
            var loadedBitmap: android.graphics.Bitmap? = null
            if (!app.iconPath.isNullOrBlank()) {
                val file = java.io.File(app.iconPath)
                if (file.exists() && file.isFile) {
                    loadedBitmap = runCatching {
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    }.getOrNull()
                }
            }
            
            if (loadedBitmap != null) {
                addView(
                    android.widget.ImageView(context).apply {
                        setImageBitmap(loadedBitmap)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = roundedBackground(Color.TRANSPARENT, dp(6))
                        clipToOutline = true
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                )
            } else {
                val char = app.title.trim().take(1).uppercase()
                val colors = listOf(
                    Color.rgb(244, 67, 54),   // 红色
                    Color.rgb(233, 30, 99),   // 粉红
                    Color.rgb(156, 39, 176),  // 紫色
                    Color.rgb(103, 58, 183),  // 深紫
                    Color.rgb(63, 81, 181),   // 蓝色
                    Color.rgb(33, 150, 243),  // 浅蓝
                    Color.rgb(0, 150, 136),   // 蓝绿
                    Color.rgb(76, 175, 80),   // 绿色
                    Color.rgb(255, 152, 0),   // 橙色
                    Color.rgb(121, 85, 72)    // 褐色
                )
                val colorIndex = Math.abs(app.title.hashCode()) % colors.size
                val avatarColor = colors[colorIndex]
                
                addView(
                    TextView(context).apply {
                        text = char
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background = roundedBackground(avatarColor, dp(12))
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                )
            }
        }
    }

    private fun showAddTabDialog() {
        val ctx = context
        setPanelExpanded(expanded = false, animated = true)
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val webApps = withContext(Dispatchers.IO) {
                    val db = site.anzz.childkiosk.data.AppDatabase.getInstance(ctx)
                    db.webAppDao().getAllWebApps().filter { it.isEnabled }
                }
                renderAddTabDialog(ctx, webApps)
            } catch (e: Exception) {
                android.util.Log.e("FloatingBrowser", "Failed to load apps for add dialog", e)
            }
        }
    }

    private fun renderAddTabDialog(ctx: Context, webApps: List<site.anzz.childkiosk.data.WebAppEntity>) {
        WebAppSelectDialog.show(
            context = ctx,
            webApps = webApps,
            showNewTabButton = true,
            onNewTabClick = {
                callbacks.onNewTab()
            },
            onWebAppSelect = { app ->
                callbacks.onOpenWebApp(app)
            }
        )
    }

    private fun actionStripView(section: FloatingControlSection): HorizontalScrollView {
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    section.actions.forEach { action ->
                        addView(actionButtonView(action), actionItemLayoutParams())
                    }
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun actionButtonView(action: FloatingControlAction): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isEnabled = action.enabled
            alpha = if (action.enabled) 1f else 0.36f
            background = roundedBackground(actionBackgroundColor(action), dp(12))
            minimumWidth = dp(50)
            minimumHeight = dp(56)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = action.title
            if (action.id == ACTION_BROWSER_FORCE_REFRESH) {
                setOnClickListener {
                    if (action.enabled) {
                        android.widget.Toast.makeText(context, "长按“强刷”以执行强制刷新", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener {
                    if (action.enabled) {
                        handleAction(action.id)
                        true
                    } else {
                        false
                    }
                }
            } else {
                setOnClickListener {
                    if (action.enabled) {
                        handleAction(action.id)
                    }
                }
            }
            addView(
                ImageView(context).apply {
                    setImageResource(action.iconRes)
                    imageTintList = ColorStateList.valueOf(actionTintColor(action))
                    scaleType = ImageView.ScaleType.CENTER
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    bottomMargin = dp(2)
                }
            )
            addView(
                TextView(context).apply {
                    text = action.title
                    textSize = 10f
                    setTextColor(actionTintColor(action))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun handleAction(actionId: String) {
        when (actionId) {
            ACTION_BROWSER_HOME -> {
                setPanelExpanded(expanded = false, animated = false)
                callbacks.onHome()
            }
            ACTION_BROWSER_BACK -> callbacks.onBack()
            ACTION_BROWSER_FORWARD -> callbacks.onForward()
            ACTION_BROWSER_REFRESH -> {
                setPanelExpanded(expanded = false, animated = true)
                callbacks.onRefresh()
            }
            ACTION_BROWSER_FORCE_REFRESH -> {
                setPanelExpanded(expanded = false, animated = true)
                callbacks.onForceRefresh()
            }
            ACTION_BROWSER_STOP -> callbacks.onStopLoading()
            ACTION_BROWSER_BOOKMARK -> callbacks.onBookmarkCurrentPage()
            ACTION_PANEL_CLOSE -> setPanelExpanded(expanded = false, animated = true)
            else -> Unit
        }
        callbacks.onActionSelected(actionId)
        if (actionId != ACTION_PANEL_CLOSE) {
            scheduleEdgeHide()
        }
    }

    private fun actionItemLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(dp(50), dp(56)).apply {
            rightMargin = dp(6)
        }
    }

    private fun isCurrentPageBookmarkable(): Boolean {
        if (state.isHomeScreen) return false
        val url = state.currentUrl.trim()
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun actionBackgroundColor(action: FloatingControlAction): Int {
        return when {
            action.style == FloatingControlActionStyle.DESTRUCTIVE -> DestructiveSoftColor
            action.highlighted || action.style == FloatingControlActionStyle.PRIMARY -> AccentSoftColor
            else -> ActionBackgroundColor
        }
    }

    private fun actionTintColor(action: FloatingControlAction): Int {
        return when {
            action.style == FloatingControlActionStyle.DESTRUCTIVE -> DestructiveColor
            action.highlighted || action.style == FloatingControlActionStyle.PRIMARY -> AccentColor
            else -> PanelTextColor
        }
    }

    private fun maxScrollableSectionsHeight(): Int {
        val usableHeight = height - imeHeight
        if (usableHeight <= 0) return dp(120)
        return (usableHeight - panelMargin * 2 - dp(150)).coerceAtLeast(dp(80))
    }

    private fun placeInitialBubble() {
        if (width <= 0 || height <= 0) return
        val x = maxX()
        val y = ((height * 0.62f) - (bubbleSize / 2f)).coerceIn(minY(), maxY())
        moveBubbleTo(x, y)
        isAttachedToRightEdge = true
        hideBubbleAtEdge(animated = false)
    }

    private fun moveBubbleTo(targetX: Float, targetY: Float) {
        val clampedX = targetX.coerceIn(minX(), maxX())
        val clampedY = targetY.coerceIn(minY(), maxY())
        bubbleButton.x = clampedX
        bubbleButton.y = clampedY
        isBubbleHiddenAtEdge = false
        positionPanel()
    }

    private fun constrainBubbleIntoBounds(animated: Boolean) {
        val x = bubbleButton.x.coerceIn(minX(), maxX())
        val y = bubbleButton.y.coerceIn(minY(), maxY())
        if (animated) {
            animateBubbleTo(x, y) {
                if (!panelExpanded) scheduleEdgeHide()
            }
        } else {
            bubbleButton.x = x
            bubbleButton.y = y
        }
    }

    private fun restoreBubbleAfterBoundsChange(animated: Boolean) {
        if (isBubbleHiddenAtEdge && !panelExpanded && !isDraggingBubble) {
            hideBubbleAtEdge(animated = animated)
        } else {
            constrainBubbleIntoBounds(animated = animated)
        }
    }

    private fun snapBubbleToNearestEdge(animated: Boolean) {
        if (width <= 0) return
        val centerX = bubbleButton.x + bubbleSize / 2f
        isAttachedToRightEdge = centerX >= width / 2f
        val targetX = if (isAttachedToRightEdge) maxX() else minX()
        val targetY = bubbleButton.y.coerceIn(minY(), maxY())
        if (animated) {
            animateBubbleTo(targetX, targetY) {
                positionPanel()
                scheduleEdgeHide()
            }
        } else {
            bubbleButton.x = targetX
            bubbleButton.y = targetY
            positionPanel()
            scheduleEdgeHide()
        }
    }

    private fun hideBubbleAtEdge(animated: Boolean) {
        if (width <= 0) return
        val targetX = if (isAttachedToRightEdge) {
            width - visibleEdgeWidth.toFloat()
        } else {
            -bubbleSize + visibleEdgeWidth.toFloat()
        }
        isBubbleHiddenAtEdge = true
        val targetY = bubbleButton.y.coerceIn(minY(), maxY())
        if (animated) {
            animateBubbleTo(targetX, targetY, targetAlpha = 0.4f)
        } else {
            bubbleButton.x = targetX
            bubbleButton.y = targetY
            bubbleButton.alpha = 0.4f
            positionPanel()
        }
    }

    private fun revealBubble(animated: Boolean) {
        if (!isBubbleHiddenAtEdge) return
        isBubbleHiddenAtEdge = false
        val targetX = if (isAttachedToRightEdge) maxX() else minX()
        if (animated) {
            animateBubbleTo(targetX, bubbleButton.y.coerceIn(minY(), maxY()), targetAlpha = 1.0f) {
                positionPanel()
            }
        } else {
            bubbleButton.x = targetX
            bubbleButton.alpha = 1.0f
            positionPanel()
        }
    }

    private fun animateBubbleTo(
        targetX: Float,
        targetY: Float,
        targetAlpha: Float = 1.0f,
        endAction: (() -> Unit)? = null
    ) {
        bubbleButton.animate()
            .x(targetX)
            .y(targetY)
            .alpha(targetAlpha)
            .setDuration(animationDurationMs)
            .withEndAction { endAction?.invoke() }
            .start()
    }

    private fun updatePanelWidth() {
        if (width <= 0) return
        val available = (width - panelMargin * 2).coerceAtLeast(0)
        val targetWidth = min(panelMaxWidth, available)
            .coerceAtLeast(min(panelMinWidth, available))
        val params = panelView.layoutParams
        if (params.width != targetWidth) {
            params.width = targetWidth
            panelView.layoutParams = params
        }
    }

    private fun positionPanel() {
        if (width <= 0 || height <= 0 || panelView.width <= 0) return
        val panelWidth = panelView.width
        val panelHeight = panelView.height.takeIf { it > 0 } ?: panelView.measuredHeight
        val targetX = if (isAttachedToRightEdge) {
            width - panelMargin - panelWidth
        } else {
            panelMargin
        }.coerceIn(panelMargin, max(panelMargin, width - panelMargin - panelWidth))
        val desiredY = bubbleButton.y + bubbleSize / 2f - panelHeight / 2f
        val targetY = desiredY.coerceIn(
            panelMargin.toFloat(),
            max(panelMargin.toFloat(), height - imeHeight - panelMargin - panelHeight.toFloat())
        )
        panelView.x = targetX.toFloat()
        panelView.y = targetY
    }

    private fun animatePanel(show: Boolean, animated: Boolean) {
        panelView.animate().cancel()
        if (!animated) {
            panelView.alpha = if (show) 1f else 0f
            panelView.scaleX = if (show) 1f else 0.96f
            panelView.scaleY = if (show) 1f else 0.96f
            panelView.isVisible = show
            return
        }
        if (show) {
            panelView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(animationDurationMs)
                .start()
        } else {
            panelView.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(animationDurationMs)
                .withEndAction {
                    if (!panelExpanded) {
                        panelView.isVisible = false
                    }
                }
                .start()
        }
    }

    private fun scheduleEdgeHide() {
        removeCallbacks(hideRunnable)
        if (!panelExpanded && !urlInput.hasFocus()) {
            postDelayed(hideRunnable, idleHideDelayMs)
        }
    }

    private fun syncInputFromStateIfNeeded() {
        if (!urlInput.hasFocus() && urlInput.text?.toString() != state.currentUrl) {
            urlInput.setText(state.currentUrl)
            urlInput.setSelection(0)
            urlInput.scrollTo(0, 0)
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun browserIconButton(
        @DrawableRes iconRes: Int,
        contentDescription: String,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(PanelTextColor)
            this.contentDescription = contentDescription
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(ActionBackgroundColor, dp(12))
            minimumWidth = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun minX(): Float = edgeMargin.toFloat()

    private fun maxX(): Float = max(edgeMargin, width - edgeMargin - bubbleSize).toFloat()

    private fun minY(): Float = edgeMargin.toFloat()

    private fun maxY(): Float = max(edgeMargin, height - edgeMargin - bubbleSize).toFloat()

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private val AccentColor = Color.rgb(28, 98, 92)
        private val AccentSoftColor = Color.rgb(219, 237, 232)
        private val DestructiveColor = Color.rgb(174, 54, 42)
        private val DestructiveSoftColor = Color.rgb(251, 230, 226)
        private val PanelBackgroundColor = Color.rgb(248, 250, 248)
        private val ActionBackgroundColor = Color.rgb(232, 240, 238)
        private val PanelTextColor = Color.rgb(31, 42, 38)
        private val PanelMutedTextColor = Color.rgb(92, 106, 101)
        private val PanelHintColor = Color.rgb(125, 139, 134)
        private val StrokeColor = Color.rgb(209, 221, 216)

        const val SECTION_TABS = "tabs"
        const val SECTION_BROWSER = "browser"
        const val ACTION_BROWSER_BACK = "browser.back"
        const val ACTION_BROWSER_FORWARD = "browser.forward"
        const val ACTION_BROWSER_REFRESH = "browser.refresh"
        const val ACTION_BROWSER_FORCE_REFRESH = "browser.force_refresh"
        const val ACTION_BROWSER_STOP = "browser.stop"
        const val ACTION_BROWSER_BOOKMARK = "browser.bookmark"
        const val ACTION_PANEL_CLOSE = "panel.close"
        const val ACTION_BROWSER_HOME = "browser.home"

        fun attachTo(
            root: FrameLayout,
            initialState: FloatingBrowserControlsState = FloatingBrowserControlsState(),
            callbacks: FloatingBrowserControlsCallbacks = FloatingBrowserControlsCallbacks()
        ): FloatingBrowserControlsOverlay {
            return FloatingBrowserControlsOverlay(root.context).apply {
                setCallbacks(callbacks)
                updateState(initialState)
                root.addView(
                    this,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    }
}

private class HeightLimitedScrollView(context: Context) : ScrollView(context) {
    var maxContentHeight: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val requestedMaxHeight = maxContentHeight.coerceAtLeast(0)
        val cappedHeight = when (heightMode) {
            MeasureSpec.UNSPECIFIED -> requestedMaxHeight
            else -> min(heightSize, requestedMaxHeight)
        }
        val cappedSpec = MeasureSpec.makeMeasureSpec(cappedHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}
