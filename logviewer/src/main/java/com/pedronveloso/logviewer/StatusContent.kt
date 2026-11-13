package com.pedronveloso.logviewer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun StatusContent(
  capabilities: LogCapabilities,
  health: LogHealth,
  modifier: Modifier = Modifier,
  exitState: ProcessExitState? = null,
  onDismissExits: () -> Unit = {},
  onSelectExit: (ProcessExit) -> Unit = {},
) {
  LazyColumn(
    modifier,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Text(
        when {
          !capabilities.hasInMemoryLogs && !capabilities.persistsAcrossCrashes -> "No log source"
          capabilities.isLibraryCapture -> "Library capture"
          else -> "App-provided source"
        },
        style = MaterialTheme.typography.titleMedium,
      )
    }
    items(statusMessages(capabilities, health)) { message -> StatusCard(message) }
    health.lastDiskWriteMillis?.let { last ->
      item {
        Text(
          "Last disk write: ${formatSessionDate(last)}",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    if (exitState != null) processExitItems(exitState, onDismissExits, onSelectExit)
  }
}

private fun LazyListScope.processExitItems(
  state: ProcessExitState,
  onDismiss: () -> Unit,
  onSelect: (ProcessExit) -> Unit,
) {
  item {
    Text(
      "Previous exits",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
  when (state) {
    ProcessExitState.Unsupported ->
      item {
        StatusCard(
          StatusMessage(
            "Exit history unavailable",
            "Android records why the app stopped from Android 11 (API 30).",
            false,
          ),
        )
      }
    ProcessExitState.Loading ->
      item { Text("Reading exit history...", style = MaterialTheme.typography.bodyMedium) }
    is ProcessExitState.Failed ->
      item { StatusCard(StatusMessage("Could not read exit history", state.message, true)) }
    is ProcessExitState.Loaded -> {
      if (state.exits.isEmpty()) {
        item { Text("No exits recorded.", style = MaterialTheme.typography.bodyMedium) }
      }
      items(state.pending, key = { "pending-${it.timestampMillis}-${it.pid}" }) { exit ->
        StatusCard(
          exit.statusMessage(isProblem = true),
          onClick = { onSelect(exit) },
          testTag = "exit_card_${exit.pid}",
        )
      }
      if (state.pending.isNotEmpty()) {
        item {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss_exits")) {
            Text(if (state.pending.size == 1) "Dismiss" else "Dismiss all")
          }
        }
      }
      val reviewed = state.exits - state.pending.toSet()
      items(reviewed, key = { "exit-${it.timestampMillis}-${it.pid}" }) { exit ->
        StatusCard(
          exit.statusMessage(isProblem = false),
          showIcon = false,
          onClick = { onSelect(exit) },
          testTag = "exit_card_${exit.pid}",
        )
      }
    }
  }
}

/** One exit as a status card: when and why, and what it means. The rest waits in its sheet. */
internal fun ProcessExit.statusMessage(isProblem: Boolean): StatusMessage =
  StatusMessage(
    "${reason.label} • ${formatSessionDate(timestampMillis)}",
    "${reason.explanation} $whereText",
    isProblem,
  )

/** Decimal units, like Android's own storage screens. */
internal fun formatKilobytes(kb: Long): String {
  val bytes = kb * 1024.0
  return when {
    bytes >= 1e9 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
    bytes >= 1e6 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
  }
}

/** The warning tone used for problems, readable on light and dark surfaces. */
@Composable
@ReadOnlyComposable
internal fun problemColor(): Color =
  if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFF8F5A00) else Color(0xFFFFC46B)

@Composable
private fun StatusCard(
  message: StatusMessage,
  showIcon: Boolean = true,
  onClick: (() -> Unit)? = null,
  testTag: String? = null,
) {
  val statusColor =
    if (message.isProblem) problemColor()
    else if (showIcon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
  val modifier =
    Modifier.fillMaxWidth()
      .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
      .semantics(mergeDescendants = true) {
        stateDescription = if (message.isProblem) "Action needed" else "Healthy"
        if (onClick != null)
          onClick(label = "Show details") {
            onClick()
            true
          }
      }
  val content: @Composable () -> Unit = {
    StatusCardContent(message, showIcon, statusColor, showChevron = onClick != null)
  }
  if (onClick != null) Card(onClick = onClick, modifier = modifier) { content() }
  else Card(modifier = modifier) { content() }
}

@Composable
private fun StatusCardContent(
  message: StatusMessage,
  showIcon: Boolean,
  statusColor: Color,
  showChevron: Boolean,
) {
  Column(Modifier.padding(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (showIcon) {
        Icon(
          imageVector =
            if (message.isProblem) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
          contentDescription = null,
          tint = statusColor,
        )
        Spacer(Modifier.width(8.dp))
      }
      Text(
        message.title,
        color = statusColor,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1f),
      )
      if (showChevron) {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
      }
    }
    Text(
      message.detail,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(start = if (showIcon) 32.dp else 0.dp, top = 4.dp),
    )
  }
}

@Composable
private fun StatusPreviewTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
  ) {
    Surface(color = MaterialTheme.colorScheme.background) { content() }
  }
}

