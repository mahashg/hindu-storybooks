package com.mahesh.hindustorybooks.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Applies the user's language choice app-wide, immediately. */
object LocaleHelper {
    fun apply(language: String) {
        val locales = LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
