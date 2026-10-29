package com.mahesh.hindustorybooks.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mahesh.hindustorybooks.R
import com.mahesh.hindustorybooks.data.CatalogRefresh
import com.mahesh.hindustorybooks.data.Prefs
import com.mahesh.hindustorybooks.data.RefreshResult
import com.mahesh.hindustorybooks.databinding.ActivityMainBinding
import com.mahesh.hindustorybooks.util.FontScaleHelper
import com.mahesh.hindustorybooks.util.LocaleHelper
import com.mahesh.hindustorybooks.work.CatalogWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val homeFragment = HomeFragment()
    private val catalogFragment = CatalogFragment()
    private val savedFragment = SavedFragment()
    private val settingsFragment = SettingsFragment()
    private var active: Fragment = homeFragment

    companion object {
        // The tab the user was on when the activity was last torn down.
        // Needed because a settings change recreates this activity (the
        // new locale / font scale only applies via attachBaseContext on a
        // fresh instance), and without this the fresh instance always
        // landed on Home — kicking the user out of Settings.
        private const val KEY_SELECTED_TAB = "selected_tab"
    }

    private fun fragmentFor(tabId: Int): Fragment = when (tabId) {
        R.id.nav_catalog -> catalogFragment
        R.id.nav_saved -> savedFragment
        R.id.nav_settings -> settingsFragment
        else -> homeFragment
    }

    private fun tabIdFor(fragment: Fragment): Int = when (fragment) {
        catalogFragment -> R.id.nav_catalog
        savedFragment -> R.id.nav_saved
        settingsFragment -> R.id.nav_settings
        else -> R.id.nav_home
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = Prefs(newBase).language
        LocaleHelper.apply(lang)
        super.attachBaseContext(FontScaleHelper.wrap(newBase, Prefs(newBase).appFontScale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore the tab the user was on (Settings after a settings
        // change), instead of always starting on Home. active is set
        // first so the listener below treats this tab as already shown.
        val startTabId =
            savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.nav_home) ?: R.id.nav_home
        val start = fragmentFor(startTabId)
        active = start
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, start)
            .commit()

        binding.bottomNav.setOnItemSelectedListener { item ->
            val next = fragmentFor(item.itemId)
            if (next != active) {
                // REPLACE, not add/hide: only one tab's views are ever
                // attached, so a previous tab's painting can never show
                // through the new tab.
                supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host, next)
                    .commit()
                active = next
            }
            true
        }
        // Highlight the restored tab. Firing the listener here is safe:
        // next == active, so it no-ops instead of re-replacing.
        binding.bottomNav.selectedItemId = startTabId

        // Daily background catalog refresh (same validated flow as manual).
        val request = PeriodicWorkRequestBuilder<CatalogWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "catalog-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        // Silent refresh on launch: only speaks when new books arrived.
        CatalogRefresh.refreshAsync(this) { result ->
            if (result is RefreshResult.Success && result.count > 0) {
                Toast.makeText(this, getString(R.string.toast_new_books, result.count), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Remember the visible tab so a recreate (settings change,
        // rotation) restores it instead of dropping the user on Home.
        outState.putInt(KEY_SELECTED_TAB, tabIdFor(active))
        super.onSaveInstanceState(outState)
    }
}

/** Opens the in-app reader for a story. Never an external browser. */
fun openReader(context: Context, pdfUrl: String, title: String, startPage: Int = -1) {
    val intent = Intent(context, ReaderActivity::class.java).apply {
        putExtra(ReaderActivity.EXTRA_PDF_URL, pdfUrl)
        putExtra(ReaderActivity.EXTRA_TITLE, title)
        putExtra(ReaderActivity.EXTRA_START_PAGE, startPage)
    }
    context.startActivity(intent)
}

/**
 * The ONE open path for every book, used by Home, Catalog and Saved.
 * Reflowable ONLY for books that have a reflowable edition (the 17 current
 * books); otherwise the existing PDF reader (the 5 Archives books). There
 * is deliberately no PDF option for reflowable books.
 */
fun openBook(
    context: Context,
    book: com.mahesh.hindustorybooks.model.Book,
    title: String,
    startPage: Int = -1,
    startScroll: Int = -1
) {
    val reflowUrl = book.reflowableUrl()
    if (reflowUrl != null) {
        val intent = Intent(context, ReflowableReaderActivity::class.java).apply {
            putExtra(ReflowableReaderActivity.EXTRA_REFLOW_URL, reflowUrl)
            putExtra(ReflowableReaderActivity.EXTRA_TITLE, title)
            putExtra(ReflowableReaderActivity.EXTRA_SHA256, book.reflowableSha256)
            putExtra(ReflowableReaderActivity.EXTRA_START_SCROLL, startScroll)
        }
        context.startActivity(intent)
    } else {
        openReader(context, book.pdfUrl() ?: return, title, startPage)
    }
}
