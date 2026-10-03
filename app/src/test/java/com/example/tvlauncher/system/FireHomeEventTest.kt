package com.example.tvlauncher.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FireHomeEventTest {
    private val physicalHome = "1791050406.134 619 664 I ActivityManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10200000 cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext (has extras)} from uid 1000 on display 0"

    @Test fun acceptsObservedFireOsHomeEvent() { assertTrue(FireHomeEvent.isHome(physicalHome)) }

    @Test fun leavesOtherUsersAndActivitiesAlone() {
        assertFalse(FireHomeEvent.isHome(physicalHome.replace("u0 {", "u10 {")))
        assertFalse(FireHomeEvent.isHome(physicalHome.replace("HomeActivity_vNext", "HomeActivity_vNextSettings")))
        assertFalse(FireHomeEvent.isHome(physicalHome.replace("com.amazon.tv.launcher/.ui.HomeActivity_vNext", "com.example.tvlauncher/.MainActivity")))
    }

    @Test fun doesNotReactToSettingsOrDiagnostics() {
        assertFalse(FireHomeEvent.isHome(physicalHome.replace("android.intent.category.HOME", "android.intent.category.LAUNCHER")))
        assertFalse(FireHomeEvent.isHome(physicalHome.replace("START u0 {", "Displayed u0 {")))
    }
}
