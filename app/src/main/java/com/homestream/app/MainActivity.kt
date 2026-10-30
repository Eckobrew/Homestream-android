package com.homestream.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var api: HomeStreamApi
    private lateinit var content: LinearLayout
    private lateinit var root: LinearLayout
    private var work: Job? = null
    private var screen = "home"
    private var generation = 0
    private var playbackWasLaunched = false
    private var lastDetails: Media? = null
    private val gutter get() = dp(if (isTelevision()) 48 else 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this); api = HomeStreamApi(settings)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != "home") loadHome() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) setup() else loadHome()
    }

    override fun onResume() {
        super.onResume()
        if (playbackWasLaunched) {
            playbackWasLaunched = false
            lastDetails?.takeIf { screen == "details" }?.let { details(it) } ?: loadHome()
        }
    }

    private fun shell() {
        root = column().apply { setBackgroundColor(INK) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(gutter, dp(10), gutter, dp(10))
        }
        header.addView(label("HOMESTREAM", if (isTelevision()) 24f else 20f, true).apply { setTextColor(RED); contentDescription = "HomeStream" })
        if (isTelevision()) {
            val nav = LinearLayout(this)
            nav.addView(action("Home") { loadHome() })
            nav.addView(action("Movies") { loadLibrary("movies") })
            nav.addView(action("TV Shows") { loadLibrary("tv") })
            nav.addView(action("Search") { search() })
            nav.addView(action("Profiles") { profiles() })
            nav.addView(action("Settings") { setup() })
            header.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(nav) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        } else {
            header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        castButton(this)?.let { header.addView(it, LinearLayout.LayoutParams(dp(48), dp(48))) }
        if (!isTelevision()) header.addView(action("⚙") { setup() }.apply { contentDescription = "Server settings"; minWidth = dp(48); minimumWidth = dp(48) })
        root.addView(header)
        if (!isTelevision()) {
            val categories = LinearLayout(this).apply { setPadding(gutter - dp(4), 0, gutter, 0) }
            categories.addView(action("TV Shows") { loadLibrary("tv") })
            categories.addView(action("Movies") { loadLibrary("movies") })
            root.addView(categories)
        }
        content = column().apply { setPadding(0, 0, 0, dp(40)) }
        root.addView(ScrollView(this).apply { clipChildren = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val mini = androidx.fragment.app.FragmentContainerView(this).apply { id = R.id.cast_mini }
        root.addView(mini, LinearLayout.LayoutParams(-1, -2))
        if (!isTelevision()) {
            val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER; setBackgroundColor(0xFF141414.toInt()); setPadding(dp(12), dp(4), dp(12), dp(4)) }
            listOf("Home" to { loadHome() }, "Search" to { search() }, "Profiles" to { profiles() }).forEach { (title, click) ->
                bottom.addView(action(title, title == "Home" && screen == "home", click).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
                })
            }
            root.addView(bottom)
        }
        setContentView(root)
        // Fragment is attached only when Cast is supported (Google Play services).
        if (castContext(this) != null) {
            supportFragmentManager.findFragmentByTag("cast-mini")?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
            supportFragmentManager.beginTransaction().replace(mini.id,
                com.google.android.gms.cast.framework.media.widget.MiniControllerFragment(), "cast-mini").commitNow()
        }
    }

    private fun heading(text: String, size: Float = 22f) = label(text, size, true).apply {
        setPadding(gutter, dp(20), gutter, dp(12)); content.addView(this)
    }
    private fun message(text: String) {
        content.addView(label(text).apply { setTextColor(MUTED); setPadding(gutter, dp(12), gutter, dp(12)) })
    }
    private fun fetch(path: String, params: Map<String, String> = emptyMap(), draw: (JSONObject) -> Unit) {
        work?.cancel(); val current = ++generation
        shell(); message("Loading…")
        work = lifecycleScope.launch {
            try {
                val data = api.call(path, params)
                if (generation != current) return@launch
                content.removeAllViews(); draw(data)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Navigation cancelled this request.
            } catch (e: Exception) {
                if (generation != current) return@launch
                content.removeAllViews(); heading("Unable to connect")
                message(e.message ?: "Check your HomeStream connection.")
                content.addView(action("Retry") { fetch(path, params, draw) })
                content.addView(action("Server settings") { setup() })
            }
        }
    }
    private fun loadHome() {
        screen = "home"
        fetch("home") { data ->
            if (data.optBoolean("isDemoMode")) message("DEMO LIBRARY • Connect Jellyfin on your HomeStream server to play your media.")
            data.optJSONObject("hero")?.let { hero(Media(it)) }
            val rows = data.optJSONArray("sections")
            if (rows == null || rows.length() == 0) message("Your library is empty. Add media in Jellyfin, then refresh.")
            rows?.let { for (i in 0 until it.length()) {
                val row = it.getJSONObject(i); mediaRow(row.optString("title"), row.mediaList("items"))
            } }
        }
    }
    private fun hero(item: Media, showActions: Boolean = true) {
        val frame = FrameLayout(this)
        val height = dp(if (isTelevision()) 350 else 390)
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            load(api.absolute(item.backdrop)) { crossfade(true) }
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x33080808, 0x99080808.toInt(), INK)) }, FrameLayout.LayoutParams(-1, -1))
        val copy = column().apply { setPadding(gutter, dp(30), gutter, dp(16)) }
        copy.addView(label("H O M E S T R E A M   •   FEATURED", 11f, true).apply { setTextColor(MUTED) })
        copy.addView(label(item.title, if (isTelevision()) 38f else 32f, true).apply { maxLines = 3 })
        copy.addView(label(item.metadata, 13f).apply { setPadding(0, dp(8), 0, dp(8)); setTextColor(MUTED) })
        copy.addView(label(item.overview, 14f).apply { maxLines = 3 })
        if (showActions) {
            val actions = LinearLayout(this)
            if (item.playable && (item.type != "Series" || item.isDownload)) actions.addView(action("▶ Play", true) { play(item) })
            actions.addView(action("ⓘ More info") { details(item) })
            copy.addView(actions)
        }
        frame.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        content.addView(frame, LinearLayout.LayoutParams(-1, height))
    }
    private fun mediaRow(title: String, items: List<Media>) {
        if (items.isEmpty()) return
        heading(title)
        val row = LinearLayout(this).apply { setPadding(gutter, dp(8), gutter, dp(12)); clipChildren = false }
        items.forEach { item ->
            val card = column().apply {
                setPadding(dp(5), dp(5), dp(5), dp(8)); focusableCard()
                contentDescription = "${item.title}, ${item.type}, ${item.metadata}"
                setOnClickListener { details(item) }
            }
            card.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(api.absolute(item.poster)) { crossfade(true); error(R.drawable.ic_homestream) }
            }, LinearLayout.LayoutParams(-1, dp(190)))
            if (item.json.optDouble("progressPercent") > 0) {
                card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = item.json.optDouble("progressPercent").toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(RED)
                }, LinearLayout.LayoutParams(-1, dp(4)))
            }
            card.addView(label(item.title, 13f, true).apply { maxLines = 2; setPadding(dp(4), dp(6), dp(4), 0) })
            row.addView(card, LinearLayout.LayoutParams(dp(140), -2).apply { setMargins(0, 0, dp(10), 0) })
        }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(row) })
    }
    private fun loadLibrary(type: String, query: String = "") {
        screen = type
        fetch("library/$type", mapOf("search" to query)) { data ->
            heading(if (query.isBlank()) data.optString("title", "Your library") else "Results for “$query”")
            val items = data.mediaList("items")
            if (items.isEmpty()) message("No titles found in this library.")
            items.chunked(12).forEachIndexed { index, chunk -> mediaRow(if (index == 0) "Your collection" else "More to explore", chunk) }
        }
    }
    private fun search() {
        val field = EditText(this).apply { hint = "Search your library"; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Search HomeStream").setView(field)
            .setPositiveButton("Search") { _, _ ->
                val query = field.text.toString().trim(); if (query.isBlank()) return@setPositiveButton
                screen = "search"
                fetch("library/movies", mapOf("search" to query)) { movies ->
                    heading("Results for “$query”")
                    val movieItems = movies.mediaList("items")
                    mediaRow("Movies", movieItems)
                    work = lifecycleScope.launch {
                        try {
                            val shows = api.call("library/tv", mapOf("search" to query)).mediaList("items")
                            mediaRow("TV Shows", shows)
                            if (movieItems.isEmpty() && shows.isEmpty()) message("No matching titles in your Jellyfin library.")
                        } catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) message("TV search failed. Try again.") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun details(item: Media) {
        lastDetails = item
        screen = "details"
        fetch("media/${android.net.Uri.encode(item.id)}") { data ->
            val full = Media(data.getJSONObject("item")); hero(full, false)
            heading(full.title); message(full.overview)
            if ((full.type != "Series" || full.isDownload) && full.playable) {
                content.addView(action(if (full.resumeMs > 0) "▶ Resume" else "▶ Play", true) { play(full) })
                if (full.resumeMs > 0) content.addView(action("Play from beginning") { play(full, true) })
            }
            full.json.optJSONArray("seasons")?.let { seasons ->
                for (i in 0 until seasons.length()) {
                    val season = seasons.getJSONObject(i)
                    heading(season.optString("name", "Season ${i + 1}"))
                    season.mediaList("episodes").forEach { episode ->
                        val caption = "${episode.json.optInt("episodeNumber")}. ${episode.title}"
                        content.addView(action(caption) {
                            if (episode.playable) play(episode) else toast("This episode is not available in Jellyfin yet.")
                        })
                    }
                }
            }
            if (!full.playable && full.json.optInt("tmdbId") > 0 && full.json.optString("status") == "not_requested") {
                content.addView(action("Request title") {
                    AlertDialog.Builder(this).setTitle("Request ${full.title}?")
                        .setMessage("Send this request to your configured Seerr server?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Request") { _, _ ->
                            lifecycleScope.launch {
                                try { val response = api.call("request", body = JSONObject()
                                    .put("mediaType", if (full.type == "Series") "tv" else "movie")
                                    .put("mediaId", full.json.getInt("tmdbId")).apply { if (full.type == "Series") put("seasons", "all") })
                                    toast(response.optString("message", "Request sent"))
                                } catch (e: Exception) { toast(e.message ?: "Request failed") }
                            }
                        }.show()
                })
            } else if (!full.playable && full.type != "Series") message("This title is not available for playback yet.")
        }
    }
    private fun play(item: Media, fromBeginning: Boolean = false) {
        playbackWasLaunched = true
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("media", item.json.toString()).putExtra("fromBeginning", fromBeginning))
    }
    private fun profiles() {
        lifecycleScope.launch {
            try {
                val profiles = api.call("profiles").optJSONArray("profiles")
                if (profiles == null || profiles.length() == 0) { toast("Create profiles in the HomeStream web app first."); return@launch }
                val names = Array(profiles.length()) { profiles.getJSONObject(it).optString("name") }
                AlertDialog.Builder(this@MainActivity).setTitle("Who's watching?").setItems(names) { _, index ->
                    settings.profile = profiles.getJSONObject(index).getString("id"); loadHome()
                }.show()
            } catch (e: Exception) { toast(e.message ?: "Could not load profiles") }
        }
    }
    private fun setup() {
        val first = settings.baseUrl.isBlank()
        if (first) { shell(); heading("Your cinema. Your collection."); message("Connect to your HomeStream server to begin.") }
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val url = EditText(this).apply {
            hint = "https://homestream.example.com"; setText(settings.baseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true
        }
        val token = EditText(this).apply {
            hint = "Server pairing token"; setText(settings.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true
        }
        form.addView(label("HomeStream server URL", 14f)); form.addView(url)
        form.addView(label("HOMESTREAM_MOBILE_TOKEN", 14f)); form.addView(token)
        form.addView(label("Use HTTPS. HTTP is available for a trusted local network only. Set the pairing token on your HomeStream server first.", 12f).apply { setTextColor(MUTED) })
        val dialog = AlertDialog.Builder(this).setTitle("Connect HomeStream").setView(form)
            .setPositiveButton("Connect", null).setNegativeButton(if (first) "Close" else "Cancel") { _, _ -> if (first) finish() }
            .apply { if (!first) setNeutralButton("Disconnect") { _, _ -> settings.clear(); setup() } }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = url.text.toString().trim().toHttpUrlOrNull()
                if (parsed == null || parsed.username.isNotBlank() || parsed.password.isNotBlank() || parsed.query != null || parsed.fragment != null) {
                    url.error = "Enter an HTTP(S) server URL without credentials, query or fragment"; return@setOnClickListener
                }
                val pairingToken = token.text.toString().trim()
                if (pairingToken.length < 32) { token.error = "Token must contain at least 32 characters"; return@setOnClickListener }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false; button.text = "Connecting…"
                lifecycleScope.launch {
                    try {
                        val candidate = object : ConnectionSettings {
                            override val baseUrl = parsed.toString().trimEnd('/')
                            override val token = pairingToken
                            override var profile = ""
                        }
                        val capabilities = HomeStreamApi(candidate).call("capabilities")
                        check(capabilities.optInt("apiVersion") == 1) { "This server requires a different app version." }
                        if (dialog.isShowing) {
                            settings.save(candidate.baseUrl, pairingToken); dialog.dismiss(); loadHome()
                        }
                    } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) token.error = e.message ?: "Connection failed"
                    } finally { button.isEnabled = true; button.text = "Connect" }
                }
            }
        }
        dialog.show()
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
