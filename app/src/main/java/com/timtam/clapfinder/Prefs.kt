package com.timtam.clapfinder

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("clapfinder", Context.MODE_PRIVATE)

    var sensitivity: Int
        get() = p.getInt("sensitivity", 65)
        set(v) = p.edit().putInt("sensitivity", v).apply()

    var clapsNeeded: Int
        get() = p.getInt("claps", 2)
        set(v) = p.edit().putInt("claps", v).apply()

    var sound: String
        get() = p.getString("sound", "siren") ?: "siren"
        set(v) = p.edit().putString("sound", v).apply()

    var flash: Boolean
        get() = p.getBoolean("flash", true)
        set(v) = p.edit().putBoolean("flash", v).apply()

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()

    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()

    var ringSeconds: Int
        get() = p.getInt("ring", 30)
        set(v) = p.edit().putInt("ring", v).apply()
}
