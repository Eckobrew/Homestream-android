package com.homestream.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 * Single-page (1 page per screen) eBook reader for Android with Red HomeStream theme.
 * Supports EPUB (single-column CSS-paginated WebView) and PDF (native PdfRenderer).
 */
class BookReaderActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var api: HomeStreamApi

    private var bookId: String = ""
    private var bookTitle: String = ""
    private var bookAuthor: String = ""
    private var bookFormat: String = "epub"
    private var initialLocation: String = ""

    private lateinit var stage: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var progressFill: View

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
    private var fontSizePercent = 105

    private val RED = 0xFFE50914.toInt()
    private val INK = 0xFF0E0E0E.toInt()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this)
        api = HomeStreamApi(settings)

        bookId = intent.getStringExtra("bookId").orEmpty()
        bookTitle = intent.getStringExtra("title").orEmpty().ifBlank { "Book" }
        bookAuthor = intent.getStringExtra("author").orEmpty()
        bookFormat = intent.getStringExtra("format").orEmpty().lowercase().ifBlank { "epub" }
        initialLocation = intent.getStringExtra("location").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
        }

        // Top Header Bar (Red HomeStream accent)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF161616.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }
        header.addView(closeBtn)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = bookTitle
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        titleCol.addView(TextView(this).apply {
            text = "$bookAuthor • ${bookFormat.uppercase()} • 1 Page / Screen"
            textSize = 11f
            setTextColor(0xFFEF5350.toInt())
            maxLines = 1
        })
        header.addView(titleCol)

        if (bookFormat == "epub") {
            header.addView(topButton("A-") {
                fontSizePercent = (fontSizePercent - 10).coerceAtLeast(80)
                applyEpubPagination()
            })
            header.addView(topButton("A+") {
                fontSizePercent = (fontSizePercent + 10).coerceAtMost(160)
                applyEpubPagination()
            })
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Main 1-page viewport
        stage = FrameLayout(this).apply {
            setBackgroundColor(0xFF121212.toInt())
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom Bar with Prev / Progress / Next
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF161616.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val prevBtn = navButton("‹ Prev") { prevPage() }
        val nextBtn = navButton("Next ›") { nextPage() }

        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(dp(12), 0, dp(12), 0)
        }
        statusLabel = TextView(this).apply {
            text = "Loading…"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        centerCol.addView(statusLabel)

        val progressTrack = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF2A2A2A.toInt())
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(4) }
        }
        progressFill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(RED)
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(0, -1)
        }
        progressTrack.addView(progressFill)
        centerCol.addView(progressTrack)

        footer.addView(prevBtn)
        footer.addView(centerCol)
        footer.addView(nextBtn)
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        loadBook()
    }

    private fun topButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(0xFF262626.toInt())
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
        setOnClickListener { click() }
    }

    private fun navButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(RED)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { click() }
    }

    private fun loadBook() {
        stage.removeAllViews()
        stage.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(RED)
        }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))

        lifecycleScope.launch {
            try {
                // Fetch saved progress if not passed
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
                    text = "Unable to open book: ${e.message}"
                    textSize = 13f
                    setTextColor(0xFFFF8A80.toInt())
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
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(vx) > 150) {
                    if (dx < 0) nextPage() else prevPage()
                    return true
                }
                return false
            }
        })
        img.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }

        pdfImageView = img
        stage.removeAllViews()
        stage.addView(img, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
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
    // EPUB 1-Page Renderer (Single-column CSS paginated WebView)
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
            setBackgroundColor(0xFF141414.toInt())
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

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) > dp(48) && kotlin.math.abs(vx) > 150) {
                    if (dx < 0) nextPage() else prevPage()
                    return true
                }
                return false
            }
        })
        wv.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }

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
                var h = window.innerHeight || d.documentElement.clientHeight;
                var w = window.innerWidth || d.documentElement.clientWidth;
                var style = d.getElementById('hs-reader-style');
                if (!style) {
                    style = d.createElement('style');
                    style.id = 'hs-reader-style';
                    d.head.appendChild(style);
                }
                style.textContent =
                    'html, body { margin: 0 !important; padding: 0 !important; background: #141414 !important; color: #ececec !important; ' +
                    'height: ' + h + 'px !important; overflow: hidden !important; font-family: Georgia, serif !important; ' +
                    'font-size: ${fontSizePercent}% !important; line-height: 1.65 !important; } ' +
                    'body { column-width: ' + w + 'px !important; column-gap: 0px !important; column-fill: auto !important; ' +
                    'box-sizing: border-box !important; padding: 20px 22px !important; } ' +
                    'img, svg, video { max-width: 100% !important; max-height: ' + (h - 48) + 'px !important; height: auto !important; } ' +
                    'a { color: #f87171 !important; }';
                var total = Math.max(1, Math.round(d.body.scrollWidth / Math.max(1, w)));
                var page = Math.min(total - 1, Math.max(0, $epubPageInChapter));
                window.scrollTo(page * w, 0);
                return page + '/' + total;
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw ->
            val cleaned = raw?.trim('"').orEmpty()
            val parts = cleaned.split("/")
            epubPageInChapter = parts.getOrNull(0)?.toIntOrNull() ?: 0
            epubPagesInChapter = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceAtLeast(1)
            updateEpubStatus()
        }
    }

    private fun scrollEpubToPage() {
        val wv = webView ?: return
        wv.evaluateJavascript(
            "(function(){ var w = window.innerWidth || 1; window.scrollTo(${epubPageInChapter} * w, 0); })();",
            null
        )
        updateEpubStatus()
    }

    private fun updateEpubStatus() {
        val totalChapters = epubSpine.size.coerceAtLeast(1)
        val frac = (epubChapterIndex.toDouble() + (epubPageInChapter + 1).toDouble() / epubPagesInChapter.toDouble()) / totalChapters.toDouble()
        val pct = (frac * 100.0).coerceIn(0.0, 100.0)
        updateProgressUi(
            "Chapter ${epubChapterIndex + 1}/$totalChapters • Page ${epubPageInChapter + 1}/$epubPagesInChapter",
            pct
        )
        saveProgress("ch:$epubChapterIndex:$epubPageInChapter", pct)
    }

    private fun prevPage() {
        if (bookFormat == "pdf") {
            if (pdfPageIndex > 0) {
                pdfPageIndex--
                renderPdfSinglePage()
            }
        } else {
            if (epubPageInChapter > 0) {
                epubPageInChapter--
                scrollEpubToPage()
            } else if (epubChapterIndex > 0) {
                epubChapterIndex--
                epubPageInChapter = 9999 // snap to last page of previous chapter
                loadEpubChapter()
            }
        }
    }

    private fun nextPage() {
        if (bookFormat == "pdf") {
            if (pdfPageIndex + 1 < pdfPageCount) {
                pdfPageIndex++
                renderPdfSinglePage()
            }
        } else {
            if (epubPageInChapter + 1 < epubPagesInChapter) {
                epubPageInChapter++
                scrollEpubToPage()
            } else if (epubChapterIndex + 1 < epubSpine.size) {
                epubChapterIndex++
                epubPageInChapter = 0
                loadEpubChapter()
            }
        }
    }

    private fun updateProgressUi(label: String, pct: Double) {
        statusLabel.text = "$label (${pct.toInt()}%)"
        progressFill.post {
            val parentW = (progressFill.parent as? View)?.width ?: 0
            progressFill.layoutParams = FrameLayout.LayoutParams(
                ((parentW * (pct / 100.0)).toInt()).coerceIn(0, parentW),
                -1
            )
        }
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
