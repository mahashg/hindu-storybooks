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
 * the phone: page bitmaps are scaled from the actual runtime screen width,
 * never a fixed pixel size. Zoom: Settings default on open, pinch and +/−
 * in the reader for the session. v1 scope is clean reading + zoom only.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URL = "pdf_url"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var binding: ActivityReaderBinding
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var currentPage = 0
    private var zoom = 1.0f
    private var zoomGen = 0
    private var screenWidthPx = 1080
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
                        .coerceIn(0.75f, 3.0f)
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            renderer?.close()
        } catch (ignored: Exception) {
        }
        try {
            pfd?.close()
        } catch (ignored: Exception) {
        }
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
        val clamped = newZoom.coerceIn(0.75f, 3.0f)
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
        private val bitmaps = object : LruCache<Int, Bitmap>(3) {}

        fun evictBitmaps() {
            bitmaps.evictAll()
        }

        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_page, parent, false) as ImageView
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val tag = "$position:$zoomGen"
            holder.image.tag = tag
            val cached = bitmaps.get(position)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
                return
            }
            holder.image.setImageBitmap(null)
            val zoomAtStart = zoom
            Thread {
                val bitmap = renderPage(position, zoomAtStart)
                if (bitmap != null) {
                    bitmaps.put(position, bitmap)
                    holder.image.post {
                        if (holder.image.tag == tag) holder.image.setImageBitmap(bitmap)
                    }
                }
            }.start()
        }

        /** Renders one page at fit-to-width scale from runtime screen width. */
        private fun renderPage(position: Int, zoomFactor: Float): Bitmap? {
            val r = renderer ?: return null
            synchronized(renderLock) {
                try {
                    r.openPage(position).use { page ->
                        // Bitmap width from the real screen width times zoom,
                        // capped so very high zoom stays within memory budget.
                        val width = ((screenWidthPx * zoomFactor).roundToInt())
                            .coerceIn(1, 1600)
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

        inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image)
    }
}
