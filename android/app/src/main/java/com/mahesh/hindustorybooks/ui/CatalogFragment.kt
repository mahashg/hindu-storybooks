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
import com.mahesh.hindustorybooks.databinding.FragmentCatalogBinding
import com.mahesh.hindustorybooks.model.Book

class CatalogFragment : Fragment() {

    private var binding: FragmentCatalogBinding? = null
    private var adapter: BookAdapter? = null

    /**
     * The list's scroll position. Tab switches use replace(), which destroys
     * this fragment's view, and returning from a reader recreates the data —
     * without this, the list always reopened at the top. The field survives
     * tab switches because MainActivity holds this fragment instance; the
     * saved-state bundle covers activity recreates (settings changes).
     */
    private var listState: Parcelable? = null

    companion object {
        private const val KEY_LIST_STATE = "catalog_list_state"
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentCatalogBinding.inflate(inflater, container, false)
        binding = b
        listState = savedInstanceState?.getParcelable(KEY_LIST_STATE) ?: listState
        b.catalogList.layoutManager = LinearLayoutManager(requireContext())
        // One adapter for the fragment's life: reload() updates its data in
        // place (update() + notifyDataSetChanged) instead of swapping in a
        // new adapter, which is what used to throw the scroll position away
        // on every return from a reader.
        if (adapter == null) {
            adapter = BookAdapter(
                items = emptyList(),
                lang = Prefs(requireContext()).language,
                isSaved = { url -> Prefs(requireContext()).isSaved(url) },
                isRead = { url -> Prefs(requireContext()).isRead(url) },
                onCard = { book -> openBook(book) },
                onStar = { book -> toggleStar(book) },
                onHeaderClick = { key -> toggleHeader(key) }
            )
        }
        b.catalogList.adapter = adapter
        return b.root
    }

    override fun onResume() {
        super.onResume()
        reload()
        // Restore after the posted layout pass, so the position applies to
        // the refreshed data rather than the stale layout.
        val state = listState
        if (state != null) {
            binding?.catalogList?.post {
                binding?.catalogList?.layoutManager?.onRestoreInstanceState(state)
            }
        }
    }

    // Show/hide tab switches do not trigger onResume; reload here so star
    // states stay in sync across tabs.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reload()
    }

    override fun onPause() {
        listState = binding?.catalogList?.layoutManager?.onSaveInstanceState() ?: listState
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = binding?.catalogList?.layoutManager?.onSaveInstanceState() ?: listState
        if (state != null) outState.putParcelable(KEY_LIST_STATE, state)
        super.onSaveInstanceState(outState)
    }

    private fun reload() {
        val prefs = Prefs(requireContext())
        val ctx = requireContext()
        // v1.8: New section (arrivals) at top, Read section (local read
        // marks) at bottom, collapsible headers — all recomputed here, so
        // onResume AND post-refresh reloads pick up catalog updates.
        // READ marks are never cleared by refresh; CatalogRefresh only
        // ever replaces the arrivals set.
        val items = buildCatalogItems(
            catalog = CatalogStore.load(ctx),
            lang = prefs.language,
            arrivalUrls = prefs.arrivalPdfUrls(),
            readUrls = prefs.readPdfUrls(),
            collapsedSections = prefs.collapsedSectionKeys(),
            collapsedGroups = prefs.collapsedGroupKeys(),
            newSectionTitle = ctx.getString(com.mahesh.hindustorybooks.R.string.section_new_books),
            readSectionTitle = ctx.getString(com.mahesh.hindustorybooks.R.string.section_read_books)
        )
        val existing = adapter
        if (existing == null) {
            adapter = BookAdapter(
                items = items,
                lang = prefs.language,
                isSaved = { url -> prefs.isSaved(url) },
                isRead = { url -> prefs.isRead(url) },
                onCard = { book -> openBook(book) },
                onStar = { book -> toggleStar(book) },
                onHeaderClick = { key -> toggleHeader(key) }
            )
            binding?.catalogList?.adapter = adapter
        } else {
            existing.update(items)
        }
    }

    /** Collapse/expand one header and rebuild; the choice persists in Prefs
     *  across launches. Scroll position is kept as far as possible: the
     *  tapped header stays in the list, so the RecyclerView holds its
     *  place approximately (the existing listState restore on onResume
     *  still applies when returning from a reader or another tab). */
    private fun toggleHeader(key: String) {
        val prefs = Prefs(requireContext())
        if (key.startsWith("group:")) {
            prefs.setGroupCollapsed(key, !prefs.isGroupCollapsed(key))
        } else {
            prefs.setSectionCollapsed(key, !prefs.isSectionCollapsed(key))
        }
        reload()
    }

    override fun onDestroyView() {
        listState = binding?.catalogList?.layoutManager?.onSaveInstanceState() ?: listState
        super.onDestroyView()
        binding = null
        adapter = null
    }

    private fun openBook(book: Book) {
        openBook(requireContext(), book, book.title(Prefs(requireContext()).language))
    }

    private fun toggleStar(book: Book) {
        val url = book.pdfUrl() ?: return
        val prefs = Prefs(requireContext())
        val nowSaved = !prefs.isSaved(url)
        prefs.setSaved(url, nowSaved)
        showSaveToast(this, nowSaved)
        reload()
    }
}
