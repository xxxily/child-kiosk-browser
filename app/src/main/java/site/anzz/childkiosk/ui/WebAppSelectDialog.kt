package site.anzz.childkiosk.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.load
import site.anzz.childkiosk.R
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.util.WebAppIconCache
import kotlin.math.abs

/**
 * Reusable dialog for selecting from enabled Web Apps in standard grid layout.
 * Used for both adding browser tabs and adding high-performance rules.
 */
object WebAppSelectDialog {
    private val AccentColor = Color.rgb(28, 98, 92)
    private val PanelBackgroundColor = Color.rgb(248, 250, 248)
    private val ActionBackgroundColor = Color.rgb(232, 240, 238)
    private val PanelTextColor = Color.rgb(31, 42, 38)
    private val PanelMutedTextColor = Color.rgb(92, 106, 101)

    fun show(
        context: Context,
        webApps: List<WebAppEntity>,
        showNewTabButton: Boolean,
        onNewTabClick: (() -> Unit)? = null,
        onWebAppSelect: (WebAppEntity) -> Unit
    ) {
        var dialogRef: AlertDialog? = null
        val builder = AlertDialog.Builder(context)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        fun roundedBackground(color: Int, radius: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = radius.toFloat()
            }
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(PanelBackgroundColor, dp(16))
        }

        if (showNewTabButton) {
            val newTabBtn = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = roundedBackground(AccentColor, dp(12))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    onNewTabClick?.invoke()
                    dialogRef?.dismiss()
                }

                addView(
                    android.widget.ImageView(context).apply {
                        setImageResource(R.drawable.ic_browser_add_24)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    },
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        rightMargin = dp(8)
                    }
                )

                addView(
                    TextView(context).apply {
                        text = "新建空白标签页"
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    }
                )
            }
            rootLayout.addView(newTabBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            })
        }

        val categories = listOf(
            WebAppEntity.CATEGORY_STUDY,
            WebAppEntity.CATEGORY_BOOK,
            WebAppEntity.CATEGORY_GAME,
            WebAppEntity.CATEGORY_VIDEO,
            WebAppEntity.CATEGORY_TOOL,
            WebAppEntity.CATEGORY_OTHER
        )

        val appsByCategory = webApps.groupBy { it.category }

        val scrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = false

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL

                    categories.forEach { cat ->
                        val apps = appsByCategory[cat] ?: emptyList()
                        if (apps.isNotEmpty()) {
                            addView(
                                TextView(context).apply {
                                    val emoji = WebAppEntity.getCategoryEmoji(cat)
                                    val name = WebAppEntity.getCategoryDisplayName(cat)
                                    text = "$emoji $name"
                                    textSize = 13f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(PanelMutedTextColor)
                                    setPadding(0, dp(12), 0, dp(6))
                                },
                                LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            )

                            val chunked = apps.chunked(2)
                            chunked.forEach { rowApps ->
                                val rowLayout = LinearLayout(context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    weightSum = 2f
                                }

                                rowApps.forEach { app ->
                                    val appCard = LinearLayout(context).apply {
                                        orientation = LinearLayout.HORIZONTAL
                                        gravity = Gravity.CENTER_VERTICAL
                                        background = roundedBackground(ActionBackgroundColor, dp(10))
                                        setPadding(dp(8), dp(8), dp(8), dp(8))
                                        setOnClickListener {
                                            onWebAppSelect(app)
                                            dialogRef?.dismiss()
                                        }

                                        val iconFrame = FrameLayout(context).apply {
                                            val iconSize = dp(24)
                                            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                                                gravity = Gravity.CENTER_VERTICAL
                                            }

                                            val preferredIconPath = WebAppIconCache.preferredIconPath(
                                                context = context,
                                                cachedSiteIconPath = app.siteIconPath,
                                                fallbackIconPath = app.iconPath
                                            )
                                            val char = app.title.trim().take(1).uppercase()
                                            val colors = listOf(
                                                Color.rgb(244, 67, 54),
                                                Color.rgb(233, 30, 99),
                                                Color.rgb(156, 39, 176),
                                                Color.rgb(103, 58, 183),
                                                Color.rgb(63, 81, 181),
                                                Color.rgb(33, 150, 243),
                                                Color.rgb(0, 150, 136),
                                                Color.rgb(76, 175, 80),
                                                Color.rgb(255, 152, 0),
                                                Color.rgb(121, 85, 72)
                                            )
                                            val avatarColor = colors[abs(app.title.hashCode()) % colors.size]
                                            addView(
                                                TextView(context).apply {
                                                    text = char
                                                    textSize = 10f
                                                    typeface = Typeface.DEFAULT_BOLD
                                                    setTextColor(Color.WHITE)
                                                    gravity = Gravity.CENTER
                                                    background = roundedBackground(avatarColor, dp(12))
                                                },
                                                FrameLayout.LayoutParams(
                                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                                    FrameLayout.LayoutParams.MATCH_PARENT
                                                )
                                            )

                                            WebAppIconCache.resolveCachedFile(context, preferredIconPath)?.let { file ->
                                                val imageView = android.widget.ImageView(context).apply {
                                                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                                    background = roundedBackground(Color.TRANSPARENT, dp(6))
                                                    clipToOutline = true
                                                }
                                                addView(
                                                    imageView,
                                                    FrameLayout.LayoutParams(
                                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                                        FrameLayout.LayoutParams.MATCH_PARENT
                                                    )
                                                )
                                                imageView.load(file) {
                                                    crossfade(false)
                                                    listener(
                                                        onError = { _, _ -> imageView.visibility = View.GONE }
                                                    )
                                                }
                                            }
                                        }

                                        addView(iconFrame)

                                        addView(
                                            TextView(context).apply {
                                                text = app.title
                                                textSize = 12f
                                                setTextColor(PanelTextColor)
                                                maxLines = 1
                                                ellipsize = TextUtils.TruncateAt.END
                                                setPadding(dp(8), 0, 0, 0)
                                            },
                                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                        )
                                    }

                                    rowLayout.addView(
                                        appCard,
                                        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                            rightMargin = dp(4)
                                        }
                                    )
                                }

                                repeat(2 - rowApps.size) {
                                    rowLayout.addView(
                                        View(context),
                                        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                                            rightMargin = dp(4)
                                        }
                                    )
                                }

                                addView(rowLayout, LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    bottomMargin = dp(6)
                                })
                            }
                        }
                    }
                }
            )
        }

        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(300)
        ))

        val dialog = builder.setView(rootLayout).create()
        dialogRef = dialog
        dialog.show()
    }
}
