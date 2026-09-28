package com.teamsassignments.widget.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** Starting Teams. Needs the `<queries>` entry for Teams in the manifest (package visibility). */
object TeamsLauncher {

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TeamsSelectors.TEAMS_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** The Assignments deep link, handled by Teams itself (Phase 0: opens the list, even cold). */
    fun assignmentsIntent(): Intent =
        Intent(Intent.ACTION_VIEW, TeamsSelectors.ASSIGNMENTS_DEEP_LINK.toUri())
            .setPackage(TeamsSelectors.TEAMS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun launchAssignments(context: Context): Boolean = try {
        context.startActivity(assignmentsIntent())
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Opens Teams' store page: in a store app if there is one, else on the web. False if neither opens. */
    fun openStorePage(context: Context): Boolean {
        val id = TeamsSelectors.TEAMS_PACKAGE
        return listOf("market://details?id=$id", "https://play.google.com/store/apps/details?id=$id").any { page ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, page.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}
