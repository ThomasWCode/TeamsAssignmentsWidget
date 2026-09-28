package com.teamsassignments.widget.ui

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teamsassignments.widget.R
import com.teamsassignments.widget.automation.ScreenDumper
import com.teamsassignments.widget.automation.TeamsAutomationService
import com.teamsassignments.widget.automation.TeamsLauncher
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.ClassColors
import com.teamsassignments.widget.data.DueBucket
import com.teamsassignments.widget.data.DueDateParser
import com.teamsassignments.widget.data.DueFormatter
import com.teamsassignments.widget.data.LogEntry
import com.teamsassignments.widget.data.SyncLog
import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.data.WidgetState
import com.teamsassignments.widget.data.groupIntoSections
import com.teamsassignments.widget.widget.AssignmentsWidgetReceiver
import kotlinx.coroutines.delay
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Setup checklist, manual sync, the synced list, and troubleshooting tools. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { SetupScreen() } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** What the checklist shows; re-read whenever the screen resumes, since it changes in Settings. */
private data class SetupChecks(
    val teamsInstalled: Boolean,
    val serviceEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    val widgetPlaced: Boolean,
) {
    companion object {
        fun read(context: Context) = SetupChecks(
            teamsInstalled = TeamsLauncher.isInstalled(context),
            serviceEnabled = TeamsAutomationService.isEnabled(context),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            widgetPlaced = AppWidgetManager.getInstance(context).getAppWidgetIds(widgetProvider(context)).isNotEmpty(),
        )
    }
}

private fun widgetProvider(context: Context) = ComponentName(context, AssignmentsWidgetReceiver::class.java)

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val state by remember { AssignmentStore.get(context).state }.collectAsStateWithLifecycle()
    val logEntries by remember { SyncLog.get(context).entries }.collectAsStateWithLifecycle()
    val connected by TeamsAutomationService.connected.collectAsStateWithLifecycle()
    var checks by remember { mutableStateOf(SetupChecks.read(context)) }
    var latestDump by remember { mutableStateOf(ScreenDumper.dumps(context).firstOrNull()) }
    LifecycleResumeEffect(Unit) {
        checks = SetupChecks.read(context)
        latestDump = ScreenDumper.dumps(context).firstOrNull()
        onPauseOrDispose { }
    }
    val running = state.status is SyncStatus.Running

    Scaffold { insets ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 16.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text("Teams Assignments", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "A home-screen widget for the Teams work you haven't handed in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SetupCard(checks, connected) }
            item { SyncCard(state, connected, running) }
            if (state.assignments.isNotEmpty()) item { AssignmentsCard(state) }
            item { TroubleshootingCard(connected, running, latestDump, logEntries) }
        }
    }
}

@Composable
private fun SetupCard(checks: SetupChecks, connected: Boolean) {
    val context = LocalContext.current
    SectionCard("Setup") {
        Step(
            done = checks.teamsInstalled,
            title = "Microsoft Teams installed",
            body = "The widget reads your assignments from the Teams app.",
        ) {
            OutlinedButton(
                onClick = {
                    if (!TeamsLauncher.openStorePage(context)) {
                        Toast.makeText(context, "Install Microsoft Teams from your app store", Toast.LENGTH_LONG).show()
                    }
                },
            ) { Text("Get Teams") }
        }
        Step(
            done = checks.serviceEnabled && connected,
            title = if (checks.serviceEnabled && !connected) "Sync service starting…" else "Turn on Teams Assignments sync",
            body = "In Accessibility settings, open Installed apps → Teams Assignments sync and switch it on. " +
                "It only reads Teams, only when you sync, and never hands anything in.\n\n" +
                "Switch greyed out? Open App info → ⋮ → Allow restricted settings, then try again.",
        ) {
            Button(onClick = { openServiceSettings(context) }) { Text("Accessibility settings") }
            TextButton(onClick = { context.startActivity(appInfoIntent(context)) }) { Text("App info") }
        }
        Step(
            done = checks.batteryUnrestricted,
            title = "Keep it running in the background",
            body = "Samsung can put the app to sleep, which switches the sync off. Allow unrestricted battery use, " +
                "and add Teams Assignments under Settings → Battery → Background usage limits → Never sleeping apps.",
        ) {
            OutlinedButton(onClick = { requestUnrestrictedBattery(context) }) { Text("Allow") }
        }
        Step(
            done = checks.widgetPlaced,
            title = "Add the widget to your home screen",
            body = "Tap Add widget, or long-press the home screen and find Teams Assignments under Widgets.",
        ) {
            Button(onClick = { requestPinWidget(context) }) { Text("Add widget") }
        }
    }
}

