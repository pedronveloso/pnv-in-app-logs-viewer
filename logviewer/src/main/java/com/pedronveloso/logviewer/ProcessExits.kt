package com.pedronveloso.logviewer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why Android ended one of the app's processes, with the copy the Status tab shows for it. */
public enum class ExitReason(public val label: String, public val explanation: String) {
  MemoryLimit(
    "Memory limit",
    "Android's memory limiter stopped the app for using more RAM than its per-app limit allows.",
  ),
  LowMemory("Low memory", "The device ran short of RAM and the system reclaimed the app."),
  Crash("Crash", "An uncaught Java or Kotlin exception ended the app."),
  NativeCrash("Native crash", "Native code crashed the process."),
  Anr("ANR", "The app stopped responding and the system closed it."),
  ExitSelf("Exited itself", "The app ended its own process."),
  Signaled("Signaled", "The process got a signal from the system that ended it."),
  StartFailed("Start failed", "The process failed while starting up."),
  PermissionChanged("Permission changed", "A permission was revoked, which restarts the app."),
  ExcessiveResourceUse(
    "Excessive resource use",
    "The system stopped the app for using too much CPU or other resources.",
  ),
  UserStopped("User stopped", "Someone stopped the app, from Recents, Force stop or adb."),
  UserProfileStopped("User profile stopped", "The user profile the app ran in was stopped."),
  DependencyDied("Dependency died", "A process the app depended on died, so it went too."),
  Frozen("Frozen", "The app was stopped while frozen in the background."),
  PackageStateChanged("Package state changed", "The app or one of its components was disabled."),
  AppUpdated("App updated", "The app was updated or reinstalled."),
  Other("Other", "The system gave a reason outside the standard ones."),
  Unknown("Unknown", "Android didn't record why the app stopped.");

  public companion object {
    /** What [ProcessExitHistory] flags unless the app chooses otherwise. */
    public val DEFAULT_ALERTS: Set<ExitReason> = setOf(MemoryLimit, Crash, NativeCrash, Anr)
  }
}

/** One exit Android recorded for the app, from `ApplicationExitInfo`. */
public data class ProcessExit(
  public val timestampMillis: Long,
  public val pid: Int,
  public val reason: ExitReason,
  /** The raw `ApplicationExitInfo` reason, for reasons the [ExitReason] mapping folds together. */
  public val reasonCode: Int,
  /** Android's own words, already passed through the configured redactor. */
  public val description: String?,
  public val importance: Int,
  /** Memory Android sampled at exit, in kB; 0 when it took no sample. */
  public val pssKb: Long,
  public val rssKb: Long,
  public val processName: String,
  /** The `exit()` code, or the signal number when the process was signaled; see [statusText]. */
  public val status: Int = 0,
  /** Whether Android kept a trace for this exit; read it with [ProcessExitHistory.openTrace]. */
  public val hasTrace: Boolean = false,
  public val traceKind: TraceKind? = null,
  /** Only for ANRs, from Android 17 (API 37). */
  public val anr: AnrDetails? = null,
  /** The library capture session that was running, when it tagged the process. */
  public val sessionId: String? = null,
  /** The note the app set with [TimberLogCapture.setExitNote], redacted. */
  public val appNote: String? = null,
  /** State another writer left with `setProcessStateSummary`: text when printable, else hex. */
  public val stateSummary: String? = null,
) {
  /** Where the app was when it stopped, which decides how much memory it may use. */
  public val whereText: String
    get() =
      when {
        importance <= IMPORTANCE_FOREGROUND -> "It was on screen."
        importance <= IMPORTANCE_FOREGROUND_SERVICE -> "It was running a foreground service."
        importance <= IMPORTANCE_VISIBLE -> "It was visible."
        importance <= IMPORTANCE_PERCEPTIBLE ->
          "It was in the background but noticeable, like playing audio."
        else -> "It was in the background."
      }

  /** What [status] means for this reason, or null when it carries nothing. */
  public val statusText: String?
    get() =
      when (reason) {
        ExitReason.Signaled,
        ExitReason.NativeCrash,
        ExitReason.MemoryLimit,
        ExitReason.LowMemory ->
          status
            .takeIf { it > 0 }
            ?.let { "Signal $it" + signalName(it)?.let { name -> " ($name)" }.orEmpty() }
        ExitReason.ExitSelf -> "Exit code $status"
        else -> status.takeIf { it != 0 }?.let { "Status $it" }
      }
}

public enum class TraceKind(public val label: String) {
  /** The thread dump Android took for an ANR; plain text. */
  AnrDump("ANR thread dump"),
  /** A native crash tombstone, a protobuf following AOSP's `tombstone.proto` (API 31+). */
  Tombstone("Native crash tombstone (protobuf)"),
}

