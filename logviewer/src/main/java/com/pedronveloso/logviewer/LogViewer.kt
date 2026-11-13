package com.pedronveloso.logviewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ExpandedLayoutMinWidth = 840.dp
private val FullLevelNamesMinWidth = 600.dp
private const val LOG_LIST_PANE_WEIGHT = 0.56f
private const val LOG_DETAILS_PANE_WEIGHT = 0.44f

/**
 * Host-themed, navigation-agnostic log viewer. Pass one source per installation. [processExits]
 * fills the Status tab's exit section; a library capture brings its own, and an app-provided source
 * can pass one built with [ProcessExitHistory.Builder].
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
public fun LogViewer(
  source: LogSource,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  processExits: ProcessExitHistory? = (source as? TimberLogCapture)?.processExits,
) {
  val capabilities by source.capabilities.collectAsState()
  val exitState by
    produceState<ProcessExitState?>(null, processExits) {
      processExits?.state?.collect { value = it }
    }
  val pendingExits = exitState?.pendingExits.orEmpty()
  val liveUpdates = capabilities.hasLiveUpdates && source.changes != null
  val effectiveCapabilities = capabilities.copy(hasLiveUpdates = liveUpdates)
  val health by source.health.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var sessions by remember(source) { mutableStateOf<List<LogSession>>(emptyList()) }
  var selectedSession by rememberSaveable(source) { mutableStateOf<String?>(null) }
  var allEntries by remember(source) { mutableStateOf<List<LogEntry>>(emptyList()) }
  var filter by
    rememberSaveable(
      stateSaver =
        listSaver(
          save = { listOf(it.minimumLevel.name, it.query) },
          restore = { LogFilter(LogLevel.valueOf(it[0]), it[1]) },
        ),
    ) {
      mutableStateOf(LogFilter())
    }
  var follow by rememberSaveable(source) { mutableStateOf(true) }
  var refresh by remember { mutableIntStateOf(0) }
  var error by remember { mutableStateOf<String?>(null) }
  // Save selection identifiers rather than serializing log entries or traces.
  var selectedEntryKey by
    rememberSaveable(
      source,
      stateSaver =
        listSaver<Pair<String, Long>?, Any>(
          save = { key -> key?.let { listOf(it.first, it.second) }.orEmpty() },
          restore = { if (it.isEmpty()) null else it[0] as String to it[1] as Long },
        ),
    ) {
      mutableStateOf<Pair<String, Long>?>(null)
    }
  val selectedEntry = allEntries.firstOrNull { (it.sessionId to it.id) == selectedEntryKey }
  val preferences = remember(context) { LogViewerPreferences(context) }
  var wrapLines by remember { mutableStateOf(false) }
  LaunchedEffect(preferences) {
    wrapLines = withContext(Dispatchers.IO) { preferences.wrapLongLines }
  }
  val listState = rememberLazyListState()
  val isDragged by listState.interactionSource.collectIsDraggedAsState()
  val filtered = remember(allEntries, filter) { allEntries.filter { it.matches(filter) } }
  val warningCount =
    remember(allEntries) { allEntries.count { it.priority == LogLevel.WARN.priority } }
  val errorCount =
    remember(allEntries) { allEntries.count { it.priority == LogLevel.ERROR.priority } }
  val canFollow =
    liveUpdates && sessions.firstOrNull { it.id == selectedSession }?.isCurrent == true

  LaunchedEffect(source, selectedSession, refresh) {
    suspend fun reload() {
      runCatching {
        withContext(Dispatchers.IO) {
          val available = source.sessions()
          val chosen =
            selectedSession?.takeIf { id -> available.any { it.id == id } }
              ?: available.firstOrNull { it.isCurrent }?.id
              ?: available.firstOrNull()?.id
          Triple(available, chosen, chosen?.let { source.entries(it) }.orEmpty())
        }
      }
        .onSuccess { (available, chosen, entries) ->
          sessions = available
          if (selectedSession != chosen) {
            selectedSession = chosen
            selectedEntryKey = null
          }
          if (entries.none { (it.sessionId to it.id) == selectedEntryKey }) {
            selectedEntryKey = null
          }
          allEntries = entries
          error = null
        }
        .onFailure {
          if (it is CancellationException) throw it
          error = it.message ?: "Could not load logs"
        }
    }
    reload()
    source.changes?.sample(250.milliseconds)?.collect { reload() }
  }

  LaunchedEffect(listState, isDragged) {
    if (isDragged)
      snapshotFlow { listState.firstVisibleItemIndex }
        .collect {
          val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
          if (lastVisible != null && lastVisible < listState.layoutInfo.totalItemsCount - 2)
            follow = false
        }
  }
  LaunchedEffect(selectedSession, filtered.lastOrNull()?.id, follow, tab) {
    if (tab == 0 && follow && filtered.isNotEmpty()) {
      listState.animateScrollToItem(filtered.lastIndex)
    }
  }

  BoxWithConstraints(modifier) {
    val expanded = maxWidth >= ExpandedLayoutMinWidth
    val fullLevelNames = maxWidth >= FullLevelNamesMinWidth
    val onWrapLinesChange: (Boolean) -> Unit = {
      wrapLines = it
      preferences.wrapLongLines = it
    }
    val onSelectEntry: (LogEntry) -> Unit = {
      val key = it.sessionId to it.id
      if (selectedEntryKey != key) {
        selectedEntryKey = key
        follow = false
      }
    }
    Scaffold(
      topBar = {
        LogViewerTopBar(
          showActions = tab == 0,
          canClear = capabilities.canClear && selectedSession != null,
          onBack = onBack,
          canRefresh = !liveUpdates,
          onRefresh = { refresh++ },
          onShare = {
            runCatching { shareLogs(context, formatLogEntries(filtered)) }
              .onFailure { error = it.message ?: "Could not share logs" }
          },
          onClear = {
            selectedSession?.let { sessionId ->
              scope.launch {
                runCatching { source.clear(sessionId) }
                  .onSuccess {
                    if (selectedEntryKey?.first == sessionId) selectedEntryKey = null
                    refresh++
                  }
                  .onFailure { error = it.message ?: "Could not clear logs" }
              }
            }
          },
          selectedTab = if (expanded) tab else null,
          onSelectTab = { tab = it },
          pendingExits = pendingExits.size,
        )
      },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        if (!expanded) {
          LogViewerTabs(
            selectedTab = tab,
            onSelectTab = { tab = it },
            pendingExits = pendingExits.size,
          )
        }
        error?.let {
          Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        if (tab == 1) {
          StatusContent(
            effectiveCapabilities,
            health,
            Modifier.fillMaxSize(),
            exitState = exitState,
            onDismissExits = { processExits?.let { scope.launch { it.acknowledge() } } },
          )
        } else {
          val onSelectSession: (LogSession) -> Unit = {
            if (selectedSession != it.id) {
              selectedEntryKey = null
              allEntries = emptyList()
            }
            selectedSession = it.id
            follow = it.isCurrent
          }
          if (expanded) {
            Row(
              Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              SessionSelector(
                sessions,
                selectedSession,
                onSelectSession,
                Modifier.widthIn(max = 240.dp),
              )
              LogSearchField(
                filter.query,
                { filter = filter.copy(query = it) },
                Modifier.weight(1f),
              )
              FollowChip(follow, canFollow, { follow = it })
            }
          } else {
            SessionSelector(
              sessions,
              selectedSession,
              onSelectSession,
              Modifier.padding(start = 16.dp, top = 8.dp),
            )
            LogSearchField(
              filter.query,
              { filter = filter.copy(query = it) },
              Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
          }
          LogFilterRow(
            selectedLevel = filter.minimumLevel,
            warningCount = warningCount,
            errorCount = errorCount,
            follow = follow,
            canFollow = canFollow,
            onSelectLevel = { filter = filter.copy(minimumLevel = it) },
            onFollowChange = { follow = it },
            fullLevelNames = fullLevelNames,
            showFollow = !expanded,
          )
          Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
              if (expanded) Modifier.weight(LOG_LIST_PANE_WEIGHT) else Modifier.fillMaxWidth(),
            ) {
              Text(
                "${filtered.size} of ${allEntries.size} entries",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
              )
              LogList(
                entries = filtered,
                noEntries = allEntries.isEmpty(),
                listState = listState,
                compact = expanded,
                selectedEntryKey = selectedEntryKey,
                onSelectEntry = onSelectEntry,
              )
            }
            if (expanded) {
              VerticalDivider()
              val selectedIndex = filtered.indexOfFirst {
                (it.sessionId to it.id) == selectedEntryKey
              }
              LogDetailsPane(
                entry = selectedEntry,
                session = sessions.firstOrNull { it.id == selectedSession },
                previousTimestamp = filtered.getOrNull(selectedIndex - 1)?.timestampMillis,
                hiddenByFilters = selectedEntry != null && selectedIndex < 0,
                wrapLines = wrapLines,
                onWrapLinesChange = onWrapLinesChange,
                onFilterLikeThis = {
                  filter = filter.copy(query = selectedEntry?.tag.orEmpty())
                },
                modifier = Modifier.weight(LOG_DETAILS_PANE_WEIGHT).fillMaxSize(),
              )
            }
          }
        }
      }
    }
    if (!expanded && tab == 0) {
      selectedEntry?.let { entry ->
        LogDetailsSheet(
          entry = entry,
          wrapLines = wrapLines,
          onWrapLinesChange = onWrapLinesChange,
          onDismiss = { selectedEntryKey = null },
          onFilterLikeThis = {
            filter = filter.copy(query = entry.tag.orEmpty())
            selectedEntryKey = null
          },
        )
      }
    }
  }
}

@Composable
private fun LogList(
  entries: List<LogEntry>,
  noEntries: Boolean,
  listState: LazyListState,
  compact: Boolean,
  selectedEntryKey: Pair<String, Long>?,
  onSelectEntry: (LogEntry) -> Unit,
) {
  if (entries.isEmpty()) {
    LogEmptyState(noEntries)
  } else {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val stackMetadata = shouldStackTabletMetadata(maxWidth, LocalDensity.current.fontScale)
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("log_list"),
        contentPadding = PaddingValues(LogListContentPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        itemsIndexed(entries, key = { _, entry -> "${entry.sessionId}:${entry.id}" }) { index, entry
          ->
          val previousTimestamp = entries.getOrNull(index - 1)?.timestampMillis
          if (compact) {
            TabletLogRow(
              entry = entry,
              previousTimestamp = previousTimestamp,
              stackMetadata = stackMetadata,
              selected = (entry.sessionId to entry.id) == selectedEntryKey,
              onClick = { onSelectEntry(entry) },
            )
          } else {
            LogRow(entry, previousTimestamp, onClick = { onSelectEntry(entry) })
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewerTopBar(
  showActions: Boolean,
  canClear: Boolean,
  canRefresh: Boolean,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onShare: () -> Unit,
  onClear: () -> Unit,
  selectedTab: Int? = null,
  onSelectTab: (Int) -> Unit = {},
  pendingExits: Int = 0,
) {
  TopAppBar(
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Logs")
        if (selectedTab != null) {
          LogViewerTabs(
            selectedTab,
            onSelectTab,
            Modifier.padding(start = 24.dp).width(240.dp),
            pendingExits = pendingExits,
          )
        }
      }
    },
    navigationIcon = {
      IconButton(
        onClick = onBack,
        modifier = Modifier.semantics { contentDescription = "Go back" },
      ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
      }
    },
    actions = {
      if (showActions) {
        if (canRefresh) {
          IconButton(
            onClick = onRefresh,
            modifier =
              Modifier.testTag("refresh_logs").semantics { contentDescription = "Refresh logs" },
          ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
          }
        }
        IconButton(
          onClick = onShare,
          modifier = Modifier.semantics { contentDescription = "Share visible logs" },
        ) {
          Icon(Icons.Default.Share, contentDescription = null)
        }
        if (canClear) {
          IconButton(
            onClick = onClear,
            modifier = Modifier.semantics { contentDescription = "Clear logs in selected session" },
          ) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null)
          }
        }
      }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogViewerTabs(
  selectedTab: Int,
  onSelectTab: (Int) -> Unit,
  modifier: Modifier = Modifier,
  pendingExits: Int = 0,
) {
  PrimaryTabRow(selectedTabIndex = selectedTab, modifier = modifier) {
    Tab(
      selected = selectedTab == 0,
      onClick = { onSelectTab(0) },
      text = { Text("Logs") },
      modifier = Modifier.testTag("logs_tab").semantics { contentDescription = "Show logs" },
    )
    Tab(
      selected = selectedTab == 1,
      onClick = { onSelectTab(1) },
      text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (pendingExits > 0) {
            Icon(
              Icons.Outlined.WarningAmber,
              contentDescription = null,
              tint = problemColor(),
              modifier = Modifier.padding(end = 6.dp).size(18.dp).testTag("status_tab_warning"),
            )
          }
          Text("Status")
        }
      },
      modifier =
        Modifier.testTag("status_tab").semantics {
          contentDescription =
            if (pendingExits > 0) {
              "Show capture status, $pendingExits ${if (pendingExits == 1) "exit" else "exits"} to review"
            } else {
              "Show capture status"
            }
        },
    )
  }
}

@Composable
private fun SessionSelector(
  sessions: List<LogSession>,
  selectedSessionId: String?,
  onSelectSession: (LogSession) -> Unit,
  modifier: Modifier = Modifier,
) {
  var sessionMenu by remember { mutableStateOf(false) }
  Box(modifier) {
    val selected = sessions.firstOrNull { it.id == selectedSessionId }
    TextButton(
      onClick = { sessionMenu = true },
      modifier =
        Modifier.testTag("session_selector").semantics {
          contentDescription =
            when {
              selected == null -> "Select log session"
              selected.isCurrent -> "Select log session, current session"
              else ->
                "Select log session, previous session from ${formatSessionDate(selected.startedAtMillis)}"
            }
        },
    ) {
      Text(
        if (selected?.isCurrent == true) "Current session ▾"
        else "Previous session: ${selected?.startedAtMillis?.let(::formatSessionDate).orEmpty()} ▾",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
      sessions.forEach { session ->
        DropdownMenuItem(
          modifier =
            Modifier.testTag("session_${session.id}").semantics {
              contentDescription =
                if (session.isCurrent) "Select current session"
                else "Select session from ${formatSessionDate(session.startedAtMillis)}"
            },
          text = {
            Text(
              if (session.isCurrent) "Current session"
              else formatSessionDate(session.startedAtMillis),
            )
          },
          onClick = {
            onSelectSession(session)
            sessionMenu = false
          },
        )
      }
    }
  }
}

@Composable
private fun LogSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    label = { Text("Filter by tag or message") },
    trailingIcon =
      if (query.isNotEmpty()) {
        {
          TextButton(
            onClick = { onQueryChange("") },
            modifier =
              Modifier.testTag("clear_log_search").semantics {
                contentDescription = "Clear filter text"
              },
          ) {
            Text("Clear")
          }
        }
      } else null,
    singleLine = true,
    modifier = modifier.testTag("log_search"),
  )
}

@Composable
private fun LogFilterRow(
  selectedLevel: LogLevel,
  warningCount: Int,
  errorCount: Int,
  follow: Boolean,
  canFollow: Boolean,
  onSelectLevel: (LogLevel) -> Unit,
  onFollowChange: (Boolean) -> Unit,
  fullLevelNames: Boolean = false,
  showFollow: Boolean = true,
) {
  val chips: @Composable () -> Unit = {
    LogLevel.entries.forEach { level ->
      LogLevelChip(level, selectedLevel, warningCount, errorCount, fullLevelNames, onSelectLevel)
    }
  }
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (fullLevelNames) {
      FlowRow(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = { chips() },
      )
    } else {
      Row(
        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        chips()
      }
    }
    if (showFollow) {
      FollowChip(follow, canFollow, onFollowChange, Modifier.padding(start = 8.dp))
    }
  }
}

@Composable
private fun LogLevelChip(
  level: LogLevel,
  selectedLevel: LogLevel,
  warningCount: Int,
  errorCount: Int,
  fullLevelNames: Boolean,
  onSelectLevel: (LogLevel) -> Unit,
) {
  val count =
    when (level) {
      LogLevel.WARN -> warningCount
      LogLevel.ERROR -> errorCount
      else -> null
    }
  val label = if (fullLevelNames) level.accessibilityName else level.shortLabel
  FilterChip(
    selected = selectedLevel == level,
    onClick = { onSelectLevel(level) },
    label = {
      Text(
        if (count == null) label else "$label ${cappedCount(count)}",
        maxLines = 1,
        softWrap = false,
        color =
          if (level == LogLevel.WARN || level == LogLevel.ERROR) levelColor(level)
          else Color.Unspecified,
      )
    },
    modifier =
      Modifier.testTag("level_${level.name}").semantics {
        contentDescription =
          "Filter ${level.accessibilityName} and above" +
            if (count == null) ""
            else
              ", ${cappedCount(count)} ${level.accessibilityName.lowercase()} ${if (count == 1) "entry" else "entries"}"
      },
  )
}

@Composable
private fun FollowChip(
  follow: Boolean,
  canFollow: Boolean,
  onFollowChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  FilterChip(
    selected = follow,
    onClick = { onFollowChange(!follow) },
    enabled = canFollow,
    label = { Text("Follow") },
    leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, contentDescription = null) },
    modifier =
      modifier.testTag("follow_logs").semantics {
        contentDescription = "Follow new logs and scroll to the newest entry"
        stateDescription =
          if (!canFollow) "Unavailable for this session or log source"
          else if (follow) "On" else "Off"
      },
  )
}

@Composable
private fun LogEmptyState(noEntries: Boolean) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(if (noEntries) "No log entries captured yet." else "No entries match this filter.")
  }
}

@Composable
internal fun LogViewerPreviewTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
  ) {
    Surface(color = MaterialTheme.colorScheme.background) { content() }
  }
}

@PreviewLightDark
@Composable
private fun LogViewerTopBarPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) {
      LogViewerTopBar(
        showActions = true,
        canClear = true,
        canRefresh = true,
        onBack = {},
        onRefresh = {},
        onShare = {},
        onClear = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun LogViewerTabsPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) { LogViewerTabs(selectedTab = 0, onSelectTab = {}) }
  }
}

@PreviewLightDark
@Composable
private fun SessionSelectorPreview() {
  LogViewerPreviewTheme {
    SessionSelector(
      sessions = listOf(LogSession("current", 1_700_000_000_000L, true)),
      selectedSessionId = "current",
      onSelectSession = {},
    )
  }
}

@PreviewLightDark
@Composable
private fun LogSearchFieldPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) { LogSearchField(query = "network", onQueryChange = {}) }
  }
}

@PreviewLightDark
@Composable
private fun LogFilterRowPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp)) {
      LogFilterRow(
        selectedLevel = LogLevel.WARN,
        warningCount = 12,
        errorCount = 3,
        follow = true,
        canFollow = true,
        onSelectLevel = {},
        onFollowChange = {},
      )
    }
  }
}

@PreviewLightDark
@Composable
private fun NoEntriesPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).height(180.dp)) { LogEmptyState(noEntries = true) }
  }
}

@PreviewLightDark
@Composable
private fun NoMatchingEntriesPreview() {
  LogViewerPreviewTheme {
    Box(Modifier.width(420.dp).height(180.dp)) { LogEmptyState(noEntries = false) }
  }
}

internal fun shareLogs(context: Context, text: String) {
  val directory = File(context.cacheDir, "pnv-logviewer-exports")
  check(directory.isDirectory || directory.mkdirs()) { "Could not prepare export" }
  val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
  directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
  val file = File(directory, "logs-${System.currentTimeMillis()}.txt")
  file.writeText(text)
  val uri =
    FileProvider.getUriForFile(context, "${context.packageName}.pnvlogviewer.fileprovider", file)
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      clipData = ClipData.newRawUri("logs", uri)
    }
  context.startActivity(Intent.createChooser(intent, "Share logs"))
}
