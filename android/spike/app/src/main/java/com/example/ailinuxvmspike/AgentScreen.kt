package com.example.ailinuxvmspike

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ailinuxvmspike.koog.ModelProviderCatalog
import com.example.ailinuxvmspike.session.SessionMessage
import com.example.ailinuxvmspike.workspace.WorkspaceFileNode
import kotlinx.coroutines.launch

private enum class AgentPanel {
  Conversation,
  HtmlFiles,
  Sessions,
  Files,
  AgentFile,
  Settings,
}

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
          text = { Text("Sessions") },
          onClick = {
            menuOpen = false
            activePanel = AgentPanel.Sessions
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
      onDismiss = { activePanel = null },
    )

    AgentPanel.HtmlFiles -> HtmlFilesDialog(
      state = state,
      controller = controller,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Sessions -> SessionsDialog(
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
        text = "可选择html进行打开",
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
  val scope = rememberCoroutineScope()

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
            Text("Conversation", style = MaterialTheme.typography.titleMedium)
            Text(
              text = state.status,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          TextButton(onClick = onDismiss) {
            Text("Close")
          }
        }

        LazyColumn(
          modifier = Modifier.fillMaxWidth().weight(1f),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val messages = state.session?.messages.orEmpty()
          if (messages.isEmpty()) {
            item {
              Text(
                text = "Ask the agent to create or edit files in the current workspace.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          } else {
            items(messages) { message ->
              MessageBubble(message)
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
        Button(
          onClick = { scope.launch { controller.submit() } },
          enabled = !state.isRunning,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (state.isRunning) "Running..." else "Send")
        }
      }
    }
  }
}

@Composable
private fun MessageBubble(message: SessionMessage) {
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

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = horizontal) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.84f),
      shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 18.dp,
      ),
      color = bubbleColor,
      tonalElevation = 1.dp,
    ) {
      SelectionContainer {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = when {
              isUser -> "You"
              isError -> "Error"
              else -> "Assistant"
            },
            color = textColor.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
          )
          Text(text = message.content, color = textColor, style = MaterialTheme.typography.bodyMedium)
          message.toolEvents.forEach { event ->
            Surface(shape = RoundedCornerShape(10.dp), color = textColor.copy(alpha = 0.10f)) {
              Text(
                text = "${event.name}: ${event.result}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                color = textColor,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }
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
          OutlinedButton(onClick = controller::newSession, modifier = Modifier.weight(1f)) {
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
            title = session.title,
            subtitle = if (session.pinnedAtMillis == null) {
              "${session.messages.size} messages"
            } else {
              "Pinned / ${session.messages.size} messages"
            },
            active = session.id == state.session?.id,
            menuContent = { closeMenu ->
              DropdownMenuItem(
                text = { Text("Open") },
                onClick = {
                  closeMenu()
                  controller.openSession(session.id)
                  onDismiss()
                },
              )
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
  title: String,
  subtitle: String,
  active: Boolean,
  menuContent: @Composable (closeMenu: () -> Unit) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxWidth(),
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
            Text("☰", style = MaterialTheme.typography.titleMedium)
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
              onOpenHtml = { path ->
                controller.selectHtmlFile(path)
                onDismiss()
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
  onOpenHtml: (String) -> Unit,
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
        if (node.isDirectory) onToggle(node.path)
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
        Text("☰", style = MaterialTheme.typography.titleMedium)
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        if (!node.isDirectory && node.path.endsWith(".html", ignoreCase = true)) {
          DropdownMenuItem(
            text = { Text("Open in WebView") },
            onClick = {
              menuOpen = false
              onOpenHtml(node.path)
            },
          )
        }
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
        onOpenHtml = onOpenHtml,
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
