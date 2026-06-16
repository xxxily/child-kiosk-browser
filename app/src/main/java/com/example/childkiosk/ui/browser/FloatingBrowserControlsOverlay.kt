package com.example.childkiosk.ui.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.example.childkiosk.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FloatingBrowserControlsState(
    val currentUrl: String = "",
    val pageTitle: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0
)

data class FloatingBrowserControlsCallbacks(
    val onNavigateToUrl: (String) -> Unit = {},
    val onBack: () -> Unit = {},
    val onForward: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onStopLoading: () -> Unit = {},
    val onPanelExpandedChanged: (Boolean) -> Unit = {}
)

class FloatingBrowserControlsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeMargin = dp(12)
    private val visibleEdgeWidth = dp(20)
    private val bubbleSize = dp(72)
    private val panelMaxWidth = dp(380)
    private val panelMinWidth = dp(288)
    private val panelMargin = dp(16)
    private val idleHideDelayMs = 2800L
    private val animationDurationMs = 180L

    private var callbacks = FloatingBrowserControlsCallbacks()
    private var state = FloatingBrowserControlsState()
    private var panelExpanded = false
    private var isDraggingBubble = false
    private var isBubbleHiddenAtEdge = false
    private var isAttachedToRightEdge = true
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

    private val urlInput = EditText(context).apply {
        minHeight = dp(56)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_GO
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        textSize = 15f
        setTextColor(PanelTextColor)
        setHintTextColor(PanelHintColor)
        hint = "输入网址"
        setPadding(dp(14), 0, dp(14), 0)
        background = roundedBackground(Color.WHITE, dp(16), StrokeColor, dp(1))
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

    private val backButton = browserIconButton(
        iconRes = R.drawable.ic_browser_back_24,
        contentDescription = "后退"
    ) {
        callbacks.onBack()
        scheduleEdgeHide()
    }

    private val forwardButton = browserIconButton(
        iconRes = R.drawable.ic_browser_forward_24,
        contentDescription = "前进"
    ) {
        callbacks.onForward()
        scheduleEdgeHide()
    }

    private val refreshButton = browserIconButton(
        iconRes = R.drawable.ic_browser_refresh_24,
        contentDescription = "刷新"
    ) {
        if (state.isLoading) {
            callbacks.onStopLoading()
        } else {
            callbacks.onRefresh()
        }
        scheduleEdgeHide()
    }

    private val closeButton = browserIconButton(
        iconRes = R.drawable.ic_browser_close_24,
        contentDescription = "收起"
    ) {
        setPanelExpanded(expanded = false, animated = true)
    }

    private val goButton = browserIconButton(
        iconRes = R.drawable.ic_browser_go_24,
        contentDescription = "访问"
    ) {
        submitUrl()
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
            titleView,
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
                addView(
                    urlInput,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                addView(
                    goButton,
                    LinearLayout.LayoutParams(dp(56), dp(56)).apply {
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
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton, actionButtonLayoutParams())
                addView(forwardButton, actionButtonLayoutParams())
                addView(refreshButton, actionButtonLayoutParams())
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(closeButton, actionButtonLayoutParams())
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
    }

    private val bubbleButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_floating_browser_menu_24)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = "浏览控制"
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = roundedBackground(AccentColor, bubbleSize / 2)
        elevation = dp(10).toFloat()
        setOnTouchListener { _, event -> handleBubbleTouch(event) }
    }

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

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            doOnLayout {
                constrainBubbleIntoBounds(animated = false)
                positionPanel()
            }
            insets
        }

        doOnLayout {
            if (bubbleButton.x == 0f && bubbleButton.y == 0f) {
                placeInitialBubble()
            } else {
                constrainBubbleIntoBounds(animated = false)
            }
            scheduleEdgeHide()
        }
    }

    fun setCallbacks(callbacks: FloatingBrowserControlsCallbacks) {
        this.callbacks = callbacks
    }

    fun updateState(nextState: FloatingBrowserControlsState) {
        state = nextState.copy(progress = nextState.progress.coerceIn(0, 100))
        titleView.text = state.pageTitle.takeIf { it.isNotBlank() }
            ?: state.currentUrl.takeIf { it.isNotBlank() }
            ?: "浏览控制"
        if (!urlInput.hasFocus()) {
            urlInput.setText(state.currentUrl)
            urlInput.setSelection(urlInput.text?.length ?: 0)
        }
        setButtonEnabled(backButton, state.canGoBack)
        setButtonEnabled(forwardButton, state.canGoForward)
        refreshButton.contentDescription = if (state.isLoading) "停止加载" else "刷新"
        refreshButton.setImageResource(
            if (state.isLoading) R.drawable.ic_browser_close_24 else R.drawable.ic_browser_refresh_24
        )
        progressView.progress = state.progress
        progressView.isVisible = state.isLoading || state.progress in 1..99
    }

    fun setPanelExpanded(expanded: Boolean, animated: Boolean = true) {
        if (panelExpanded == expanded && panelView.isVisible == expanded) return
        panelExpanded = expanded
        callbacks.onPanelExpandedChanged(expanded)
        removeCallbacks(hideRunnable)

        if (expanded) {
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
        if (w > 0 && h > 0) {
            if (oldw == 0 || oldh == 0) {
                placeInitialBubble()
            } else {
                constrainBubbleIntoBounds(animated = false)
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

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                bubbleButton.animate().cancel()
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

    private fun placeInitialBubble() {
        if (width <= 0 || height <= 0) return
        val x = maxX()
        val y = ((height * 0.62f) - (bubbleSize / 2f)).coerceIn(minY(), maxY())
        moveBubbleTo(x, y)
        isAttachedToRightEdge = true
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
        if (animated) {
            animateBubbleTo(targetX, bubbleButton.y.coerceIn(minY(), maxY()))
        } else {
            bubbleButton.x = targetX
        }
    }

    private fun revealBubble(animated: Boolean) {
        if (!isBubbleHiddenAtEdge) return
        isBubbleHiddenAtEdge = false
        val targetX = if (isAttachedToRightEdge) maxX() else minX()
        if (animated) {
            animateBubbleTo(targetX, bubbleButton.y.coerceIn(minY(), maxY())) {
                positionPanel()
            }
        } else {
            bubbleButton.x = targetX
            positionPanel()
        }
    }

    private fun animateBubbleTo(
        targetX: Float,
        targetY: Float,
        endAction: (() -> Unit)? = null
    ) {
        bubbleButton.animate()
            .x(targetX)
            .y(targetY)
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
            max(panelMargin.toFloat(), height - panelMargin - panelHeight.toFloat())
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
            urlInput.setSelection(urlInput.text?.length ?: 0)
        }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun setButtonEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.36f
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
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(ActionBackgroundColor, dp(16))
            minimumWidth = dp(56)
            minimumHeight = dp(56)
            setOnClickListener { onClick() }
        }
    }

    private fun actionButtonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            rightMargin = dp(8)
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
        private val PanelBackgroundColor = Color.rgb(248, 250, 248)
        private val ActionBackgroundColor = Color.rgb(232, 240, 238)
        private val PanelTextColor = Color.rgb(31, 42, 38)
        private val PanelMutedTextColor = Color.rgb(92, 106, 101)
        private val PanelHintColor = Color.rgb(125, 139, 134)
        private val StrokeColor = Color.rgb(209, 221, 216)

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
