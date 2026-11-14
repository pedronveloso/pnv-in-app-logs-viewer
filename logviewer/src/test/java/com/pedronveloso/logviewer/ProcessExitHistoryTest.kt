package com.pedronveloso.logviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessExitHistoryTest {
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

  private fun raw(
    timestamp: Long,
    reason: Int,
    description: String? = null,
    importance: Int = 100,
  ) = RawExit(timestamp, timestamp.toInt(), reason, description, importance, 0, 0, "app")

  // The tests run on API 35, where only the documented limiter form counts.
  private fun limiterKill(timestamp: Long) = raw(timestamp, 13, LIMITER)

  // Unconfined runs the initial load before the constructor returns, so tests read state directly.
  private fun history(
    exits: List<RawExit>,
    alertOn: Set<ExitReason> = ExitReason.DEFAULT_ALERTS,
    maxExits: Int = 16,
    redact: ((String) -> String)? = null,
    onRead: (Int) -> Unit = {},
  ) =
    ProcessExitHistory(context, alertOn, maxExits, redact, Dispatchers.Unconfined) { _, max ->
      onRead(max)
      exits
    }

  private val ProcessExitHistory.loaded: ProcessExitState.Loaded
    get() = state.value as ProcessExitState.Loaded

  @Test
  fun memoryLimiterMatchesTheDocumentedAndTheRecordedForm() {
    assertThat(classifyExit(13, "MemoryLimiter:AnonSwap")).isEqualTo(ExitReason.MemoryLimit)
    // What an Android 17 emulator recorded for a real limiter kill.
    assertThat(classifyExit(17, "[KILL PID] kill", sdkInt = 37)).isEqualTo(ExitReason.MemoryLimit)
    // Reason 17 isn't public, so before Android 17 it means nothing we know of.
    assertThat(classifyExit(17, "[KILL PID] kill", sdkInt = 36)).isEqualTo(ExitReason.Unknown)
    assertThat(classifyExit(13, null)).isEqualTo(ExitReason.Other)
    assertThat(classifyExit(13, "killed by user")).isEqualTo(ExitReason.Other)
    assertThat(classifyExit(13, "after MemoryLimiter: in another process"))
      .isEqualTo(ExitReason.Other)
    assertThat(classifyExit(4, null)).isEqualTo(ExitReason.Crash)
    assertThat(classifyExit(999, null)).isEqualTo(ExitReason.Unknown)
  }

  @Test
  fun defaultAlertsFlagMemoryLimitCrashesAndAnrs() {
    val history =
      history(listOf(limiterKill(6), raw(5, 10), raw(4, 4), raw(3, 6), raw(2, 3), raw(1, 5)))
    assertThat(history.loaded.exits).hasSize(6)
    assertThat(history.loaded.pending.map { it.reason })
      .containsExactly(
        ExitReason.MemoryLimit,
        ExitReason.Crash,
        ExitReason.Anr,
        ExitReason.NativeCrash,
      )
      .inOrder()
    assertThat(history.state.value.pendingExits).hasSize(4)
  }

  @Test
  fun alertOnReplacesTheDefaults() {
    val history =
      history(listOf(limiterKill(2), raw(1, 4)), alertOn = setOf(ExitReason.MemoryLimit))
    assertThat(history.loaded.pending.map { it.reason }).containsExactly(ExitReason.MemoryLimit)
  }

  @Test
  fun acknowledgeClearsPendingAndPersists() {
    val exits = listOf(limiterKill(2_000), raw(1_000, 4))
    val history = history(exits)
    runBlocking { history.acknowledge() }
    assertThat(history.loaded.pending).isEmpty()
    assertThat(history.loaded.exits).hasSize(2)
    assertThat(history(exits).loaded.pending).isEmpty()
    // Only what is newer than the dismissed exits comes back.
    assertThat(history(listOf(raw(3_000, 6)) + exits).loaded.pending.map { it.timestampMillis })
      .containsExactly(3_000L)
  }

  @Test
  fun descriptionsAreRedacted() {
    val history = history(listOf(raw(1, 13, "token=abc")), redact = { it.replace("abc", "***") })
    assertThat(history.loaded.exits.single().description).isEqualTo("token=***")
    val failing = history(listOf(raw(1, 13, "x")), redact = { error("boom") })
    assertThat(failing.loaded.exits.single().description).isEqualTo("<redaction failed>")
  }

  @Test
  fun maxExitsBoundsTheRead() {
    var requested = 0
    val history =
      history(List(5) { raw(it.toLong(), 4) }, maxExits = 3, onRead = { requested = it })
    assertThat(requested).isEqualTo(3)
    assertThat(history.loaded.exits).hasSize(3)
  }

  @Test
  fun aFailedReadIsReported() {
    val history =
      ProcessExitHistory(context, ExitReason.DEFAULT_ALERTS, 16, null, Dispatchers.Unconfined) {
        _,
        _ ->
        error("binder died")
      }
    assertThat(history.state.value).isEqualTo(ProcessExitState.Failed("binder died"))
    assertThat(history.state.value.pendingExits).isEmpty()
  }

  @Test
  @Config(sdk = [29])
  fun olderAndroidIsUnsupportedAndNeverReads() {
    var read = false
    val history = history(listOf(raw(1, 4)), onRead = { read = true })
    assertThat(history.state.value).isEqualTo(ProcessExitState.Unsupported)
    assertThat(read).isFalse()
  }

  @Test
  fun whereTextNamesWhereTheAppWas() {
    fun where(importance: Int) =
      ProcessExit(0, 1, ExitReason.Crash, 4, null, importance, 0, 0, "app")
    assertThat(where(100).whereText).isEqualTo("It was on screen.")
    assertThat(where(125).whereText).isEqualTo("It was running a foreground service.")
    assertThat(where(200).whereText).isEqualTo("It was visible.")
    assertThat(where(400).whereText).isEqualTo("It was in the background.")
  }

  @Test
  fun captureBuilderTracksExitsUnlessTurnedOff() {
    assertThat(TimberLogCapture.Builder(context).build().processExits).isNotNull()
    assertThat(TimberLogCapture.Builder(context).processExits(enabled = false).build().processExits)
      .isNull()
  }

  @Test
  fun statusMessageExplainsTheExit() {
    val exit =
      ProcessExit(0, 1, ExitReason.MemoryLimit, 17, "[KILL PID] kill", 100, 1_480_000, 0, "app")
    val message = exit.statusMessage(isProblem = true)
    assertThat(message.title).startsWith("Memory limit • ")
    // The card stays short; the description and memory live in the details sheet.
    assertThat(message.detail).isEqualTo("${ExitReason.MemoryLimit.explanation} It was on screen.")
  }
}

private const val LIMITER = "MemoryLimiter:AnonSwap"
