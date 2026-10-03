package com.example.tvlauncher.ui.room

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.data.home.AssetFilmSource
import com.example.tvlauncher.data.home.AssetMesaRecipeSource
import com.example.tvlauncher.data.home.AssetProjectSource
import com.example.tvlauncher.data.home.Project
import com.example.tvlauncher.data.home.Recipe
import com.example.tvlauncher.data.home.RecipeDeck
import com.example.tvlauncher.data.home.TonightPlanner
import com.example.tvlauncher.design.SectionTheme

/**
 * The personal part of the home screen, laid into the page as a small "room":
 * the project shelf (a sideboard with things you're making), then a framed "Tonight?" print
 * beside the MESA recipe cards. Data comes from the sources below; swap them to plug in real ones.
 */
class HomeRoom(private val activity: Activity, private val sheet: InfoSheet) {

    // Plug points: replace these with real sources (MESA catalogue, project tracker, watchlist).
    private val projectSource = object : com.example.tvlauncher.data.home.ProjectSource {
        override fun projects(): List<Project> = emptyList()
    }
    private val recipeSource = AssetMesaRecipeSource(activity)
    private val planner = TonightPlanner(AssetFilmSource(activity), projectSource, recipeSource)

    private val recipes = recipeSource.recipes()
    private val deck = RecipeDeck(recipes)
    private val tonight = TonightCard(activity, { film ->
        sheet.show("Watch tonight", film.title, film.year?.toString(), listOfNotNull(film.note, "Eva's Letterboxd card above has the live watchlist, and where each film is showing."))
    }, { openProject(it) }, ::openRecipe)
    private val recipeCards = RecipeCardStack(activity, deck)
    private var shownDay = -1

    init {
        val sections = activity.findViewById<LinearLayout>(R.id.discoverSections)
        sections.addView(heading("This evening", "A few gentle ideas"), 0)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; gravity = Gravity.TOP }
        // 520 + 24 + 320 = the 864dp between the page margins.
        row.addView(tonight.view, LinearLayout.LayoutParams(dp(520), -2))
        row.addView(recipeCards.view, LinearLayout.LayoutParams(dp(320), dp(220)).apply { marginStart = dp(24) })
        SectionTheme.tag(row, SectionTheme.Mood.EVENING)
        sections.addView(row, 1, LinearLayout.LayoutParams(-1, -2))
        tonight.rows[1].visibility = View.GONE
        linkTonightAndRecipes()
        refresh()
    }

    /** Start / stop the live parts (turntable polling). Tie these to the activity's resume / pause. */
    fun start() = Unit
    fun stop() = Unit
    fun close() = Unit

    /** Right from the Tonight lines lands on the recipe cards and Left returns; neither side leaks to another row. */
    private fun linkTonightAndRecipes() {
        val stack = recipeCards.view
        stack.id = View.generateViewId()
        val rows = tonight.rows.filter { it.visibility != View.GONE }
        rows.forEach { line ->
            line.id = View.generateViewId()
            line.nextFocusRightId = stack.id
            line.setOnKeyListener { _, keyCode, event ->
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && event.action == android.view.KeyEvent.ACTION_DOWN
            }
        }
        stack.nextFocusLeftId = rows[rows.size / 2].id
        stack.setOnKeyListener { _, keyCode, event ->
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && event.action == android.view.KeyEvent.ACTION_DOWN
        }
    }

    /** Re-plans "Tonight?" when the day has changed. Call from onResume. */
    fun refresh() {
        val plan = planner.plan()
        if (plan.dayKey == shownDay) return
        shownDay = plan.dayKey
        tonight.update(plan)
        recipeCards.showRecipe(plan.recipe?.id)
    }

    /** The project sheet shows the print itself, large, lifted off the sideboard when there is one to lift. */
    private fun openProject(project: Project, from: View? = null) = sheet.show(
        "Project", project.title, project.status,
        listOfNotNull("Next: ${project.nextAction}".takeIf { project.nextAction.isNotBlank() }, project.notes?.takeIf { it.isNotBlank() }),
        image = ProjectArtView.render(activity, project.art, PRINT_DP), imageSizeDp = PRINT_DP to PRINT_DP, from = from
    )

    private fun openRecipe(recipe: Recipe) = sheet.show(
        "Recipe · MESA", recipe.title, recipe.cuisine,
        listOfNotNull(recipe.descriptor, "Full recipes will open here once the MESA catalogue is connected.")
    )

    // The shared heading: same page margin, section gap and heading gap as every other section.
    private fun heading(title: String, subtitle: String): View = com.example.tvlauncher.design.SectionHeader.build(activity, title, subtitle)

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private companion object { const val PRINT_DP = 220 }
}
