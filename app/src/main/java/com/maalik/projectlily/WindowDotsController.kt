package com.maalik.projectlily

import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/** Owns the three macOS-style window-dot interactions and their popovers. */
internal class WindowDotsController(
    private val activity: MainActivity,
    private val preferences: SharedPreferences,
    private val onDot1: () -> Unit,
    private val onSaveSql: () -> Unit,
    private val commandProvider: () -> List<Pair<String, () -> Unit>>
) {
    private val white = Color.rgb(231, 227, 233)
    private val panel = Color.rgb(20, 22, 29)
    private val row = Color.rgb(28, 30, 38)
    private val border = Color.rgb(58, 53, 64)
    private var hoverAnimator: ValueAnimator? = null

    fun setup() {
        bind(activity.findViewById(R.id.windowDot1), "Toggle database sidebar", onDot1)
        bind(activity.findViewById(R.id.windowDot2), "Save query") { onSaveSql() }
        bind(activity.findViewById(R.id.windowDot3), "Open command palette") { showCommandPalette() }
        updateState()
    }

    fun updateState() {
        listOf(R.id.windowDot1, R.id.windowDot2, R.id.windowDot3).forEach { id ->
            activity.findViewById<View>(id)?.apply { alpha = 0.82f }
        }
    }

    private fun bind(view: View?, description: String, action: (() -> Unit)? = null) {
        view ?: return
        view.contentDescription = description
        if (action != null) view.setOnClickListener { action() }
        view.isClickable = true
        view.isFocusable = true
        view.setOnHoverListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> animateHover(v, true)
                MotionEvent.ACTION_HOVER_EXIT -> animateHover(v, false)
            }
            false
        }
    }

    /** Subtle desktop-style hover feedback: scale + opacity, never a loud glow. */
    private fun animateHover(view: View, hovered: Boolean) {
        hoverAnimator?.cancel()
        val start = view.scaleX
        val end = if (hovered) 1.16f else 1f
        val animator = ValueAnimator.ofFloat(start, end).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val scale = a.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
                view.alpha = if (hovered) 1f else 0.82f
                view.translationZ = if (hovered) dp(2).toFloat() else 0f
            }
        }
        hoverAnimator = animator
        animator.start()
    }

    private fun showCommandPalette() {
        val anchor = activity.findViewById<View>(R.id.windowDot3) ?: return
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(panel, 1, border, 13f)
            isClickable = true
        }
        val search = EditText(activity).apply {
            hint = "Search commands"
            setHintTextColor(Color.rgb(105, 108, 119))
            setTextColor(white)
            textSize = 12.5f
            setSingleLine(true)
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(Color.rgb(27, 29, 36), 1, Color.rgb(53, 55, 64), 9f)
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(36)))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var popup: PopupWindow? = null

        fun render(filter: String) {
            list.removeAllViews()
            commandProvider().filter { it.first.contains(filter.trim(), true) }.forEachIndexed { index, pair ->
                val item = menuRow(pair.first) { popup?.dismiss(); pair.second() }
                list.addView(item, LinearLayout.LayoutParams(-1, dp(32)).apply { if (index > 0) topMargin = dp(1) })
            }
            if (list.childCount == 0) {
                list.addView(TextView(activity).apply {
                    text = "No matching commands"
                    textSize = 12f
                    setTextColor(Color.rgb(125, 128, 139))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                }, LinearLayout.LayoutParams(-1, dp(42)))
            }
        }

        val width = min(dp(258), activity.resources.displayMetrics.widthPixels - dp(20))
        popup = PopupWindow(root, width, dp(250), true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(e: Editable?) {}
        })
        render("")

        val defaultX = (activity.resources.displayMetrics.widthPixels - width - dp(10)).coerceAtLeast(dp(10))
        val defaultY = IntArray(2).also { anchor.getLocationOnScreen(it) }[1]
            .plus(anchor.height).plus(dp(6))
            .coerceAtMost(activity.resources.displayMetrics.heightPixels - dp(262))
        var popupX = preferences.getInt("command_palette_x", -1).let { if (it >= 0) it else defaultX }
            .coerceIn(dp(6), activity.resources.displayMetrics.widthPixels - width - dp(6))
        var popupY = preferences.getInt("command_palette_y", -1).let { if (it >= 0) it else defaultY }
            .coerceIn(dp(6), activity.resources.displayMetrics.heightPixels - dp(6))
        var dragX = 0f
        var dragY = 0f

        popup.setTouchInterceptor { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragX = e.rawX; dragY = e.rawY; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dragX
                    val dy = e.rawY - dragY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) {
                        popupX = (popupX + dx).toInt().coerceIn(dp(6), activity.resources.displayMetrics.widthPixels - width - dp(6))
                        popupY = (popupY + dy).toInt().coerceIn(dp(6), activity.resources.displayMetrics.heightPixels - dp(6))
                        preferences.edit().putInt("command_palette_x", popupX).putInt("command_palette_y", popupY).apply()
                        popup.update(popupX, popupY, -1, -1, true)
                        dragX = e.rawX; dragY = e.rawY
                    }
                    false
                }
                else -> false
            }
        }
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, popupX, popupY)
        search.requestFocus()
        search.postDelayed({
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120)
        animateIn(root)
    }

    private fun menuRow(textValue: String, action: () -> Unit): TextView = TextView(activity).apply {
        text = textValue
        textSize = 12f
        setTextColor(white)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        background = rounded(Color.TRANSPARENT, 0, Color.TRANSPARENT, 8f)
        isClickable = true
        setOnClickListener { action() }
        setOnHoverListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    v.background = rounded(Color.rgb(42, 44, 52), 0, Color.TRANSPARENT, 8f)
                    v.alpha = 1f
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    v.background = rounded(Color.TRANSPARENT, 0, Color.TRANSPARENT, 8f)
                    v.alpha = 0.94f
                }
            }
            false
        }
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun dp(value: Int): Int = round(value * activity.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, strokeColor: Int, radius: Float): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
            cornerRadius = dp(radius.toInt()).toFloat()
        }

    private fun dp(value: Float): Int = round(value * activity.resources.displayMetrics.density).toInt()
}

