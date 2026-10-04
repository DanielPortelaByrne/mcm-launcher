package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.example.tvlauncher.system.VpnPilot

/**
 * +SBT, Brazil's SBT live and on demand. It only plays from Brazil, so opening it from Amélia's guide first
 * makes sure Amélia's PrivadoVPN is the VPN up, on Brazil ([VpnPilot.BRASIL]). It carries only her
 * Brazilian apps (split tunnelling), so it can stay connected: nothing else in the house goes through Brazil.
 */
object BrazilTv {
    const val APP = "br.com.sbt.mais"

    fun installed(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(APP) != null ||
        context.packageManager.getLeanbackLaunchIntentForPackage(APP) != null

    fun open(activity: Activity) {
        val launch = activity.packageManager.getLeanbackLaunchIntentForPackage(APP) ?: activity.packageManager.getLaunchIntentForPackage(APP) ?: return
        com.example.tvlauncher.data.UsageLog.opened(com.example.tvlauncher.data.UsageLog.whereOf(activity.currentFocus), "+SBT (TV do Brasil)", APP)
        val start = { activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val route = VpnPilot.routeFor(activity, APP)
        if (route == null) start() else VpnPilot.connectThen(activity, route, start)
    }
}
