package com.vsp.internetspeedmeter.util

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.vsp.internetspeedmeter.R

/** The "تم" preference: light blue (default), purple or dark. */
object Palette {

    const val KEY = "theme_color"

    private fun value(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, "light") ?: "light"

    /** Call before super.onCreate(). */
    fun applyNightMode(context: Context) {
        val target = if (value(context) == "dark") AppCompatDelegate.MODE_NIGHT_YES
        else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    /** Call after super.onCreate() and before setContentView(). */
    fun apply(activity: Activity, paintWindow: Boolean = true) {
        val overlay = when (value(activity)) {
            "purple" -> R.style.ThemeOverlay_Ism_Purple
            "dark" -> R.style.ThemeOverlay_Ism_Dark
            else -> R.style.ThemeOverlay_Ism_Blue
        }
        activity.theme.applyStyle(overlay, true)
        if (paintWindow) {
            val dark = value(activity) == "dark"
            val page = color(activity, R.attr.ismPage)
            activity.window.statusBarColor = page
            activity.window.navigationBarColor = page
            activity.window.setBackgroundDrawable(ColorDrawable(page))
            var flags = activity.window.decorView.systemUiVisibility
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            activity.window.decorView.systemUiVisibility = flags
        }
    }

    fun color(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
