package com.flovera.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
import com.flovera.app.web.FloveraWebBridge
import com.flovera.app.workspace.WorkspaceFileNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AgentPanel {
  Conversation,
  HtmlFiles,
  Files,
  AgentFile,
  Settings,
}

private const val EmptyWebPrompt = "\u53ef\u9009\u62e9 HTML \u8fdb\u884c\u6253\u5f00"

@Composable
fun AgentScreen(controller: AgentController, modifier: Modifier = Modifier) {
  val state by controller.state.collectAsStateWithLifecycle()
  var menuOpen by remember { mutableStateOf(false) }
  var activePanel by remember { mutableStateOf<AgentPanel?>(null) }

  Box(modifier = modifier.fillMaxSize()) {
    WorkspaceWebView(url = state.selectedHtmlUrl)

    Surface(
      modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
      shape = RoundedCornerShape(999.dp),
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
      shadowElevation = 3.dp,
    ) {
      TextButton(onClick = { activePanel = AgentPanel.Conversation }) {
        Text(">", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
      }
    }

    Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
      OutlinedButton(onClick = { menuOpen = true }) {
        Text("Menu")
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
          text = { Text("Select HTML") },
          onClick = {
            menuOpen = false
            activePanel = AgentPanel.HtmlFiles
          },
        )
        DropdownMenuItem(
          text = { Text("Files") },
          onClick = {
            menuOpen = false
            activePanel = AgentPanel.Files
          },
        )
        DropdownMenuItem(
          text = { Text("AGENT.md") },
          onClick = {
            menuOpen = false
            activePanel = AgentPanel.AgentFile
          },
        )
        DropdownMenuItem(
          text = { Text("Settings") },
          onClick = {
            menuOpen = false
            activePanel = AgentPanel.Settings
          },
        )
      }
    }
  }

  when (activePanel) {
    AgentPanel.Conversation -> ConversationDialog(
      state = state,
      controller = controller,
      onDismiss = {
        controller.discardEmptyDraftSession()
        activePanel = null
      },
    )

    AgentPanel.HtmlFiles -> HtmlFilesDialog(
      state = state,
      controller = controller,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Files -> FilesDialog(
      state = state,
      controller = controller,
      onDismiss = { activePanel = null },
    )

    AgentPanel.AgentFile -> AgentFileDialog(
      state = state,
      controller = controller,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Settings -> SettingsDialog(
      state = state,
      controller = controller,
      onDismiss = { activePanel = null },
    )

    null -> Unit
  }
}

@Composable
private fun WorkspaceWebView(url: String?) {
  if (url.isNullOrBlank()) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101214),
      ) {}
      Text(
        text = EmptyWebPrompt,
        color = Color(0xFFD7D9DD),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    return
  }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { context ->
      WebView(context).apply {
        webViewClient = WebViewClient()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        addJavascriptInterface(FloveraWebBridge(context.applicationContext), "Flovera")
        loadUrl(url)
      }
    },
    update = { webView ->
      if (webView.url != url) {
        webView.loadUrl(url)
      }
    },
  )
}

