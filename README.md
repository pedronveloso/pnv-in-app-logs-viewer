# In-app log viewer

A Compose log viewer for Android apps that use Timber. It can capture logs itself or display logs
from an app-provided `LogSource`. The library has no automatic initializer: adding the dependency
alone does not plant a tree or collect logs.

The repository has a `:logviewer` Android library and a `:sample` app. It uses AGP 9.4.1, Kotlin
2.4.21, Compose BOM 2026.09.00, Java 17 bytecode, and minSdk 26.

## Include in a host app

Use Maven Central (already included by default in most Android projects) and add the dependency
only to the debug variant:

```kotlin
dependencies {
    debugImplementation("io.github.pedronveloso:logviewer:0.11.0")
}
```

For local development against this repository instead of the published artifact, add a composite
build to the host's `settings.gradle.kts`:

```kotlin
includeBuild("../pnv-in-app-logs-viewer")
```

Keep all references to the library in `src/debug/`. If production code needs to reach a debug
menu, use a small interface or variant-specific entry point with a no-op `src/release/`
implementation. A release build then has no viewer classes, manifest provider, or viewer resources.
Apps that deliberately want production access may use `implementation` instead.

The published Maven group is independent of the library's Kotlin package,
`com.pedronveloso.logviewer`.

## Versioning

The library uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every change set
bumps the `:logviewer` version and keeps the dependency example above in sync. While the API is
pre-1.0, bump MINOR for new features or breaking changes and PATCH for fixes, documentation, or
tooling. From 1.0 onward, bump MAJOR for breaking API changes, MINOR for compatible features, and
PATCH for fixes, documentation, or tooling.
Use the highest applicable bump when a change set contains more than one kind of change.

`:logviewer` builds with Kotlin's [explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors)
(`kotlin { explicitApi() }`), so every public declaration must carry an explicit visibility
modifier. This keeps the public surface intentional ahead of the SemVer policy above.

## Publish a release

Follow the step-by-step [Maven Central release guide](docs/release.md). Only the `:logviewer`
release AAR is published. The project is licensed under [Apache 2.0](LICENSE).

## Capture with the library

Create one capture in the host `Application` and explicitly install it. Keep it somewhere the
debug screen can access it, such as a dependency-injection singleton:

```kotlin
val capture =
  TimberLogCapture.Builder(this)
    .persistAcrossCrashes(true)
    .redact(StandardLogRedactor::redact)
    .build()
capture.install()
```

Every builder option has a default, so `TimberLogCapture.Builder(this).build()` is a valid
memory-only capture. The older `TimberLogCapture(context, ...)` constructor still works but is
deprecated in favor of the builder.

Then render it inside the app's theme and navigation:

```kotlin
LogViewer(source = capture, onBack = onBack)
```

`persistAcrossCrashes` defaults to `false`. Memory keeps the newest 1,000 entries by default.
Persistent capture keeps at most two 1 MiB segments for each of the three newest process sessions
under the app's private, non-backed-up storage. Storage initializes in the background, so
construction starts one background journal initialization, even before `install()`, while neither
call performs disk I/O on its calling thread. Early logs are buffered and flushed in order;
a crash before initialization finishes can lose them. The startup buffer is limited to 2 MiB of
encoded entries, with older entries dropped and a distinct Status notice shown if it fills. Once
storage is ready, each accepted entry is synced before Timber returns, so logging can add disk
latency on its calling thread. Messages and separate throwable traces are each limited to 12,000 characters and
marked when truncated. An incomplete tail record after a crash is discarded while earlier complete
records remain readable. Disk failure leaves the in-memory store operating and appears on Status.
Persistence covers process crashes; it does not claim durability against device power loss.

`StandardLogRedactor` is an opt-in policy for URLs, common credential fields, Bearer and Basic
values, email addresses, and UUIDs. Pass `StandardLogRedactor::redact` as shown above to use it;
capture without a `redact` callback keeps text unchanged. The policy runs on messages, throwable
stack traces, and tags before either memory or disk storage. Pattern matching cannot recognize
every sensitive value, so avoid logging secrets and supply an app-specific callback if needed.
The callback should be fast and must not log through Timber, which would recurse. If it throws,
the affected field is replaced with `<redaction failed>`. Share uses captured entries;
sharing is a user action through an app-private temporary file and a non-exported `FileProvider`.
The viewer also remembers one UI preference, whether long lines wrap in the entry details, in a
private `SharedPreferences` file named `pnv_logviewer`; it never stores log content there.

## Process exits

From Android 11 (API 30), the Status tab also lists the exits Android recorded for the app
(`ApplicationExitInfo`): when and why each process ended, what that reason means, where the app was
(on screen, a foreground service, the background), Android's own description and the memory it
sampled. A process can't see its own death, so these are earlier sessions; read them next to the
persisted logs of the session that died. On older versions the section says it's unavailable.

