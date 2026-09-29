package com.example.tvlauncher.data

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    /** Real installed-app icon (always present). */
    val icon: Drawable,
    /**
     * Leanback banner (400x225 landscape artwork), when the app declares
     * one. Preferred over [icon] for the landscape tile treatment because
     * it's usually the app's actual, recognisable brand artwork rather
     * than a square icon stretched into a landscape slot.
     */
    val banner: Drawable?,
    val launchIntent: Intent
) {
    /** What the shelf/grid should actually draw for this app. */
    val tileArtwork: Drawable get() = banner ?: icon
}
