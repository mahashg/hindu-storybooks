package com.mahesh.hindustorybooks.model

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

const val SITE_BASE = "https://mahashg.github.io/hindu-storybooks/"
const val CATALOG_URL = SITE_BASE + "catalog.json"
const val ARCHIVES_SECTION = "archives"

data class Book(
    val titleHi: String,
    val titleEn: String,
    val descHi: String,
    val descEn: String,
    val metaHi: String,
    val metaEn: String,
    val pdf: String?,
    val painting: String?,
    val group: String?,
    val reflowable: String?,
    val reflowableSha256: String?,
    val reflowableEn: String?,
    val reflowableEnSha256: String?,
    val sectionId: String,
    val sectionTitleHi: String,
    val sectionTitleEn: String,
    val groupTitleHi: String?,
    val groupTitleEn: String?
) {
    val isPublished: Boolean get() = pdf != null
    fun title(lang: String) = if (lang == "en") titleEn else titleHi
    fun desc(lang: String) = if (lang == "en") descEn else descHi
    fun meta(lang: String) = if (lang == "en") metaEn else metaHi
    // Defensive: a blank or literal-"null" segment must never become a URL
    // (a "null" pdf used to resolve to SITE_BASE + "null" and fail refresh).
    fun pdfUrl(): String? =
        pdf?.takeIf { it.isNotBlank() && it != "null" }?.let { resolve(it) }
            ?.takeIf { it.isNotEmpty() }
    fun paintingUrl(): String? =
        painting?.takeIf { it.isNotBlank() && it != "null" }?.let { resolve(it) }
            ?.takeIf { it.isNotEmpty() }
    // Reflowable edition URL, guarded exactly like pdfUrl() (v1.3 lesson:
    // a JSON null must never become a ".../null" URL).
    fun reflowableUrl(): String? =
        reflowable?.takeIf { it.isNotBlank() && it != "null" }?.let { resolve(it) }
            ?.takeIf { it.isNotEmpty() }
    // English reflowable edition URL, same guards (v1.7).
    fun reflowableEnUrl(): String? =
        reflowableEn?.takeIf { it.isNotBlank() && it != "null" }?.let { resolve(it) }
            ?.takeIf { it.isNotEmpty() }
    fun sectionTitle(lang: String) = if (lang == "en") sectionTitleEn else sectionTitleHi
    fun groupTitle(lang: String): String? =
        if (lang == "en") groupTitleEn else groupTitleHi

    companion object {
        fun resolve(path: String): String {
            val clean = path.trim()
            // Never build a URL from a blank or literal-"null" segment.
            if (clean.isEmpty() || clean == "null") return ""
            return if (clean.startsWith("http://") || clean.startsWith("https://")) clean
            else SITE_BASE + clean
        }
    }
}

data class Section(
    val id: String,
    val titleHi: String,
    val titleEn: String,
    val groups: Map<String, Pair<String, String>>,
    val books: List<Book>
) {
    fun title(lang: String) = if (lang == "en") titleEn else titleHi
}

data class Catalog(val sections: List<Section>) {
    val allBooks: List<Book> get() = sections.flatMap { it.books }

    /** Home pool: published, not in Archives, has a painting. */
    val pool: List<Book>
        get() = allBooks.filter {
            it.isPublished && it.sectionId != ARCHIVES_SECTION && it.painting != null
        }

