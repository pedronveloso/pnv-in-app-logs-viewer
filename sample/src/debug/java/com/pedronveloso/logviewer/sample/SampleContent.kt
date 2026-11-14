package com.pedronveloso.logviewer.sample

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.pedronveloso.logviewer.LogViewer
import timber.log.Timber

@Composable
fun SampleContent(modifier: Modifier = Modifier) {
  var showViewer by rememberSaveable { mutableStateOf(false) }
  var count by remember { mutableIntStateOf(0) }
  var scenario by rememberSaveable { mutableStateOf(StatusDemoScenario.REAL) }
  val returnHome = { showViewer = false }
  BackHandler(enabled = showViewer, onBack = returnHome)
  if (showViewer) {
    LogViewer(
      source = remember(scenario) { scenario.source(SampleApplication.capture) },
      onBack = returnHome,
      modifier = modifier,
      // Demo scenarios wrap the capture, so hand its exit history over explicitly.
      processExits = SampleApplication.capture.processExits,
    )
  } else {
    SampleHome(
      scenario = scenario,
      onScenarioChange = { scenario = it },
      onGenerateLogs = {
        val batch = ++count
        Timber.tag("Sample").d("Generated message %d access_token=sample-secret", batch)
        Timber.tag("Sample").w("Generated warning %d: slow response", batch)
        Timber.tag("Sample\$generateLogs")
          .e(
            IllegalStateException("Request $batch failed"),
            "Generated error %d: request failed",
            batch,
          )
      },
      onCrash = {
        Timber.tag("Sample").w("About to crash; this entry should survive restart")
        error("Intentional sample crash")
      },
      onOpenLogs = { showViewer = true },
      modifier = modifier,
    )
  }
}

@Composable
private fun SampleHome(
  scenario: StatusDemoScenario,
  onScenarioChange: (StatusDemoScenario) -> Unit,
  onGenerateLogs: () -> Unit,
  onCrash: () -> Unit,
  onOpenLogs: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var scenarioMenu by remember { mutableStateOf(false) }
  Column(
    modifier
      .fillMaxSize()
      .safeDrawingPadding()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Log viewer sample", style = MaterialTheme.typography.titleLarge)
    Button(onClick = onGenerateLogs) { Text("Generate logs") }
    Box {
      TextButton(onClick = { scenarioMenu = true }) { Text("Status demo: ${scenario.label} ▾") }
      DropdownMenu(expanded = scenarioMenu, onDismissRequest = { scenarioMenu = false }) {
        StatusDemoScenario.entries.forEach { option ->
          DropdownMenuItem(
            text = { Text(option.label) },
            onClick = {
              onScenarioChange(option)
              scenarioMenu = false
            },
          )
        }
      }
    }
    if (scenario != StatusDemoScenario.REAL) {
      Text(
        "This Status scenario is simulated. Your captured logs and settings are unchanged.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Button(onClick = onOpenLogs) { Text("Open logs") }
    Button(onClick = onCrash) { Text("Crash app to test recovery") }
    Text("After a crash, reopen the app and choose the previous session in Logs.")
  }
}

@PreviewLightDark
@Composable
private fun RealCaptureSamplePreview() {
  SampleTheme {
    SampleHome(StatusDemoScenario.REAL, {}, {}, {}, {}, Modifier.height(600.dp))
  }
}

@PreviewLightDark
@Composable
private fun SimulatedStatusSamplePreview() {
  SampleTheme {
    SampleHome(StatusDemoScenario.WRITE_FAILURE, {}, {}, {}, {}, Modifier.height(600.dp))
  }
}
