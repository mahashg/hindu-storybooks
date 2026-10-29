package com.mahesh.hindustorybooks.data

import android.content.Context
import com.mahesh.hindustorybooks.model.Catalog
import com.mahesh.hindustorybooks.model.parseCatalog
import java.io.File

/**
 * Catalog storage. The cached copy lives in filesDir and is replaced only by
 * a fully validated refresh. The bundled asset (assets/catalog.json) is the
 * fallback and is NEVER overwritten by a refresh.
 */
object CatalogStore {
    private const val CACHED_NAME = "catalog.json"
    private const val TEMP_NAME = "catalog.json.tmp"
    private const val ASSET_NAME = "catalog.json"

    /** Cached catalog if present, else the bundled asset. Never null in practice. */
    fun load(context: Context): Catalog {
        val cached = File(context.filesDir, CACHED_NAME)
        if (cached.exists()) {
            try {
                return parseCatalog(cached.readText())
            } catch (e: Exception) {
                // Corrupt cache: fall through to the bundled asset.
            }
        }
        val assetText = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return parseCatalog(assetText)
    }

    /** Current cached catalog, or null when no refresh has ever landed. */
    fun loadCachedOnly(context: Context): Catalog? {
        val cached = File(context.filesDir, CACHED_NAME)
        if (!cached.exists()) return null
        return try {
            parseCatalog(cached.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Atomic replace: write to a temp file, then rename over the cache. */
    fun saveAtomically(context: Context, json: String) {
        val tmp = File(context.filesDir, TEMP_NAME)
        val dst = File(context.filesDir, CACHED_NAME)
        tmp.writeText(json)
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }
}
