package com.mahesh.hindustorybooks.ui

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.mahesh.hindustorybooks.data.CatalogStore
import com.mahesh.hindustorybooks.data.Prefs
import com.mahesh.hindustorybooks.databinding.FragmentSavedBinding
import com.mahesh.hindustorybooks.model.Book

class SavedFragment : Fragment() {

    private var binding: FragmentSavedBinding? = null
    private var adapter: BookAdapter? = null

    /**
     * The list's scroll position. Same story as Catalog: tab switches use
     * replace() (view destroyed) and reload() used to swap in a new adapter
     * on every return from a reader, so the list always reopened at the top.
     * The field survives tab switches because MainActivity holds this
     * fragment instance; the saved-state bundle covers activity recreates.
     */
    private var listState: Parcelable? = null

    companion object {
        private const val KEY_LIST_STATE = "saved_list_state"
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentSavedBinding.inflate(inflater, container, false)
        binding = b
        listState = savedInstanceState?.getParcelable(KEY_LIST_STATE) ?: listState
        b.savedList.layoutManager = LinearLayoutManager(requireContext())
        // One adapter for the fragment's life; reload() updates it in place
        // instead of swapping in a new adapter (which discarded the scroll
        // position on every return from a reader).
        if (adapter == null) {
            adapter = BookAdapter(
                items = emptyList(),
                lang = Prefs(requireContext()).language,
                isSaved = { true },
                isRead = { url -> Prefs(requireContext()).isRead(url) },
                onCard = { book -> openBook(book) },
                onStar = { book -> unsave(book) }
            )
        }
        b.savedList.adapter = adapter
        return b.root
    }

    override fun onResume() {
        super.onResume()
        reload()
        // Restore after the posted layout pass, so the position applies to
        // the refreshed data rather than the stale layout.
        val state = listState
        if (state != null) {
            binding?.savedList?.post {
                binding?.savedList?.layoutManager?.onRestoreInstanceState(state)
            }
        }
    }

    // Show/hide tab switches do not trigger onResume; reload here so an
    // unsave on another tab is reflected when returning to Saved.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reload()
    }

    override fun onPause() {
        listState = binding?.savedList?.layoutManager?.onSaveInstanceState() ?: listState
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = binding?.savedList?.layoutManager?.onSaveInstanceState() ?: listState
        if (state != null) outState.putParcelable(KEY_LIST_STATE, state)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        val prefs = Prefs(requireContext())
        val catalog = CatalogStore.load(requireContext())
        val saved = catalog.allBooks.filter { book ->
            val url = book.pdfUrl()
            url != null && prefs.isSaved(url)
        }
        val items = saved.map { book ->
            DisplayItem.BookCard(book, book.pdfUrl() in prefs.arrivalPdfUrls())
        }
        val existing = adapter
        if (existing == null) {
            adapter = BookAdapter(
                items = items,
                lang = prefs.language,
                isSaved = { true },
                isRead = { url -> prefs.isRead(url) },
                onCard = { book -> openBook(book) },
                onStar = { book -> unsave(book) }
            )
            binding?.savedList?.adapter = adapter
        } else {
            existing.update(items)
        }
        val b = binding ?: return
        if (items.isEmpty()) {
            b.savedList.visibility = View.GONE
            b.savedEmpty.visibility = View.VISIBLE
        } else {
            b.savedList.visibility = View.VISIBLE
            b.savedEmpty.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        listState = binding?.savedList?.layoutManager?.onSaveInstanceState() ?: listState
        super.onDestroyView()
        binding = null
        adapter = null
    }

    private fun openBook(book: Book) {
        openBook(requireContext(), book, book.title(Prefs(requireContext()).language))
    }

    private fun unsave(book: Book) {
        val url = book.pdfUrl() ?: return
        Prefs(requireContext()).setSaved(url, false)
        showSaveToast(this, false)
        reload()
    }
}