/** `ApplicationExitInfo.AnrInfo`, Android 17 (API 37) and later. */
public data class AnrDetails(
  /** An `android.app.AnrTypes` value. */
  public val type: Int,
  public val timeoutMillis: Long,
  /** Whether the hang was in front of the user, rather than in a background component. */
  public val userPerceptible: Boolean,
  public val anrId: Int,
) {
  public val typeLabel: String
    get() =
      when (type) {
        1 -> "No focused window"
        2 -> "Input dispatch"
        3 -> "Broadcast"
        4 -> "Start foreground service"
        5 -> "Execute service"
        6 -> "Content provider"
        7 -> "App triggered"
        8 -> "Short foreground service timeout"
        9 -> "Job service start"
        10 -> "Application start"
        else -> "Other"
      }
}

private fun signalName(signal: Int): String? =
  when (signal) {
    4 -> "SIGILL"
    6 -> "SIGABRT"
    7 -> "SIGBUS"
    8 -> "SIGFPE"
    9 -> "SIGKILL"
    11 -> "SIGSEGV"
    15 -> "SIGTERM"
    else -> null
  }

/** The exit history as the Status tab and host apps observe it. */
public sealed interface ProcessExitState {
  /** `ApplicationExitInfo` needs Android 11 (API 30). */
  public data object Unsupported : ProcessExitState

  public data object Loading : ProcessExitState

  public data class Failed(public val message: String) : ProcessExitState

  /**
   * [exits] newest first. [pending] are those whose reason is in the configured alert set and that
   * are newer than the last [ProcessExitHistory.acknowledge], also newest first.
   */
  public data class Loaded(
    public val exits: List<ProcessExit>,
    public val pending: List<ProcessExit>,
  ) : ProcessExitState
}

/** The exits still to review; empty in every state but [ProcessExitState.Loaded]. */
public val ProcessExitState.pendingExits: List<ProcessExit>
  get() = (this as? ProcessExitState.Loaded)?.pending.orEmpty()

/**
 * Reads the exits Android recorded for the app and remembers which flagged ones were reviewed.
 * Reading only queries the system, so it plants nothing and collects no logs. It loads once when
 * built and again on [refresh]; a process can't see its own death, only earlier ones.
 */
