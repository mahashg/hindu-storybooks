package com.mahesh.hindustorybooks.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.mahesh.hindustorybooks.R
import com.mahesh.hindustorybooks.databinding.ItemBookBinding
import com.mahesh.hindustorybooks.databinding.ItemGroupBinding
import com.mahesh.hindustorybooks.databinding.ItemSectionBinding
import com.mahesh.hindustorybooks.model.Book

sealed class DisplayItem {
    /**
     * collapseKey is the Prefs collapse key ("section:<id>" or
     * "group:<sectionId>|<groupKey>"); collapsed is its current state;
     * hasNew shows the section/group-level नया tag. Defaults keep
     * header-less callers (Saved builds BookCards only) unaffected.
     */
    data class SectionHeader(
        val title: String,
        val collapseKey: String = "",
        val collapsed: Boolean = false,
        val hasNew: Boolean = false
    ) : DisplayItem()
    data class GroupHeader(
        val title: String,
        val collapseKey: String = "",
        val collapsed: Boolean = false,
        val hasNew: Boolean = false
    ) : DisplayItem()
    data class BookCard(val book: Book, val isNew: Boolean) : DisplayItem()
}

/**
 * One adapter for Catalog and Saved. Cards are text-only: title, description,
 * meta, a small star at the right end, a नया badge for arrivals, and a
 * READ badge for finished books.
 * Tapping a card reads; tapping the star saves/unsaves.
 * v1.8: section/group headers are tappable and collapse their books;
 * onHeaderClick receives the header's collapse key ("" means no-op, which
 * is the Saved case — Saved never builds headers).
 */
class BookAdapter(
    private var items: List<DisplayItem>,
    private val lang: String,
    private val isSaved: (String) -> Boolean,
    private val isRead: (String) -> Boolean,
    private val onCard: (Book) -> Unit,
    private val onStar: (Book) -> Unit,
    private val onHeaderClick: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_GROUP = 1
        private const val TYPE_BOOK = 2
    }

    fun update(newItems: List<DisplayItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DisplayItem.SectionHeader -> TYPE_SECTION
        is DisplayItem.GroupHeader -> TYPE_GROUP
        is DisplayItem.BookCard -> TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SECTION -> SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_GROUP -> GroupHolder(ItemGroupBinding.inflate(inflater, parent, false))
            else -> BookHolder(ItemBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DisplayItem.SectionHeader -> (holder as SectionHolder).bind(item)
            is DisplayItem.GroupHeader -> (holder as GroupHolder).bind(item)
            is DisplayItem.BookCard -> (holder as BookHolder).bind(item)
        }
    }

    private inner class SectionHolder(private val b: ItemSectionBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: DisplayItem.SectionHeader) {
            b.sectionTitle.text = item.title
            // Same badge style as the book-level नया tag (badge_bg).
            b.sectionNew.visibility = if (item.hasNew) View.VISIBLE else View.GONE
            b.sectionIndicator.text = if (item.collapsed) "▸" else "▾"
            b.root.setOnClickListener {
                if (item.collapseKey.isNotEmpty()) onHeaderClick(item.collapseKey)
            }
        }
    }

    private inner class GroupHolder(private val b: ItemGroupBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: DisplayItem.GroupHeader) {
            b.groupTitle.text = item.title
            b.groupNew.visibility = if (item.hasNew) View.VISIBLE else View.GONE
            b.groupIndicator.text = if (item.collapsed) "▸" else "▾"
            b.root.setOnClickListener {
                if (item.collapseKey.isNotEmpty()) onHeaderClick(item.collapseKey)
            }
        }
    }

    private inner class BookHolder(private val b: ItemBookBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: DisplayItem.BookCard) {
            val book = item.book
            val ctx = b.root.context
            b.bookTitle.text = book.title(lang)
            b.bookDesc.text = book.desc(lang)
            if (book.isPublished) {
                b.bookMeta.text = book.meta(lang)
                b.bookMeta.alpha = 1.0f
                b.root.alpha = 1.0f
            } else {
                b.bookMeta.text = ctx.getString(R.string.planned)
                b.bookMeta.alpha = 0.6f
                b.root.alpha = 0.55f
            }
            b.bookNew.visibility = if (item.isNew) View.VISIBLE else View.GONE
            val pdfUrl = book.pdfUrl()
            // READ badge: same bottom row as the नया tag, quiet chip.
            // Shown only for published books with a pdf identity; planned
            // books (no pdf URL) never carry it.
            b.bookRead.visibility =
                if (pdfUrl != null && isRead(pdfUrl)) View.VISIBLE else View.GONE
            if (pdfUrl == null) {
                b.bookStar.visibility = View.GONE
                b.root.setOnClickListener(null)
            } else {
                b.bookStar.visibility = View.VISIBLE
                val saved = isSaved(pdfUrl)
                b.bookStar.text = if (saved) "★" else "☆"
                b.bookStar.setTextColor(
                    ctx.resources.getColor(
                        if (saved) R.color.star_gold else R.color.muted, null
                    )
                )
                b.bookStar.contentDescription =
                    ctx.getString(if (saved) R.string.unsave else R.string.save)
                b.bookStar.setOnClickListener { onStar(book) }
                b.root.setOnClickListener { onCard(book) }
            }
        }
    }
}

