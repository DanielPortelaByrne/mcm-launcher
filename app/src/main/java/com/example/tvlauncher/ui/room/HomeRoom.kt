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
    private val projectSource = AssetProjectSource(activity)
    private val recipeSource = AssetMesaRecipeSource(activity)
    private val planner = TonightPlanner(AssetFilmSource(activity), projectSource, recipeSource)

    private val recipes = recipeSource.recipes()
    private val deck = RecipeDeck(recipes)
    private val tonight = TonightCard(activity, { film ->
        sheet.show("Watch tonight", film.title, film.year?.toString(), listOfNotNull(film.note, "Eva's Letterboxd card above has the live watchlist, and where each film is showing."))
    }, ::openProject, ::openRecipe)
    private val recipeCards = RecipeCardStack(activity, deck)
    private val nowSpinning = NowSpinning(activity, sheet)
    private var shownDay = -1

    init {
        val sections = activity.findViewById<LinearLayout>(R.id.discoverSections)
        val projects = projectSource.projects()

        sections.addView(heading("On the sideboard", "What you're making"), 0)
        val shelf = ProjectShelf(activity, projects, ::openProject).view
        SectionTheme.tag(shelf, SectionTheme.Mood.MAKING)
        sections.addView(shelf, 1, LinearLayout.LayoutParams(-1, -2))

        sections.addView(heading("This evening", "A few gentle ideas"), 2)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; gravity = Gravity.TOP }
        // 520 + 24 + 320 = the 864dp between the page margins.
        row.addView(tonight.view, LinearLayout.LayoutParams(dp(520), -2))
        row.addView(recipeCards.view, LinearLayout.LayoutParams(dp(320), dp(220)).apply { marginStart = dp(24) })
        SectionTheme.tag(row, SectionTheme.Mood.EVENING)
        sections.addView(row, 3, LinearLayout.LayoutParams(-1, -2))
        linkTonightAndRecipes()

        sections.addView(heading("Now spinning", "Eva and Daniel, on the turntables"), 4)
        SectionTheme.tag(nowSpinning.row, SectionTheme.Mood.SPINNING)
        sections.addView(nowSpinning.row, 5, LinearLayout.LayoutParams(-1, -2))
        refresh()
    }

    /** Start / stop the live parts (turntable polling). Tie these to the activity's resume / pause. */
    fun start() = nowSpinning.start()
    fun stop() = nowSpinning.stop()
    fun close() = nowSpinning.close()

    /** Right from the Tonight lines lands on the recipe cards and Left returns; neither side leaks to another row. */
    private fun linkTonightAndRecipes() {
        val stack = recipeCards.view
        stack.id = View.generateViewId()
        val rows = tonight.rows
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

    private fun openProject(project: Project) = sheet.show(
        "Project", project.title, project.status,
        listOfNotNull("Next: ${project.nextAction}".takeIf { project.nextAction.isNotBlank() }, project.notes?.takeIf { it.isNotBlank() })
    )

    private fun openRecipe(recipe: Recipe) = sheet.show(
        "Recipe · MESA", recipe.title, recipe.cuisine,
        listOfNotNull(recipe.descriptor, "Full recipes will open here once the MESA catalogue is connected.")
    )

    // The shared heading: same page margin, section gap and heading gap as every other section.
    private fun heading(title: String, subtitle: String): View = com.example.tvlauncher.design.SectionHeader.build(activity, title, subtitle)

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
