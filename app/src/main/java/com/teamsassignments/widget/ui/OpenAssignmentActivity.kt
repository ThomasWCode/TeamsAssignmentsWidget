package com.teamsassignments.widget.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.teamsassignments.widget.automation.TeamsAutomationService
import com.teamsassignments.widget.automation.TeamsLauncher
import kotlinx.coroutines.launch

/**
 * A widget row tap. The service opens the assignment itself; without the service, this falls
 * back to opening the Assignments list so the tap still goes somewhere useful.
 */
class OpenAssignmentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY)
        lifecycleScope.launch {
            val service = TeamsAutomationService.awaitInstance(this@OpenAssignmentActivity)
            when {
                // A sync (or another tap) is driving Teams: leave it alone rather than
                // yanking Teams back to the list underneath it.
                service?.isBusy == true -> toast("Still syncing. Try again in a moment.")
                key != null && service?.openAssignment(key) == true -> Unit
                else -> {
                    TeamsLauncher.launchAssignments(this@OpenAssignmentActivity)
                    if (service == null) toast("Turn on Teams Assignments sync to jump straight to an assignment")
                }
            }
            finish()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_KEY = "assignment_key"

        /** The data URI makes each row's intent distinct, so their PendingIntents don't collide. */
        fun intent(context: Context, key: String): Intent =
            Intent(context, OpenAssignmentActivity::class.java)
                .setData("teamsassignments://assignment/${Uri.encode(key)}".toUri())
                .putExtra(EXTRA_KEY, key)
    }
}