@PreviewLightDark
@Composable
private fun HealthyStatusPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health = LogHealth(installed = true, lastDiskWriteMillis = 1_700_000_000_000),
      modifier = Modifier.height(500.dp),
    )
  }
}

@PreviewLightDark
@Composable
private fun ConfigurationWarningsPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, false, false, false, isLibraryCapture = true),
      health = LogHealth(installed = false),
      modifier = Modifier.height(650.dp),
    )
  }
}

@PreviewLightDark
@Composable
private fun FailureStatusPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health =
        LogHealth(
          installed = true,
          readError = "Demo read failure",
          writeError = "Demo write failure",
        ),
      modifier = Modifier.height(500.dp),
    )
  }
}

@PreviewLightDark
@Composable
private fun ExternalSourcePreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true),
      health = LogHealth(),
      modifier = Modifier.height(500.dp),
    )
  }
}

private val previewExits =
  listOf(
    ProcessExit(
      1_790_400_000_000,
      10969,
      ExitReason.MemoryLimit,
      17,
      "[KILL PID] kill",
      100,
      0,
      0,
      "app",
    ),
    ProcessExit(
      1_790_300_000_000,
      9120,
      ExitReason.UserStopped,
      10,
      "remove task",
      400,
      0,
      0,
      "app",
    ),
    ProcessExit(
      1_790_200_000_000,
      8810,
      ExitReason.LowMemory,
      3,
      null,
      400,
      420_000,
      610_000,
      "app",
    ),
  )

@PreviewLightDark
@Composable
private fun PendingExitsPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health = LogHealth(installed = true),
      modifier = Modifier.height(900.dp),
      exitState = ProcessExitState.Loaded(previewExits, previewExits.take(1)),
    )
  }
}

@PreviewLightDark
@Composable
private fun ReviewedExitsPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health = LogHealth(installed = true),
      modifier = Modifier.height(700.dp),
      exitState = ProcessExitState.Loaded(previewExits, emptyList()),
    )
  }
}

@PreviewLightDark
@Composable
private fun UnsupportedExitsPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health = LogHealth(installed = true),
      modifier = Modifier.height(500.dp),
      exitState = ProcessExitState.Unsupported,
    )
  }
}

@PreviewLightDark
@Composable
private fun NoExitsPreview() {
  StatusPreviewTheme {
    StatusContent(
      capabilities = LogCapabilities(true, true, true, true, isLibraryCapture = true),
      health = LogHealth(installed = true),
      modifier = Modifier.height(500.dp),
      exitState = ProcessExitState.Loaded(emptyList(), emptyList()),
    )
  }
}
