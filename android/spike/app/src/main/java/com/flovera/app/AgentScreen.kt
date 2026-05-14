package com.flovera.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.flovera.app.workspace.WorkspaceSettingsProposal
import com.flovera.app.workspace.WorkspaceSnapshotRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FloveraFabShape = RoundedCornerShape(999.dp)
private val FloveraPanelShape = RoundedCornerShape(18.dp)
private val FloveraSmallShape = RoundedCornerShape(8.dp)
private val FloveraUserBubbleColor = Color(0xFF233640)
private val FloveraUserBubbleBorder = Color(0xFF365A67)
private val FloveraAssistantBubbleBorder = Color(0xFF2C3137)
private val FloveraFabContainer = Color(0xFF172229)
private val FloveraFabText = Color(0xFFDEF3F8)

private enum class AgentPanel {
  Conversation,
  HtmlFiles,
  Files,
  Snapshots,
  AgentFile,
  Settings,
}

private const val EmptyWebPrompt = "\u53ef\u9009\u62e9 HTML \u8fdb\u884c\u6253\u5f00"

@Composable
fun AgentScreen(controller: AgentController, modifier: Modifier = Modifier) {
  val state by controller.state.collectAsStateWithLifecycle()
  val language = state.settings.language
  val context = LocalContext.current
  var activePanel by remember { mutableStateOf<AgentPanel?>(null) }

  LaunchedEffect(state.status) {
    if (shouldShowStatusToast(state.status)) {
      Toast.makeText(context, state.status, Toast.LENGTH_SHORT).show()
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    WorkspaceWebView(url = state.selectedHtmlUrl, workspaceRootUrl = state.workspaceRootUrl)

    Row(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(18.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FloatingActionButton(
        onClick = { activePanel = AgentPanel.HtmlFiles },
        modifier = Modifier.semantics { contentDescription = "Open HTML quick picker" },
        shape = FloveraFabShape,
        containerColor = FloveraFabContainer,
        contentColor = FloveraFabText,
      ) {
        Text("HTML", modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge)
      }
      FloatingActionButton(
        onClick = { activePanel = AgentPanel.Conversation },
        modifier = Modifier.semantics { contentDescription = "Open agent conversation" },
        shape = FloveraFabShape,
        containerColor = FloveraFabContainer,
        contentColor = FloveraFabText,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
          Text(t(language, "Agent", "Agent"), style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }

  when (activePanel) {
    AgentPanel.Conversation -> ConversationDialog(
      state = state,
      controller = controller,
      language = language,
      onOpenPanel = { activePanel = it },
      onDismiss = {
        controller.discardEmptyDraftSession()
        activePanel = null
      },
    )

    AgentPanel.HtmlFiles -> HtmlFilesDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Files -> FilesDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Snapshots -> SnapshotsDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = { activePanel = null },
    )

    AgentPanel.AgentFile -> AgentFileDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Settings -> SettingsDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = { activePanel = null },
    )

    null -> Unit
  }
}

@Composable
private fun WorkspaceWebView(url: String?, workspaceRootUrl: String) {
  var webError by remember(url) { mutableStateOf<String?>(null) }

  if (url.isNullOrBlank()) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
      ) {}
      Text(
        text = EmptyWebPrompt,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    return
  }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { context ->
      WebView(context).apply {
        webViewClient = FloveraWorkspaceWebViewClient(
          workspaceRootUrl = workspaceRootUrl,
          onError = { webError = it },
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        addJavascriptInterface(FloveraWebBridge(context), "Flovera")
        loadUrl(url)
      }
    },
    update = { webView ->
      if (webView.url != url) {
        webError = null
        webView.loadUrl(url)
      }
    },
  )

  webError?.let { message ->
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.BottomCenter,
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 4.dp,
      ) {
        Text(
          text = message,
          modifier = Modifier.padding(12.dp),
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private fun t(language: String, en: String, zh: String): String = if (language == "zh") zh else en

private class FloveraWorkspaceWebViewClient(
  private val workspaceRootUrl: String,
  private val onError: (String) -> Unit,
) : WebViewClient() {
  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
    return handleUrl(view, request.url)
  }

  @Suppress("DEPRECATION")
  override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
    return handleUrl(view, Uri.parse(url))
  }

  override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
    if (request.isForMainFrame) {
      onError("WebView load failed: ${error.description}")
    }
  }

  private fun handleUrl(view: WebView, uri: Uri): Boolean {
    val target = uri.toString()
    if (target.startsWith(workspaceRootUrl)) {
      return false
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
      val intent = Intent(Intent.ACTION_VIEW, uri)
      return runCatching {
        view.context.startActivity(intent)
        onError("Opened external link outside Flovera.")
        true
      }.getOrElse {
        onError("No app can open external link: $target")
        true
      }
    }

    onError("Blocked non-workspace navigation: $target")
    return true
  }
}

@Composable
private fun ConversationDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onOpenPanel: (AgentPanel) -> Unit,
  onDismiss: () -> Unit,
) {
  val listState = rememberLazyListState()
  val messages = state.session?.messages.orEmpty()
  val visibleMessageCount = messages.size + if (state.assistantDraft == null) 0 else 1
  var pendingRevertIndex by remember { mutableStateOf<Int?>(null) }
  var sessionPickerOpen by remember { mutableStateOf(false) }
  var moreMenuOpen by remember { mutableStateOf(false) }
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
      modifier = Modifier.fillMaxSize().padding(10.dp),
      shape = FloveraPanelShape,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      tonalElevation = 0.dp,
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
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = if (isDraftSession) {
                t(language, "New conversation", "\u65b0\u5bf9\u8bdd")
              } else {
                t(language, "Conversation", "\u5bf9\u8bdd")
              },
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = if (isDraftSession) {
                t(language, "Draft: send a message to create this session.", "\u8349\u7a3f\uff1a\u53d1\u9001\u7b2c\u4e00\u6761\u6d88\u606f\u540e\u624d\u4f1a\u521b\u5efa session\u3002")
              } else {
                state.status
              },
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
              onClick = controller::newSession,
              enabled = !state.isRunning,
              shape = FloveraSmallShape,
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
              Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
              Text(t(language, "New", "\u65b0\u5efa"))
            }
            Box {
              IconButton(
                onClick = { moreMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = "More" },
              ) {
                Icon(Icons.Filled.Menu, contentDescription = null)
              }
              DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                DropdownMenuItem(
                  text = { Text(t(language, "Sessions", "Sessions")) },
                  onClick = {
                    moreMenuOpen = false
                    sessionPickerOpen = true
                  },
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Select HTML", "\u9009\u62e9 HTML")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.HtmlFiles)
                  },
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Files", "\u6587\u4ef6")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Files)
                  },
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Snapshots", "\u5feb\u7167")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Snapshots)
                  },
                )
                DropdownMenuItem(
                  text = { Text("AGENT.md") },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.AgentFile)
                  },
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Settings", "\u8bbe\u7f6e")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Settings)
                  },
                )
              }
            }
            IconButton(
              onClick = onDismiss,
              modifier = Modifier.semantics { contentDescription = "Close" },
            ) {
              Icon(Icons.Filled.Close, contentDescription = null)
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
                text = t(language, "Ask the agent to create or edit files in the current workspace.", "\u8ba9 agent \u5728\u5f53\u524d workspace \u4e2d\u521b\u5efa\u6216\u7f16\u8f91\u6587\u4ef6\u3002"),
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
                onRevert = if (!state.isRunning && message.role == "user") ({ pendingRevertIndex = index }) else null,
              )
            }
            state.assistantDraft?.let { draft ->
              item(key = "assistant-draft") {
                MessageBubble(message = draft, onRevert = null)
              }
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          OutlinedTextField(
            value = state.input,
            onValueChange = controller::updateInput,
            label = { Text(t(language, "Message", "\u6d88\u606f")) },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              unfocusedBorderColor = MaterialTheme.colorScheme.outline,
              focusedContainerColor = MaterialTheme.colorScheme.background,
              unfocusedContainerColor = MaterialTheme.colorScheme.background,
            ),
            modifier = Modifier.weight(1f),
          )
          Surface(
            modifier = Modifier
              .size(52.dp)
              .semantics { contentDescription = "Send message" }
              .clickable(enabled = !state.isRunning, onClick = controller::submit),
            shape = RoundedCornerShape(12.dp),
            color = if (state.isRunning) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (state.isRunning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
            }
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(t(language, "Network", "\u8054\u7f51"), style = MaterialTheme.typography.bodyMedium)
            Text(
              text = if (state.settings.networkEnabled) {
                t(language, "fetch_url and download_file available", "fetch_url \u548c download_file \u53ef\u7528")
              } else {
                t(language, "network tools disabled", "\u8054\u7f51\u5de5\u5177\u5df2\u5173\u95ed")
              },
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
      language = language,
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
    isUser -> FloveraUserBubbleColor
    isError -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val textColor = when {
    isUser -> MaterialTheme.colorScheme.onSurface
    isError -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val bubbleBorderColor = when {
    isUser -> FloveraUserBubbleBorder
    isError -> MaterialTheme.colorScheme.error
    else -> FloveraAssistantBubbleBorder
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
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (isUser) 14.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 14.dp,
      ),
      color = bubbleColor,
      border = BorderStroke(1.dp, bubbleBorderColor),
      tonalElevation = 0.dp,
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

  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
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

private fun formatSnapshotTime(timestampMillis: Long): String {
  return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))
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
  language: String,
  onDismiss: () -> Unit,
) {
  val sortedHtmlFiles = remember(state.htmlFiles, state.settings.pinnedHtmlPaths) {
    state.htmlFiles.sortedWith(
      compareByDescending<String> { it in state.settings.pinnedHtmlPaths }
        .thenBy { it.lowercase() },
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Select HTML", "\u9009\u62e9 HTML")) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (sortedHtmlFiles.isEmpty()) {
          item {
            Text(t(language, "No HTML files in this workspace.", "\u5f53\u524d workspace \u6ca1\u6709 HTML \u6587\u4ef6\u3002"), style = MaterialTheme.typography.bodyMedium)
          }
        }
        items(sortedHtmlFiles) { path ->
          HtmlFilePickerRow(
            path = path,
            selected = path == state.selectedHtmlPath,
            pinned = path in state.settings.pinnedHtmlPaths,
            language = language,
            onOpen = {
              controller.selectHtmlFile(path)
              onDismiss()
            },
            onPin = { pinned -> controller.setHtmlPinned(path, pinned) },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Close", "\u5173\u95ed"))
      }
    },
  )
}

@Composable
private fun SessionsDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  var renameTarget by remember { mutableStateOf<SessionMessageTarget?>(null) }
  var archivedMenuOpen by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Sessions", "Sessions")) },
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
            Text(t(language, "New Session", "\u65b0\u5efa Session"))
          }
          Box {
            OutlinedButton(
              onClick = { archivedMenuOpen = true },
              enabled = state.archivedSessions.isNotEmpty(),
            ) {
              Text(t(language, "Archived", "\u5df2\u5f52\u6863"))
            }
            DropdownMenu(expanded = archivedMenuOpen, onDismissRequest = { archivedMenuOpen = false }) {
              state.archivedSessions.forEach { session ->
                DropdownMenuItem(
                  text = { Text(t(language, "Restore ${session.title}", "\u6062\u590d ${session.title}")) },
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
          Text(t(language, "No active sessions.", "\u6ca1\u6709\u6d3b\u8dc3 session\u3002"), style = MaterialTheme.typography.bodyMedium)
        }
        state.sessions.forEach { session ->
          SessionListItem(
            sessionId = session.id,
            title = session.title,
            subtitle = if (session.pinnedAtMillis == null) {
              t(language, "${session.messages.size} messages", "${session.messages.size} \u6761\u6d88\u606f")
            } else {
              t(language, "Pinned / ${session.messages.size} messages", "\u5df2\u7f6e\u9876 / ${session.messages.size} \u6761\u6d88\u606f")
            },
            active = session.id == state.session?.id,
            onOpen = {
              controller.openSession(session.id)
              onDismiss()
            },
            menuContent = { closeMenu ->
              DropdownMenuItem(
                text = { Text(if (session.pinnedAtMillis == null) t(language, "Pin", "\u7f6e\u9876") else t(language, "Unpin", "\u53d6\u6d88\u7f6e\u9876")) },
                onClick = {
                  closeMenu()
                  controller.setSessionPinned(session.id, session.pinnedAtMillis == null)
                },
              )
              DropdownMenuItem(
                text = { Text(t(language, "Rename", "\u91cd\u547d\u540d")) },
                onClick = {
                  closeMenu()
                  renameTarget = SessionMessageTarget(session.id, session.title)
                },
              )
              DropdownMenuItem(
                text = { Text(t(language, "Copy", "\u590d\u5236")) },
                onClick = {
                  closeMenu()
                  controller.duplicateSession(session.id)
                },
              )
              DropdownMenuItem(
                text = { Text(t(language, "Archive", "\u5f52\u6863")) },
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
        Text(t(language, "Close", "\u5173\u95ed"))
      }
    },
  )

  renameTarget?.let { target ->
    RenameSessionDialog(
      initialTitle = target.title,
      language = language,
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
            Icon(Icons.Filled.Menu, contentDescription = null)
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
  language: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var title by remember(initialTitle) { mutableStateOf(initialTitle) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Rename Session", "\u91cd\u547d\u540d Session")) },
    text = {
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text(t(language, "Title", "\u6807\u9898")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onSave(title) }) {
        Text(t(language, "Save", "\u4fdd\u5b58"))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Cancel", "\u53d6\u6d88"))
      }
    },
  )
}

@Composable
private fun FilesDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  val root = state.workspaceTree
  val expandedPaths = remember { mutableStateOf(setOf<String>()) }
  var renameTarget by remember { mutableStateOf<WorkspaceFileNode?>(null) }
  val context = LocalContext.current
  val clipboard = context.getSystemService(ClipboardManager::class.java)
  val visibleNodes = remember(root, expandedPaths.value) {
    root?.children.orEmpty().flattenVisibleWorkspaceNodes(expandedPaths.value)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Workspace Files", "Workspace \u6587\u4ef6")) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        item {
          OutlinedButton(onClick = controller::refreshWorkspaceFiles, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(t(language, "Refresh", "\u5237\u65b0"))
          }
        }
        if (visibleNodes.isEmpty()) {
          item {
            Text(t(language, "(empty)", "\uff08\u7a7a\uff09"), style = MaterialTheme.typography.bodyMedium)
          }
        } else {
          items(visibleNodes, key = { it.node.path }) { visibleNode ->
            WorkspaceFileTreeNode(
              node = visibleNode.node,
              depth = visibleNode.depth,
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
              language = language,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Close", "\u5173\u95ed"))
      }
    },
  )

  renameTarget?.let { target ->
    RenameWorkspacePathDialog(
      initialName = target.name,
      language = language,
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
  language: String,
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
        Icon(Icons.Filled.Menu, contentDescription = null)
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (!node.isDirectory) {
          DropdownMenuItem(
            text = { Text(t(language, "Open with...", "\u6253\u5f00\u65b9\u5f0f...")) },
            onClick = {
              menuOpen = false
              onOpenWith(node.path)
            },
          )
          DropdownMenuItem(
            text = { Text(t(language, "Share", "\u5206\u4eab")) },
            onClick = {
              menuOpen = false
              onShare(node.path)
            },
          )
        }
        DropdownMenuItem(
          text = { Text(t(language, "Rename", "\u91cd\u547d\u540d")) },
          onClick = {
            menuOpen = false
            onRename(node)
          },
        )
        DropdownMenuItem(
          text = { Text(t(language, "Copy path", "\u590d\u5236\u8def\u5f84")) },
          onClick = {
            menuOpen = false
            onCopyPath(node.path)
          },
        )
      }
    }
  }

}

private data class VisibleWorkspaceFileNode(
  val node: WorkspaceFileNode,
  val depth: Int,
)

private fun List<WorkspaceFileNode>.flattenVisibleWorkspaceNodes(
  expandedPaths: Set<String>,
  depth: Int = 0,
): List<VisibleWorkspaceFileNode> {
  return flatMap { node ->
    val current = VisibleWorkspaceFileNode(node = node, depth = depth)
    if (node.isDirectory && node.path in expandedPaths) {
      listOf(current) + node.children.flattenVisibleWorkspaceNodes(expandedPaths, depth + 1)
    } else {
      listOf(current)
    }
  }
}

@Composable
private fun RenameWorkspacePathDialog(
  initialName: String,
  language: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var name by remember(initialName) { mutableStateOf(initialName) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Rename", "\u91cd\u547d\u540d")) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(t(language, "Name", "\u540d\u79f0")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onSave(name) }) {
        Text(t(language, "Save", "\u4fdd\u5b58"))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Cancel", "\u53d6\u6d88"))
      }
    },
  )
}