public class ProcessExitHistory
internal constructor(
  context: Context,
  private val alertOn: Set<ExitReason>,
  private val maxExits: Int,
  private val redact: ((String) -> String)?,
  private val ioDispatcher: CoroutineDispatcher,
  private val readExits: (Context, Int) -> List<RawExit>,
) {
  init {
    require(maxExits > 0) { "maxExits must be positive" }
  }

  private val appContext = context.applicationContext
  private val prefs =
    appContext.getSharedPreferences(LogViewerPreferences.FILE, Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
  private val mutex = Mutex()
  private val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
  private val mutableState =
    MutableStateFlow(if (supported) ProcessExitState.Loading else ProcessExitState.Unsupported)
  public val state: StateFlow<ProcessExitState> = mutableState.asStateFlow()

  init {
    refresh()
  }

  /** Reads the history again, for example when the app returns to the foreground. */
  public fun refresh() {
    if (supported) scope.launch { mutex.withLock { load() } }
  }

  internal var traceReader: (Context, Int, Long) -> InputStream? = ::platformTrace

  /**
   * Opens the trace Android kept for [exit], or null when there is none or it has since been
   * overwritten (traces share a system-wide buffer). The caller closes the stream. A
   * [TraceKind.Tombstone] is binary; share it to a computer rather than showing it.
   */
  public suspend fun openTrace(exit: ProcessExit): InputStream? =
    if (!exit.hasTrace || !supported) null
    else withContext(ioDispatcher) { traceReader(appContext, exit.pid, exit.timestampMillis) }

  /** Marks every pending exit as reviewed. The choice persists across restarts. */
  public suspend fun acknowledge() {
    withContext(ioDispatcher) {
      mutex.withLock {
        val loaded = mutableState.value as? ProcessExitState.Loaded ?: return@withLock
        val newest = loaded.pending.maxOfOrNull { it.timestampMillis } ?: return@withLock
        prefs.edit(commit = true) { putLong(KEY_EXITS_SEEN, newest) }
        mutableState.value = loaded.copy(pending = emptyList())
      }
    }
  }

  private fun load() {
    runCatching { readExits(appContext, maxExits).take(maxExits).map(::toExit) }
      .onSuccess { exits ->
        val seen = prefs.getLong(KEY_EXITS_SEEN, 0L)
        mutableState.value =
          ProcessExitState.Loaded(
            exits,
            exits.filter { it.reason in alertOn && it.timestampMillis > seen },
          )
      }
      .onFailure {
        mutableState.value = ProcessExitState.Failed(it.message ?: "Could not read exit history")
      }
  }

  private fun redacted(value: String): String = runCatching {
    redact?.invoke(value) ?: value
  }
    .getOrDefault("<redaction failed>")

  private fun toExit(raw: RawExit): ProcessExit {
    val summary = decodeStateSummary(raw.stateSummary)
    return ProcessExit(
      timestampMillis = raw.timestampMillis,
      pid = raw.pid,
      reason = classifyExit(raw.reasonCode, raw.description),
      reasonCode = raw.reasonCode,
      description = raw.description?.let(::redacted),
      importance = raw.importance,
      pssKb = raw.pssKb,
      rssKb = raw.rssKb,
      processName = raw.processName,
      status = raw.status,
      hasTrace = raw.hasTrace,
      traceKind =
        when {
          !raw.hasTrace -> null
          raw.reasonCode == REASON_CRASH_NATIVE -> TraceKind.Tombstone
          // Android also keeps an ANR's dump when the process recovered and died later.
          else -> TraceKind.AnrDump
        },
      anr = raw.anr,
      sessionId = summary.sessionId,
      appNote = summary.note?.let(::redacted),
      stateSummary = summary.foreign?.let(::redacted),
    )
  }

  /** Configures a history for an app that brings its own [LogSource]. */
  public class Builder(context: Context) {
    private val context = context.applicationContext
    private var alertOn = ExitReason.DEFAULT_ALERTS
    private var maxExits = DEFAULT_MAX_EXITS
    private var redact: ((String) -> String)? = null

    /** The reasons that count as pending and warn on the Status tab. */
    public fun alertOn(reasons: Set<ExitReason>): Builder = apply { alertOn = reasons.toSet() }

    /** How many of the newest exits to read. */
    public fun maxExits(count: Int): Builder = apply {
      require(count > 0) { "maxExits must be positive" }
      maxExits = count
    }

    /** Applied to each exit's description before it enters [state]. */
    public fun redact(redact: ((String) -> String)?): Builder = apply { this.redact = redact }

    public fun build(): ProcessExitHistory =
      ProcessExitHistory(context, alertOn, maxExits, redact, Dispatchers.IO, ::platformExits)
  }

  internal companion object {
    const val KEY_EXITS_SEEN = "process_exits_seen_until"
    const val DEFAULT_MAX_EXITS = 16
  }
}

/** What the platform reports, before classification and redaction. */
internal class RawExit(
  val timestampMillis: Long,
  val pid: Int,
  val reasonCode: Int,
  val description: String?,
  val importance: Int,
  val pssKb: Long,
  val rssKb: Long,
  val processName: String,
  val status: Int = 0,
  val hasTrace: Boolean = false,
  val anr: AnrDetails? = null,
  val stateSummary: ByteArray? = null,
)

private fun platformExits(context: Context, max: Int): List<RawExit> =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) readPlatformExits(context, max)
  else emptyList()

@RequiresApi(Build.VERSION_CODES.R)
private fun readPlatformExits(context: Context, max: Int): List<RawExit> =
  context
    .getSystemService(ActivityManager::class.java)
    ?.getHistoricalProcessExitReasons(null, 0, max)
    .orEmpty()
    .map {
      RawExit(
        timestampMillis = it.timestamp,
        pid = it.pid,
        reasonCode = it.reason,
        description = it.description,
        importance = it.importance,
        pssKb = it.pss,
        rssKb = it.rss,
        processName = it.processName,
        status = it.status,
        // Opening is enough to know; reading a dump of every exit would be wasted work.
        hasTrace = runCatching { it.traceInputStream?.use { true } ?: false }.getOrDefault(false),
        anr = if (Build.VERSION.SDK_INT >= ANDROID_17) anrDetails(it) else null,
        stateSummary = it.processStateSummary,
      )
    }

@RequiresApi(ANDROID_17)
private fun anrDetails(exit: android.app.ApplicationExitInfo): AnrDetails? =
  exit.anrInfo?.let { AnrDetails(it.anrType, it.timeoutMillis, it.isUserPerceptible, it.anrId) }

private fun platformTrace(context: Context, pid: Int, timestampMillis: Long): InputStream? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    context
      .getSystemService(ActivityManager::class.java)
      ?.getHistoricalProcessExitReasons(null, pid, 0)
      ?.firstOrNull { it.timestamp == timestampMillis }
      ?.traceInputStream
  } else {
    null
  }

internal fun writeStateSummary(context: Context, bytes: ByteArray) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(bytes)
  }
}

/** Android's limit for `setProcessStateSummary`. */
internal const val MAX_STATE_SUMMARY_BYTES = 128
private const val STATE_SUMMARY_PREFIX = "pnvlv:1:"

