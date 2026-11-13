package com.pedronveloso.logviewer

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Opt-in Timber capture, made with [Builder]. Call [install] once from the host Application. With
 * persistence enabled, construction starts one background journal initialization, even before
 * [install]. Startup emissions are buffered until disk storage is ready. Later emissions are synced
 * before Timber returns, which can slow logging on the calling thread.
 */
public class TimberLogCapture
internal constructor(
  context: Context,
  private val capacity: Int,
  private val persistAcrossCrashes: Boolean,
  private val redact: ((String) -> String)?,
  private val clock: () -> Long,
  private val ioDispatcher: CoroutineDispatcher,
  private val maxPendingBytes: Int,
  private val createJournal: suspend (Context, String) -> Journal,
) : LogSource {
  @Deprecated(
    "Use TimberLogCapture.Builder, which also configures process exit tracking.",
    ReplaceWith(
      "TimberLogCapture.Builder(context).capacity(capacity)" +
        ".persistAcrossCrashes(persistAcrossCrashes).redact(redact).clock(clock).build()",
    ),
  )
  public constructor(
    context: Context,
    capacity: Int = DEFAULT_CAPACITY,
    persistAcrossCrashes: Boolean = false,
    redact: ((String) -> String)? = null,
    clock: () -> Long = System::currentTimeMillis,
  ) : this(
    context,
    capacity,
    persistAcrossCrashes,
    redact,
    clock,
    Dispatchers.IO,
    MAX_PENDING_BYTES,
    { appContext, id -> DiskJournal(appContext, id) },
  ) {
    processExits = ProcessExitHistory.Builder(context).redact(redact).build()
  }

  /** The app's exit history, shown on the Status tab; null when [Builder.processExits] is off. */
  public var processExits: ProcessExitHistory? = null
    private set

  /**
   * Configures a capture. Every option has a default, so `Builder(context).build()` is a valid,
   * memory-only capture with process exit tracking on.
   */
  public class Builder(context: Context) {
    private val context = context.applicationContext
    private var capacity = DEFAULT_CAPACITY
    private var persistAcrossCrashes = false
    private var redact: ((String) -> String)? = null
    private var clock: () -> Long = System::currentTimeMillis
    private var processExitsEnabled = true
    private var configureProcessExits: ProcessExitHistory.Builder.() -> Unit = {}

    /** How many entries the current session keeps in memory. */
    public fun capacity(capacity: Int): Builder = apply {
      require(capacity > 0) { "capacity must be positive" }
      this.capacity = capacity
    }

    /** Also writes each session to disk, so the one that crashed can be read after a relaunch. */
    public fun persistAcrossCrashes(enabled: Boolean): Builder = apply {
      persistAcrossCrashes = enabled
    }

    /**
     * Applied to every message, tag and trace before it reaches memory or disk, and to exit
     * descriptions.
     */
    public fun redact(redact: ((String) -> String)?): Builder = apply { this.redact = redact }

    public fun clock(clock: () -> Long): Builder = apply { this.clock = clock }

    /**
     * Reads the exits Android recorded for the app and shows them on the Status tab, warning about
     * the reasons in [ProcessExitHistory.Builder.alertOn]. On by default; it only queries the
     * system. [configure] runs after the capture's redactor is applied, so it can replace it.
     */
    public fun processExits(
      enabled: Boolean = true,
      configure: ProcessExitHistory.Builder.() -> Unit = {},
    ): Builder = apply {
      processExitsEnabled = enabled
      configureProcessExits = configure
    }

    public fun build(): TimberLogCapture =
      TimberLogCapture(
          context,
          capacity,
          persistAcrossCrashes,
          redact,
          clock,
          Dispatchers.IO,
          MAX_PENDING_BYTES,
        ) { appContext, id ->
          DiskJournal(appContext, id)
        }
        .also { capture ->
          if (processExitsEnabled) {
            capture.processExits =
              ProcessExitHistory.Builder(context)
                .redact(redact)
                .apply(configureProcessExits)
                .build()
          }
        }
  }

  init {
    require(capacity > 0) { "capacity must be positive" }
    require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
  }

  private val appContext = context.applicationContext
  private val lock = Any()
  private val startedAt = clock()
  private val sessionId =
    String.format(Locale.US, "%013d", startedAt) + "-" + UUID.randomUUID().toString().take(8)
  private val memory = ArrayDeque<LogEntry>(capacity)
  private val pending = ArrayDeque<PendingEntry>()
  private var pendingBytes = 0
  private var nextId = 0L
  private var planted = false
  private var journal: Journal? = null
  private var diskWritable = true
  private val initialization = CompletableDeferred<Unit>()
  private val revision = MutableStateFlow(0L)
  override val changes: Flow<Unit> = revision.map {}
  private val mutableCapabilities =
    MutableStateFlow(
      LogCapabilities(true, persistAcrossCrashes, true, redact != null, isLibraryCapture = true),
    )
  override val capabilities: StateFlow<LogCapabilities> = mutableCapabilities.asStateFlow()
  private val mutableHealth = MutableStateFlow(LogHealth(installed = false))
  override val health: StateFlow<LogHealth> = mutableHealth.asStateFlow()

  private val tree =
    object : Timber.DebugTree() {
      override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        append(priority, tag, message, t)
      }
    }

  init {
    if (persistAcrossCrashes) {
      CoroutineScope(SupervisorJob() + ioDispatcher).launch { initializeJournal() }
    } else {
      initialization.complete(Unit)
    }
  }

  /** Plant only this tree; unrelated Timber trees remain the host app's responsibility. */
  public fun install(): Unit =
    synchronized(lock) {
      if (!planted) {
        Timber.plant(tree)
        planted = true
        mutableHealth.value = mutableHealth.value.copy(installed = true)
      }
    }

  public fun uninstall(): Unit =
    synchronized(lock) {
      if (planted) {
        Timber.uproot(tree)
        planted = false
        mutableHealth.value = mutableHealth.value.copy(installed = false)
      }
    }

  override suspend fun sessions(): List<LogSession> {
    initialization.await()
    return withContext(ioDispatcher) {
      synchronized(lock) {
        val current = LogSession(sessionId, startedAt, true)
        val diskSessions = runCatching {
          journal?.sessions().orEmpty()
        }
          .getOrElse {
            mutableHealth.value =
              mutableHealth.value.copy(readError = it.message ?: "Could not list sessions")
            emptyList()
          }
        listOf(current) + diskSessions.filterNot { it.id == sessionId }
      }
    }
  }

  override suspend fun entries(sessionId: String): List<LogEntry> {
    initialization.await()
    return withContext(ioDispatcher) {
      synchronized(lock) {
        val diskEntries = runCatching {
          journal?.read(sessionId).orEmpty()
        }
          .getOrElse {
            mutableHealth.value =
              mutableHealth.value.copy(readError = it.message ?: "Could not read logs")
            emptyList()
          }
        if (sessionId != this@TimberLogCapture.sessionId) diskEntries
        else (diskEntries + memory).associateBy(LogEntry::id).values.sortedBy(LogEntry::id)
      }
    }
  }

  override suspend fun clear(sessionId: String) {
    initialization.await()
    withContext(ioDispatcher) {
      synchronized(lock) {
        if (sessionId == this@TimberLogCapture.sessionId) memory.clear()
        runCatching { journal?.clear(sessionId) }
          .onFailure {
            mutableHealth.value =
              mutableHealth.value.copy(writeError = it.message ?: "Could not clear logs")
          }
        revision.value += 1
      }
    }
  }

  private suspend fun initializeJournal() {
    val initialized =
      try {
        createJournal(appContext, sessionId)
      } catch (failure: Exception) {
        synchronized(lock) {
          pending.clear()
          pendingBytes = 0
          mutableHealth.value =
            mutableHealth.value.copy(writeError = failure.message ?: "Could not prepare logs")
          initialization.complete(Unit)
        }
        return
      }
    synchronized(lock) { journal = initialized }
    while (true) {
      val next =
        synchronized(lock) {
          if (pending.isEmpty()) {
            initialization.complete(Unit)
            null
          } else {
            pending.removeFirst().also { pendingBytes -= it.bytes }
          }
        } ?: return
      try {
        initialized.write(next.entry)
      } catch (failure: Exception) {
        synchronized(lock) {
          pending.clear()
          pendingBytes = 0
          diskWritable = false
          mutableHealth.value =
            mutableHealth.value.copy(writeError = failure.message ?: "Could not write logs")
          initialization.complete(Unit)
        }
        return
      }
      synchronized(lock) {
        mutableHealth.value =
          mutableHealth.value.copy(lastDiskWriteMillis = next.entry.timestampMillis)
      }
    }
  }

  private fun append(priority: Int, tag: String?, message: String, throwable: Throwable?) {
    fun safeRedact(value: String): String = runCatching {
      (redact?.invoke(value) ?: value)
    }
      .getOrDefault("<redaction failed>")
    fun truncate(value: String): String =
      if (value.length > MAX_MESSAGE_CHARS) value.take(MAX_MESSAGE_CHARS) + "\n<truncated>"
      else value
    val stackTrace = throwable?.let {
      StringWriter(256).also { writer -> it.printStackTrace(PrintWriter(writer)) }.toString()
    }
    val hasExactSuffix =
      stackTrace != null &&
        message.length > stackTrace.length &&
        message[message.length - stackTrace.length - 1] == '\n' &&
        message.regionMatches(message.length - stackTrace.length, stackTrace, 0, stackTrace.length)
    val plainMessage =
      when {
        message == stackTrace -> ""
        hasExactSuffix -> message.substring(0, message.length - stackTrace.length - 1)
        else -> message
      }
    val entryMessage = truncate(safeRedact(plainMessage))
    val entryTag = tag?.let(::safeRedact)?.take(MAX_TAG_CHARS)
    val entryStackTrace = stackTrace?.let(::safeRedact)?.trimEnd()?.let(::truncate)
    synchronized(lock) {
      val entry =
        LogEntry(nextId++, sessionId, clock(), priority, entryTag, entryMessage, entryStackTrace)
      if (memory.size == capacity) memory.removeFirst()
      memory.addLast(entry)
      val activeJournal = journal
      if (activeJournal != null && initialization.isCompleted && diskWritable) {
        runCatching { activeJournal.write(entry) }
          .onSuccess {
            mutableHealth.value =
              mutableHealth.value.copy(lastDiskWriteMillis = entry.timestampMillis)
          }
          .onFailure {
            diskWritable = false
            mutableHealth.value =
              mutableHealth.value.copy(writeError = it.message ?: "Could not write logs")
          }
      } else if (persistAcrossCrashes && !initialization.isCompleted) {
        val bytes = DiskJournal.encodedRecordSize(entry)
        pending.addLast(PendingEntry(entry, bytes))
        pendingBytes += bytes
        var discarded = false
        while (pendingBytes > maxPendingBytes) {
          pendingBytes -= pending.removeFirst().bytes
          discarded = true
        }
        if (discarded) {
          mutableHealth.value = mutableHealth.value.copy(startupEntriesDropped = true)
        }
      }
      revision.value += 1
    }
  }

  private companion object {
    const val DEFAULT_CAPACITY = 1000
    const val MAX_PENDING_BYTES = 2 * 1024 * 1024
    const val MAX_MESSAGE_CHARS = 12_000
    const val MAX_TAG_CHARS = 256
  }

  private data class PendingEntry(val entry: LogEntry, val bytes: Int)
}
