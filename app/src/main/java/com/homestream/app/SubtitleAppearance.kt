package com.homestream.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import android.widget.LinearLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SubtitleAppearance(context: Context) {
    private val prefs = context.getSharedPreferences("subtitle-appearance", Context.MODE_PRIVATE)
    private val sizes = listOf("Small" to 0.75f, "Medium" to 1f, "Large" to 1.25f, "Extra large" to 1.5f)
    private val colors = listOf("White" to Color.WHITE, "Yellow" to Color.YELLOW, "Green" to Color.GREEN, "Cyan" to Color.CYAN)
    private val backgrounds = listOf("None" to Color.TRANSPARENT, "Translucent black" to 0x99000000.toInt(), "Black" to Color.BLACK)
    private val edges = listOf("None" to CaptionStyleCompat.EDGE_TYPE_NONE, "Outline" to CaptionStyleCompat.EDGE_TYPE_OUTLINE, "Shadow" to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW)
    private fun index(key: String, count: Int, default: Int = 0) = prefs.getInt(key, default).coerceIn(0, count - 1)

    fun apply(view: SubtitleView?) {
        view ?: return
        val customized = prefs.getBoolean("customized", false)
        view.setApplyEmbeddedStyles(!customized)
        view.setApplyEmbeddedFontSizes(!customized)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizes[index("size", sizes.size, 1)].second)
        view.setStyle(if (!customized) CaptionStyleCompat.DEFAULT else CaptionStyleCompat(
            colors[index("color", colors.size)].second,
            backgrounds[index("background", backgrounds.size, 2)].second,
            Color.TRANSPARENT,
            edges[index("edge", edges.size)].second,
            Color.BLACK,
            Typeface.DEFAULT
        ))
    }

    fun show(context: Context, playerView: androidx.media3.ui.PlayerView) {
        val subtitles = playerView.subtitleView
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (8 * density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val preview = SubtitleView(context).apply {
            setCues(listOf(androidx.media3.common.text.Cue.Builder()
                .setText("This is a subtitle preview")
                .setLine(0.5f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION)
                .setLineAnchor(androidx.media3.common.text.Cue.ANCHOR_TYPE_MIDDLE)
                .build()))
        }
        val previewFrame = android.widget.FrameLayout(context).apply {
            setBackgroundColor(0x33000000)
            addView(preview, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        content.addView(previewFrame, LinearLayout.LayoutParams(
            context.resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_setting_width), (90 * density).toInt()))
        fun refresh() {
            apply(subtitles)
            apply(preview)
            preview.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                18f * sizes[index("size", sizes.size, 1)].second)
        }
        var panel: android.widget.PopupWindow? = null
        val refreshLabels = mutableListOf<() -> Unit>()
        fun option(title: String, key: String, names: List<String>, default: Int = 0) {
            val row = android.view.LayoutInflater.from(context).inflate(
                androidx.media3.ui.R.layout.exo_styled_sub_settings_list_item, content, false)
            val button = row.findViewById<TextView>(androidx.media3.ui.R.id.exo_text)
            row.isFocusable = true
            val update = { button.text = "$title: ${names[index(key, names.size, default)]}" }
            refreshLabels.add(update)
            update()
            row.setOnClickListener {
                panel?.dismiss()
                showPlayerChoices(playerView, names, index(key, names.size, default)) { choice ->
                    prefs.edit().putInt(key, choice).putBoolean("customized", true).apply()
                    update(); refresh()
                    show(context, playerView)
                }
            }
            content.addView(row)
        }
        option("Text size", "size", sizes.map { it.first }, 1)
        option("Text colour", "color", colors.map { it.first })
        option("Background", "background", backgrounds.map { it.first }, 2)
        option("Text edge", "edge", edges.map { it.first })
        refresh()
        val reset = android.view.LayoutInflater.from(context).inflate(
            androidx.media3.ui.R.layout.exo_styled_sub_settings_list_item, content, false).apply {
            findViewById<TextView>(androidx.media3.ui.R.id.exo_text).text = "Reset to default"
            isFocusable = true
            setOnClickListener {
                prefs.edit().clear().apply()
                refreshLabels.forEach { it() }; refresh()
            }
        }
        content.addView(reset)
        panel = showPlayerPopup(playerView, android.widget.ScrollView(context).apply { addView(content) })
    }
}
