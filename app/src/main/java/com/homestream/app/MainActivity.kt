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
        content = column().apply { setPadding(0, 0, 0, dp(40)) }
        if (isTelevision()) televisionShell() else phoneShell()
        setContentView(root)
        if (castContext(this) != null) {
            supportFragmentManager.findFragmentByTag("cast-mini")?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
            supportFragmentManager.beginTransaction().replace(R.id.cast_mini,
                com.google.android.gms.cast.framework.media.widget.MiniControllerFragment(), "cast-mini").commitNow()
        }
    }

    private fun phoneShell() {
        val header = LinearLayout(this).apply {
            id = R.id.phone_header; gravity = Gravity.CENTER_VERTICAL
            setPadding(gutter, dp(6), gutter, dp(2))
        }
        header.addView(label("For You", 28f, true), LinearLayout.LayoutParams(0, dp(54), 1f))
        castButton(this)?.let { header.addView(it, LinearLayout.LayoutParams(dp(44), dp(44))) }
        header.addView(iconButton("Search", R.drawable.nav_search) { search() })
        root.addView(header)
        val categories = LinearLayout(this).apply {
            id = R.id.phone_categories; setPadding(gutter, 0, gutter, dp(12))
        }
        categories.addView(categoryPill("TV Shows") { loadLibrary("tv") })
        categories.addView(categoryPill("Movies") { loadLibrary("movies") })
        categories.addView(categoryPill("Categories ⌄") {
            AlertDialog.Builder(this).setTitle("Browse").setItems(arrayOf("Home", "TV Shows", "Movies", "My List")) { _, choice ->
                when (choice) { 0 -> loadHome(); 1 -> loadLibrary("tv"); 2 -> loadLibrary("movies"); else -> myHomeStream() }
            }.show()
        })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categories) })
        root.addView(ScrollView(this).apply { clipChildren = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(androidx.fragment.app.FragmentContainerView(this).apply { id = R.id.cast_mini }, LinearLayout.LayoutParams(-1, -2))
        val bottom = LinearLayout(this).apply {
            id = R.id.phone_bottom_navigation; gravity = Gravity.CENTER
            setBackgroundColor(0xFF141414.toInt()); setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        val tabs = listOf(Triple("Home", R.drawable.nav_home, "home"),
            Triple("New & Hot", R.drawable.nav_new, "new"), Triple("My HomeStream", R.drawable.nav_profile, "mine"))
        tabs.forEach { (title, icon, page) ->
            val tab = action(title) { when (page) { "home" -> loadHome(); "new" -> newAndHot(); else -> myHomeStream() } }.apply {
                textSize = 10f; setPadding(dp(2), dp(4), dp(2), 0)
                setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
                compoundDrawablePadding = dp(4)
                setTextColor(if (screen == page) Color.WHITE else MUTED)
                background = rounded(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f)
            }
            bottom.addView(tab)
        }
        root.addView(bottom)
    }

    private fun televisionShell() {
        val stage = FrameLayout(this)
        stage.addView(ScrollView(this).apply { clipChildren = false; addView(content) },
            FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(68) })
        val rail = column().apply {
            id = R.id.tv_navigation_rail; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(24), dp(8), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(INK, 0xF0080808.toInt(), 0xDD080808.toInt()))
        }
        val entries = listOf(Triple("Profiles", R.drawable.nav_profile, { profiles() }),
            Triple("Search", R.drawable.nav_search, { search() }),
            Triple("Home", R.drawable.nav_home, { loadHome() }),
            Triple("New & Popular", R.drawable.nav_new, { newAndHot() }),
            Triple("TV Shows", R.drawable.nav_series, { loadLibrary("tv") }),
            Triple("Movies", R.drawable.nav_movies, { loadLibrary("movies") }),
            Triple("My List", R.drawable.nav_list, { myHomeStream() }),
            Triple("Settings", R.drawable.nav_settings, { setup() }))
        val buttons = entries.map { (title, icon, click) ->
            action("", click = click).apply {
                contentDescription = title; tag = title; gravity = Gravity.CENTER_VERTICAL
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(18)
                setPadding(dp(12), 0, dp(8), 0)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF383838.toInt()))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT))
                }
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(4) }
                rail.addView(this)
            }
        }
        stage.addView(rail, FrameLayout.LayoutParams(dp(68), -1, Gravity.START))
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            var parent: android.view.ViewParent? = focused?.parent
            var inside = focused === rail
            while (parent != null) { if (parent === rail) inside = true; parent = parent.parent }
            val width = dp(if (inside) 200 else 68)
            if (rail.layoutParams.width != width) {
                rail.layoutParams = (rail.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                buttons.forEach { it.text = if (inside) it.tag.toString() else "" }
            }
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun iconButton(title: String, icon: Int, click: () -> Unit) = action("", click = click).apply {
        contentDescription = title; setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        setPadding(dp(10), 0, dp(6), 0); background = rounded(Color.TRANSPARENT)
        minWidth = dp(44); minimumWidth = dp(44)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
    }
    private fun categoryPill(title: String, click: () -> Unit) = action(title, click = click).apply {
        textSize = 12f; minHeight = dp(36); minimumHeight = dp(36)
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFF777777.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(8) }
    }

    private fun newAndHot() {
        screen = "new"
        fetch("home") { data ->
            heading(if (isTelevision()) "New & Popular" else "New & Hot", 28f)
            val rows = data.optJSONArray("requestSections")?.takeIf { it.length() > 0 } ?: data.optJSONArray("sections")
            rows?.let { for (i in 0 until it.length()) {
                val row = it.getJSONObject(i); mediaRow(row.optString("title"), row.mediaList("items"))
            } }
        }
    }
    private fun myHomeStream() {
        screen = "mine"
        fetch("home") { data ->
            heading(if (isTelevision()) "My List" else "My HomeStream", 28f)
            val account = LinearLayout(this).apply { setPadding(gutter, 0, gutter, dp(12)) }
            account.addView(action("Switch profile") { profiles() })
            account.addView(action("Settings") { setup() })
            content.addView(account)
            val saved = Watchlist(this, settings).items()
            if (saved.isEmpty()) message("Add titles to My List from the featured card or a title's details.")
            else mediaRow("My List", saved)
            data.optJSONArray("sections")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (row.optString("id") == "continue-watching") mediaRow("Continue Watching", row.mediaList("items"))
                }
            }
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
                if (isTelevision()) content.post {
                    val targets = ArrayList<View>()
                    content.addFocusables(targets, View.FOCUS_FORWARD)
                    targets.firstOrNull()?.requestFocus()
                }
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
        if (isTelevision()) televisionHero(item, showActions) else phoneHero(item, showActions)
    }

    private fun phoneHero(item: Media, showActions: Boolean) {
        val frame = FrameLayout(this).apply {
            id = R.id.featured_card; background = GradientDrawable().apply {
                setColor(0xFF242424.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF555555.toInt())
            }; clipToOutline = true
        }
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            load(api.absolute(item.poster.ifBlank { item.backdrop })) { crossfade(true) }
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00080808, 0x11080808, 0xF0080808.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        val copy = column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(20), dp(16), dp(16)) }
        copy.addView(label("H O M E S T R E A M", 10f, true).apply { gravity = Gravity.CENTER })
        copy.addView(label(item.title, 32f, true).apply { gravity = Gravity.CENTER; maxLines = 2 })
        val genres = item.json.optJSONArray("genres")?.let { array -> (0 until minOf(3, array.length())).map { array.optString(it) }.joinToString("  •  ") }.orEmpty()
        copy.addView(label(genres.ifBlank { item.metadata }, 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(10)) })
        if (showActions) {
            val actions = LinearLayout(this)
            actions.addView(action("▶ Play", true) { if (item.playable && item.type != "Series") play(item) else details(item) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
            })
            val list = Watchlist(this, settings)
            actions.addView(action(if (list.contains(item)) "✓ My List" else "+ My List") {}.apply {
                setOnClickListener { text = if (list.toggle(item)) "✓ My List" else "+ My List" }
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            })
            copy.addView(actions)
            frame.addView(iconButton("More info", R.drawable.nav_info) { details(item) },
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))
        }
        frame.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val height = dp((resources.configuration.screenHeightDp * .61f).toInt().coerceIn(360, 520))
        content.addView(frame, LinearLayout.LayoutParams(-1, height).apply { setMargins(gutter, dp(2), gutter, dp(8)) })
    }

    private fun televisionHero(item: Media, showActions: Boolean) {
        val frame = FrameLayout(this).apply { id = R.id.featured_card }
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            load(api.absolute(item.backdrop)) { crossfade(true) }
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(INK, 0xCC080808.toInt(), 0x00080808)) }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00080808, 0x00080808, INK)) }, FrameLayout.LayoutParams(-1, -1))
        val copy = column().apply { setPadding(gutter, dp(40), dp(16), dp(32)) }
        copy.addView(label("H O M E S T R E A M   •   FEATURED", 12f, true).apply { setTextColor(MUTED) })
        copy.addView(label(item.title, 40f, true).apply { maxLines = 2 })
        copy.addView(label(item.metadata, 14f).apply { setPadding(0, dp(12), 0, dp(12)); setTextColor(MUTED) })
        copy.addView(label(item.overview, 16f).apply { maxLines = 3 })
        if (showActions) {
            val actions = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
            actions.addView(action("▶ Play", true) { if (item.playable && item.type != "Series") play(item) else details(item) })
            actions.addView(action("More Info") { details(item) })
            copy.addView(actions)
        }
        val copyWidth = dp(((resources.configuration.screenWidthDp - 68) * .56f).toInt())
        frame.addView(copy, FrameLayout.LayoutParams(copyWidth, -2, Gravity.CENTER_VERTICAL))
        val height = dp((resources.configuration.screenHeightDp * .70f).toInt().coerceIn(330, 480))
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
                load(api.absolute(if (isTelevision()) item.backdrop else item.poster)) { crossfade(true); error(R.drawable.ic_homestream) }
            }, LinearLayout.LayoutParams(-1, dp(if (isTelevision()) 126 else 158)))
            if (item.json.optDouble("progressPercent") > 0) {
                card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = item.json.optDouble("progressPercent").toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(RED)
                }, LinearLayout.LayoutParams(-1, dp(4)))
            }
            if (isTelevision()) card.addView(label(item.title, 13f, true).apply { maxLines = 1; setPadding(dp(4), dp(6), dp(4), 0) })
            row.addView(card, LinearLayout.LayoutParams(dp(if (isTelevision()) 224 else 112), -2).apply { setMargins(0, 0, dp(10), 0) })
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
            val list = Watchlist(this, settings)
            content.addView(action(if (list.contains(full)) "✓ My List" else "+ My List") {}.apply {
                setOnClickListener { text = if (list.toggle(full)) "✓ My List" else "+ My List" }
            })
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
