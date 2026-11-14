package com.pedronveloso.logviewer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ROW_WHEN = "When"
private const val ROW_MEANING = "What it means"
private const val ROW_WHERE = "Where"

/** Every field of an exit, labelled; the sheet shows them and "Share details" writes them out. */
internal fun ProcessExit.detailRows(): List<Pair<String, String>> = buildList {
  add(ROW_WHEN to formatSessionDate(timestampMillis))
  add("Reason" to "${reason.label} (code $reasonCode)")
  add(ROW_MEANING to reason.explanation)
  add(ROW_WHERE to whereText)
  description?.takeIf { it.isNotBlank() }?.let { add("System description" to it) }
  statusText?.let { add("Status" to it) }
  listOfNotNull(
      pssKb.takeIf { it > 0 }?.let { "PSS ${formatKilobytes(it)}" },
      rssKb.takeIf { it > 0 }?.let { "RSS ${formatKilobytes(it)}" },
    )
    .takeIf { it.isNotEmpty() }
    ?.let { add("Memory at exit" to it.joinToString(" • ")) }
  add("Process" to "$processName (PID $pid)")
  anr?.let {
    add("ANR type" to it.typeLabel)
    add("ANR timeout" to formatTimeout(it.timeoutMillis))
    add("User could see it" to if (it.userPerceptible) "Yes" else "No")
    add("ANR ID" to it.anrId.toString())
  }
  sessionId?.let { add("Log session" to it) }
  appNote?.let { add("App note" to it) }
  stateSummary?.let { add("App state" to it) }
  add("Trace" to (traceKind?.label ?: "None kept"))
}

/** The "Share details" file: the rows, then an ANR dump when there is one, as text. */
internal fun ProcessExit.detailsText(trace: String?): String = buildString {
  appendLine("Process exit")
  detailRows().forEach { (label, value) -> appendLine("$label: $value") }
  if (trace != null) {
    appendLine()
    appendLine("Trace")
    append(trace)
  }
}

/** `anr-20261112-193655.txt`: sortable, and safe on any file system it lands on. */
internal fun ProcessExit.exportFileName(prefix: String, extension: String): String =
  "$prefix-" +
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMillis)) +
    ".$extension"

internal fun relativeTime(millis: Long, now: Long): String {
  val minutes = (now - millis) / 60_000
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "$minutes min ago"
    minutes < 48 * 60 -> "${minutes / 60} h ago"
    else -> "${minutes / (24 * 60)} days ago"
  }
}

private fun formatTimeout(millis: Long): String =
  if (millis % 1000 == 0L) "${millis / 1000} s" else "$millis ms"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExitDetailsSheet(
  exit: ProcessExit,
  sessionKept: Boolean,
  onOpenSession: () -> Unit,
  onShareTrace: () -> Unit,
  onShareDetails: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("exit_details_sheet"),
  ) {
    ExitDetailsContent(exit, sessionKept, onOpenSession, onShareTrace, onShareDetails)
  }
}

@Composable
private fun ExitDetailsContent(
  exit: ProcessExit,
  sessionKept: Boolean,
  onOpenSession: () -> Unit,
  onShareTrace: () -> Unit,
  onShareDetails: () -> Unit,
  modifier: Modifier = Modifier,
  now: Long = System.currentTimeMillis(),
) {
  Column(
    modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(exit.reason.label, style = MaterialTheme.typography.headlineSmall)
    Text(
      "${formatSessionDate(exit.timestampMillis)} • ${relativeTime(exit.timestampMillis, now)}",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("${exit.reason.explanation} ${exit.whereText}", style = MaterialTheme.typography.bodyLarge)
    // The header already says when, why and where; the rows carry the rest.
    exit
      .detailRows()
      .filterNot { (label, _) -> label == ROW_WHEN || label == ROW_MEANING || label == ROW_WHERE }
      .forEach { (label, value) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        }
      }
    if (exit.sessionId != null) {
      if (sessionKept) {
        OutlinedButton(onClick = onOpenSession, modifier = Modifier.testTag("open_exit_session")) {
          Text("Open this session's logs")
        }
      } else {
        Text(
          "This session's logs are no longer kept.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (exit.hasTrace) {
        ShareButton("Share trace", onShareTrace, Modifier.testTag("share_exit_trace"))
      }
      ShareButton("Share details", onShareDetails, Modifier.testTag("share_exit_details"))
    }
  }
}

@Composable
private fun ShareButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  OutlinedButton(onClick = onClick, modifier = modifier) {
    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(label)
  }
}

@Composable
private fun ExitPreviewTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
  ) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
  }
}

private const val PREVIEW_NOW = 1_790_407_200_000

@PreviewLightDark
@Composable
private fun MemoryLimitExitPreview() {
  ExitPreviewTheme {
    ExitDetailsContent(
      ProcessExit(
        1_790_400_000_000,
        10969,
        ExitReason.MemoryLimit,
        17,
        "[KILL PID] kill",
        100,
        0,
        0,
        "com.example.app",
        status = 9,
        sessionId = "1790399000000-3f2a9c1b",
        appNote = "model: Voz",
      ),
      sessionKept = true,
      onOpenSession = {},
      onShareTrace = {},
      onShareDetails = {},
      now = PREVIEW_NOW,
    )
  }
}

@PreviewLightDark
@Composable
private fun AnrExitPreview() {
  ExitPreviewTheme {
    ExitDetailsContent(
      ProcessExit(
        1_790_400_000_000,
        8120,
        ExitReason.Anr,
        6,
        "Input dispatching timed out",
        100,
        310_000,
        420_000,
        "com.example.app",
        hasTrace = true,
        traceKind = TraceKind.AnrDump,
        anr = AnrDetails(type = 2, timeoutMillis = 5_000, userPerceptible = true, anrId = 41),
        sessionId = "1790399000000-3f2a9c1b",
      ),
      sessionKept = false,
      onOpenSession = {},
      onShareTrace = {},
      onShareDetails = {},
      now = PREVIEW_NOW,
    )
  }
}

@PreviewLightDark
@Composable
private fun NativeCrashExitPreview() {
  ExitPreviewTheme {
    ExitDetailsContent(
      ProcessExit(
        1_790_400_000_000,
        7311,
        ExitReason.NativeCrash,
        5,
        null,
        100,
        0,
        0,
        "com.example.app",
        status = 11,
        hasTrace = true,
        traceKind = TraceKind.Tombstone,
        stateSummary = "level=4;mode=hard",
      ),
      sessionKept = false,
      onOpenSession = {},
      onShareTrace = {},
      onShareDetails = {},
      now = PREVIEW_NOW,
    )
  }
}
