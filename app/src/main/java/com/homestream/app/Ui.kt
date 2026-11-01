package com.homestream.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

const val RED = 0xFFE50914.toInt()
const val INK = 0xFF080808.toInt()
const val MUTED = 0xFFB3B3B3.toInt()
fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
fun Context.isTelevision() = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.label(value: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(Color.WHITE)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}
fun rounded(color: Int, stroke: Int = Color.TRANSPARENT, radius: Float = 10f) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; setStroke(4, stroke)
}
fun Context.badgePill(value: String) = TextView(this).apply {
    text = value; textSize = 11f; setTextColor(Color.WHITE)
    background = GradientDrawable().apply {
        setColor(0x33FFFFFF.toInt()); cornerRadius = 6f
        setStroke(1, 0x66FFFFFF.toInt())
    }
    setPadding(dp(6), dp(2), dp(6), dp(2))
}
fun View.focusableCard(color: Int = 0xFF202020.toInt()) {
    isFocusable = true; isClickable = true
    defaultFocusHighlightEnabled = false
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(color, Color.WHITE))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(color, RED))
        addState(intArrayOf(), rounded(color))
    }
    setOnFocusChangeListener { view, focused ->
        val scale = if (focused && !context.isTelevision()) 1.035f else 1f
        view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    }
}
fun Context.action(title: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
    text = title; isAllCaps = false; textSize = 14f
    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Color.BLACK, if (primary) Color.BLACK else Color.WHITE)))
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(RED))
        addState(intArrayOf(), rounded(if (primary) Color.WHITE else 0xFF292929.toInt()))
    }
    minHeight = dp(48); setPadding(dp(18), dp(4), dp(18), dp(4))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
    setOnClickListener { click() }
}