/** Builds the Catalog display list: sections in order, ungrouped books first,
 *  then each labeled group row with its books.
 *
 *  v1.8, display-level only (no catalog.json change):
 *  - A "New books" section at the very top: books whose pdfUrl is in
 *    arrivalUrls AND present in the current catalog, in catalog order.
 *    Hidden entirely when empty; its header carries NO new tag.
 *  - A "Read books" section at the very bottom: books whose pdfUrl is in
 *    readUrls AND present in the current catalog, in catalog order.
 *    Hidden entirely when empty; its header follows the same new-tag rule
 *    as any other section (tagged only if it contains an arrival).
 *  - A book in New/Read ALSO stays in its home section: duplication is
 *    intended (a filtered view, not a move). State stays consistent
 *    because saved/read/resume are all keyed by pdfUrl.
 *  - Every section/group header is collapsible: collapsed headers hide
 *    their books (a collapsed section hides its groups too). Collapse
 *    keys live in Prefs; empty sets mean everything expanded.
 *  - Section/group headers carry the नया tag exactly when their own books
 *    contain an arrival — the same lifecycle as the book-level tags. */
fun buildCatalogItems(
    catalog: com.mahesh.hindustorybooks.model.Catalog,
    lang: String,
    arrivalUrls: Set<String>,
    readUrls: Set<String> = emptySet(),
    collapsedSections: Set<String> = emptySet(),
    collapsedGroups: Set<String> = emptySet(),
    newSectionTitle: String = if (lang == "en") "New books" else "नई किताबें",
    readSectionTitle: String = if (lang == "en") "Read books" else "पढ़ी गई"
): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    val all = catalog.allBooks
    fun isNew(book: Book): Boolean = book.pdfUrl() in arrivalUrls

    // New section at top (hidden when empty).
    val newBooks = all.filter { isNew(it) }
    if (newBooks.isNotEmpty()) {
        val key = "section:new"
        val collapsed = key in collapsedSections
        items.add(DisplayItem.SectionHeader(newSectionTitle, key, collapsed, false))
        if (!collapsed) {
            for (book in newBooks) items.add(DisplayItem.BookCard(book, true))
        }
    }

    for (section in catalog.sections) {
        val sectionKey = "section:${section.id}"
        val sectionCollapsed = sectionKey in collapsedSections
        items.add(
            DisplayItem.SectionHeader(
                section.title(lang),
                sectionKey,
                sectionCollapsed,
                section.books.any { isNew(it) }
            )
        )
        if (sectionCollapsed) continue
        val ungrouped = section.books.filter { it.group == null }
        val grouped = section.books.filter { it.group != null }
            .groupBy { it.group!! }
        for (book in ungrouped) {
            items.add(DisplayItem.BookCard(book, isNew(book)))
        }
        for ((groupKey, books) in grouped) {
            val title = books.firstOrNull()?.groupTitle(lang) ?: ""
            if (title.isEmpty()) {
                // Header-less group (pre-v1.8 behavior): books always shown.
                for (book in books) items.add(DisplayItem.BookCard(book, isNew(book)))
                continue
            }
            val groupCollapseKey = "group:${section.id}|$groupKey"
            val groupCollapsed = groupCollapseKey in collapsedGroups
            items.add(
                DisplayItem.GroupHeader(
                    title,
                    groupCollapseKey,
                    groupCollapsed,
                    books.any { isNew(it) }
                )
            )
            if (!groupCollapsed) {
                for (book in books) items.add(DisplayItem.BookCard(book, isNew(book)))
            }
        }
    }

    // Read section at bottom (hidden when empty).
    val readBooks = all.filter { it.pdfUrl() in readUrls }
    if (readBooks.isNotEmpty()) {
        val key = "section:read"
        val collapsed = key in collapsedSections
        items.add(
            DisplayItem.SectionHeader(
                readSectionTitle,
                key,
                collapsed,
                readBooks.any { isNew(it) }
            )
        )
        if (!collapsed) {
            for (book in readBooks) items.add(DisplayItem.BookCard(book, isNew(book)))
        }
    }
    return items
}

fun showSaveToast(fragment: androidx.fragment.app.Fragment, saved: Boolean) {
    Toast.makeText(
        fragment.requireContext(),
        if (saved) R.string.toast_saved else R.string.toast_unsaved,
        Toast.LENGTH_SHORT
    ).show()
}
