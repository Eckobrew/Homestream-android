package com.homestream.app

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
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
    private lateinit var curlView: InteractivePageCurlView
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
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var isDraggingCurl = false
    private var dragForward = true
    private var velocityTracker: VelocityTracker? = null

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

        // Google Play Books / iBooks interactive finger-tracked page curl canvas
        curlView = InteractivePageCurlView(this).apply {
            visibility = View.GONE
            elevation = dp(18).toFloat()
        }

        paperStack.addView(pageSheet, FrameLayout.LayoutParams(-1, -1))
        paperStack.addView(curlView, FrameLayout.LayoutParams(-1, -1))
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

    private fun canTurnNext(): Boolean =
        if (bookFormat == "pdf") pdfPageIndex + 1 < pdfPageCount
        else epubPageInChapter + 1 < epubPagesInChapter || epubChapterIndex + 1 < epubSpine.size

    private fun canTurnPrev(): Boolean =
        if (bookFormat == "pdf") pdfPageIndex > 0
        else epubPageInChapter > 0 || epubChapterIndex > 0

    private fun advancePageImmediate(forward: Boolean) {
        if (bookFormat == "pdf") {
            if (forward && pdfPageIndex + 1 < pdfPageCount) {
                pdfPageIndex++
                renderPdfSinglePage()
            } else if (!forward && pdfPageIndex > 0) {
                pdfPageIndex--
                renderPdfSinglePage()
            }
        } else {
            if (forward) {
                if (epubPageInChapter + 1 < epubPagesInChapter) {
                    epubPageInChapter++
                    scrollEpubToPage()
                } else if (epubChapterIndex + 1 < epubSpine.size) {
                    epubChapterIndex++
                    epubPageInChapter = 0
                    loadEpubChapter()
                }
            } else {
                if (epubPageInChapter > 0) {
                    epubPageInChapter--
                    scrollEpubToPage()
                } else if (epubChapterIndex > 0) {
                    epubChapterIndex--
                    epubPageInChapter = 9999
                    loadEpubChapter()
                }
            }
        }
    }

    private fun captureSheetBitmap(): Bitmap? {
        val w = pageSheet.width
        val h = pageSheet.height
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            pageSheet.draw(canvas)
            bmp
        }.getOrNull()
    }

    private fun screenXToProgress(rawX: Float, forward: Boolean): Float {
        val loc = IntArray(2)
        pageSheet.getLocationOnScreen(loc)
        val w = pageSheet.width.coerceAtLeast(1).toFloat()
        val localX = (rawX - loc[0]).coerceIn(0f, w)
        return if (forward) ((w - localX) / w).coerceIn(0.01f, 0.99f)
        else (localX / w).coerceIn(0.01f, 0.99f)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isAnimatingTurn) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
                isDraggingCurl = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.rawX - touchDownX
                val dy = ev.rawY - touchDownY
                if (!isDraggingCurl) {
                    if (kotlin.math.abs(dx) > dp(10) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        val wantForward = dx < 0
                        if ((wantForward && canTurnNext()) || (!wantForward && canTurnPrev())) {
                            val snapshot = captureSheetBitmap()
                            if (snapshot != null) {
                                isDraggingCurl = true
                                dragForward = wantForward
                                if (dragForward) {
                                    // Advance underlying page now so curling back top page reveals the next page underneath!
                                    advancePageImmediate(true)
                                }
                                curlView.startInteractiveCurl(snapshot, dragForward, screenXToProgress(ev.rawX, dragForward))
                            }
                        }
                    }
                } else {
                    curlView.updateCurlProgress(screenXToProgress(ev.rawX, dragForward))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(ev)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                if (isDraggingCurl) {
                    isDraggingCurl = false
                    val progress = curlView.curlProgress
                    val commit = if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        false
                    } else if (dragForward) {
                        progress > 0.22f || vx < -350f
                    } else {
                        progress > 0.22f || vx > 350f
                    }
                    isAnimatingTurn = true
                    if (dragForward) {
                        curlView.SettleCurl(targetProgress = if (commit) 1f else 0f) {
                            if (!commit) {
                                // User cancelled turning forward -> restore previous page
                                advancePageImmediate(false)
                            }
                            isAnimatingTurn = false
                        }
                    } else {
                        if (commit) {
                            // Load previous page underneath as the curled sheet settles flat
                            advancePageImmediate(false)
                        }
                        curlView.SettleCurl(targetProgress = if (commit) 1f else 0f) {
                            isAnimatingTurn = false
                        }
                    }
                    return true
                }

                val dx = ev.rawX - touchDownX
                val dy = ev.rawY - touchDownY
                val dt = System.currentTimeMillis() - touchDownTime
                val w = resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080
                if (kotlin.math.abs(dx) < dp(14) && kotlin.math.abs(dy) < dp(14) && dt < 380) {
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
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onMidTurn()
            return
        }
        val snapshot = captureSheetBitmap()
        if (snapshot == null) {
            onMidTurn()
            return
        }
        isAnimatingTurn = true
        onMidTurn()
        curlView.startInteractiveCurl(snapshot, forward, if (forward) 0.02f else 0.02f)
        curlView.SettleCurl(targetProgress = 1f, durationMs = 420L) {
            isAnimatingTurn = false
        }
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
                    'html { margin: 0 !important; padding: 0 !important; width: ' + w + 'px !important; height: ' + h + 'px !important; overflow: hidden !important; background: #f7f1e3 !important; } ' +
                    'body { margin: 0 !important; padding: 12px ' + margin + 'px !important; width: ' + w + 'px !important; height: ' + h + 'px !important; ' +
                    'background: #f7f1e3 !important; color: #231c14 !important; font-family: Georgia, "Noto Serif", serif !important; ' +
                    'font-size: ${fontSizePercent}% !important; line-height: 1.72 !important; text-align: justify !important; ' +
                    'column-width: ' + lineWidth + 'px !important; column-gap: ' + (margin * 2) + 'px !important; column-fill: auto !important; ' +
                    'box-sizing: border-box !important; overflow-wrap: anywhere; transition: none !important; will-change: transform; } ' +
                    'p { text-indent: 1.35em !important; margin-top: 0 !important; margin-bottom: 0.4em !important; } ' +
                    'h1, h2, h3, h4 { color: #1b140e !important; text-align: center !important; } ' +
                    'img, svg, video { max-width: 100% !important; max-height: ' + Math.max(1, h - 32) + 'px !important; height: auto !important; } ' +
                    'a { color: #7c2d12 !important; text-decoration: none !important; }';
                d.body.style.transform = 'translateX(0px)';
                var total = Math.max(1, Math.ceil((d.body.scrollWidth - 4) / w));
                var page = Math.min(total - 1, Math.max(0, $epubPageInChapter));
                d.body.style.transform = 'translateX(-' + (page * w) + 'px)';
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
            "(function(){ var w = document.documentElement.clientWidth || 1; document.body.style.transform = 'translateX(-' + (${epubPageInChapter} * w) + 'px)'; })();",
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
        velocityTracker?.recycle()
        velocityTracker = null
        curlView.clearBitmap()
        runCatching { webView?.destroy() }
        runCatching { pdfRenderer?.close() }
        runCatching { pdfDescriptor?.close() }
    }
}

