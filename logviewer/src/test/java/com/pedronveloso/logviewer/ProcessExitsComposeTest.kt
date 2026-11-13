package com.pedronveloso.logviewer

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val UI_WAIT_TIMEOUT_MILLIS = 5_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessExitsComposeTest {
  @get:Rule val compose = createComposeRule()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context
      .getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  private fun history(vararg exits: RawExit) =
    ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) { _, _
      ->
      exits.toList()
    }

  @Test
  fun statusTabWarnsUntilTheExitIsDismissed() {
    val history = history(RawExit(1_000, 42, 17, "[KILL PID] kill", 100, 0, 0, "app"))
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = history) }
    }
    compose.onNodeWithTag("status_tab_warning", useUnmergedTree = true).assertExists()
    compose.onNodeWithTag("status_tab").performClick()
    // The section sits below the capture cards, past the first screen of the lazy list.
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("dismiss_exits"))
    compose.onNodeWithTag("dismiss_exits").performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose
        .onAllNodesWithTag("status_tab_warning", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isEmpty()
    }
    assertThat(history.state.value.pendingExits).isEmpty()
  }

  @Test
  fun exitsThatAreNotFlaggedDoNotWarn() {
    val history = history(RawExit(1_000, 42, 10, "remove task", 400, 0, 0, "app"))
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = history) }
    }
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNode(hasScrollAction()).performScrollToNode(hasText("Previous exits"))
    assertThat(
        compose
          .onAllNodesWithTag("status_tab_warning", useUnmergedTree = true)
          .fetchSemanticsNodes(),
      )
      .isEmpty()
  }

  private fun openExitSheet(history: ProcessExitHistory, pid: Int) {
    compose.setContent {
      MaterialTheme { LogViewer(EmptySource, onBack = {}, processExits = history) }
    }
    compose.onNodeWithTag("status_tab").performClick()
    compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("exit_card_$pid"))
    compose.onNodeWithTag("exit_card_$pid").performClick()
    compose.onNodeWithTag("exit_details_sheet").assertExists()
  }

  @Test
  fun tappingAnExitOpensItsDetailsWithTraceSharingOnlyWhenKept() {
    val history =
      history(
        RawExit(2_000, 2, 6, "Input dispatching timed out", 100, 0, 0, "app", hasTrace = true),
        RawExit(1_000, 1, 10, "remove task", 400, 0, 0, "app"),
      )
    openExitSheet(history, pid = 2)
    compose.onNodeWithText("Input dispatching timed out").assertExists()
    compose.onNodeWithTag("share_exit_trace").assertExists()
    compose.onNodeWithTag("share_exit_details").assertExists()
  }

  @Test
  fun anExitWithoutATraceOffersOnlyDetails() {
    openExitSheet(history(RawExit(1_000, 1, 10, "remove task", 400, 0, 0, "app")), pid = 1)
    compose.onNodeWithTag("share_exit_details").assertExists()
    compose.onNodeWithTag("share_exit_trace").assertDoesNotExist()
  }

  @Test
  fun openSessionLogsJumpsToThatSession() {
    val tag = encodeStateSummary("old", null)
    openExitSheet(
      history(RawExit(1_000, 3, 17, "[KILL PID] kill", 100, 0, 0, "app", stateSummary = tag)),
      pid = 3,
    )
    // The sheet is taller than the test screen; tapping off-screen would land on the scrim.
    compose.onNodeWithTag("open_exit_session").performScrollTo().performClick()
    compose.waitUntil(UI_WAIT_TIMEOUT_MILLIS) {
      compose
        .onAllNodesWithText("entry in old", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose.onNodeWithTag("exit_details_sheet").assertDoesNotExist()
  }

  private object EmptySource : LogSource {
    override val changes: Flow<Unit>? = null
    override val capabilities: StateFlow<LogCapabilities> =
      MutableStateFlow(LogCapabilities(true, true, false, true))
    override val health: StateFlow<LogHealth> = MutableStateFlow(LogHealth())

    override suspend fun sessions() =
      listOf(LogSession("current", 2, true), LogSession("old", 1, false))

    override suspend fun entries(sessionId: String) =
      if (sessionId == "old") listOf(LogEntry(0, "old", 1, 4, "Tag", "entry in old"))
      else emptyList()

    override suspend fun clear(sessionId: String) = Unit
  }
}
