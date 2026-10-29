package com.mahesh.hindustorybooks.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mahesh.hindustorybooks.model.CATALOG_URL
import com.mahesh.hindustorybooks.model.Catalog
import com.mahesh.hindustorybooks.model.computeArrivals
import com.mahesh.hindustorybooks.model.parseCatalog
import java.net.HttpURLConnection
import java.net.URL

sealed class RefreshResult {
    /** Refresh landed. count = newly arrived books (per the user's rule). */
    data class Success(val count: Int, val arrivals: List<String>) : RefreshResult()
    /** Nothing was replaced; failures lists what did not validate. */
    data class Failure(val failures: List<String>) : RefreshResult()
}

/**
 * Catalog refresh with validation, shared by the manual button, the launch
 * refresh and the daily worker. The cached catalog is replaced ONLY when
 * every check passes; otherwise it stays untouched.
 */
object CatalogRefresh {
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    /** Blocking version — call off the main thread (Worker, background thread). */
    fun refreshBlocking(context: Context): RefreshResult {
        val oldCatalog: Catalog? = try {
            CatalogStore.loadCachedOnly(context) ?: CatalogStore.load(context)
        } catch (e: Exception) {
            null
        }
        val json: String
        try {
            json = httpGet(CATALOG_URL)
        } catch (e: Exception) {
            return RefreshResult.Failure(listOf("catalog.json: ${e.message}"))
        }
        val candidate: Catalog
        try {
            candidate = parseCatalog(json) // also enforces ui/sections/books checks
        } catch (e: Exception) {
            return RefreshResult.Failure(listOf("catalog.json: ${e.message}"))
        }
        val failures = mutableListOf<String>()
        val urls = linkedSetOf<String>()
        for (book in candidate.allBooks) {
            book.pdfUrl()?.let { urls.add(it) }
        }
        for (book in candidate.pool) {
            book.paintingUrl()?.let { urls.add(it) }
        }
        for (url in urls) {
            if (!urlReachable(url)) failures.add(url)
        }
        if (failures.isNotEmpty()) return RefreshResult.Failure(failures)
        val arrivals = computeArrivals(oldCatalog, candidate)
        CatalogStore.saveAtomically(context, json)
        Prefs(context).setArrivals(arrivals.mapNotNull { it.pdfUrl() }.toSet())
        return RefreshResult.Success(arrivals.size, arrivals.mapNotNull { it.pdfUrl() })
    }

    /** Async version for UI callers; the result arrives on the main thread. */
    fun refreshAsync(context: Context, onResult: (RefreshResult) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = refreshBlocking(context)
            main.post { onResult(result) }
        }.start()
    }

    private fun httpGet(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * HEAD the URL; on any non-200, retry once with a 1-byte range GET and
     * accept 200 or 206. (Some CDNs answer HEAD oddly but serve ranges.)
     */
    private fun urlReachable(urlString: String): Boolean {
        if (headOk(urlString)) return true
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            try {
                conn.responseCode == HttpURLConnection.HTTP_OK ||
                    conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun headOk(urlString: String): Boolean {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            try {
                conn.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
