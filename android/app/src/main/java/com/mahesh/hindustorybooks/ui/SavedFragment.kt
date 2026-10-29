package com.mahesh.hindustorybooks.ui

import android.os.Bundle
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentSavedBinding.inflate(inflater, container, false)
        binding = b
        b.savedList.layoutManager = LinearLayoutManager(requireContext())
        return b.root
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // Show/hide tab switches do not trigger onResume; reload here so an
    // unsave on another tab is reflected when returning to Saved.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reload()
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
        adapter = BookAdapter(
            items = items,
            lang = prefs.language,
            isSaved = { true },
            onCard = { book -> openBook(book) },
            onStar = { book -> unsave(book) }
        )
        val b = binding ?: return
        b.savedList.adapter = adapter
        if (items.isEmpty()) {
            b.savedList.visibility = View.GONE
            b.savedEmpty.visibility = View.VISIBLE
        } else {
            b.savedList.visibility = View.VISIBLE
            b.savedEmpty.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
    }

    private fun openBook(book: Book) {
        val url = book.pdfUrl() ?: return
        openReader(requireContext(), url, book.title(Prefs(requireContext()).language))
    }

    private fun unsave(book: Book) {
        val url = book.pdfUrl() ?: return
        Prefs(requireContext()).setSaved(url, false)
        showSaveToast(this, false)
        reload()
    }
}
