package com.mahesh.hindustorybooks.data

import android.content.Context
import android.content.SharedPreferences

/** Single SharedPreferences home for language, saved books, arrivals, zoom, read books. */
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

    /** Books finished (read to the end), as pdf URLs — the same stable
     * identity saved books use, so reflowable books and archive PDFs
     * share one scheme. Local-only: catalog refresh never touches this
     * set (it only replaces arrivals), and SharedPreferences lives in the
     * app's internal data dir, which Android preserves across app updates
     * for the same package and signing key — it is NOT cacheDir (which
     * the system may clear) and NOT the bundled assets catalog (which
     * every update replaces). */
    fun readPdfUrls(): Set<String> =
        prefs.getStringSet(KEY_READ, emptySet()) ?: emptySet()

    fun isRead(pdfUrl: String): Boolean = readPdfUrls().contains(pdfUrl)

    /** Mark finished. One-way: partial re-reads never unmark, and there
     * is no unmark control. */
    fun markRead(pdfUrl: String) {
        val set = readPdfUrls().toMutableSet()
        if (set.add(pdfUrl)) prefs.edit().putStringSet(KEY_READ, set).apply()
    }

    /** One persisted reader zoom, shared across ALL books (default 1.0 = fit to width).
     * Changing zoom in any book saves it here; every book opens at this zoom.
     * Migrates the old Settings book-zoom default once, if present. */
    var bookZoom: Float
        get() = if (prefs.contains(KEY_BOOK_ZOOM)) prefs.getFloat(KEY_BOOK_ZOOM, 1.0f)
        else prefs.getFloat(KEY_ZOOM_OLD, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_BOOK_ZOOM, value).apply()

    /**
     * App text-size choice: 0 = system default (no override), else a font
     * scale (1.15 Medium / 1.3 Large / 1.5 Extra large), applied app-wide.
     * This is the APP zoom — it never touches book zoom.
     */
    var appFontScale: Float
        get() = prefs.getFloat(KEY_APP_FONT_SCALE, 0f)
        set(value) = prefs.edit().putFloat(KEY_APP_FONT_SCALE, value).apply()

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

    /**
     * Last-read scroll position per reflowable book, as a JSON map:
     * reflowableUrl -> {scroll, fraction, time}. Scroll is the WebView's
     * vertical scroll offset in pixels; fraction is the offset as a share
     * of the scrollable range (0 = top, 1 = end). The fraction is what
     * restore uses: pixel offsets go stale when the content height changes
     * (base64 images still decoding, font size changed), while the fraction
     * still lands at the same place in the story. The px offset is kept so
     * older entries and the Home hero's "unfinished" check keep working.
     * A book with no entry is never started or finished (scrolled to the
     * very end).
     */
    private fun reflowResumeMap(): org.json.JSONObject {
        return try {
            org.json.JSONObject(prefs.getString(KEY_REFLOW_RESUME, "{}") ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }

    /** Saved scroll offset for a reflowable book, or -1 if none. */
    fun reflowScroll(reflowUrl: String): Int {
        return reflowResumeMap().optJSONObject(reflowUrl)?.optInt("scroll", -1) ?: -1
    }

    /** Saved fraction of the scrollable range, or null if the entry predates
     * fractions (very old saves) or the book was never started. */
    fun reflowFraction(reflowUrl: String): Float? {
        val entry = reflowResumeMap().optJSONObject(reflowUrl) ?: return null
        if (!entry.has("fraction")) return null
        val f = entry.optDouble("fraction", -1.0).toFloat()
        return if (f >= 0f) f else null
    }

    /** Record the scroll offset AND the fraction of the scrollable range a
     * reflowable book was left at. */
    fun setReflowScroll(reflowUrl: String, scroll: Int, fraction: Float) {
        val map = reflowResumeMap()
        map.put(
            reflowUrl,
            org.json.JSONObject()
                .put("scroll", scroll)
                .put("fraction", fraction.toDouble())
                .put("time", System.currentTimeMillis())
        )
        prefs.edit().putString(KEY_REFLOW_RESUME, map.toString()).apply()
    }

    /** Clear the reflowable resume entry: the book is finished (or never started). */
    fun clearReflowScroll(reflowUrl: String) {
        val map = reflowResumeMap()
        map.remove(reflowUrl)
        prefs.edit().putString(KEY_REFLOW_RESUME, map.toString()).apply()
    }

    /**
     * Most recently read UNFINISHED book across BOTH readers, by timestamp:
     * PDF books carry a page, reflowable books carry a scroll offset.
     */
    fun lastUnfinishedAny(): ResumeInfo? {
        var best: ResumeInfo? = null
        val pdfMap = resumeMap()
        for (key in pdfMap.keys()) {
            val entry = pdfMap.optJSONObject(key) ?: continue
            val page = entry.optInt("page", -1)
            val time = entry.optLong("time", 0)
            if (page >= 0 && (best == null || time > best.time)) {
                best = ResumeInfo(ResumeKind.PDF, key, page, time)
            }
        }
        val reflowMap = reflowResumeMap()
        for (key in reflowMap.keys()) {
            val entry = reflowMap.optJSONObject(key) ?: continue
            val scroll = entry.optInt("scroll", -1)
            val time = entry.optLong("time", 0)
            if (scroll >= 0 && (best == null || time > best.time)) {
                best = ResumeInfo(ResumeKind.REFLOW, key, scroll, time)
            }
        }
        return best
    }

    companion object {
        private const val KEY_LANG = "language"
        private const val KEY_SAVED = "saved_pdf_urls"
        private const val KEY_ARRIVALS = "arrival_pdf_urls"
        private const val KEY_BOOK_ZOOM = "book_zoom"
        private const val KEY_ZOOM_OLD = "zoom_default"
        private const val KEY_APP_FONT_SCALE = "app_font_scale"
        private const val KEY_RESUME = "resume_pages"
        private const val KEY_REFLOW_RESUME = "reflow_resume"
        private const val KEY_READ = "read_pdf_urls"
    }
}

/** Which reader an unfinished book belongs to (PDF = page, reflowable = scroll). */
enum class ResumeKind { PDF, REFLOW }

/** One unfinished book: its reader kind, its URL key, and its position. */
data class ResumeInfo(
    val kind: ResumeKind,
    val url: String,
    val position: Int,
    val time: Long
)
