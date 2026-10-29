package com.mahesh.hindustorybooks.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Painting loader: BitmapFactory over HttpURLConnection on a background
 * thread, with an in-memory cache. No image library. The view tag guards
 * against recycled views receiving a stale bitmap.
 */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String, view: ImageView) {
        view.tag = url
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageBitmap(null)
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bitmap != null) {
                    cache.put(url, bitmap)
                    view.post {
                        if (view.tag == url) view.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                // Leave the placeholder; a missing painting never crashes a card.
            }
        }.start()
    }
}
