package com.hololo.app.dnschanger.utils

import android.content.Context

object RateManager {

    private const val PREF = "rate_pref"
    private const val KEY_SHOWN = "rate_shown"

    fun shouldShow(context: Context): Boolean {
        val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return !pref.getBoolean(KEY_SHOWN, false)
    }

    fun markShown(context: Context) {
        val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        pref.edit().putBoolean(KEY_SHOWN, true).apply()
    }
}
