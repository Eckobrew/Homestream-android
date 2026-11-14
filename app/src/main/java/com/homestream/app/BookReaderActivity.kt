package com.homestream.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Physical book-styled 1-page reader for Android with cream paper, serif typography,
 * 3D page-turning animation, edge-tap / swipe navigation, and zero button clutter.
 */
class BookReaderActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var api: HomeStreamApi

    private var bookId: String = ""
    private var bookTitle: String = ""
    private var bookAuthor: String = ""
    private var bookFormat: String = "epub"
    private var initialLocation: String = ""

    private lateinit var pageSheet: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var curlOverlay: View
    private lateinit var folioLeftLabel: TextView
    private lateinit var folioRightLabel: TextView
    private var isAnimatingTurn = false

    // PDF single-page state
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfImageView: ImageView? = null
    private var pdfPageIndex = 0
    private var pdfPageCount = 0

    // EPUB single-page state
    private var epubDir: File? = null
    private var epubRootDir: String = ""
    private var epubSpine: List<String> = emptyList()
    private var epubChapterIndex = 0
    private var epubPageInChapter = 0
    private var epubPagesInChapter = 1
    private var webView: WebView? = null
    private var fontSizePercent = 108

    private val LEATHER_BG = 0xFF1A110B.toInt()
    private val PAPER_CREAM = 0xFFF7F1E3.toInt()
    private val PAPER_EDGE = 0xFFDFD3BE.toInt()
    private val INK_BROWN = 0xFF231C14.toInt()
    private val MUTED_SERIF = 0xFF7B6A55.toInt()
    private lateinit var pageGestureDetector: GestureDetector
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this)
        api = HomeStreamApi(settings)
        pageGestureDetector = buildPageGestureDetector()

        bookId = intent.getStringExtra("bookId").orEmpty()
        bookTitle = intent.getStringExtra("title").orEmpty().ifBlank { "Book" }
        bookAuthor = intent.getStringExtra("author").orEmpty()
        bookFormat = intent.getStringExtra("format").orEmpty().lowercase().ifBlank { "epub" }
        initialLocation = intent.getStringExtra("location").orEmpty()

        // Outer mahogany leather cover with gold embossed border
        val outerBinding = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF42281A.toInt(), 0xFF291810.toInt(), 0xFF362015.toInt())
            )
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val leatherCoverFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF4A2C1D.toInt(), 0xFF2B1810.toInt(), 0xFF3D2316.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), 0xFFB8925A.toInt()) // Gold-leaf embossed cover trim
            }
            setPadding(dp(8), dp(8), dp(12), dp(10))
        }

        // Stacked paper block (visible paper stack thickness on right and bottom edges)
        val paperStack = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(0xFF9C8468.toInt(), PAPER_EDGE, 0xFFCDB99D.toInt(), 0xFFE6DBC8.toInt())
                cornerRadius = dp(9).toFloat()
                setStroke(dp(2), 0xFF6B4E35.toInt())
            }
            setPadding(dp(4), dp(3), dp(9), dp(7))
        }

        // Actual turning book page sheet with uniform cream paper
        pageSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(PAPER_CREAM)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), 0xFFC8B89E.toInt())
            }
            elevation = dp(10).toFloat()
            cameraDistance = 16000f * resources.displayMetrics.density
        }

        // Running book header inside top paper margin with decorative rule
        val runningHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(6))
        }
        runningHeader.addView(TextView(this).apply {
            text = bookAuthor.ifBlank { bookTitle }.uppercase()
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setTextColor(MUTED_SERIF)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        runningHeader.addView(TextView(this).apply {
            text = bookTitle
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(MUTED_SERIF)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        pageSheet.addView(runningHeader, LinearLayout.LayoutParams(-1, -2))
        // Subtle ink rule under running header
        pageSheet.addView(View(this).apply {
            setBackgroundColor(0x337B6A55)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(20), 0, dp(20), dp(4)) })

        // Main 1-page viewport
        stage = FrameLayout(this)
        stage.setPadding(dp(12), dp(2), dp(12), dp(2))
        stage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                webView?.post { applyEpubPagination() }
            }
        }
        pageSheet.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // Subtle ink rule above running footer
        pageSheet.addView(View(this).apply {
            setBackgroundColor(0x337B6A55)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(20), dp(4), dp(20), 0) })

        // Printed book folio footer inside bottom paper margin
        val runningFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(12))
        }
        folioLeftLabel = TextView(this).apply {
            text = ""
            textSize = 11f
            typeface = Typeface.SERIF
            setTextColor(MUTED_SERIF)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        folioRightLabel = TextView(this).apply {
            text = ""
            textSize = 11f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            setTextColor(MUTED_SERIF)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        runningFooter.addView(folioLeftLabel)
        runningFooter.addView(folioRightLabel)
        pageSheet.addView(runningFooter, LinearLayout.LayoutParams(-1, -2))

        // Classic iBooks-style curling page fold & shadow overlay
        curlOverlay = View(this).apply {
            visibility = View.GONE
            elevation = dp(16).toFloat()
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    0x00000000,
                    0x441F130B,
                    0xFFEFE5D0.toInt(),
                    0xFFFDF9F0.toInt(),
                    0xFFE3D3B8.toInt(),
                    0x381A1008,
                    0x00000000
                )
            )
        }

        paperStack.addView(pageSheet, FrameLayout.LayoutParams(-1, -1))
        paperStack.addView(curlOverlay, FrameLayout.LayoutParams(-1, -1))
        leatherCoverFrame.addView(paperStack, FrameLayout.LayoutParams(-1, -1))
        outerBinding.addView(leatherCoverFrame, FrameLayout.LayoutParams(-1, -1))

        ViewCompat.setOnApplyWindowInsetsListener(outerBinding) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + dp(12), bars.top + dp(10), bars.right + dp(12), bars.bottom + dp(10))
            insets
        }
        setContentView(outerBinding)
        loadBook()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pageGestureDetector.onTouchEvent(ev)) {
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - touchDownX
                val dy = ev.rawY - touchDownY
                val dt = System.currentTimeMillis() - touchDownTime
                val w = resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080
                if (kotlin.math.abs(dx) > dp(36) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f && dt < 700) {
                    if (dx < 0) nextPage() else prevPage()
                    return true
                } else if (kotlin.math.abs(dx) < dp(14) && kotlin.math.abs(dy) < dp(14) && dt < 350) {
                    if (ev.rawX < w * 0.35f) {
                        prevPage()
                        return true
                    } else if (ev.rawX > w * 0.65f) {
                        nextPage()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                nextPage()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> {
                prevPage()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun animatePageTurn(forward: Boolean, onMidTurn: () -> Unit) {
        if (isAnimatingTurn) return
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            onMidTurn()
            return
        }
        isAnimatingTurn = true
        val width = pageSheet.width.takeIf { it > 0 }?.toFloat() ?: 1080f

        // Classic iBooks curling fold sweeping across the page from edge to spine
        curlOverlay.visibility = View.VISIBLE
        curlOverlay.alpha = 0f
        curlOverlay.scaleX = 0.55f
        curlOverlay.rotation = if (forward) -4f else 4f
        curlOverlay.translationX = if (forward) width * 0.65f else -width * 0.65f

        pageSheet.pivotX = 0f
        pageSheet.pivotY = pageSheet.height / 2f

        curlOverlay.animate()
            .translationX(if (forward) -width * 0.7f else width * 0.7f)
            .alpha(1f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                curlOverlay.visibility = View.GONE
            }
            .start()

        pageSheet.animate()
            .rotationY(if (forward) -22f else 18f)
            .scaleX(0.985f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                onMidTurn()
                pageSheet.rotationY = if (forward) 14f else -14f
                pageSheet.animate()
                    .rotationY(0f)
                    .scaleX(1f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        isAnimatingTurn = false
                    }
                    .start()
            }
            .start()
    }

    private fun buildPageGestureDetector(): GestureDetector {
        return GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = stage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                if (e.x < w * 0.30f) {
                    prevPage()
                    return true
                } else if (e.x > w * 0.70f) {
                    nextPage()
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > dp(42) && kotlin.math.abs(vx) > 120) {
                    if (dx < 0) nextPage() else prevPage()
                    return true
                }
                return false
            }
        })
    }

    private fun loadBook() {
        stage.removeAllViews()
        stage.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF8C3B2B.toInt())
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))

        lifecycleScope.launch {
            try {
                if (initialLocation.isBlank()) {
                    runCatching {
                        val prog = api.call("books/progress?bookId=$bookId").optJSONObject("progress")
                        if (prog != null) initialLocation = prog.optString("location")
                    }
                }
                val file = withContext(Dispatchers.IO) { downloadBookToCache() }
                if (bookFormat == "pdf") {
                    openPdf(file)
                } else {
                    openEpub(file)
                }
            } catch (e: Exception) {
                stage.removeAllViews()
                stage.addView(TextView(this@BookReaderActivity).apply {
                    text = "Unable to open volume: ${e.message}"
                    textSize = 13f
                    typeface = Typeface.SERIF
                    setTextColor(INK_BROWN)
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    private fun downloadBookToCache(): File {
        val cacheDir = File(cacheDir, "books_cache").apply { mkdirs() }
        val target = File(cacheDir, "book_${bookId}.${bookFormat}")
        if (target.exists() && target.length() > 128) return target

        val url = api.absolute("books/file?bookId=$bookId&format=$bookFormat")
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).apply {
            if (settings.token.isNotBlank()) header("Authorization", "Bearer ${settings.token}")
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty file response")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        return target
    }

    // -------------------------------------------------------------------------
    // PDF 1-Page Renderer
    // -------------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    private fun openPdf(file: File) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        pdfDescriptor = pfd
        pdfRenderer = renderer
        pdfPageCount = renderer.pageCount.coerceAtLeast(1)
        pdfPageIndex = (initialLocation.toIntOrNull()?.minus(1) ?: 0).coerceIn(0, pdfPageCount - 1)

        val img = ImageView(this).apply {
            colorFilter = LightingColorFilter(PAPER_CREAM, 0)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(PAPER_CREAM)
        }

        pdfImageView = img
        stage.removeAllViews()
        stage.addView(img, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(8), dp(4), dp(8), dp(4))
        })
        renderPdfSinglePage()
    }

    private fun renderPdfSinglePage() {
        val renderer = pdfRenderer ?: return
        val img = pdfImageView ?: return
        if (pdfPageIndex !in 0 until pdfPageCount) return

        renderer.openPage(pdfPageIndex).use { page ->
            val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(480)
            val scale = (screenW.toFloat() / page.width.coerceAtLeast(1)).coerceIn(1f, 2.5f)
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            img.setImageBitmap(bmp)
        }
        val pct = ((pdfPageIndex + 1).toDouble() / pdfPageCount.toDouble()) * 100.0
        updateProgressUi("Page ${pdfPageIndex + 1} of $pdfPageCount", pct)
        saveProgress(String.format("%d", pdfPageIndex + 1), pct)
    }

    // -------------------------------------------------------------------------
    // EPUB 1-Page Renderer (Single-column CSS paginated WebView on cream paper)
    // -------------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private suspend fun openEpub(file: File) {
        val extractedDir = File(cacheDir, "epub_unzipped_$bookId")
        val spine = withContext(Dispatchers.IO) { extractAndParseEpub(file, extractedDir) }
        if (spine.isEmpty()) error("No readable chapters found in EPUB")

        epubDir = extractedDir
        epubSpine = spine

        val parts = initialLocation.removePrefix("ch:").split(":")
        epubChapterIndex = (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, spine.size - 1)
        epubPageInChapter = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceAtLeast(0)

        val wv = WebView(this).apply {
            setBackgroundColor(PAPER_CREAM)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val u = request.url ?: return null
                    if (u.host != "epub.local") return null
                    val rel = u.path?.removePrefix("/") ?: return null
                    val target = File(extractedDir, rel).canonicalFile
                    if (!target.path.startsWith(extractedDir.canonicalPath) || !target.isFile) {
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    val mime = when (target.extension.lowercase()) {
                        "xhtml", "html", "htm" -> "application/xhtml+xml"
                        "css" -> "text/css"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "svg" -> "image/svg+xml"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "ttf" -> "font/ttf"
                        "otf" -> "font/otf"
                        "woff" -> "font/woff"
                        "woff2" -> "font/woff2"
                        else -> "application/octet-stream"
                    }
                    return WebResourceResponse(mime, "utf-8", target.inputStream())
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    applyEpubPagination()
                }
            }
        }

        webView = wv
        stage.removeAllViews()
        stage.addView(wv, FrameLayout.LayoutParams(-1, -1))
        loadEpubChapter()
    }

    private fun extractAndParseEpub(epubFile: File, destDir: File): List<String> {
        if (!destDir.exists()) {
            destDir.mkdirs()
            ZipFile(epubFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val outFile = File(destDir, entry.name).canonicalFile
                    if (!outFile.path.startsWith(destDir.canonicalPath)) continue
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }

        val containerXml = File(destDir, "META-INF/container.xml")
        var opfRelPath = "OEBPS/content.opf"
        if (containerXml.exists()) {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(containerXml.readText()))
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val fp = parser.getAttributeValue(null, "full-path")
                    if (!fp.isNullOrBlank()) {
                        opfRelPath = fp
                        break
                    }
                }
                ev = parser.next()
            }
        }

        val opfFile = File(destDir, opfRelPath)
        if (!opfFile.exists()) return emptyList()
        val opfDir = opfFile.parentFile?.relativeTo(destDir)?.path.orEmpty()
        epubRootDir = opfDir

        val manifest = mutableMapOf<String, String>()
        val spineIds = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(opfFile.readText()))
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name.substringAfterLast(':')) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) manifest[id] = href
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (!idref.isNullOrBlank()) spineIds.add(idref)
                    }
                }
            }
            ev = parser.next()
        }

        return spineIds.mapNotNull { id ->
            val href = manifest[id] ?: return@mapNotNull null
            if (opfDir.isBlank()) href else "$opfDir/$href"
        }
    }

    private fun loadEpubChapter() {
        val wv = webView ?: return
        val rel = epubSpine.getOrNull(epubChapterIndex) ?: return
        wv.loadUrl("https://epub.local/$rel")
    }

    private fun applyEpubPagination() {
        val wv = webView ?: return
        val js = """
            (function() {
                var d = document;
                var viewport = d.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = d.createElement('meta');
                    viewport.name = 'viewport';
                    d.head.appendChild(viewport);
                }
                viewport.content = 'width=device-width, initial-scale=1';
                var h = d.documentElement.clientHeight;
                var w = d.documentElement.clientWidth;
                if (!w || !h) return '';
                var margin = Math.min(18, Math.floor(w / 12));
                var lineWidth = w - margin * 2;
                var style = d.getElementById('hs-reader-style');
                if (!style) {
                    style = d.createElement('style');
                    style.id = 'hs-reader-style';
                    d.head.appendChild(style);
                }
                style.textContent =
                    'html, body { margin: 0 !important; padding: 0 !important; background: #f7f1e3 !important; color: #231c14 !important; ' +
                    'height: ' + h + 'px !important; overflow: hidden !important; font-family: Georgia, "Noto Serif", serif !important; ' +
                    'font-size: ${fontSizePercent}% !important; line-height: 1.72 !important; text-align: justify !important; } ' +
                    'body { background: #f7f1e3 !important; column-width: ' + lineWidth + 'px !important; column-gap: ' + (margin * 2) + 'px !important; column-fill: auto !important; ' +
                    'box-sizing: border-box !important; padding: 12px ' + margin + 'px !important; overflow-wrap: anywhere; } ' +
                    'p { text-indent: 1.35em !important; margin-top: 0 !important; margin-bottom: 0.4em !important; } ' +
                    'h1, h2, h3, h4 { color: #1b140e !important; text-align: center !important; } ' +
                    'img, svg, video { max-width: 100% !important; max-height: ' + Math.max(1, h - 32) + 'px !important; height: auto !important; } ' +
                    'a { color: #7c2d12 !important; text-decoration: none !important; }';
                var total = Math.max(1, Math.ceil(d.body.scrollWidth / w));
                var page = Math.min(total - 1, Math.max(0, $epubPageInChapter));
                window.scrollTo(page * w, 0);
                return page + '/' + total;
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw ->
            val cleaned = raw?.trim('"').orEmpty()
            if (!cleaned.contains('/')) return@evaluateJavascript
            val parts = cleaned.split("/")
            epubPageInChapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
            epubPagesInChapter = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceAtLeast(1)
            updateEpubStatus()
        }
    }

    private fun scrollEpubToPage() {
        val wv = webView ?: return
        wv.evaluateJavascript(
            "(function(){ var w = document.documentElement.clientWidth || 1; window.scrollTo(${epubPageInChapter} * w, 0); })();",
            null
        )
        updateEpubStatus()
    }

    private fun updateEpubStatus() {
        val totalChapters = epubSpine.size.coerceAtLeast(1)
        val frac = (epubChapterIndex.toDouble() + (epubPageInChapter + 1).toDouble() / epubPagesInChapter.toDouble()) / totalChapters.toDouble()
        val pct = (frac * 100.0).coerceIn(0.0, 100.0)
        updateProgressUi(
            "Ch. ${epubChapterIndex + 1} · Page ${epubPageInChapter + 1} of $epubPagesInChapter",
            pct
        )
        saveProgress("ch:$epubChapterIndex:$epubPageInChapter", pct)
    }

    private fun prevPage() {
        if (bookFormat == "pdf") {
            if (pdfPageIndex > 0) {
                animatePageTurn(forward = false) {
                    pdfPageIndex--
                    renderPdfSinglePage()
                }
            }
        } else {
            if (epubPageInChapter > 0) {
                animatePageTurn(forward = false) {
                    epubPageInChapter--
                    scrollEpubToPage()
                }
            } else if (epubChapterIndex > 0) {
                animatePageTurn(forward = false) {
                    epubChapterIndex--
                    epubPageInChapter = 9999
                    loadEpubChapter()
                }
            }
        }
    }

    private fun nextPage() {
        if (bookFormat == "pdf") {
            if (pdfPageIndex + 1 < pdfPageCount) {
                animatePageTurn(forward = true) {
                    pdfPageIndex++
                    renderPdfSinglePage()
                }
            }
        } else {
            if (epubPageInChapter + 1 < epubPagesInChapter) {
                animatePageTurn(forward = true) {
                    epubPageInChapter++
                    scrollEpubToPage()
                }
            } else if (epubChapterIndex + 1 < epubSpine.size) {
                animatePageTurn(forward = true) {
                    epubChapterIndex++
                    epubPageInChapter = 0
                    loadEpubChapter()
                }
            }
        }
    }

    private fun updateProgressUi(label: String, pct: Double) {
        folioLeftLabel.text = label
        folioRightLabel.text = "${pct.toInt()}%"
    }

    private fun saveProgress(location: String, pct: Double) {
        if (bookId.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                api.call(
                    "books/progress",
                    body = JSONObject()
                        .put("bookId", bookId)
                        .put("location", location)
                        .put("percent", pct),
                    method = "POST"
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { webView?.destroy() }
        runCatching { pdfRenderer?.close() }
        runCatching { pdfDescriptor?.close() }
    }
}