private fun openWorkspaceFile(context: Context, controller: AgentController, path: String) {
  val uri = controller.workspaceFileUri(path)
  if (uri == null) {
    controller.reportStatus("File is not available: $path")
    return
  }
  val intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, controller.workspaceMimeType(path))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  try {
    context.startActivity(Intent.createChooser(intent, "Open with"))
  } catch (_: ActivityNotFoundException) {
    controller.reportStatus("No app can open $path")
  }
}

private fun shareWorkspaceFile(context: Context, controller: AgentController, path: String) {
  val uri = controller.workspaceFileUri(path)
  if (uri == null) {
    controller.reportStatus("File is not available: $path")
    return
  }
  val intent = Intent(Intent.ACTION_SEND)
    .setType(controller.workspaceMimeType(path))
    .putExtra(Intent.EXTRA_STREAM, uri)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  try {
    context.startActivity(Intent.createChooser(intent, "Share"))
  } catch (_: ActivityNotFoundException) {
    controller.reportStatus("No app can share $path")
  }
}

@Composable
private fun SnapshotsDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  var snapshotName by remember { mutableStateOf("") }
  var restoreTarget by remember { mutableStateOf<WorkspaceSnapshotRecord?>(null) }
  var deleteTarget by remember { mutableStateOf<WorkspaceSnapshotRecord?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Workspace Snapshots", "Workspace \u5feb\u7167")) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = snapshotName,
          onValueChange = { snapshotName = it },
          label = { Text(t(language, "Snapshot name", "\u5feb\u7167\u540d\u79f0")) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Button(
          onClick = {
            controller.createWorkspaceSnapshot(snapshotName)
            snapshotName = ""
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(t(language, "Create Manual Snapshot", "\u521b\u5efa\u624b\u52a8\u5feb\u7167"))
        }
        Text(
          text = t(
            language,
            "Automatic snapshots are created before workspace file changes and keep the latest 3.",
            "\u6587\u4ef6\u53d8\u66f4\u524d\u4f1a\u81ea\u52a8\u521b\u5efa\u5feb\u7167\uff0c\u4ec5\u4fdd\u7559\u6700\u8fd1 3 \u4e2a\u3002",
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (state.workspaceSnapshots.isEmpty()) {
            item {
              Text(t(language, "No snapshots yet.", "\u6682\u65e0\u5feb\u7167\u3002"), style = MaterialTheme.typography.bodyMedium)
            }
          }
          items(state.workspaceSnapshots, key = { it.id }) { snapshot ->
            SnapshotListItem(
              snapshot = snapshot,
              language = language,
              onRestore = { restoreTarget = snapshot },
              onDelete = { deleteTarget = snapshot },
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Close", "\u5173\u95ed"))
      }
    },
  )

  restoreTarget?.let { snapshot ->
    AlertDialog(
      onDismissRequest = { restoreTarget = null },
      title = { Text(t(language, "Restore snapshot?", "\u6062\u590d\u5feb\u7167\uff1f")) },
      text = {
        Text(
          t(
            language,
            "This will overwrite the current workspace with ${snapshot.name}.",
            "\u8fd9\u4f1a\u7528 ${snapshot.name} \u8986\u76d6\u5f53\u524d workspace\u3002",
          ),
        )
      },
      confirmButton = {
        Button(
          onClick = {
            controller.restoreWorkspaceSnapshot(snapshot.id)
            restoreTarget = null
          },
        ) {
          Text(t(language, "Restore", "\u6062\u590d"))
        }
      },
      dismissButton = {
        TextButton(onClick = { restoreTarget = null }) {
          Text(t(language, "Cancel", "\u53d6\u6d88"))
        }
      },
    )
  }

  deleteTarget?.let { snapshot ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(t(language, "Delete snapshot?", "\u5220\u9664\u5feb\u7167\uff1f")) },
      text = { Text(snapshot.name) },
      confirmButton = {
        Button(
          onClick = {
            controller.deleteWorkspaceSnapshot(snapshot.id)
            deleteTarget = null
          },
        ) {
          Text(t(language, "Delete", "\u5220\u9664"))
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) {
          Text(t(language, "Cancel", "\u53d6\u6d88"))
        }
      },
    )
  }
}

@Composable
private fun SnapshotListItem(
  snapshot: WorkspaceSnapshotRecord,
  language: String,
  onRestore: () -> Unit,
  onDelete: () -> Unit,
) {
  val kind = if (snapshot.kind == "auto") t(language, "Auto", "\u81ea\u52a8") else t(language, "Manual", "\u624b\u52a8")

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(snapshot.name, style = MaterialTheme.typography.bodyLarge)
      Text(
        "$kind / ${formatSnapshotTime(snapshot.createdAtMillis)} / ${snapshot.fileCount} files / ${snapshot.totalBytes} bytes",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      if (snapshot.reason.isNotBlank()) {
        Text(snapshot.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onRestore) {
          Text(t(language, "Restore", "\u6062\u590d"))
        }
        if (snapshot.kind != "auto") {
          TextButton(onClick = onDelete) {
            Text(t(language, "Delete", "\u5220\u9664"))
          }
        }
      }
    }
  }
}

