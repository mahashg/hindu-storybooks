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
        const val EXTRA_PDF_URL = "pdf_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_START_SCROLL = "start_scroll"
        private const val END_TOLERANCE_PX = 8
        // Restore retries: base64 images keep decoding after page load and
        // the document keeps growing, so a single early scrollTo gets
        // clamped to a too-small maximum and then never revisited. Retry
        // until the content height stops changing and the scroll lands.
        private const val RESTORE_INTERVAL_MS = 250L
        private const val RESTORE_MAX_ATTEMPTS = 20
        private const val RESTORE_TOLERANCE_PX = 4
    }

    private lateinit var binding: ActivityReflowableBinding
    private var reflowUrl = ""
    private var pdfUrl = ""
    private var expectedSha: String? = null
    private var startScroll = -1
    private var currentScroll = 0
    private var loaded = false
    private var restoreStarted = false
    private var restoreRunnable: Runnable? = null
    // Set on any touch: once the user grabs the page, the restore loop
    // must stop yanking the scroll position back.
    private var userInteracted = false

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
        // The book's stable identity for READ marking: its pdf URL, the
        // same key saved books use. openBook always passes it; if it ever
        // arrives empty, finishing marks nothing rather than keying by
        // the reflowable URL (which would split one book across two keys).
        pdfUrl = intent.getStringExtra(EXTRA_PDF_URL) ?: ""
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
        // Touches are only observed, never consumed: the edition still
        // receives them exactly as before.
        webView.setOnTouchListener { _, _ ->
            userInteracted = true
            false
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
            restoreRunnable?.let { binding.reflowWeb.removeCallbacks(it) }
        } catch (ignored: Exception) {
        }
        try {
            binding.reflowWeb.stopLoading()
        } catch (ignored: Exception) {
        }
        try {
            binding.reflowWeb.destroy()
        } catch (ignored: Exception) {
        }
    }

    /** Stores the position the book was left at; reaching the very end
     * counts as FINISHED, so the resume entry is cleared instead — and
     * the book is marked READ (same finish path, one-way). Both the
     * pixel offset and the fraction of the scrollable range are stored:
     * the fraction is what restore uses, since it survives content-height
     * changes between sessions. */
    private fun saveProgress() {
        if (!loaded || reflowUrl.isEmpty()) return
        val prefs = Prefs(this)
        if (isAtEnd()) {
            prefs.clearReflowScroll(reflowUrl)
            if (pdfUrl.isNotEmpty()) prefs.markRead(pdfUrl)
        } else {
            val maxScroll = maxScrollPx()
            val fraction = if (maxScroll > 0) {
                (currentScroll / maxScroll).coerceIn(0f, 1f)
            } else 0f
            prefs.setReflowScroll(reflowUrl, currentScroll, fraction)
        }
    }

    /** Maximum vertical scroll in view pixels, or 0 while the document has
     * no measurable height yet (images still decoding). */
    private fun maxScrollPx(): Float {
        val webView = binding.reflowWeb
        val contentHeight = webView.contentHeight
        if (contentHeight <= 0) return 0f
        return (contentHeight * webView.scale - webView.height).coerceAtLeast(0f)
    }

    private fun isAtEnd(): Boolean {
        val maxScroll = maxScrollPx()
        if (maxScroll <= 0) return false
        return currentScroll >= maxScroll - END_TOLERANCE_PX
    }

    /**
     * Restores the saved position internally, whatever opened the book.
     * Previously only the Home hero passed a scroll offset, so opening a
     * book from Catalog or Saved always started at the top. Now the reader
     * reads Prefs itself: the stored fraction wins (it survives height
     * changes), with the pixel offset as fallback for pre-fraction saves
     * and for the explicit Home-hero offset. The loop re-applies the
     * target until the content height stops changing and the scroll lands
     * — one early scrollTo used to get clamped to a still-growing document
     * and the single 400 ms retry was often still too early. The loop stops
     * as soon as the user touches the page.
     */
    private fun restoreScroll() {
        if (restoreStarted) return
        restoreStarted = true
        val prefs = Prefs(this)
        val fraction = prefs.reflowFraction(reflowUrl)
        val pxFallback = if (startScroll >= 0) startScroll else prefs.reflowScroll(reflowUrl)
        if (fraction == null && pxFallback < 0) return
        val webView = binding.reflowWeb
        var attempts = 0
        var lastHeight = -1
        val step = object : Runnable {
            override fun run() {
                if (userInteracted) return
                attempts++
                val maxScroll = maxScrollPx()
                if (fraction == null || maxScroll > 0) {
                    val targetPx = if (fraction != null) {
                        (fraction * maxScroll).toInt()
                    } else {
                        pxFallback
                    }
                    webView.scrollTo(0, targetPx)
                    val heightNow = webView.contentHeight
                    val settled = heightNow == lastHeight &&
                        kotlin.math.abs(webView.scrollY - targetPx) <= RESTORE_TOLERANCE_PX
                    lastHeight = heightNow
                    if (!settled && attempts < RESTORE_MAX_ATTEMPTS) {
                        webView.postDelayed(this, RESTORE_INTERVAL_MS)
                    }
                } else {
                    // Fraction known but the document has no measurable
                    // height yet: keep waiting for content to arrive.
                    lastHeight = webView.contentHeight
                    if (attempts < RESTORE_MAX_ATTEMPTS) {
                        webView.postDelayed(this, RESTORE_INTERVAL_MS)
                    }
                }
            }
        }
        restoreRunnable = step
        webView.post(step)
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