@Composable
private fun SyncCard(state: WidgetState, connected: Boolean, running: Boolean) {
    val context = LocalContext.current
    SectionCard("Sync") {
        Text(statusLine(context, state), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startSync(context, full = false) }, enabled = connected && !running) { Text("Sync now") }
            OutlinedButton(onClick = { startSync(context, full = true) }, enabled = connected && !running) {
                Text("Full resync")
            }
        }
        Text(
            "Syncing opens Teams for a few seconds and reads any new or changed assignments. " +
                "Full resync rereads every assignment's instructions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssignmentsCard(state: WidgetState) {
    val context = LocalContext.current
    val clock = remember { Clock.systemDefaultZone() }
    val parser = remember { DueDateParser(clock) }
    val formatter = remember { DueFormatter(clock, Locale.getDefault(), DateFormat.is24HourFormat(context)) }
    // Sections depend on the time as well as the list ("Tomorrow" becomes "Today", deadlines
    // pass), so regroup every minute and whenever the screen comes back.
    var minute by remember { mutableLongStateOf(currentMinute()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            minute = currentMinute()
        }
    }
    LifecycleResumeEffect(Unit) {
        minute = currentMinute()
        onPauseOrDispose { }
    }
    val sections = remember(state.assignments, minute) { groupIntoSections(state.assignments, parser) }

    SectionCard("Assignments (${state.assignments.size})") {
        sections.forEach { section ->
            Text(
                formatter.sectionTitle(section.bucket).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (section.bucket == DueBucket.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            section.assignments.forEach { assignment ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { context.startActivity(OpenAssignmentActivity.intent(context, assignment.key)) },
                ) {
                    Box(
                        Modifier
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(Color(ClassColors.colorFor(assignment.className, state.classColors))),
                    )
                    Column(Modifier.padding(12.dp)) {
                        Text(assignment.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatter.rowDue(assignment, section.bucket)} · ${assignment.className}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (assignment.description.isNotBlank()) {
                            Text(
                                assignment.description.replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TroubleshootingCard(connected: Boolean, running: Boolean, latestDump: File?, log: List<LogEntry>) {
    val context = LocalContext.current
    SectionCard("Troubleshooting") {
        Text(
            "If syncing breaks after a Teams update, capture the screen it gets stuck on and share the file. " +
                "A sync that fails by itself (rather than being cancelled) also saves a capture.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    TeamsAutomationService.instance?.armDumper()
                    TeamsLauncher.launchAssignments(context)
                    Toast.makeText(context, "Go to the screen, then tap Capture", Toast.LENGTH_LONG).show()
                },
                enabled = connected && !running,
            ) { Text("Dump Teams screen") }
            if (latestDump != null) {
                TextButton(onClick = { ScreenDumper.share(context, latestDump) }) { Text("Share latest dump") }
            }
        }
        if (log.isNotEmpty()) {
            Text("Recent steps", style = MaterialTheme.typography.titleSmall)
            val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
            SelectionContainer {
                Column {
                    log.asReversed().forEach { entry ->
                        Text(
                            "${time.format(Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault()))}  ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A checklist row. The explanation and buttons only show until the step is done. */
@Composable
private fun Step(done: Boolean, title: String, body: String, actions: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline),
            contentDescription = if (done) "Done" else "To do",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!done) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
        }
    }
}

private fun currentMinute() = System.currentTimeMillis() / 60_000

private fun statusLine(context: Context, state: WidgetState): String {
    val lastGood = state.lastSuccessAt?.let { "last synced ${formatWhen(context, it)}" }
    return when (val status = state.status) {
        is SyncStatus.Running -> if (status.total > 0) "Syncing ${status.done}/${status.total}…" else "Syncing…"
        is SyncStatus.Failed -> "Last sync failed: ${status.reason}" + (lastGood?.let { " ($it)" } ?: "")
        is SyncStatus.Stopped -> status.summary + (lastGood?.let { " ($it)" } ?: "")
        SyncStatus.Idle -> lastGood?.let { "${it.replaceFirstChar(Char::uppercase)} · ${state.assignments.size} to do" }
            ?: "Not synced yet"
    }
}

private fun formatWhen(context: Context, epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")
    return if (time.toLocalDate() == LocalDate.now()) clock.format(time)
    else DateTimeFormatter.ofPattern("EEE d MMM").format(time) + ", " + clock.format(time)
}

private fun startSync(context: Context, full: Boolean) {
    val service = TeamsAutomationService.instance ?: return
    // Come back here afterwards rather than to the home screen.
    service.startSync(full, returnTo = Intent(context, MainActivity::class.java))
}

/**
 * Opens the service's own switch where Android allows it (11+; the action is hidden from the SDK
 * but Settings accepts it for an app's own service), else the Accessibility menu.
 */
private fun openServiceSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            ComponentName(context, TeamsAutomationService::class.java).flattenToString(),
        )
        if (runCatching { context.startActivity(details) }.isSuccess) return
    }
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun appInfoIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

/** Asks the launcher to place the widget; the launcher shows its own confirmation. */
private fun requestPinWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (!manager.isRequestPinAppWidgetSupported || !manager.requestPinAppWidget(widgetProvider(context), null, null)) {
        Toast.makeText(context, "Long-press the home screen and add it from Widgets", Toast.LENGTH_LONG).show()
    }
}

@SuppressLint("BatteryLife") // Sideloaded app; the user confirms in the system dialog.
private fun requestUnrestrictedBattery(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(request) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
