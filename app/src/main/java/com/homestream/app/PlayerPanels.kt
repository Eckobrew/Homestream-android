package com.homestream.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun showPlayerChoices(anchor: PlayerView, labels: List<String>, selected: Int = -1, choose: (Int) -> Unit) {
    val content = LinearLayout(anchor.context).apply { orientation = LinearLayout.VERTICAL }
    lateinit var popup: PopupWindow
    labels.forEachIndexed { index, label ->
        val row = LayoutInflater.from(anchor.context).inflate(androidx.media3.ui.R.layout.exo_styled_sub_settings_list_item, content, false)
        row.findViewById<TextView>(androidx.media3.ui.R.id.exo_text).text = label
        row.findViewById<View>(androidx.media3.ui.R.id.exo_check).visibility = if (selected == index) View.VISIBLE else View.INVISIBLE
        row.isFocusable = true
        row.setOnClickListener { popup.dismiss(); choose(index) }
        content.addView(row)
    }
    popup = showPlayerPopup(anchor, ScrollView(anchor.context).apply { addView(content) })
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun showPlayerPopup(anchor: PlayerView, content: View): PopupWindow {
    val resources = anchor.resources
    val margin = resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_settings_offset)
    val maxWidth = (anchor.width - margin * 2).coerceAtLeast(1)
    val maxHeight = (anchor.height - margin * 2).coerceAtLeast(1)
    content.setBackgroundColor(resources.getColor(androidx.media3.ui.R.color.exo_black_opacity_70, anchor.context.theme))
    content.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
    return PopupWindow(content, content.measuredWidth, content.measuredHeight, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        anchor.showController()
        val timeout = anchor.controllerShowTimeoutMs
        anchor.controllerShowTimeoutMs = 0
        setOnDismissListener { anchor.controllerShowTimeoutMs = timeout; anchor.showController() }
        showAsDropDown(anchor, anchor.width - width - margin, -height - margin)
        content.requestFocus()
    }
}