@Composable
private fun ConversationDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  val listState = rememberLazyListState()
  val messages = state.session?.messages.orEmpty()
  val visibleMessageCount = messages.size + if (state.assistantDraft == null) 0 else 1
  var pendingRevertIndex by remember { mutableStateOf<Int?>(null) }
  var sessionPickerOpen by remember { mutableStateOf(false) }
  val isDraftSession = state.session != null && state.session.messages.isEmpty()

  LaunchedEffect(state.session?.id, visibleMessageCount) {
    if (visibleMessageCount > 0) {
      listState.scrollToItem(visibleMessageCount - 1)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().padding(12.dp),
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 3.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = if (isDraftSession) "New conversation" else state.session?.title ?: "Conversation",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = if (isDraftSession) "Draft: send a message to create this session." else state.status,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = controller::newSession, enabled = !state.isRunning) {
              Text("New")
            }
            OutlinedButton(onClick = { sessionPickerOpen = true }) {
              Text("Sessions")
            }
            TextButton(onClick = onDismiss) {
              Text("Close")
            }
          }
        }

        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          state = listState,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (messages.isEmpty()) {
            item {
              Text(
                text = "Ask the agent to create or edit files in the current workspace.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          } else {
            itemsIndexed(
              items = messages,
              key = { index, message -> "${message.timestampMillis}-${message.role}-$index" },
            ) { index, message ->
              MessageBubble(
                message = message,
                onRevert = if (state.isRunning) null else ({ pendingRevertIndex = index }),
              )
            }
            state.assistantDraft?.let { draft ->
              item(key = "assistant-draft") {
                MessageBubble(message = draft, onRevert = null)
              }
            }
          }
        }

        OutlinedTextField(
          value = state.input,
          onValueChange = controller::updateInput,
          label = { Text("Message") },
          minLines = 2,
          maxLines = 5,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Network", style = MaterialTheme.typography.bodyMedium)
            Text(
              text = if (state.settings.networkEnabled) "fetch_url and download_file available" else "network tools disabled",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(
            checked = state.settings.networkEnabled,
            onCheckedChange = controller::setNetworkEnabled,
            enabled = !state.isRunning,
            modifier = Modifier.semantics { contentDescription = "Network tools switch" },
          )
        }
        Button(
          onClick = controller::submit,
          enabled = !state.isRunning,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (state.isRunning) "Running..." else "Send")
        }
      }
    }
  }

  pendingRevertIndex?.let { index ->
    AlertDialog(
      onDismissRequest = { pendingRevertIndex = null },
      title = { Text("Revert conversation?") },
      text = { Text("This will remove the selected message and all messages after it.") },
      confirmButton = {
        TextButton(
          onClick = {
            controller.revertSessionToMessage(index)
            pendingRevertIndex = null
          },
        ) {
          Text("Revert")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingRevertIndex = null }) {
          Text("Cancel")
        }
      },
    )
  }

  if (sessionPickerOpen) {
    SessionsDialog(
      state = state,
      controller = controller,
      onDismiss = { sessionPickerOpen = false },
    )
  }
}

