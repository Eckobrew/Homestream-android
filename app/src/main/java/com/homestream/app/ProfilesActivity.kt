package com.homestream.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

class ProfilesActivity : AppCompatActivity() {
    private lateinit var settings: ServerSettings
    private lateinit var api: HomeStreamApi
    private lateinit var page: LinearLayout
    private val avatars = listOf("fox", "owl", "panda", "cat", "bear", "penguin", "robot", "astronaut")
    private val gutter get() = dp(if (isTelevision()) 48 else 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this)
        if (!settings.canUseProfiles) { finish(); return }
        api = HomeStreamApi(settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = column().apply { setBackgroundColor(INK) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(gutter, dp(12), gutter, dp(8)) }
        header.addView(action("Back") { finish() })
        header.addView(label("Profiles", if (isTelevision()) 30f else 26f, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        page = column().apply { setPadding(gutter, dp(12), gutter, dp(28)) }
        root.addView(ScrollView(this).apply { addView(page) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        refresh()
    }

    private fun avatarImage(id: String, size: Int): ImageView = ImageView(this).apply {
        val name = id.takeIf { it in avatars } ?: avatars.first()
        val resource = resources.getIdentifier("profile_$name", "drawable", packageName)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "$name profile picture"
        background = rounded(0xFF292929.toInt(), radius = dp(size / 2).toFloat())
        clipToOutline = true
        load(if (resource == 0) R.drawable.nav_profile else resource) { size(256) }
    }

    private fun refresh() {
        page.removeAllViews(); page.addView(label("Loading profiles…"))
        lifecycleScope.launch {
            try {
                val cached = api.cached("profiles")
                val response = cached ?: api.call("profiles")
                val list = response.optJSONArray("profiles")
                val canManage = response.optBoolean("canManage")
                page.removeAllViews()
                page.addView(label("Choose a profile to continue", 16f).apply { setTextColor(MUTED) })
                val grid = GridLayout(this@ProfilesActivity).apply { columnCount = if (isTelevision()) 4 else 2 }
                val current = settings.profile.ifBlank { list?.optJSONObject(0)?.optString("id").orEmpty() }
                for (index in 0 until (list?.length() ?: 0)) {
                    val profile = list!!.getJSONObject(index)
                    val cell = column()
                    val card = column().apply {
                        gravity = Gravity.CENTER; setPadding(dp(12), dp(16), dp(12), dp(16)); focusableCard()
                        contentDescription = "${profile.optString("name")}${if (profile.optString("id") == current) ", current profile" else ""}"
                        setOnClickListener {
                            settings.profile = profile.getString("id")
                            setResult(Activity.RESULT_OK); finish()
                        }
                    }
                    val stock = profile.optString("avatarId").ifBlank { avatars[index % avatars.size] }
                    val size = if (isTelevision()) 112 else 88
                    card.addView(avatarImage(stock, size), LinearLayout.LayoutParams(dp(size), dp(size)))
                    card.addView(label((if (profile.optString("id") == current) "✓ " else "") + profile.optString("name"), 17f, true).apply {
                        gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(10), 0, 0)
                    })
                    cell.addView(card, LinearLayout.LayoutParams(-1, -2))
                    cell.addView(action("Edit profile") { edit(profile, canManage) }, LinearLayout.LayoutParams(-1, dp(48)))
                    grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(index / grid.columnCount), GridLayout.spec(index % grid.columnCount, GridLayout.FILL, 1f)).apply {
                        width = 0; height = -2; setMargins(dp(6), dp(12), dp(6), dp(12))
                    })
                }
                page.addView(grid, LinearLayout.LayoutParams(-1, -2))
                if (canManage) page.addView(action("+ Add profile", primary = true) { edit(null, true) })
                else page.addView(label("Sign in with a paired household device to add or link profiles.", 13f).apply { setTextColor(MUTED) })
                if ((list?.length() ?: 0) == 0) page.addView(label("No profiles yet. Add your first profile to get started.", 15f))
                if (canManage) (application as HomeStreamApplication).playbackReports.launch {
                    runCatching { api.prefetch("profiles/users") }
                }
                if (cached != null) lifecycleScope.launch {
                    runCatching { api.call("profiles") }.onSuccess { fresh ->
                        if (fresh.toString() != cached.toString()) refresh()
                    }
                }
                if (isTelevision()) page.post {
                    val targets = ArrayList<View>(); page.addFocusables(targets, View.FOCUS_FORWARD)
                    targets.firstOrNull()?.requestFocus()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                page.removeAllViews(); page.addView(label(e.message ?: "Could not load profiles"))
                page.addView(action("Retry") { refresh() })
            }
        }
    }

    private fun edit(profile: JSONObject?, canManage: Boolean) {
        lifecycleScope.launch {
            try {
                val users = if (canManage) (api.cached("profiles/users") ?: api.call("profiles/users")).optJSONArray("users") else null
                showEditor(profile, canManage, (0 until (users?.length() ?: 0)).map { users!!.getJSONObject(it) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(this@ProfilesActivity, e.message ?: "Could not load Jellyfin accounts", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEditor(profile: JSONObject?, canManage: Boolean, users: List<JSONObject>) {
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), dp(16)) }
        val name = EditText(this).apply { hint = "Profile name"; isSingleLine = true; setText(profile?.optString("name").orEmpty()); isEnabled = canManage }
        form.addView(label("Profile name", 14f)); form.addView(name)
        form.addView(label("Profile picture", 14f).apply { setPadding(0, dp(14), 0, dp(8)) })
        var chosen = profile?.optString("avatarId")?.takeIf { it in avatars } ?: avatars.first()
        val avatarGrid = GridLayout(this).apply { columnCount = 4 }
        val choices = mutableListOf<FrameLayout>()
        fun paintChoices() { choices.forEachIndexed { index, view ->
            val normal = if (avatars[index] == chosen) RED else 0xFF292929.toInt()
            view.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(normal, Color.WHITE))
                addState(intArrayOf(), rounded(normal))
            }
            view.contentDescription = "${avatars[index]}${if (avatars[index] == chosen) ", selected" else ""}"
        } }
        avatars.forEachIndexed { index, avatar ->
            val choice = FrameLayout(this).apply {
                isFocusable = true; isClickable = true
                addView(avatarImage(avatar, 48), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                setOnClickListener { chosen = avatar; paintChoices() }
            }
            choices.add(choice)
            avatarGrid.addView(choice, GridLayout.LayoutParams(GridLayout.spec(index / 4), GridLayout.spec(index % 4, GridLayout.FILL, 1f)).apply {
                width = 0; height = dp(64); setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        paintChoices(); form.addView(avatarGrid, LinearLayout.LayoutParams(-1, -2))
        val mode = RadioGroup(this)
        val existing = RadioButton(this).apply { id = View.generateViewId(); text = "Link existing Jellyfin account" }
        val create = RadioButton(this).apply { id = View.generateViewId(); text = "Create new Jellyfin account" }
        val account = Spinner(this).apply {
            adapter = ArrayAdapter(this@ProfilesActivity, android.R.layout.simple_spinner_dropdown_item,
                users.map { it.optString("name") }.ifEmpty { listOf("No Jellyfin accounts available") })
            val linked = profile?.optString("jellyfinUserId").orEmpty().ifBlank { profile?.optString("id").orEmpty() }
            setSelection(users.indexOfFirst { it.optString("id") == linked }.coerceAtLeast(0))
        }
        val username = EditText(this).apply { hint = "Jellyfin username (defaults to profile name)"; isSingleLine = true }
        val passwordNote = label("New accounts use no password when supported. If Jellyfin requires one, it will match the Jellyfin username.", 12f).apply { setTextColor(MUTED) }
        if (canManage) {
            form.addView(label("Jellyfin account", 14f).apply { setPadding(0, dp(14), 0, dp(6)) })
            if (profile == null) { mode.addView(existing); mode.addView(create); form.addView(mode) }
            form.addView(account)
            if (profile == null) { form.addView(username); form.addView(passwordNote) }
            mode.setOnCheckedChangeListener { _, checked ->
                account.visibility = if (checked == existing.id) View.VISIBLE else View.GONE
                username.visibility = if (checked == create.id) View.VISIBLE else View.GONE
                passwordNote.visibility = username.visibility
            }
            mode.check(if (users.isNotEmpty()) existing.id else create.id)
        }
        val error = label("", 13f).apply { setTextColor(0xFFFF8A80.toInt()) }
        form.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(if (profile == null) "Add profile" else "Edit profile")
            .setView(ScrollView(this).apply { addView(form) }).setPositiveButton(if (profile == null) "Create profile" else "Save", null)
            .setNegativeButton("Cancel", null).create()
        var saveJob: kotlinx.coroutines.Job? = null
        dialog.setOnDismissListener { saveJob?.cancel() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val displayName = name.text.toString().trim()
                if (displayName.isBlank() || displayName.length > 64) { name.error = "Enter a name of 1–64 characters"; return@setOnClickListener }
                val isNewAccount = profile == null && mode.checkedRadioButtonId == create.id
                val selectedUser = users.getOrNull(account.selectedItemPosition)
                if (canManage && !isNewAccount && selectedUser == null) { error.text = "Choose an existing account or create a new one"; return@setOnClickListener }
                val body = JSONObject().put("action", if (profile == null) "create" else "update")
                    .put("name", displayName).put("avatarId", chosen)
                profile?.optString("id")?.let { body.put("profileId", it) }
                if (canManage) {
                    body.put("mode", if (isNewAccount) "new" else "existing")
                    if (isNewAccount) body.put("jellyfinName", username.text.toString().trim().ifBlank { displayName })
                    else body.put("jellyfinUserId", selectedUser!!.getString("id"))
                }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false; error.text = ""
                saveJob = lifecycleScope.launch {
                    try {
                        val result = api.call("profiles", body = body)
                        result.optJSONObject("profile")?.optString("id")?.takeIf { it.isNotBlank() }?.let {
                            if (profile == null || profile.optString("id") == settings.profile) settings.profile = it
                        }
                        setResult(Activity.RESULT_OK)
                        val message = when (result.optString("passwordMode")) {
                            "none" -> "Profile added. Jellyfin account created without a password."
                            "username" -> "Profile added. The Jellyfin password matches its username."
                            else -> "Profile saved"
                        }
                        Toast.makeText(this@ProfilesActivity, message, Toast.LENGTH_LONG).show()
                        // Clear the reference before dismissing so a successful save is not canceled mid-refresh.
                        saveJob = null; dialog.dismiss(); refresh()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        error.text = e.message ?: "Could not save profile"
                    } finally { button.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
}
