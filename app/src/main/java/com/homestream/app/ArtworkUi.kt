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

/** Framed album, artist, and poster artwork with a matching placeholder. */
internal class LibraryArtwork(
    context: Context,
    private val poster: Boolean = false,
    placeholder: Int = R.drawable.nav_music,
    private val circular: Boolean = false
) : FrameLayout(context) {
    private val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    init {
        val cornerRadius = if (circular) context.dp(999).toFloat() else context.dp(if (poster) 6 else 8).toFloat()
        background = rounded(SPOTIFY_ELEVATED, radius = cornerRadius)
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
    val circular: Boolean = false,
    val click: () -> Unit
)

internal fun Context.artworkGrid(target: LinearLayout, tiles: List<ArtworkTile>) {
    val columns = if (isTelevision()) 4 else if (resources.configuration.screenWidthDp >= 560) 3 else 2
    tiles.chunked(columns).forEach { group ->
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        group.forEach { tile ->
            val card = column().apply {
                setPadding(dp(4), dp(4), dp(4), dp(12))
                focusableCard(Color.TRANSPARENT)
                addView(LibraryArtwork(context, tile.poster, tile.placeholder, tile.circular).apply { show(tile.cover) }, LinearLayout.LayoutParams(-1, -2))
                addView(label(tile.title, 14f, true).apply {
                    if (tile.circular) gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(8), dp(2), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                if (tile.subtitle.isNotBlank()) {
                    addView(label(tile.subtitle, 12f).apply {
                        if (tile.circular) gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(2), dp(2), dp(2), 0); setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                }
                setOnClickListener { tile.click() }
            }
            row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(6)) })
        }
        repeat(columns - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        target.addView(row)
    }
}

internal fun Context.musicTrackRow(title: String, subtitle: String, number: Int, selected: Boolean = false, coverUrl: String? = null, download: (() -> Unit)? = null, click: () -> Unit) =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        focusableCard(if (selected) 0xFF1F2B23.toInt() else SPOTIFY_SURFACE)
        if (!coverUrl.isNullOrBlank()) {
            addView(LibraryArtwork(context).apply { show(coverUrl) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) })
        } else {
            addView(label(number.toString(), 13f, true).apply {
                setTextColor(if (selected) SPOTIFY_GREEN else MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(30), -2).apply { rightMargin = dp(8) })
        }
        addView(column().apply {
            addView(label(title, 15f, true).apply {
                setTextColor(if (selected) SPOTIFY_GREEN else Color.WHITE)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (subtitle.isNotBlank()) addView(label(subtitle, 12f).apply { setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_play_arrow); setColorFilter(SPOTIFY_GREEN)
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        }
        if (download != null) addView(musicDownloadIcon(title, download))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
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