@Composable
private fun MessageBubble(
  message: SessionMessage,
  onRevert: (() -> Unit)?,
) {
  val isUser = message.role == "user"
  val isError = message.role == "error"
  val horizontal = if (isUser) Arrangement.End else Arrangement.Start
  val bubbleColor = when {
    isUser -> MaterialTheme.colorScheme.primary
    isError -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val textColor = when {
    isUser -> MaterialTheme.colorScheme.onPrimary
    isError -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val previewContent = remember(message.content) { collapsedMessageContent(message.content) }
  val canExpand = previewContent != message.content
  var expanded by remember(message.timestampMillis, message.role, message.content) { mutableStateOf(!canExpand) }
  var selectionEnabled by remember(message.timestampMillis, message.role, message.content) { mutableStateOf(false) }
  val displayContent = if (expanded) message.content else previewContent
  val surfaceModifier = if (selectionEnabled) {
    Modifier.fillMaxWidth(0.84f)
  } else {
    Modifier
      .fillMaxWidth(0.84f)
      .pointerInput(message.timestampMillis, message.role, message.content) {
        detectTapGestures(onLongPress = { selectionEnabled = true })
      }
  }

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = horizontal) {
    Surface(
      modifier = surfaceModifier,
      shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 18.dp,
      ),
      color = bubbleColor,
      tonalElevation = 1.dp,
    ) {
      MessageBubbleContent(
        selectionEnabled = selectionEnabled,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text(
                text = when {
                  isUser -> "You"
                  isError -> "Error"
                  else -> "Assistant"
                },
                color = textColor.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
              )
              Text(
                text = formatMessageTime(message.timestampMillis),
                color = textColor.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelSmall,
              )
            }
            onRevert?.let {
              IconButton(
                onClick = it,
                modifier = Modifier.semantics {
                  contentDescription = "Revert to before this message"
                },
              ) {
                Text("\u21A9", color = textColor.copy(alpha = 0.82f), style = MaterialTheme.typography.titleMedium)
              }
            }
          }
          MarkdownMessageText(content = displayContent, color = textColor)
          if (canExpand) {
            TextButton(onClick = { expanded = !expanded }) {
              Text(
                text = if (expanded) "Show less" else "Show more",
                color = textColor.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
          if (selectionEnabled) {
            TextButton(onClick = { selectionEnabled = false }) {
              Text(
                text = "Done selecting",
                color = textColor.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
          ToolEventsSummary(events = message.toolEvents, color = textColor)
        }
      }
    }
  }
}

@Composable
private fun MessageBubbleContent(
  selectionEnabled: Boolean,
  content: @Composable () -> Unit,
) {
  if (selectionEnabled) {
    SelectionContainer {
      content()
    }
  } else {
    content()
  }
}

@Composable
private fun ToolEventsSummary(events: List<ToolEvent>, color: Color) {
  if (events.isEmpty()) return
  var expanded by remember(events.size) { mutableStateOf(false) }
  val summary = events.joinToString(", ") { it.name }

  Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.10f)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      TextButton(onClick = { expanded = !expanded }) {
        Text(
          text = if (expanded) "Hide tool calls (${events.size})" else "Tool calls (${events.size}): $summary",
          color = color,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (expanded) {
        events.forEach { event ->
          Text(
            text = "${event.name} @ ${formatMessageTime(event.timestampMillis)}\nargs: ${event.args}\nresult: ${event.result}",
            color = color,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

@Composable
private fun MarkdownMessageText(content: String, color: Color) {
  val blocks = remember(content) { parseMarkdownBlocks(content) }
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    blocks.forEach { block ->
      when (block) {
        is MarkdownBlock.Heading -> Text(
          text = block.text,
          color = color,
          fontWeight = FontWeight.SemiBold,
          style = when (block.level) {
            1 -> MaterialTheme.typography.titleMedium
            2 -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.bodyLarge
          },
        )

        is MarkdownBlock.Paragraph -> InlineMarkdownText(
          text = block.text,
          color = color,
          style = MaterialTheme.typography.bodyMedium,
        )

        is MarkdownBlock.Bullet -> InlineMarkdownText(
          text = "- ${block.text}",
          color = color,
          style = MaterialTheme.typography.bodyMedium,
        )

        is MarkdownBlock.Quote -> InlineMarkdownText(
          text = "> ${block.text}",
          color = color.copy(alpha = 0.82f),
          style = MaterialTheme.typography.bodyMedium,
        )

        is MarkdownBlock.Code -> Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
          color = color.copy(alpha = 0.12f),
        ) {
          Text(
            text = block.text,
            modifier = Modifier.padding(10.dp),
            color = color,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

@Composable
private fun InlineMarkdownText(
  text: String,
  color: Color,
  style: androidx.compose.ui.text.TextStyle,
) {
  val annotated = remember(text, color) { inlineMarkdown(text, color) }
  Text(
    text = annotated,
    color = color,
    style = style,
  )
}

private fun formatMessageTime(timestampMillis: Long): String {
  return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))
}

private fun collapsedMessageContent(content: String): String {
  val maxLines = 24
  val maxChars = 1600
  val lines = content.lines()
  val lineCollapsed = lines.size > maxLines
  val charCollapsed = content.length > maxChars
  if (!lineCollapsed && !charCollapsed) return content

  val byLines = if (lineCollapsed) lines.take(maxLines).joinToString("\n") else content
  val preview = if (byLines.length > maxChars) byLines.take(maxChars).trimEnd() else byLines
  return "$preview\n\n..."
}

private sealed interface MarkdownBlock {
  data class Heading(val level: Int, val text: String) : MarkdownBlock
  data class Paragraph(val text: String) : MarkdownBlock
  data class Bullet(val text: String) : MarkdownBlock
  data class Quote(val text: String) : MarkdownBlock
  data class Code(val text: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
  val blocks = mutableListOf<MarkdownBlock>()
  val paragraph = StringBuilder()
  val code = StringBuilder()
  var inCode = false

  fun flushParagraph() {
    val text = paragraph.toString().trim()
    if (text.isNotBlank()) blocks += MarkdownBlock.Paragraph(text)
    paragraph.clear()
  }

  content.lines().forEach { rawLine ->
    val line = rawLine.trimEnd()
    if (line.trimStart().startsWith("```")) {
      if (inCode) {
        blocks += MarkdownBlock.Code(code.toString().trimEnd())
        code.clear()
      } else {
        flushParagraph()
      }
      inCode = !inCode
      return@forEach
    }

    if (inCode) {
      code.appendLine(rawLine)
      return@forEach
    }

    val trimmed = line.trim()
    when {
      trimmed.isBlank() -> flushParagraph()
      trimmed.startsWith("#") -> {
        flushParagraph()
        val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
        blocks += MarkdownBlock.Heading(level, trimmed.drop(level).trim())
      }
      trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
        flushParagraph()
        blocks += MarkdownBlock.Bullet(trimmed.drop(2).trim())
      }
      trimmed.startsWith("> ") -> {
        flushParagraph()
        blocks += MarkdownBlock.Quote(trimmed.drop(2).trim())
      }
      else -> {
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
      }
    }
  }
  if (inCode && code.isNotEmpty()) blocks += MarkdownBlock.Code(code.toString().trimEnd())
  flushParagraph()
  return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph("")) }
}

private fun inlineMarkdown(text: String, color: Color) = buildAnnotatedString {
  var index = 0
  while (index < text.length) {
    when {
      text.startsWith("`", index) -> {
        val end = text.indexOf('`', startIndex = index + 1)
        if (end > index) {
          withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.12f))) {
            append(text.substring(index + 1, end))
          }
          index = end + 1
        } else {
          append(text[index])
          index += 1
        }
      }
      text.startsWith("**", index) -> {
        val end = text.indexOf("**", startIndex = index + 2)
        if (end > index) {
          withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(text.substring(index + 2, end))
          }
          index = end + 2
        } else {
          append(text[index])
          index += 1
        }
      }
      else -> {
        append(text[index])
        index += 1
      }
    }
  }
}

@Composable
private fun HtmlFilesDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Select HTML") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state.htmlFiles.isEmpty()) {
          Text("No HTML files in this workspace.", style = MaterialTheme.typography.bodyMedium)
        }
        state.htmlFiles.forEach { path ->
          OutlinedButton(
            onClick = {
              controller.selectHtmlFile(path)
              onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (path == state.selectedHtmlPath) "$path  selected" else path)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    },
  )
}

@Composable
private fun SessionsDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  var renameTarget by remember { mutableStateOf<SessionMessageTarget?>(null) }
  var archivedMenuOpen by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Sessions") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedButton(
            onClick = {
              controller.newSession()
              onDismiss()
            },
            modifier = Modifier.weight(1f),
          ) {
            Text("New Session")
          }
          Box {
            OutlinedButton(
              onClick = { archivedMenuOpen = true },
              enabled = state.archivedSessions.isNotEmpty(),
            ) {
              Text("Archived")
            }
            DropdownMenu(expanded = archivedMenuOpen, onDismissRequest = { archivedMenuOpen = false }) {
              state.archivedSessions.forEach { session ->
                DropdownMenuItem(
                  text = { Text("Restore ${session.title}") },
                  onClick = {
                    archivedMenuOpen = false
                    controller.restoreSession(session.id)
                    onDismiss()
                  },
                )
              }
            }
          }
        }
        if (state.sessions.isEmpty()) {
          Text("No active sessions.", style = MaterialTheme.typography.bodyMedium)
        }
        state.sessions.forEach { session ->
          SessionListItem(
            sessionId = session.id,
            title = session.title,
            subtitle = if (session.pinnedAtMillis == null) {
              "${session.messages.size} messages"
            } else {
              "Pinned / ${session.messages.size} messages"
            },
            active = session.id == state.session?.id,
            onOpen = {
              controller.openSession(session.id)
              onDismiss()
            },
            menuContent = { closeMenu ->
              DropdownMenuItem(
                text = { Text(if (session.pinnedAtMillis == null) "Pin" else "Unpin") },
                onClick = {
                  closeMenu()
                  controller.setSessionPinned(session.id, session.pinnedAtMillis == null)
                },
              )
              DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                  closeMenu()
                  renameTarget = SessionMessageTarget(session.id, session.title)
                },
              )
              DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                  closeMenu()
                  controller.duplicateSession(session.id)
                },
              )
              DropdownMenuItem(
                text = { Text("Archive") },
                onClick = {
                  closeMenu()
                  controller.archiveSession(session.id)
                },
              )
            },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    },
  )

  renameTarget?.let { target ->
    RenameSessionDialog(
      initialTitle = target.title,
      onDismiss = { renameTarget = null },
      onSave = { title ->
        controller.renameSession(target.id, title)
        renameTarget = null
      },
    )
  }
}

