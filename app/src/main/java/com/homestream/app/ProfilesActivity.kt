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
    private var editMode = false
    private lateinit var editToggle: TextView
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
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(label("HOMESTREAM", if (isTelevision()) 24f else 20f, true).apply {
            setTextColor(RED); letterSpacing = 0.08f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        editToggle = TextView(this).apply {
            text = "Edit"
            textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            isClickable = true; isFocusable = true
            setOnClickListener {
                editMode = !editMode
                text = if (editMode) "Done" else "Edit"
                refresh()
            }
        }
        header.addView(editToggle)
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
                page.gravity = Gravity.CENTER_HORIZONTAL
                page.addView(label("Who’s watching?", if (isTelevision()) 36f else 22f, true).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(if (isTelevision()) 28 else 20), 0, dp(24))
                })
                val cols = if (isTelevision()) 4 else 2
                val grid = GridLayout(this@ProfilesActivity).apply {
                    columnCount = cols
                }
                val current = response.optString("activeProfileId").ifBlank { settings.profile }.ifBlank { list?.optJSONObject(0)?.optString("id").orEmpty() }
                val avatarSize = if (isTelevision()) 128 else 108
                for (index in 0 until (list?.length() ?: 0)) {
                    val profile = list!!.getJSONObject(index)
                    val isCurrent = profile.optString("id") == current
                    if (isCurrent) activeProfile = profile
                    val card = column().apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(10), dp(10), dp(10), dp(12))
                        focusableCard(Color.TRANSPARENT)
                        contentDescription = "${profile.optString("name")}${if (isCurrent) ", current profile" else ""}"
                        setOnClickListener {
                            if (editMode) edit(profile, canManage) else selectProfile(profile)
                        }
                    }
                    val stock = profile.optString("avatarId").ifBlank { avatars[index % avatars.size] }
                    val avatarBox = FrameLayout(this@ProfilesActivity).apply {
                        background = rounded(
                            0xFF202020.toInt(),
                            stroke = if (isCurrent) Color.WHITE else Color.TRANSPARENT,
                            radius = dp(10).toFloat(),
                            strokeWidth = dp(2)
                        )
                        clipToOutline = true
                    }
                    avatarBox.addView(avatarImage(stock, avatarSize), FrameLayout.LayoutParams(-1, -1))
                    if (editMode) {
                        avatarBox.addView(TextView(this@ProfilesActivity).apply {
                            text = "✎"
                            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                            setBackgroundColor(0x99000000.toInt())
                        }, FrameLayout.LayoutParams(-1, -1))
                    }
                    card.addView(avatarBox, LinearLayout.LayoutParams(dp(avatarSize), dp(avatarSize)))
                    card.addView(label(profile.optString("name"), 15f, isCurrent).apply {
                        gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(8), 0, 0)
                        setTextColor(if (isCurrent) Color.WHITE else MUTED)
                    })
                    if (profile.optBoolean("isKids")) {
                        card.addView(label("KIDS", 10.5f, true).apply {
                            gravity = Gravity.CENTER; setTextColor(0xFF38BDF8.toInt()); setPadding(0, dp(2), 0, 0)
                        })
                    }
                    grid.addView(card, GridLayout.LayoutParams(GridLayout.spec(index / cols), GridLayout.spec(index % cols, GridLayout.FILL, 1f)).apply {
                        width = 0; height = -2; setMargins(dp(6), dp(6), dp(6), dp(10))
                    })
                }
                if (canManage) {
                    val addIndex = list?.length() ?: 0
                    val addCard = column().apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(10), dp(10), dp(10), dp(12))
                        focusableCard(Color.TRANSPARENT)
                        setOnClickListener { edit(null, true) }
                    }
                    val plusBox = FrameLayout(this@ProfilesActivity).apply {
                        background = rounded(0xFF1A1A1A.toInt(), stroke = 0x33FFFFFF, radius = dp(10).toFloat(), strokeWidth = dp(1))
                    }
                    plusBox.addView(TextView(this@ProfilesActivity).apply {
                        text = "+"; textSize = 36f; setTextColor(MUTED); gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                    addCard.addView(plusBox, LinearLayout.LayoutParams(dp(avatarSize), dp(avatarSize)))
                    addCard.addView(label("Add Profile", 14f).apply {
                        gravity = Gravity.CENTER; setTextColor(MUTED); setPadding(0, dp(8), 0, 0)
                    })
                    grid.addView(addCard, GridLayout.LayoutParams(GridLayout.spec(addIndex / cols), GridLayout.spec(addIndex % cols, GridLayout.FILL, 1f)).apply {
                        width = 0; height = -2; setMargins(dp(6), dp(6), dp(6), dp(10))
                    })
                }
                val gridWrapWidth = if (isTelevision()) -1 else dp(320).coerceAtMost(resources.displayMetrics.widthPixels - dp(24))
                page.addView(grid, LinearLayout.LayoutParams(gridWrapWidth, -2))
                if (!canManage) page.addView(label("Sign in with a paired household device to add or link profiles.", 13f).apply {
                    setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
                })
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
        fun load(pin: String) {
            showEditor(profile, canManage, pin)
        }
        if (parentPinConfigured) askPin("Parent PIN to manage profiles", ::load) else load("")
    }

    private fun showEditor(profile: JSONObject?, canManage: Boolean, parentPin: String) {
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
        val restrictionDescription = "Only titles at or below this rating are shown. A parent PIN is required to leave Kids mode or change restrictions."
        val restrictionNote = label(restrictionDescription, 12f).apply { setTextColor(MUTED) }
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
                if (kids.isChecked && birthDate.isBlank()) { error.text = "Choose the child’s date of birth"; return@setOnClickListener }
                val effectivePin = if (parentPinConfigured) parentPin else pin.text.toString()
                if (kids.isChecked && !parentPinConfigured && (!effectivePin.matches(Regex("[0-9]{4,8}")) || effectivePin != confirmPin.text.toString())) {
                    error.text = "Enter matching parent PINs of 4–8 digits"; return@setOnClickListener
                }
                val body = JSONObject().put("isKids", kids.isChecked).put("kidsDateOfBirth", birthDate).put("parentPin", effectivePin).put("action", if (profile == null) "create" else "update")
                    .put("name", displayName).put("avatarId", chosen).put("mode", "native")
                profile?.optString("id")?.let { body.put("profileId", it) }
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false; error.text = ""
                saveJob = lifecycleScope.launch {
                    try {
                        api.call("profiles", body = body)
                        if (api.refreshProfilePolicy()) clearCastIdle(this@ProfilesActivity)
                        setResult(Activity.RESULT_OK)
                        Toast.makeText(this@ProfilesActivity, "Profile saved", Toast.LENGTH_LONG).show()
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
