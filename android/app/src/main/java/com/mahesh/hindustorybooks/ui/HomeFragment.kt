package com.mahesh.hindustorybooks.ui

import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mahesh.hindustorybooks.R
import com.mahesh.hindustorybooks.data.CatalogStore
import com.mahesh.hindustorybooks.data.ImageLoader
import com.mahesh.hindustorybooks.data.Prefs
import com.mahesh.hindustorybooks.databinding.FragmentHomeBinding
import com.mahesh.hindustorybooks.model.Book

class HomeFragment : Fragment() {

    private var binding: FragmentHomeBinding? = null
    private var current: Book? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentHomeBinding.inflate(inflater, container, false)
        binding = b
        b.homeTitle.paintFlags = b.homeTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        b.homeTitle.setOnClickListener {
            current?.let { book ->
                book.pdfUrl()?.let { url -> openReader(requireContext(), url, book.title(Prefs(requireContext()).language)) }
            }
        }
        b.homeStar.setOnClickListener { toggleSave() }
        b.homeNext.setOnClickListener {
            val catalog = CatalogStore.load(requireContext())
            catalog.anotherStory(current)?.let { showBook(it) }
        }
        return b.root
    }

    override fun onResume() {
        super.onResume()
        val catalog = CatalogStore.load(requireContext())
        val pick = catalog.storyOfTheDay()
        if (pick != null) showBook(pick) else binding?.homeTitle?.setText(R.string.loading)
    }

    // Show/hide tab switches do not trigger onResume; sync the star here so
    // a save made on another tab is reflected when returning to Home.
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshStar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun showBook(book: Book) {
        current = book
        val b = binding ?: return
        val lang = Prefs(requireContext()).language
        b.homeKicker.setText(R.string.kicker_today)
        b.homeTitle.text = book.title(lang)
        b.homeDesc.text = book.desc(lang)
        b.homeMeta.text = book.meta(lang)
        val paintingUrl = book.paintingUrl()
        if (paintingUrl != null) {
            b.homePainting.visibility = View.VISIBLE
            ImageLoader.load(paintingUrl, b.homePainting)
        } else {
            b.homePainting.visibility = View.GONE
        }
        refreshStar()
    }

    private fun toggleSave() {
        val book = current ?: return
        val url = book.pdfUrl() ?: return
        val prefs = Prefs(requireContext())
        val nowSaved = !prefs.isSaved(url)
        prefs.setSaved(url, nowSaved)
        refreshStar()
        Toast.makeText(
            requireContext(),
            if (nowSaved) R.string.toast_saved else R.string.toast_unsaved,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshStar() {
        val b = binding ?: return
        val book = current ?: return
        val url = book.pdfUrl()
        val saved = url != null && Prefs(requireContext()).isSaved(url)
        b.homeStar.text = if (saved) "★" else "☆"
        b.homeStar.setTextColor(
            resources.getColor(
                if (saved) R.color.star_gold else R.color.muted,
                null
            )
        )
        b.homeStar.contentDescription =
            getString(if (saved) R.string.unsave else R.string.save)
    }
}