private data class SessionMessageTarget(val id: String, val title: String)

@Composable
private fun SessionListItem(
  sessionId: String,
  title: String,
  subtitle: String,
  active: Boolean,
  onOpen: () -> Unit,
  menuContent: @Composable (closeMenu: () -> Unit) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("open-session-$sessionId")
      .clickable(onClick = onOpen)
      .semantics {
        contentDescription = "Open session $title"
        onClick {
          onOpen()
          true
        }
      },
    shape = RoundedCornerShape(8.dp),
    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(title, style = MaterialTheme.typography.bodyLarge)
          Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Box {
          IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.semantics {
              contentDescription = "Session actions for $title"
            },
          ) {
            Text("\u2630", style = MaterialTheme.typography.titleMedium)
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menuContent { menuOpen = false }
          }
        }
      }
    }
  }
}

@Composable
private fun RenameSessionDialog(
  initialTitle: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var title by remember(initialTitle) { mutableStateOf(initialTitle) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Rename Session") },
    text = {
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onSave(title) }) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun FilesDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  val root = state.workspaceTree
  val expandedPaths = remember { mutableStateOf(setOf<String>()) }
  var renameTarget by remember { mutableStateOf<WorkspaceFileNode?>(null) }
  val context = LocalContext.current
  val clipboard = context.getSystemService(ClipboardManager::class.java)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Workspace Files") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        OutlinedButton(onClick = controller::refreshWorkspaceFiles, modifier = Modifier.fillMaxWidth()) {
          Text("Refresh")
        }
        if (root == null || root.children.isEmpty()) {
          Text("(empty)", style = MaterialTheme.typography.bodyMedium)
        } else {
          root.children.forEach { node ->
            WorkspaceFileTreeNode(
              node = node,
              depth = 0,
              expandedPaths = expandedPaths.value,
              onToggle = { path ->
                expandedPaths.value = if (path in expandedPaths.value) {
                  expandedPaths.value - path
                } else {
                  expandedPaths.value + path
                }
              },
              onDefaultOpen = { path ->
                if (path.endsWith(".html", ignoreCase = true)) {
                  controller.selectHtmlFile(path)
                  onDismiss()
                } else {
                  openWorkspaceFile(context, controller, path)
                }
              },
              onOpenWith = { path -> openWorkspaceFile(context, controller, path) },
              onShare = { path -> shareWorkspaceFile(context, controller, path) },
              onRename = { renameTarget = it },
              onCopyPath = { path -> clipboard.setPrimaryClip(ClipData.newPlainText("Workspace path", path)) },
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    },
  )

  renameTarget?.let { target ->
    RenameWorkspacePathDialog(
      initialName = target.name,
      onDismiss = { renameTarget = null },
      onSave = { newName ->
        controller.renameWorkspacePath(target.path, newName)
        renameTarget = null
      },
    )
  }
}