fun shouldShowStatusToast(status: String): Boolean {
  val normalized = status.trim()
  if (normalized.isBlank() || normalized == "Idle" || normalized == "Ready") return false
  val lower = normalized.lowercase()
  return lower.startsWith("imported ") ||
    listOf(
      "could not",
      "does not exist",
      "failed",
      "invalid",
      "missing",
      "no app",
      "not available",
      "not granted",
      "permission",
      "unable",
    ).any { it in lower }
}

@Composable
private fun AgentFileDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  var agentRulesDraft by remember(state.agentRulesDraft) { mutableStateOf(state.agentRulesDraft) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("AGENT.md") },
    text = {
      OutlinedTextField(
        value = agentRulesDraft,
        onValueChange = { agentRulesDraft = it },
        label = { Text(t(language, "Workspace agent rules", "Workspace agent \u89c4\u5219")) },
        minLines = 10,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(
        onClick = {
          controller.saveAgentRules(agentRulesDraft)
          onDismiss()
        },
      ) {
        Text(t(language, "Save", "\u4fdd\u5b58"))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Cancel", "\u53d6\u6d88"))
      }
    },
  )
}

@Composable
private fun SettingsDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  var providerDraft by remember(state.providerDraft) { mutableStateOf(state.providerDraft) }
  var modelDraft by remember(state.modelDraft) { mutableStateOf(state.modelDraft) }
  var apiKeyDraft by remember(state.apiKeyDraft) { mutableStateOf(state.apiKeyDraft) }
  var languageDraft by remember(state.settings.language) { mutableStateOf(state.settings.language) }
  var themeModeDraft by remember(state.settings.themeMode) { mutableStateOf(state.settings.themeMode) }
  var themeColorDraft by remember(state.settings.themeColor) { mutableStateOf(state.settings.themeColor) }
  var authorityModeDraft by remember(state.settings.agentAuthorityMode) { mutableStateOf(state.settings.agentAuthorityMode) }
  val selectedProvider = ModelProviderCatalog.findProvider(providerDraft) ?: ModelProviderCatalog.defaultProvider
  var providerMenuOpen by remember { mutableStateOf(false) }
  var modelMenuOpen by remember { mutableStateOf(false) }
  var languageMenuOpen by remember { mutableStateOf(false) }
  var authorityMenuOpen by remember { mutableStateOf(false) }
  val themeColorPreview = remember(themeColorDraft) { parseUiColor(themeColorDraft) ?: Color(0xFF76C4D8) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Settings", "\u8bbe\u7f6e")) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("${selectedProvider.label} / $modelDraft", style = MaterialTheme.typography.bodySmall)
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
                  providerDraft = provider.id
                  modelDraft = provider.defaultModel
                  apiKeyDraft = state.settings.apiKeyFor(provider.id)
                },
              )
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            label = { Text(t(language, "Model", "\u6a21\u578b")) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Box {
            OutlinedButton(onClick = { modelMenuOpen = true }) {
              Text(t(language, "Presets", "\u9884\u8bbe"))
            }
            DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
              selectedProvider.suggestedModels.forEach { model ->
                DropdownMenuItem(
                  text = { Text(model) },
                  onClick = {
                    modelMenuOpen = false
                    modelDraft = model
                  },
                )
              }
            }
          }
        }
        OutlinedTextField(
          value = apiKeyDraft,
          onValueChange = { apiKeyDraft = it },
          label = { Text(selectedProvider.apiKeyLabel) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Box {
          OutlinedButton(onClick = { languageMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(t(language, "Language: ${languageLabel(languageDraft)}", "\u8bed\u8a00\uff1a${languageLabel(languageDraft)}"))
          }
          DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
            DropdownMenuItem(
              text = { Text("English") },
              onClick = {
                languageMenuOpen = false
                languageDraft = "en"
              },
            )
            DropdownMenuItem(
              text = { Text("\u4e2d\u6587") },
              onClick = {
                languageMenuOpen = false
                languageDraft = "zh"
              },
            )
          }
        }
        Text(t(language, "Agent authority", "Agent \u6743\u9650"), style = MaterialTheme.typography.titleSmall)
        Box {
          OutlinedButton(onClick = { authorityMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(authorityModeLabel(language, authorityModeDraft))
          }
          DropdownMenu(expanded = authorityMenuOpen, onDismissRequest = { authorityMenuOpen = false }) {
            DropdownMenuItem(
              text = { Text(authorityModeLabel(language, "safe")) },
              onClick = {
                authorityMenuOpen = false
                authorityModeDraft = "safe"
              },
            )
            DropdownMenuItem(
              text = { Text(authorityModeLabel(language, "assisted")) },
              onClick = {
                authorityMenuOpen = false
                authorityModeDraft = "assisted"
              },
            )
          }
        }
        Text(
          t(
            language,
            "Full Authority is planned, but not enabled in this build.",
            "Full Authority \u5df2\u7eb3\u5165\u5f85\u5b9e\u73b0\uff0c\u5f53\u524d\u7248\u672c\u4e0d\u5f00\u653e\u3002",
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        if (state.settingsProposals.isNotEmpty()) {
          Text(t(language, "Pending proposals", "\u5f85\u786e\u8ba4\u63d0\u6848"), style = MaterialTheme.typography.titleSmall)
          state.settingsProposals.forEach { proposal ->
            SettingsProposalItem(
              proposal = proposal,
              language = language,
              onApprove = { controller.approveSettingsProposal(proposal.path) },
              onReject = { controller.rejectSettingsProposal(proposal.path) },
            )
          }
        }
        Text(t(language, "Appearance", "\u5916\u89c2"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedButton(
            onClick = { themeModeDraft = "dark" },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Theme dark" },
            border = BorderStroke(
              1.dp,
              if (themeModeDraft == "dark") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
          ) {
            Text(t(language, "Dark", "\u6697\u8272"))
          }
          OutlinedButton(
            onClick = { themeModeDraft = "light" },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Theme light" },
            border = BorderStroke(
              1.dp,
              if (themeModeDraft == "light") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
          ) {
            Text(t(language, "Light", "\u4eae\u8272"))
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = themeColorDraft,
            onValueChange = { themeColorDraft = it },
            label = { Text(t(language, "Theme color", "\u4e3b\u9898\u8272")) },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Surface(
            modifier = Modifier.size(42.dp).semantics { contentDescription = "Theme color preview" },
            shape = RoundedCornerShape(999.dp),
            color = themeColorPreview,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          ) {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          ThemeColorPreset("#76C4D8", themeColorDraft) { themeColorDraft = it }
          ThemeColorPreset("#9AA7FF", themeColorDraft) { themeColorDraft = it }
          ThemeColorPreset("#D1B56F", themeColorDraft) { themeColorDraft = it }
          ThemeColorPreset("#C989B8", themeColorDraft) { themeColorDraft = it }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          controller.saveModelSettings(
            providerId = providerDraft,
            model = modelDraft,
            apiKey = apiKeyDraft,
            language = languageDraft,
            themeMode = themeModeDraft,
            themeColor = themeColorDraft,
            authorityMode = authorityModeDraft,
          )
          onDismiss()
        },
      ) {
        Text(t(language, "Save", "\u4fdd\u5b58"))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Cancel", "\u53d6\u6d88"))
      }
    },
  )
}

@Composable
private fun HtmlFilePickerRow(
  path: String,
  selected: Boolean,
  pinned: Boolean,
  language: String,
  onOpen: () -> Unit,
  onPin: (Boolean) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedButton(
      onClick = onOpen,
      modifier = Modifier.weight(1f),
    ) {
      val marker = if (pinned) "* " else ""
      Text(if (selected) t(language, "$marker$path  selected", "$marker$path  \u5df2\u9009\u4e2d") else "$marker$path")
    }
    Box {
      IconButton(
        onClick = { menuOpen = true },
        modifier = Modifier.semantics { contentDescription = "HTML actions for $path" },
      ) {
        Icon(Icons.Filled.Menu, contentDescription = null)
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
          text = { Text(if (pinned) t(language, "Unpin", "\u53d6\u6d88\u7f6e\u9876") else t(language, "Pin", "\u7f6e\u9876")) },
          onClick = {
            menuOpen = false
            onPin(!pinned)
          },
        )
      }
    }
  }
}

@Composable
private fun SettingsProposalItem(
  proposal: WorkspaceSettingsProposal,
  language: String,
  onApprove: () -> Unit,
  onReject: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(proposal.title, style = MaterialTheme.typography.bodyLarge)
      if (proposal.reason.isNotBlank()) {
        Text(proposal.reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      }
      Text(
        settingsProposalSummary(proposal),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onApprove) {
          Text(t(language, "Apply", "\u5e94\u7528"))
        }
        TextButton(onClick = onReject) {
          Text(t(language, "Reject", "\u62d2\u7edd"))
        }
      }
    }
  }
}

private fun settingsProposalSummary(proposal: WorkspaceSettingsProposal): String {
  val changes = proposal.changes
  val parts = listOfNotNull(
    changes.provider?.let { "provider=$it" },
    changes.model?.let { "model=$it" },
    changes.selectedHtmlPath?.let { "selectedHtml=$it" },
    changes.maxAgentIterations?.let { "maxIterations=$it" },
    changes.networkEnabled?.let { "network=$it" },
    changes.language?.let { "language=$it" },
    changes.themeMode?.let { "themeMode=$it" },
    changes.themeColor?.let { "themeColor=$it" },
    changes.agentAuthorityMode?.let { "authority=$it" },
  )
  return parts.ifEmpty { listOf(proposal.path) }.joinToString(", ")
}

private fun authorityModeLabel(language: String, authorityMode: String): String {
  return when (authorityMode) {
    "assisted" -> t(language, "Assisted: agent proposes, user confirms", "Assisted\uff1aagent \u63d0\u6848\uff0c\u7528\u6237\u786e\u8ba4")
    else -> t(language, "Safe: read-only app settings", "Safe\uff1a\u53ea\u8bfb app \u8bbe\u7f6e")
  }
}

private fun languageLabel(language: String): String = if (language == "zh") "\u4e2d\u6587" else "English"

@Composable
private fun ThemeColorPreset(colorHex: String, selectedColorHex: String, onSelect: (String) -> Unit) {
  val color = remember(colorHex) { parseUiColor(colorHex) ?: Color(0xFF76C4D8) }
  val selected = colorHex.equals(selectedColorHex.trim(), ignoreCase = true)
  Surface(
    modifier = Modifier
      .size(34.dp)
      .clickable { onSelect(colorHex) }
      .semantics { contentDescription = "Theme color $colorHex" },
    shape = RoundedCornerShape(999.dp),
    color = color,
    border = BorderStroke(
      if (selected) 2.dp else 1.dp,
      if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
    ),
  ) {}
}

private fun parseUiColor(value: String): Color? {
  val normalized = value.trim().removePrefix("#")
  if (!Regex("^[0-9A-Fa-f]{6}$").matches(normalized)) return null
  return Color(("FF$normalized").toLong(16))
}
