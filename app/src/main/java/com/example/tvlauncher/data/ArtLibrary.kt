package com.example.tvlauncher.data

import android.content.Context
import android.widget.ImageView
import com.example.tvlauncher.R

data class Painting(val title: String, val resource: Int)
class ArtLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("art", Context.MODE_PRIVATE)
    val paintings = listOf(Painting("Mediterranean lemons", R.drawable.bg_painting)) +
        listOf("Sunroom studies", "Breakfast in colour", "Blue-hour coast", "Lamplight", "Olive hills", "Sunday records", "Ceramic forms", "Orange courtyard", "Pears at the table", "Moon garden", "Harbour houses", "Poppies & velvet").mapIndexedNotNull { i, title ->
            val id = context.resources.getIdentifier("mcm_painting_${(i+1).toString().padStart(2, '0')}", "drawable", context.packageName)
            if (id == 0) null else Painting(title, id)
        }
    var index: Int = prefs.getInt("selected", 0).coerceIn(0, paintings.lastIndex)
        private set
    var interval: Long
        get() = prefs.getLong("interval", 60000)
        set(value) { prefs.edit().putLong("interval", value).apply() }
    fun select(value: Int) { index = (value + paintings.size) % paintings.size; prefs.edit().putInt("selected", index).apply() }
    fun show(view: ImageView) { view.setImageResource(paintings[index].resource) }
}
