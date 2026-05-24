package com.flovera.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flovera.app.agent.AgentContextBudget
import com.flovera.app.config.normalizeBraveSearchApiKey
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
import com.flovera.app.web.FloveraWebBridge
import com.flovera.app.workspace.WorkspaceArtifactJob
import com.flovera.app.workspace.WorkspaceControlledToolProposal
import com.flovera.app.workspace.WorkspaceFileNode
import com.flovera.app.workspace.WorkspaceSettingsProposal
import com.flovera.app.workspace.WorkspaceSnapshotRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

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
  ArtifactJobs,
  Files,
  Snapshots,
  AgentFile,
  Settings,
}

private const val EmptyWebPrompt = "\u53ef\u9009\u62e9 HTML / Markdown / JSON / CSV / Text \u8fdb\u884c\u6253\u5f00"

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
    WorkspacePreview(state = state, controller = controller)

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
      if (state.workspaceArtifactJobs.isNotEmpty()) {
        FloatingActionButton(
          onClick = { activePanel = AgentPanel.ArtifactJobs },
          modifier = Modifier.semantics { contentDescription = "Open artifact jobs" },
          shape = FloveraFabShape,
          containerColor = FloveraFabContainer,
          contentColor = FloveraFabText,
        ) {
          Text("Jobs", modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge)
        }
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

    AgentPanel.ArtifactJobs -> ArtifactJobsDialog(
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
private fun WorkspacePreview(state: AgentScreenState, controller: AgentController) {
  val previewPath = state.selectedPreviewPath
  val previewContent = state.selectedPreviewContent
  val mimeType = state.selectedPreviewMimeType
  val previewUri = state.selectedPreviewUri
  val htmlUrl = state.selectedHtmlUrl
  val isImagePreview = previewPath.isNotBlank() && mimeType.startsWith("image/")
  val isPdfPreview = previewPath.isNotBlank() && isPdfPreview(previewPath, mimeType)
  val isTextPreview = previewPath.isNotBlank() &&
    !previewPath.endsWith(".html", ignoreCase = true) &&
    !previewPath.endsWith(".htm", ignoreCase = true) &&
    !isImagePreview &&
    !isPdfPreview

  if (isTextPreview) {
    WorkspaceTextPreview(
      path = previewPath,
      content = previewContent,
      mimeType = mimeType,
    )
    return
  }

  if (isImagePreview) {
    WorkspaceImagePreview(
      path = previewPath,
      mimeType = mimeType,
      uri = previewUri,
    )
    return
  }

  if (isPdfPreview) {
    WorkspacePdfPreview(
      path = previewPath,
      mimeType = mimeType,
      uri = previewUri,
    )
    return
  }

  WorkspaceWebView(url = htmlUrl, workspaceRootUrl = state.workspaceRootUrl, controller = controller)
}

@Composable
private fun WorkspaceImagePreview(path: String, mimeType: String, uri: String) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(path, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
      Text(mimeType.ifBlank { "image/*" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      Surface(
        modifier = Modifier.fillMaxWidth().weight(1f),
        shape = FloveraSmallShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      ) {
        if (uri.isBlank()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Image preview unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        } else {
          AndroidView(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Image preview for $path" },
            factory = { context ->
              ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
              }
            },
            update = { imageView ->
              imageView.setImageURI(Uri.parse(uri))
            },
          )
        }
      }
    }
  }
}

@Composable
private fun WorkspacePdfPreview(path: String, mimeType: String, uri: String) {
  val context = LocalContext.current
  var pdfError by remember(uri) { mutableStateOf<String?>(null) }
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(path, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
      Text(mimeType.ifBlank { "application/pdf" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      Surface(
        modifier = Modifier.fillMaxWidth().weight(1f),
        shape = FloveraSmallShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      ) {
        if (uri.isBlank()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDF preview unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        } else {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
              modifier = Modifier.fillMaxSize().semantics { contentDescription = "PDF preview for $path" },
              factory = { viewContext ->
                ImageView(viewContext).apply {
                  scaleType = ImageView.ScaleType.FIT_CENTER
                  adjustViewBounds = true
                  setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
              },
              update = { imageView ->
                val bitmap = renderPdfFirstPage(context, uri)
                if (bitmap == null) {
                  pdfError = "PDF preview unavailable"
                  imageView.setImageDrawable(null)
                } else {
                  pdfError = null
                  imageView.setImageBitmap(bitmap)
                }
              },
            )
            pdfError?.let {
              Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WorkspaceTextPreview(path: String, content: String, mimeType: String) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(path, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
      Text(mimeType.ifBlank { "text/plain" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      when {
        isMarkdownPreview(path) -> MarkdownMessageText(content = content, color = MaterialTheme.colorScheme.onSurface)
        isJsonPreview(path, mimeType) -> WorkspaceJsonPreview(content)
        isCsvPreview(path, mimeType) -> WorkspaceCsvPreview(content)
        isCodePreview(path, mimeType) -> WorkspaceCodePreview(content)
        else -> WorkspacePlainTextPreview(content)
      }
    }
  }
}

@Composable
private fun WorkspacePlainTextPreview(content: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Text(
      text = content,
      modifier = Modifier.padding(12.dp),
      color = MaterialTheme.colorScheme.onSurface,
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun WorkspaceJsonPreview(content: String) {
  Text("JSON preview", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  WorkspacePlainTextPreview(prettyJsonPreview(content))
}

@Composable
private fun WorkspaceCsvPreview(content: String) {
  val rows = remember(content) { parseCsvPreview(content) }
  Text("CSV preview", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  if (rows.isEmpty()) {
    WorkspacePlainTextPreview(content)
    return
  }
  Surface(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      rows.forEachIndexed { rowIndex, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          row.forEach { cell ->
            Surface(
              modifier = Modifier.size(width = 132.dp, height = if (rowIndex == 0) 42.dp else 38.dp),
              shape = RoundedCornerShape(6.dp),
              color = if (rowIndex == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              Text(
                text = cell,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
private fun WorkspaceCodePreview(content: String) {
  val lines = remember(content) { content.lineSequence().toList().ifEmpty { listOf("") } }
  Text("Code preview", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  Surface(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      lines.take(400).forEachIndexed { index, line ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = (index + 1).toString().padStart(3, ' '),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            text = line,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      if (lines.size > 400) {
        Text(
          text = "[truncated: showing first 400 lines]",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun WorkspaceWebView(url: String?, workspaceRootUrl: String, controller: AgentController) {
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
    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Workspace WebView" },
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
        addJavascriptInterface(
          FloveraWebBridge(
            context,
            object : FloveraWebBridge.ArtifactActions {
              override fun runAction(actionId: String, inputJson: String): String {
                return controller.runWorkspaceArtifactAction(actionId, inputJson)
              }

              override fun getJob(jobId: String): String {
                return controller.getWorkspaceArtifactJob(jobId)
              }

              override fun cancelJob(jobId: String): String {
                return controller.cancelWorkspaceArtifactJob(jobId)
              }
            },
          ),
          "Flovera",
        )
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

private fun isMarkdownPreview(path: String): Boolean {
  return path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true)
}

private fun isJsonPreview(path: String, mimeType: String): Boolean {
  return mimeType == "application/json" || path.endsWith(".json", ignoreCase = true)
}

private fun isCsvPreview(path: String, mimeType: String): Boolean {
  return mimeType == "text/csv" || path.endsWith(".csv", ignoreCase = true)
}

private fun isCodePreview(path: String, mimeType: String): Boolean {
  val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
  return extension in setOf(
    "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "css",
    "xml", "sql", "sh", "ps1", "rb", "go", "rs", "c", "cpp", "h", "hpp",
  ) || mimeType == "text/x-python" || mimeType == "application/javascript"
}

private fun isPdfPreview(path: String, mimeType: String): Boolean {
  return mimeType == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)
}

private fun renderPdfFirstPage(context: Context, uri: String): Bitmap? {
  return runCatching {
    val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
    descriptor.use { parcel ->
      val renderer = PdfRenderer(parcel)
      try {
        if (renderer.pageCount <= 0) return null
        val page = renderer.openPage(0)
        try {
          val width = page.width.coerceAtLeast(1)
          val height = page.height.coerceAtLeast(1)
          val scale = (1600f / width).coerceIn(1f, 3f)
          val bitmap = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
          )
          bitmap.eraseColor(android.graphics.Color.WHITE)
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          bitmap
        } finally {
          page.close()
        }
      } finally {
        renderer.close()
      }
    }
  }.getOrNull()
}

private fun prettyJsonPreview(content: String): String {
  val trimmed = content.trim()
  if (trimmed.isBlank()) return content
  return runCatching {
    when {
      trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
      trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
      else -> content
    }
  }.getOrDefault(content)
}

private fun parseCsvPreview(content: String, maxRows: Int = 40, maxColumns: Int = 12): List<List<String>> {
  return content.lineSequence()
    .filter { it.isNotBlank() }
    .take(maxRows)
    .map { parseCsvLine(it).take(maxColumns) }
    .toList()
}

private fun parseCsvLine(line: String): List<String> {
  val cells = mutableListOf<String>()
  val current = StringBuilder()
  var quoted = false
  var index = 0
  while (index < line.length) {
    val char = line[index]
    when {
      char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
        current.append('"')
        index += 1
      }
      char == '"' -> quoted = !quoted
      char == ',' && !quoted -> {
        cells += current.toString()
        current.clear()
      }
      else -> current.append(char)
    }
    index += 1
  }
  cells += current.toString()
  return cells
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

  override fun onPageFinished(view: WebView, url: String) {
    view.evaluateJavascript(WorkspaceWebViewHardening.viewportHelperJs, null)
    view.postDelayed(
      {
        view.evaluateJavascript(WorkspaceWebViewHardening.visibleContentCheckJs) { result ->
          if (!WorkspaceWebViewHardening.isVisibleResult(result)) {
            onError("WebView content may be invisible. Check viewport height, offscreen roots, blocked resources, or missing local HTTP routes.")
          }
        }
      },
      WorkspaceWebViewHardening.visibleCheckDelayMs,
    )
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
  val focusManager = LocalFocusManager.current
  val messages = state.session?.messages.orEmpty()
  val latestContextRecord = state.session?.contextRecords?.lastOrNull()
  val visibleMessageCount = messages.size + if (state.assistantDraft == null) 0 else 1
  val assistantDraftScrollKey = state.assistantDraft?.let { draft ->
    "${draft.content.length}:${draft.runEvents.size}:${draft.toolEvents.size}:${draft.runEvents.lastOrNull()?.timestampMillis ?: 0L}"
  }.orEmpty()
  val workspaceMessagePaths = remember(state.workspaceTree) { state.workspaceTree.workspaceMessageLinkPaths() }
  var pendingRevertIndex by remember { mutableStateOf<Int?>(null) }
  var sessionPickerOpen by remember { mutableStateOf(false) }
  var moreMenuOpen by remember { mutableStateOf(false) }
  var stickToConversationBottom by remember(state.session?.id) { mutableStateOf(true) }
  val isDraftSession = state.session != null && state.session.messages.isEmpty()

  LaunchedEffect(listState, visibleMessageCount) {
    snapshotFlow { listState.isScrollInProgress to listState.isNearBottom() }
      .collect { (isScrolling, isNearBottom) ->
        if (isScrolling) {
          stickToConversationBottom = isNearBottom
        }
      }
  }

  LaunchedEffect(state.session?.id, visibleMessageCount) {
    if (visibleMessageCount > 0) {
      stickToConversationBottom = true
      listState.scrollToItem(visibleMessageCount - 1)
    }
  }

  LaunchedEffect(visibleMessageCount, assistantDraftScrollKey, stickToConversationBottom) {
    if (visibleMessageCount > 0 && stickToConversationBottom) {
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
            if (!isDraftSession && latestContextRecord != null) {
              var contextDetailsOpen by remember(latestContextRecord.id) { mutableStateOf(false) }
              Row(
                modifier = Modifier
                  .clickable { contextDetailsOpen = true }
                  .semantics {
                    contentDescription = "Context usage details"
                  },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                ContextUsageRing(latestContextRecord)
                Text(
                  text = formatContextUsageCompact(latestContextRecord, language),
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                  style = MaterialTheme.typography.bodySmall,
                )
              }
              if (contextDetailsOpen) {
                ContextUsageDetailsDialog(
                  record = latestContextRecord,
                  language = language,
                  onDismiss = { contextDetailsOpen = false },
                )
              }
            }
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
                  text = { Text(t(language, "Open Preview", "\u6253\u5f00\u9884\u89c8")) },
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
              if (message.role == SESSION_ROLE_COMPRESSION) {
                CompressionDivider(message)
              } else {
                ConversationRunEvents(message = message, language = language)
                if (shouldShowConversationMessageBubble(message)) {
                  MessageBubble(
                    message = message,
                    pathLinks = remember(message.content, workspaceMessagePaths) {
                      conversationPathLinks(message.content, workspaceMessagePaths)
                    },
                    onOpenPath = {
                      controller.selectWorkspacePreview(it)
                      onDismiss()
                    },
                    onRevert = if (!state.isRunning && message.role == "user") ({ pendingRevertIndex = index }) else null,
                  )
                }
              }
            }
            state.assistantDraft?.let { draft ->
              item(key = "assistant-draft") {
                ConversationRunEvents(message = draft, language = language)
                if (shouldShowConversationMessageBubble(draft)) {
                  MessageBubble(
                    message = draft,
                    pathLinks = remember(draft.content, workspaceMessagePaths) {
                      conversationPathLinks(draft.content, workspaceMessagePaths)
                    },
                    onOpenPath = {
                      controller.selectWorkspacePreview(it)
                      onDismiss()
                    },
                    onRevert = null,
                  )
                }
              }
            }
          }
        }

        if (state.queuedInputs.isNotEmpty()) {
          QueuedMessagesPanel(
            inputs = state.queuedInputs,
            language = language,
            onGuide = controller::markQueuedInputAsGuidance,
            onRemove = controller::removeQueuedInput,
          )
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
          val hasInput = state.input.isNotBlank()
          val actionStopsRun = state.isRunning && !hasInput
          Surface(
            modifier = Modifier
              .size(52.dp)
              .semantics { contentDescription = if (actionStopsRun) "Interrupt agent" else "Send message" }
              .clickable(
                onClick = {
                  focusManager.clearFocus()
                  if (actionStopsRun) controller.interruptAgentRun() else controller.submit()
                },
              ),
            shape = RoundedCornerShape(12.dp),
            color = if (actionStopsRun) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (actionStopsRun) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (actionStopsRun) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
              } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
              }
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
                if (state.settings.webSearchEnabled && state.settings.braveSearchApiKey.isNotBlank()) {
                  t(language, "fetch_url, download_file, and web_search available", "fetch_url\u3001download_file \u548c web_search \u53ef\u7528")
                } else {
                  t(language, "fetch_url and download_file available", "fetch_url \u548c download_file \u53ef\u7528")
                }
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
private fun CompressionDivider(message: SessionMessage) {
  var expanded by remember(message.timestampMillis, message.content) { mutableStateOf(false) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.44f)),
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = "Context compressed",
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = formatMessageTime(message.timestampMillis),
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
        style = MaterialTheme.typography.labelSmall,
      )
      TextButton(onClick = { expanded = !expanded }) {
        Text(
          text = if (expanded) "Hide handoff summary" else "Show handoff summary",
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      if (expanded) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = FloveraSmallShape,
          color = MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          Box(modifier = Modifier.padding(10.dp)) {
            MarkdownMessageText(
              content = message.content,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun QueuedMessagesPanel(
  inputs: List<QueuedAgentInput>,
  language: String,
  onGuide: (Int) -> Unit,
  onRemove: (Int) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      inputs.forEachIndexed { index, input ->
        QueuedMessageRow(
          index = index,
          input = input,
          language = language,
          onGuide = onGuide,
          onRemove = onRemove,
        )
      }
    }
  }
}

@Composable
private fun QueuedMessageRow(
  index: Int,
  input: QueuedAgentInput,
  language: String,
  onGuide: (Int) -> Unit,
  onRemove: (Int) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = if (input.mode == QUEUED_INPUT_GUIDANCE) "\u21b3" else "\u21b1",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
    Text(
      text = input.content,
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodyMedium,
    )
    if (input.mode == QUEUED_INPUT_GUIDANCE) {
      Text(
        text = t(language, "Guidance", "\u5f15\u5bfc"),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
      )
    } else {
      TextButton(
        onClick = { onGuide(index) },
        modifier = Modifier.semantics { contentDescription = "Guide queued message" },
      ) {
        Text(t(language, "Guide", "\u5f15\u5bfc"))
      }
    }
    IconButton(
      onClick = { onRemove(index) },
      modifier = Modifier.semantics { contentDescription = "Remove queued message" },
    ) {
      Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun MessageBubble(
  message: SessionMessage,
  pathLinks: List<String> = emptyList(),
  onOpenPath: (String) -> Unit = {},
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
  var selectionEnabled by remember(message.timestampMillis, message.role, message.content) { mutableStateOf(false) }
  val surfaceModifier = Modifier.fillMaxWidth(0.84f)

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
          MarkdownMessageText(content = message.content, color = textColor)
          if (!selectionEnabled && pathLinks.isNotEmpty()) {
            ConversationPathLinks(
              paths = pathLinks,
              color = textColor,
              onOpenPath = onOpenPath,
            )
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
        }
      }
    }
  }
}

@Composable
private fun ConversationRunEvents(message: SessionMessage, language: String) {
  val events = remember(message.runEvents, message.toolEvents) { compactConversationRunEvents(message) }
  if (events.isEmpty()) return
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    events.forEach { event ->
      ConversationRunEventRow(event = event, language = language)
    }
  }
}

@Composable
private fun ConversationRunEventRow(event: AgentRunTimelineEvent, language: String) {
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  Surface(
    modifier = Modifier.fillMaxWidth(0.86f),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Text(
        text = "\u25B9",
        color = color.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodySmall,
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = compactRunEventTitle(event, language),
          color = color,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        compactRunEventDetail(event)?.let { detail ->
          Text(
            text = detail,
            color = color.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

private fun shouldShowConversationMessageBubble(message: SessionMessage): Boolean {
  if (message.role != "assistant") return message.content.isNotBlank()
  val content = message.content.trim()
  if (content.isBlank()) return false
  return content != "Working..." &&
    content != "Compressing context..." &&
    !content.startsWith("Working...\n\nProgress:")
}

private fun LazyListState.isNearBottom(thresholdPx: Int = 48): Boolean {
  val layout = layoutInfo
  val totalItems = layout.totalItemsCount
  if (totalItems == 0) return true
  val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return true
  if (lastVisible.index < totalItems - 1) return false
  return lastVisible.offset + lastVisible.size <= layout.viewportEndOffset + thresholdPx
}

private fun compactConversationRunEvents(message: SessionMessage): List<AgentRunTimelineEvent> {
  val filtered = message.runEvents.filter { event ->
    when (event.type) {
      "guidance",
      "compression",
      "thinking",
      "tool_call",
      "tool_omitted",
      "run_failed",
      "run_interrupted" -> true
      "final_response_streaming" -> event.status == "running"
      else -> false
    }
  }
  if (filtered.isNotEmpty()) return filtered
  return message.toolEvents.takeLast(6).map { event ->
    AgentRunTimelineEvent(
      type = "tool_call",
      title = "Tool: ${event.name}",
      detail = toolEventInlineDetail(event),
      timestampMillis = event.timestampMillis,
      status = "completed",
    )
  }
}

private fun compactRunEventTitle(event: AgentRunTimelineEvent, language: String): String {
  return when (event.type) {
    "thinking" -> t(language, "Thinking", "\u601d\u8003")
    "tool_call" -> {
      val toolName = event.title.removePrefix("Tool: ").ifBlank { event.title }
      t(language, "Tool: $toolName", "\u5de5\u5177\uff1a$toolName")
    }
    "tool_omitted" -> t(language, event.title, "\u5df2\u9690\u85cf\u66f4\u65e9\u5de5\u5177\u8c03\u7528")
    "final_response_streaming" -> t(language, "Writing answer", "\u6b63\u5728\u8f93\u51fa\u56de\u7b54")
    "run_failed" -> t(language, "Run failed", "\u8fd0\u884c\u5931\u8d25")
    "run_interrupted" -> t(language, "Run interrupted", "\u8fd0\u884c\u5df2\u4e2d\u65ad")
    "compression" -> t(language, event.title, event.title)
    "guidance" -> t(language, "Guidance queued", "\u5df2\u52a0\u5165\u5f15\u5bfc")
    else -> event.title
  }
}

private fun compactRunEventDetail(event: AgentRunTimelineEvent): String? {
  if (event.type == "tool_call") return event.detail.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
  if (event.type == "thinking") {
    return event.detail.takeIf { it.isNotBlank() }
      ?: if (event.status == "running") "Waiting for the next step." else null
  }
  return event.detail.takeIf { it.isNotBlank() }
}

private fun toolEventInlineDetail(event: ToolEvent): String {
  val path = toolEventArg(event.args, "path")
  return when (event.name) {
    "list_files" -> "Listed ${path.ifBlank { "workspace" }}"
    "workspace_search" -> "Searched ${path.ifBlank { "workspace" }}"
    "read_file" -> "Read ${path.ifBlank { "file" }}"
    "write_file" -> "Wrote ${path.ifBlank { "file" }}"
    "edit_file" -> "Edited ${path.ifBlank { "file" }}"
    "python_run" -> "Ran Python"
    "python_package_install" -> "Checked Python package"
    "artifact_inspect" -> "Inspected ${path.ifBlank { "artifact" }}"
    "fetch_url" -> "Fetched URL"
    "download_file" -> "Downloaded ${path.ifBlank { "file" }}"
    "web_search" -> "Searched the web"
    else -> "Ran ${event.name}"
  }
}

private fun toolEventArg(args: String, name: String): String {
  val prefix = "$name="
  return args.split(", ")
    .firstOrNull { it.startsWith(prefix) }
    ?.removePrefix(prefix)
    ?.trim()
    .orEmpty()
}

@Composable
private fun ConversationPathLinks(
  paths: List<String>,
  color: Color,
  onOpenPath: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    paths.take(5).forEach { path ->
      TextButton(
        onClick = { onOpenPath(path) },
        modifier = Modifier.semantics { contentDescription = "Open conversation path $path" },
      ) {
        Text(
          text = path,
          color = color,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
        )
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

@Composable
private fun ContextUsageRing(record: ContextUsageRecord) {
  val permille = effectiveContextPermille(record)
  val rawProgress = ((permille ?: 0).toFloat() / 1_000f).coerceIn(0f, 1f)
  val progress = if (rawProgress == 0f && record.approximateTokens > 0 && effectiveContextWindow(record) != null) {
    0.01f
  } else {
    rawProgress
  }
  val percent = if (permille == null) {
    "?"
  } else {
    val rounded = ((permille + 5) / 10).coerceIn(0, 100)
    if (rounded == 0 && record.approximateTokens > 0) "<1" else rounded.toString()
  }
  val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
  val progressColor = when (record.contextBudgetStatus) {
    AgentContextBudget.STATUS_WATCH -> MaterialTheme.colorScheme.tertiary
    AgentContextBudget.STATUS_COMPRESSION_RECOMMENDED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
  }
  Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
      drawArc(
        color = trackColor,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        style = stroke,
      )
      drawArc(
        color = progressColor,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        style = stroke,
      )
    }
    Text(
      text = percent,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
private fun ContextUsageDetailsDialog(record: ContextUsageRecord, language: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Context", "\u4e0a\u4e0b\u6587")) },
    text = {
      Text(
        text = formatContextUsageDetails(record, language),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "OK", "\u786e\u5b9a"))
      }
    },
  )
}

private fun formatContextUsageCompact(record: ContextUsageRecord, language: String): String {
  val percent = formatContextPercent(record, language)
  val window = effectiveContextWindow(record)
  val used = formatTokenCount(record.approximateTokens)
  val total = window?.let(::formatTokenCount) ?: "?"
  val prefix = if (isEstimatedContextRecord(record)) t(language, "est ", "\u4f30 ") else ""
  return "$prefix$percent · $used/$total"
}

private fun formatContextUsageDetails(record: ContextUsageRecord, language: String): String {
  val window = effectiveContextWindow(record)
  val used = formatTokenCount(record.approximateTokens)
  val total = window?.let(::formatTokenCount) ?: t(language, "unknown", "\u672a\u77e5")
  val requestChars = record.estimatedRequestChars.takeIf { it > 0 }
    ?: (record.inputChars + record.historyChars + record.rulesChars + record.workspaceListingChars)
  val estimateLabel = if (isEstimatedContextRecord(record)) {
    t(language, "Estimated from request characters.", "\u57fa\u4e8e\u8bf7\u6c42\u5b57\u7b26\u6570\u4f30\u7b97\u3002")
  } else {
    t(language, "Reported by provider or tokenizer.", "\u6765\u81ea provider \u6216 tokenizer \u62a5\u544a\u3002")
  }
  return buildString {
    appendLine(
      t(
        language,
        "Used $used tokens, total $total. $estimateLabel",
        "\u5df2\u7528 $used tokens\uff0c\u5171 $total\u3002$estimateLabel",
      ),
    )
    appendLine(
      t(
        language,
        "Flovera automatically compresses background information when the context approaches its budget.",
        "Flovera \u4f1a\u5728\u4e0a\u4e0b\u6587\u63a5\u8fd1\u9884\u7b97\u65f6\u81ea\u52a8\u538b\u7f29\u5176\u80cc\u666f\u4fe1\u606f\u3002",
      ),
    )
    appendLine()
    appendLine(t(language, "Breakdown:", "\u62c6\u5206\uff1a"))
    appendLine("- inputChars=${record.inputChars}")
    appendLine("- historyChars=${record.historyChars}")
    appendLine("- rulesChars=${record.rulesChars}")
    appendLine("- workspaceListingChars=${record.workspaceListingChars}")
    appendLine("- toolSchemaChars=${record.toolSchemaChars}")
    appendLine("- providerOverheadChars=${record.providerOverheadChars}")
    append("- estimatedRequestChars=$requestChars")
  }
}

private fun formatContextPercent(record: ContextUsageRecord, language: String): String {
  val permille = effectiveContextPermille(record) ?: return t(language, "estimate", "\u4f30\u7b97")
  val value = ((permille + 5) / 10).coerceIn(0, 100)
  if (value == 0 && record.approximateTokens > 0) return t(language, "<1%", "<1%")
  return "$value%"
}

private fun effectiveContextWindow(record: ContextUsageRecord): Int? {
  return record.modelContextWindowTokens
    ?: ModelProviderCatalog.findProvider(record.provider)?.contextFor(record.model)?.contextWindowTokens
}

private fun effectiveContextPermille(record: ContextUsageRecord): Int? {
  record.contextUsagePermille?.let { return it }
  val window = effectiveContextWindow(record) ?: return null
  if (window <= 0) return null
  return ((record.approximateTokens.coerceAtLeast(0).toLong() * 1_000L) / window)
    .coerceIn(0L, 1_000L)
    .toInt()
}

private fun isEstimatedContextRecord(record: ContextUsageRecord): Boolean {
  return !record.tokenUsageSource.equals("provider", ignoreCase = true) &&
    !record.tokenUsageSource.equals("provider_reported", ignoreCase = true) &&
    !record.tokenUsageSource.equals("tokenizer", ignoreCase = true)
}

private fun formatTokenCount(tokens: Int): String {
  return when {
    tokens >= 1_000_000 -> {
      val value = tokens / 1_000_000.0
      if (tokens % 1_000_000 == 0) "${tokens / 1_000_000}M" else String.format(Locale.US, "%.1fM", value)
    }
    tokens >= 1_000 -> {
      val value = tokens / 1_000.0
      if (tokens % 1_000 == 0) "${tokens / 1_000}k" else String.format(Locale.US, "%.1fk", value)
    }
    else -> tokens.toString()
  }
}

private fun formatSnapshotTime(timestampMillis: Long): String {
  return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))
}

private fun WorkspaceFileNode?.workspaceMessageLinkPaths(): List<String> {
  if (this == null) return emptyList()
  val paths = mutableListOf<String>()

  fun visit(node: WorkspaceFileNode) {
    if (!node.isDirectory && node.path.isNotBlank()) paths += node.path
    node.children.forEach(::visit)
  }

  visit(this)
  return paths
    .distinct()
    .sortedWith(compareByDescending<String> { it.length }.thenBy { it.lowercase(Locale.US) })
}

private fun conversationPathLinks(content: String, workspacePaths: List<String>): List<String> {
  if (content.isBlank() || workspacePaths.isEmpty()) return emptyList()
  return workspacePaths
    .asSequence()
    .filter { it.length >= 3 && content.hasWorkspacePathOccurrence(it) }
    .take(12)
    .toList()
}

private fun String.hasWorkspacePathOccurrence(path: String): Boolean {
  var start = indexOf(path)
  while (start >= 0) {
    val before = if (start == 0) null else this[start - 1]
    val afterIndex = start + path.length
    val after = if (afterIndex >= length) null else this[afterIndex]
    if (before.isWorkspacePathBoundary() && after.isWorkspacePathBoundary()) return true
    start = indexOf(path, start + 1)
  }
  return false
}

private fun Char?.isWorkspacePathBoundary(): Boolean {
  if (this == null) return true
  if (isLetterOrDigit() || this == '_' || this == '-' || this == '.' || this == '/') return false
  return true
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
  val sortedHtmlFiles = remember(state.htmlFiles, state.settings.pinnedHtmlPaths, state.settings.recentHtmlPaths) {
    val recentRank = state.settings.recentHtmlPaths.withIndex().associate { it.value to it.index }
    state.htmlFiles.sortedWith(
      compareByDescending<String> { it in state.settings.pinnedHtmlPaths }
        .thenBy { recentRank[it] ?: Int.MAX_VALUE }
        .thenBy { it.lowercase() },
    )
  }
  val artifactServerStatusByManifest = remember(state.workspaceArtifactServerStatuses) {
    state.workspaceArtifactServerStatuses.associateBy { it.manifestPath }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Open Preview", "\u6253\u5f00\u9884\u89c8")) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state.workspaceArtifacts.isNotEmpty()) {
          item {
            Text(t(language, "Workspace Apps", "\u5de5\u4f5c\u533a\u5e94\u7528"), style = MaterialTheme.typography.labelLarge)
          }
          items(state.workspaceArtifacts, key = { it.manifestPath }) { artifact ->
            WorkspaceArtifactPickerRow(
              artifact = artifact,
              serverStatus = artifactServerStatusByManifest[artifact.manifestPath],
              language = language,
              onOpen = { previewPath ->
                controller.selectHtmlFile(previewPath)
                onDismiss()
              },
              onStopServer = { controller.stopWorkspaceArtifactServer(artifact.manifestPath) },
            )
          }
        }
        item {
          Text(t(language, "HTML Files", "HTML Files"), style = MaterialTheme.typography.labelLarge)
        }
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

object WorkspaceWebViewHardening {
  const val visibleCheckDelayMs = 180L

  fun isVisibleResult(result: String?): Boolean {
    val raw = result.orEmpty()
    return raw.contains("\\\"visible\\\":true") || raw.contains("\"visible\":true")
  }

  val viewportHelperJs = """
    (function () {
      if (window.__floveraViewportHelperInstalled) return;
      window.__floveraViewportHelperInstalled = true;
      function update() {
        var height = window.innerHeight || document.documentElement.clientHeight || 0;
        var width = window.innerWidth || document.documentElement.clientWidth || 0;
        document.documentElement.style.setProperty('--flovera-viewport-height', height + 'px');
        document.documentElement.style.setProperty('--flovera-viewport-width', width + 'px');
        document.documentElement.style.setProperty('--flovera-safe-bottom', '0px');
        window.FloveraViewport = { height: height, width: width, safeBottom: 0 };
        try {
          window.dispatchEvent(new CustomEvent('flovera:viewport', { detail: window.FloveraViewport }));
        } catch (error) {}
      }
      window.addEventListener('resize', update);
      update();
    })();
  """.trimIndent()

  val visibleContentCheckJs = """
    (function () {
      var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
      var viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
      var body = document.body;
      if (!body) return JSON.stringify({ visible: false, reason: 'no-body' });
      var candidates = Array.prototype.slice.call(body.querySelectorAll('main, [role="main"], section, article, form, button, input, textarea, canvas, svg, img, video, h1, h2, p, div'))
        .filter(function (node) {
          var style = window.getComputedStyle(node);
          if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
          var rect = node.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 && rect.top < viewportHeight && rect.left < viewportWidth;
        });
      return JSON.stringify({
        visible: candidates.length > 0,
        viewportHeight: viewportHeight,
        viewportWidth: viewportWidth,
        bodyHeight: body.scrollHeight || body.offsetHeight || 0,
        visibleCandidates: candidates.length
      });
    })();
  """.trimIndent()
}

@Composable
private fun WorkspaceArtifactPickerRow(
  artifact: com.flovera.app.workspace.WorkspaceArtifact,
  serverStatus: com.flovera.app.workspace.WorkspacePythonHttpRuntimeStatus?,
  language: String,
  onOpen: (String) -> Unit,
  onStopServer: () -> Unit,
) {
  val previewPath = artifact.preview?.path.orEmpty()
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(artifact.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
          listOfNotNull(
            artifact.kind.takeIf { it.isNotBlank() },
            artifact.preview?.kind?.takeIf { it.isNotBlank() }?.let { "preview=$it" },
            serverStatus?.state?.takeIf { it.isNotBlank() }?.let { "server=$it" },
            serverStatus?.port?.let { "port=$it" },
            previewPath.takeIf { it.isNotBlank() },
            artifact.actions.takeIf { it.isNotEmpty() }?.joinToString(prefix = "actions=", separator = ",") { it.id },
          ).joinToString("  "),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
        )
        if (!artifact.valid && artifact.diagnostics.isNotEmpty()) {
          Text(
            artifact.diagnostics.first().message,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (serverStatus?.detail?.isNotBlank() == true) {
          Text(
            serverStatus.detail,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      if (serverStatus?.state == "running") {
        OutlinedButton(onClick = onStopServer) {
          Text(t(language, "Stop", "\u505c\u6b62"))
        }
      }
      OutlinedButton(
        enabled = artifact.valid && previewPath.isNotBlank(),
        onClick = { onOpen(previewPath) },
      ) {
        Text(t(language, "Open", "\u6253\u5f00"))
      }
    }
  }
}

@Composable
private fun ArtifactJobsDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Artifact Jobs", "Artifact Jobs")) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 480.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item {
          OutlinedButton(onClick = controller::refreshWorkspaceFiles, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(t(language, "Refresh", "\u5237\u65b0"))
          }
        }
        if (state.workspaceArtifactJobs.isEmpty()) {
          item {
            Text(t(language, "No artifact jobs yet.", "No artifact jobs yet."), style = MaterialTheme.typography.bodyMedium)
          }
        } else {
          items(state.workspaceArtifactJobs, key = { it.id }) { job ->
            ArtifactJobRow(
              job = job,
              controller = controller,
              language = language,
              onDismiss = onDismiss,
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
}

@Composable
private fun ArtifactJobRow(
  job: WorkspaceArtifactJob,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            "${job.actionId}  ${job.status}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "${job.artifactRootPath}  ${formatSnapshotTime(job.updatedAtMillis)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        OutlinedButton(onClick = { controller.rerunWorkspaceArtifactJob(job.id) }) {
          Text(t(language, "Rerun", "Rerun"))
        }
        OutlinedButton(
          enabled = job.status == "queued" || job.status == "running",
          onClick = { controller.cancelWorkspaceArtifactJob(job.id) },
        ) {
          Text(t(language, "Cancel", "\u53d6\u6d88"))
        }
      }
      ArtifactJobStream("stdout", job.stdout)
      ArtifactJobStream("stderr", job.stderr.ifBlank { job.error })
      if (job.outputPaths.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          job.outputPaths.take(3).forEach { output ->
            OutlinedButton(
              onClick = {
                controller.selectWorkspacePreview(output)
                onDismiss()
              },
            ) {
              Text(output.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ArtifactJobStream(label: String, text: String) {
  if (text.isBlank()) return
  Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = FloveraSmallShape,
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Text(
      text = text.take(1200),
      modifier = Modifier.padding(8.dp),
      color = MaterialTheme.colorScheme.onSurface,
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.bodySmall,
    )
  }
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
  var deleteTarget by remember { mutableStateOf<WorkspaceFileNode?>(null) }
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
                controller.selectWorkspacePreview(path)
                onDismiss()
              },
              onOpenWith = { path -> openWorkspaceFile(context, controller, path) },
              onShare = { path -> shareWorkspaceFile(context, controller, path) },
              onRename = { renameTarget = it },
              onDelete = { deleteTarget = it },
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

  deleteTarget?.let { target ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(t(language, "Delete file?", "\u5220\u9664\u6587\u4ef6\uff1f")) },
      text = {
        Text(
          if (target.isDirectory) {
            t(
              language,
              "This will delete ${target.path} and everything inside it.",
              "\u8fd9\u4f1a\u5220\u9664 ${target.path} \u53ca\u5176\u4e2d\u6240\u6709\u5185\u5bb9\u3002",
            )
          } else {
            target.path
          },
        )
      },
      confirmButton = {
        Button(
          onClick = {
            controller.deleteWorkspacePath(target.path)
            deleteTarget = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
          Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
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
  onDelete: (WorkspaceFileNode) -> Unit,
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
          text = { Text(t(language, "Delete", "\u5220\u9664")) },
          onClick = {
            menuOpen = false
            onDelete(node)
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
  var customOpenAIBaseUrlDraft by remember(state.customOpenAIBaseUrlDraft) {
    mutableStateOf(state.customOpenAIBaseUrlDraft)
  }
  var customOpenAIChatPathDraft by remember(state.customOpenAIChatCompletionsPathDraft) {
    mutableStateOf(state.customOpenAIChatCompletionsPathDraft)
  }
  var customOpenAICompatibilityModeDraft by remember(state.customOpenAICompatibilityModeDraft) {
    mutableStateOf(state.customOpenAICompatibilityModeDraft)
  }
  var languageDraft by remember(state.settings.language) { mutableStateOf(state.settings.language) }
  var themeModeDraft by remember(state.settings.themeMode) { mutableStateOf(state.settings.themeMode) }
  var themeColorDraft by remember(state.settings.themeColor) { mutableStateOf(state.settings.themeColor) }
  var authorityModeDraft by remember(state.settings.agentAuthorityMode) { mutableStateOf(state.settings.agentAuthorityMode) }
  var deepSeekThinkingEffortDraft by remember(state.settings.deepSeekThinkingEffort) {
    mutableStateOf(state.settings.deepSeekThinkingEffort)
  }
  var webSearchEnabledDraft by remember(state.settings.webSearchEnabled) { mutableStateOf(state.settings.webSearchEnabled) }
  var braveSearchApiKeyDraft by remember(state.settings.braveSearchApiKey) { mutableStateOf(state.settings.braveSearchApiKey) }
  val selectedProvider = ModelProviderCatalog.findProvider(providerDraft) ?: ModelProviderCatalog.defaultProvider
  var providerMenuOpen by remember { mutableStateOf(false) }
  var modelMenuOpen by remember { mutableStateOf(false) }
  var languageMenuOpen by remember { mutableStateOf(false) }
  var authorityMenuOpen by remember { mutableStateOf(false) }
  var deepSeekThinkingMenuOpen by remember { mutableStateOf(false) }
  var customOpenAICompatibilityMenuOpen by remember { mutableStateOf(false) }
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
                  customOpenAIBaseUrlDraft = state.settings.customOpenAIProvider.baseUrl
                  customOpenAIChatPathDraft = state.settings.customOpenAIProvider.chatCompletionsPath
                  customOpenAICompatibilityModeDraft = state.settings.customOpenAIProvider.compatibilityMode
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
        if (selectedProvider.id == "custom-openai") {
          Text(t(language, "Custom endpoint", "\u81ea\u5b9a\u4e49\u7aef\u70b9"), style = MaterialTheme.typography.titleSmall)
          OutlinedTextField(
            value = customOpenAIBaseUrlDraft,
            onValueChange = { customOpenAIBaseUrlDraft = it },
            label = { Text(t(language, "Base URL", "Base URL")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = customOpenAIChatPathDraft,
            onValueChange = { customOpenAIChatPathDraft = it },
            label = { Text(t(language, "Chat completions path", "Chat completions path")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
          Box {
            OutlinedButton(
              onClick = { customOpenAICompatibilityMenuOpen = true },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(customOpenAICompatibilityModeLabel(language, customOpenAICompatibilityModeDraft))
            }
            DropdownMenu(
              expanded = customOpenAICompatibilityMenuOpen,
              onDismissRequest = { customOpenAICompatibilityMenuOpen = false },
            ) {
              listOf("generic", "ollama").forEach { mode ->
                DropdownMenuItem(
                  text = { Text(customOpenAICompatibilityModeLabel(language, mode)) },
                  onClick = {
                    customOpenAICompatibilityMenuOpen = false
                    customOpenAICompatibilityModeDraft = mode
                  },
                )
              }
            }
          }
          Text(
            t(
              language,
              "Ollama mode adds the profile-controlled num_ctx option from model context. Custom request bodies are not enabled.",
              "Ollama \u6a21\u5f0f\u4f1a\u6839\u636e\u6a21\u578b\u4e0a\u4e0b\u6587\u6ce8\u5165 profile \u63a7\u5236\u7684 num_ctx\u3002\u5f53\u524d\u4e0d\u5f00\u653e\u81ea\u5b9a\u4e49\u8bf7\u6c42\u4f53\u3002",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (selectedProvider.id == "deepseek") {
          Text(t(language, "DeepSeek", "DeepSeek"), style = MaterialTheme.typography.titleSmall)
          Box {
            OutlinedButton(onClick = { deepSeekThinkingMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
              Text(deepSeekThinkingEffortLabel(language, deepSeekThinkingEffortDraft))
            }
            DropdownMenu(expanded = deepSeekThinkingMenuOpen, onDismissRequest = { deepSeekThinkingMenuOpen = false }) {
              listOf("off", "high", "max").forEach { effort ->
                DropdownMenuItem(
                  text = { Text(deepSeekThinkingEffortLabel(language, effort)) },
                  onClick = {
                    deepSeekThinkingMenuOpen = false
                    deepSeekThinkingEffortDraft = effort
                  },
                )
              }
            }
          }
        }
        Text(t(language, "Web search", "Web search"), style = MaterialTheme.typography.titleSmall)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(t(language, "Brave Search", "Brave Search"), style = MaterialTheme.typography.bodyMedium)
            Text(
              t(
                language,
                "Requires Network enabled in each conversation.",
                "\u9700\u8981\u5728\u6bcf\u6b21\u5bf9\u8bdd\u4e2d\u6253\u5f00\u8054\u7f51\u5f00\u5173\u3002",
              ),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(
            checked = webSearchEnabledDraft,
            onCheckedChange = { webSearchEnabledDraft = it },
            modifier = Modifier.semantics { contentDescription = "Web search switch" },
          )
        }
        OutlinedTextField(
          value = braveSearchApiKeyDraft,
          onValueChange = { braveSearchApiKeyDraft = normalizeBraveSearchApiKey(it) },
          label = { Text(t(language, "Brave Search API key", "Brave Search API key")) },
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
            DropdownMenuItem(
              text = { Text(authorityModeLabel(language, "full")) },
              onClick = {
                authorityMenuOpen = false
                authorityModeDraft = "full"
              },
            )
          }
        }
        Text(
          t(
            language,
            "Full Authority auto-applies settings proposals after a workspace snapshot and audit log. Android permissions and secrets remain app-owned boundaries.",
            "Full Authority 会在创建 workspace 快照和审计日志后自动应用设置提案。Android 权限和密钥仍然是 app 边界。",
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
        if (state.controlledToolProposals.isNotEmpty()) {
          Text(t(language, "Tool proposals", "\u5de5\u5177\u63d0\u6848"), style = MaterialTheme.typography.titleSmall)
          state.controlledToolProposals.forEach { proposal ->
            ControlledToolProposalItem(
              proposal = proposal,
              language = language,
              onDismiss = { controller.dismissControlledToolProposal(proposal.path) },
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
            customOpenAIBaseUrl = customOpenAIBaseUrlDraft,
            customOpenAIChatCompletionsPath = customOpenAIChatPathDraft,
            customOpenAICompatibilityMode = customOpenAICompatibilityModeDraft,
            language = languageDraft,
            themeMode = themeModeDraft,
            themeColor = themeColorDraft,
            authorityMode = authorityModeDraft,
            deepSeekThinkingEffort = deepSeekThinkingEffortDraft,
            webSearchEnabled = webSearchEnabledDraft,
            braveSearchApiKey = braveSearchApiKeyDraft,
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

@Composable
private fun ControlledToolProposalItem(
  proposal: WorkspaceControlledToolProposal,
  language: String,
  onDismiss: () -> Unit,
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
        controlledToolProposalSummary(proposal),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        t(
          language,
          "Recorded only. Tool and MCP installation are not enabled in this build.",
          "\u4ec5\u8bb0\u5f55\u63d0\u6848\u3002\u5f53\u524d\u7248\u672c\u4e0d\u5f00\u653e\u5de5\u5177\u6216 MCP \u5b89\u88c5\u3002",
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = onDismiss) {
        Text(t(language, "Dismiss", "\u5ffd\u7565"))
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
    changes.webSearchEnabled?.let { "webSearch=$it" },
    changes.language?.let { "language=$it" },
    changes.themeMode?.let { "themeMode=$it" },
    changes.themeColor?.let { "themeColor=$it" },
    changes.agentAuthorityMode?.let { "authority=$it" },
    changes.deepSeekThinkingEffort?.let { "deepSeekThinking=$it" },
    changes.customOpenAIBaseUrl?.let { "customBaseUrl=$it" },
    changes.customOpenAIChatCompletionsPath?.let { "customChatPath=$it" },
    changes.customOpenAICompatibilityMode?.let { "customCompatibility=$it" },
    changes.modelContextWindowTokens?.let { "context=$it" },
    changes.modelCompressionThresholdPercent?.let { "compression=$it%" },
  )
  return parts.ifEmpty { listOf(proposal.path) }.joinToString(", ")
}

private fun controlledToolProposalSummary(proposal: WorkspaceControlledToolProposal): String {
  val parts = listOfNotNull(
    "type=${proposal.type}",
    proposal.name.takeIf { it.isNotBlank() }?.let { "name=$it" },
    proposal.command.takeIf { it.isNotBlank() }?.let { "command=$it" },
    proposal.endpoint.takeIf { it.isNotBlank() }?.let { "endpoint=$it" },
    proposal.requestedCapabilities.takeIf { it.isNotEmpty() }?.joinToString(prefix = "capabilities=", separator = "|"),
    proposal.permissions.takeIf { it.isNotEmpty() }?.joinToString(prefix = "permissions=", separator = "|"),
  )
  return parts.joinToString(", ")
}

private fun authorityModeLabel(language: String, authorityMode: String): String {
  return when (authorityMode) {
    "assisted" -> t(language, "Assisted: agent proposes, user confirms", "Assisted\uff1aagent \u63d0\u6848\uff0c\u7528\u6237\u786e\u8ba4")
    "full" -> t(language, "Full Authority: auto-apply proposals", "Full Authority\uff1a\u81ea\u52a8\u5e94\u7528\u63d0\u6848")
    else -> t(language, "Safe: read-only app settings", "Safe\uff1a\u53ea\u8bfb app \u8bbe\u7f6e")
  }
}

private fun deepSeekThinkingEffortLabel(language: String, effort: String): String {
  return when (effort) {
    "off" -> t(language, "Thinking: off", "\u601d\u8003\uff1a\u5173\u95ed")
    "max" -> t(language, "Thinking: max", "\u601d\u8003\uff1a\u6700\u9ad8")
    else -> t(language, "Thinking: high", "\u601d\u8003\uff1a\u9ad8")
  }
}

private fun customOpenAICompatibilityModeLabel(language: String, mode: String): String {
  return when (mode) {
    "ollama" -> t(language, "Compatibility: Ollama", "\u517c\u5bb9\uff1aOllama")
    else -> t(language, "Compatibility: generic OpenAI", "\u517c\u5bb9\uff1a\u901a\u7528 OpenAI")
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
