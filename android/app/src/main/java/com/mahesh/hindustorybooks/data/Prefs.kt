package com.mahesh.hindustorybooks.data

import android.content.Context
import android.content.SharedPreferences

/** Single SharedPreferences home for language, saved books, arrivals, zoom. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("storybooks", Context.MODE_PRIVATE)

    var language: String
        get() = prefs.getString(KEY_LANG, "hi") ?: "hi"
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    /** Saved books, as pdf URLs — shared by Home, Catalog and Saved. */
    fun savedPdfUrls(): MutableSet<String> =
        prefs.getStringSet(KEY_SAVED, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun isSaved(pdfUrl: String): Boolean = savedPdfUrls().contains(pdfUrl)

    fun setSaved(pdfUrl: String, saved: Boolean) {
        val set = savedPdfUrls()
        if (saved) set.add(pdfUrl) else set.remove(pdfUrl)
        prefs.edit().putStringSet(KEY_SAVED, set).apply()
    }

    /** Books that arrived in the latest refresh, as pdf URLs. */
    fun arrivalPdfUrls(): Set<String> =
        prefs.getStringSet(KEY_ARRIVALS, emptySet()) ?: emptySet()

    fun setArrivals(pdfUrls: Set<String>) {
        prefs.edit().putStringSet(KEY_ARRIVALS, pdfUrls).apply()
    }

    /** Default reader zoom: 1.0 = fit to width, else 1.25 / 1.5 / 2.0. */
    var zoomDefault: Float
        get() = prefs.getFloat(KEY_ZOOM, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ZOOM, value).apply()

    companion object {
        private const val KEY_LANG = "language"
        private const val KEY_SAVED = "saved_pdf_urls"
        private const val KEY_ARRIVALS = "arrival_pdf_urls"
        private const val KEY_ZOOM = "zoom_default"
    }
}
