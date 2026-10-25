package com.example.tvlauncher.data.home

/** WIP: unused home modules are parked until their data, interaction and performance can be revisited. */
object HomeFeatures {
    const val SIDEBOARD = false
    const val LISTENING = false
    const val PLANNER = false
    const val MESA_RECIPES = false

    val roomEnabled: Boolean get() = SIDEBOARD || LISTENING || PLANNER || MESA_RECIPES
}