@Composable
private fun WorkspaceFileTreeNode(
  node: WorkspaceFileNode,
  depth: Int,
  expandedPaths: Set<String>,
  onToggle: (String) -> Unit,
  onDefaultOpen: (String) -> Unit,
  onOpenWith: (String) -> Unit,
  onShare: (String) -> Unit,
  onRename: (WorkspaceFileNode) -> Unit,
  onCopyPath: (String) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val expanded = node.path in expandedPaths
  val leftPadding = (depth * 14).dp

  Row(
    modifier = Modifier.fillMaxWidth().padding(start = leftPadding),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(
      onClick = {
        if (node.isDirectory) {
          onToggle(node.path)
        } else {
          onDefaultOpen(node.path)
        }
      },
      modifier = Modifier.weight(1f),
    ) {
      val marker = when {
        node.isDirectory && expanded -> "-"
        node.isDirectory -> "+"
        else -> " "
      }
      val label = if (node.isDirectory) node.name else "${node.name} (${node.sizeBytes} bytes)"
      Text("$marker $label")
    }
    Box {
      IconButton(
        onClick = { menuOpen = true },
        modifier = Modifier.semantics {
          contentDescription = "File actions for ${node.path}"
        },
      ) {
        Text("\u2630", style = MaterialTheme.typography.titleMedium)
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (!node.isDirectory) {
          DropdownMenuItem(
            text = { Text("Open with...") },
            onClick = {
              menuOpen = false
              onOpenWith(node.path)
            },
          )
          DropdownMenuItem(
            text = { Text("Share") },
            onClick = {
              menuOpen = false
              onShare(node.path)
            },
          )
        }
        DropdownMenuItem(
          text = { Text("Rename") },
          onClick = {
            menuOpen = false
            onRename(node)
          },
        )
        DropdownMenuItem(
          text = { Text("Copy path") },
          onClick = {
            menuOpen = false
            onCopyPath(node.path)
          },
        )
      }
    }
  }

  if (node.isDirectory && expanded) {
    node.children.forEach { child ->
      WorkspaceFileTreeNode(
        node = child,
        depth = depth + 1,
        expandedPaths = expandedPaths,
        onToggle = onToggle,
        onDefaultOpen = onDefaultOpen,
        onOpenWith = onOpenWith,
        onShare = onShare,
        onRename = onRename,
        onCopyPath = onCopyPath,
      )
    }
  }
}

