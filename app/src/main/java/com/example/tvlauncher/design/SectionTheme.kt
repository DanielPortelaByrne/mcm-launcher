package com.example.tvlauncher.design

import android.graphics.Color
import android.view.View
import com.example.tvlauncher.R

/**
 * The room's light shifts a little with where you are on the page: the painting is tinted towards the
 * focused section's mood (see HomeBackdrop.setMood). It is meant to be felt more than seen, so every tint
 * is low alpha and text contrast is untouched (text sits on its own scrims).
 */
object SectionTheme {
    enum class Mood(val tint: Int) {
        /** Apps, header, greeting: the painting as it is. */
        NEUTRAL(Color.argb(0, 0, 0, 0)),
        /** Films and Continue watching: the lights dimmed a touch, slightly cooler. */
        FILM(Color.argb(58, 10, 16, 30)),
        /** This evening (Tonight, recipes): warm lamp light. */
        EVENING(Color.argb(34, 176, 96, 30)),
        /** Now spinning: a little teal in the shadows. */
        SPINNING(Color.argb(38, 18, 78, 74)),
        /** The sideboard of projects: olive and walnut. */
        MAKING(Color.argb(40, 60, 62, 30)),
        /** The painting shelf and settings: a faint darkening so the thumbnails read. */
        GALLERY(Color.argb(30, 20, 14, 8))
    }

    const val TRANSITION_MS = 560L

    /** Marks [section] (and so everything inside it) as belonging to [mood]. */
    fun tag(section: View, mood: Mood) = section.setTag(R.id.section_mood, mood)

    /** The mood of the nearest tagged ancestor of [view], or null if nothing on the way up is tagged. */
    fun moodOf(view: View?): Mood? {
        var v: Any? = view
        while (v is View) {
            (v.getTag(R.id.section_mood) as? Mood)?.let { return it }
            v = v.parent
        }
        return null
    }
}
