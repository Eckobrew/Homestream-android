package com.homestream.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

class MainActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var api: HomeStreamApi
    private lateinit var content: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var mainContainer: LinearLayout
    private var sidebarDrawer: FrameLayout? = null
    private var trailerView: WebView? = null
    private var sceneHost: FrameLayout? = null
    private var work: Job? = null
    private var searchJob: Job? = null
    private var categoryJob: Job? = null
    private val catalogCache = mutableMapOf<String, List<Media>>()
    private var heroRotationJob: Job? = null
    private var currentTab = "home"
    private var screen = "home"
    private var generation = 0
    private var policyMonitor: Job? = null
    private var policyCheck: Job? = null
    private var policyReady = false
    private var playbackWasLaunched = false
    private var lastDetails: Media? = null
    private val gutter get() = dp(if (isTelevision()) 48 else 20)

    private data class BrowseState(
        val root: FrameLayout, val main: LinearLayout, val content: LinearLayout,
        val drawer: FrameLayout?, val screen: String, val tab: String, val focus: View?,
        val heroItems: List<Media>, val heroIndex: Int, val heroContainer: FrameLayout?,
        val details: Media?, val key: String, val loading: Boolean, val reload: (() -> Unit)?
    )
    private val browseHistory = java.util.ArrayDeque<BrowseState>()
    private var navigationKey = "home"
    private var screenLoading = false
    private var screenLoader: (() -> Unit)? = null

    private fun resetNavigationHistory() {
        browseHistory.clear(); navigationKey = "home"; screen = "home"; lastDetails = null
    }

    private fun rememberScreen(key: String, reload: () -> Unit) {
        if (::root.isInitialized && key != navigationKey) {
            // Detached screens retain scroll, query, filters and focused cards. Keep memory bounded.
            trailerView?.apply { (parent as? ViewGroup)?.removeView(this); stopLoading(); destroy() }
            trailerView = null
            browseHistory.addLast(BrowseState(root, mainContainer, content, sidebarDrawer, screen, currentTab,
                currentFocus, heroItems, currentHeroIndex, heroContainer, lastDetails, navigationKey, screenLoading, screenLoader))
            while (browseHistory.size > 12) browseHistory.removeFirst()
        }
        navigationKey = key; screenLoader = reload
    }

    private fun returnToPreviousScreen(): Boolean {
        val saved = browseHistory.pollLast() ?: return false
        hubJob?.cancel(); hubSearch?.cancel()
        work?.cancel(); searchJob?.cancel(); categoryJob?.cancel(); heroRotationJob?.cancel(); ++generation
        trailerView?.apply { stopLoading(); destroy() }; trailerView = null
        root = saved.root; mainContainer = saved.main; content = saved.content; sidebarDrawer = saved.drawer
        screen = saved.screen; currentTab = saved.tab; heroItems = saved.heroItems
        currentHeroIndex = saved.heroIndex; heroContainer = saved.heroContainer; lastDetails = saved.details
        navigationKey = saved.key; screenLoader = saved.reload; screenLoading = saved.loading
        showScene(root, backwards = true)
        if (screen == "search") {
            fun findQuery(view: View): EditText? {
                if (view is EditText) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) findQuery(view.getChildAt(index))?.let { return it }
                return null
            }
            findQuery(content)?.takeIf { it.text.isNotBlank() }?.let { input ->
                val text = input.text.toString(); val cursor = input.selectionStart
                input.setText(text); input.setSelection(cursor.coerceIn(0, text.length))
            }
        }
        if (saved.loading || saved.screen in listOf("requests", "music", "youtube")) saved.reload?.invoke()
        else root.post { saved.focus?.takeIf { it.isAttachedToWindow }?.requestFocus() }
        return true
    }

    // Hero rotation state
    private var heroItems: List<Media> = emptyList()
    private var currentHeroIndex = 0
    private var heroContainer: FrameLayout? = null
    private var lastBackPressTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this); api = HomeStreamApi(settings)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sidebarDrawer?.visibility == View.VISIBLE) {
                    closeSidebar(); return
                }
                if (screen == "music" && musicArtist != null) {
                    musicArtist = null
                    mediaHub("music")
                    return
                }
                if (screen == "music" && selectedPlaylistId != null) {
                    selectedPlaylistId = null
                    mediaHub("music")
                    return
                }
                if (screen == "youtube" && youtubeSubTab != "home") {
                    youtubeSubTab = "home"
                    loadYouTube()
                    return
                }
                if (isTelevision()) {
                    val rail = findViewById<View>(R.id.tv_navigation_rail)
                    if (rail != null && !rail.hasFocus() && (screen == "home" || browseHistory.isEmpty())) {
                        rail.requestFocus()
                        return
                    }
                }
                if (returnToPreviousScreen()) return
                if (screen != "home") {
                    loadTab("home")
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    toast("Press back again to exit")
                }
            }
        })
        if (settings.baseUrl.isBlank() || settings.token.isBlank()) setup() else if (settings.token.isNotBlank()) {
            setContentView(label("HomeStream").apply { setBackgroundColor(INK); gravity = Gravity.CENTER })
        } else if (!openLauncherItem(intent)) loadTab("home")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (policyReady || settings.token.isBlank()) openLauncherItem(intent)
        if (policyReady && intent.getBooleanExtra("musicPlayer", false) && !settings.isKids) { intent.removeExtra("musicPlayer"); musicControls() }
    }

    private fun openLauncherItem(source: Intent): Boolean {
        val link = source.data ?: return false
        if (!isTelevision() || source.action != Intent.ACTION_VIEW || link.scheme != "homestream" || link.host != "watch") return false
        val id = link.getQueryParameter("id") ?: return false
        if (!id.matches(Regex("[A-Za-z0-9-]{1,200}")) || link.getQueryParameter("scope") != WatchNext.scope(settings)) return false
        source.data = null
        browseHistory.clear(); navigationKey = "home"; screen = "home"; lastDetails = null
        loadTab("home")
        lifecycleScope.launch {
            try {
                val item = Media(api.call("media/${Uri.encode(id)}").getJSONObject("item"))
                if (item.isAvailableForPlayback && item.playbackId == id) play(item)
                else details(item)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("Could not open launcher title. Check your server connection.")
            }
        }
        return true
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
        policyMonitor?.cancel()
        policyMonitor = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                if (settings.token.isNotBlank() && policyReady) {
                    try { if (api.refreshProfilePolicy()) { clearCastIdle(this@MainActivity); policyReady = false; verifyProfilePolicy() } }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; verifyProfilePolicy() }
                }
            }
        }
        if (isTelevision()) (application as HomeStreamApplication).playbackReports.launch {
            runCatching { WatchNext.sync(applicationContext) }
                .onFailure { android.util.Log.w("HomeStreamWatchNext", "Launcher sync unavailable: ${it.javaClass.simpleName}") }
        }
        castContext(this)?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
        if (screen == "home" && heroItems.size > 1) setupDynamicHero(heroItems)
        if (policyReady && screen in listOf("requests", "music")) mediaHub(screen)
    }

    override fun onStop() {
        hubJob?.cancel(); hubSearch?.cancel()
        policyMonitor?.cancel(); policyCheck?.cancel()
        trailerView?.onPause()
        heroRotationJob?.cancel()
        super.onStop()
        castContext(this)?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    override fun onResume() {
        super.onResume()
        if (settings.token.isNotBlank()) { verifyProfilePolicy(); return }
        trailerView?.onResume()
        if (playbackWasLaunched) {
            playbackWasLaunched = false
            catalogCache.clear()
            if (screen == "details" && lastDetails != null) {
                details(lastDetails!!)
            } else if (screen == "youtube") {
                loadYouTube()
            } else if (screenLoader != null) {
                screenLoader?.invoke()
            } else {
                loadTab(currentTab)
            }
        }
    }

    private fun verifyProfilePolicy() {
        policyCheck?.cancel()
        if (::root.isInitialized) root.visibility = View.INVISIBLE
        policyCheck = lifecycleScope.launch {
            try {
                val changed = api.refreshProfilePolicy()
                if (changed) { clearCastIdle(this@MainActivity); stopService(Intent(this@MainActivity, MusicService::class.java)) }
                if (changed || !policyReady) {
                    work?.cancel(); searchJob?.cancel(); categoryJob?.cancel()
                    catalogCache.clear(); heroItems = emptyList(); resetNavigationHistory()
                    policyReady = true
                    if (!openLauncherItem(intent)) loadTab("home")
                } else if (playbackWasLaunched) {
                    playbackWasLaunched = false; catalogCache.clear()
                    if (screen == "details" && lastDetails != null) {
                        details(lastDetails!!)
                    } else if (screen == "youtube") {
                        loadYouTube()
                    } else if (screenLoader != null) {
                        screenLoader?.invoke()
                    } else {
                        loadTab(currentTab)
                    }
                }
                if (::root.isInitialized) root.visibility = View.VISIBLE
                trailerView?.onResume()
                if (intent.getBooleanExtra("musicPlayer", false) && !settings.isKids) { intent.removeExtra("musicPlayer"); musicControls() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val blocked = column().apply {
                    setBackgroundColor(INK); gravity = Gravity.CENTER
                    addView(label("Connect to HomeStream to confirm your profile"))
                    addView(action("Retry") { verifyProfilePolicy() })
                    if (e is HomeStreamApi.ResponseException && e.statusCode == 401) addView(action("Sign in / pair") { setup() })
                }
                work?.cancel(); searchJob?.cancel(); categoryJob?.cancel(); heroRotationJob?.cancel()
                trailerView?.onPause()
                stopService(Intent(this@MainActivity, MusicService::class.java))
                policyReady = false; sceneHost = null; setContentView(blocked)
            }
        }
    }

    private fun shell() {
        hubJob?.cancel(); hubSearch?.cancel()
        searchJob?.cancel(); categoryJob?.cancel()
        trailerView?.apply { stopLoading(); destroy() }; trailerView = null
        heroRotationJob?.cancel()
        if (!supportFragmentManager.isStateSaved) {
            supportFragmentManager.findFragmentByTag("cast-mini")?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
        }
        root = FrameLayout(this).apply { setBackgroundColor(INK) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        mainContainer = column().apply { setBackgroundColor(INK) }
        content = column().apply { setPadding(0, 0, 0, dp(if (isTelevision()) 24 else 90)) } // phone floating navigation / TV safe bottom

        if (isTelevision()) televisionShell() else phoneShell()

        root.addView(mainContainer, FrameLayout.LayoutParams(-1, -1))
        setupSidebarDrawer()
        showScene(root)
    }

    private fun showScene(incoming: FrameLayout, backwards: Boolean = false) {
        val host = sceneHost ?: FrameLayout(this).apply {
            setBackgroundColor(INK); sceneHost = this
            setContentView(this)
        }
        val outgoing = (0 until host.childCount).map { host.getChildAt(it) }.lastOrNull()
        if (outgoing === incoming) return
        for (index in 0 until host.childCount) host.getChildAt(index).animate().cancel()
        host.removeAllViews()
        incoming.animate().cancel(); incoming.alpha = 1f; incoming.translationX = 0f
        incoming.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val animate = outgoing != null && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (animate) {
            outgoing!!.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (outgoing as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            outgoing.alpha = 1f; outgoing.translationX = 0f
            host.addView(outgoing, FrameLayout.LayoutParams(-1, -1))
        }
        (incoming.parent as? ViewGroup)?.removeView(incoming)
        host.addView(incoming, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.requestApplyInsets(incoming)
        if (animate) {
            val direction = if (backwards) -1 else 1
            incoming.alpha = 0f; incoming.translationX = dp(if (isTelevision()) 8 else 18) * direction.toFloat()
            incoming.animate().alpha(1f).translationX(0f).setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            outgoing!!.animate().alpha(0f).translationX((-dp(8) * direction).toFloat()).setDuration(160)
                .withEndAction {
                    if (outgoing.parent === host && outgoing !== root) host.removeView(outgoing)
                    outgoing.alpha = 1f; outgoing.translationX = 0f
                }.start()
        }
    }

    private fun browseViewport(): View = if (screen.startsWith("catalog-") || screen.startsWith("categories-")) content else
        ScrollView(this).apply { clipChildren = false; addView(content) }

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

        if (!settings.isKids) header.addView(iconButton("Music player", R.drawable.nav_music) { musicControls() })

        // Profile switcher button
        if (settings.canUseProfiles) header.addView(iconButton("Profile", R.drawable.nav_profile) { profiles() })

        mainContainer.addView(header)

        // 3. Scrollable content
        mainContainer.addView(browseViewport(),
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

    private data class BottomNavItem(
        val title: String,
        val icon: Int,
        val isActive: Boolean,
        val action: () -> Unit
    )

    private fun createFloatingBottomNav(): View {
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        val accentColor = when (screen) {
            "music" -> SPOTIFY_GREEN
            "youtube" -> RED
            else -> RED
        }
        val strokeColor = when (screen) {
            "music" -> 0x551DB954.toInt()
            "youtube" -> 0x55E50914.toInt()
            else -> 0x33FFFFFF.toInt()
        }

        val pill = LinearLayout(this).apply {
            id = R.id.phone_bottom_navigation; gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xF0181818.toInt()); cornerRadius = dp(28).toFloat()
                setStroke(dp(1), strokeColor)
            }
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val items: List<BottomNavItem> = when (screen) {
            "youtube" -> listOf(
                BottomNavItem("Home", R.drawable.nav_home, youtubeSubTab == "home") {
                    youtubeSubTab = "home"; loadYouTube()
                },
                BottomNavItem("Subs", R.drawable.ic_play_arrow, youtubeSubTab == "subscriptions") {
                    youtubeSubTab = "subscriptions"; loadYouTube()
                },
                BottomNavItem("Queue", R.drawable.nav_list, youtubeSubTab == "queue") {
                    youtubeSubTab = "queue"; loadYouTube()
                },
                BottomNavItem("Archive", R.drawable.ic_download, youtubeSubTab == "archive") {
                    youtubeSubTab = "archive"; loadYouTube()
                },
                BottomNavItem("Search", R.drawable.nav_search, youtubeSubTab == "search") {
                    youtubeSubTab = "search"; loadYouTube()
                }
            )
            "music" -> listOf(
                BottomNavItem("Albums", R.drawable.nav_music, musicCategory == "albums") {
                    musicCategory = "albums"; musicArtist = null; selectedPlaylistId = null; mediaHub("music")
                },
                BottomNavItem("Artists", R.drawable.nav_profile, musicCategory == "artists") {
                    musicCategory = "artists"; musicArtist = null; selectedPlaylistId = null; mediaHub("music")
                },
                BottomNavItem("Songs", R.drawable.nav_list, musicCategory == "songs") {
                    musicCategory = "songs"; musicArtist = null; selectedPlaylistId = null; mediaHub("music")
                },
                BottomNavItem("Playlists", R.drawable.ic_download, musicCategory == "playlists") {
                    musicCategory = "playlists"; musicArtist = null; selectedPlaylistId = null; mediaHub("music")
                },
                BottomNavItem("Player", R.drawable.ic_play_arrow, false) {
                    musicControls()
                }
            )
            else -> listOf(
                BottomNavItem("Home", R.drawable.nav_home, currentTab == "home" && screen == "home") {
                    loadTab("home")
                },
                BottomNavItem("TV Shows", R.drawable.nav_series, currentTab == "series" && screen == "series") {
                    loadTab("series")
                },
                BottomNavItem("Movies", R.drawable.nav_movies, currentTab == "movies" && screen == "movies") {
                    loadTab("movies")
                },
                BottomNavItem("Discover", R.drawable.ic_download, (currentTab == "requests" || screen == "requests") && screen != "search") {
                    loadTab("requests")
                },
                BottomNavItem("Search", R.drawable.nav_search, screen == "search") {
                    openSearch()
                }
            )
        }

        items.forEach { navItem ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(6))
                isClickable = true; isFocusable = true
                background = GradientDrawable().apply {
                    setColor(if (navItem.isActive) 0x22FFFFFF.toInt() else Color.TRANSPARENT)
                    cornerRadius = dp(20).toFloat()
                }
                setOnClickListener { navItem.action() }
            }

            val img = ImageView(this).apply {
                setImageResource(navItem.icon)
                imageTintList = ColorStateList.valueOf(if (navItem.isActive) accentColor else MUTED)
            }
            item.addView(img, LinearLayout.LayoutParams(dp(20), dp(20)))

            val txt = TextView(this).apply {
                text = navItem.title; textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (navItem.isActive) Color.WHITE else MUTED)
                setPadding(0, dp(2), 0, 0)
                isSingleLine = true
                gravity = Gravity.CENTER
            }
            item.addView(txt)

            pill.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }

        return pill
    }

    private fun showTvBrowseMenu() {
        val destinations = listOf(
            "A–Z Movies" to { loadCatalog("movies") },
            "A–Z Series" to { loadCatalog("tv") },
            "Movie Categories" to { loadCategories("movies") },
            "TV Categories" to { loadCategories("tv") },
            "Music Hub" to { loadTab("music") },
            "YouTube Hub" to { loadYouTube() },
            "Active Downloads" to { loadDownloads() }
        ).filter { (it.first !in listOf("Music Hub", "YouTube Hub", "Active Downloads")) || !settings.isKids }
        AlertDialog.Builder(this).setTitle("Browse more")
            .setItems(destinations.map { it.first }.toTypedArray()) { _, index -> destinations[index].second() }
            .setNegativeButton("Back", null).show()
    }

    private fun televisionShell() {
        val stage = FrameLayout(this)
        stage.addView(browseViewport(),
            FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(76) })

        val rail = column().apply {
            id = R.id.tv_navigation_rail; gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(INK, 0xF0080808.toInt(), 0xDD080808.toInt()))
        }

        val entries = listOf(
            Triple("Search", R.drawable.nav_search, { openSearch() }),
            Triple("Home", R.drawable.nav_home, { loadTab("home") }),
            Triple("TV Shows", R.drawable.nav_series, { loadTab("series") }),
            Triple("Movies", R.drawable.nav_movies, { loadTab("movies") }),
            Triple("Music", R.drawable.nav_music, { loadTab("music") }),
            Triple("YouTube", R.drawable.ic_play_arrow, { loadYouTube() }),
            Triple("Discover", R.drawable.ic_download, { loadTab("requests") }),
            Triple("Music player", R.drawable.nav_music, { musicControls() }),
            Triple("More", R.drawable.ic_menu, { showTvBrowseMenu() }),
            Triple("My List", R.drawable.nav_list, { myHomeStream() }),
            Triple("Profiles", R.drawable.nav_profile, { profiles() }),
            Triple("Settings", R.drawable.nav_settings, { openServerSettingsDialog() })
        ).filter { (it.first !in listOf("Music", "Music player", "YouTube") || !settings.isKids) && (it.first != "Profiles" || settings.canUseProfiles) && (it.first != "Downloads" || settings.kidsRating.isBlank()) }

        val buttons = entries.map { (title, icon, click) ->
            action("", click = click).apply {
                contentDescription = title; tag = title; gravity = Gravity.CENTER_VERTICAL
                isSelected = when (title) {
                    "Home" -> screen == "home" && currentTab == "home"
                    "Movies" -> (screen == "movies" || (currentTab == "movies" && screen == "home"))
                    "TV Shows" -> (screen == "series" || (currentTab == "series" && screen == "home"))
                    "YouTube" -> screen == "youtube"
                    "Discover" -> currentTab == "requests" || screen == "requests"
                    "Music" -> currentTab == "music" || screen == "music"
                    "Search" -> screen == "search"
                    "My List" -> screen == "mine"
                    "More" -> screen.startsWith("catalog-") || screen.startsWith("categories-") || screen == "downloads"
                    else -> false
                }
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = 0
                setPadding(dp(10), 0, dp(10), 0)
                setDefaultFocusHighlightEnabled(false)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF383838.toInt(), Color.WHITE))
                    addState(intArrayOf(android.R.attr.state_selected), rounded(0x33E50914, RED))
                    addState(intArrayOf(), rounded(Color.TRANSPARENT))
                }
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(4) }
                rail.addView(this)
            }
        }

        val railViewport = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(rail) }
        stage.addView(railViewport, FrameLayout.LayoutParams(dp(76), -1, Gravity.START))
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            var parent: android.view.ViewParent? = focused?.parent
            var inside = focused === rail
            while (parent != null) { if (parent === rail) inside = true; parent = parent.parent }
            val width = dp(if (inside) 210 else 76)
            if (railViewport.layoutParams.width != width) {
                railViewport.layoutParams = (railViewport.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                buttons.forEach { button ->
                    button.text = if (inside) button.tag.toString() else ""
                    button.compoundDrawablePadding = if (inside) dp(18) else 0
                    button.setPadding(dp(if (inside) 12 else 10), 0, dp(if (inside) 8 else 10), 0)
                    button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply { this.width = if (inside) -1 else dp(44) }
                }
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
        panel.addView(homeFeedBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Dedicated Hubs Section: Music Hub & YouTube Hub
        if (!settings.isKids) {
            // Dedicated Music Hub Tab
            val musicBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (screen == "music" || currentTab == "music") 0xFF1DB954.toInt() else 0x22FFFFFF.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { closeSidebar(); loadTab("music") }
            }
            musicBtn.addView(ImageView(this).apply {
                setImageResource(R.drawable.nav_music); imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val musicText = column().apply { setPadding(dp(12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            musicText.addView(label("Music", 14f, true))
            musicText.addView(TextView(this).apply { text = "Albums, artists & music player"; textSize = 11f; setTextColor(MUTED) })
            musicBtn.addView(musicText)
            musicBtn.addView(badgePill("MUSIC"))
            panel.addView(musicBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

            val ytBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (screen == "youtube") RED else 0x22FFFFFF.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { closeSidebar(); loadYouTube() }
            }
            ytBtn.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_play_arrow); imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val ytText = column().apply { setPadding(dp(12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            ytText.addView(label("YouTube", 14f, true))
            ytText.addView(TextView(this).apply { text = "Continue watching, podcasts & trending"; textSize = 11f; setTextColor(MUTED) })
            ytBtn.addView(ytText)
            ytBtn.addView(badgePill("HUB"))
            panel.addView(ytBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        }

        // Section: Jellyfin Media (A–Z)
        panel.addView(TextView(this).apply {
            text = "MEDIA LIBRARY (A–Z)"; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(MUTED); setPadding(0, 0, 0, dp(4))
        })
        panel.addView(TextView(this).apply {
            text = "Direct access to all media stored on your server, sorted alphabetically."; textSize = 11f
            setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, dp(12))
        })

        // Library Items: Movies (A-Z) and TV Shows (A-Z)
        val libs = listOf(
            Triple("Movies", "All movies on your Jellyfin server", "movies"),
            Triple("TV Shows", "All series on your Jellyfin server", "tv")
        )

        libs.forEach { (name, desc, type) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(0x18FFFFFF.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener {
                    closeSidebar()
                    loadCatalog(type)
                }
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
            visibility = if (settings.canUseProfiles) View.VISIBLE else View.GONE
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
        profText.addView(label("Profiles", 13.5f, true))
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
        if (tab == "requests" || tab == "music") { mediaHub(tab); return }
        if (tab == "youtube") { loadYouTube(); return }
        if (tab == "search") { openSearch(); return }
        if (tab == "downloads") { loadDownloads(); return }
        if (tab == "mine") { myHomeStream(); return }
        rememberScreen(tab) { loadTab(tab) }
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
        categoryJob?.cancel()
        val current = generation
        val slot = column().apply { setTag(R.id.browse_key, "category-preview:$type") }
        content.addView(slot)
        fun render(data: JSONObject) {
            val sections = data.optJSONArray("sections") ?: return
            slot.removeAllViews()
            slot.setTag(R.id.browse_signature, data.toString())
            if (sections.length() == 0) return
            slot.addView(label("Browse categories", 20f, true).apply { setPadding(gutter, dp(20), gutter, dp(8)) })
            val virtualHost = column()
            slot.addView(virtualHost)
            // Keep lightweight row slots; instantiate artwork/cards only while on screen.
            val slots = (0 until minOf(64, sections.length())).map { index ->
                val section = sections.getJSONObject(index)
                column().apply {
                    minimumHeight = dp(if (isTelevision()) 240 else 290)
                    setTag(R.id.browse_key, "category:${section.optString("id")}")
                    virtualHost.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
            }
            val offsets = mutableMapOf<Int, Int>()
            val visible = android.graphics.Rect()
            var queued = false
            val prefetched = mutableSetOf<Int>()
            fun reconcile() {
                if (!virtualHost.isAttachedToWindow) return
                var lastVisible = -1
                slots.forEachIndexed { index, row ->
                    if (row.getGlobalVisibleRect(visible) || row.hasFocus()) {
                        lastVisible = index
                        if (row.childCount == 0) {
                            val section = sections.getJSONObject(index)
                            mediaRow(section.optString("title"), section.mediaList("items").take(30), parent = row)
                            (row.getChildAt(1) as? HorizontalScrollView)?.let { scroll ->
                                scroll.post { scroll.scrollTo(offsets[index] ?: 0, 0) }
                            }
                        }
                    } else if (row.childCount > 0) {
                        offsets[index] = (row.getChildAt(1) as? HorizontalScrollView)?.scrollX ?: 0
                        row.minimumHeight = maxOf(row.minimumHeight, row.height)
                        row.removeAllViews()
                    }
                }
                // Warm only the leading artwork of the next two rows; never fetch details here.
                if (lastVisible >= 0) for (index in lastVisible + 1..minOf(lastVisible + 2, slots.lastIndex)) {
                    if (prefetched.add(index)) {
                        sections.getJSONObject(index).mediaList("items").take(4).forEach { item ->
                            coil.Coil.imageLoader(this@MainActivity).enqueue(
                                coil.request.ImageRequest.Builder(this@MainActivity)
                                    .data(api.absolute(if (isTelevision()) item.backdrop else item.poster))
                                    .size(if (isTelevision()) dp(240) else dp(140))
                                    .build())
                        }
                    }
                }
            }
            val observer = android.view.ViewTreeObserver.OnScrollChangedListener {
                if (!queued) { queued = true; slot.postOnAnimation { queued = false; reconcile() } }
            }
            virtualHost.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.viewTreeObserver.addOnScrollChangedListener(observer)
                    view.post { reconcile() }
                }
                override fun onViewDetachedFromWindow(view: View) {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnScrollChangedListener(observer)
                }
            })
            if (virtualHost.isAttachedToWindow) {
                virtualHost.viewTreeObserver.addOnScrollChangedListener(observer)
                slot.post { reconcile() }
            }

        }
        val cached = api.cached("categories/$type")
        if (cached != null) render(cached)
        if (cached != null && api.cacheFresh("categories/$type")) return
        categoryJob = lifecycleScope.launch {
            try {
                val fresh = api.call("categories/$type")
                if (current == generation && (cached == null || cached.toString() != fresh.toString())) render(fresh)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("HomeStream", "Category refresh unavailable")
            }
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
                    val featured = content.findViewById<View>(R.id.featured_card)
                    if (isTelevision() && featured != null && currentFocus?.let { isWithin(it, featured) } == true) continue
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
        sendCustomCastIdle(session, heroItems.ifEmpty { listOf(item) }, api)
    }

    private fun hero(item: Media, showActions: Boolean = true) {
        if (screen != "home" && screen != "youtube") return
        content.findViewById<View>(R.id.featured_card)?.let { content.removeView(it) }
        if (isTelevision()) {
            televisionHero(item, showActions)
            content.findViewById<View>(R.id.featured_card)?.let { addTvFocusIndicators(it) }
        } else phoneHero(item, showActions)
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
        if (item.source == "youtube") {
            metaRow.addView(badgePill("YOUTUBE"))
        } else if (item.json.optString("rating").isNotBlank()) {
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
                setOnClickListener {
                    if (item.source == "youtube") showYouTubeItemDialog(item) else details(item)
                }
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
            actions.addView(action("More Info") {
                if (item.source == "youtube") showYouTubeItemDialog(item) else details(item)
            })
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

    private fun mediaRow(title: String, items: List<Media>, isContinueWatching: Boolean = false, parent: LinearLayout = content) {
        if (items.isEmpty()) return
        parent.addView(label(title, 20f, true).apply { setPadding(gutter, dp(22), gutter, dp(8)); setTag(R.id.browse_key, "heading:$title"); setTag(R.id.browse_signature, title) })
        val row = LinearLayout(this).apply { setPadding(gutter, dp(6), gutter, dp(10)); clipChildren = false }

        items.forEach { item ->
            val card = column().apply {
                setPadding(dp(4), dp(4), dp(4), dp(6)); focusableCard()
                contentDescription = "${item.title}, ${item.type}, ${item.metadata}"
                setTag(R.id.browse_key, "media:${item.id}"); setTag(R.id.browse_signature, item.json.toString())
                val focusStyle = onFocusChangeListener
                setOnFocusChangeListener { view, focused ->
                    focusStyle?.onFocusChange(view, focused)
                    if (focused) prefetchDetails(item)
                }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) prefetchDetails(item)
                    false
                }
                setOnClickListener {
                    // Tapping Continue Watching or YouTube jumps straight to playback at saved position!
                    if (isContinueWatching || item.source == "youtube") {
                        play(item)
                    } else {
                        details(item)
                    }
                }
                setOnLongClickListener {
                    if (item.source == "youtube") {
                        showYouTubeItemDialog(item)
                        true
                    } else false
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

            // Duration badge for YouTube cards (e.g. "45:12")
            val isYouTube = item.source == "youtube"
            val durBadge = if (isYouTube) item.json.optString("metadata").substringAfterLast("• ").trim().takeIf { it.isNotBlank() } else null
            if (durBadge != null) {
                val badgeView = TextView(this).apply {
                    text = durBadge
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    background = rounded(0xCC000000.toInt(), radius = dp(4).toFloat())
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
                posterContainer.addView(badgeView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(4), if (item.json.optDouble("progressPercent") > 0) dp(8) else dp(4))
                })
            }

            // Progress Bar for Continue Watching items
            val progressVal = item.json.optDouble("progressPercent")
            if (progressVal > 0) {
                val pBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = progressVal.toInt()
                    progressTintList = ColorStateList.valueOf(RED)
                }
                posterContainer.addView(pBar, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))
            }

            val cardWidth = dp(if (isTelevision()) (if (isYouTube || isContinueWatching) 220 else 200) else if (isYouTube || isContinueWatching) 180 else 115)
            val cardHeight = dp(if (isTelevision()) (if (isYouTube || isContinueWatching) 124 else 115) else if (isYouTube || isContinueWatching) 101 else 165)
            card.addView(posterContainer, LinearLayout.LayoutParams(cardWidth, cardHeight))

            // Card title under poster
            card.addView(label((if (item.json.optBoolean("played")) "✓ " else "") + item.title, if (isTelevision()) 12.5f else 11f, bold = true).apply {
                maxLines = if (isYouTube) 2 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(5), dp(2), 0)
            })

            // Subtitle for YouTube (channel title)
            val subText = if (isYouTube) item.json.optString("seriesName").ifBlank { item.json.optString("channelTitle") } else ""
            if (subText.isNotBlank()) {
                card.addView(label(subText, if (isTelevision()) 11f else 9.5f).apply {
                    setTextColor(MUTED); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(1), dp(2), 0)
                })
            }

            row.addView(card, LinearLayout.LayoutParams(cardWidth + card.paddingLeft + card.paddingRight, -2).apply { setMargins(0, 0, dp(10), 0) })
        }

        parent.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; addView(row)
            setTag(R.id.browse_key, "row:$title")
        })
    }

    // -----------------------------------------------------------------------------------------
    // Full Netflix Media Details Modal / Screen
    // -----------------------------------------------------------------------------------------
    private fun details(item: Media) {
        rememberScreen("details:${item.id}") { details(item) }
        heroRotationJob?.cancel()
        lastDetails = item
        screen = "details"
        val path = "media/${android.net.Uri.encode(item.id)}"
        api.seed(path, JSONObject().put("item", item.json))
        fetch(path) { data ->
            val full = Media(data.getJSONObject("item"))
            lastDetails = full

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
                trailerView = webView
                headerArea.addView(webView, FrameLayout.LayoutParams(-1, -1))
            }

            // Close button (X) in top right corner
            val closeBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_close); imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = rounded(0x88000000.toInt(), radius = dp(18).toFloat())
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { if (!returnToPreviousScreen()) loadTab(currentTab) }
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
                marginEnd = dp(8)
            })

            val dlBtn = Button(this).apply {
                text = if (full.isAvailableForPlayback) "⬇ Save to device" else "⬇ Download"
                textSize = 12.5f
                setTextColor(Color.WHITE); isAllCaps = false
                background = rounded(0x33FFFFFF.toInt(), radius = dp(8).toFloat())
                minHeight = dp(40); minimumHeight = dp(40)
                setOnClickListener {
                    if (full.isAvailableForPlayback) triggerDownload(full) else requestMediaTitle(full)
                }
            }
            btnRow.addView(dlBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
            metaSection.addView(btnRow)
            if (full.isAvailableForPlayback && !full.isDownload) {
                val watched = Button(this).apply {
                    text = if (full.json.optBoolean("played")) "✓ Watched · Mark unwatched" else "Mark as watched"
                    textSize = 12.5f; isAllCaps = false; setTextColor(Color.WHITE)
                    background = rounded(0x33FFFFFF.toInt(), radius = dp(8).toFloat())
                    minHeight = dp(40); minimumHeight = dp(40)
                    setOnClickListener {
                        isEnabled = false
                        lifecycleScope.launch {
                            try {
                                val played = !full.json.optBoolean("played")
                                api.call("watched", body = JSONObject().put("itemId", full.playbackId).put("played", played))
                                catalogCache.clear()
                                if (isTelevision()) WatchNext.refresh(applicationContext)
                                details(Media(JSONObject(full.json.toString()).put("played", played)))
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                toast(e.message ?: "Could not update watched status")
                            } finally { isEnabled = true }
                        }
                    }
                }
                metaSection.addView(watched, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
            }

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
                setOnClickListener { selectedTab = 0; updateTabs(); if (isTelevision()) tabHeader.getChildAt(0)?.requestFocus() }
            }
            val t2 = TextView(this).apply {
                text = tab2Title; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (selectedTab == 1) Color.WHITE else MUTED)
                setPadding(dp(10), dp(8), dp(20), dp(8))
                setOnClickListener { selectedTab = 1; updateTabs(); if (isTelevision()) tabHeader.getChildAt(1)?.requestFocus() }
            }
            if (isTelevision()) { t1.focusableCard(); t2.focusableCard() }
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
                            play(Media(JSONObject(ep.json.toString()).put("seriesId", full.playbackId).put("seriesName", full.title)))
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
                    text = (if (ep.json.optBoolean("played")) "✓ " else "") + "$epNum. ${ep.title}"; textSize = 13.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
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
                val total = ep.json.optLong("totalDurationTicks")
                val percent = if (ep.json.optBoolean("played")) 100 else if (total > 0)
                    ((ep.json.optLong("playbackPositionTicks").toDouble() / total) * 100).toInt().coerceIn(0, 100)
                    else ep.json.optInt("progressPercent").coerceIn(0, 100)
                epCard.addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = percent
                    progressTintList = ColorStateList.valueOf(RED)
                    progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                    isFocusable = false; contentDescription = "$percent percent watched"
                }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10) })
                epCard.addView(label(if (percent == 100) "Watched" else "$percent% watched", 10.5f).apply {
                    setTextColor(MUTED); setPadding(0, dp(4), 0, 0)
                })
                episodesContainer.addView(epCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }

        seasonPillsScroll.addView(seasonPills)
        container.addView(seasonPillsScroll)
        container.addView(episodesContainer)
        showEpisodesForSeason(0)
    }

    private fun prefetchDetails(item: Media) {
        (application as HomeStreamApplication).playbackReports.launch {
            runCatching { api.prefetch("media/${Uri.encode(item.id)}") }
        }
    }

    private fun posterAspect(view: View) {
        view.addOnLayoutChangeListener { target, left, _, right, _, _, _, _, _ ->
            val height = ((right - left) * 1.5f).toInt()
            if (height > 0 && target.layoutParams.height != height) target.layoutParams = target.layoutParams.apply { this.height = height }
        }
    }

    private fun renderDetailsGrid(items: List<Media>, container: LinearLayout, title: String? = null) {
        val viewport = content.parent as? ScrollView
        val scroll = viewport?.scrollY ?: 0
        container.removeAllViews()
        if (!title.isNullOrBlank()) container.addView(label(title, 16f, true).apply { setPadding(0, dp(10), 0, dp(8)) })
        if (items.isEmpty()) { container.addView(label("No titles available.", 13f).apply { setTextColor(MUTED) }); return }
        val grid = GridLayout(this).apply { columnCount = if (isTelevision()) 5 else 3 }
        items.forEachIndexed { index, item ->
            val card = column().apply {
                setPadding(dp(4), dp(4), dp(4), dp(6)); focusableCard()
                contentDescription = item.title
                setOnClickListener { details(item) }
                val style = onFocusChangeListener
                setOnFocusChangeListener { view, focused -> style?.onFocusChange(view, focused); if (focused) prefetchDetails(item) }
            }
            val poster = FrameLayout(this).apply { background = rounded(0xFF222222.toInt()); clipToOutline = true }
            poster.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(api.absolute(item.poster)) { error(R.drawable.ic_homestream) }
            }, FrameLayout.LayoutParams(-1, -1))
            if (!item.playable && (item.id.startsWith("seerr-") || item.id.startsWith("tmdb-") || item.source == "seerr" || item.source == "tmdb")) poster.addView(label("Request", 9f, true).apply {
                background = rounded(RED); setPadding(dp(5), dp(2), dp(5), dp(2))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
            card.addView(poster, LinearLayout.LayoutParams(-1, dp(145))); posterAspect(poster)
            card.addView(label(item.title, 11f, true).apply { maxLines = 2; setPadding(dp(2), dp(5), dp(2), 0) })
            grid.addView(card, GridLayout.LayoutParams(GridLayout.spec(index / grid.columnCount), GridLayout.spec(index % grid.columnCount, GridLayout.FILL, 1f)).apply {
                width = 0; height = -2; setMargins(dp(3), dp(6), dp(3), dp(6))
            })
        }
        container.addView(grid, LinearLayout.LayoutParams(-1, -2))
        container.post { viewport?.scrollTo(0, scroll) }
    }

    private fun detailsGridRequest(path: String, params: Map<String, String>, container: LinearLayout, collection: Boolean) {
        fun render(data: JSONObject) {
            if (collection) {
                val value = data.optJSONObject("collection")
                renderDetailsGrid(value?.mediaList("parts").orEmpty(), container, value?.optString("name"))
            } else renderDetailsGrid(data.mediaList("recommendations"), container)
        }
        val cached = api.cached(path, params)
        if (cached != null) render(cached) else {
            val row = LinearLayout(this)
            repeat(if (isTelevision()) 5 else 3) {
                row.addView(View(this).apply { background = rounded(0xFF202020.toInt()) },
                    LinearLayout.LayoutParams(0, dp(150), 1f).apply { setMargins(dp(4), dp(12), dp(4), dp(6)) })
            }
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        lifecycleScope.launch {
            try {
                val fresh = api.call(path, params)
                if (cached == null || cached.toString() != fresh.toString()) render(fresh)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (cached == null) { container.removeAllViews(); container.addView(label("Could not refresh titles.", 13f)) }
            }
        }
    }

    private fun renderCollectionTab(full: Media, container: LinearLayout) {
        detailsGridRequest("media/${Uri.encode(full.id)}/collection", emptyMap(), container, collection = true)
    }

    private fun renderMoreLikeThisTab(full: Media, container: LinearLayout) {
        val params = mutableMapOf("type" to if (full.type in listOf("Series", "Episode")) "tv" else "movie")
        if (full.tmdbId > 0) params["tmdbId"] = full.tmdbId.toString()
        detailsGridRequest("media/${Uri.encode(full.id)}/recommendations", params, container, collection = false)
    }

    // -----------------------------------------------------------------------------------------
    // In-Page Netflix Search (No AlertDialog)
    // -----------------------------------------------------------------------------------------
    private fun openSearch() {
        rememberScreen("search") { openSearch() }; screenLoading = false
        heroRotationJob?.cancel()
        work?.cancel(); ++generation
        screen = "search"
        shell()

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
                            if (available.isNotEmpty()) mediaRow("In Your Library", available, parent = resultsContainer)
                            if (toRequest.isNotEmpty()) mediaRow("Available to Request", toRequest, parent = resultsContainer)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        resultsContainer.removeAllViews()
                        resultsContainer.addView(label(e.message ?: "Search failed. Try again."))
                    }
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
        rememberScreen("catalog-$type") { loadCatalog(type, selectedLetter) }
        screen = "catalog-$type"
        val cacheKey = "${settings.baseUrl}|${settings.profile}|$type"
        val cached = catalogCache[cacheKey]
        if (cached != null) {
            work?.cancel(); ++generation; shell(); screenLoading = false
            renderCatalog(type, selectedLetter, cached)
        } else fetch("library/$type") { data ->
            val items = data.mediaList("items")
            catalogCache[cacheKey] = items
            renderCatalog(type, selectedLetter, items)
        }
    }

    private fun renderCatalog(type: String, selectedLetter: String, allItems: List<Media>) {
        val title = when (type) { "movies" -> "Movies"; "tv" -> "TV Shows"; else -> "YouTube" }
        heading("$title (A–Z) (${allItems.size})", 22f)
        val alphaRow = LinearLayout(this).apply { setPadding(gutter, dp(4), gutter, dp(14)) }
        (listOf("All", "#") + ('A'..'Z').map { it.toString() }).forEach { letter ->
            alphaRow.addView(action(letter, primary = letter == selectedLetter) { loadCatalog(type, letter) })
        }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(alphaRow) })
        val options = getSharedPreferences("library-options", MODE_PRIVATE)
        val optionKey = "${settings.serverIdentity}|${settings.profile}|$type"
        val sort = options.getInt("$optionKey-sort", 0)
        val filter = options.getInt("$optionKey-filter", 0)
        val sorts = arrayOf("Title A–Z", "Title Z–A", "Newest year", "Unwatched first")
        val filters = arrayOf("All titles", "Unwatched", "Watched", "In progress")
        val controls = LinearLayout(this).apply { setPadding(gutter, 0, gutter, dp(8)) }
        controls.addView(action("Sort: ${sorts[sort.coerceIn(sorts.indices)]}") {
            AlertDialog.Builder(this).setTitle("Sort library").setSingleChoiceItems(sorts, sort) { dialog, index ->
                options.edit().putInt("$optionKey-sort", index).apply(); dialog.dismiss(); loadCatalog(type, selectedLetter)
            }.show()
        })
        controls.addView(action(filters[filter.coerceIn(filters.indices)]) {
            AlertDialog.Builder(this).setTitle("Filter library").setSingleChoiceItems(filters, filter) { dialog, index ->
                options.edit().putInt("$optionKey-filter", index).apply(); dialog.dismiss(); loadCatalog(type, selectedLetter)
            }.show()
        })
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(controls) })
        val filtered = libraryItems(when (selectedLetter) {
            "All" -> allItems
            "#" -> allItems.filter { it.title.firstOrNull()?.isLetter() != true }
            else -> allItems.filter { it.title.startsWith(selectedLetter, ignoreCase = true) }
        }, sort, filter)
        if (filtered.isEmpty()) { message("No titles found under '$selectedLetter'."); return }
        val grid = RecyclerView(this).apply {
            setTag(R.id.browse_key, "catalog-list:$type")
            setPadding(gutter, 0, gutter, 0)
            clipToPadding = false
            val manager = GridLayoutManager(this@MainActivity, 1)
            layoutManager = manager
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val columns = ((view.width - paddingLeft - paddingRight) / dp(if (isTelevision()) 150 else 108)).coerceAtLeast(1)
                if (manager.spanCount != columns) manager.spanCount = columns
            }
            adapter = object : RecyclerView.Adapter<CatalogHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatalogHolder {
                    val card = column().apply {
                        setPadding(dp(4), dp(4), dp(4), dp(8)); focusableCard()
                        layoutParams = RecyclerView.LayoutParams(-1, -2)
                    }
                    val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    val label = label("", 12f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                    card.addView(image, LinearLayout.LayoutParams(-1, dp(if (isTelevision()) 210 else 145)))
                    card.addView(label)
                    return CatalogHolder(card, image, label)
                }
                override fun getItemCount() = filtered.size
                override fun onBindViewHolder(holder: CatalogHolder, position: Int) {
                    val item = filtered[position]
                    holder.title.text = (if (item.json.optBoolean("played")) "✓ " else "") + item.title
                    holder.itemView.contentDescription = item.title + if (item.json.optBoolean("played")) ", watched" else ", unwatched"
                    holder.image.load(api.absolute(item.poster)) { error(R.drawable.ic_homestream) }
                    holder.itemView.setOnClickListener { details(item) }
                }
            }
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private class CatalogHolder(view: View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(view)

    // -----------------------------------------------------------------------------------------
    // Categories & Other Helpers
    // -----------------------------------------------------------------------------------------
    private fun showCategoriesMenu() {
        val options = listOf("Movie Categories", "TV Categories", "Active Downloads", "My List").filter { it != "Active Downloads" || settings.kidsRating.isBlank() }
        AlertDialog.Builder(this).setTitle("Browse Categories").setItems(options.toTypedArray()) { _, idx ->
            when (options[idx]) {
                "Movie Categories" -> loadCategories("movies")
                "TV Categories" -> loadCategories("tv")
                "Active Downloads" -> loadDownloads()
                else -> myHomeStream()
            }
        }.show()
    }

    private class CategoryHolder(val body: LinearLayout) : RecyclerView.ViewHolder(body) { var sectionId = "" }

    private fun loadCategories(type: String, selectedCategory: String? = null) {
        rememberScreen("categories-$type") { loadCategories(type, selectedCategory) }
        screen = "categories-$type"
        fetch("categories/$type") { data ->
            heading(when (type) { "movies" -> "Movie Categories"; "requests" -> "Discover by Category"; else -> "TV Categories" })
            val sections = data.optJSONArray("sections")
            if (sections == null || sections.length() == 0) { message("No categories found."); return@fetch }
            val positions = mutableMapOf<String, Int>()
            val list = RecyclerView(this).apply {
                setTag(R.id.browse_key, "category-list:$type")
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
                setItemViewCacheSize(0)
                recycledViewPool.setMaxRecycledViews(0, 2)
                adapter = object : RecyclerView.Adapter<CategoryHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CategoryHolder(column().apply {
                        layoutParams = RecyclerView.LayoutParams(-1, -2)
                    })
                    private fun remember(holder: CategoryHolder) {
                        if (holder.sectionId.isNotBlank()) positions[holder.sectionId] =
                            (holder.body.getChildAt(1) as? HorizontalScrollView)?.scrollX ?: 0
                    }
                    override fun onBindViewHolder(holder: CategoryHolder, position: Int) {
                        remember(holder); holder.body.removeAllViews()
                        val section = sections.getJSONObject(position)
                        holder.sectionId = section.optString("id")
                        mediaRow(section.optString("title"), section.mediaList("items"), parent = holder.body)
                        (holder.body.getChildAt(1) as? HorizontalScrollView)?.let { row ->
                            val targetId = holder.sectionId
                            row.post { if (holder.sectionId == targetId) row.scrollTo(positions[targetId] ?: 0, 0) }
                        }
                    }
                    override fun onViewRecycled(holder: CategoryHolder) {
                        remember(holder); holder.sectionId = ""; holder.body.removeAllViews()
                    }
                    override fun getItemCount() = sections.length()
                }
            }
            content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            if (selectedCategory != null) {
                val index = (0 until sections.length()).firstOrNull { sections.getJSONObject(it).optString("id") == selectedCategory }
                if (index != null) list.scrollToPosition(index)
            }
        }
    }

    private fun loadDownloads() {
        if (settings.kidsRating.isNotBlank()) { toast("Downloading torrents are unavailable in Kids mode"); return }
        rememberScreen("downloads") { loadDownloads() }
        screen = "downloads"
        fetch("downloads") { data ->
            heading("Active Downloads")
            val items = data.mediaList("items")
            if (items.isEmpty()) message("No active torrent downloads.")
            else mediaRow("Downloading via qBittorrent", items)
        }
    }

    private fun myHomeStream() {
        rememberScreen("mine") { myHomeStream() }
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

    private val videoRequests = mutableSetOf<String>()

    private fun requestMediaTitle(media: Media, onSuccess: (() -> Unit)? = null) {
        val tmdb = if (media.tmdbId > 0) media.tmdbId else {
            val strId = media.id.replace("^(?:seerr|tmdb)-(?:movie-|tv-)?".toRegex(), "")
            strId.toIntOrNull() ?: 0
        }
        if (tmdb <= 0) {
            toast("Cannot request: TMDB ID not found for this title.")
            return
        }
        if (!videoRequests.add(media.id)) return
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast(e.message ?: "Request failed")
            } finally { videoRequests.remove(media.id) }
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
                    if (streamData.optBoolean("isTranscoding") ||
                        Uri.parse(streamUrl).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
                        toast("This title requires streaming conversion and cannot be downloaded as a file.")
                        return@launch
                    }
                    val token = settings.token
                    val internal = api.isServerUrl(streamUrl)
                    val downloadUri = Uri.parse(api.absolute(streamUrl))
                    val fileName = "${item.title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")}.mp4"
                    val request = DownloadManager.Request(downloadUri)
                        .setTitle(item.title)
                        .setDescription("HomeStream Download")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .apply {
                            if (android.os.Build.VERSION.SDK_INT >= 29) setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, fileName)
                            else setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_MOVIES, fileName)
                        }
                        .apply {
                            if (internal && token.isNotBlank()) addRequestHeader("Authorization", "Bearer $token")
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
            val rawId = (if (item.id.startsWith("seerr-") || item.id.startsWith("tmdb-")) item.id else parent?.id ?: item.id)
                .replace("^(?:seerr|tmdb)-(?:movie-|tv-)?".toRegex(), "")
            rawId.toIntOrNull() ?: 0
        }
        val isTv = item.type == "Series" || item.type == "Episode" || (parent != null && parent.type == "Series")
        val mediaType = if (isTv) "tv" else "movie"
        val seasonNum = item.json.optInt("seasonNumber", 1).takeIf { it > 0 } ?: 1
        val episodeNum = item.json.optInt("episodeNumber", 1).takeIf { it > 0 } ?: 1


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
        stopService(Intent(this, MusicService::class.java))
        playbackWasLaunched = true
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("media", item.json.toString())
            .putExtra("fromBeginning", fromBeginning))
    }

    private val profilePage = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            policyReady = false; catalogCache.clear(); resetNavigationHistory(); verifyProfilePolicy()
        }
    }

    private val exportifyFilePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        if (uri != null) {
            importExportifyUri(uri)
        }
    }

    private fun profiles() {
        stopService(Intent(this, MusicService::class.java))
        if (!settings.canUseProfiles) return
        profilePage.launch(Intent(this, ProfilesActivity::class.java))
        if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        else @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openServerSettingsDialog() {
        lifecycleScope.launch {
            try {
                if (api.call("capabilities").optBoolean("canManageIntegrations")) {
                    AlertDialog.Builder(this@MainActivity).setTitle("Server settings")
                        .setItems(arrayOf("Connection", "Admin integrations")) { _, choice ->
                            if (choice == 1) adminIntegrations() else connectionSettingsDialog()
                        }.show()
                } else connectionSettingsDialog()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                connectionSettingsDialog()
            }
        }
    }

    private fun connectionSettingsDialog() {
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

        form.addView(label("Cache limits: 256 MB artwork + 16 MB metadata on disk. Old entries are automatically evicted.", 12f).apply { setTextColor(MUTED) })
        form.addView(action("Clear cache") {
            (application as HomeStreamApplication).playbackReports.launch {
                BrowseCache.clear()
                coil.Coil.imageLoader(applicationContext).memoryCache?.clear()
                coil.Coil.imageLoader(applicationContext).diskCache?.clear()
                withContext(Dispatchers.Main) { toast("Cache cleared") }
            }
        })

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
        var userLookupJob: Job? = null
        fun fetchJellyfinUsers(serverUrl: String) {
            userLookupJob?.cancel()
            val clean = serverUrl.trim().trimEnd('/')
            if (clean.isBlank()) return
            userLookupJob = lifecycleScope.launch(Dispatchers.IO) {
                delay(300)
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
                                if (extUrlInput.text.toString().trim().trimEnd('/') != clean) return@withContext
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
                            check(token.isNotBlank()) { "Server did not return a login token" }
                            val userObj = resp.optJSONObject("user")
                            withContext(Dispatchers.Main) {
                                settings.saveServer(local = "", external = cleanUrl, token = token, type = "jellyfin", name = userObj?.optString("name", username) ?: username)
                                userObj?.optString("id")?.let { if (it.isNotBlank()) settings.profile = it }
                                toast("Signed in as $username!")
                                jDialog.dismiss()
                                resetNavigationHistory(); loadTab("home")
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
        var connectionDialog: AlertDialog? = null
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
            text = "LOCAL — AUTO DISCOVER & PAIR"; textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD
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
            setOnClickListener { connectionDialog?.dismiss(); openJellyfinLoginDialog() }
        }
        form.addView(jellyfinRemoteBtn, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(14) })

        var discoveryJob: Job? = null
        val dialog = AlertDialog.Builder(this).setTitle("Connect HomeStream")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton(if (first) "Close" else "Cancel") { _, _ ->
                discoveryJob?.cancel()
                if (first) finish()
            }
            .apply { if (!first) setNeutralButton("Disconnect") { _, _ -> discoveryJob?.cancel(); settings.clear(); resetNavigationHistory(); setup() } }.create()

        connectionDialog = dialog
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
                    isClickable = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
                val info = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); isFocusable = false }
                info.addView(label(s.name, 13f, true))
                info.addView(TextView(this@MainActivity).apply { text = s.url; textSize = 11f; setTextColor(MUTED) })
                sCard.addView(info)

                val pairBtn = Button(this@MainActivity).apply {
                    text = "Connect"; textSize = 13f; setTextColor(Color.BLACK); isAllCaps = false
                    isFocusable = true; isClickable = true
                    contentDescription = "Connect to ${s.name}"
                    background = rounded(Color.WHITE, radius = dp(6).toFloat())
                    minHeight = dp(48); minimumHeight = dp(48)
                    setPadding(dp(16), 0, dp(16), 0)
                    setOnClickListener {
                        isEnabled = false; text = "Pairing…"
                        lifecycleScope.launch {
                            try {
                                val candidate = object : ConnectionSettings {
                                    override val baseUrl = s.url
                                    override val token = ""
                                    override var profile = ""
                                }
                                val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                                    ?: java.util.UUID.randomUUID().toString()
                                val result = HomeStreamApi(candidate).call("auth/pair", body = JSONObject()
                                    .put("deviceId", deviceId)
                                    .put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
                                val pairedToken = result.getString("token")
                                val external = result.getString("externalUrl")
                                check(pairedToken.isNotBlank() && external.toHttpUrlOrNull() != null) { "Invalid pairing response" }
                                if (dialog.isShowing) {
                                    settings.saveServer(s.url, external, pairedToken, name = s.name)
                                    dialog.dismiss(); resetNavigationHistory(); loadTab("home")
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                scanStatus.text = e.message ?: "Pairing failed"
                            } finally { isEnabled = true; text = "Connect" }
                        }
                    }
                }
                sCard.addView(pairBtn, LinearLayout.LayoutParams(-2, dp(48)))
                if (isTelevision()) addTvFocusIndicators(sCard)

                discoveredServersContainer.addView(sCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                if (isTelevision() && foundServers.size == 1 && dialog.isShowing && !form.hasFocus()) pairBtn.post { if (dialog.isShowing) pairBtn.requestFocus() }
            }
        }

        discoveryJob?.invokeOnCompletion {
            runOnUiThread {
                if (dialog.isShowing) scanStatus.text = if (foundServers.isEmpty()) "No servers found. Check Wi-Fi, then retry." else "Found ${foundServers.size} server(s)"
            }
        }
        val retry = Button(this).apply {
            text = "Scan again"; isAllCaps = false
            setOnClickListener { dialog.dismiss(); setup() }
        }
        discoverySection.addView(retry)
        dialog.show()
        if (isTelevision()) addTvFocusIndicators(form)
    }

    private fun isWithin(view: View, ancestor: View): Boolean {
        var current: android.view.ViewParent? = view.parent
        if (view === ancestor) return true
        while (current != null) { if (current === ancestor) return true; current = current.parent }
        return false
    }

    private fun addTvFocusIndicators(view: View) {
        if (view is Button || (view is TextView && view.isClickable)) {
            val normal = view.background
            view.isFocusable = true
            view.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF383838.toInt(), Color.WHITE))
                addState(intArrayOf(), normal ?: rounded(Color.TRANSPARENT))
            }
            (view as TextView).setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Color.WHITE, view.currentTextColor)))
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) addTvFocusIndicators(view.getChildAt(index))
    }

    private fun updateBrowseContent(data: JSONObject, draw: (JSONObject) -> Unit) {
        val visible = content
        val viewport = visible.parent as? ScrollView
        val scrollY = viewport?.scrollY ?: 0
        val focused = currentFocus
        val recyclerStates = mutableMapOf<String, android.os.Parcelable>()
        fun eachRecycler(view: View, action: (RecyclerView, String) -> Unit) {
            if (view is RecyclerView) view.getTag(R.id.browse_key)?.toString()?.let { action(view, it) }
            else if (view is ViewGroup) for (index in 0 until view.childCount) eachRecycler(view.getChildAt(index), action)
        }
        eachRecycler(visible) { recycler, key -> recycler.layoutManager?.onSaveInstanceState()?.let { recyclerStates[key] = it } }
        val staged = column().apply { setPadding(visible.paddingLeft, visible.paddingTop, visible.paddingRight, visible.paddingBottom) }
        if (screen == "details") { trailerView?.apply { stopLoading(); destroy() }; trailerView = null }
        heroRotationJob?.cancel()
        content = staged
        try { draw(data) } finally { content = visible }
        fun key(view: View, index: Int): String = view.getTag(R.id.browse_key)?.toString()
            ?: if (view.id != View.NO_ID) "id:${view.id}" else "${view.javaClass.name}:$index"
        fun reconcile(old: android.view.ViewGroup, fresh: android.view.ViewGroup) {
            val previous = (0 until old.childCount).associate { index -> key(old.getChildAt(index), index) to old.getChildAt(index) }
            val chosen = (0 until fresh.childCount).map { index ->
                val incoming = fresh.getChildAt(index)
                val prior = previous[key(incoming, index)]
                val signature = incoming.getTag(R.id.browse_signature)
                when {
                    prior != null && signature != null && prior.getTag(R.id.browse_signature) == signature -> prior
                    prior is HorizontalScrollView && incoming is HorizontalScrollView && incoming.getTag(R.id.browse_key) != null -> {
                        val oldRow = prior.getChildAt(0) as? LinearLayout
                        val newRow = incoming.getChildAt(0) as? LinearLayout
                        if (oldRow != null && newRow != null) reconcile(oldRow, newRow)
                        prior
                    }
                    else -> incoming
                }
            }
            old.removeAllViews()
            chosen.forEach { view -> (view.parent as? ViewGroup)?.removeView(view); old.addView(view) }
        }
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) android.transition.TransitionManager.beginDelayedTransition(visible,
            android.transition.AutoTransition().apply { duration = 140 })
        reconcile(visible, staged)
        if (isTelevision()) addTvFocusIndicators(visible)
        visible.post {
            eachRecycler(visible) { recycler, key -> recyclerStates[key]?.let { recycler.layoutManager?.onRestoreInstanceState(it) } }
            viewport?.scrollTo(0, scrollY)
            focused?.takeIf { it.isAttachedToWindow }?.requestFocus()
        }
    }

    private fun browsePlaceholder() {
        val row = LinearLayout(this).apply { setPadding(gutter, dp(24), gutter, dp(16)) }
        repeat(if (isTelevision()) 5 else 3) {
            row.addView(View(this).apply { background = rounded(0xFF202020.toInt(), radius = dp(8).toFloat()) },
                LinearLayout.LayoutParams(0, dp(if (isTelevision()) 200 else 150), 1f).apply { marginEnd = dp(8) })
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun fetch(path: String, params: Map<String, String> = emptyMap(), draw: (JSONObject) -> Unit) {
        work?.cancel(); val current = ++generation
        val cached = api.cached(path, params)
        shell(); screenLoading = cached == null
        if (cached != null) {
            draw(cached)
            if (isTelevision()) addTvFocusIndicators(content)
        } else browsePlaceholder()
        var rendered = cached?.toString()
        if (cached != null && api.cacheFresh(path, params)) return
        work = lifecycleScope.launch {
            try {
                if (cached == null || api.cacheSeeded(path, params)) api.diskCached(path, params)?.let { disk ->
                    if (generation != current) return@launch
                    content.removeAllViews(); draw(disk); screenLoading = false
                    if (isTelevision()) addTvFocusIndicators(content)
                    rendered = disk.toString()
                }
                if (rendered != null && api.cacheFresh(path, params)) return@launch
                val data = api.call(path, params)
                if (generation != current) return@launch
                screenLoading = false
                if (rendered != null) {
                    if (rendered != data.toString()) updateBrowseContent(data, draw)
                    return@launch
                }
                content.removeAllViews(); draw(data)
                if (isTelevision()) addTvFocusIndicators(content)
                content.animate().cancel()
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    content.alpha = 0.3f; content.translationY = dp(6).toFloat()
                    content.animate().alpha(1f).translationY(0f).setDuration(160).start()
                }
                val expectedContent = content
                if (isTelevision()) content.post {
                    if (content !== expectedContent || generation != current) return@post
                    val focused = currentFocus
                    if (focused != null && !isWithin(focused, content)) return@post
                    val targets = ArrayList<View>(); content.addFocusables(targets, View.FOCUS_FORWARD)
                    targets.firstOrNull()?.requestFocus()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                if (generation != current) return@launch
                screenLoading = false
                if (rendered != null) {
                    android.util.Log.w("HomeStream", "Keeping cached content while refresh is unavailable")
                    return@launch
                }
                content.removeAllViews(); heading("Unable to connect")
                message(e.message ?: "Check your HomeStream connection.")
                content.addView(action("Retry") { fetch(path, params, draw) })
                content.addView(action("Server settings") { setup() })
            }
        }
    }

    override fun onDestroy() {
        trailerView?.apply { stopLoading(); destroy() }; trailerView = null
        super.onDestroy()
    }

    private var hubJob: Job? = null
    private var hubSearch: Job? = null
    private val albumRequests = mutableSetOf<String>()
    private var hubFilter = "all"
    private var hubQuery = ""
    private var musicQuery = ""
    private var musicCategory = "albums"
    private var musicArtist: String? = null
    private var selectedPlaylistId: String? = null

    private fun hubFilters(options: List<Pair<String, String>>, selected: String, change: (String) -> Unit) {
        val row = LinearLayout(this).apply { setPadding(gutter, dp(4), gutter, dp(8)) }
        options.forEach { (key, title) -> row.addView(categoryPill(title, key == selected) { change(key) }) }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun mediaHub(tab: String) {
        if (tab == "music" && settings.isKids) return
        rememberScreen(tab) { mediaHub(tab) }
        work?.cancel(); hubJob?.cancel(); hubSearch?.cancel(); ++generation
        currentTab = tab; screen = tab
        if (hubFilter == "music" && settings.isKids) hubFilter = "all"
        shell()
        val music = tab == "music"
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()

        if (music) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val timeGreeting = if (hour in 5..11) "Good morning" else if (hour in 12..17) "Good afternoon" else "Good evening"
            val userProfile = settings.profileName.ifBlank { "Nathan" }

            val titleBar = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(gutter, dp(12), gutter, dp(4))
            }
            val titleCol = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            titleCol.addView(label("$timeGreeting, $userProfile", 24f, true))
            titleCol.addView(TextView(this).apply {
                text = "Music Hub • Per-Profile Library"
                textSize = 12f
                setTextColor(SPOTIFY_GREEN)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, 0)
            })
            titleBar.addView(titleCol)
            content.addView(titleBar)

            hubFilters(listOf("albums" to "Albums", "artists" to "Artists", "songs" to "Songs", "playlists" to "Playlists"), musicCategory) {
                musicCategory = it; musicArtist = null; selectedPlaylistId = null; mediaHub(tab)
            }
        } else {
            val titleBar = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(gutter, dp(8), gutter, dp(4))
            }
            titleBar.addView(label("Discover", 24f, true), LinearLayout.LayoutParams(0, -2, 1f))
            titleBar.addView(action("Categories") { loadCategories("requests") }.apply {
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.nav_list, 0, 0, 0)
                compoundDrawablePadding = dp(4)
                compoundDrawableTintList = textColors
            })
            content.addView(titleBar)
            val options = listOf("all" to "All media", "movie" to "Movies", "tv" to "TV")
            hubFilters(options, hubFilter) { hubFilter = it; mediaHub(tab) }
        }

        val results = column().apply { setPadding(gutter, dp(8), gutter, dp(12)) }
        val input = EditText(this).apply {
            hint = when {
                !music -> when (hubFilter) {
                    "movie" -> "Search movies"
                    "tv" -> "Search TV shows"
                    else -> "Search movies and TV"
                }
                musicCategory == "songs" -> "Search songs..."
                musicCategory == "artists" -> "Search artists..."
                else -> "Search music catalog & albums..."
            }
            setTextColor(Color.WHITE); setHintTextColor(MUTED)
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            setText(if (tab == "music") musicQuery else hubQuery)
        }
        input.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFF222222.toInt(), if (music) SPOTIFY_GREEN else 0xFF777777.toInt(), dp(12).toFloat()))
            addState(intArrayOf(), rounded(0xFF1D1D1D.toInt(), radius = dp(12).toFloat()))
        }
        input.setCompoundDrawablesWithIntrinsicBounds(R.drawable.nav_search, 0, 0, 0)
        input.compoundDrawableTintList = ColorStateList.valueOf(if (music) SPOTIFY_GREEN else MUTED)
        input.compoundDrawablePadding = dp(10)
        input.setPadding(dp(12), dp(8), dp(12), dp(8))
        input.textSize = 14f
        content.addView(input, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(gutter, dp(4), gutter, dp(8)) })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                hubSearch?.cancel(); hubJob?.cancel()
                val query = s.toString().trim()
                if (tab == "music") { musicQuery = query; musicArtist = null } else hubQuery = query
                hubSearch = lifecycleScope.launch { delay(400); discoverResults(results, query, if (tab == "music") "music" else hubFilter) }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        content.addView(results)
        hubJob = lifecycleScope.launch { discoverResults(results, input.text.toString(), if (tab == "music") "music" else hubFilter) }
    }

    private suspend fun discoverResults(target: LinearLayout, query: String, filter: String) {
        target.removeAllViews(); target.addView(label("Loading…"))
        val staged = column()
        try {
            if (filter != "music") {
                try {
                    val data = api.call(if (query.isBlank()) "home" else "search", if (query.isBlank()) emptyMap() else mapOf("q" to query))
                    val items = if (query.isBlank()) data.objects("requestSections").flatMap { it.mediaList("items") }
                        else data.mediaList("results")
                    val videos = items.distinctBy { it.id }
                    listOf("movie" to "Movies", "tv" to "TV Shows").forEach { (kind, title) ->
                        if (filter == "all" || filter == kind) {
                            val matches = videos.filter {
                                if (kind == "movie") it.type == "Movie" else it.type in listOf("Series", "Episode")
                            }
                            if (matches.isNotEmpty()) {
                                staged.addView(hubSection(title))
                                artworkGrid(staged, matches.map { item ->
                                    val icon = if (kind == "movie") R.drawable.nav_movies else R.drawable.nav_series
                                    ArtworkTile(item.title, item.metadata.ifBlank { if (kind == "movie") "Movie" else item.type },
                                        api.absolute(item.poster), poster = true, placeholder = icon) { details(item) }
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    staged.addView(label(e.message ?: "Video discovery is unavailable"))
                }
            }
            if (filter == "music" && !settings.isKids) {
                try {
                    if (currentTab == "music" && musicCategory == "playlists") {
                        if (selectedPlaylistId != null) {
                            renderPlaylistDetail(staged, selectedPlaylistId!!)
                        } else {
                            renderPlaylistsGrid(staged, query)
                        }
                    } else {
                        var offset = 0
                        val musicAlbums = mutableListOf<MusicAlbum>()
                        val librarySongs = currentTab == "music" && musicCategory == "songs"
                        val seen = mutableSetOf<String>()
                        do {
                            val page = api.call("music", if (query.isBlank() || librarySongs) mapOf("offset" to "$offset") else mapOf("q" to query))
                            if (!page.optBoolean("configured")) { staged.addView(label("Music is not configured")); break }
                            val albums = page.objects("albums").map(::MusicAlbum)
                            musicAlbums.addAll(albums.filter { seen.add(it.id) })
                            offset += albums.size
                            if (albums.isEmpty() || (query.isNotBlank() && !librarySongs) || offset >= page.optInt("total")) break
                        } while (true)
                        when {
                            currentTab == "music" && musicCategory == "artists" -> {
                                val artists = musicAlbums.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                                val selected = musicArtist
                                if (selected == null) artworkGrid(staged, artists.map { (artist, albums) ->
                                    ArtworkTile(artist, "${albums.size} ${if (albums.size == 1) "album" else "albums"}", albumArtwork(albums.first())) {
                                        musicArtist = artist; mediaHub("music")
                                    }
                                }) else {
                                    staged.addView(action("All artists") { musicArtist = null; mediaHub("music") })
                                    staged.addView(label(selected, 20f, true))
                                    artworkGrid(staged, artists[selected].orEmpty().map(::albumTile))
                                }
                            }
                            librarySongs -> {
                                musicAlbums.filter { it.inLibrary }.forEach { album ->
                                    val tracks = api.call("music/album", mapOf("albumId" to album.id)).objects("tracks").map(::MusicTrack)
                                    val albumArt = albumArtwork(album)
                                    tracks.forEachIndexed { index, track ->
                                        if (query.isBlank() || listOf(track.title, album.title, album.artist).any { it.contains(query, ignoreCase = true) }) {
                                            val songArt = track.coverUrl.takeIf { it.isNotBlank() && it != "null" }?.let(api::absolute) ?: albumArt
                                            staged.addView(musicTrackRow(track.title, "${album.artist} • ${album.title}", track.json.optInt("trackNumber", index + 1), coverUrl = songArt) { startMusicTracks(tracks, album, index) })
                                        }
                                    }
                                }
                                if (staged.childCount == 0) staged.addView(label("No imported songs found"))
                            }
                            else -> {
                                if (currentTab != "music" && musicAlbums.isNotEmpty()) staged.addView(hubSection("Albums"))
                                artworkGrid(staged, musicAlbums.map(::albumTile))
                            }
                        }
                        if (query.isNotBlank() && !librarySongs) staged.addView(label("Showing up to 30 catalog albums. Refine your search for more.", 12f))
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    staged.addView(label(e.message ?: "Music is unavailable"))
                }
            }
            target.removeAllViews()
            if (staged.childCount == 0) target.addView(label("No results"))
            while (staged.childCount > 0) { val child = staged.getChildAt(0); staged.removeViewAt(0); target.addView(child) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            target.removeAllViews(); target.addView(label(e.message ?: "Discover is unavailable"))
        }
    }

    private fun hubSection(title: String) = label(title, 20f, true).apply {
        setPadding(dp(4), dp(12), dp(4), dp(6))
    }

    private fun albumArtwork(album: MusicAlbum): String? = album.json.optString("coverUrl")
        .takeIf { it.isNotBlank() && it != "null" }?.let(api::absolute)

    private fun albumTile(album: MusicAlbum) = ArtworkTile(album.title,
        if (album.inLibrary) album.artist
        else if (album.isDownloaded) "${album.artist} • On Server (Instant Add)"
        else if (album.requested) "${album.artist} • Requested"
        else album.artist,
        albumArtwork(album)) { openAlbum(album) }

    private fun openAlbum(album: MusicAlbum) {
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        lifecycleScope.launch {
            try {
                val data = api.call("music/album", mapOf("albumId" to album.id))
                val current = MusicAlbum(data.getJSONObject("album"))
                val tracks = data.objects("tracks").map(::MusicTrack)
                val form = column().apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
                val albumHeader = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(14))
                }
                albumHeader.addView(LibraryArtwork(this@MainActivity).apply { show(albumArtwork(current)) }, LinearLayout.LayoutParams(dp(96), dp(96)))
                albumHeader.addView(column().apply {
                    setPadding(dp(14), 0, 0, 0)
                    addView(label(current.title, 18f, true))
                    addView(label(current.artist, 14f).apply { setTextColor(SPOTIFY_GREEN); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(2), 0, 0) })
                    addView(label("${releaseLabel(current.json.optString("releaseDate").ifBlank { current.json.optString("year") })} • ${tracks.size.takeIf { it > 0 } ?: current.trackCount} tracks", 12f).apply { setTextColor(MUTED); setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                form.addView(albumHeader)

                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setView(ScrollView(this@MainActivity).apply { addView(form) })
                    .setNegativeButton("Close", null)
                    .create()

                // Actions row
                if (current.inLibrary && tracks.isNotEmpty()) {
                    // Big green Spotify Play button
                    val playBtn = Button(this@MainActivity).apply {
                        text = "▶  Play Album"
                        textSize = 14f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.BLACK)
                        isAllCaps = false
                        background = rounded(SPOTIFY_GREEN, radius = dp(24).toFloat())
                        minHeight = dp(44); minimumHeight = dp(44)
                        setOnClickListener {
                            dialog.dismiss()
                            startMusicTracks(tracks, current, 0)
                        }
                    }
                    form.addView(playBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })

                    val removeBtn = Button(this@MainActivity).apply {
                        text = "Remove from My Profile"
                        textSize = 12f
                        setTextColor(MUTED)
                        isAllCaps = false
                        background = rounded(0x22FFFFFF.toInt(), radius = dp(8).toFloat())
                        minHeight = dp(36); minimumHeight = dp(36)
                        setOnClickListener {
                            dialog.dismiss()
                            lifecycleScope.launch {
                                try {
                                    api.call("music/profile-album", method = "DELETE", body = JSONObject().put("albumId", current.id))
                                    toast("Removed ${current.title} from your profile")
                                    mediaHub("music")
                                } catch (e: Exception) {
                                    toast("Failed: ${e.message}")
                                }
                            }
                        }
                    }
                    form.addView(removeBtn, LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(14) })
                } else if (current.isDownloaded && !current.inLibrary) {
                    val addBtn = Button(this@MainActivity).apply {
                        text = "+ Add to My Profile (Instant)"
                        textSize = 13.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.BLACK)
                        isAllCaps = false
                        background = rounded(SPOTIFY_GREEN, radius = dp(24).toFloat())
                        minHeight = dp(44); minimumHeight = dp(44)
                        setOnClickListener {
                            dialog.dismiss()
                            lifecycleScope.launch {
                                try {
                                    api.call("music/profile-album", body = JSONObject().put("albumId", current.id))
                                    toast("Added ${current.title} to your profile library!")
                                    mediaHub("music")
                                } catch (e: Exception) {
                                    toast("Failed: ${e.message}")
                                }
                            }
                        }
                    }
                    form.addView(addBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })
                } else if (!current.inLibrary) {
                    val reqText = if (current.requested) "Requested • downloading via Soulseek" else "Download / Request Album"
                    val reqBtn = Button(this@MainActivity).apply {
                        text = reqText
                        textSize = 13f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        isAllCaps = false
                        background = rounded(0x44FFFFFF.toInt(), radius = dp(8).toFloat())
                        isEnabled = !current.requested && current.id !in albumRequests
                        setOnClickListener {
                            if (albumRequests.add(current.id)) {
                                dialog.dismiss()
                                lifecycleScope.launch {
                                    try {
                                        val response = api.call("music/request", body = JSONObject().put("musicbrainzId", current.id))
                                        toast(response.optString("message", response.optString("status", "Request submitted")))
                                        mediaHub("music")
                                    } catch (e: Exception) {
                                        toast(e.message ?: "Request failed")
                                    } finally {
                                        albumRequests.remove(current.id)
                                    }
                                }
                            }
                        }
                    }
                    form.addView(reqBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })
                }

                if (tracks.isNotEmpty()) {
                    form.addView(label("Tracks", 15f, true).apply { setPadding(0, dp(4), 0, dp(8)) })
                    tracks.forEachIndexed { index, track ->
                        val dur = track.durationSeconds
                        val durText = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
                        form.addView(musicTrackRow(track.title, durText, track.json.optInt("trackNumber", index + 1)) {
                            dialog.dismiss()
                            startMusicTracks(tracks, current, index)
                        })
                    }
                }
                dialog.show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast(e.message ?: "Album unavailable")
            }
        }
    }

    private fun startMusicTracks(tracks: List<MusicTrack>, album: MusicAlbum, index: Int) {
        if (settings.isKids) return
        val payload = org.json.JSONArray().apply {
            tracks.forEach { put(JSONObject(it.json.toString()).put("coverUrl", it.json.optString("coverUrl").ifBlank { album.json.optString("coverUrl") })) }
        }
        castContext(this)?.sessionManager?.currentCastSession?.remoteMediaClient?.stop()
        androidx.core.content.ContextCompat.startForegroundService(this,
            Intent(this, MusicService::class.java).putExtra("tracks", payload.toString()).putExtra("index", index))
        musicControls()
    }

    private fun musicControls() {
        if (!settings.isKids) startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    private suspend fun renderPlaylistsGrid(staged: LinearLayout, query: String = "") {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            val btn = action("+ Import Playlists (Spotify / Exportify)", primary = true) {
                showSpotifyImportDialog()
            }.apply {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE))
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF1ED760.toInt()))
                    addState(intArrayOf(), rounded(0xFF1DB954.toInt()))
                }
                setTextColor(Color.BLACK)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_download, 0, 0, 0)
                compoundDrawablePadding = dp(6)
            }
            addView(btn)
        }
        staged.addView(banner)

        val playlistsData = runCatching { api.call("music/playlists") }.getOrNull()
        val allPlaylists = playlistsData?.objects("playlists")?.map(::MusicPlaylist).orEmpty()
        val playlists = allPlaylists.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }

        if (playlists.isEmpty()) {
            val emptyCard = column().apply {
                setPadding(dp(16), dp(24), dp(16), dp(24))
                gravity = Gravity.CENTER_HORIZONTAL
                focusableCard(0xFF1A1A1A.toInt())
                addView(label("No Playlists Yet", 18f, true).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(TextView(context).apply {
                    text = "Import public Spotify playlists or Exportify (.csv/.zip) files. Tracks are automatically downloaded in MP3 format via Soulseek (slskd) directly into your personal music profile."
                    textSize = 13f
                    setTextColor(MUTED)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(8), dp(8), dp(16))
                })
                addView(action("Import Playlists", primary = true) {
                    showSpotifyImportDialog()
                })
            }
            staged.addView(emptyCard)
            return
        }

        artworkGrid(staged, playlists.map { pl ->
            val sub = if (pl.totalTracks == 0) "0 tracks"
            else if (pl.downloadedTracks >= pl.totalTracks) "${pl.totalTracks} tracks • Downloaded"
            else "${pl.downloadedTracks}/${pl.totalTracks} downloaded • Soulseek"

            ArtworkTile(
                title = pl.name,
                subtitle = sub,
                cover = pl.coverUrl?.takeIf { it.isNotBlank() }?.let(api::absolute),
                poster = false,
                placeholder = R.drawable.nav_music
            ) {
                selectedPlaylistId = pl.id
                mediaHub("music")
            }
        })
    }

    private suspend fun renderPlaylistDetail(staged: LinearLayout, playlistId: String) {
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        val data = runCatching { api.call("music/playlist", mapOf("playlistId" to playlistId)) }.getOrNull()
        val plObj = data?.optJSONObject("playlist")
        if (plObj == null) {
            staged.addView(action("← All Playlists") { selectedPlaylistId = null; mediaHub("music") })
            staged.addView(label("Playlist not found or removed"))
            return
        }
        val playlist = MusicPlaylist(plObj)
        val tracks = data.objects("tracks").map(::MusicPlaylistTrack)

        staged.addView(action("← All Playlists") {
            selectedPlaylistId = null
            mediaHub("music")
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
            gravity = Gravity.CENTER_VERTICAL
            val coverView = ImageView(context).apply {
                val cover = playlist.coverUrl?.takeIf { it.isNotBlank() }?.let(api::absolute)
                if (cover != null) {
                    coil.Coil.imageLoader(context).enqueue(
                        coil.request.ImageRequest.Builder(context)
                            .data(cover)
                            .target(this)
                            .placeholder(R.drawable.nav_music)
                            .error(R.drawable.nav_music)
                            .crossfade(true)
                            .build()
                    )
                } else {
                    setImageResource(R.drawable.nav_music)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFF222222.toInt())
                clipToOutline = true
            }
            addView(coverView, LinearLayout.LayoutParams(dp(80), dp(80)).apply { marginEnd = dp(14) })

            val infoCol = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            infoCol.addView(label(playlist.name, 22f, true))
            if (playlist.description.isNotBlank()) {
                infoCol.addView(TextView(context).apply {
                    text = playlist.description
                    textSize = 12f
                    setTextColor(MUTED)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
            }
            val progressText = "${playlist.downloadedTracks}/${playlist.totalTracks} downloaded • Soulseek"
            infoCol.addView(TextView(context).apply {
                text = progressText
                textSize = 12f
                setTextColor(if (playlist.downloadedTracks >= playlist.totalTracks && playlist.totalTracks > 0) SPOTIFY_GREEN else 0xFF64B5F6.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            addView(infoCol)
        }
        staged.addView(header)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(12))

            val playAllBtn = action("▶  Play All", primary = true) {
                playPlaylist(tracks, playlist.coverUrl, startIndex = 0)
            }.apply {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE))
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF1ED760.toInt()))
                    addState(intArrayOf(), rounded(SPOTIFY_GREEN))
                }
                setTextColor(Color.BLACK)
            }
            addView(playAllBtn)

            val shuffleBtn = action("🔀  Shuffle") {
                val ready = tracks.filter { it.isReady }
                if (ready.isEmpty()) {
                    toast("No downloaded tracks ready to play yet")
                } else {
                    playPlaylist(ready.shuffled(), playlist.coverUrl, startIndex = 0)
                }
            }
            addView(shuffleBtn)

            val refreshBtn = action("⟳  Refresh") {
                mediaHub("music")
            }
            addView(refreshBtn)

            val deleteBtn = action("🗑  Delete") {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete Playlist")
                    .setMessage("Remove '${playlist.name}' from your profile? Downloaded music files will remain in your library.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                api.call("music/playlist", mapOf("playlistId" to playlist.id), method = "DELETE")
                                toast("Playlist deleted")
                                selectedPlaylistId = null
                                mediaHub("music")
                            } catch (e: Exception) {
                                toast(e.message ?: "Could not delete playlist")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            addView(deleteBtn)
        }
        staged.addView(actions)

        if (tracks.isEmpty()) {
            staged.addView(label("No tracks in playlist", 14f).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
        } else {
            tracks.forEachIndexed { idx, track ->
                staged.addView(renderPlaylistTrackRow(track, idx, playlist.coverUrl) {
                    if (track.isReady) {
                        playPlaylist(tracks, playlist.coverUrl, startIndex = idx)
                    } else {
                        val msg = when (track.status) {
                            "downloading" -> "Downloading track via Soulseek (slskd)..."
                            "searching" -> "Searching Soulseek network for track..."
                            "failed" -> "Download failed: ${track.errorMessage.ifBlank { "No Soulseek match found" }}"
                            else -> "Track download queued in Soulseek..."
                        }
                        toast(msg)
                    }
                })
            }
        }
    }

    private fun renderPlaylistTrackRow(track: MusicPlaylistTrack, index: Int, defaultCover: String?, click: () -> Unit): View {
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        val AMBER = 0xFFFFB300.toInt()
        val CYAN = 0xFF4DD0E1.toInt()
        val RED_ERR = 0xFFFF5252.toInt()

        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            focusableCard(if (track.isReady) 0xFF202020.toInt() else 0xFF181818.toInt())

            addView(label((index + 1).toString(), 13f, true).apply {
                setTextColor(if (track.isReady) Color.WHITE else MUTED)
            }, LinearLayout.LayoutParams(dp(30), -2))

            val infoCol = column().apply {
                addView(label(track.title, 15f, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (!track.isReady) setTextColor(0xFFCCCCCC.toInt())
                })
                val sub = "${track.artist} • ${track.album}"
                addView(label(sub, 12f).apply {
                    setTextColor(MUTED)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))

            val statusBadge = TextView(context).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                when (track.status) {
                    "completed" -> {
                        val dur = track.durationSeconds
                        val durText = if (dur > 0) "%d:%02d".format(dur / 60, dur % 60) else ""
                        text = if (durText.isNotBlank()) "✓ Ready  $durText" else "✓ Ready"
                        setTextColor(SPOTIFY_GREEN)
                    }
                    "downloading" -> {
                        text = "Downloading (slskd)"
                        setTextColor(AMBER)
                    }
                    "searching" -> {
                        text = "Searching Soulseek"
                        setTextColor(CYAN)
                    }
                    "failed" -> {
                        text = "Failed"
                        setTextColor(RED_ERR)
                    }
                    else -> {
                        text = "Queued"
                        setTextColor(MUTED)
                    }
                }
                setPadding(dp(6), dp(2), dp(8), dp(2))
            }
            addView(statusBadge)

            val icon = ImageView(context).apply {
                setImageResource(if (track.isReady) R.drawable.ic_play_arrow else R.drawable.ic_download)
                setColorFilter(if (track.isReady) Color.WHITE else MUTED)
            }
            addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))

            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            setOnClickListener { click() }
        }
    }

    private fun playPlaylist(tracks: List<MusicPlaylistTrack>, defaultCover: String?, startIndex: Int = 0) {
        if (settings.isKids) return
        val readyTracks = tracks.filter { it.isReady }
        if (readyTracks.isEmpty()) {
            toast("No downloaded tracks ready to play yet")
            return
        }
        val targetIndex = if (startIndex < tracks.size && tracks[startIndex].isReady) {
            val sel = tracks[startIndex]
            readyTracks.indexOfFirst { it.id == sel.id }.coerceAtLeast(0)
        } else {
            0
        }
        val payload = org.json.JSONArray().apply {
            readyTracks.forEach { t ->
                val obj = JSONObject(t.json.toString())
                if (!obj.has("coverUrl") || obj.optString("coverUrl").isBlank()) {
                    obj.put("coverUrl", defaultCover ?: "")
                }
                if (!obj.has("trackNumber")) {
                    obj.put("trackNumber", t.position + 1)
                }
                put(obj)
            }
        }
        castContext(this)?.sessionManager?.currentCastSession?.remoteMediaClient?.stop()
        androidx.core.content.ContextCompat.startForegroundService(this,
            Intent(this, MusicService::class.java).putExtra("tracks", payload.toString()).putExtra("index", targetIndex))
        musicControls()
    }

    private fun importExportifyUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val cr = contentResolver
                var displayName = "Exportify Playlist"
                cr.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIdx)
                    }
                }

                val isZip = displayName.endsWith(".zip", ignoreCase = true)
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Could not read file")

                val payload = JSONObject()
                if (isZip) {
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    payload.put("zipBase64", b64)
                } else {
                    val text = String(bytes, Charsets.UTF_8)
                    payload.put("csv", text)
                    payload.put("playlistName", displayName.replace(Regex("\\.csv$", RegexOption.IGNORE_CASE), ""))
                }

                toast("Importing Exportify file...")
                val res = api.call("music/playlists/import", body = payload)
                toast(res.optString("message", "Playlists imported successfully"))
                selectedPlaylistId = null
                mediaHub("music")
            } catch (e: Exception) {
                toast(e.message ?: "Failed importing Exportify file")
            }
        }
    }

    private fun showSpotifyImportDialog() {
        var importDialog: AlertDialog? = null
        val form = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(label("Import Playlists", 18f, true))
            addView(TextView(context).apply {
                text = "Import from Spotify (link or user profile) or Exportify (.csv or .zip). Tracks are automatically downloaded in MP3 format via Soulseek (slskd) directly into your profile's music library."
                textSize = 13f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, dp(12))
            })

            val fileBtn = action("📁  Upload Exportify CSV or ZIP File", primary = true) {
                importDialog?.dismiss()
                exportifyFilePicker.launch("*/*")
            }.apply {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE))
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF1ED760.toInt()))
                    addState(intArrayOf(), rounded(0xFF262626.toInt()))
                }
                setTextColor(Color.WHITE)
            }
            addView(fileBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })

            addView(label("Or paste Spotify link(s) or Exportify CSV:", 13f, true).apply {
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(6))
            })
        }

        val urlInput = EditText(this).apply {
            hint = "https://open.spotify.com/playlist/...\nhttps://open.spotify.com/user/...\nOr paste CSV text"
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF262626.toInt())
            form.addView(this)
        }

        importDialog = AlertDialog.Builder(this)
            .setTitle("Import Playlists")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Start Import") { _, _ ->
                val text = urlInput.text.toString().trim()
                if (text.isBlank()) {
                    toast("Please enter a Spotify URL, Exportify CSV, or select a file")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val payload = JSONObject()
                        if (text.startsWith("Track URI", ignoreCase = true) || text.startsWith("Track Name", ignoreCase = true)) {
                            payload.put("csv", text)
                            payload.put("playlistName", "Exportify Playlist")
                        } else {
                            payload.put("urls", text)
                        }
                        val res = api.call("music/playlists/import", body = payload)
                        toast(res.optString("message", "Import started"))
                        mediaHub("music")
                    } catch (e: Exception) {
                        toast(e.message ?: "Failed to import playlist")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun adminIntegrations() {
        lifecycleScope.launch {
            try {
                if (!api.call("capabilities").optBoolean("canManageIntegrations")) { toast("Administrator sign-in required"); return@launch }
                val data = api.call("integrations")
                val form = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
                data.objects("integrations").forEach { integration ->
                    form.addView(action("${integration.optString("name")} • ${if (integration.optBoolean("configured")) "Configured" else "Disconnected"}") { editIntegration(integration, data.optString("downloadsPath")) })
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Admin integrations").setView(form).setNegativeButton("Close", null).show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast(e.message ?: "Integrations unavailable")
            }
        }
    }

    private fun editIntegration(integration: JSONObject, downloadsPath: String) {
        val form = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        fun field(hintText: String, value: String = "", secret: Boolean = false) = EditText(this).apply {
            hint = hintText; setText(value); setTextColor(Color.WHITE); setHintTextColor(MUTED)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false; form.addView(this)
        }
        val url = field("HTTP(S) URL; blank disconnects", integration.optString("url"))
        val secret = field(if (integration.optBoolean("credentialSet")) "Credential saved; blank preserves" else "Credential", secret = true)
        val username = if (integration.optString("id") == "qbittorrent") field("Username", integration.optString("username")) else null
        val path = if (username != null) field("Backend downloads path", downloadsPath) else null
        val pin = field("Parent PIN (if required)", secret = true)
        val status = label(""); form.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(integration.optString("name")).setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).setNeutralButton("Test", null).create()
        dialog.setOnShowListener {
            fun submit(action: String) {
                val parsed = url.text.toString().trim().toHttpUrlOrNull()
                if (url.text.isNotBlank() && (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null)) {
                    status.text = "Enter an HTTP(S) URL without credentials, query or fragment"; return
                }
                val payload = JSONObject().put("integration", integration.optString("id")).put("action", action)
                    .put("url", url.text.toString().trim()).put("secret", secret.text.toString())
                username?.let { payload.put("username", it.text.toString()) }; path?.let { payload.put("downloadsPath", it.text.toString()) }
                if (pin.text.isNotBlank()) payload.put("parentPin", pin.text.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
                lifecycleScope.launch {
                    try {
                        val response = api.call("integrations", body = payload)
                        status.text = if (response.optBoolean("success")) if (action == "save") "Saved" else "Connection successful" else "Connection test failed"
                        if (action == "save") { secret.text.clear(); pin.text.clear() }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        status.text = e.message ?: "Integration operation failed"
                    } finally {
                        payload.remove("secret"); payload.remove("parentPin")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit("save") }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { submit("test") }
        }
        dialog.setOnDismissListener { secret.text.clear(); pin.text.clear() }; dialog.show()
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

    private var youtubeSubTab = "home"
    private var youtubeSearchQuery = ""
    private var youtubeSearchJob: Job? = null
    private var youtubePodcastFilter = false

    private fun loadYouTube() {
        if (settings.isKids) {
            toast("YouTube is unavailable in Kids mode")
            return
        }
        rememberScreen("youtube") { loadYouTube() }
        work?.cancel(); youtubeSearchJob?.cancel(); ++generation
        currentTab = "youtube"; screen = "youtube"
        shell()

        if (youtubeSubTab == "home") {
            renderYouTubeHome()
        } else {
            val pillRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(gutter, dp(10), gutter, dp(12))
            }
            pillRow.addView(categoryPill("All", active = false) { youtubeSubTab = "home"; loadYouTube() })
            pillRow.addView(categoryPill("Subscriptions", active = youtubeSubTab == "subscriptions") { youtubeSubTab = "subscriptions"; loadYouTube() })
            pillRow.addView(categoryPill("Queue", active = youtubeSubTab == "queue") { youtubeSubTab = "queue"; loadYouTube() })
            pillRow.addView(categoryPill("NAS Archive", active = youtubeSubTab == "archive") { youtubeSubTab = "archive"; loadYouTube() })
            pillRow.addView(categoryPill("Search", active = youtubeSubTab == "search") { youtubeSubTab = "search"; loadYouTube() })
            pillRow.addView(categoryPill("Account", active = false) { openYouTubeAuthDialog() })

            content.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(pillRow)
            })

            when (youtubeSubTab) {
                "search" -> renderYouTubeSearch()
                "subscriptions" -> renderYouTubeSubscriptions()
                "queue" -> renderYouTubeQueue()
                "archive" -> renderYouTubeArchive()
            }
        }
    }

    private fun youtubeItemToMedia(obj: JSONObject): Media {
        val id = obj.optString("id").ifBlank { obj.optString("videoId") }
        val vid = id.removePrefix("yt-")
        val title = obj.optString("title")
        val channelTitle = obj.optString("channelTitle").ifBlank { obj.optString("channelName") }
        val durationFormatted = obj.optString("durationFormatted")
        val durationSeconds = obj.optInt("durationSeconds")
        val durText = if (durationFormatted.isNotBlank()) durationFormatted else if (durationSeconds > 0) {
            val h = durationSeconds / 3600
            val m = (durationSeconds % 3600) / 60
            val s = durationSeconds % 60
            if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        } else ""
        val posSec = obj.optLong("positionSeconds")
        val thumb = obj.optString("thumbnailUrl")
        val proxyUrl = api.absolute("/api/mobile/v1/youtube/stream-video?videoId=$vid")
        val progressPercent = if (obj.has("progressPercent")) obj.optDouble("progressPercent")
            else if (durationSeconds > 0) (posSec.toDouble() / durationSeconds * 100) else 0.0

        val json = JSONObject().apply {
            put("id", "yt-$vid")
            put("videoId", vid)
            put("jellyfinId", "yt-$vid")
            put("playbackId", "yt-$vid")
            put("title", title)
            put("name", title)
            put("seriesName", channelTitle)
            put("channelTitle", channelTitle)
            put("metadata", if (channelTitle.isNotBlank() && durText.isNotBlank()) "$channelTitle • $durText" else (channelTitle.ifBlank { durText }))
            put("overview", obj.optString("description"))
            put("source", "youtube")
            put("mediaType", "Episode")
            put("isDownloading", false)
            put("downloadUrl", proxyUrl)
            put("downloadStreamUrl", proxyUrl)
            put("videoStreamUrl", proxyUrl)
            put("posterUrl", thumb)
            put("backdropUrl", thumb)
            put("thumbnailUrl", thumb)
            put("poster", thumb)
            put("backdrop", thumb)
            put("playbackPositionTicks", posSec * 10_000_000L)
            put("progressPercent", progressPercent)
            put("isAvailableForPlayback", true)
        }
        return Media(json)
    }

    private fun renderYouTubeHome() {
        val loadBox = FrameLayout(this).apply {
            setPadding(0, dp(40), 0, dp(40))
            addView(ProgressBar(this@MainActivity).apply {
                indeterminateTintList = ColorStateList.valueOf(RED)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        content.addView(loadBox)

        lifecycleScope.launch {
            try {
                val recsDeferred = async { runCatching { api.call("youtube/recommendations") }.getOrNull() }
                val cwDeferred = async { runCatching { api.call("youtube/continue-watching") }.getOrNull() }

                val recs = recsDeferred.await()
                val cw = cwDeferred.await()

                if (screen != "youtube" || youtubeSubTab != "home") return@launch
                content.removeView(loadBox)

                // 1. Featured Hero Banner matching Home feed
                val heroObj = recs?.optJSONObject("hero")
                if (heroObj != null) {
                    val heroMedia = youtubeItemToMedia(heroObj)
                    hero(heroMedia, showActions = true)
                }

                // 2. Category Navigation Pills
                val pillRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(gutter, dp(6), gutter, dp(12))
                }
                pillRow.addView(categoryPill("All", active = true) { youtubeSubTab = "home"; loadYouTube() })
                pillRow.addView(categoryPill("Subscriptions", active = false) { youtubeSubTab = "subscriptions"; loadYouTube() })
                pillRow.addView(categoryPill("Queue", active = false) { youtubeSubTab = "queue"; loadYouTube() })
                pillRow.addView(categoryPill("NAS Archive", active = false) { youtubeSubTab = "archive"; loadYouTube() })
                pillRow.addView(categoryPill("Search", active = false) { youtubeSubTab = "search"; loadYouTube() })
                pillRow.addView(categoryPill("Account", active = false) { openYouTubeAuthDialog() })

                content.addView(HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(pillRow)
                })

                // 3. Continue Watching Row
                val cwItems = cw?.optJSONArray("items")
                if (cwItems != null && cwItems.length() > 0) {
                    val list = (0 until cwItems.length()).map { i ->
                        youtubeItemToMedia(cwItems.getJSONObject(i))
                    }
                    mediaRow("Continue Watching", list, isContinueWatching = true, parent = content)
                }

                // 4. Section Rows (Trending Podcasts & Shows, Documentaries, Subscriptions, etc.)
                val sections = recs?.optJSONArray("sections")
                if (sections != null && sections.length() > 0) {
                    for (i in 0 until sections.length()) {
                        val sec = sections.getJSONObject(i)
                        val secTitle = sec.optString("title")
                        val itemsArr = sec.optJSONArray("items") ?: continue
                        if (itemsArr.length() == 0) continue
                        val list = (0 until itemsArr.length()).map { j ->
                            youtubeItemToMedia(itemsArr.getJSONObject(j))
                        }
                        mediaRow(secTitle, list, isContinueWatching = false, parent = content)
                    }
                }
            } catch (e: Exception) {
                if (screen == "youtube" && youtubeSubTab == "home") {
                    content.removeView(loadBox)
                    content.addView(TextView(this@MainActivity).apply {
                        text = "Failed loading YouTube feed: ${e.message}"
                        setTextColor(MUTED)
                        setPadding(gutter, dp(20), gutter, dp(20))
                    })
                }
            }
        }
    }

    private fun showYouTubeItemDialog(item: Media) {
        val options = arrayOf("▶  Play Now", "⬇  Download to NAS", "🔔  Subscribe to Channel")
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(item)
                    1 -> lifecycleScope.launch {
                        try {
                            api.call("youtube/download", body = JSONObject().apply {
                                put("videoId", item.json.optString("videoId"))
                                put("title", item.title)
                                put("channelTitle", item.json.optString("channelTitle"))
                                put("thumbnailUrl", item.poster)
                            })
                            toast("Queued download for: ${item.title}")
                        } catch (e: Exception) {
                            toast("Failed: ${e.message}")
                        }
                    }
                    2 -> lifecycleScope.launch {
                        try {
                            val ch = item.json.optString("channelTitle")
                            api.call("youtube/subscriptions", body = JSONObject().apply {
                                put("channelUrlOrHandle", ch)
                                put("autoDownload", true)
                                put("minDurationSeconds", 30 * 60)
                                put("ignoreShorts", true)
                            })
                            toast("Subscribed to $ch")
                        } catch (e: Exception) {
                            toast("Failed: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openYouTubeAuthDialog() {
        lifecycleScope.launch {
            val auth = runCatching { api.call("youtube/auth") }.getOrNull()
            val isAuth = auth?.optBoolean("authenticated") == true
            val profileName = auth?.optString("profileName").orEmpty().ifBlank { settings.profile }

            val builder = AlertDialog.Builder(this@MainActivity)
            builder.setTitle("YouTube Account (${if (isAuth) "Connected" else "Not Connected"})")

            val layout = column().apply {
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            layout.addView(TextView(this@MainActivity).apply {
                text = if (isAuth) {
                    "Your YouTube account is connected for profile: $profileName.\nSuggestions and subscriptions are personalized."
                } else {
                    "Connect YouTube to import your subscriptions, continue watching, and recommendations for profile: $profileName."
                }
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(12))
            })

            val cookieInput = EditText(this@MainActivity).apply {
                hint = "Paste cookies.txt or session cookies here"
                textSize = 12f
                setTextColor(Color.WHITE)
                setHintTextColor(MUTED)
                background = rounded(0x22FFFFFF.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
            }
            layout.addView(cookieInput)
            layout.addView(TextView(this@MainActivity).apply {
                text = "Export cookies from your browser (e.g. 'Get cookies.txt LOCALLY' extension) and paste here."
                textSize = 10.5f
                setTextColor(MUTED)
                setPadding(0, dp(6), 0, 0)
            })

            builder.setView(layout)
            builder.setPositiveButton(if (isAuth) "Update Cookies" else "Save & Connect") { _, _ ->
                val txt = cookieInput.text.toString().trim()
                if (txt.isNotBlank()) {
                    lifecycleScope.launch {
                        try {
                            api.call("youtube/auth", body = JSONObject().apply { put("cookiesText", txt) })
                            toast("YouTube account connected!")
                            loadYouTube()
                        } catch (e: Exception) {
                            toast("Failed saving cookies: ${e.message}")
                        }
                    }
                }
            }
            if (isAuth) {
                builder.setNeutralButton("Disconnect") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            api.call("youtube/auth", method = "DELETE")
                            toast("YouTube account disconnected")
                            loadYouTube()
                        } catch (e: Exception) {
                            toast("Failed: ${e.message}")
                        }
                    }
                }
            }
            builder.setNegativeButton("Close", null)
            builder.show()
        }
    }

    private fun renderYouTubeSearch() {
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gutter, dp(2), gutter, dp(8))
        }
        val allBtn = categoryPill("All Videos", !youtubePodcastFilter) {
            youtubePodcastFilter = false
            renderYouTubeSearch()
        }
        val podBtn = categoryPill("🎙️ Podcasts (≥20m)", youtubePodcastFilter) {
            youtubePodcastFilter = true
            renderYouTubeSearch()
        }
        filterRow.addView(allBtn)
        filterRow.addView(podBtn)
        content.addView(filterRow)

        val input = EditText(this).apply {
            hint = "Search YouTube videos & podcasts..."
            setTextColor(Color.WHITE); setHintTextColor(MUTED)
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            setText(youtubeSearchQuery)
            background = rounded(0xFF1D1D1D.toInt(), radius = dp(12).toFloat())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.nav_search, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(MUTED)
            compoundDrawablePadding = dp(10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 14f
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(gutter, dp(2), gutter, dp(8)) })

        val resultsContainer = column().apply { setPadding(gutter, dp(4), gutter, dp(16)) }
        content.addView(resultsContainer)

        val performSearch = { query: String ->
            youtubeSearchJob?.cancel()
            if (query.isBlank()) {
                resultsContainer.removeAllViews()
                resultsContainer.addView(TextView(this).apply {
                    text = "Type above to search YouTube"
                    textSize = 12f; setTextColor(MUTED); setPadding(0, dp(16), 0, 0)
                })
            } else {
                resultsContainer.removeAllViews()
                resultsContainer.addView(ProgressBar(this).apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
                })
                youtubeSearchJob = lifecycleScope.launch {
                    try {
                        val res = api.call("youtube/search", mapOf("q" to query))
                        val results = res.optJSONArray("results")
                        resultsContainer.removeAllViews()
                        if (results == null || results.length() == 0) {
                            resultsContainer.addView(TextView(this@MainActivity).apply {
                                text = "No YouTube results found"
                                textSize = 12f; setTextColor(MUTED); setPadding(0, dp(16), 0, 0)
                            })
                            return@launch
                        }

                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val durationSec = item.optInt("durationSeconds", 0)
                            if (youtubePodcastFilter && durationSec < 20 * 60) continue

                            val card = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                background = rounded(0x18FFFFFF.toInt(), radius = dp(10).toFloat())
                                setPadding(dp(10), dp(10), dp(10), dp(10))
                                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                                isClickable = true
                                isFocusable = true
                            }

                            val thumbFrame = FrameLayout(this@MainActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(dp(120), dp(68)).apply { marginEnd = dp(10) }
                            }
                            val thumbView = ImageView(this@MainActivity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                background = rounded(0xFF111111.toInt(), radius = dp(8).toFloat())
                                clipToOutline = true
                                layoutParams = FrameLayout.LayoutParams(-1, -1)
                            }
                            val thumbUrl = item.optString("thumbnailUrl")
                            if (thumbUrl.isNotBlank()) thumbView.load(thumbUrl) { crossfade(true) }
                            thumbFrame.addView(thumbView)

                            val durBadge = TextView(this@MainActivity).apply {
                                text = item.optString("durationFormatted")
                                textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                                background = rounded(0xCC000000.toInt(), radius = dp(4).toFloat())
                                setPadding(dp(4), dp(1), dp(4), dp(1))
                                layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                                    gravity = Gravity.BOTTOM or Gravity.END
                                    setMargins(0, 0, dp(4), dp(4))
                                }
                            }
                            thumbFrame.addView(durBadge)
                            card.addView(thumbFrame)

                            val metaCol = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                            metaCol.addView(TextView(this@MainActivity).apply {
                                text = item.optString("title")
                                textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE); maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            })
                            metaCol.addView(TextView(this@MainActivity).apply {
                                text = "${item.optString("channelTitle")} • ${item.optString("uploadDate")}"
                                textSize = 11f; setTextColor(MUTED)
                                setPadding(0, dp(2), 0, 0)
                            })
                            card.addView(metaCol)

                            val actionsCol = column().apply {
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
                            }

                            val streamBtn = action("Stream") {
                                lifecycleScope.launch {
                                    try {
                                        toast("Resolving stream...")
                                        val sRes = api.call("youtube/stream", mapOf("videoId" to item.optString("id")))
                                        val streamUrl = sRes.optString("streamUrl")
                                        if (streamUrl.isNotBlank()) {
                                            val m = JSONObject().apply {
                                                put("id", "yt-" + item.optString("id"))
                                                put("title", item.optString("title"))
                                                put("overview", item.optString("description"))
                                                put("posterUrl", item.optString("thumbnailUrl"))
                                                put("backdropUrl", item.optString("thumbnailUrl"))
                                                put("isDownloading", true)
                                                val proxyUrl = api.absolute("/api/mobile/v1/youtube/stream-video?videoId=" + item.optString("id"))
                                                put("downloadStreamUrl", proxyUrl)
                                                put("source", "youtube")
                                            }
                                            play(Media(m))
                                        } else {
                                            toast("Could not resolve stream URL")
                                        }
                                    } catch (e: Exception) {
                                        toast("Playback failed: ${e.message}")
                                    }
                                }
                            }.apply {
                                textSize = 11f
                                setPadding(dp(10), dp(4), dp(10), dp(4))
                                background = rounded(RED, radius = dp(6).toFloat())
                                layoutParams = LinearLayout.LayoutParams(-2, dp(28)).apply { bottomMargin = dp(4) }
                            }
                            actionsCol.addView(streamBtn)

                            val dlBtn = action("Save") {
                                lifecycleScope.launch {
                                    try {
                                        api.call("youtube/download", body = JSONObject().apply {
                                            put("videoId", item.optString("id"))
                                            put("title", item.optString("title"))
                                            put("channelTitle", item.optString("channelTitle"))
                                            put("durationSeconds", item.optInt("durationSeconds"))
                                            put("thumbnailUrl", item.optString("thumbnailUrl"))
                                        })
                                        toast("Queued for download")
                                    } catch (e: Exception) {
                                        toast("Failed: ${e.message}")
                                    }
                                }
                            }.apply {
                                textSize = 11f
                                setPadding(dp(10), dp(4), dp(10), dp(4))
                                background = rounded(0x22FFFFFF.toInt(), radius = dp(6).toFloat())
                                layoutParams = LinearLayout.LayoutParams(-2, dp(28))
                            }
                            actionsCol.addView(dlBtn)

                            card.addView(actionsCol)
                            resultsContainer.addView(card)
                        }
                    } catch (e: Exception) {
                        resultsContainer.removeAllViews()
                        resultsContainer.addView(TextView(this@MainActivity).apply {
                            text = "Search error: ${e.message}"
                            textSize = 12f; setTextColor(Color.RED)
                        })
                    }
                }
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                youtubeSearchQuery = s.toString().trim()
                youtubeSearchJob?.cancel()
                youtubeSearchJob = lifecycleScope.launch {
                    delay(500)
                    performSearch(youtubeSearchQuery)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        if (youtubeSearchQuery.isNotBlank()) performSearch(youtubeSearchQuery)
    }

    private fun renderYouTubeSubscriptions() {
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gutter, dp(2), gutter, dp(8))
        }
        val addBtn = action("+ Add Channel") {
            showAddSubscriptionDialog()
        }.apply {
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(RED, radius = dp(8).toFloat())
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        }
        val checkBtn = action("Check Uploads") {
            lifecycleScope.launch {
                try {
                    toast("Checking new uploads...")
                    api.call("youtube/subscriptions/check", body = JSONObject())
                    toast("Subscriptions checked")
                    renderYouTubeSubscriptions()
                } catch (e: Exception) {
                    toast("Failed: ${e.message}")
                }
            }
        }.apply {
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0x22FFFFFF.toInt(), radius = dp(8).toFloat())
        }
        actionsRow.addView(addBtn)
        actionsRow.addView(checkBtn)
        content.addView(actionsRow)

        val listContainer = column().apply { setPadding(gutter, dp(4), gutter, dp(16)) }
        content.addView(listContainer)

        lifecycleScope.launch {
            try {
                val res = api.call("youtube/subscriptions")
                val subs = res.optJSONArray("subscriptions")
                listContainer.removeAllViews()
                if (subs == null || subs.length() == 0) {
                    listContainer.addView(TextView(this@MainActivity).apply {
                        text = "No channel subscriptions yet.\nAdd a podcast channel to automatically download full episodes to your NAS."
                        textSize = 12f; setTextColor(MUTED); setPadding(0, dp(16), 0, 0)
                    })
                    return@launch
                }

                for (i in 0 until subs.length()) {
                    val sub = subs.getJSONObject(i)
                    val id = sub.optString("id")
                    val title = sub.optString("title")
                    val handle = sub.optString("handle")
                    val avatarUrl = sub.optString("avatarUrl")
                    val minSec = sub.optInt("minDurationSeconds", 1800)

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(0x18FFFFFF.toInt(), radius = dp(10).toFloat())
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                    }

                    val avatar = ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
                        background = rounded(0xFF222222.toInt(), radius = dp(22).toFloat())
                        clipToOutline = true
                    }
                    if (avatarUrl.isNotBlank()) avatar.load(avatarUrl)
                    card.addView(avatar)

                    val textCol = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                    textCol.addView(TextView(this@MainActivity).apply {
                        text = title; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    })
                    textCol.addView(TextView(this@MainActivity).apply {
                        text = "$handle • Min ${minSec / 60}m • Auto-Download"
                        textSize = 11f; setTextColor(MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                    card.addView(textCol)

                    val checkChanBtn = action("Check") {
                        lifecycleScope.launch {
                            try {
                                toast("Checking $title...")
                                val r = api.call("youtube/subscriptions/check", body = JSONObject().apply { put("channelId", id) })
                                val count = r.optInt("downloadedCount", 0)
                                toast("$title: $count new episode(s) queued")
                            } catch (e: Exception) {
                                toast("Error: ${e.message}")
                            }
                        }
                    }.apply {
                        textSize = 11f; setPadding(dp(8), dp(4), dp(8), dp(4))
                        background = rounded(0x22FFFFFF.toInt(), radius = dp(6).toFloat())
                        layoutParams = LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(4) }
                    }
                    card.addView(checkChanBtn)

                    val delBtn = action("✕") {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Unsubscribe")
                            .setMessage("Unsubscribe from $title?")
                            .setPositiveButton("Unsubscribe") { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        api.call("youtube/subscriptions", params = mapOf("id" to id), method = "DELETE")
                                        toast("Unsubscribed from $title")
                                        renderYouTubeSubscriptions()
                                    } catch (e: Exception) {
                                        toast("Failed: ${e.message}")
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }.apply {
                        textSize = 11f; setPadding(dp(8), dp(4), dp(8), dp(4))
                        background = rounded(0x22FFFFFF.toInt(), radius = dp(6).toFloat())
                        layoutParams = LinearLayout.LayoutParams(-2, dp(28))
                    }
                    card.addView(delBtn)

                    listContainer.addView(card)
                }
            } catch (e: Exception) {
                listContainer.removeAllViews()
                listContainer.addView(TextView(this@MainActivity).apply {
                    text = "Failed loading subscriptions: ${e.message}"
                    textSize = 12f; setTextColor(Color.RED)
                })
            }
        }
    }

    private fun showAddSubscriptionDialog() {
        val input = EditText(this).apply {
            hint = "e.g. @joerogan, @lexfridman"
            setTextColor(Color.WHITE); setHintTextColor(MUTED)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Add YouTube Channel")
            .setMessage("Enter the channel handle (@name) or URL to automatically download podcast episodes (≥30m):")
            .setView(input)
            .setPositiveButton("Subscribe") { _, _ ->
                val handle = input.text.toString().trim()
                if (handle.isNotBlank()) {
                    lifecycleScope.launch {
                        try {
                            val res = api.call("youtube/subscriptions", body = JSONObject().apply {
                                put("channelUrlOrHandle", handle)
                                put("autoDownload", true)
                                put("minDurationSeconds", 1800)
                                put("ignoreShorts", true)
                            })
                            if (res.optBoolean("success")) {
                                toast("Subscribed to channel!")
                                renderYouTubeSubscriptions()
                            } else {
                                toast("Could not subscribe: ${res.optString("error")}")
                            }
                        } catch (e: Exception) {
                            toast("Failed: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderYouTubeQueue() {
        val refreshBtn = action("Refresh Queue") {
            renderYouTubeQueue()
        }.apply {
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0x22FFFFFF.toInt(), radius = dp(8).toFloat())
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(gutter, dp(2), gutter, dp(8)) }
        }
        content.addView(refreshBtn)

        val queueContainer = column().apply { setPadding(gutter, dp(4), gutter, dp(16)) }
        content.addView(queueContainer)

        lifecycleScope.launch {
            try {
                val res = api.call("youtube/queue")
                val queue = res.optJSONArray("queue")
                queueContainer.removeAllViews()
                if (queue == null || queue.length() == 0) {
                    queueContainer.addView(TextView(this@MainActivity).apply {
                        text = "Download queue is empty.\nSearch videos or subscribe to channels to queue downloads."
                        textSize = 12f; setTextColor(MUTED); setPadding(0, dp(16), 0, 0)
                    })
                    return@launch
                }

                for (i in 0 until queue.length()) {
                    val item = queue.getJSONObject(i)
                    val vid = item.optString("videoId")
                    val title = item.optString("title")
                    val chan = item.optString("channelTitle")
                    val status = item.optString("status")
                    val pct = item.optDouble("progressPercent", 0.0).toFloat()
                    val eta = item.optInt("etaSeconds", 0)

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = rounded(0x18FFFFFF.toInt(), radius = dp(10).toFloat())
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                    }

                    val rowTop = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val statusBadge = TextView(this@MainActivity).apply {
                        text = status.uppercase()
                        textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(if (status == "downloading") 0xFF64B5F6.toInt() else if (status == "completed") 0xFF81C784.toInt() else MUTED)
                        background = rounded(0x33FFFFFF.toInt(), radius = dp(4).toFloat())
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                    }
                    rowTop.addView(statusBadge)
                    rowTop.addView(TextView(this@MainActivity).apply {
                        text = title; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })

                    val cancelBtn = action("✕") {
                        lifecycleScope.launch {
                            try {
                                api.call("youtube/queue", params = mapOf("videoId" to vid), method = "DELETE")
                                toast("Removed from queue")
                                renderYouTubeQueue()
                            } catch (e: Exception) {
                                toast("Failed: ${e.message}")
                            }
                        }
                    }.apply {
                        textSize = 11f; setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = rounded(Color.TRANSPARENT)
                    }
                    rowTop.addView(cancelBtn)
                    card.addView(rowTop)

                    card.addView(TextView(this@MainActivity).apply {
                        text = "$chan • ${pct.toInt()}%" + (if (eta > 0) " • ETA: ${eta / 60}m ${eta % 60}s" else "")
                        textSize = 11f; setTextColor(MUTED)
                        setPadding(0, dp(4), 0, 0)
                    })

                    if (status == "downloading" || status == "queued") {
                        val pb = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                            max = 100
                            progress = pct.toInt()
                            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) }
                        }
                        card.addView(pb)
                    }

                    queueContainer.addView(card)
                }
            } catch (e: Exception) {
                queueContainer.removeAllViews()
                queueContainer.addView(TextView(this@MainActivity).apply {
                    text = "Failed loading queue: ${e.message}"
                    textSize = 12f; setTextColor(Color.RED)
                })
            }
        }
    }

    private fun renderYouTubeArchive() {
        val archiveContainer = column().apply { setPadding(gutter, dp(4), gutter, dp(16)) }
        content.addView(archiveContainer)

        lifecycleScope.launch {
            try {
                val res = api.call("youtube/archive")
                val items = res.optJSONArray("items")
                archiveContainer.removeAllViews()
                if (items == null || items.length() == 0) {
                    archiveContainer.addView(TextView(this@MainActivity).apply {
                        text = "No downloaded YouTube videos found in /youtube mount."
                        textSize = 12f; setTextColor(MUTED); setPadding(0, dp(16), 0, 0)
                    })
                    return@launch
                }

                archiveContainer.addView(TextView(this@MainActivity).apply {
                    text = "Downloaded Archive (${items.length()} episodes)"
                    textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(MUTED); setPadding(0, 0, 0, dp(8))
                })

                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val id = it.optString("id")
                    val title = it.optString("title")
                    val chan = it.optString("channelName")
                    val streamUrl = it.optString("streamUrl")
                    val thumbUrl = it.optString("thumbnailUrl")
                    val sizeMb = it.optLong("fileSize") / (1024 * 1024)

                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(0x18FFFFFF.toInt(), radius = dp(10).toFloat())
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            val mediaJson = JSONObject().apply {
                                put("id", "yt-archive-$id")
                                put("title", title)
                                put("overview", "Channel: $chan • $sizeMb MB")
                                put("posterUrl", api.absolute(thumbUrl))
                                put("backdropUrl", api.absolute(thumbUrl))
                                put("isDownloading", true)
                                put("downloadStreamUrl", api.absolute(streamUrl))
                                put("source", "youtube")
                            }
                            play(Media(mediaJson))
                        }
                    }

                    val thumbView = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(0xFF111111.toInt(), radius = dp(8).toFloat())
                        clipToOutline = true
                        layoutParams = LinearLayout.LayoutParams(dp(100), dp(56)).apply { marginEnd = dp(10) }
                    }
                    if (thumbUrl.isNotBlank()) thumbView.load(api.absolute(thumbUrl)) { crossfade(true) }
                    card.addView(thumbView)

                    val metaCol = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                    metaCol.addView(TextView(this@MainActivity).apply {
                        text = title; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE); maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    metaCol.addView(TextView(this@MainActivity).apply {
                        text = "$chan • ${sizeMb} MB • Ready to play"
                        textSize = 11f; setTextColor(0xFF81C784.toInt())
                        setPadding(0, dp(2), 0, 0)
                    })
                    card.addView(metaCol)

                    val playBtn = TextView(this@MainActivity).apply {
                        text = "▶"
                        textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        background = rounded(RED, radius = dp(18).toFloat())
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    }
                    card.addView(playBtn)

                    archiveContainer.addView(card)
                }
            } catch (e: Exception) {
                archiveContainer.removeAllViews()
                archiveContainer.addView(TextView(this@MainActivity).apply {
                    text = "Failed loading archive: ${e.message}"
                    textSize = 12f; setTextColor(Color.RED)
                })
            }
        }
    }
}
