package com.teamsassignments.widget.automation

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * A pill drawn over Teams in a `TYPE_ACCESSIBILITY_OVERLAY` window, which an accessibility
 * service may add without the overlay permission. It sits over Teams' toolbar: the automation
 * never touches that area, and it keeps stray touches off the Hand in button.
 *
 * It also says how a row tap or hand-in went ([showMessage]), near the bottom of the screen, as
 * a toast would. A toast itself can't: Android drops one from an app in the background that has
 * no notification permission, and the service is in the background whenever it has news.
 */
class OverlayBanner(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var label: TextView? = null
    private var action: TextView? = null
    private var spinner: ProgressBar? = null
    private var isMessage = false

    val isShowing: Boolean get() = root != null

    /** Shows (or updates) the pill with [message] and an [actionLabel] button, or no button if it's empty. */
    fun show(message: String, actionLabel: String, showSpinner: Boolean, onAction: () -> Unit) {
        // A message still showing gives way: it sits elsewhere on the screen, and hides itself.
        if (isMessage) hide()
        if (root == null) {
            val view = build()
            runCatching { windowManager.addView(view, layoutParams(atBottom = false)) }.onFailure { return }
            root = view
        }
        label?.text = message
        action?.text = actionLabel
        action?.setOnClickListener { onAction() }
        action?.visibility = if (actionLabel.isEmpty()) View.GONE else View.VISIBLE
        // Without the button, the label keeps the pill its usual height and balances its ends.
        if (actionLabel.isEmpty()) label?.setPadding(dp(12), dp(10), dp(12), dp(10)) else label?.setPadding(dp(12), 0, dp(8), 0)
        spinner?.visibility = if (showSpinner) View.VISIBLE else View.GONE
    }

    fun update(message: String) {
        label?.text = message
    }

    /**
     * Shows [message] on its own for [MESSAGE_MS], or until it's tapped or the pill is needed
     * again. It replaces whatever the pill showed.
     */
    fun showMessage(message: String) {
        hide()
        val view = build()
        runCatching { windowManager.addView(view, layoutParams(atBottom = true)) }.onFailure { return }
        root = view
        isMessage = true
        spinner?.visibility = View.GONE
        action?.visibility = View.GONE
        label?.apply {
            text = message
            maxLines = MESSAGE_MAX_LINES
            maxWidth = service.resources.displayMetrics.widthPixels - dp(72)
            setPadding(0, dp(8), dp(10), dp(8))
        }
        // Only while this is still the view on screen: by then the pill may be showing a run.
        val dismiss = { if (root === view) hide() }
        view.setOnClickListener { dismiss() }
        view.postDelayed({ dismiss() }, MESSAGE_MS)
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        label = null
        action = null
        spinner = null
        isMessage = false
    }

    private fun layoutParams(atBottom: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        y = dp(if (atBottom) 96 else 44)
    }

    private fun build(): View {
        // Material You colours where available, so the pill matches the phone's theme.
        val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val surface = if (dynamic) service.getColor(android.R.color.system_neutral1_800) else 0xFF2B2D31.toInt()
        val onSurface = if (dynamic) service.getColor(android.R.color.system_neutral1_50) else Color.WHITE
        val accent = if (dynamic) service.getColor(android.R.color.system_accent1_200) else 0xFFA8C7FA.toInt()

        spinner = ProgressBar(service, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(accent)
        }
        label = TextView(service).apply {
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), 0, dp(8), 0)
            maxLines = 1
        }
        action = TextView(service).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) },
            )
            isClickable = true
        }
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(surface)
            }
            elevation = dp(6).toFloat()
            addView(spinner, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(label)
            addView(action)
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        /** A little longer than a long toast: these run to three lines. */
        const val MESSAGE_MS = 5_000L
        const val MESSAGE_MAX_LINES = 4
    }
}
