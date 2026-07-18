package site.anzz.childkiosk

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * A [WebView] with the opt-in webpage IME restriction used by the kiosk sandbox.
 *
 * WebView visibility, screen state, and lifecycle callbacks deliberately remain untouched. Chromium
 * uses those callbacks together with Android focus state to establish its input connection; changing
 * them breaks normal IME behavior even when the explicit restriction is disabled.
 */
class PersistentWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {
    /**
     * When true, the WebView reports itself as a non-text-editor so the system IME is never
     * invoked for this view. This is an opt-in restriction controlled by the parent setting
     * [site.anzz.childkiosk.util.KioskPrefs.isLimitImeInputEnabled].
     */
    var imeInputLimited: Boolean = false
        private set

    fun applyImeInputLimit(limited: Boolean, refreshInputConnection: Boolean = false) {
        val changed = imeInputLimited != limited
        imeInputLimited = limited
        if (!changed && !refreshInputConnection) return

        post {
            if (isAttachedToWindow) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.restartInput(this)
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean {
        if (imeInputLimited) return false
        return super.onCheckIsTextEditor()
    }
}