@Composable
private fun RenameWorkspacePathDialog(
  initialName: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var name by remember(initialName) { mutableStateOf(initialName) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Rename") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onSave(name) }) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

private fun openWorkspaceFile(context: Context, controller: AgentController, path: String) {
  val uri = controller.workspaceFileUri(path) ?: return
  val intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, controller.workspaceMimeType(path))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  try {
    context.startActivity(Intent.createChooser(intent, "Open with"))
  } catch (_: ActivityNotFoundException) {
  }
}

private fun shareWorkspaceFile(context: Context, controller: AgentController, path: String) {
  val uri = controller.workspaceFileUri(path) ?: return
  val intent = Intent(Intent.ACTION_SEND)
    .setType(controller.workspaceMimeType(path))
    .putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  try {
    context.startActivity(Intent.createChooser(intent, "Share"))
  } catch (_: ActivityNotFoundException) {
  }
}

@Composable
private fun AgentFileDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("AGENT.md") },
    text = {
      OutlinedTextField(
        value = state.agentRulesDraft,
        onValueChange = controller::updateAgentRules,
        label = { Text("Workspace agent rules") },
        minLines = 10,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(
        onClick = {
          controller.saveAgentRules()
          onDismiss()
        },
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun SettingsDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  val selectedProvider = ModelProviderCatalog.findProvider(state.providerDraft) ?: ModelProviderCatalog.defaultProvider
  var providerMenuOpen by remember { mutableStateOf(false) }
  var modelMenuOpen by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Settings") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${selectedProvider.label} / ${state.modelDraft}", style = MaterialTheme.typography.bodySmall)
        Box {
          OutlinedButton(onClick = { providerMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedProvider.label)
          }
          DropdownMenu(expanded = providerMenuOpen, onDismissRequest = { providerMenuOpen = false }) {
            ModelProviderCatalog.providers.forEach { provider ->
              DropdownMenuItem(
                text = { Text(provider.label) },
                onClick = {
                  providerMenuOpen = false
                  controller.updateProvider(provider.id)
                },
              )
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = state.modelDraft,
            onValueChange = controller::updateModel,
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Box {
            OutlinedButton(onClick = { modelMenuOpen = true }) {
              Text("Presets")
            }
            DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
              selectedProvider.suggestedModels.forEach { model ->
                DropdownMenuItem(
                  text = { Text(model) },
                  onClick = {
                    modelMenuOpen = false
                    controller.updateModel(model)
                  },
                )
              }
            }
          }
        }
        OutlinedTextField(
          value = state.apiKeyDraft,
          onValueChange = controller::updateApiKey,
          label = { Text(selectedProvider.apiKeyLabel) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          controller.saveModelSettings()
          onDismiss()
        },
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
