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
    // Page the hero book should open at: the saved page for a resume hero,
    // -1 otherwise (the reader then falls back to its own saved page).
    private var currentStartPage = -1
    // Scroll offset the hero book should open at (reflowable books only),
    // -1 otherwise.
    private var currentResumeScroll = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentHomeBinding.inflate(inflater, container, false)
        binding = b
        b.homeTitle.paintFlags = b.homeTitle.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        b.homeTitle.setOnClickListener { openCurrent() }
        // The hero painting opens the same book, exactly like the title.
        b.homePainting.setOnClickListener { openCurrent() }
        b.homeStar.setOnClickListener { toggleSave() }
        b.homeNext.setOnClickListener {
            val catalog = CatalogStore.load(requireContext())
            catalog.anotherStory(current)?.let { showBook(it) }
        }
        return b.root
    }

    override fun onResume() {
        super.onResume()
        val prefs = Prefs(requireContext())
        val catalog = CatalogStore.load(requireContext())
        // "Continue reading" wins the hero: the most recently read
        // UNFINISHED book, PDF or reflowable, instead of the story of the day.
        val resume = prefs.lastUnfinishedAny()
        if (resume != null) {
            val resumeBook = when (resume.kind) {
                com.mahesh.hindustorybooks.data.ResumeKind.PDF ->
                    catalog.allBooks.firstOrNull { it.pdfUrl() == resume.url }
                com.mahesh.hindustorybooks.data.ResumeKind.REFLOW ->
                    catalog.allBooks.firstOrNull { it.reflowableUrl() == resume.url }
            }
            if (resumeBook != null) {
                if (resume.kind == com.mahesh.hindustorybooks.data.ResumeKind.PDF) {
                    showBook(resumeBook, resumePage = resume.position, isResume = true)
                } else {
                    showBook(resumeBook, resumeScroll = resume.position, isResume = true)
                }
                return
            }
        }
        val pick = catalog.storyOfTheDay()
        if (pick != null) showBook(pick) else binding?.homeTitle?.setText(R.string.loading)
    }

    // Show/hide tab switches do not trigger onResume; sync the star here so
    // a save made on another tab is reflected when returning to Home.
    // (Kept for safety; tab navigation now uses replace, so onResume
    // normally covers this via showBook -> refreshStar.)
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshStar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun openCurrent() {
        val book = current ?: return
        openBook(
            requireContext(),
            book,
            book.title(Prefs(requireContext()).language),
            currentStartPage,
            currentResumeScroll
        )
    }

    private fun showBook(
        book: Book,
        resumePage: Int = -1,
        resumeScroll: Int = -1,
        isResume: Boolean = false
    ) {
        current = book
        currentStartPage = resumePage
        currentResumeScroll = resumeScroll
        val b = binding ?: return
        val lang = Prefs(requireContext()).language
        b.homeKicker.text = if (isResume && resumeScroll >= 0) {
            // Reflowable books have no pages: no page number in the kicker.
            getString(R.string.kicker_resume_reflow)
        } else if (isResume) {
            getString(R.string.kicker_resume, resumePage + 1)
        } else {
            getString(R.string.kicker_today)
        }
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
