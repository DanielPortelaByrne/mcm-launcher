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
import com.example.tvlauncher.data.home.HomeFeatures
import com.example.tvlauncher.design.SectionTheme

/**
 * The personal part of the home screen, laid into the page as a small "room":
 * the project shelf (a sideboard with things you're making), then a framed "Tonight?" print
 * beside the MESA recipe cards. Data comes from the sources below; swap them to plug in real ones.
 */
class HomeRoom(private val activity: Activity, private val sheet: InfoSheet) {

    // Plug points: replace these with real sources (MESA catalogue, project tracker, watchlist).
    private val projectSource by lazy { AssetProjectSource(activity) }
    private val recipeSource by lazy { AssetMesaRecipeSource(activity) }
    private val planner by lazy { TonightPlanner(AssetFilmSource(activity), projectSource, recipeSource) }

    private val recipes by lazy { recipeSource.recipes() }
    private val deck by lazy { RecipeDeck(recipes) }
    private val tonight by lazy { TonightCard(activity, { film ->
        sheet.show("Watch tonight", film.title, film.year?.toString(), listOfNotNull(film.note, "Eva's Letterboxd card above has the live watchlist, and where each film is showing."))
    }, { openProject(it) }, ::openRecipe) }
    private val recipeCards by lazy { RecipeCardStack(activity, deck) }
    private val nowSpinning by lazy { NowSpinning(activity, sheet) }
    private var shownDay = -1

    init {
        val sections = activity.findViewById<LinearLayout>(R.id.discoverSections)
        var at = 0
        if (HomeFeatures.SIDEBOARD) {
            sections.addView(heading("On the sideboard", "What you're making"), at++)
            val shelf = ProjectShelf(activity, projectSource.projects()) { project, from -> openProject(project, from) }.view
            SectionTheme.tag(shelf, SectionTheme.Mood.MAKING)
            sections.addView(shelf, at++, LinearLayout.LayoutParams(-1, -2))
        }

        if (HomeFeatures.PLANNER || HomeFeatures.MESA_RECIPES) {
        sections.addView(heading("This evening", "A few gentle ideas"), at++)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; gravity = Gravity.TOP }
        // 520 + 24 + 320 = the 864dp between the page margins.
        if (HomeFeatures.PLANNER) row.addView(tonight.view, LinearLayout.LayoutParams(dp(520), -2))
        if (HomeFeatures.MESA_RECIPES) row.addView(recipeCards.view, LinearLayout.LayoutParams(dp(320), dp(220)).apply { if (HomeFeatures.PLANNER) marginStart = dp(24) })
        SectionTheme.tag(row, SectionTheme.Mood.EVENING)
        sections.addView(row, at++, LinearLayout.LayoutParams(-1, -2))
        if (HomeFeatures.PLANNER && HomeFeatures.MESA_RECIPES) linkTonightAndRecipes()
        }

        if (HomeFeatures.LISTENING) {
        sections.addView(heading("Now spinning", "Eva and Daniel, on the turntables"), at++)
        SectionTheme.tag(nowSpinning.row, SectionTheme.Mood.SPINNING)
        sections.addView(nowSpinning.row, at, LinearLayout.LayoutParams(-1, -2))
        }
        refresh()
    }

    /** Start / stop the live parts (turntable polling). Tie these to the activity's resume / pause. */
    fun start() { if (HomeFeatures.LISTENING) nowSpinning.start() }
    fun stop() { if (HomeFeatures.LISTENING) nowSpinning.stop() }
    fun close() { if (HomeFeatures.LISTENING) nowSpinning.close() }

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
        if (!HomeFeatures.PLANNER && !HomeFeatures.MESA_RECIPES) return
        val plan = planner.plan()
        if (plan.dayKey == shownDay) return
        shownDay = plan.dayKey
        if (HomeFeatures.PLANNER) tonight.update(plan)
        if (HomeFeatures.MESA_RECIPES) recipeCards.showRecipe(plan.recipe?.id)
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
