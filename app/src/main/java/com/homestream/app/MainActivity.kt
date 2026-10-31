package com.homestream.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage

class MainActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var api: HomeStreamApi
    private lateinit var content: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var mainContainer: LinearLayout
    private var sidebarDrawer: FrameLayout? = null
    private var work: Job? = null
    private var heroRotationJob: Job? = null
    private var currentTab = "home"
    private var screen = "home"
    private var generation = 0
    private var playbackWasLaunched = false
    private var lastDetails: Media? = null
    private val gutter get() = dp(if (isTelevision()) 48 else 20)

    // Hero rotation state
    private var heroItems: List<Media> = emptyList()
    private var currentHeroIndex = 0
    private var heroContainer: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this); api = HomeStreamApi(settings)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sidebarDrawer?.visibility == View.VISIBLE) {
                    closeSidebar(); return
                }
                if (screen != "home") {
                    loadTab("home")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) setup() else loadTab("home")
    }

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) {
            heroItems.getOrNull(currentHeroIndex)?.let { syncCastIdle(it) }
        }
        override fun onSessionResumed(session: CastSession, suspended: Boolean) {
            heroItems.getOrNull(currentHeroIndex)?.let { syncCastIdle(it) }
        }
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, id: String) {}
    }

    override fun onStart() {
        super.onStart()
        castContext(this)?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    override fun onStop() {
        super.onStop()
        castContext(this)?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    override fun onResume() {
        super.onResume()
        if (playbackWasLaunched) {
            playbackWasLaunched = false
            lastDetails?.takeIf { screen == "details" }?.let { details(it) } ?: loadTab(currentTab)
        }
    }

    private fun shell() {
        heroRotationJob?.cancel()
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.findFragmentByTag("cast-mini")?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
        }
        root = FrameLayout(this).apply { setBackgroundColor(INK) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        mainContainer = column().apply { setBackgroundColor(INK) }
        content = column().apply { setPadding(0, 0, 0, dp(90)) } // padding for bottom floating pill

        if (isTelevision()) televisionShell() else phoneShell()

        root.addView(mainContainer, FrameLayout.LayoutParams(-1, -1))
        setupSidebarDrawer()
        setContentView(root)
    }

    private fun phoneShell() {
        // 1. Netflix-style sleek header
        val header = LinearLayout(this).apply {
            id = R.id.phone_header; gravity = Gravity.CENTER_VERTICAL
            setPadding(gutter, dp(6), gutter, dp(4))
        }

        // Hamburger Menu Button (opens sliding sidebar)
        header.addView(iconButton("Menu", R.drawable.ic_menu) { openSidebar() })

        // App Logo (bold red HOMESTREAM)
        val logo = TextView(this).apply {
            text = "HOMESTREAM"; textSize = 20f; setTextColor(RED); typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f; setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { loadTab("home") }
        }
        header.addView(logo, LinearLayout.LayoutParams(0, -2, 1f))

        // Chromecast button
        castButton(this)?.let { header.addView(it, LinearLayout.LayoutParams(dp(40), dp(40))) }

        // Profile switcher button
        header.addView(iconButton("Profile", R.drawable.nav_profile) { profiles() })

        mainContainer.addView(header)

        // 2. Top quick-filter pills (TV Shows, Movies, Categories)
        if (screen != "search" && screen != "details") {
            val pillsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val pills = LinearLayout(this).apply {
                id = R.id.phone_categories; setPadding(gutter, dp(2), gutter, dp(8))
            }
            pills.addView(categoryPill("TV Shows", currentTab == "series") { loadTab("series") })
            pills.addView(categoryPill("Movies", currentTab == "movies") { loadTab("movies") })
            pills.addView(categoryPill("Categories ⌄", false) { showCategoriesMenu() })
            pillsScroll.addView(pills)
            mainContainer.addView(pillsScroll)
        }

        // 3. Scrollable content
        mainContainer.addView(ScrollView(this).apply { clipChildren = false; addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))

        // Cast mini controller if cast is active - docked above floating navigation pill
        if (castContext(this) != null) {
            val miniController = castMiniControllerView(mainContainer)
            mainContainer.addView(miniController, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(76)
            })
        }

        // 4. Extended Floating Pill Bottom Navigation Bar (Home, TV Shows, Movies, Requests, Search)
        val floatingNav = createFloatingBottomNav()
        root.addView(floatingNav, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(14)
        })
    }

    private fun createFloatingBottomNav(): View {
        val pill = LinearLayout(this).apply {
            id = R.id.phone_bottom_navigation; gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xF0181818.toInt()); cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x33FFFFFF.toInt())
            }
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val navTabs = listOf(
            Triple("Home", R.drawable.nav_home, "home"),
            Triple("TV Shows", R.drawable.nav_series, "series"),
            Triple("Movies", R.drawable.nav_movies, "movies"),
            Triple("Requests", R.drawable.ic_download, "requests"),
            Triple("Search", R.drawable.nav_search, "search")
        )

        navTabs.forEach { (title, icon, tabKey) ->
            val isActive = (tabKey == "search" && screen == "search") || (tabKey != "search" && currentTab == tabKey && screen != "search")
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(6))
                isClickable = true; isFocusable = true
                background = GradientDrawable().apply {
                    setColor(if (isActive) 0x22FFFFFF.toInt() else Color.TRANSPARENT)
                    cornerRadius = dp(20).toFloat()
                }
                setOnClickListener {
                    if (tabKey == "search") openSearch() else loadTab(tabKey)
                }
            }

            val img = ImageView(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(if (isActive) RED else MUTED)
            }
            item.addView(img, LinearLayout.LayoutParams(dp(20), dp(20)))

            val txt = TextView(this).apply {
                text = title; textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) Color.WHITE else MUTED)
                setPadding(0, dp(2), 0, 0)
                isSingleLine = true
                gravity = Gravity.CENTER
            }
            item.addView(txt)

            pill.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }

        return pill
    }

    private fun televisionShell() {
        val stage = FrameLayout(this)
        stage.addView(ScrollView(this).apply { clipChildren = false; addView(content) },
            FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(76) })

        val rail = column().apply {
            id = R.id.tv_navigation_rail; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(24), dp(8), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(INK, 0xF0080808.toInt(), 0xDD080808.toInt()))
        }

        val entries = listOf(
            Triple("Search", R.drawable.nav_search, { openSearch() }),
            Triple("Home", R.drawable.nav_home, { loadTab("home") }),
            Triple("TV Shows", R.drawable.nav_series, { loadTab("series") }),
            Triple("Movies", R.drawable.nav_movies, { loadTab("movies") }),
            Triple("Requests", R.drawable.ic_download, { loadTab("requests") }),
            Triple("A–Z Movies", R.drawable.nav_movies, { loadCatalog("movies") }),
            Triple("A–Z Series", R.drawable.nav_series, { loadCatalog("tv") }),
            Triple("Categories", R.drawable.nav_movies, { loadCategories("movies") }),
            Triple("Downloads", R.drawable.ic_download, { loadDownloads() }),
            Triple("My List", R.drawable.nav_list, { myHomeStream() }),
            Triple("Profiles", R.drawable.nav_profile, { profiles() }),
            Triple("Settings", R.drawable.nav_settings, { setup() })
        )

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
                layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) }
                rail.addView(this)
            }
        }

        stage.addView(rail, FrameLayout.LayoutParams(dp(76), -1, Gravity.START))
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            var parent: android.view.ViewParent? = focused?.parent
            var inside = focused === rail
            while (parent != null) { if (parent === rail) inside = true; parent = parent.parent }
            val width = dp(if (inside) 210 else 76)
            if (rail.layoutParams.width != width) {
                rail.layoutParams = (rail.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                buttons.forEach { it.text = if (inside) it.tag.toString() else "" }
            }
        }

        mainContainer.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // -----------------------------------------------------------------------------------------
    // Sidebar Drawer (Matching HomeStream Web App Sidebar.tsx)
    // -----------------------------------------------------------------------------------------
    private fun setupSidebarDrawer() {
        val drawer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { closeSidebar() }
        }

        val panelWidth = dp(290).coerceAtMost((resources.displayMetrics.widthPixels * 0.85f).toInt())
        val panel = column().apply {
            setBackgroundColor(0xFF121212.toInt())
            isClickable = true
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        // Header: Logo + Jellyfin Library Indicator + Close button
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(20)) }
        val logoBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(RED); cornerRadius = dp(8).toFloat()
            }
            addView(TextView(this@MainActivity).apply {
                text = "H"; textSize = 18f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        head.addView(logoBox, LinearLayout.LayoutParams(dp(36), dp(36)))

        val titleCol = column().apply { setPadding(dp(12), 0, 0, 0) }
        titleCol.addView(label("HomeStream", 16f, true))
        val status = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF10B981.toInt()); shape = GradientDrawable.OVAL }
        }, LinearLayout.LayoutParams(dp(7), dp(7)))
        status.addView(TextView(this).apply {
            text = "  Jellyfin Library"; textSize = 10f; setTextColor(MUTED)
        })
        titleCol.addView(status)
        head.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); imageTintList = ColorStateList.valueOf(MUTED)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true; setOnClickListener { closeSidebar() }
        }
        head.addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        panel.addView(head)

        // Home Feed Button
        val homeFeedBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (screen == "home") RED else 0x22FFFFFF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { closeSidebar(); loadTab("home") }
        }
        homeFeedBtn.addView(ImageView(this).apply {
            setImageResource(R.drawable.nav_home); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        val homeFeedText = column().apply { setPadding(dp(12), 0, 0, 0) }
        homeFeedText.addView(label("Home Streaming Feed", 14f, true))
        homeFeedText.addView(TextView(this).apply { text = "Netflix-style rows & categories"; textSize = 11f; setTextColor(MUTED) })
        homeFeedBtn.addView(homeFeedText)
        panel.addView(homeFeedBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        // Section: Jellyfin Media (A–Z)
        panel.addView(TextView(this).apply {
            text = "JELLYFIN MEDIA (A–Z)"; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(MUTED); setPadding(0, 0, 0, dp(4))
        })
        panel.addView(TextView(this).apply {
            text = "Direct access to all media stored on your server, sorted alphabetically."; textSize = 11f
            setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, dp(12))
        })

        // Library Items: Movies (A-Z), TV Shows (A-Z), YouTube (A-Z)
        val libs = listOf(
            Triple("Movies", "All movies on your Jellyfin server", "movies"),
            Triple("TV Shows", "All series on your Jellyfin server", "tv"),
            Triple("YouTube", "Downloaded YouTube library", "youtube")
        )

        libs.forEach { (name, desc, type) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(0x18FFFFFF.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { closeSidebar(); loadCatalog(type) }
            }
            val textCol = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            textCol.addView(label(name, 14f, true))
            textCol.addView(TextView(this).apply { text = desc; textSize = 11f; setTextColor(MUTED) })
            btn.addView(textCol)
            btn.addView(badgePill("A–Z"))
            panel.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        // Divider
        panel.addView(View(this).apply {
            setBackgroundColor(0x22FFFFFF.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(16)) })

        // Settings Section Header
        panel.addView(TextView(this).apply {
            text = "SETTINGS & CONNECTION"; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(MUTED); setPadding(0, 0, 0, dp(10))
        })

        // Server Settings Button (with External URL configuration)
        val settingsBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x18FFFFFF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { closeSidebar(); openServerSettingsDialog() }
        }
        settingsBtn.addView(ImageView(this).apply {
            setImageResource(R.drawable.nav_settings); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val settingsText = column().apply { setPadding(dp(12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        settingsText.addView(label("Server Settings", 13.5f, true))
        settingsText.addView(TextView(this).apply {
            val mode = if (settings.localUrl.isNotBlank() && settings.externalUrl.isNotBlank()) "Dual URL (Local + Remote)"
                else if (settings.externalUrl.isNotBlank()) "External URL" else "Local Server"
            text = mode; textSize = 11f; setTextColor(MUTED)
        })
        settingsBtn.addView(settingsText)
        panel.addView(settingsBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Switch Profile Button
        val profileBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x18FFFFFF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { closeSidebar(); profiles() }
        }
        profileBtn.addView(ImageView(this).apply {
            setImageResource(R.drawable.nav_profile); imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val profText = column().apply { setPadding(dp(12), 0, 0, 0) }
        profText.addView(label("Who's Watching?", 13.5f, true))
        profText.addView(TextView(this).apply { text = "Switch user profile"; textSize = 11f; setTextColor(MUTED) })
        profileBtn.addView(profText)
        panel.addView(profileBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        drawer.addView(panel, FrameLayout.LayoutParams(panelWidth, -1, Gravity.START))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        sidebarDrawer = drawer
    }

    private fun openSidebar() {
        sidebarDrawer?.visibility = View.VISIBLE
    }

    private fun closeSidebar() {
        sidebarDrawer?.visibility = View.GONE
    }

    // -----------------------------------------------------------------------------------------
    // Top Tabs Loading: Home, TV Shows, Movies, Requests
    // -----------------------------------------------------------------------------------------
    private fun loadTab(tab: String) {
        heroRotationJob?.cancel()
        currentTab = tab
        screen = tab
        fetch("home") { data ->
            if (data.optBoolean("isDemoMode")) {
                message("DEMO LIBRARY • Connect Jellyfin on your HomeStream server to play your media.")
            }

            // Extract candidate hero items for dynamic billboard
            val allHero = mutableListOf<Media>()
            data.optJSONObject("hero")?.let { allHero.add(Media(it)) }
            data.optJSONArray("heroItems")?.let { arr ->
                for (i in 0 until arr.length()) allHero.add(Media(arr.getJSONObject(i)))
            }
            data.optJSONArray("sections")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val sec = rows.getJSONObject(i)
                    if (sec.optString("id") != "continue-watching") {
                        allHero.addAll(sec.mediaList("items"))
                    }
                }
            }
            data.optJSONArray("requestSections")?.let { rows ->
                for (i in 0 until rows.length()) {
                    allHero.addAll(rows.getJSONObject(i).mediaList("items"))
                }
            }

            // Filter hero items by active tab
            val tabHeroCandidates = when (tab) {
                "series" -> allHero.filter { it.type == "Series" || it.type == "Episode" }
                "movies" -> allHero.filter { it.type == "Movie" }
                "requests" -> allHero.filter { !it.playable }
                else -> allHero
            }.distinctBy { it.id }.take(5)

            if (tabHeroCandidates.isNotEmpty()) {
                setupDynamicHero(tabHeroCandidates)
            } else if (allHero.isNotEmpty()) {
                setupDynamicHero(allHero.take(5))
            }

            // Handle content sections per tab
            when (tab) {
                "home" -> {
                    // Continue watching row
                    data.optJSONArray("sections")?.let { rows ->
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            if (row.optString("id") == "continue-watching") {
                                mediaRow(row.optString("title"), row.mediaList("items"), isContinueWatching = true)
                            }
                        }
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            if (row.optString("id") != "continue-watching") {
                                mediaRow(row.optString("title"), row.mediaList("items"))
                            }
                        }
                    }
                }
                "series" -> {
                    // Continue watching TV
                    data.optJSONArray("sections")?.let { rows ->
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            if (row.optString("id") == "continue-watching") {
                                val tvItems = row.mediaList("items").filter { it.type == "Series" || it.type == "Episode" }
                                if (tvItems.isNotEmpty()) mediaRow("Continue Watching", tvItems, isContinueWatching = true)
                            }
                        }
                    }
                    // Fetch category suggestions for TV Shows
                    loadCategorySectionsIntoContent("tv")
                }
                "movies" -> {
                    // Continue watching Movies
                    data.optJSONArray("sections")?.let { rows ->
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            if (row.optString("id") == "continue-watching") {
                                val movItems = row.mediaList("items").filter { it.type == "Movie" }
                                if (movItems.isNotEmpty()) mediaRow("Continue Watching", movItems, isContinueWatching = true)
                            }
                        }
                    }
                    // Fetch category suggestions for Movies
                    loadCategorySectionsIntoContent("movies")
                }
                "requests" -> {
                    // Requests Sections & Active Downloads
                    data.optJSONArray("requestSections")?.let { rows ->
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            mediaRow(row.optString("title"), row.mediaList("items"))
                        }
                    }
                    loadCategorySectionsIntoContent("requests")
                }
            }
        }
    }

    private fun loadCategorySectionsIntoContent(type: String) {
        lifecycleScope.launch {
            try {
                val catData = api.call("categories/$type")
                val sections = catData.optJSONArray("sections")
                if (sections != null && sections.length() > 0) {
                    for (i in 0 until sections.length()) {
                        val sec = sections.getJSONObject(i)
                        mediaRow(sec.optString("title"), sec.mediaList("items"))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------------------------
    // Dynamic Rotating Hero Banner (Cycling 5 Items Every 7 Seconds) & Hero Presentation
    // -----------------------------------------------------------------------------------------
    private fun setupDynamicHero(items: List<Media>) {
        heroItems = items
        currentHeroIndex = 0
        if (heroItems.isNotEmpty()) {
            hero(heroItems[0], true)
            syncCastIdle(heroItems[0])
        }
        if (heroItems.size > 1) {
            heroRotationJob?.cancel()
            heroRotationJob = lifecycleScope.launch {
                while (isActive) {
                    delay(7000)
                    if (screen != "home" || content.findViewById<View>(R.id.featured_card) == null) {
                        break
                    }
                    if (heroItems.isNotEmpty()) {
                        currentHeroIndex = (currentHeroIndex + 1) % heroItems.size
                        val nextItem = heroItems[currentHeroIndex]
                        hero(nextItem, true)
                        syncCastIdle(nextItem)
                    }
                }
            }
        }
    }

    private fun syncCastIdle(item: Media) {
        val session = castContext(this)?.sessionManager?.currentCastSession ?: return
        val client = session.remoteMediaClient ?: return
        if (!client.isPlaying && !client.isLoadingNextItem) {
            val art = item.backdrop.ifBlank { item.poster }
            if (art.isNotBlank()) {
                try {
                    val fullUrl = api.absolute(art)
                    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
                        putString(MediaMetadata.KEY_TITLE, item.title)
                        putString(MediaMetadata.KEY_SUBTITLE, "Ready to cast")
                        addImage(WebImage(Uri.parse(fullUrl)))
                    }
                    val photoInfo = MediaInfo.Builder(fullUrl)
                        .setContentType("image/jpeg")
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setMetadata(metadata)
                        .build()
                    client.load(MediaLoadRequestData.Builder().setMediaInfo(photoInfo).setAutoplay(true).build())
                } catch (_: Exception) {}
            }
        }
    }

    private fun hero(item: Media, showActions: Boolean = true) {
        if (screen != "home") return
        content.findViewById<View>(R.id.featured_card)?.let { content.removeView(it) }
        if (isTelevision()) televisionHero(item, showActions) else phoneHero(item, showActions)
    }

    private fun phoneHero(item: Media, showActions: Boolean) {
        val frame = FrameLayout(this).apply {
            id = R.id.featured_card
            clipToOutline = true
            background = rounded(0xFF1E1E1E.toInt(), radius = dp(12).toFloat())
        }

        // 1. High-Res 16:9 Backdrop Image (preventing awkward portrait zoom/crops like Fallout)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            load(api.absolute(item.backdrop.ifBlank { item.poster })) {
                crossfade(true); error(R.drawable.ic_homestream)
            }
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))

        // 2. Vertical Netflix scrim (dark bottom gradient)
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00141414, 0x44141414, 0xCC141414.toInt(), INK)
            )
        }
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        // 3. Information Overlay
        val infoCol = column().apply {
            gravity = Gravity.BOTTOM; setPadding(dp(16), dp(16), dp(16), dp(14))
        }

        // Title
        val titleView = TextView(this).apply {
            text = item.title; textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoCol.addView(titleView)

        // Metadata Pills row
        val metaRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(10))
        }
        if (item.json.optString("rating").isNotBlank()) {
            metaRow.addView(badgePill(item.json.optString("rating")))
        }
        val year = item.json.optString("year").takeUnless { it == "0" }.orEmpty()
        if (year.isNotBlank()) {
            metaRow.addView(TextView(this).apply {
                text = "  •  $year"; textSize = 11.5f; setTextColor(MUTED)
            })
        }
        val res = item.json.optString("resolution")
        if (res.isNotBlank()) {
            metaRow.addView(TextView(this).apply {
                text = "  •  $res"; textSize = 11.5f; setTextColor(0xFF10B981.toInt())
            })
        }
        infoCol.addView(metaRow)

        if (showActions) {
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val canPlay = item.isAvailableForPlayback
            val playBtn = Button(this).apply {
                text = if (canPlay) (if (item.resumeMs > 0) "▶  Resume" else "▶  Play") else "▶  Stream"
                textSize = 13.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (canPlay) Color.BLACK else Color.WHITE); isAllCaps = false
                background = rounded(if (canPlay) Color.WHITE else RED, radius = dp(8).toFloat())
                minHeight = dp(40); minimumHeight = dp(40)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener {
                    if (canPlay) play(item) else streamMedia(item)
                }
            }
            btnRow.addView(playBtn, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(10) })

            // Translucent My List Button
            val list = Watchlist(this, settings)
            val listBtn = Button(this).apply {
                text = if (list.contains(item)) "✓  My List" else "+  My List"; textSize = 13.5f
                setTextColor(Color.WHITE); isAllCaps = false
                background = rounded(0x55FFFFFF.toInt(), radius = dp(8).toFloat())
                minHeight = dp(40); minimumHeight = dp(40)
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    val added = list.toggle(item)
                    text = if (added) "✓  My List" else "+  My List"
                }
            }
            btnRow.addView(listBtn, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(10) })

            // Info Button
            val infoBtn = Button(this).apply {
                text = "ⓘ  Info"; textSize = 13.5f; setTextColor(Color.WHITE); isAllCaps = false
                background = rounded(0x44FFFFFF.toInt(), radius = dp(8).toFloat())
                minHeight = dp(40); minimumHeight = dp(40)
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { details(item) }
            }
            btnRow.addView(infoBtn, LinearLayout.LayoutParams(-2, dp(40)))
            infoCol.addView(btnRow)
        }

        frame.addView(infoCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val height = dp((resources.configuration.screenHeightDp * .61f).toInt().coerceIn(360, 520))
        content.addView(frame, 0, LinearLayout.LayoutParams(-1, height).apply {
            setMargins(gutter, dp(2), gutter, dp(14))
        })
    }

    private fun televisionHero(item: Media, showActions: Boolean) {
        val frame = FrameLayout(this).apply {
            id = R.id.featured_card
            clipToOutline = true
        }
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            load(api.absolute(item.backdrop.ifBlank { item.poster })) { crossfade(true); error(R.drawable.ic_homestream) }
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(INK, 0xCC080808.toInt(), 0x00080808)
            )
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00080808, 0x00080808, INK)
            )
        }, FrameLayout.LayoutParams(-1, -1))

        val copy = column().apply { setPadding(gutter, dp(32), dp(16), dp(24)) }
        copy.addView(label("H O M E S T R E A M   •   FEATURED", 12f, true).apply { setTextColor(MUTED) })
        copy.addView(label(item.title, 36f, true).apply { maxLines = 2 })
        copy.addView(label(item.metadata, 14f).apply { setPadding(0, dp(8), 0, dp(8)); setTextColor(MUTED) })
        copy.addView(label(item.overview, 15f).apply { maxLines = 3 })

        if (showActions) {
            val actions = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
            val canPlay = item.isAvailableForPlayback
            val playText = if (canPlay) "▶ Play" else "▶ Stream"
            actions.addView(action(playText, true) {
                if (canPlay && item.type != "Series") play(item)
                else if (!canPlay) streamMedia(item)
                else details(item)
            })
            actions.addView(action("More Info") { details(item) })
            copy.addView(actions)
        }

        val copyWidth = dp(((resources.configuration.screenWidthDp - 68) * .56f).toInt())
        frame.addView(copy, FrameLayout.LayoutParams(copyWidth, -2, Gravity.CENTER_VERTICAL))

        val height = dp((resources.configuration.screenHeightDp * .70f).toInt().coerceIn(330, 480))
        content.addView(frame, 0, LinearLayout.LayoutParams(-1, height))
    }

    // -----------------------------------------------------------------------------------------
    // Media Rows & Cards
    // -----------------------------------------------------------------------------------------
    private fun mediaRow(title: String, items: List<Media>) {
        mediaRow(title, items, isContinueWatching = false)
    }

    private fun mediaRow(title: String, items: List<Media>, isContinueWatching: Boolean = false) {
        if (items.isEmpty()) return
        heading(title)
        val row = LinearLayout(this).apply { setPadding(gutter, dp(6), gutter, dp(10)); clipChildren = false }

        items.forEach { item ->
            val card = column().apply {
                setPadding(dp(4), dp(4), dp(4), dp(6)); focusableCard()
                contentDescription = "${item.title}, ${item.type}, ${item.metadata}"
                setOnClickListener {
                    // Tapping Continue Watching jumps straight to playback at saved position!
                    if (isContinueWatching) {
                        play(item)
                    } else {
                        details(item)
                    }
                }
            }

            val posterContainer = FrameLayout(this).apply {
                background = rounded(0xFF222222.toInt(), radius = dp(6).toFloat())
                clipToOutline = true
            }

            val posterImg = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(api.absolute(if (isTelevision()) item.backdrop else item.poster)) {
                    crossfade(true); error(R.drawable.ic_homestream)
                }
            }
            posterContainer.addView(posterImg, FrameLayout.LayoutParams(-1, -1))

            // Progress Bar for Continue Watching items
            val progressVal = item.json.optDouble("progressPercent")
            if (progressVal > 0) {
                val pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = progressVal.toInt()
                    progressTintList = ColorStateList.valueOf(RED)
                }
                posterContainer.addView(pBar, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))
            }

            val cardWidth = dp(if (isTelevision()) 200 else 115)
            val cardHeight = dp(if (isTelevision()) 115 else 165)
            card.addView(posterContainer, LinearLayout.LayoutParams(cardWidth, cardHeight))

            // Card title under poster
            card.addView(label(item.title, if (isTelevision()) 12.5f else 11f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(5), dp(2), 0)
            })

            row.addView(card, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(10), 0) })
        }

        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; addView(row)
        })
    }

    // -----------------------------------------------------------------------------------------
    // Full Netflix Media Details Modal / Screen
    // -----------------------------------------------------------------------------------------
    private fun details(item: Media) {
        heroRotationJob?.cancel()
        lastDetails = item
        screen = "details"
        fetch("media/${android.net.Uri.encode(item.id)}") { data ->
            val full = Media(data.getJSONObject("item"))

            // 1. Trailer / Preview Header Area
            val headerArea = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(0xFF161616.toInt()) }
                clipToOutline = true
            }

            val backdropView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(api.absolute(full.backdrop.ifBlank { full.poster })) {
                    crossfade(true); error(R.drawable.ic_homestream)
                }
            }
            headerArea.addView(backdropView, FrameLayout.LayoutParams(-1, -1))

            // Check and load YouTube trailer preview if available
            val trailerKey = full.trailerKey.ifBlank {
                data.optJSONObject("item")?.optString("trailerKey").orEmpty()
            }

            if (trailerKey.isNotBlank()) {
                val webView = WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    val html = """
                        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden;}iframe{width:100%;height:100%;border:none;}</style>
                        </head><body>
                        <iframe src="https://www.youtube-nocookie.com/embed/$trailerKey?autoplay=1&mute=1&controls=0&loop=1&playlist=$trailerKey&modestbranding=1&rel=0" allow="autoplay; fullscreen"></iframe>
                        </body></html>
                    """.trimIndent()
                    loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null)
                }
                headerArea.addView(webView, FrameLayout.LayoutParams(-1, -1))
            }

            // Close button (X) in top right corner
            val closeBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_close); imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = rounded(0x88000000.toInt(), radius = dp(18).toFloat())
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { loadTab(currentTab) }
            }
            headerArea.addView(closeBtn, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
                setMargins(dp(12), dp(12), dp(12), dp(12))
            })

            content.addView(headerArea, LinearLayout.LayoutParams(-1, dp(230)))

            // 2. Metadata Block
            val metaSection = column().apply { setPadding(gutter, dp(14), gutter, dp(12)) }
            metaSection.addView(label(full.title, 24f, true))

            val badges = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(10)) }
            if (full.json.optString("rating").isNotBlank()) badges.addView(badgePill(full.json.optString("rating")))
            val year = full.json.optString("year").takeUnless { it == "0" }.orEmpty()
            if (year.isNotBlank()) badges.addView(TextView(this).apply { text = "  •  $year"; textSize = 11.5f; setTextColor(MUTED) })
            val res = full.json.optString("resolution")
            if (res.isNotBlank()) badges.addView(TextView(this).apply { text = "  •  $res"; textSize = 11.5f; setTextColor(0xFF10B981.toInt()) })
            val duration = full.json.optInt("durationMinutes")
            if (duration > 0) badges.addView(TextView(this).apply { text = "  •  ${duration}m"; textSize = 11.5f; setTextColor(MUTED) })
            metaSection.addView(badges)

            // Primary Play / Stream Button
            val playBtn = Button(this).apply {
                val canPlay = full.isAvailableForPlayback
                val resume = full.resumeMs > 0
                text = if (canPlay) (if (resume) "▶  Resume" else "▶  Play") else "▶  Stream"
                textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (canPlay) Color.BLACK else Color.WHITE); isAllCaps = false
                background = rounded(if (canPlay) Color.WHITE else RED, radius = dp(8).toFloat())
                minHeight = dp(46); minimumHeight = dp(46)
                setOnClickListener {
                    if (canPlay) {
                        play(full)
                    } else {
                        streamMedia(full)
                    }
                }
            }
            metaSection.addView(playBtn, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })

            // Secondary Buttons (+ My List, Download)
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val list = Watchlist(this, settings)
            val listBtn = Button(this).apply {
                text = if (list.contains(full)) "✓ My List" else "+ My List"; textSize = 12.5f
                setTextColor(Color.WHITE); isAllCaps = false
                background = rounded(0x33FFFFFF.toInt(), radius = dp(8).toFloat())
                minHeight = dp(40); minimumHeight = dp(40)
                setOnClickListener { text = if (list.toggle(full)) "✓ My List" else "+ My List" }
            }
            btnRow.addView(listBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (full.isAvailableForPlayback) marginEnd = dp(8)
            })

            // Download Button (aligned to right) - ONLY shown for currently downloaded titles!
            if (full.isAvailableForPlayback) {
                val dlBtn = Button(this).apply {
                    text = "⬇ Download"; textSize = 12.5f
                    setTextColor(Color.WHITE); isAllCaps = false
                    background = rounded(0x33FFFFFF.toInt(), radius = dp(8).toFloat())
                    minHeight = dp(40); minimumHeight = dp(40)
                    setOnClickListener { triggerDownload(full) }
                }
                btnRow.addView(dlBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
            metaSection.addView(btnRow)

            // Synopsis
            metaSection.addView(TextView(this).apply {
                text = full.overview; textSize = 13f; setTextColor(0xFFCCCCCC.toInt())
                setPadding(0, dp(12), 0, dp(14)); setLineSpacing(dp(2).toFloat(), 1f)
            })

            content.addView(metaSection)

            // 3. Details Sub-Tabs (Episodes/More Like This for TV, Collection/More Like This for Movies)
            renderDetailsSubTabs(full)
        }
    }

    private fun renderDetailsSubTabs(full: Media) {
        val tabContainer = column().apply { setPadding(gutter, dp(6), gutter, dp(16)) }
        val tabHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val isTv = full.type == "Series"
        val tab1Title = if (isTv) "Episodes" else "Collection"
        val tab2Title = "More Like This"

        val subContent = column()

        var selectedTab = 0

        fun updateTabs() {
            tabHeader.removeAllViews()
            val t1 = TextView(this).apply {
                text = tab1Title; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (selectedTab == 0) Color.WHITE else MUTED)
                setPadding(0, dp(8), dp(20), dp(8))
                setOnClickListener { selectedTab = 0; updateTabs() }
            }
            val t2 = TextView(this).apply {
                text = tab2Title; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (selectedTab == 1) Color.WHITE else MUTED)
                setPadding(dp(10), dp(8), dp(20), dp(8))
                setOnClickListener { selectedTab = 1; updateTabs() }
            }
            tabHeader.addView(t1)
            tabHeader.addView(t2)

            subContent.removeAllViews()
            if (selectedTab == 0) {
                if (isTv) renderEpisodesTab(full, subContent) else renderCollectionTab(full, subContent)
            } else {
                renderMoreLikeThisTab(full, subContent)
            }
        }

        tabContainer.addView(tabHeader)
        tabContainer.addView(subContent)
        content.addView(tabContainer)
        updateTabs()
    }

    private fun renderEpisodesTab(full: Media, container: LinearLayout) {
        val seasons = full.json.optJSONArray("seasons")
        if (seasons == null || seasons.length() == 0) {
            container.addView(TextView(this).apply {
                text = "No episode data available for this show."; textSize = 13f; setTextColor(MUTED)
                setPadding(0, dp(14), 0, dp(14))
            })
            return
        }

        // Season Selector Pills (Season 1, Season 2, etc.)
        val seasonPillsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val seasonPills = LinearLayout(this).apply { setPadding(0, dp(10), 0, dp(14)) }
        var currentSeasonIndex = 0

        val episodesContainer = column()

        fun showEpisodesForSeason(seasonIdx: Int) {
            currentSeasonIndex = seasonIdx
            seasonPills.removeAllViews()
            for (i in 0 until seasons.length()) {
                val sObj = seasons.getJSONObject(i)
                val sName = sObj.optString("name", "Season ${i + 1}")
                val pill = action(sName, primary = (i == currentSeasonIndex)) {
                    showEpisodesForSeason(i)
                }.apply {
                    textSize = 12f; minHeight = dp(34); minimumHeight = dp(34)
                    setPadding(dp(12), 0, dp(12), 0)
                    background = rounded(if (i == currentSeasonIndex) Color.WHITE else 0x33FFFFFF.toInt(), radius = dp(16).toFloat())
                    setTextColor(if (i == currentSeasonIndex) Color.BLACK else Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) }
                }
                seasonPills.addView(pill)
            }

            episodesContainer.removeAllViews()
            val seasonObj = seasons.getJSONObject(currentSeasonIndex)
            val episodes = seasonObj.mediaList("episodes")

            if (episodes.isEmpty()) {
                episodesContainer.addView(TextView(this).apply {
                    text = "No episodes found for this season."; textSize = 13f; setTextColor(MUTED)
                    setPadding(0, dp(10), 0, dp(10))
                })
                return
            }

            episodes.forEach { ep ->
                val canPlayEp = ep.isAvailableForPlayback
                val epCard = column().apply {
                    background = rounded(0x11FFFFFF.toInt(), radius = dp(8).toFloat())
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    focusableCard()
                    setOnClickListener {
                        if (canPlayEp) {
                            play(ep)
                        } else {
                            streamMedia(ep, full)
                        }
                    }
                }

                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }

                // 16:9 Episode Thumbnail with duration badge and play icon
                val thumbBox = FrameLayout(this).apply {
                    background = rounded(0xFF222222.toInt(), radius = dp(6).toFloat())
                    clipToOutline = true
                }
                val thumbImg = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    load(api.absolute(ep.poster.ifBlank { full.backdrop })) {
                        crossfade(true); error(R.drawable.ic_homestream)
                    }
                }
                thumbBox.addView(thumbImg, FrameLayout.LayoutParams(-1, -1))

                // Play icon overlay
                val playOverlay = ImageView(this).apply {
                    setImageResource(R.drawable.ic_play_arrow)
                    background = rounded(if (canPlayEp) 0x88000000.toInt() else 0xCCE50914.toInt(), radius = dp(14).toFloat())
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                thumbBox.addView(playOverlay, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))

                val duration = ep.json.optInt("durationMinutes").takeIf { it > 0 } ?: 45
                val durBadge = TextView(this).apply {
                    text = "${duration}m"; textSize = 9.5f; setTextColor(Color.WHITE)
                    background = rounded(0x99000000.toInt(), radius = dp(4).toFloat())
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                }
                thumbBox.addView(durBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(4), dp(4))
                })

                topRow.addView(thumbBox, LinearLayout.LayoutParams(dp(125), dp(72)))

                // Episode Number, Title, Overview
                val titleCol = column().apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, dp(8), 0) }
                }
                val epNum = ep.json.optInt("episodeNumber", 1)
                titleCol.addView(TextView(this).apply {
                    text = "$epNum. ${ep.title}"; textSize = 13.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                titleCol.addView(TextView(this).apply {
                    text = ep.overview; textSize = 11.5f; setTextColor(MUTED)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
                topRow.addView(titleCol)

                // Episode Download Button (aligned to right) - ONLY shown for currently downloaded episodes!
                if (canPlayEp) {
                    val dlBtn = ImageView(this).apply {
                        setImageResource(R.drawable.ic_download)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                        background = rounded(0x22FFFFFF.toInt(), radius = dp(18).toFloat())
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        isClickable = true
                        contentDescription = "Download episode"
                        setOnClickListener { triggerDownload(ep) }
                    }
                    topRow.addView(dlBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    })
                }

                epCard.addView(topRow, LinearLayout.LayoutParams(-1, -2))
                episodesContainer.addView(epCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }

        seasonPillsScroll.addView(seasonPills)
        container.addView(seasonPillsScroll)
        container.addView(episodesContainer)
        showEpisodesForSeason(0)
    }

    private fun renderCollectionTab(full: Media, container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = "Loading movie collection…"; textSize = 13f; setTextColor(MUTED)
            setPadding(0, dp(14), 0, dp(14))
        })

        lifecycleScope.launch {
            try {
                val colData = api.call("media/${android.net.Uri.encode(full.id)}/collection")
                val col = colData.optJSONObject("collection")
                val parts = col?.mediaList("parts").orEmpty()
                container.removeAllViews()

                if (parts.isNotEmpty()) {
                    val colTitle = col?.optString("name", "Movie Collection") ?: "Movie Collection"
                    container.addView(label(colTitle, 16f, true).apply { setPadding(0, dp(6), 0, dp(8)) })

                    val grid = GridLayout(this@MainActivity).apply {
                        columnCount = if (isTelevision()) 5 else 3
                    }
                    parts.forEach { part ->
                        val card = column().apply {
                            setPadding(dp(4), dp(4), dp(4), dp(6)); focusableCard()
                            setOnClickListener { details(part) }
                        }
                        val img = ImageView(this@MainActivity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            load(api.absolute(part.poster)) { crossfade(true); error(R.drawable.ic_homestream) }
                        }
                        card.addView(img, LinearLayout.LayoutParams(dp(100), dp(145)))
                        card.addView(label(part.title, 11f, bold = true).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dp(2), dp(4), dp(2), 0)
                        })
                        grid.addView(card)
                    }
                    container.addView(grid)
                } else {
                    container.addView(TextView(this@MainActivity).apply {
                        text = "This movie is not part of a boxset collection."; textSize = 13f; setTextColor(MUTED)
                        setPadding(0, dp(14), 0, dp(14))
                    })
                }
            } catch (_: Exception) {
                container.removeAllViews()
                container.addView(TextView(this@MainActivity).apply {
                    text = "No collection information available."; textSize = 13f; setTextColor(MUTED)
                })
            }
        }
    }

    private fun renderMoreLikeThisTab(full: Media, container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = "Loading recommendations…"; textSize = 13f; setTextColor(MUTED)
            setPadding(0, dp(14), 0, dp(14))
        })

        lifecycleScope.launch {
            try {
                val params = mutableMapOf<String, String>()
                params["type"] = if (full.type == "Series" || full.type == "Episode") "tv" else "movie"
                val tmdb = full.json.optInt("tmdbId").takeIf { it > 0 } ?: full.tmdbId
                if (tmdb > 0) {
                    params["tmdbId"] = tmdb.toString()
                }
                val recData = api.call("media/${android.net.Uri.encode(full.id)}/recommendations", params)
                val recs = recData.mediaList("recommendations")
                container.removeAllViews()

                if (recs.isNotEmpty()) {
                    val grid = GridLayout(this@MainActivity).apply {
                        columnCount = if (isTelevision()) 5 else 3
                    }
                    recs.forEach { rec ->
                        val card = column().apply {
                            setPadding(dp(4), dp(4), dp(4), dp(6)); focusableCard()
                            setOnClickListener { details(rec) }
                        }
                        val posterBox = FrameLayout(this@MainActivity).apply {
                            background = rounded(0xFF222222.toInt(), radius = dp(6).toFloat())
                            clipToOutline = true
                        }
                        val img = ImageView(this@MainActivity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            load(api.absolute(rec.poster)) { crossfade(true); error(R.drawable.ic_homestream) }
                        }
                        posterBox.addView(img, FrameLayout.LayoutParams(-1, -1))

                        // If item is from Seerr / not in local library, show a neat Request badge
                        if (!rec.playable && (rec.id.startsWith("seerr-") || rec.source == "seerr")) {
                            val reqBadge = TextView(this@MainActivity).apply {
                                text = "Request"; textSize = 8.5f; setTextColor(Color.WHITE)
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                background = rounded(RED, radius = dp(4).toFloat())
                                setPadding(dp(4), dp(1), dp(4), dp(1))
                            }
                            posterBox.addView(reqBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                                setMargins(dp(4), dp(4), 0, 0)
                            })
                        }

                        card.addView(posterBox, LinearLayout.LayoutParams(dp(100), dp(145)))
                        card.addView(label(rec.title, 11f, bold = true).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(dp(2), dp(4), dp(2), 0)
                        })
                        grid.addView(card)
                    }
                    container.addView(grid)
                } else {
                    container.addView(TextView(this@MainActivity).apply {
                        text = "No recommendations found for this title."; textSize = 13f; setTextColor(MUTED)
                        setPadding(0, dp(14), 0, dp(14))
                    })
                }
            } catch (_: Exception) {
                container.removeAllViews()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // In-Page Netflix Search (No AlertDialog)
    // -----------------------------------------------------------------------------------------
    private fun openSearch() {
        heroRotationJob?.cancel()
        screen = "search"
        content.removeAllViews()

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF222222.toInt(), radius = dp(8).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.nav_search); imageTintList = ColorStateList.valueOf(MUTED)
        }
        searchBar.addView(searchIcon, LinearLayout.LayoutParams(dp(22), dp(22)))

        val searchInput = EditText(this).apply {
            hint = "Search movies, series, requests…"; textSize = 14f; setTextColor(Color.WHITE)
            setHintTextColor(MUTED); background = null; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(10), 0, dp(10), 0) }
        }
        searchBar.addView(searchInput)

        val clearBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); imageTintList = ColorStateList.valueOf(MUTED)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { searchInput.setText("") }
        }
        searchBar.addView(clearBtn, LinearLayout.LayoutParams(dp(26), dp(26)))

        content.addView(searchBar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(gutter, dp(12), gutter, dp(16))
        })

        val resultsContainer = column()
        content.addView(resultsContainer)

        var searchJob: Job? = null
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                if (q.isBlank()) {
                    resultsContainer.removeAllViews()
                    return
                }
                searchJob = lifecycleScope.launch {
                    delay(300)
                    try {
                        val res = api.call("search", mapOf("q" to q))
                        val items = res.mediaList("results")
                        resultsContainer.removeAllViews()
                        if (items.isEmpty()) {
                            resultsContainer.addView(TextView(this@MainActivity).apply {
                                text = "No matching titles found for “$q”."; textSize = 13.5f; setTextColor(MUTED)
                                setPadding(gutter, dp(16), gutter, dp(16))
                            })
                        } else {
                            val available = items.filter { it.playable }
                            val toRequest = items.filter { !it.playable }
                            if (available.isNotEmpty()) mediaRow("In Your Library", available)
                            if (toRequest.isNotEmpty()) mediaRow("Available to Request", toRequest)
                        }
                    } catch (_: Exception) {}
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.requestFocus()
    }

    // -----------------------------------------------------------------------------------------
    // A–Z Library Catalog View
    // -----------------------------------------------------------------------------------------
    private fun loadCatalog(type: String, selectedLetter: String = "All") {
        screen = "catalog-$type"
        fetch("library/$type") { data ->
            val title = when (type) {
                "movies" -> "Movies (A–Z)"
                "tv" -> "TV Shows (A–Z)"
                else -> "YouTube (A–Z)"
            }
            val allItems = data.mediaList("items")

            heading("$title (${allItems.size})", 22f)

            // Alphabet Filter Bar: All, #, A-Z
            val alphabet = listOf("All", "#") + ('A'..'Z').map { it.toString() }
            val alphaScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val alphaRow = LinearLayout(this).apply { setPadding(gutter, dp(4), gutter, dp(14)) }

            alphabet.forEach { letter ->
                val pill = action(letter, primary = (letter == selectedLetter)) {
                    loadCatalog(type, letter)
                }.apply {
                    textSize = 12f; minHeight = dp(32); minimumHeight = dp(32)
                    setPadding(dp(12), 0, dp(12), 0)
                    background = rounded(if (letter == selectedLetter) RED else 0x33FFFFFF.toInt(), radius = dp(16).toFloat())
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(6) }
                }
                alphaRow.addView(pill)
            }
            alphaScroll.addView(alphaRow)
            content.addView(alphaScroll)

            // Filter items by selected letter
            val filtered = if (selectedLetter == "All") {
                allItems
            } else if (selectedLetter == "#") {
                allItems.filter { it.title.firstOrNull()?.isLetter() != true }
            } else {
                allItems.filter { it.title.startsWith(selectedLetter, ignoreCase = true) }
            }

            if (filtered.isEmpty()) {
                message("No titles found under '$selectedLetter'.")
            } else {
                val grid = GridLayout(this).apply {
                    columnCount = if (isTelevision()) 5 else 3
                    setPadding(gutter, dp(4), gutter, dp(16))
                }
                filtered.forEach { item ->
                    val card = column().apply {
                        setPadding(dp(4), dp(4), dp(4), dp(6)); focusableCard()
                        setOnClickListener { details(item) }
                    }
                    val img = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        load(api.absolute(item.poster)) { crossfade(true); error(R.drawable.ic_homestream) }
                    }
                    card.addView(img, LinearLayout.LayoutParams(dp(100), dp(145)))
                    card.addView(label(item.title, 11f, bold = true).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(2), dp(4), dp(2), 0)
                    })
                    grid.addView(card)
                }
                content.addView(grid)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Categories & Other Helpers
    // -----------------------------------------------------------------------------------------
    private fun showCategoriesMenu() {
        val options = arrayOf("Movie Categories", "TV Categories", "Active Downloads", "My List")
        AlertDialog.Builder(this).setTitle("Browse Categories").setItems(options) { _, idx ->
            when (idx) {
                0 -> loadCategories("movies")
                1 -> loadCategories("tv")
                2 -> loadDownloads()
                else -> myHomeStream()
            }
        }.show()
    }

    private fun loadCategories(type: String) {
        screen = "categories-$type"
        fetch("categories/$type") { data ->
            heading(if (type == "movies") "Movie Categories" else "TV Categories")
            val sections = data.optJSONArray("sections")
            if (sections != null && sections.length() > 0) {
                for (i in 0 until sections.length()) {
                    val sec = sections.getJSONObject(i)
                    mediaRow(sec.optString("title"), sec.mediaList("items"))
                }
            } else message("No categories found.")
        }
    }

    private fun loadDownloads() {
        screen = "downloads"
        fetch("downloads") { data ->
            heading("Active Downloads")
            val items = data.mediaList("items")
            if (items.isEmpty()) message("No active torrent downloads.")
            else mediaRow("Downloading via qBittorrent", items)
        }
    }

    private fun myHomeStream() {
        screen = "mine"
        fetch("home") { data ->
            heading(if (isTelevision()) "My List" else "My HomeStream", 26f)
            val saved = Watchlist(this, settings).items()
            if (saved.isEmpty()) message("Add titles to My List to watch them later.")
            else mediaRow("My List", saved)
            data.optJSONArray("sections")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    if (row.optString("id") == "continue-watching") {
                        mediaRow("Continue Watching", row.mediaList("items"), isContinueWatching = true)
                    }
                }
            }
        }
    }

    private fun requestMediaTitle(media: Media, onSuccess: (() -> Unit)? = null) {
        val tmdb = if (media.tmdbId > 0) media.tmdbId else {
            val strId = media.id.replace("^seerr-(?:movie-|tv-)?".toRegex(), "")
            strId.toIntOrNull() ?: 0
        }
        if (tmdb <= 0) {
            toast("Cannot request: TMDB ID not found for this title.")
            return
        }
        lifecycleScope.launch {
            try {
                toast("Sending download request…")
                val body = JSONObject()
                    .put("mediaType", if (media.type == "Series" || media.type == "tv") "tv" else "movie")
                    .put("mediaId", tmdb)
                val res = api.call("request", body = body)
                toast(res.optString("message", "Request sent successfully! It will download automatically."))
                onSuccess?.invoke()
            } catch (e: Exception) {
                toast(e.message ?: "Request failed")
            }
        }
    }

    // Native device download via DownloadManager
    private fun triggerDownload(item: Media) {
        lifecycleScope.launch {
            try {
                toast("Preparing download: ${item.title}…")
                val streamData = api.call("playback", mapOf("itemId" to item.playbackId))
                val streamUrl = streamData.optString("streamUrl")
                if (streamUrl.isNotBlank()) {
                    val token = settings.token
                    val downloadUri = if (token.isNotBlank() && (streamUrl.startsWith("/api/") || !streamUrl.contains("api_key="))) {
                        Uri.parse(api.absolute(streamUrl)).buildUpon().appendQueryParameter("token", token).build()
                    } else {
                        Uri.parse(api.absolute(streamUrl))
                    }
                    val fileName = "${item.title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")}.mp4"
                    val request = DownloadManager.Request(downloadUri)
                        .setTitle(item.title)
                        .setDescription("HomeStream Download")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, fileName)
                        .apply {
                            if (token.isNotBlank()) addRequestHeader("Authorization", "Bearer $token")
                        }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    toast("Download started: ${item.title}")
                } else {
                    toast("Unable to get playable download stream.")
                }
            } catch (e: Exception) {
                toast(e.message ?: "Download failed")
            }
        }
    }

    private fun streamMedia(item: Media, parent: Media? = null) {
        val tmdb = if (item.tmdbId > 0) item.tmdbId else if (parent != null && parent.tmdbId > 0) parent.tmdbId else {
            val rawId = (if (item.id.startsWith("seerr-")) item.id else parent?.id ?: item.id)
                .replace("^seerr-(?:movie-|tv-)?".toRegex(), "")
            rawId.toIntOrNull() ?: 0
        }
        val isTv = item.type == "Series" || item.type == "Episode" || (parent != null && parent.type == "Series")
        val mediaType = if (isTv) "tv" else "movie"
        val seasonNum = item.json.optInt("seasonNumber", 1).takeIf { it > 0 } ?: 1
        val episodeNum = item.json.optInt("episodeNumber", 1).takeIf { it > 0 } ?: 1

        if (tmdb > 0) {
            lifecycleScope.launch {
                try {
                    val body = JSONObject()
                        .put("mediaType", mediaType)
                        .put("mediaId", tmdb)
                    if (isTv) {
                        body.put("seasons", org.json.JSONArray().put(seasonNum))
                    }
                    api.call("request", body = body)
                } catch (e: Exception) {
                    android.util.Log.w("HomeStream", "Stream request error: ${e.message}")
                }
            }
        }

        playbackWasLaunched = true
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("media", item.json.toString())
            putExtra("isStreamingDownload", true)
            putExtra("mediaType", mediaType)
            putExtra("tmdbId", tmdb)
            if (isTv) {
                putExtra("season", seasonNum)
                putExtra("episode", episodeNum)
                val seriesName = parent?.title ?: item.title
                putExtra("seriesTitle", seriesName)
            }
        }
        startActivity(intent)
    }

    private fun play(item: Media, fromBeginning: Boolean = false) {
        playbackWasLaunched = true
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("media", item.json.toString())
            .putExtra("fromBeginning", fromBeginning))
    }

    private fun profiles() {
        lifecycleScope.launch {
            try {
                val profiles = api.call("profiles").optJSONArray("profiles")
                if (profiles == null || profiles.length() == 0) {
                    toast("Create profiles in the HomeStream web app first."); return@launch
                }
                val names = Array(profiles.length()) { profiles.getJSONObject(it).optString("name") }
                AlertDialog.Builder(this@MainActivity).setTitle("Who's watching?").setItems(names) { _, index ->
                    settings.profile = profiles.getJSONObject(index).getString("id"); loadTab(currentTab)
                }.show()
            } catch (e: Exception) { toast(e.message ?: "Could not load profiles") }
        }
    }

    private fun openServerSettingsDialog() {
        val form = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }

        form.addView(label("Connected Server", 16f, true).apply { setPadding(0, 0, 0, dp(6)) })

        val currentLocal = settings.localUrl.ifBlank { settings.baseUrl }
        val infoBox = column().apply {
            background = rounded(0x18FFFFFF.toInt(), radius = dp(8).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        infoBox.addView(TextView(this).apply {
            text = "Local Server: ${currentLocal.ifBlank { "Not configured" }}"; textSize = 12f; setTextColor(Color.WHITE)
        })
        val authStatus = if (settings.authType == "device") "Device Pairing Token (Keystore secured)"
            else if (settings.authType == "jellyfin") "Jellyfin Account Authentication" else "Pre-shared Mobile Token"
        infoBox.addView(TextView(this).apply {
            text = "Authentication: $authStatus"; textSize = 11f; setTextColor(0xFF10B981.toInt()); setPadding(0, dp(4), 0, dp(2))
        })
        form.addView(infoBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // External URL Field
        form.addView(label("External Server URL (Remote Access)", 13.5f, true))
        form.addView(TextView(this).apply {
            text = "Set an external reverse proxy / domain (e.g. https://homeflix.example.com) to access your server remotely with your existing device authentication."; textSize = 11f; setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(6))
        })
        val extInput = EditText(this).apply {
            hint = "https://homeflix.example.com"
            setText(settings.externalUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        form.addView(extInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Server & Remote Settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save External URL") { _, _ ->
                val ext = extInput.text.toString().trim()
                settings.updateExternalUrl(ext)
                toast(if (ext.isNotBlank()) "External URL saved: $ext" else "External URL cleared")
            }
            .setNeutralButton("Switch Server") { _, _ -> setup() }
            .setNegativeButton("Close", null)
            .create()

        dialog.show()
    }

    private fun openJellyfinLoginDialog() {
        val form = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }

        form.addView(label("External HomeStream URL", 13.5f, true))
        val extUrlInput = EditText(this).apply {
            hint = "https://homeflix.example.com"
            setText(settings.externalUrl.ifBlank { if (settings.baseUrl.startsWith("https")) settings.baseUrl else "" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        form.addView(extUrlInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val usersBox = column().apply { visibility = View.GONE }
        val usersLabel = label("Select User", 13.5f, true)
        val usersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(8))
        }
        val usersScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(usersContainer) }
        usersBox.addView(usersLabel)
        usersBox.addView(usersScroll)
        form.addView(usersBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val usernameInput = EditText(this).apply {
            hint = "Jellyfin username"; isSingleLine = true
        }
        val passwordInput = EditText(this).apply {
            hint = "Jellyfin password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true
        }
        form.addView(label("Username", 13.5f, true)); form.addView(usernameInput)
        form.addView(label("Password", 13.5f, true)); form.addView(passwordInput)

        // Helper function to fetch users from the entered URL
        fun fetchJellyfinUsers(serverUrl: String) {
            val clean = serverUrl.trim().trimEnd('/')
            if (clean.isBlank()) return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val u = java.net.URL("$clean/api/mobile/v1/auth/users")
                    val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 4000; readTimeout = 4000
                    }
                    if (conn.responseCode == 200) {
                        val respText = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(respText)
                        val uArr = json.optJSONArray("users")
                        if (uArr != null && uArr.length() > 0) {
                            withContext(Dispatchers.Main) {
                                usersContainer.removeAllViews()
                                for (i in 0 until uArr.length()) {
                                    val uObj = uArr.getJSONObject(i)
                                    val uName = uObj.optString("name")
                                    val uCard = LinearLayout(this@MainActivity).apply {
                                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                                        setPadding(dp(8), dp(4), dp(8), dp(4))
                                        background = rounded(0x22FFFFFF.toInt(), radius = dp(8).toFloat())
                                        isClickable = true
                                        setOnClickListener {
                                            usernameInput.setText(uName)
                                            passwordInput.requestFocus()
                                        }
                                    }
                                    val avatar = FrameLayout(this@MainActivity).apply {
                                        background = rounded(RED, radius = dp(18).toFloat())
                                        addView(TextView(this@MainActivity).apply {
                                            this.text = uName.take(2).uppercase(); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            setTextColor(Color.WHITE); gravity = Gravity.CENTER
                                        }, FrameLayout.LayoutParams(-1, -1))
                                    }
                                    uCard.addView(avatar, LinearLayout.LayoutParams(dp(36), dp(36)))
                                    uCard.addView(TextView(this@MainActivity).apply {
                                        this.text = uName; textSize = 11f; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, 0)
                                    })
                                    usersContainer.addView(uCard, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                                }
                                usersBox.visibility = View.VISIBLE
                                if (usernameInput.text.isBlank() && uArr.length() > 0) {
                                    usernameInput.setText(uArr.getJSONObject(0).optString("name"))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        extUrlInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { fetchJellyfinUsers(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        if (extUrlInput.text.isNotBlank()) fetchJellyfinUsers(extUrlInput.text.toString())

        val jDialog = AlertDialog.Builder(this)
            .setTitle("Jellyfin Sign In")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Sign In", null)
            .setNegativeButton("Cancel", null)
            .create()

        jDialog.setOnShowListener {
            val signBtn = jDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            signBtn.setOnClickListener {
                val cleanUrl = extUrlInput.text.toString().trim().trimEnd('/')
                val username = usernameInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (cleanUrl.isBlank()) { extUrlInput.error = "Enter server URL"; return@setOnClickListener }
                if (username.isBlank()) { usernameInput.error = "Enter username"; return@setOnClickListener }

                signBtn.isEnabled = false; signBtn.text = "Signing in…"
                val devId = runCatching {
                    android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                }.getOrNull() ?: java.util.UUID.randomUUID().toString()
                val devName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val u = java.net.URL("$cleanUrl/api/mobile/v1/auth/login")
                        val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 8000; readTimeout = 8000
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        val body = JSONObject().put("username", username).put("password", password)
                            .put("deviceName", devName).put("deviceId", devId)
                        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

                        if (conn.responseCode == 200) {
                            val text = conn.inputStream.bufferedReader().use { it.readText() }
                            val resp = JSONObject(text)
                            val token = resp.optString("token")
                            val userObj = resp.optJSONObject("user")
                            withContext(Dispatchers.Main) {
                                settings.saveServer(local = "", external = cleanUrl, token = token, type = "jellyfin", name = userObj?.optString("name", username) ?: username)
                                userObj?.optString("id")?.let { if (it.isNotBlank()) settings.profile = it }
                                toast("Signed in as $username!")
                                jDialog.dismiss()
                                loadTab("home")
                            }
                        } else {
                            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            val errJson = runCatching { JSONObject(errText) }.getOrNull()
                            val msg = errJson?.optString("error") ?: "Authentication failed (${conn.responseCode})"
                            withContext(Dispatchers.Main) {
                                passwordInput.error = msg
                                signBtn.isEnabled = true; signBtn.text = "Sign In"
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            passwordInput.error = e.message ?: "Connection error"
                            signBtn.isEnabled = true; signBtn.text = "Sign In"
                        }
                    }
                }
            }
        }
        jDialog.show()
    }

    private fun setup() {
        val first = settings.baseUrl.isBlank()
        if (first) { shell(); heading("Your cinema. Your collection."); message("Connect to your HomeStream server to begin.") }
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }

        // 1. Local Auto-Discovery Section
        val discoverySection = column().apply {
            background = rounded(0x15FFFFFF.toInt(), radius = dp(8).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val discTitleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        discTitleRow.addView(TextView(this).apply {
            text = "LOCAL SERVERS DISCOVERED"; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(RED); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val scanStatus = TextView(this).apply {
            text = "🔍 Scanning network…"; textSize = 10.5f; setTextColor(MUTED)
        }
        discTitleRow.addView(scanStatus)
        discoverySection.addView(discTitleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val discoveredServersContainer = column()
        discoverySection.addView(discoveredServersContainer)
        form.addView(discoverySection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // 2. Remote / External Server Option (Jellyfin Auth)
        val jellyfinRemoteBtn = Button(this).apply {
            text = "🌐 Connect External Server (Jellyfin Sign In)"; textSize = 12.5f
            setTextColor(Color.WHITE); isAllCaps = false
            background = rounded(0x28FFFFFF.toInt(), radius = dp(8).toFloat())
            minHeight = dp(40); minimumHeight = dp(40)
            setOnClickListener { openJellyfinLoginDialog() }
        }
        form.addView(jellyfinRemoteBtn, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(14) })

        // 3. Manual Server Setup (Preserving test compatibility & direct pairing token)
        form.addView(TextView(this).apply {
            text = "MANUAL SERVER CONNECTION"; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(MUTED); setPadding(0, 0, 0, dp(4))
        })
        val url = EditText(this).apply {
            hint = "https://homestream.example.com"; setText(settings.baseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true
        }
        val token = EditText(this).apply {
            hint = "Server pairing token"; setText(settings.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; isSingleLine = true
        }
        form.addView(label("HomeStream server URL", 13f)); form.addView(url)
        form.addView(label("HOMESTREAM_MOBILE_TOKEN", 13f)); form.addView(token)
        form.addView(label("Set pairing token or connect via auto-discovered local server above.", 11.5f).apply { setTextColor(MUTED) })

        var discoveryJob: Job? = null
        val dialog = AlertDialog.Builder(this).setTitle("Connect HomeStream")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Connect", null).setNegativeButton(if (first) "Close" else "Cancel") { _, _ ->
                discoveryJob?.cancel()
                if (first) finish()
            }
            .apply { if (!first) setNeutralButton("Disconnect") { _, _ -> discoveryJob?.cancel(); settings.clear(); setup() } }.create()

        dialog.setOnDismissListener { discoveryJob?.cancel() }

        // Start Discovery
        val foundServers = mutableListOf<DiscoveredServer>()
        discoveryJob = NetworkDiscovery.startDiscovery(lifecycleScope) { s ->
            runOnUiThread {
                if (foundServers.any { it.url == s.url }) return@runOnUiThread
                foundServers.add(s)
                scanStatus.text = "✓ Found (${foundServers.size})"

                val sCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = rounded(0x22FFFFFF.toInt(), radius = dp(6).toFloat())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    isClickable = true
                }
                val info = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                info.addView(label(s.name, 13f, true))
                info.addView(TextView(this@MainActivity).apply { text = s.url; textSize = 11f; setTextColor(MUTED) })
                sCard.addView(info)

                val pairBtn = Button(this@MainActivity).apply {
                    text = "Pair"; textSize = 11.5f; setTextColor(Color.BLACK); isAllCaps = false
                    background = rounded(Color.WHITE, radius = dp(6).toFloat())
                    minHeight = dp(32); minimumHeight = dp(32)
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        discoveryJob?.cancel()
                        isEnabled = false; text = "Pairing…"
                        val devId = runCatching {
                            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                        }.getOrNull() ?: java.util.UUID.randomUUID().toString()
                        val devName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val u = java.net.URL("${s.url}/api/mobile/v1/auth/pair")
                                val conn = (u.openConnection() as java.net.HttpURLConnection).apply {
                                    connectTimeout = 5000; readTimeout = 5000
                                    requestMethod = "POST"
                                    doOutput = true
                                    setRequestProperty("Content-Type", "application/json")
                                }
                                val reqBody = JSONObject().put("deviceId", devId).put("deviceName", devName).put("client", "android")
                                conn.outputStream.bufferedWriter().use { it.write(reqBody.toString()) }

                                if (conn.responseCode == 200) {
                                    val respText = conn.inputStream.bufferedReader().use { it.readText() }
                                    val respJson = JSONObject(respText)
                                    val pairedToken = respJson.optString("token")
                                    if (pairedToken.isNotBlank()) {
                                        withContext(Dispatchers.Main) {
                                            settings.saveServer(local = s.url, external = "", token = pairedToken, type = "device", name = s.name)
                                            toast("Successfully paired with ${s.name}!")
                                            dialog.dismiss()
                                            loadTab("home")
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        toast("Local pairing rejected (${conn.responseCode}). Try manual setup.")
                                        isEnabled = true; text = "Pair"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    toast(e.message ?: "Pairing connection failed")
                                    isEnabled = true; text = "Pair"
                                }
                            }
                        }
                    }
                }
                sCard.addView(pairBtn, LinearLayout.LayoutParams(-2, dp(32)))

                discoveredServersContainer.addView(sCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = url.text.toString().trim().toHttpUrlOrNull()
                if (parsed == null || parsed.username.isNotBlank() || parsed.password.isNotBlank() || parsed.query != null || parsed.fragment != null) {
                    url.error = "Enter an HTTP(S) server URL without credentials, query or fragment"; return@setOnClickListener
                }
                val pairingToken = token.text.toString().trim()
                if (pairingToken.length < 6) { token.error = "Token must contain at least 6 characters"; return@setOnClickListener }
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
                            discoveryJob?.cancel()
                            settings.save(candidate.baseUrl, pairingToken); dialog.dismiss(); loadTab("home")
                        }
                    } catch (e: Exception) {
                        if (e !is kotlinx.coroutines.CancellationException) token.error = e.message ?: "Connection failed"
                    } finally { button.isEnabled = true; button.text = "Connect" }
                }
            }
        }
        dialog.show()
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
            } catch (e: Exception) {
                if (generation != current) return@launch
                content.removeAllViews(); heading("Unable to connect")
                message(e.message ?: "Check your HomeStream connection.")
                content.addView(action("Retry") { fetch(path, params, draw) })
                content.addView(action("Server settings") { setup() })
            }
        }
    }

    private fun heading(text: String, size: Float = 20f) = label(text, size, true).apply {
        setPadding(gutter, dp(16), gutter, dp(8)); content.addView(this)
    }
    private fun message(text: String) {
        content.addView(label(text).apply { setTextColor(MUTED); setPadding(gutter, dp(12), gutter, dp(12)) })
    }
    private fun iconButton(title: String, icon: Int, click: () -> Unit) = action("", click = click).apply {
        contentDescription = title; setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
        setPadding(dp(8), 0, dp(6), 0); background = rounded(Color.TRANSPARENT)
        minWidth = dp(40); minimumWidth = dp(40)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
    }
    private fun categoryPill(title: String, active: Boolean = false, click: () -> Unit) = action(title, click = click).apply {
        textSize = 12f; minHeight = dp(32); minimumHeight = dp(32)
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(if (active) RED else Color.TRANSPARENT, stroke = if (active) RED else 0xFF777777.toInt(), radius = dp(20).toFloat())
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(8) }
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
