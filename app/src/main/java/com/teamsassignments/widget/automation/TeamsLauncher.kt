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

    fun playStoreIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=${TeamsSelectors.TEAMS_PACKAGE}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
