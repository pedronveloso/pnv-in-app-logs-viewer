@file:Suppress(
  "DEPRECATION",
) // Also covers the deprecated constructor, which existing apps still call.

package com.pedronveloso.logviewer

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimberLogCaptureTest {
  private lateinit var context: Context
  private lateinit var directory: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    directory = File(context.noBackupFilesDir, "pnv-logviewer")
    directory.deleteRecursively()
    Timber.uprootAll()
  }

  @After
  fun tearDown() {
    Timber.uprootAll()
    directory.deleteRecursively()
  }

  @Test
  fun `bounded memory keeps latest emissions and redacts before storage`() = runBlocking {
    val capture =
      TimberLogCapture(context, capacity = 2, redact = { it.replace("secret", "hidden") })
    capture.install()
    repeat(3) { Timber.tag("Key").d("secret %d", it) }

    val entries = capture.entries(capture.sessions().first().id)
    assertThat(entries.map(LogEntry::id)).containsExactly(1L, 2L).inOrder()
    assertThat(entries.map(LogEntry::message)).containsExactly("hidden 1", "hidden 2").inOrder()
    assertThat(entries.map(LogEntry::tag)).containsExactly("Key", "Key")
    Unit
  }

  @Test
  fun `standard policy redacts tags messages and throwable text before persistence`() =
    runBlocking {
      val capture =
        TimberLogCapture(
          context,
          persistAcrossCrashes = true,
          clock = { 1000L },
          redact = StandardLogRedactor::redact,
        )
      capture.install()
      Timber.tag("person@example.com")
        .e(
          IllegalStateException("password=hunter2"),
          "Visited https://example.com/private?token=secret123",
        )
      capture.uninstall()

      assertThat(capture.capabilities.value.redactionConfigured).isTrue()
      val restarted = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 2000L })
      val previous = restarted.sessions().single { !it.isCurrent }
      val stored = restarted.entries(previous.id).single()
      assertThat(stored.tag).isEqualTo("<redacted>")
      assertThat(stored.message).contains("https://example.com/<redacted>")
      assertThat(stored.message).doesNotContain("password=hunter2")
      assertThat(stored.throwableStackTrace).contains("password=<redacted>")
      assertThat(stored.throwableStackTrace).doesNotContain("hunter2")
      val exported = formatLogEntries(listOf(stored))
      assertThat(exported).doesNotContain("person@example.com")
      assertThat(exported).doesNotContain("secret123")
      assertThat(exported).doesNotContain("hunter2")
    }

  @Test
  fun `capture without a callback keeps existing opt in behavior`() = runBlocking {
    val capture = TimberLogCapture(context)
    capture.install()
    Timber.tag("Private").d("password=hunter2")

    assertThat(capture.capabilities.value.redactionConfigured).isFalse()
    assertThat(capture.entries(capture.sessions().first().id).single().message)
      .isEqualTo("password=hunter2")
  }

  @Test
  fun `concurrent emissions have stable unique IDs`() = runBlocking {
    val capture = TimberLogCapture(context, capacity = 4000)
    capture.install()
    val start = CountDownLatch(1)
    val writers =
      (0 until 8).map { writer ->
        thread {
          start.await()
          repeat(250) { Timber.d("writer %d entry %d", writer, it) }
        }
      }
    start.countDown()
    writers.forEach(Thread::join)

    val ids = capture.entries(capture.sessions().first().id).map(LogEntry::id)
    assertThat(ids).hasSize(2000)
    assertThat(ids.toSet()).hasSize(2000)
    assertThat(ids).isInStrictOrder()
  }

  @Test
  fun `construction and install do not prepare disk on the caller thread`() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val initializerThread = AtomicReference<Thread>()
    val callerThread = Thread.currentThread()
    val capture =
      TimberLogCapture(
        context,
        10,
        true,
        { it.replace("secret", "hidden") },
        { 1000L },
        Dispatchers.IO,
        2 * 1024 * 1024,
        { appContext, id ->
          initializerThread.set(Thread.currentThread())
          gate.await()
          DiskJournal(appContext, id)
        },
      )
    try {
      capture.install()
      Timber.tag("Early").d("secret")
      assertThat(directory.exists()).isFalse()

      gate.complete(Unit)
      val session = withTimeout(5_000) { capture.sessions().single() }
      assertThat(initializerThread.get()).isNotSameInstanceAs(callerThread)
      assertThat(capture.entries(session.id).map(LogEntry::message)).containsExactly("hidden")

      Timber.tag("Early").d("after ready")
      assertThat(DiskJournal(context, session.id).read(session.id).map(LogEntry::message))
        .containsExactly("hidden", "after ready")
        .inOrder()
    } finally {
      gate.complete(Unit)
      capture.uninstall()
    }
  }

  @Test
  fun `startup buffer drops oldest entries and reports the loss`() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val message = "a".repeat(80)
    val recordSize =
      DiskJournal.encodedRecordSize(LogEntry(0, "unused", 1000L, Log.DEBUG, "Buffer", message))
    val capture =
      TimberLogCapture(
        context,
        10,
        true,
        null,
        { 1000L },
        Dispatchers.IO,
        recordSize,
        { appContext, id ->
          gate.await()
          DiskJournal(appContext, id)
        },
      )
    try {
      capture.install()
      repeat(3) { Timber.tag("Buffer").d("%d%s", it, message.drop(1)) }
      assertThat(capture.health.value.startupEntriesDropped).isTrue()
      assertThat(capture.health.value.writeError).isNull()

      gate.complete(Unit)
      val session = withTimeout(5_000) { capture.sessions().single() }
      assertThat(capture.entries(session.id).map(LogEntry::id))
        .containsExactly(0L, 1L, 2L)
        .inOrder()
      assertThat(DiskJournal(context, session.id).read(session.id).map(LogEntry::id))
        .containsExactly(2L)
      assertThat(capture.health.value.startupEntriesDropped).isTrue()
      assertThat(capture.health.value.writeError).isNull()
    } finally {
      gate.complete(Unit)
      capture.uninstall()
    }
  }

  @Test
  fun `large startup buffer finishes flushing in capture order`() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val capture =
      TimberLogCapture(
        context,
        100,
        true,
        null,
        { 1000L },
        Dispatchers.IO,
        2 * 1024 * 1024,
        { appContext, id ->
          gate.await()
          DiskJournal(appContext, id)
        },
      )
    try {
      capture.install()
      repeat(70) { Timber.tag("Startup").d("entry %d", it) }
      gate.complete(Unit)
      val session = withTimeout(5_000) { capture.sessions().single() }
      Timber.tag("Startup").d("after ready")
      assertThat(DiskJournal(context, session.id).read(session.id).map(LogEntry::id))
        .containsExactlyElementsIn((0L..70L).toList())
        .inOrder()
    } finally {
      gate.complete(Unit)
      capture.uninstall()
    }
  }

  @Test
  fun `emission during blocked 65th flush write returns and drains in order`() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val writing65 = CountDownLatch(1)
    val release65 = CountDownLatch(1)
    val capture =
      TimberLogCapture(context, 100, true, null, { 1000L }, Dispatchers.IO, 2 * 1024 * 1024) {
        appContext,
        id ->
        gate.await()
        val delegate = DiskJournal(appContext, id)
        object : Journal by delegate {
          override fun write(entry: LogEntry) {
            if (entry.id == 64L) {
              writing65.countDown()
              check(release65.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            delegate.write(entry)
          }
        }
      }
    try {
      capture.install()
      repeat(70) { Timber.d("entry %d", it) }
      gate.complete(Unit)
      assertThat(writing65.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
      val emission = async(Dispatchers.Default) { Timber.d("during flush") }
      withTimeout(2_000) { emission.await() }
      release65.countDown()
      val session = withTimeout(5_000) { capture.sessions().single() }
      assertThat(DiskJournal(context, session.id).read(session.id).map(LogEntry::id))
        .containsExactlyElementsIn((0L..70L).toList())
        .inOrder()
    } finally {
      release65.countDown()
      gate.complete(Unit)
      capture.uninstall()
    }
  }

  @Test
  fun `initialization failure leaves redacted memory available`() = runBlocking {
    val capture =
      TimberLogCapture(
        context,
        10,
        true,
        { it.replace("secret", "hidden") },
        { 1000L },
        Dispatchers.IO,
        2 * 1024 * 1024,
        { _, _ -> throw IOException("Storage unavailable") },
      )
    capture.install()
    try {
      Timber.tag("Failure").e("secret")
      val session = withTimeout(5_000) { capture.sessions().single() }
      assertThat(capture.entries(session.id).map(LogEntry::message)).containsExactly("hidden")
      assertThat(capture.health.value.writeError).contains("Storage unavailable")
    } finally {
      capture.uninstall()
    }
  }

  @Test
  fun `buffer flush failure completes initialization and keeps memory available`() = runBlocking {
    val gate = CompletableDeferred<Unit>()
    val capture =
      TimberLogCapture(
        context,
        10,
        true,
        null,
        { 1000L },
        Dispatchers.IO,
        2 * 1024 * 1024,
        { appContext, id ->
          gate.await()
          DiskJournal(appContext, id).also {
            directory.deleteRecursively()
            directory.writeText("blocked")
          }
        },
      )
    try {
      capture.install()
      Timber.tag("Flush").d("Buffered entry")
      gate.complete(Unit)
      val session = withTimeout(5_000) { capture.sessions().single() }
      assertThat(capture.entries(session.id).map(LogEntry::message))
        .containsExactly("Buffered entry")
      assertThat(capture.health.value.writeError).isNotNull()
    } finally {
      gate.complete(Unit)
      capture.uninstall()
    }
  }

  @Test
  fun `reads and clear wait for initialization without blocking the caller`() = runBlocking {
    val oldId = "0000000001000-1234abcd"
    DiskJournal(context, oldId).write(LogEntry(0, oldId, 1000L, Log.DEBUG, "Old", "old log"))
    val gate = CompletableDeferred<Unit>()
    val capture =
      TimberLogCapture(
        context,
        10,
        true,
        null,
        { 2000L },
        Dispatchers.IO,
        2 * 1024 * 1024,
        { appContext, id ->
          gate.await()
          DiskJournal(appContext, id)
        },
      )
    val sessions = async(start = CoroutineStart.UNDISPATCHED) { capture.sessions() }
    val entries = async(start = CoroutineStart.UNDISPATCHED) { capture.entries(oldId) }
    val clearId = "0000000001001-1234abcd"
    DiskJournal(context, clearId).write(LogEntry(0, clearId, 1001L, Log.DEBUG, "Old", "clear me"))
    val clear = async(start = CoroutineStart.UNDISPATCHED) { capture.clear(clearId) }
    assertThat(sessions.isCompleted).isFalse()
    assertThat(entries.isCompleted).isFalse()
    assertThat(clear.isCompleted).isFalse()

    gate.complete(Unit)
    val returnedSessions = withTimeout(5_000) { sessions.await() }
    val returnedEntries = withTimeout(5_000) { entries.await() }
    withTimeout(5_000) { clear.await() }
    assertThat(returnedSessions.map(LogSession::id)).contains(oldId)
    assertThat(returnedEntries.map(LogEntry::message)).containsExactly("old log")
    assertThat(DiskJournal(context, clearId).read(clearId)).isEmpty()
  }

  @Test
  fun `disk entries survive a new capture after process restart`() = runBlocking {
    val first = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 1000L })
    first.install()
    Timber.tag("CrashTest").e(IllegalStateException("boom"), "Before crash")
    first.uninstall()
    first.sessions()

    val restarted = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 2000L })
    val previous = restarted.sessions().single { !it.isCurrent }
    val restored = restarted.entries(previous.id).single()
    assertThat(restored.tag).isEqualTo("CrashTest")
    assertThat(restored.priority).isEqualTo(Log.ERROR)
    assertThat(restored.message).contains("Before crash")
    assertThat(restored.message).doesNotContain("java.lang.IllegalStateException: boom")
    assertThat(restored.throwableStackTrace).contains("java.lang.IllegalStateException: boom")
    assertThat(formatLogEntries(listOf(restored)).split("java.lang.IllegalStateException: boom"))
      .hasSize(2)
  }

  @Test
  fun `mismatched throwable formatting retains separately redacted trace`() = runBlocking {
    val capture =
      TimberLogCapture(
        context,
        persistAcrossCrashes = true,
        clock = { 6000L },
        redact = { it.replace("secret", "hidden") },
      )
    val sessionId = capture.sessions().first().id
    val append =
      TimberLogCapture::class
        .java
        .getDeclaredMethod(
          "append",
          Int::class.javaPrimitiveType,
          String::class.java,
          String::class.java,
          Throwable::class.java,
        )
        .apply { isAccessible = true }
    append.invoke(
      capture,
      Log.ERROR,
      "Trace",
      "secret: differently formatted exception",
      IllegalStateException("secret"),
    )
    val stored = DiskJournal(context, sessionId).read(sessionId).single()
    assertThat(stored.message).isEqualTo("hidden: differently formatted exception")
    assertThat(stored.throwableStackTrace).contains("IllegalStateException: hidden")
    assertThat(stored.throwableStackTrace).doesNotContain("secret")
  }

  @Test
  fun `journal six field round trip and encoded size include unicode`() {
    val sessionId = "0000000001000-1234abcd"
    val journal = DiskJournal(context, sessionId)
    val entry = LogEntry(123, sessionId, 456, Log.ERROR, "Täg", "Message 🧪", "Trace é")
    val predictedSize = DiskJournal.encodedRecordSize(entry)
    journal.write(entry)
    assertThat(File(directory, "s-$sessionId.0").length()).isEqualTo(predictedSize.toLong())
    assertThat(journal.read(sessionId)).containsExactly(entry)
  }

  @Test
  fun `uninstall only detaches its own tree and can be repeated`() = runBlocking {
    val other = object : Timber.DebugTree() {}
    Timber.plant(other)
    val capture = TimberLogCapture(context)
    capture.install()
    capture.install()
    capture.uninstall()
    capture.uninstall()
    assertThat(Timber.forest()).containsExactly(other)
    assertThat(capture.health.value.installed).isFalse()
  }

  @Test
  fun `legacy disk records retain combined messages`() = runBlocking {
    val capture = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 5000L })
    val sessionId = capture.sessions().first().id
    val message = "Before crash\njava.lang.IllegalStateException: boom"
    val encode = Base64.getEncoder()
    val payload =
      listOf(
          "0",
          "5000",
          Log.ERROR.toString(),
          encode.encodeToString("Legacy".toByteArray(StandardCharsets.UTF_8)),
          encode.encodeToString(message.toByteArray(StandardCharsets.UTF_8)),
        )
        .joinToString("\t")
        .toByteArray(StandardCharsets.UTF_8)
    RandomAccessFile(File(directory, "s-$sessionId.0"), "rw").use {
      it.writeInt(payload.size)
      it.writeInt(CRC32().apply { update(payload) }.value.toInt())
      it.write(payload)
    }

    val restored = capture.entries(sessionId).single()
    assertThat(restored.message).isEqualTo(message)
    assertThat(restored.throwableStackTrace).isNull()
    assertThat(formatLogEntries(listOf(restored))).contains(message)
  }

  @Test
  fun `incomplete tail record is ignored after restart`() = runBlocking {
    val first = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 3000L })
    first.install()
    Timber.i("Complete record")
    first.uninstall()
    val oldId = first.sessions().first().id
    RandomAccessFile(File(directory, "s-$oldId.0"), "rw").use {
      it.seek(it.length())
      it.writeInt(100)
      it.writeInt(0)
      it.write(byteArrayOf(1, 2, 3))
    }

    val restarted = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 4000L })
    assertThat(restarted.entries(oldId).map(LogEntry::message)).containsExactly("Complete record")
    Unit
  }

  @Test
  fun `disk segments and retained sessions stay bounded`() = runBlocking {
    repeat(4) { session ->
      val capture =
        TimberLogCapture(context, persistAcrossCrashes = true, clock = { (session + 1) * 1000L })
      capture.install()
      repeat(160) { Timber.d("%s", "x".repeat(10_000)) }
      capture.uninstall()
      capture.sessions()
    }
    val current = TimberLogCapture(context, persistAcrossCrashes = true, clock = { 5000L })
    assertThat(current.sessions()).hasSize(3)
    assertThat(
        directory.listFiles().orEmpty().filter { it.name.endsWith(".0") || it.name.endsWith(".1") },
      )
      .hasSize(5)
    assertThat(directory.listFiles().orEmpty().all { it.length() <= 1024 * 1024 }).isTrue()
  }

  @Test
  fun `clear removes selected session from memory and disk`() = runBlocking {
    val capture = TimberLogCapture(context, persistAcrossCrashes = true)
    capture.install()
    Timber.d("Clear me")
    val sessionId = capture.sessions().first().id
    capture.clear(sessionId)
    assertThat(capture.entries(sessionId)).isEmpty()
    assertThat(directory.listFiles().orEmpty().filter { it.name.contains(sessionId) }).isEmpty()
  }

  @Test
  fun `disk write failure keeps in-memory capture available`() = runBlocking {
    val capture = TimberLogCapture(context, persistAcrossCrashes = true)
    capture.install()
    capture.sessions()
    directory.deleteRecursively()
    directory.writeText("blocked")

    Timber.w("Still captured")

    val current = capture.sessions().first().id
    assertThat(capture.entries(current).map(LogEntry::message)).containsExactly("Still captured")
    assertThat(capture.health.value.writeError).isNotNull()
  }

  @Test
  fun `write failure retains older sessions for reading and clearing`() = runBlocking {
    val oldId = "0000000001000-1234abcd"
    DiskJournal(context, oldId).write(LogEntry(0, oldId, 1000L, Log.DEBUG, "Old", "disk only"))
    val capture =
      TimberLogCapture(context, 1, true, null, { 2000L }, Dispatchers.IO, 2 * 1024 * 1024) {
        appContext,
        id ->
        val delegate = DiskJournal(appContext, id)
        object : Journal by delegate {
          override fun write(entry: LogEntry) {
            if (entry.id == 1L) throw IOException("Disk full")
            delegate.write(entry)
          }
        }
      }
    capture.install()
    try {
      val currentId = capture.sessions().first().id
      Timber.d("first")
      Timber.d("second")
      assertThat(capture.health.value.writeError).contains("Disk full")
      assertThat(capture.entries(currentId).map(LogEntry::message))
        .containsExactly("first", "second")
        .inOrder()
      assertThat(capture.sessions().map(LogSession::id)).contains(oldId)
      assertThat(capture.entries(oldId).map(LogEntry::message)).containsExactly("disk only")
      capture.clear(oldId)
      assertThat(capture.entries(oldId)).isEmpty()
    } finally {
      capture.uninstall()
    }
  }
}
