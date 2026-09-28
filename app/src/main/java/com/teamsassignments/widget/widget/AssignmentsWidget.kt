package com.teamsassignments.widget.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.teamsassignments.widget.R
import com.teamsassignments.widget.automation.TeamsAutomationService
import com.teamsassignments.widget.data.Assignment
import com.teamsassignments.widget.data.AssignmentStore
import com.teamsassignments.widget.data.ClassColors
import com.teamsassignments.widget.data.DueBucket
import com.teamsassignments.widget.data.DueDateParser
import com.teamsassignments.widget.data.DueFormatter
import com.teamsassignments.widget.data.SyncStatus
import com.teamsassignments.widget.data.WidgetState
import com.teamsassignments.widget.data.groupIntoSections
import com.teamsassignments.widget.ui.MainActivity
import com.teamsassignments.widget.ui.OpenAssignmentActivity
import com.teamsassignments.widget.ui.RefreshActivity
import java.time.Clock

/**
 * The home-screen widget: the Teams assignments that haven't been handed in, grouped by due date,
 * with a ↻ pill that syncs and rows that open the assignment in Teams.
 *
 * It only draws what [AssignmentStore] holds. Syncing is always manual (the ↻ pill); the widget
 * just redraws itself at midnight and as deadlines pass so "Today" and "Overdue" stay true.
 */
class AssignmentsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = AssignmentStore.get(context)
        WidgetUpdater.scheduleRedraw(context, store.state.value)
        provideContent {
            val state by store.state.collectAsState()
            val connected by TeamsAutomationService.connected.collectAsState()
            // Connected covers the running service; the setting covers the moment after a
            // restart, before Android has bound it again.
            val serviceOn = remember(connected) { connected || TeamsAutomationService.isEnabled(context) }
            GlanceTheme { WidgetContent(state, serviceOn) }
        }
    }
}

private const val SECTION_ID_BASE = 1L shl 40 // above any Int hash code, so never clashes with a row id

@Composable
private fun WidgetContent(state: WidgetState, serviceOn: Boolean) {
    val background = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
    // Match the launcher's own widget corners where Android defines them (12+).
    val rounded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        background
    }
    Column(rounded.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
        Header(state)
        Spacer(GlanceModifier.height(8.dp))
        when {
            !serviceOn -> Message(
                title = "Tap to finish setup",
                body = "Turn on Teams Assignments sync so ↻ can read your assignments.",
                modifier = GlanceModifier.clickable(actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))),
            )
            state.assignments.isEmpty() && state.lastSuccessAt != null ->
                Message(title = "Nothing due 🎉", body = "Tap ↻ to sync again.")
            state.assignments.isEmpty() ->
                Message(title = "No assignments yet", body = "Tap ↻ to read them from Teams.")
            else -> AssignmentList(state)
        }
    }
}

@Composable
private fun Header(state: WidgetState) {
    val context = LocalContext.current
    val failed = state.status is SyncStatus.Failed
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                "Assignments",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                WidgetText.subtitle(state, Clock.systemDefaultZone(), DateFormat.is24HourFormat(context)),
                style = TextStyle(
                    color = if (failed) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
        RefreshPill(running = state.status is SyncStatus.Running)
    }
}

/** The tonal ↻ button. While a sync runs it shows a spinner and does nothing when tapped. */
@Composable
private fun RefreshPill(running: Boolean) {
    val context = LocalContext.current
    val pill = GlanceModifier
        .size(width = 56.dp, height = 40.dp)
        .cornerRadius(20.dp)
        .background(GlanceTheme.colors.primaryContainer)
    Box(
        modifier = if (running) pill else pill.clickable(actionStartActivity(RefreshActivity.intent(context))),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            CircularProgressIndicator(GlanceModifier.size(20.dp), color = GlanceTheme.colors.onPrimaryContainer)
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Sync with Teams",
                modifier = GlanceModifier.size(22.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
    }
}

@Composable
private fun AssignmentList(state: WidgetState) {
    val context = LocalContext.current
    val clock = Clock.systemDefaultZone()
    val sections = groupIntoSections(state.assignments, DueDateParser(clock))
    val formatter = DueFormatter(clock, context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))
    // Too short for previews: keep each row to its title and due line.
    val showDescriptions = LocalSize.current.height >= 220.dp

    LazyColumn(GlanceModifier.fillMaxSize()) {
        sections.forEachIndexed { index, section ->
            item(itemId = SECTION_ID_BASE + index) {
                SectionHeader(formatter.sectionTitle(section.bucket), overdue = section.bucket == DueBucket.Overdue)
            }
            items(section.assignments, itemId = { it.key.hashCode().toLong() }) { assignment ->
                AssignmentRow(
                    assignment = assignment,
                    due = formatter.rowDue(assignment, section.bucket),
                    stripe = Color(ClassColors.colorFor(assignment.className, state.classColors)),
                    showDescription = showDescriptions,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, overdue: Boolean) {
    Text(
        title.uppercase(),
        style = TextStyle(
            color = if (overdue) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
        modifier = GlanceModifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** A rounded card with the class-colour stripe; tapping it opens the assignment in Teams. */
@Composable
private fun AssignmentRow(assignment: Assignment, due: String, stripe: Color, showDescription: Boolean) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            GlanceModifier
                .fillMaxWidth()
                .cornerRadius(14.dp)
                .background(GlanceTheme.colors.surface)
                .clickable(actionStartActivity(OpenAssignmentActivity.intent(context, assignment.key))),
        ) {
            Box(GlanceModifier.width(5.dp).fillMaxHeight().background(ColorProvider(stripe))) {}
            Column(GlanceModifier.defaultWeight().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                Text(
                    assignment.title,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 2,
                )
                Text(
                    listOf(due, assignment.className).filter(String::isNotBlank).joinToString(" · "),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                    maxLines = 1,
                )
                if (showDescription && assignment.description.isNotBlank()) {
                    Text(
                        WidgetText.descriptionPreview(assignment.description),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String, modifier: GlanceModifier = GlanceModifier) {
    Column(
        modifier.fillMaxSize().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            body,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
        )
    }
}
