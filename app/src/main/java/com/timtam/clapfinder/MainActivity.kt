package com.timtam.clapfinder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private val bg = Color.parseColor("#101418")
    private val card = Color.parseColor("#1A2027")
    private val accent = Color.parseColor("#FFB020")
    private val red = Color.parseColor("#E8453C")
    private val muted = Color.parseColor("#9AA4AE")

    private lateinit var prefs: Prefs
    private lateinit var bigBtn: Button
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var meter: ProgressBar
    private lateinit var foundBtn: Button
    private lateinit var testBtn: Button
    private var tester: AlarmPlayer? = null

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 80)
        }
    }
    private val stopTest = Runnable {
        tester?.stop()
        testBtn.text = "Test the sound"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
        setContentView(scroll)

        root.addView(text("Clap Finder", 30f, Color.WHITE, bold = true))
        root.addView(text("Lost your phone? Clap and it shouts back.", 15f, muted).also { pad(it, top = 2, bottom = 18) })

        bigBtn = Button(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            stateListAnimator = null
            setOnClickListener { toggle() }
        }
        val circle = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(bigBtn, LinearLayout.LayoutParams(dp(210), dp(210)))
        }
        root.addView(circle)

        status = text("", 16f, Color.WHITE).also {
            it.gravity = Gravity.CENTER
            pad(it, top = 16)
        }
        root.addView(status)
        heard = text("", 15f, accent, bold = true).also {
            it.gravity = Gravity.CENTER
            pad(it, top = 4)
        }
        root.addView(heard)

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A323B"))
        }
        root.addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).also { it.topMargin = dp(10) })
        root.addView(text("Sound level. Clap and watch it jump.", 12f, muted).also { it.gravity = Gravity.CENTER; pad(it, top = 4) })

        foundBtn = Button(this).apply {
            text = "I found it! Stop the alarm"
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(red, 14)
            visibility = View.GONE
            setOnClickListener { ClapService.send(this@MainActivity, ClapService.ACTION_FOUND) }
        }
        root.addView(foundBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).also { it.topMargin = dp(16) })

        // ---------- settings ----------
        val settings = cardBox(root, "Settings")

        val sensLabel = text("", 15f, Color.WHITE)
        settings.addView(sensLabel)
        val sens = SeekBar(this).apply {
            max = 100
            progress = prefs.sensitivity
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
        }
        fun showSens(v: Int) { sensLabel.text = "Sensitivity: $v" }
        showSens(prefs.sensitivity)
        sens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                prefs.sensitivity = v
                showSens(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        settings.addView(sens)
        settings.addView(text("Higher hears quieter claps from further away, but may go off by mistake.", 12f, muted).also { pad(it, bottom = 12) })

        settings.addView(text("Claps needed", 15f, Color.WHITE))
        val clapGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(2, 3).forEach { n ->
            clapGroup.addView(radio("$n claps", n == prefs.clapsNeeded) { prefs.clapsNeeded = n })
        }
        settings.addView(clapGroup)

        settings.addView(text("Alarm sound", 15f, Color.WHITE).also { pad(it, top = 12) })
        val soundGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("siren" to "Siren", "beep" to "Beeps", "chirp" to "Bird chirps", "retro" to "8-bit tune").forEach { (id, name) ->
            soundGroup.addView(radio(name, id == prefs.sound) { prefs.sound = id })
        }
        settings.addView(soundGroup)

        settings.addView(check("Shout \"I'm over here!\"", prefs.speak) { prefs.speak = it }.also { pad(it, top = 8) })
        settings.addView(check("Flash the torch", prefs.flash) { prefs.flash = it })
        settings.addView(check("Vibrate", prefs.vibrate) { prefs.vibrate = it })

        val ringLabel = text("", 15f, Color.WHITE).also { pad(it, top = 12) }
        settings.addView(ringLabel)
        val ring = SeekBar(this).apply {
            max = 10
            progress = (prefs.ringSeconds - 10) / 5
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
        }
        fun showRing(s: Int) { ringLabel.text = "Ring for $s seconds" }
        showRing(prefs.ringSeconds)
        ring.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                val secs = 10 + v * 5
                prefs.ringSeconds = secs
                showRing(secs)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        settings.addView(ring)

        testBtn = Button(this).apply {
            text = "Test the sound"
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(accent, 12)
            setOnClickListener { testSound() }
        }
        settings.addView(testBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).also { it.topMargin = dp(12) })

        // ---------- background tips ----------
        val tips = cardBox(root, "Keep it working with the screen off")
        tips.addView(text("Android can pause apps to save battery. Let Clap Finder run in the background so it still hears you when the screen is off. Listening uses a little battery, so turn it off when you don't need it.", 14f, muted))
        val battery = Button(this).apply {
            text = "Allow running in the background"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#2A323B"), 12)
            setOnClickListener { openBatterySettings() }
        }
        tips.addView(battery, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).also { it.topMargin = dp(12) })
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    override fun onDestroy() {
        h.removeCallbacks(stopTest)
        tester?.release()
        super.onDestroy()
    }

    // ---------- actions ----------

    private fun toggle() {
        if (ClapService.listening) {
            ClapService.send(this, ClapService.ACTION_STOP)
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        ClapService.error = null
        ClapService.send(this, ClapService.ACTION_START)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ClapService.error = null
            ClapService.send(this, ClapService.ACTION_START)
        } else {
            ClapService.error = "Clap Finder needs the microphone to hear your claps. Allow it in Settings > Apps > Clap Finder > Permissions."
        }
    }

    private fun testSound() {
        val t = tester ?: AlarmPlayer(this).also { tester = it }
        h.removeCallbacks(stopTest)
        if (t.isPlaying) {
            stopTest.run()
            return
        }
        t.play(prefs.sound, prefs.speak)
        testBtn.text = "Stop the test"
        h.postDelayed(stopTest, 6000)
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
    }

    private fun refresh() {
        val on = ClapService.listening
        val ringing = ClapService.alarming
        bigBtn.text = if (on) "Listening\ntap to stop" else "Start\nlistening"
        bigBtn.setTextColor(if (on) Color.BLACK else Color.WHITE)
        bigBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) accent else card)
            setStroke(dp(3), accent)
        }
        val err = ClapService.error
        status.text = when {
            err != null -> err
            ringing -> "Ringing! Tap below when you've found it."
            on -> "Clap ${prefs.clapsNeeded} times and your phone will ring. Works with the screen off."
            else -> "Tap Start, then put your phone down anywhere."
        }
        status.setTextColor(if (err != null) red else Color.WHITE)
        val since = System.currentTimeMillis() - ClapService.lastClapAt
        heard.text = if (on && since < 1200 && ClapService.clapsInRow > 0) {
            "Heard a clap (${ClapService.clapsInRow} of ${prefs.clapsNeeded})"
        } else ""
        meter.progress = if (on) (ClapService.level * 250).toInt().coerceIn(0, 100) else 0
        foundBtn.visibility = if (ringing) View.VISIBLE else View.GONE
    }

    // ---------- little UI helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pad(v: View, top: Int = 0, bottom: Int = 0) {
        v.setPadding(v.paddingLeft, dp(top), v.paddingRight, dp(bottom))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun cardBox(parent: LinearLayout, title: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        box.addView(text(title, 18f, Color.WHITE, bold = true).also { pad(it, bottom = 10) })
        parent.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(20) })
        return box
    }

    private fun radio(label: String, checked: Boolean, onPick: () -> Unit): RadioButton =
        RadioButton(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(accent)
            id = View.generateViewId()
            isChecked = checked
            setOnCheckedChangeListener { _, isOn -> if (isOn) onPick() }
        }

    private fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(accent)
            isChecked = checked
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }
}
