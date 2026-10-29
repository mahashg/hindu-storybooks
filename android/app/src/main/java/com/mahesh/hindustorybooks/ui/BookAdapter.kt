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
    data class SectionHeader(val title: String) : DisplayItem()
    data class GroupHeader(val title: String) : DisplayItem()
    data class BookCard(val book: Book, val isNew: Boolean) : DisplayItem()
}

/**
 * One adapter for Catalog and Saved. Cards are text-only: title, description,
 * meta, a small star at the right end, and a नया badge for arrivals.
 * Tapping a card reads; tapping the star saves/unsaves.
 */
class BookAdapter(
    private var items: List<DisplayItem>,
    private val lang: String,
    private val isSaved: (String) -> Boolean,
    private val onCard: (Book) -> Unit,
    private val onStar: (Book) -> Unit
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

    private class SectionHolder(private val b: ItemSectionBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: DisplayItem.SectionHeader) {
            b.sectionTitle.text = item.title
        }
    }

    private class GroupHolder(private val b: ItemGroupBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: DisplayItem.GroupHeader) {
            b.groupTitle.text = item.title
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
 *  then each labeled group row with its books. */
fun buildCatalogItems(
    catalog: com.mahesh.hindustorybooks.model.Catalog,
    lang: String,
    arrivalUrls: Set<String>
): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    for (section in catalog.sections) {
        items.add(DisplayItem.SectionHeader(section.title(lang)))
        val ungrouped = section.books.filter { it.group == null }
        val grouped = section.books.filter { it.group != null }
            .groupBy { it.group!! }
        for (book in ungrouped) {
            items.add(DisplayItem.BookCard(book, book.pdfUrl() in arrivalUrls))
        }
        for ((_, books) in grouped) {
            val title = books.firstOrNull()?.groupTitle(lang) ?: ""
            if (title.isNotEmpty()) items.add(DisplayItem.GroupHeader(title))
            for (book in books) {
                items.add(DisplayItem.BookCard(book, book.pdfUrl() in arrivalUrls))
            }
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
