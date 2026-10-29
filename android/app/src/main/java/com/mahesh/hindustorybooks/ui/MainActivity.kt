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

    override fun attachBaseContext(newBase: Context) {
        val lang = Prefs(newBase).language
        LocaleHelper.apply(lang)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, homeFragment, "home")
            .commit()

        binding.bottomNav.setOnItemSelectedListener { item ->
            val next = when (item.itemId) {
                R.id.nav_home -> homeFragment
                R.id.nav_catalog -> catalogFragment
                R.id.nav_saved -> savedFragment
                R.id.nav_settings -> settingsFragment
                else -> homeFragment
            }
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
