package com.mahesh.hindustorybooks.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mahesh.hindustorybooks.R
import com.mahesh.hindustorybooks.data.Prefs
import com.mahesh.hindustorybooks.databinding.ActivityReflowableBinding
import com.mahesh.hindustorybooks.util.FontScaleHelper
import com.mahesh.hindustorybooks.util.LocaleHelper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Reflowable reader for the 17 current books. Renders the edition HTML in
 * a WebView with JavaScript enabled, so the edition's own A−/A+ font
 * toolbar works exactly as on the web. The app's Settings app-zoom applies
 * to app UI only; the edition's toolbar is the book-text-size control, and
 * the shared PDF book zoom is deliberately NOT forced onto the WebView.
 *
 * Safety: the downloaded file is SHA-256-verified against the catalog
 * before it is ever rendered. A cached file is reused only while its hash
 * still matches; a mismatch deletes the file and ends the activity.
 * External navigation is blocked — a book never leaves the app, and no
 * ACTION_VIEW is ever used for books.
 *
 * Resume is position-based (reflowable has no pages): the WebView's
 * vertical scroll offset is stored per book and restored on reopen.
 * Scrolling to the very end marks the book finished and clears its
 * resume entry, mirroring the PDF reader's finished semantics.
 */
class ReflowableReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REFLOW_URL = "reflow_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_START_SCROLL = "start_scroll"
        private const val END_TOLERANCE_PX = 8
        private const val RESTORE_RETRY_MS = 400L
    }

    private lateinit var binding: ActivityReflowableBinding
    private var reflowUrl = ""
    private var expectedSha: String? = null
    private var startScroll = -1
    private var currentScroll = 0
    private var loaded = false
    private var restoreAttempted = false

    override fun attachBaseContext(newBase: Context) {
        LocaleHelper.apply(Prefs(newBase).language)
        super.attachBaseContext(FontScaleHelper.wrap(newBase, Prefs(newBase).appFontScale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReflowableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reflowUrl = intent.getStringExtra(EXTRA_REFLOW_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        binding.reflowTitle.text = title
        binding.reflowBack.setOnClickListener { finish() }
        this.reflowUrl = reflowUrl
        expectedSha = intent.getStringExtra(EXTRA_SHA256)
        startScroll = intent.getIntExtra(EXTRA_START_SCROLL, -1)

        val webView = binding.reflowWeb
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            currentScroll = scrollY
            saveProgress()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                loaded = true
                restoreScroll()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Allow only in-file anchors of the loaded edition; block
                // every other navigation (nothing external, ever).
                val target = request.url.toString().substringBefore("#")
                val loadedFile = "file://" + File(cacheDir, "reflow")
                    .resolve(fileNameFor(reflowUrl)).absolutePath
                return target != loadedFile
            }
        }

        Thread { downloadAndOpen(reflowUrl) }.start()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        try {
            binding.reflowWeb.stopLoading()
        } catch (ignored: Exception) {
        }
        try {
            binding.reflowWeb.destroy()
        } catch (ignored: Exception) {
        }
    }

    /** Stores the scroll offset the book was left at; reaching the very end
     * counts as FINISHED, so the resume entry is cleared instead. */
    private fun saveProgress() {
        if (!loaded || reflowUrl.isEmpty()) return
        val prefs = Prefs(this)
        if (isAtEnd()) prefs.clearReflowScroll(reflowUrl)
        else prefs.setReflowScroll(reflowUrl, currentScroll)
    }

    private fun isAtEnd(): Boolean {
        val webView = binding.reflowWeb
        val contentHeight = webView.contentHeight
        if (contentHeight <= 0) return false
        val maxScroll = (contentHeight * webView.scale - webView.height).coerceAtLeast(0f)
        return currentScroll >= maxScroll - END_TOLERANCE_PX
    }

    private fun restoreScroll() {
        val target = startScroll
        if (target < 0 || restoreAttempted) return
        restoreAttempted = true
        val webView = binding.reflowWeb
        webView.post { webView.scrollTo(0, target) }
        webView.post {
            if (webView.scrollY != target) webView.scrollTo(0, target)
        }
        // Images decode after page load and grow the document; one delayed
        // retry lands the restore even if the first scrolls ran too early.
        webView.postDelayed({
            if (webView.scrollY != target) webView.scrollTo(0, target)
        }, RESTORE_RETRY_MS)
    }

    private fun fileNameFor(url: String): String =
        url.substringAfterLast('/').ifEmpty { "book.html" }

    private fun sha256Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadAndOpen(reflowUrl: String) {
        val fileName = fileNameFor(reflowUrl)
        val dir = File(cacheDir, "reflow").apply { mkdirs() }
        val file = File(dir, fileName)
        val expected = expectedSha?.trim()?.lowercase()
        try {
            // Reuse a cached file only while its hash still matches.
            if (!(file.exists() && file.length() > 0 &&
                    expected != null && sha256Hex(file) == expected)
            ) {
                val conn = (URL(reflowUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                val total = conn.contentLength
                runOnUiThread {
                    binding.reflowProgress.isIndeterminate = total <= 0
                    if (total > 0) binding.reflowProgress.max = total
                }
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var downloaded = 0
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val d = downloaded
                                runOnUiThread { binding.reflowProgress.progress = d }
                            }
                        }
                    }
                }
                conn.disconnect()
            }
            // Fail closed: never render without a matching catalog hash.
            val actual = sha256Hex(file)
            if (expected.isNullOrEmpty() || actual == null || actual != expected) {
                try {
                    file.delete()
                } catch (ignored: Exception) {
                }
                runOnUiThread {
                    Toast.makeText(this, R.string.reader_verify_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
                return
            }
            runOnUiThread {
                binding.reflowProgressRow.visibility = View.GONE
                binding.reflowWeb.visibility = View.VISIBLE
                binding.reflowWeb.loadUrl("file://" + file.absolutePath)
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, R.string.reader_download_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
