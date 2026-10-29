package com.mahesh.hindustorybooks.ui

import android.os.Bundle
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentCatalogBinding.inflate(inflater, container, false)
        binding = b
        b.catalogList.layoutManager = LinearLayoutManager(requireContext())
        adapter = BookAdapter(
            items = emptyList(),
            lang = Prefs(requireContext()).language,
            isSaved = { url -> Prefs(requireContext()).isSaved(url) },
            onCard = { book -> openBook(book) },
            onStar = { book -> toggleStar(book) }
        )
        b.catalogList.adapter = adapter
        return b.root
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // Show/hide tab switches do not trigger onResume; reload here so star
    // states stay in sync across tabs.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) reload()
    }

    private fun reload() {
        val prefs = Prefs(requireContext())
        val catalog = CatalogStore.load(requireContext())
        adapter = BookAdapter(
            items = buildCatalogItems(catalog, prefs.language, prefs.arrivalPdfUrls()),
            lang = prefs.language,
            isSaved = { url -> prefs.isSaved(url) },
            onCard = { book -> openBook(book) },
            onStar = { book -> toggleStar(book) }
        )
        binding?.catalogList?.adapter = adapter
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

    private fun toggleStar(book: Book) {
        val url = book.pdfUrl() ?: return
        val prefs = Prefs(requireContext())
        val nowSaved = !prefs.isSaved(url)
        prefs.setSaved(url, nowSaved)
        showSaveToast(this, nowSaved)
        reload()
    }
}