/**
 * Interactive Google Play Books / iBooks page-curl overlay view that tracks the user's
 * finger position in real time and renders the peeled page, the mirrored back-of-page curl,
 * and cylindrical crease / cast shadows over the revealed page underneath.
 */
class InteractivePageCurlView(context: Context) : View(context) {
    private var snapshot: Bitmap? = null
    private var isForward: Boolean = true
    var curlProgress: Float = 0f
        private set
    private var animator: ValueAnimator? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD8EFE4CE.toInt()
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flipMatrix = Matrix()
    private val clipPath = Path()

    fun startInteractiveCurl(bmp: Bitmap, forward: Boolean, initialProgress: Float) {
        animator?.cancel()
        snapshot?.recycle()
        snapshot = bmp
        isForward = forward
        curlProgress = initialProgress.coerceIn(0.01f, 0.99f)
        visibility = VISIBLE
        invalidate()
    }

    fun updateCurlProgress(progress: Float) {
        curlProgress = progress.coerceIn(0.01f, 0.99f)
        invalidate()
    }

    fun SettleCurl(targetProgress: Float, durationMs: Long = 280L, onFinished: () -> Unit) {
        animator?.cancel()
        val start = curlProgress
        val dist = kotlin.math.abs(targetProgress - start)
        val dur = (durationMs * dist.coerceIn(0.25f, 1f)).toLong()
        animator = ValueAnimator.ofFloat(start, targetProgress).apply {
            duration = dur
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                curlProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    clearBitmap()
                    onFinished()
                }
            })
            start()
        }
    }

    fun clearBitmap() {
        animator?.cancel()
        animator = null
        snapshot?.recycle()
        snapshot = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = snapshot ?: return
        if (bmp.isRecycled) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // For forward turn: fold moves from right (w) to left (0) as progress goes 0 -> 1.
        // For backward turn: we are pulling the previous page from left (0) to right (w) as progress goes 0 -> 1.
        val foldX = if (isForward) {
            (w * (1f - curlProgress)).coerceIn(0f, w)
        } else {
            (w * (1f - curlProgress)).coerceIn(0f, w)
        }

        if (isForward) {
            // 1. Draw the unpeeled left portion [0 .. foldX] of the old page on top of the new page
            canvas.save()
            canvas.clipRect(0f, 0f, foldX, h)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()

            // 2. Cast shadow onto the revealed new page to the right of foldX
            val shadowW = (w * 0.14f).coerceAtMost(w - foldX)
            if (shadowW > 1f) {
                shadowPaint.shader = LinearGradient(
                    foldX, 0f, foldX + shadowW, 0f,
                    intArrayOf(0x66160D06, 0x22160D06, 0x00000000),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(foldX, 0f, foldX + shadowW, h, shadowPaint)
            }

            // 3. Draw the curled back-of-page flap to the left of foldX
            val flapW = ((w - foldX) * 0.48f).coerceAtMost(foldX)
            if (flapW > 2f) {
                val flapLeft = foldX - flapW
                canvas.save()
                clipPath.reset()
                clipPath.addRect(flapLeft, 0f, foldX, h, Path.Direction.CW)
                canvas.clipPath(clipPath)

                // Mirror the bitmap horizontally around foldX to show text through the back of the paper
                flipMatrix.reset()
                flipMatrix.setScale(-1f, 1f)
                flipMatrix.postTranslate(2f * foldX - (w - foldX - flapW), 0f)
                bitmapPaint.alpha = 70
                canvas.drawBitmap(bmp, flipMatrix, bitmapPaint)
                bitmapPaint.alpha = 255

                // Warm cream paper back-side tint
                canvas.drawRect(flapLeft, 0f, foldX, h, backTintPaint)

                // 3D cylindrical curl highlight & crease shading across the flap
                shadowPaint.shader = LinearGradient(
                    flapLeft, 0f, foldX, 0f,
                    intArrayOf(0x3823150B, 0x00FFFFFF, 0x66FFFFFF, 0x2823150B),
                    floatArrayOf(0f, 0.25f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(flapLeft, 0f, foldX, h, shadowPaint)
                canvas.restore()

                // 4. Soft drop shadow to the left of the curling flap back edge
                val backShadowW = (flapW * 0.35f).coerceAtMost(flapLeft)
                if (backShadowW > 1f) {
                    shadowPaint.shader = LinearGradient(
                        flapLeft - backShadowW, 0f, flapLeft, 0f,
                        intArrayOf(0x00000000, 0x44160D06),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(flapLeft - backShadowW, 0f, flapLeft, h, shadowPaint)
                }
            }
        } else {
            // Backward turn: pulling the page from left to right across the current page
            val pullX = (w * curlProgress).coerceIn(0f, w)
            val flapW = (pullX * 0.45f).coerceAtMost(w - pullX)

            // Draw the old page on the right side [pullX .. w]
            canvas.save()
            canvas.clipRect(pullX, 0f, w, h)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()

            if (flapW > 2f) {
                val flapRight = pullX + flapW
                canvas.save()
                canvas.clipRect(pullX, 0f, flapRight, h)
                canvas.drawRect(pullX, 0f, flapRight, h, backTintPaint)
                shadowPaint.shader = LinearGradient(
                    pullX, 0f, flapRight, 0f,
                    intArrayOf(0x2823150B, 0x66FFFFFF, 0x00FFFFFF, 0x3823150B),
                    floatArrayOf(0f, 0.22f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(pullX, 0f, flapRight, h, shadowPaint)
                canvas.restore()

                val rightShadowW = (flapW * 0.45f).coerceAtMost(w - flapRight)
                if (rightShadowW > 1f) {
                    shadowPaint.shader = LinearGradient(
                        flapRight, 0f, flapRight + rightShadowW, 0f,
                        intArrayOf(0x55160D06, 0x00000000),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(flapRight, 0f, flapRight + rightShadowW, h, shadowPaint)
                }
            }
        }
    }
}
