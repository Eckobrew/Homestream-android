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
    private var parentPinConfigured = false
    private var activeProfile: JSONObject? = null
    private val ratingBands = listOf("U", "PG", "12", "15", "18")
    private val gutter get() = dp(if (isTelevision()) 48 else 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ServerSettings(this)
        if (!settings.canUseProfiles) { finish(); return }
        api = HomeStreamApi(settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = column().apply { setBackgroundColor(INK) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(gutter, dp(12), gutter, dp(8)) }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Back"; background = rounded(0xFF202020.toInt(), radius = dp(24).toFloat())
            setOnClickListener { finish() }; isFocusable = true
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
        header.addView(label("HomeStream", if (isTelevision()) 24f else 20f, true).apply { setTextColor(RED) }, LinearLayout.LayoutParams(0, -2, 1f))
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
        background = rounded(0xFF292929.toInt(), radius = dp(12).toFloat())
        clipToOutline = true
        load(if (resource == 0) R.drawable.nav_profile else resource) { size(256) }
    }

    private fun refresh() {
        page.removeAllViews(); page.addView(label("Loading profiles…"))
        lifecycleScope.launch {
            try {
                val response = api.call("profiles")
                parentPinConfigured = response.optBoolean("parentPinConfigured")
                val list = response.optJSONArray("profiles")
                val canManage = response.optBoolean("canManage")
                page.removeAllViews()
                page.addView(label("Who’s watching?", if (isTelevision()) 38f else 30f, true).apply { setPadding(dp(6), dp(16), 0, dp(8)) })
                page.addView(label("Your own favourites, progress and recommendations.", 14f).apply { setTextColor(MUTED); setPadding(dp(6), 0, 0, dp(20)) })
                val grid = GridLayout(this@ProfilesActivity).apply { columnCount = if (isTelevision()) 4 else 2 }
                val current = response.optString("activeProfileId").ifBlank { settings.profile }.ifBlank { list?.optJSONObject(0)?.optString("id").orEmpty() }
                for (index in 0 until (list?.length() ?: 0)) {
                    val profile = list!!.getJSONObject(index)
                    if (profile.optString("id") == current) activeProfile = profile
                    val cell = column()
                    val card = column().apply {
                        gravity = Gravity.CENTER; setPadding(dp(12), dp(16), dp(12), dp(16)); focusableCard(0xFF141414.toInt())
                        contentDescription = "${profile.optString("name")}${if (profile.optString("id") == current) ", current profile" else ""}"
                        setOnClickListener {
                            selectProfile(profile)
                        }
                    }
                    val stock = profile.optString("avatarId").ifBlank { avatars[index % avatars.size] }
                    val size = if (isTelevision()) 112 else 88
                    card.addView(avatarImage(stock, size), LinearLayout.LayoutParams(dp(size), dp(size)))
                    card.addView(label((if (profile.optString("id") == current) "" else "") + profile.optString("name"), 17f, true).apply {
                        gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(10), 0, 0)
                    })
                    if (profile.optBoolean("isKids")) card.addView(label("Kids · ${profile.optString("kidsRating")}", 13f).apply { gravity = Gravity.CENTER; setTextColor(MUTED) })
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

    private fun askPin(title: String, action: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "4–8 digit parent PIN"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Continue") { _, _ -> action(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun selectProfile(profile: JSONObject) {
        val currentBand = activeProfile?.optString("kidsRating").orEmpty()
        val needsPin = activeProfile?.optBoolean("isKids") == true &&
            (!profile.optBoolean("isKids") || ratingBands.indexOf(profile.optString("kidsRating")) > ratingBands.indexOf(currentBand))
        fun select(pin: String) { lifecycleScope.launch {
            try {
                val result = api.call("profiles/select", body = JSONObject().put("profileId", profile.getString("id")).put("parentPin", pin))
                settings.applyProfilePolicy(result.getJSONObject("profile"), result.getString("revision"))
                // A cast session must not carry adult playback into a restricted profile.
                clearCastIdle(this@ProfilesActivity)
                setResult(Activity.RESULT_OK); finish()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(this@ProfilesActivity, e.message ?: "Could not select profile", Toast.LENGTH_LONG).show()
            }
        } }
        if (needsPin) askPin("Parent PIN", ::select) else select("")
    }

    private fun edit(profile: JSONObject?, canManage: Boolean) {
        fun load(pin: String) { lifecycleScope.launch {
            try {
                val users = if (canManage) api.call("profiles/users", body = JSONObject().put("parentPin", pin)).optJSONArray("users") else null
                showEditor(profile, canManage, (0 until (users?.length() ?: 0)).map { users!!.getJSONObject(it) }, pin)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(this@ProfilesActivity, e.message ?: "Could not load Jellyfin accounts", Toast.LENGTH_LONG).show()
            }
        } }
        if (parentPinConfigured) askPin("Parent PIN to manage profiles", ::load) else load("")
    }

    private fun showEditor(profile: JSONObject?, canManage: Boolean, users: List<JSONObject>, parentPin: String) {
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
        val kids = CheckBox(this).apply { text = "Kids profile"; isChecked = profile?.optBoolean("isKids") == true }
        var birthDate = profile?.optString("kidsDateOfBirth").orEmpty()
        val automaticRating = label("", 14f)
        fun ratingForDate(date: String): String {
            val birth = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return "U"
            val age = java.time.Period.between(birth, java.time.LocalDate.now(java.time.ZoneOffset.UTC)).years
            return when { age < 4 -> "U"; age < 12 -> "PG"; age < 15 -> "12"; age < 18 -> "15"; else -> "18" }
        }
        val dateButton = action("Child’s date of birth") {}
        fun showBirthDate() {
            dateButton.text = if (birthDate.isBlank()) "Choose child’s date of birth" else "Date of birth: $birthDate"
            automaticRating.text = if (birthDate.isBlank()) "U through age 3 · PG from 4 · 12 from 12 · 15 from 15 · 18 from 18" else "Current limit: ${ratingForDate(birthDate)} · adjusts automatically on birthdays"
        }
        dateButton.setOnClickListener {
            val initial = runCatching { java.time.LocalDate.parse(birthDate) }.getOrNull() ?: java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusYears(5)
            android.app.DatePickerDialog(this, { _, year, month, day ->
                birthDate = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day); showBirthDate()
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
                datePicker.minDate = java.util.Calendar.getInstance().apply { set(1900, 0, 1) }.timeInMillis
            }.show()
        }
        showBirthDate()
        val pin = EditText(this).apply { hint = "Create parent PIN (4–8 digits)"; isSingleLine = true; inputType = 18; filters = arrayOf(android.text.InputFilter.LengthFilter(8)) }
        val confirmPin = EditText(this).apply { hint = "Confirm parent PIN"; isSingleLine = true; inputType = 18; filters = arrayOf(android.text.InputFilter.LengthFilter(8)) }
        val restrictionNote = label("Only titles at or below this rating are shown. Unrated titles, trailers and downloading torrents are hidden. The rating limit adjusts from the date of birth. A parent PIN is required to leave Kids mode or change restrictions. Use a non-admin Jellyfin account.", 12f).apply { setTextColor(MUTED) }
        fun showKidsControls() {
            dateButton.visibility = if (kids.isChecked) View.VISIBLE else View.GONE
            automaticRating.visibility = dateButton.visibility
            restrictionNote.visibility = dateButton.visibility
            pin.visibility = if (kids.isChecked && !parentPinConfigured) View.VISIBLE else View.GONE
            confirmPin.visibility = pin.visibility
        }
        form.addView(kids); form.addView(dateButton); form.addView(automaticRating); form.addView(restrictionNote); form.addView(pin); form.addView(confirmPin)
        kids.setOnCheckedChangeListener { _, _ -> showKidsControls() }; showKidsControls()
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
                if (kids.isChecked && birthDate.isBlank()) { error.text = "Choose the child’s date of birth"; return@setOnClickListener }
                val effectivePin = if (parentPinConfigured) parentPin else pin.text.toString()
                if (kids.isChecked && !parentPinConfigured && (!effectivePin.matches(Regex("[0-9]{4,8}")) || effectivePin != confirmPin.text.toString())) {
                    error.text = "Enter matching parent PINs of 4–8 digits"; return@setOnClickListener
                }
                if (kids.isChecked && selectedUser?.optBoolean("isAdministrator") == true && !isNewAccount) {
                    error.text = "Choose a non-administrator Jellyfin account for Kids mode"; return@setOnClickListener
                }
                val body = JSONObject().put("isKids", kids.isChecked).put("kidsDateOfBirth", birthDate).put("parentPin", effectivePin).put("action", if (profile == null) "create" else "update")
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
                        if (api.refreshProfilePolicy()) clearCastIdle(this@ProfilesActivity)
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
