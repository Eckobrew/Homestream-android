package com.homestream.app

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import coil.load

/** Framed album and poster artwork with a matching placeholder. */
internal class LibraryArtwork(context: Context, private val poster: Boolean = false, placeholder: Int = R.drawable.nav_music) : FrameLayout(context) {
    private val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    init {
        background = rounded(0xFF202020.toInt(), radius = context.dp(12).toFloat())
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(ImageView(context).apply {
            setImageResource(placeholder)
            imageAlpha = 112
        }, LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER))
        addView(cover, LayoutParams(-1, -1))
    }
    fun show(url: String?) { cover.load(url?.takeIf { it.isNotBlank() }) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(if (poster) width * 3 / 2 else width, MeasureSpec.EXACTLY))
    }
}

internal data class ArtworkTile(
    val title: String, val subtitle: String, val cover: String?,
    val poster: Boolean = false, val placeholder: Int = R.drawable.nav_music,
    val click: () -> Unit
)

internal fun Context.artworkGrid(target: LinearLayout, tiles: List<ArtworkTile>) {
    val columns = if (isTelevision()) 4 else if (resources.configuration.screenWidthDp >= 560) 3 else 2
    tiles.chunked(columns).forEach { group ->
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        group.forEach { tile ->
            val card = column().apply {
                setPadding(dp(6), dp(6), dp(6), dp(10))
                focusableCard(0xFF181818.toInt())
                addView(LibraryArtwork(context, tile.poster, tile.placeholder).apply { show(tile.cover) }, LinearLayout.LayoutParams(-1, -2))
                addView(label(tile.title, 14f, true).apply {
                    setPadding(dp(4), dp(8), dp(4), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(label(tile.subtitle, 12f).apply {
                    setPadding(dp(4), dp(3), dp(4), 0); setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                setOnClickListener { tile.click() }
            }
            row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        repeat(columns - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        target.addView(row)
    }
}

internal fun Context.musicTrackRow(title: String, subtitle: String, number: Int, selected: Boolean = false, coverUrl: String? = null, download: (() -> Unit)? = null, click: () -> Unit) =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        focusableCard(if (selected) 0xFF302023.toInt() else 0xFF202020.toInt())
        if (!coverUrl.isNullOrBlank()) {
            addView(LibraryArtwork(context).apply { show(coverUrl) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        } else {
            addView(label(number.toString(), 13f, true).apply { setTextColor(if (selected) RED else MUTED) }, LinearLayout.LayoutParams(dp(34), -2))
        }
        addView(column().apply {
            addView(label(title, 15f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (subtitle.isNotBlank()) addView(label(subtitle, 12f).apply { setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_play_arrow); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        if (download != null) addView(musicDownloadIcon(title, download))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        setOnClickListener { click() }
    }


internal fun Context.musicDownloadIcon(title: String, download: () -> Unit) = android.widget.ImageButton(this).apply {
    setImageResource(R.drawable.ic_download)
    setColorFilter(MUTED)
    contentDescription = "Download $title"
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    setPadding(dp(12), dp(12), dp(12), dp(12))
    focusableCard(0x22FFFFFF)
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
    setOnClickListener { download() }
}
