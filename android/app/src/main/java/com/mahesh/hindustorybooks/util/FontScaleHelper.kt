package com.mahesh.hindustorybooks.util

import android.content.Context
import android.content.res.Configuration

/**
 * App text-size (APP zoom) support. Wraps a base context with the user's
 * chosen font scale. A scale <= 0 means "system default": no override, the
 * phone's own font size applies. Applied in every activity's
 * attachBaseContext, so it covers the whole app — it never touches book
 * zoom, which lives in the reader.
 */
object FontScaleHelper {
    fun wrap(base: Context, scale: Float): Context {
        if (scale <= 0f) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = scale
        return base.createConfigurationContext(config)
    }
}