Some exits are flagged: by default memory-limit kills, crashes, native crashes and ANRs. While one
is pending, the Status tab title shows a warning icon, and the exit gets a card with a Dismiss
button. Dismissing stores the newest reviewed exit's timestamp in the same `pnv_logviewer`
preferences, so it stays reviewed after a restart.

Tracking is on by default. It only queries the system, so it plants no tree and collects nothing.
Descriptions go through the capture's `redact` callback. Configure or turn it off on the builder:

```kotlin
TimberLogCapture.Builder(this)
  .processExits { alertOn(setOf(ExitReason.MemoryLimit)).maxExits(32) }
  .build()

TimberLogCapture.Builder(this).processExits(enabled = false).build()
```

`capture.processExits` exposes the history to the host app: `state` is a
`StateFlow<ProcessExitState>`, so the app's own UI can react, for example with a badge on its
debug menu entry, and `acknowledge()` marks everything pending as reviewed:

```kotlin
val exits = capture.processExits ?: return
val state by exits.state.collectAsStateWithLifecycle()
BadgedBox(badge = { if (state.pendingExits.isNotEmpty()) Badge() }) {
  Icon(Icons.Outlined.BugReport, contentDescription = "Logs")
}
```

Call `refresh()` to read the history again. An app that brings its own `LogSource` builds a
history with `ProcessExitHistory.Builder(context)` and passes it to
`LogViewer(source, onBack, processExits = history)`.

Android 17's memory limiter is documented as `REASON_OTHER` with `MemoryLimiter:AnonSwap` in the
description, but Android 17 builds record reason 17 ("SYSTEM MEMORY LIMIT EXCEEDED", described as
`[KILL PID] kill`), which the public SDK doesn't define. Both count as `ExitReason.MemoryLimit`.

To produce one, check that the device runs the limiter with `adb shell am memory-limiter status`; it
must report `enabled`. Some Android 17 builds ship without its config and report `disabled`, which a
`user` build can't change; use an Android 17 AVD with a Google APIs image instead. Then cap the
app's process and use it until Android stops it:

```sh
adb shell am memory-limiter manual -p $(adb shell pidof com.example.app) 300MB
```

Crossing the cap first only throttles the process; the kill comes once its anonymous memory plus
swap goes well past it, so lower the cap if nothing happens. `adb shell logcat -s MemoryLimiter`
shows the kill. The cap lasts for that process only.

## Use an existing store

Implement `LogSource` to adapt an app's in-memory or persistent Timber store. Supply immutable
session and entry snapshots, `capabilities` and `health` state flows, and a `changes` flow if new
entries should appear automatically. Return `null` for `changes` if the viewer should offer only
manual refresh. `LogEntry.id` must be unique and increasing within each session so list rows and
Follow remain stable. Set `canClear = false` for a read-only source. Set
`throwableStackTrace` when a source has separate exception details; in that case, keep the trace
out of `message`. Existing sources that include traces in `message` continue to display as supplied.

Status cannot inspect arbitrary Timber trees. For an external source it reports persistence and
redaction as claims made by the host app and displays any read or write errors the adapter exposes.
The viewer uses the host's `MaterialTheme`; it does not depend on Lazulite or AltSea styles.

## Adaptive viewer layout

The viewer adapts to the width available inside its host, including split-screen windows.
Below 600 dp it uses the phone layout and initial-letter severity filters. From 600 dp the
filters show full level names and wrap as needed. At 840 dp and wider, a compact log list sits
beside a persistent detail pane; narrower windows use the details sheet.

The detail pane starts with “Select a log entry”. Selecting an entry pauses Follow, and new
entries never replace the selection. Resume Follow to scroll to new logs while keeping the
inspected entry pinned. Filtering can hide the selected row without closing its details;
switching sessions, clearing the session, or removal of that entry clears the selection.
Details include the full message, separate exception trace, tag filtering, and the shared
“Wrap long lines” preference. The tablet detail pane also shows the session and elapsed time
since the previous visible entry; the phone details sheet retains its existing content.
Elapsed times in the list and tablet detail pane compare adjacent visible entries.

## Try the sample

Run `./gradlew :logviewer:testDebugUnitTest :sample:assembleDebug :sample:assembleRelease`, then
install and launch the debug sample. Generate logs, open Logs, and use search, severity chips,
Follow, share, and Status. Tap **Crash app to test recovery**, relaunch the sample, open
Logs, and select the previous session. The sample's release variant contains no viewer dependency
or viewer screen.

For R8 integration coverage, see [Testing the optimized sample](docs/r8-testing.md).
