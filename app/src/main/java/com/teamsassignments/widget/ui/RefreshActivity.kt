package com.teamsassignments.widget.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.teamsassignments.widget.automation.TeamsAutomationService
import kotlinx.coroutines.launch

/**
 * The widget's ↻ button. An invisible trampoline: widget clicks are an allowed source for starting
 * activities, and this one hands the work to the accessibility service and finishes at once.
 * Without the service it opens the setup screen instead.
 */
class RefreshActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val full = intent.getBooleanExtra(EXTRA_FULL, false)
        lifecycleScope.launch {
            val service = TeamsAutomationService.awaitInstance(this@RefreshActivity)
            when {
                // NEW_TASK: this trampoline lives in its own hidden task, and setup belongs in the app's.
                service == null -> startActivity(
                    Intent(this@RefreshActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                !service.startSync(full) ->
                    Toast.makeText(this@RefreshActivity, "Already syncing", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_FULL = "full"

        fun intent(context: Context, full: Boolean = false): Intent =
            Intent(context, RefreshActivity::class.java).putExtra(EXTRA_FULL, full)
    }
}
