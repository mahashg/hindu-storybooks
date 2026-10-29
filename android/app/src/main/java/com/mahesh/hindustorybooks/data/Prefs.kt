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

    /** Default reader zoom: 1.0 = fit to width, else 1.25 / 1.5 / 2.0 / 3.0. */
    var zoomDefault: Float
        get() = prefs.getFloat(KEY_ZOOM, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ZOOM, value).apply()

    /**
     * Last-read page per book, as a JSON map: pdfUrl -> {page, time}.
     * A book with no entry is either never started or finished.
     */
    private fun resumeMap(): org.json.JSONObject {
        return try {
            org.json.JSONObject(prefs.getString(KEY_RESUME, "{}") ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    /** Saved page (0-based) for a book, or -1 if none. */
    fun resumePage(pdfUrl: String): Int {
        return resumeMap().optJSONObject(pdfUrl)?.optInt("page", -1) ?: -1
    }

    /** Record the page a book was left on (not the last page — see below). */
    fun setResumePage(pdfUrl: String, page: Int) {
        val map = resumeMap()
        map.put(
            pdfUrl,
            org.json.JSONObject()
                .put("page", page)
                .put("time", System.currentTimeMillis())
        )
        prefs.edit().putString(KEY_RESUME, map.toString()).apply()
    }

    /** Clear the resume entry: the book is finished (or never started). */
    fun clearResume(pdfUrl: String) {
        val map = resumeMap()
        map.remove(pdfUrl)
        prefs.edit().putString(KEY_RESUME, map.toString()).apply()
    }

    /** Most recently read UNFINISHED book: (pdfUrl, page), or null. */
    fun lastUnfinished(): Pair<String, Int>? {
        val map = resumeMap()
        var bestUrl: String? = null
        var bestTime = -1L
        var bestPage = -1
        for (key in map.keys()) {
            val entry = map.optJSONObject(key) ?: continue
            val time = entry.optLong("time", 0)
            if (time > bestTime) {
                bestTime = time
                bestUrl = key
                bestPage = entry.optInt("page", -1)
            }
        }
        return if (bestUrl != null && bestPage >= 0) bestUrl to bestPage else null
    }

    companion object {
        private const val KEY_LANG = "language"
        private const val KEY_SAVED = "saved_pdf_urls"
        private const val KEY_ARRIVALS = "arrival_pdf_urls"
        private const val KEY_ZOOM = "zoom_default"
        private const val KEY_RESUME = "resume_pages"
    }
}
