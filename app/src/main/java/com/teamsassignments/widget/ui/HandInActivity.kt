package com.teamsassignments.widget.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.teamsassignments.widget.automation.TeamsAutomationService
import com.teamsassignments.widget.automation.TeamsSelectors
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.DueBucket
import com.teamsassignments.widget.data.DueDateParser
import com.teamsassignments.widget.data.DueFormatter
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * A widget row's Hand in button. Handing in reaches the teacher and can only be undone in Teams,
 * so this asks first. On Hand in, the accessibility service opens the assignment and presses
 * Teams' own Hand in button (see `automation/HandInStateMachine`).
 */
class HandInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_KEY)
        val assignment = AssignmentStore.get(this).state.value.assignments.firstOrNull { it.key == key }
        val refusal = when {
            assignment == null -> "That assignment is no longer on the list"
            // Without its Teams id it would be found by title, and a same-titled one could stand in.
            !TeamsSelectors.CARD_ID.matches(assignment.key) -> "Sync with ↻ first, so Teams can find this assignment"
            TeamsAutomationService.instance?.isBusy == true -> "Still syncing. Try again in a moment."
            else -> null
        }
        if (assignment == null || refusal != null) {
            refusal?.let(::toast)
            finish()
            return
        }

        val clock = Clock.systemDefaultZone()
        val parser = DueDateParser(clock)
        val formatter = DueFormatter(clock, resources.configuration.locales[0], DateFormat.is24HourFormat(this))
        val bucket = parser.bucket(assignment.dueAt?.let(Instant::ofEpochMilli))
        val due = formatter.rowDue(assignment, bucket).let { time ->
            when (bucket) {
                DueBucket.NoDueDate -> time
                DueBucket.Overdue -> "Overdue · $time"
                else -> "${formatter.sectionTitle(bucket)} $time"
            }
        }
        setContent {
            AppTheme {
                HandInDialog(
                    assignment = assignment,
                    due = due,
                    late = bucket == DueBucket.Overdue,
                    onConfirm = { handIn(assignment) },
                    onDismiss = ::finish,
                )
            }
        }
    }

    private fun handIn(assignment: Assignment) {
        lifecycleScope.launch {
            val service = TeamsAutomationService.awaitInstance(this@HandInActivity)
            when {
                // NEW_TASK: this trampoline lives in its own hidden task, and setup belongs in the app's.
                service == null -> {
                    toast("Turn on Teams Assignments sync to hand in from the widget")
                    startActivity(
                        Intent(this@HandInActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                !service.handIn(assignment.key) -> toast("Still syncing. Try again in a moment.")
            }
            finish()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_KEY = "assignment_key"

        /** The data URI makes each row's intent distinct, so their PendingIntents don't collide. */
        fun intent(context: Context, key: String): Intent =
            Intent(context, HandInActivity::class.java)
                .setData("teamsassignments://hand-in/${Uri.encode(key)}".toUri())
                .putExtra(EXTRA_KEY, key)
    }
}

@Composable
private fun HandInDialog(assignment: Assignment, due: String, late: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val action = if (late) "Hand in late" else "Hand in"
    // One tap is enough: a second would only find the service busy.
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(assignment.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(due, assignment.className).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Teams will open and hand it in, with whatever work is attached to it there now.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    confirmed = true
                    onConfirm()
                },
                enabled = !confirmed,
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