/**
 * The tag a capture leaves on its process, so the exit record names its log session:
 * `pnvlv:1:<sessionId>`, then a newline and the app's note, cut to fit 128 bytes on a UTF-8
 * boundary.
 */
internal fun encodeStateSummary(sessionId: String, note: String?): ByteArray {
  val head = (STATE_SUMMARY_PREFIX + sessionId).encodeToByteArray()
  val budget = MAX_STATE_SUMMARY_BYTES - head.size - 1
  if (note.isNullOrEmpty() || budget <= 0)
    return head.copyOf(minOf(head.size, MAX_STATE_SUMMARY_BYTES))
  return head + '\n'.code.toByte() + truncateUtf8(note, budget)
}

private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
  val bytes = text.encodeToByteArray()
  if (bytes.size <= maxBytes) return bytes
  var end = maxBytes
  // Back off continuation bytes (10xxxxxx) so a character is never split.
  while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
  return bytes.copyOf(end)
}

internal data class StateSummary(val sessionId: String?, val note: String?, val foreign: String?)

internal fun decodeStateSummary(bytes: ByteArray?): StateSummary {
  if (bytes == null || bytes.isEmpty()) return StateSummary(null, null, null)
  val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
  if (text != null && text.startsWith(STATE_SUMMARY_PREFIX)) {
    val body = text.removePrefix(STATE_SUMMARY_PREFIX)
    val newline = body.indexOf('\n')
    return if (newline < 0) StateSummary(body, null, null)
    else
      StateSummary(body.substring(0, newline), body.substring(newline + 1).ifEmpty { null }, null)
  }
  val printable = text?.all { !it.isISOControl() || it == '\n' || it == '\t' } == true
  return StateSummary(
    null,
    null,
    if (printable) text
    else bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
  )
}

/**
 * The memory limiter is documented as `REASON_OTHER` with "MemoryLimiter:AnonSwap" in the
 * description, but Android 17 builds record it as reason 17, which the public SDK doesn't define,
 * with a description of "[KILL PID] kill". Both count.
 */
internal fun classifyExit(reasonCode: Int, description: String?): ExitReason =
  if (
    reasonCode == REASON_SYSTEM_MEMORY_LIMIT ||
      (reasonCode == REASON_OTHER && description?.contains("MemoryLimiter:") == true)
  ) {
    ExitReason.MemoryLimit
  } else {
    when (reasonCode) {
      REASON_EXIT_SELF -> ExitReason.ExitSelf
      REASON_SIGNALED -> ExitReason.Signaled
      REASON_LOW_MEMORY -> ExitReason.LowMemory
      REASON_CRASH -> ExitReason.Crash
      REASON_CRASH_NATIVE -> ExitReason.NativeCrash
      REASON_ANR -> ExitReason.Anr
      REASON_INITIALIZATION_FAILURE -> ExitReason.StartFailed
      REASON_PERMISSION_CHANGE -> ExitReason.PermissionChanged
      REASON_EXCESSIVE_RESOURCE_USAGE -> ExitReason.ExcessiveResourceUse
      REASON_USER_REQUESTED -> ExitReason.UserStopped
      REASON_USER_STOPPED -> ExitReason.UserProfileStopped
      REASON_DEPENDENCY_DIED -> ExitReason.DependencyDied
      REASON_OTHER -> ExitReason.Other
      REASON_FREEZER -> ExitReason.Frozen
      REASON_PACKAGE_STATE_CHANGE -> ExitReason.PackageStateChanged
      REASON_PACKAGE_UPDATED -> ExitReason.AppUpdated
      else -> ExitReason.Unknown
    }
  }

// ApplicationExitInfo and RunningAppProcessInfo values, copied because ApplicationExitInfo needs
// API 30 and the library supports 26.
private const val REASON_EXIT_SELF = 1
private const val REASON_SIGNALED = 2
private const val REASON_LOW_MEMORY = 3
private const val REASON_CRASH = 4
private const val REASON_CRASH_NATIVE = 5
private const val REASON_ANR = 6
private const val REASON_INITIALIZATION_FAILURE = 7
private const val REASON_PERMISSION_CHANGE = 8
private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
private const val REASON_USER_REQUESTED = 10
private const val REASON_USER_STOPPED = 11
private const val REASON_DEPENDENCY_DIED = 12
private const val REASON_OTHER = 13
private const val REASON_FREEZER = 14
private const val REASON_PACKAGE_STATE_CHANGE = 15
private const val REASON_PACKAGE_UPDATED = 16
private const val REASON_SYSTEM_MEMORY_LIMIT = 17
private const val IMPORTANCE_FOREGROUND = 100
private const val IMPORTANCE_FOREGROUND_SERVICE = 125
private const val IMPORTANCE_VISIBLE = 200
private const val IMPORTANCE_PERCEPTIBLE = 230
private const val ANDROID_17 = 37
