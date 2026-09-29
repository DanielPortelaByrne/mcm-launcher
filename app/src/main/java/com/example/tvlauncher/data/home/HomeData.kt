package com.example.tvlauncher.data.home

import android.content.Context
import org.json.JSONArray
import java.util.Calendar

/**
 * Data behind the personal parts of the home screen (project shelf, tonight card, recipe cards).
 * Everything is read through the small source interfaces below, so a real backend (the MESA
 * catalogue, a projects tracker, Eva's watchlist...) can replace the local JSON files in
 * `app/src/main/assets/home/` without touching any UI code.
 */

/** How a project is drawn on the shelf (see ui/room/ProjectArtView). */
enum class ArtType {
    FIGURINE, TAYTO, MIRROR, SOFA, GENERIC;

    companion object {
        fun parse(value: String?): ArtType = values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: GENERIC
    }
}

data class Project(
    val id: String,
    val title: String,
    /** Short "what's next", shown under the focused object. */
    val nextAction: String,
    val art: ArtType = ArtType.GENERIC,
    val status: String? = null,
    val notes: String? = null
)

/** A recipe card. Field names are deliberately generic so a real MESA catalogue entry maps onto them 1:1. */
data class Recipe(
    val id: String,
    val title: String,
    val cuisine: String? = null,
    val descriptor: String? = null,
    /** Illustration hint for the card: sun, arch, leaf, bands or circles. */
    val theme: String = "sun"
)

data class TonightFilm(val id: String, val title: String, val year: Int? = null, val note: String? = null)

interface ProjectSource { fun projects(): List<Project> }

/** The plug-in point for the real MESA catalogue. */
interface RecipeSource { fun recipes(): List<Recipe> }

interface FilmSource { fun films(): List<TonightFilm> }

/** Local JSON in assets/home/, used until real sources exist. */
class AssetProjectSource(private val context: Context) : ProjectSource {
    override fun projects(): List<Project> = JsonAssets.read(context, "home/projects.json") { o ->
        Project(
            id = o.getString("id"),
            title = o.getString("title"),
            nextAction = o.optString("nextAction"),
            art = ArtType.parse(o.optString("art")),
            status = o.optString("status").ifBlank { null },
            notes = o.optString("notes").ifBlank { null }
        )
    }
}

class AssetMesaRecipeSource(private val context: Context) : RecipeSource {
    override fun recipes(): List<Recipe> = JsonAssets.read(context, "home/recipes.json") { o ->
        Recipe(
            id = o.getString("id"),
            title = o.getString("title"),
            cuisine = o.optString("cuisine").ifBlank { null },
            descriptor = o.optString("descriptor").ifBlank { null },
            theme = o.optString("theme").ifBlank { "sun" }
        )
    }
}

class AssetFilmSource(private val context: Context) : FilmSource {
    override fun films(): List<TonightFilm> = JsonAssets.read(context, "home/films.json") { o ->
        TonightFilm(o.getString("id"), o.getString("title"), o.optInt("year", 0).takeIf { it > 0 }, o.optString("note").ifBlank { null })
    }
}

private object JsonAssets {
    fun <T> read(context: Context, path: String, map: (org.json.JSONObject) -> T): List<T> = try {
        val array = JSONArray(context.assets.open(path).bufferedReader().use { it.readText() })
        (0 until array.length()).map { map(array.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
}

/** Three gentle suggestions for the evening. Any of them can be null if its source is empty. */
data class TonightPlan(val film: TonightFilm?, val project: Project?, val recipe: Recipe?, val dayKey: Int)

/** Picks one film, project and recipe per day: stable all day, different tomorrow. */
class TonightPlanner(
    private val films: FilmSource,
    private val projects: ProjectSource,
    private val recipes: RecipeSource
) {
    fun plan(): TonightPlan {
        val day = dayKey()
        return TonightPlan(
            film = films.films().pick(day, 7),
            project = projects.projects().pick(day, 13),
            recipe = recipes.recipes().pick(day, 29),
            dayKey = day
        )
    }

    fun dayKey(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }

    private fun <T> List<T>.pick(day: Int, salt: Int): T? =
        if (isEmpty()) null else this[Math.floorMod(day * 31 + salt * 17, size)]
}

/** A shuffle bag over the recipes: every card is seen before any repeats, and never the same one twice in a row. */
class RecipeDeck(recipes: List<Recipe>) {
    private val all = recipes
    private var bag = ArrayDeque<Recipe>()
    var current: Recipe? = null
        private set

    fun show(id: String?) { current = all.firstOrNull { it.id == id } ?: all.firstOrNull() }

    fun shuffle(): Recipe? {
        if (all.isEmpty()) return null
        if (bag.isEmpty()) {
            val fresh = all.shuffled().toMutableList()
            // Never open a new bag with the card that is already showing.
            if (fresh.size > 1 && fresh.first().id == current?.id) fresh.add(fresh.removeAt(0))
            bag = ArrayDeque(fresh)
        }
        current = bag.removeFirst()
        return current
    }

    fun rotate(step: Int): Recipe? {
        if (all.isEmpty()) return null
        val i = all.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        current = all[Math.floorMod(i + step, all.size)]
        return current
    }
}
