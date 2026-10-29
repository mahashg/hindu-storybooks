package com.mahesh.hindustorybooks.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mahesh.hindustorybooks.R
import com.mahesh.hindustorybooks.data.Prefs
import com.mahesh.hindustorybooks.databinding.ActivityReaderBinding
import com.mahesh.hindustorybooks.util.LocaleHelper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * In-app PDF reader. Downloads the story PDF to the app cache (reused on
 * reopen), renders with android.graphics.pdf.PdfRenderer, and always fits
 * the phone: page bitmaps are rendered at the CURRENT zoom from the actual
 * runtime screen width (never an upscaled low-resolution bitmap), capped
 * at 3000px wide for memory. Zoom: Settings default on open, pinch and +/−
 * in the reader (0.75×–4.0×) for the session. Last-read page is stored per
 * book and restored on reopen; viewing the last page marks the book
 * finished. v1.1 scope is clean reading + zoom + resume only.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URL = "pdf_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_PAGE = "start_page"
        private const val MAX_BITMAP_WIDTH = 3000
        private const val MIN_ZOOM = 0.75f
        private const val MAX_ZOOM = 4.0f
        private const val BITMAP_CACHE_KB = 96 * 1024
    }

    private lateinit var binding: ActivityReaderBinding
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var currentPage = 0
    private var zoom = 1.0f
    private var zoomGen = 0
    private var screenWidthPx = 1080
    private var pdfUrl = ""
    private var startPage = 0
    private lateinit var pageAdapter: PageAdapter
    private lateinit var layoutManager: LinearLayoutManager

    override fun attachBaseContext(newBase: Context) {
        LocaleHelper.apply(Prefs(newBase).language)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pdfUrl = intent.getStringExtra(EXTRA_PDF_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        binding.readerTitle.text = title
        binding.readerBack.setOnClickListener { finish() }
        zoom = Prefs(this).zoomDefault
        screenWidthPx = currentScreenWidth()
        this.pdfUrl = pdfUrl
        // Explicit start page (e.g. Home "Continue reading") wins; otherwise
        // restore this book's saved page; otherwise start at the beginning.
        val explicit = intent.getIntExtra(EXTRA_START_PAGE, -1)
        startPage = if (explicit >= 0) explicit else Prefs(this).resumePage(pdfUrl).takeIf { it >= 0 } ?: 0

        binding.readerZoomOut.setOnClickListener { applyZoom(zoom - 0.25f) }
        binding.readerZoomIn.setOnClickListener { applyZoom(zoom + 0.25f) }
        binding.readerPrev.setOnClickListener { goToPage(currentPage - 1, smooth = true) }
        binding.readerNext.setOnClickListener { goToPage(currentPage + 1, smooth = true) }

        layoutManager = LinearLayoutManager(this)
        binding.readerPages.layoutManager = layoutManager
        pageAdapter = PageAdapter()
        binding.readerPages.adapter = pageAdapter
        binding.readerPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val first = layoutManager.findFirstVisibleItemPosition()
                if (first >= 0 && first != currentPage) {
                    currentPage = first
                    updatePageLabel()
                    saveProgress()
                }
            }
        })
        var pendingScale = zoom
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pendingScale = zoom
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    pendingScale = (pendingScale * detector.scaleFactor)
                        .coerceIn(MIN_ZOOM, MAX_ZOOM)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    applyZoom(pendingScale)
                }
            })
        binding.readerPages.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false // do not consume: scrolling must keep working
        }

        Thread { downloadAndOpen(pdfUrl) }.start()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        try {
            renderer?.close()
        } catch (ignored: Exception) {
        }
        try {
            pfd?.close()
        } catch (ignored: Exception) {
        }
    }

    /**
     * Stores the page the book was left on. Viewing the last page counts
     * as FINISHED, so the resume entry is cleared instead of stored.
     */
    private fun saveProgress() {
        if (pageCount <= 0 || pdfUrl.isEmpty()) return
        val prefs = Prefs(this)
        if (currentPage >= pageCount - 1) prefs.clearResume(pdfUrl)
        else prefs.setResumePage(pdfUrl, currentPage)
    }

    private fun currentScreenWidth(): Int {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        }
    }

    private fun downloadAndOpen(pdfUrl: String) {
        val fileName = pdfUrl.substringAfterLast('/').ifEmpty { "book.pdf" }
        val dir = File(cacheDir, "pdfs").apply { mkdirs() }
        val file = File(dir, fileName)
        try {
            if (!(file.exists() && file.length() > 0)) {
                val conn = (URL(pdfUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                val total = conn.contentLength
                runOnUiThread {
                    binding.readerProgress.isIndeterminate = total <= 0
                    if (total > 0) binding.readerProgress.max = total
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
                                runOnUiThread { binding.readerProgress.progress = d }
                            }
                        }
                    }
                }
                conn.disconnect()
            }
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
            runOnUiThread {
                binding.readerProgressRow.visibility = View.GONE
                binding.readerPages.visibility = View.VISIBLE
                binding.readerBottom.visibility = View.VISIBLE
                pageAdapter.notifyDataSetChanged()
                // Restore the saved page (or the explicit start page).
                currentPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                binding.readerPages.post { layoutManager.scrollToPosition(currentPage) }
                updatePageLabel()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, R.string.reader_download_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun applyZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped == zoom) return
        zoom = clamped
        zoomGen++
        pageAdapter.evictBitmaps()
        pageAdapter.notifyDataSetChanged()
        binding.readerPages.post { layoutManager.scrollToPosition(currentPage) }
    }

    private fun goToPage(page: Int, smooth: Boolean) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (smooth) binding.readerPages.smoothScrollToPosition(target)
        else layoutManager.scrollToPosition(target)
        currentPage = target
        updatePageLabel()
    }

    private fun updatePageLabel() {
        if (pageCount > 0) {
            binding.readerPageLabel.text =
                getString(R.string.reader_page, currentPage + 1, pageCount)
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.PageHolder>() {
        private val renderLock = Any()
        // Bitmaps currently shown by an attached holder. Evicted bitmaps in
        // this set are NOT recycled (a view still references them); the rest
        // are recycled on eviction so zoomed pages stay within budget.
        private val displayedBitmaps = mutableSetOf<Bitmap>()
        private val bitmaps = object : LruCache<Int, Bitmap>(BITMAP_CACHE_KB) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)

            override fun entryRemoved(
                evicted: Boolean,
                key: Int,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (evicted && !displayedBitmaps.contains(oldValue)) {
                    try {
                        oldValue.recycle()
                    } catch (ignored: Exception) {
                    }
                }
            }
        }

        fun evictBitmaps() {
            bitmaps.evictAll()
        }

        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_page, parent, false)
            val image = view.findViewById<ImageView>(R.id.page_image)
            return PageHolder(view, image)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            // New bind token: any render started for a previous bind of this
            // recycled holder can never paint over the new page.
            holder.bindToken++
            val myToken = holder.bindToken
            // Clear the old page FIRST so a recycled view never ghosts the
            // previous page behind the new one.
            holder.image.setImageBitmap(null)
            holder.shown?.let { displayedBitmaps.remove(it) }
            holder.shown = null
            val tag = "$position:$zoomGen"
            holder.image.tag = tag
            // True zoom: the page view itself is screen-width × zoom wide
            // (horizontally scrollable), so zoom actually enlarges the page
            // instead of only re-rendering it sharper at the same size.
            // Width is set on every bind, cached or not, so a recycled
            // holder never keeps a previous zoom's width.
            val containerWidth =
                binding.readerPages.width.takeIf { it > 0 } ?: screenWidthPx
            val viewWidth = (containerWidth * zoom).roundToInt().coerceAtLeast(1)
            holder.image.layoutParams.width = viewWidth
            val cached = bitmaps.get(position)
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
                holder.shown = cached
                displayedBitmaps.add(cached)
                return
            }
            Thread {
                val bitmap = renderPage(position, viewWidth)
                if (bitmap != null) {
                    bitmaps.put(position, bitmap)
                    holder.image.post {
                        if (holder.bindToken == myToken &&
                            holder.bindingAdapterPosition == position &&
                            holder.image.tag == tag &&
                            !bitmap.isRecycled
                        ) {
                            holder.image.setImageBitmap(bitmap)
                            holder.shown = bitmap
                            displayedBitmaps.add(bitmap)
                        }
                    }
                }
            }.start()
        }

        override fun onViewRecycled(holder: PageHolder) {
            super.onViewRecycled(holder)
            // Invalidate any in-flight render and drop the old page at once.
            holder.bindToken++
            holder.image.setImageBitmap(null)
            holder.shown?.let { displayedBitmaps.remove(it) }
            holder.shown = null
        }

        /**
         * Renders one page at the CURRENT zoom: bitmap width = display width
         * × zoom (capped for memory), so zoomed text is rendered sharp
         * rather than upscaled from a low-resolution bitmap.
         */
        private fun renderPage(position: Int, viewWidth: Int): Bitmap? {
            val r = renderer ?: return null
            synchronized(renderLock) {
                try {
                    r.openPage(position).use { page ->
                        val width = viewWidth.coerceIn(1, MAX_BITMAP_WIDTH)
                        val height = (width.toFloat() * page.height / page.width)
                            .roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(
                            width, height, Bitmap.Config.ARGB_8888
                        )
                        page.render(
                            bitmap, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        return bitmap
                    }
                } catch (e: Exception) {
                    return null
                }
            }
        }

        inner class PageHolder(view: View, val image: ImageView) :
            RecyclerView.ViewHolder(view) {
            var bindToken = 0
            var shown: Bitmap? = null
        }
    }
}