    /** FNV-1a hash of today's local date, mod pool size — stable all day. */
    fun storyOfTheDay(): Book? {
        val p = pool
        if (p.isEmpty()) return null
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        var hash = 2166136261L
        for (b in date.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return p[(hash % p.size).toInt()]
    }

    /** Timestamp-seeded shuffle, never the story currently on screen. */
    fun anotherStory(except: Book?): Book? {
        val p = pool
        if (p.isEmpty()) return null
        if (p.size == 1) return p[0]
        val rnd = Random(System.currentTimeMillis())
        var pick: Book
        do {
            pick = p[rnd.nextInt(p.size)]
        } while (pick == except)
        return pick
    }
}

/** Unescape the HTML entities the catalog JSON carries in some titles. */
fun unescape(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")

/** Nullable catalog string: a missing key, a JSON null, a blank value, or
 * the literal string "null" (what this project's org.json optString yields
 * for a JSON null — the live catalog carries explicit "pdf": null for all
 * planned books) all mean "absent". */
private fun optNullable(obj: JSONObject, key: String): String? {
    if (obj.isNull(key)) return null
    val v = obj.optString(key, "").trim()
    return if (v.isEmpty() || v == "null") null else v
}

fun parseCatalog(json: String): Catalog {
    val root = JSONObject(json)
    if (!root.has("ui")) throw IllegalArgumentException("catalog has no ui block")
    val sectionsJson = root.optJSONArray("sections")
        ?: throw IllegalArgumentException("catalog has no sections")
    if (sectionsJson.length() == 0) throw IllegalArgumentException("catalog sections empty")
    val sections = mutableListOf<Section>()
    var totalBooks = 0
    for (i in 0 until sectionsJson.length()) {
        val s = sectionsJson.getJSONObject(i)
        val id = s.optString("id", "")
        val groups = mutableMapOf<String, Pair<String, String>>()
        val groupsJson = s.optJSONObject("groups")
        if (groupsJson != null) {
            for (key in groupsJson.keys()) {
                val g = groupsJson.getJSONObject(key)
                groups[key] = Pair(
                    unescape(g.optString("title_hi", "")),
                    unescape(g.optString("title_en", ""))
                )
            }
        }
        val booksJson = s.optJSONArray("books") ?: continue
        val books = mutableListOf<Book>()
        for (j in 0 until booksJson.length()) {
            val b = booksJson.getJSONObject(j)
            val groupKey = optNullable(b, "group")
            books.add(
                Book(
                    titleHi = unescape(b.optString("title_hi", "")),
                    titleEn = unescape(b.optString("title_en", "")),
                    descHi = unescape(b.optString("desc_hi", "")),
                    descEn = unescape(b.optString("desc_en", "")),
                    metaHi = unescape(b.optString("meta_hi", "")),
                    metaEn = unescape(b.optString("meta_en", "")),
                    pdf = optNullable(b, "pdf"),
                    painting = optNullable(b, "painting"),
                    group = groupKey,
                    reflowable = optNullable(b, "reflowable"),
                    reflowableSha256 = optNullable(b, "reflowable_sha256"),
                    reflowableEn = optNullable(b, "reflowable_en"),
                    reflowableEnSha256 = optNullable(b, "reflowable_en_sha256"),
                    sectionId = id,
                    sectionTitleHi = unescape(s.optString("title_hi", "")),
                    sectionTitleEn = unescape(s.optString("title_en", "")),
                    groupTitleHi = groupKey?.let { groups[it]?.first },
                    groupTitleEn = groupKey?.let { groups[it]?.second }
                )
            )
        }
        totalBooks += books.size
        sections.add(Section(id, unescape(s.optString("title_hi", "")), unescape(s.optString("title_en", "")), groups, books))
    }
    if (totalBooks == 0) throw IllegalArgumentException("catalog has no books")
    return Catalog(sections)
}

/**
 * Arrival computation, exactly per the user's rule: a book counts as arrived
 * if it has a PDF in the new catalog AND either (a) it was previously planned
 * (no PDF) and is now published with a PDF, or (b) it was not present in the
 * old catalog at all and now appears with a PDF. Books already published
 * with a PDF before do NOT count. Identity is section + title in either
 * language, so a planned book keeps its identity when its PDF arrives.
 */
fun computeArrivals(old: Catalog?, new: Catalog): List<Book> {
    val oldByKey = mutableMapOf<String, Book>()
    old?.allBooks?.forEach { b ->
        oldByKey[keyOf(b)] = b
        oldByKey[altKeyOf(b)] = b
    }
    val arrivals = mutableListOf<Book>()
    for (book in new.allBooks) {
        if (!book.isPublished) continue
        val prev = oldByKey[keyOf(book)] ?: oldByKey[altKeyOf(book)]
        if (prev == null || !prev.isPublished) arrivals.add(book)
    }
    return arrivals
}

private fun keyOf(b: Book) = b.sectionId + "|" + b.titleEn
private fun altKeyOf(b: Book) = b.sectionId + "|" + b.titleHi
