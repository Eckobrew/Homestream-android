package com.homestream.app

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager

/** A lightweight panel over the video rather than an opaque system dialog. */
internal fun <T : Dialog> T.showPlayerPanel(): T {
    show()
    val density = context.resources.displayMetrics.density
    window?.apply {
        setBackgroundDrawable(GradientDrawable().apply {
            setColor(0xDC101010.toInt())
            cornerRadius = 12 * density
            setStroke((density).toInt().coerceAtLeast(1), 0x22FFFFFF)
        })
        setDimAmount(0.12f)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        val available = context.resources.displayMetrics.widthPixels - (32 * density).toInt()
        setLayout((380 * density).toInt().coerceAtMost(available), WindowManager.LayoutParams.WRAP_CONTENT)
        attributes = attributes.apply { x = (16 * density).toInt() }
    }
    return this
}
