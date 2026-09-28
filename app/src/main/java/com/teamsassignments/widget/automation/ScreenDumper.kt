package com.teamsassignments.widget.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import com.teamsassignments.widget.data.SyncLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves the Teams accessibility tree as uiautomator-style XML, the same format as the Phase 0
 * test fixtures, so a dump from the phone can go straight into `src/test/resources`.
 *
 * Arming it from the setup app shows a Capture pill whenever Teams is on top; the user goes to
 * the screen that broke and taps Capture, and the dump is offered in the share sheet.
 */
class ScreenDumper(
    private val service: AccessibilityService,
    private val device: AndroidTeamsDevice,
    private val log: SyncLog,
    private val scope: CoroutineScope,
) {
    private val banner = OverlayBanner(service)
    private var watcher: Job? = null

    val isArmed: Boolean get() = watcher?.isActive == true

    fun arm() {
        watcher?.cancel()
        // Only Teams' events reach the service, so poll to notice the user leaving Teams.
        watcher = scope.launch {
            while (isActive) {
                if (device.teamsWindowRoot() != null) {
                    banner.show("Dump this Teams screen", "Capture", showSpinner = false) { capture() }
                } else {
                    banner.hide()
                }
                delay(POLL_MS)
            }
        }
    }

    fun disarm() {
        watcher?.cancel()
        watcher = null
        banner.hide()
    }

    private fun capture() {
        val root = device.teamsWindowRoot() ?: return
        disarm()
        scope.launch {
            // Walking a large tree over IPC and writing it out would stall the main thread.
            val file = withContext(Dispatchers.IO) { runCatching { save(root, "teams") }.getOrNull() }
            if (file == null) {
                Toast.makeText(service, "Couldn't save the dump", Toast.LENGTH_LONG).show()
                return@launch
            }
            log.add("Saved screen dump ${file.name}")
            share(service, file)
        }
    }

    /** Saves whatever Teams shows now; called when a sync fails, to record where it got stuck. */
    suspend fun saveFailureDump() {
        val root = device.teamsWindowRoot() ?: return
        withContext(Dispatchers.IO) { runCatching { save(root, "failure") } }
            .onSuccess { log.add("Saved screen dump ${it.name}") }
    }

    private fun save(root: AccessibilityNodeInfo, prefix: String): File {
        val dir = dumpDir(service).apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "$prefix-$stamp.xml")
        file.bufferedWriter().use { writer ->
            writer.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy rotation=\"0\">")
            writeNode(writer, root, index = 0, budget = intArrayOf(MAX_NODES))
            writer.write("</hierarchy>\n")
        }
        dumps(service).drop(KEEP_DUMPS).forEach(File::delete)
        return file
    }

    private fun writeNode(writer: Writer, node: AccessibilityNodeInfo, index: Int, budget: IntArray) {
        if (budget[0]-- <= 0) return
        val bounds = Rect().also(node::getBoundsInScreen)
        writer.write("<node")
        attr(writer, "index", index.toString())
        attr(writer, "text", node.text?.toString().orEmpty())
        attr(writer, "resource-id", node.viewIdResourceName.orEmpty())
        attr(writer, "class", node.className?.toString().orEmpty())
        attr(writer, "package", node.packageName?.toString().orEmpty())
        attr(writer, "content-desc", node.contentDescription?.toString().orEmpty())
        attr(writer, "clickable", node.isClickable.toString())
        attr(writer, "enabled", node.isEnabled.toString())
        attr(writer, "focusable", node.isFocusable.toString())
        attr(writer, "scrollable", node.isScrollable.toString())
        attr(writer, "long-clickable", node.isLongClickable.toString())
        attr(writer, "selected", node.isSelected.toString())
        attr(writer, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        writer.write(">")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { writeNode(writer, it, i, budget) }
        }
        writer.write("</node>")
    }

    private fun attr(writer: Writer, name: String, value: String) {
        writer.write(" ")
        writer.write(name)
        writer.write("=\"")
        for (c in value) {
            when {
                c == '&' -> writer.write("&amp;")
                c == '<' -> writer.write("&lt;")
                c == '>' -> writer.write("&gt;")
                c == '"' -> writer.write("&quot;")
                c == '\n' -> writer.write("&#10;")
                c == '\r' -> writer.write("&#13;")
                c == '\t' -> writer.write("&#9;")
                c < ' ' -> Unit // not allowed in XML 1.0
                else -> writer.write(c.code)
            }
        }
        writer.write("\"")
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val MAX_NODES = 5_000
        private const val KEEP_DUMPS = 20

        fun dumpDir(context: Context) = File(context.filesDir, "dumps")

        /** Saved dumps, newest first. */
        fun dumps(context: Context): List<File> =
            dumpDir(context).listFiles { f -> f.extension == "xml" }?.sortedByDescending { it.lastModified() }.orEmpty()

        fun share(context: Context, file: File) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(send, "Share Teams screen dump").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
