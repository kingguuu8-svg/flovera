package com.flovera.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.speech.RecognizerIntent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
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
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flovera.app.agent.AgentContextBudget
import com.flovera.app.config.AppSettings
import com.flovera.app.config.WorkspaceSecret
import com.flovera.app.config.normalizeBraveSearchApiKey
import com.flovera.app.koog.ModelProviderCatalog
import com.flovera.app.platform.AndroidPermissionCapabilities
import com.flovera.app.platform.AndroidPermissionType
import com.flovera.app.platform.findActivity
import com.flovera.app.session.ContextUsageRecord
import com.flovera.app.session.ConversationTranscriptEvent
import com.flovera.app.session.AgentRunTimelineEvent
import com.flovera.app.session.SESSION_ROLE_COMPRESSION
import com.flovera.app.session.SessionMessage
import com.flovera.app.session.ToolEvent
import com.flovera.app.web.FloveraWebBridge
import com.flovera.app.workspace.WorkspaceArtifactJob
import com.flovera.app.workspace.WorkspaceControlledToolProposal
import com.flovera.app.workspace.WorkspaceDocumentPreview
import com.flovera.app.workspace.WorkspaceDocumentPreviewParser
import com.flovera.app.workspace.WorkspaceFileNode
import com.flovera.app.workspace.WorkspaceSettingsProposal
import com.flovera.app.workspace.WorkspaceSnapshotRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val FloveraFabShape = RoundedCornerShape(999.dp)
private val FloveraPanelShape = RoundedCornerShape(18.dp)
private val FloveraSmallShape = RoundedCornerShape(8.dp)
private val FloveraAssistantBubbleBorder = Color(0xFF2C3137)
private val FloveraFabContainer = Color(0xFF172229)
private val FloveraFabText = Color(0xFFDEF3F8)
private val FloveraEmptyPanel = Color(0xFFF7F6F1)
private val FloveraEmptyPanelBorder = Color(0xFFE4DED2)
private val FloveraAnchorIconSize = 56.dp
private const val MARKDOWN_SEGMENTED_RENDER_MIN_CHARS = 1600
private const val MARKDOWN_SEGMENT_TARGET_CHARS = 900
private const val MARKDOWN_SEGMENT_MAX_CHARS = 1400

private enum class ConversationScrollMode {
  BottomLocked,
  Free,
}
private val FloveraDesignBackground = Color(0xFFF7FAF9)
private val FloveraDesignSurface = Color(0xFFFFFFFF)
private val FloveraDesignElevated = Color(0xFFE9F0EF)
private val FloveraDesignLine = Color(0xFFD4DEDC)
private val FloveraDesignText = Color(0xFF1D232A)
private val FloveraDesignMuted = Color(0xFF667174)
private val FloveraDesignAssistantBubble = Color(0xFFEEF2F1)
private val FloveraDesignDarkSettingMask = Color(0xFF5A3446)
private val FloveraDesignDarkSettingOnMask = Color(0xFFFFD8E7)
private const val WebChromeColorSampleDelayMs = 120L
@Composable
private fun floveraDesignFrontendEnabled(): Boolean {
  return LocalContext.current.resources.getBoolean(R.bool.design_frontend_style_enabled)
}

@Composable
private fun floveraDesignStyleEnabled(): Boolean {
  return floveraDesignFrontendEnabled() && MaterialTheme.colorScheme.background.luminance() > 0.5f
}

@Composable
private fun floveraMarkDrawableForTheme(): Int {
  return if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
    R.drawable.ic_flovera_mark_light
  } else {
    R.drawable.ic_flovera_mark_dark
  }
}

private val workspaceChromeSampleRunnables = java.util.WeakHashMap<WebView, Runnable>()

private fun WebView.scheduleWorkspaceChromeColorSample(onSampled: (Color?) -> Unit) {
  workspaceChromeSampleRunnables.remove(this)?.let(::removeCallbacks)
  val task = Runnable {
    workspaceChromeSampleRunnables.remove(this)
    evaluateJavascript(WorkspaceWebViewHardening.chromeColorSampleJs) { result ->
      onSampled(parseWorkspaceChromeColorSample(result))
    }
  }
  workspaceChromeSampleRunnables[this] = task
  postDelayed(task, WebChromeColorSampleDelayMs)
}

private fun parseWorkspaceChromeColorSample(result: String?): Color? {
  val raw = result?.trim().orEmpty()
  if (raw.isBlank() || raw == "null") return null
  val decoded = runCatching { JSONArray("[$raw]").optString(0) }.getOrNull()?.ifBlank { null } ?: raw
  val payload = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
  if (!payload.optBoolean("ok")) return null
  val hex = payload.optString("color").trim()
  if (!Regex("""^#[0-9a-fA-F]{6}$""").matches(hex)) return null
  return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

private fun floveraAdaptiveChromeColor(sample: Color): Color {
  return sample.copy(alpha = 1f)
}

private fun floveraChromeBorderColor(color: Color, designStyle: Boolean): Color {
  return if (color.luminance() > 0.5f) {
    if (designStyle) FloveraDesignLine else Color.Black.copy(alpha = 0.12f)
  } else {
    Color.White.copy(alpha = 0.14f)
  }
}

private enum class AgentPanel {
  Conversation,
  HtmlFiles,
  ArtifactJobs,
  Files,
  Skills,
  Secrets,
  Permissions,
  AgentFile,
  Settings,
}

private const val EmptyWebPrompt = "\u548c Flovera \u5bf9\u8bdd\u6765\u521b\u5efa\u9879\u76ee"

private sealed interface ConversationDisplayBlock {
  val id: String
}

private data class ConversationMessageDisplayBlock(
  override val id: String,
  val message: SessionMessage,
  val streaming: Boolean,
  val sourceMessageIndex: Int?,
) : ConversationDisplayBlock

private data class ConversationTimelineDisplayBlock(
  override val id: String,
  val event: AgentRunTimelineEvent,
) : ConversationDisplayBlock

private data class ConversationCompressionDisplayBlock(
  override val id: String,
  val message: SessionMessage,
) : ConversationDisplayBlock

@Composable
fun AgentScreen(controller: AgentController, modifier: Modifier = Modifier) {
  val state by controller.state.collectAsStateWithLifecycle()
  val language = state.settings.language
  val context = LocalContext.current
  val designFrontend = floveraDesignFrontendEnabled()
  val designStyle = floveraDesignStyleEnabled()
  var panelStack by remember { mutableStateOf<List<AgentPanel>>(emptyList()) }
  val activePanel = panelStack.lastOrNull()
  val hasPreviewSurface = state.selectedPreviewPath.isNotBlank() || !state.selectedHtmlUrl.isNullOrBlank()
  val hasUsableApi = hasUsableProviderApi(state.settings)
  val displayTargetPath = currentDisplayTargetPath(state)
  var previewChromeColor by remember { mutableStateOf<Color?>(null) }
  fun openPanelFromMainDisplay(panel: AgentPanel) {
    panelStack = listOf(panel)
  }
  fun openPanelFromActivePanel(panel: AgentPanel) {
    panelStack = panelStack + panel
  }
  fun dismissTopPanel() {
    panelStack = panelStack.dropLast(1)
  }
  fun dismissConversationPanel() {
    controller.discardEmptyDraftSession()
    panelStack = emptyList()
  }
  fun submitFromMainDisplay() {
    val trimmed = state.input.trim()
    if (trimmed.isBlank() && !state.isRunning) return
    if (!hasUsableApi && !state.isRunning) {
      openPanelFromMainDisplay(AgentPanel.Settings)
      return
    }
    val createsNewProjectSession = !hasPreviewSurface && state.session?.messages.orEmpty().isEmpty()
    if (createsNewProjectSession && !state.isRunning) {
      controller.submitInNewSession(trimmed)
    } else {
      controller.submit()
    }
  }

  LaunchedEffect(state.status) {
    if (shouldShowStatusToast(state.status)) {
      Toast.makeText(context, state.status, Toast.LENGTH_SHORT).show()
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(if (designStyle) FloveraDesignBackground else MaterialTheme.colorScheme.background),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
      ) {
        WorkspacePreview(
          state = state,
          controller = controller,
          chromeColorSamplingEnabled = designFrontend,
          onChromeColorSampled = { previewChromeColor = it },
        )
      }
      MainDisplayBottomBar(
        state = state,
        controller = controller,
        language = language,
        hasPreviewSurface = hasPreviewSurface,
        hasUsableApi = hasUsableApi,
        displayTargetPath = displayTargetPath,
        displayMimeType = currentDisplayMimeType(state),
        onOpenPreview = { openPanelFromMainDisplay(AgentPanel.HtmlFiles) },
        onOpenConversation = { openPanelFromMainDisplay(AgentPanel.Conversation) },
        onOpenSettings = { openPanelFromMainDisplay(AgentPanel.Settings) },
        previewChromeColor = previewChromeColor,
        onSubmit = ::submitFromMainDisplay,
      )
    }
  }

  when (activePanel) {
    AgentPanel.Conversation -> ConversationDialog(
      state = state,
      controller = controller,
      language = language,
      onOpenPanel = ::openPanelFromActivePanel,
      onShowDisplay = ::dismissConversationPanel,
      onDismiss = ::dismissConversationPanel,
    )

    AgentPanel.HtmlFiles -> HtmlFilesDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
      onShowDisplay = ::dismissConversationPanel,
    )

    AgentPanel.ArtifactJobs -> ArtifactJobsDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
      onShowDisplay = ::dismissConversationPanel,
    )

    AgentPanel.Files -> FilesDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
      onShowDisplay = ::dismissConversationPanel,
    )

    AgentPanel.Skills -> SkillsDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
    )

    AgentPanel.Secrets -> SecretsDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
    )

    AgentPanel.Permissions -> PermissionsDialog(
      language = language,
      onDismiss = ::dismissTopPanel,
    )

    AgentPanel.AgentFile -> AgentFileDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
    )

    AgentPanel.Settings -> SettingsDialog(
      state = state,
      controller = controller,
      language = language,
      onDismiss = ::dismissTopPanel,
    )

    null -> Unit
  }
}

@Composable
private fun FloveraEntryCluster(
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenConversation: () -> Unit,
  onOpenPreview: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (expanded) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FloveraBubbleAction(
          label = "Agent",
          icon = { Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(18.dp)) },
          contentDescription = "Open agent conversation",
          onClick = onOpenConversation,
        )
        FloveraBubbleAction(
          label = "\u9884\u89c8",
          icon = { Icon(Icons.Filled.Preview, contentDescription = null, modifier = Modifier.size(18.dp)) },
          contentDescription = "Open preview picker",
          onClick = onOpenPreview,
        )
      }
    }
    FloveraIconAnchor(
      contentDescription = if (expanded) "Close Flovera entry drawer" else "Open Flovera entry drawer",
      onClick = onToggle,
    )
  }
}

@Composable
private fun FloveraBubbleAction(
  label: String,
  icon: @Composable () -> Unit,
  contentDescription: String,
  onClick: () -> Unit,
) {
  val designFrontend = floveraDesignFrontendEnabled()
  val designLight = floveraDesignStyleEnabled()
  Surface(
    modifier = Modifier
      .height(44.dp)
      .semantics { this.contentDescription = contentDescription }
      .clickable(onClick = onClick),
    shape = FloveraFabShape,
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 4.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(7.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon()
      Text(label, style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
private fun FloveraIconAnchor(
  contentDescription: String,
  onClick: () -> Unit,
) {
  val designStyle = floveraDesignStyleEnabled()
  val designFrontend = floveraDesignFrontendEnabled()
  FloatingActionButton(
    onClick = onClick,
    modifier = Modifier.semantics { this.contentDescription = contentDescription },
    shape = FloveraFabShape,
    containerColor = if (designStyle) MaterialTheme.colorScheme.primaryContainer else FloveraFabContainer,
    contentColor = if (designStyle) MaterialTheme.colorScheme.primary else FloveraFabText,
  ) {
    Image(
      painter = painterResource(
        id = if (designFrontend) floveraMarkDrawableForTheme() else R.drawable.ic_launcher_foreground,
      ),
      contentDescription = null,
      modifier = Modifier.size(if (designFrontend) 34.dp else FloveraAnchorIconSize),
    )
  }
}

@Composable
private fun DisplayTargetPill(
  path: String,
  mimeType: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val designStyle = floveraDesignStyleEnabled()
  Surface(
    modifier = modifier
      .widthIn(max = 310.dp)
      .semantics { contentDescription = "Current display target" }
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(if (designStyle) 8.dp else 999.dp),
    color = if (designStyle) FloveraDesignSurface.copy(alpha = 0.94f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    contentColor = if (designStyle) FloveraDesignText else MaterialTheme.colorScheme.onSurface,
    tonalElevation = if (designStyle) 0.dp else 3.dp,
    border = BorderStroke(1.dp, if (designStyle) FloveraDesignLine else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Filled.Preview, contentDescription = null, modifier = Modifier.size(17.dp))
      Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
          text = path,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelMedium,
        )
        if (mimeType.isNotBlank()) {
          Text(
            text = mimeType,
            color = if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
  }
}

@Composable
private fun MissingApiSettingsEntry(
  language: String,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .heightIn(min = 60.dp)
      .semantics { contentDescription = "Open settings to configure model API" }
      .clickable(onClick = onOpenSettings),
    shape = FloveraFabShape,
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    tonalElevation = 2.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp),
      ) {
        Text(
          t(language, "Model API not configured", "\u5c1a\u672a\u914d\u7f6e\u6a21\u578b API"),
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.labelLarge,
        )
        Text(
          t(
            language,
            "Configure an API key to start chatting",
            "\u914d\u7f6e API \u5bc6\u94a5\u540e\u5373\u53ef\u5f00\u59cb\u5bf9\u8bdd",
          ),
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun MainDisplayBottomBar(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  hasPreviewSurface: Boolean,
  hasUsableApi: Boolean,
  displayTargetPath: String,
  displayMimeType: String,
  onOpenPreview: () -> Unit,
  onOpenConversation: () -> Unit,
  onOpenSettings: () -> Unit,
  previewChromeColor: Color?,
  onSubmit: () -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val hasInput = state.input.isNotBlank()
  val actionOpensSettings = !hasUsableApi && !state.isRunning
  val bottomInsetPadding = navigationBarsBottomPaddingWhenImeHidden()
  val designFrontend = floveraDesignFrontendEnabled()
  val designStyle = floveraDesignStyleEnabled()
  val hasAdaptiveChrome = designFrontend && previewChromeColor != null
  val baseBarColor = if (designStyle) FloveraDesignSurface.copy(alpha = 0.97f) else MaterialTheme.colorScheme.surface
  val adaptiveBarColor = remember(designFrontend, previewChromeColor, baseBarColor) {
    previewChromeColor
      ?.takeIf { designFrontend }
      ?.let { floveraAdaptiveChromeColor(sample = it) }
      ?: baseBarColor
  }
  val barColor by animateColorAsState(
    targetValue = adaptiveBarColor,
    animationSpec = tween(durationMillis = 220),
    label = "Main display bottom bar chrome",
  )
  val barContentColor = if (barColor.luminance() > 0.5f) {
    if (designStyle) FloveraDesignText else MaterialTheme.colorScheme.onSurface
  } else {
    Color.White.copy(alpha = 0.9f)
  }
  val barMutedColor = if (barColor.luminance() > 0.5f) {
    if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant
  } else {
    Color.White.copy(alpha = 0.72f)
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth(),
    color = barColor,
    contentColor = barContentColor,
    tonalElevation = if (hasAdaptiveChrome || designStyle) 0.dp else 4.dp,
    border = if (hasAdaptiveChrome) null else BorderStroke(1.dp, floveraChromeBorderColor(barColor, designStyle)),
  ) {
    Column(
      modifier = Modifier.padding(
        start = 10.dp,
        top = 6.dp,
        end = 10.dp,
        bottom = 6.dp + bottomInsetPadding,
      ),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (!hasUsableApi) {
        MissingApiSettingsEntry(
          language = language,
          onOpenSettings = onOpenSettings,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      BottomDisplayStatusLine(
        path = displayTargetPath,
        mimeType = displayMimeType,
        isRunning = state.isRunning,
        status = state.status,
        language = language,
        contentColor = barMutedColor,
        progressColor = if (barColor.luminance() > 0.5f) {
          MaterialTheme.colorScheme.primary
        } else {
          Color.White.copy(alpha = 0.86f)
        },
        onClick = onOpenPreview,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        CompactMainInput(
          value = state.input,
          onValueChange = controller::updateInput,
          enabled = hasUsableApi || state.isRunning,
          placeholder = if (hasUsableApi || state.isRunning) {
            t(language, "Message Flovera to create or edit", "\u548c Flovera \u5bf9\u8bdd\u6765\u521b\u5efa\u6216\u4fee\u6539")
          } else {
            t(language, "Configure model API to start", "\u8bf7\u5148\u914d\u7f6e\u6a21\u578b API")
          },
          modifier = Modifier.weight(1f),
        )
        CompactBarAction(
          contentDescription = when {
            actionOpensSettings -> "Open model settings"
            !hasInput -> "Open conversation"
            else -> "Send lightweight message"
          },
          enabled = true,
          containerColor = when {
            actionOpensSettings && designStyle -> FloveraDesignElevated
            actionOpensSettings && designFrontend -> FloveraDesignDarkSettingMask
            actionOpensSettings -> MaterialTheme.colorScheme.errorContainer
            hasInput && designStyle -> MaterialTheme.colorScheme.primary
            hasInput -> MaterialTheme.colorScheme.primaryContainer
            designStyle -> FloveraDesignElevated
            else -> MaterialTheme.colorScheme.surfaceVariant
          },
          contentColor = when {
            actionOpensSettings && designStyle -> FloveraDesignMuted
            actionOpensSettings && designFrontend -> FloveraDesignDarkSettingOnMask
            actionOpensSettings -> MaterialTheme.colorScheme.onErrorContainer
            hasInput && designStyle -> MaterialTheme.colorScheme.onPrimary
            hasInput -> MaterialTheme.colorScheme.onPrimaryContainer
            designStyle -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
          onClick = {
            focusManager.clearFocus()
            when {
              actionOpensSettings -> onOpenSettings()
              hasInput -> onSubmit()
              else -> onOpenConversation()
            }
          },
        ) {
            when {
              actionOpensSettings -> Icon(if (designFrontend) Icons.Filled.Tune else Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
            !hasInput && designFrontend -> Image(
              painter = painterResource(id = floveraMarkDrawableForTheme()),
              contentDescription = null,
              modifier = Modifier.size(30.dp),
            )
            !hasInput -> Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(20.dp))
            else -> Icon(
              if (designStyle) Icons.Filled.ArrowUpward else Icons.AutoMirrored.Filled.Send,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CompactMainInput(
  value: String,
  onValueChange: (String) -> Unit,
  enabled: Boolean,
  placeholder: String,
  modifier: Modifier = Modifier,
) {
  val designStyle = floveraDesignStyleEnabled()
  val textColor = MaterialTheme.colorScheme.onSurface
  val textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor)
  Surface(
    modifier = modifier.height(44.dp),
    shape = RoundedCornerShape(15.dp),
    color = when {
      enabled && designStyle -> FloveraDesignBackground
      enabled -> MaterialTheme.colorScheme.background
      designStyle -> FloveraDesignElevated
      else -> MaterialTheme.colorScheme.surfaceVariant
    },
    contentColor = if (designStyle) FloveraDesignText else textColor,
    border = BorderStroke(1.dp, if (designStyle) FloveraDesignLine else MaterialTheme.colorScheme.outlineVariant),
  ) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = true,
      textStyle = textStyle,
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 13.dp),
      decorationBox = { innerTextField ->
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.CenterStart,
        ) {
          if (value.isBlank()) {
            Text(
              text = placeholder,
              color = if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          innerTextField()
        }
      },
    )
  }
}

@Composable
private fun BottomDisplayStatusLine(
  path: String,
  mimeType: String,
  isRunning: Boolean,
  status: String,
  language: String,
  contentColor: Color,
  progressColor: Color,
  onClick: () -> Unit,
) {
  val displayText = if (path.isBlank()) {
    t(language, "No preview · choose display", "\u6682\u65e0\u9884\u89c8 · \u9009\u62e9\u5c55\u793a")
  } else {
    "${path.substringAfterLast('/')} · ${mimeType.ifBlank { t(language, "display", "\u5c55\u793a") }}"
  }
  val runningText = status.ifBlank { t(language, "Running", "\u8fd0\u884c\u4e2d") }
  val text = if (isRunning) {
    "${t(language, "Running", "\u8fd0\u884c\u4e2d")} · $runningText · $displayText"
  } else {
    displayText
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(16.dp)
      .testTag("bottom-display-state")
      .semantics {
        contentDescription = "Current display target"
        onClick(label = "Open preview selection") {
          onClick()
          true
        }
      }
      .clickable(onClick = onClick),
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isRunning) {
      CircularProgressIndicator(
        modifier = Modifier.size(10.dp),
        strokeWidth = 1.5.dp,
        color = progressColor,
      )
    }
    Text(
      text = text,
      color = contentColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun CompactBarAction(
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  content: @Composable () -> Unit,
) {
  val designStyle = floveraDesignStyleEnabled()
  Surface(
    modifier = modifier
      .size(44.dp)
      .semantics { this.contentDescription = contentDescription }
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(if (designStyle) 15.dp else 16.dp),
    color = containerColor,
    contentColor = contentColor,
    tonalElevation = if (enabled && !designStyle) 1.dp else 0.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      content()
    }
  }
}

@Composable
private fun ConversationDisplayBlockContent(
  block: ConversationDisplayBlock,
  workspaceMessagePaths: List<String>,
  language: String,
  onOpenPath: (String) -> Unit,
  onRevert: (() -> Unit)?,
  maxContentLines: Int? = null,
) {
  when (block) {
    is ConversationCompressionDisplayBlock -> CompressionDivider(block.message, language)
    is ConversationMessageDisplayBlock -> {
      MessageBubble(
        message = block.message,
        pathLinks = remember(block.message.content, workspaceMessagePaths) {
          conversationPathLinks(block.message.content, workspaceMessagePaths)
        },
        streaming = block.streaming,
        onOpenPath = onOpenPath,
        onRevert = onRevert,
        maxContentLines = maxContentLines,
      )
    }
    is ConversationTimelineDisplayBlock -> {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        ConversationRunEventRow(event = block.event, language = language)
      }
    }
  }
}

@Composable
private fun ConversationDisplayBlocks(
  blocks: List<ConversationDisplayBlock>,
  workspaceMessagePaths: List<String>,
  language: String,
  onOpenPath: (String) -> Unit,
  onRevert: (Int) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    blocks.forEach { block ->
      key(block.id) {
        ConversationDisplayBlockContent(
          block = block,
          workspaceMessagePaths = workspaceMessagePaths,
          language = language,
          onOpenPath = onOpenPath,
          onRevert = if (block is ConversationMessageDisplayBlock && block.sourceMessageIndex != null) {
            { onRevert(block.sourceMessageIndex) }
          } else {
            null
          },
          maxContentLines = null,
        )
      }
    }
  }
}

private fun conversationDisplayBlocksForMessage(
  message: SessionMessage,
  messageIndex: Int?,
  streaming: Boolean,
  includeCompression: Boolean,
  allowRevert: Boolean,
  includeStreamingAssistantText: Boolean,
  animateStreamingText: Boolean,
): List<ConversationDisplayBlock> {
  if (message.role == SESSION_ROLE_COMPRESSION) {
    return if (includeCompression) {
      listOf(ConversationCompressionDisplayBlock(id = "compression:${message.timestampMillis}", message = message))
    } else {
      emptyList()
    }
  }

  if (message.transcriptEvents.isNotEmpty()) {
    return compactConversationTranscriptEvents(message.transcriptEvents).mapIndexedNotNull { index, event ->
      val eventId = "transcript:${event.type}:${event.timestampMillis}:$index"
      if (event.isConversationTextEvent()) {
        if (streaming && !includeStreamingAssistantText && event.conversationRole() == "assistant") {
          return@mapIndexedNotNull null
        }
        ConversationMessageDisplayBlock(
          id = eventId,
          message = message.copy(
            role = event.conversationRole(),
            content = event.content,
            timestampMillis = event.timestampMillis,
            toolEvents = emptyList(),
            runEvents = emptyList(),
            transcriptEvents = emptyList(),
          ),
          streaming = animateStreamingText && streaming && event.type == "assistant_text",
          sourceMessageIndex = null,
        )
      } else {
        ConversationTimelineDisplayBlock(
          id = eventId,
          event = event.toTimelineEvent(),
        )
      }
    }
  }

  val blocks = compactConversationRunEvents(message).mapIndexed { index, event ->
    ConversationTimelineDisplayBlock(
      id = "run:${event.type}:${event.timestampMillis}:$index",
      event = event,
    )
  }.toMutableList<ConversationDisplayBlock>()
  if (
    shouldShowConversationMessageBubble(message) &&
    !(streaming && !includeStreamingAssistantText && message.role == "assistant")
  ) {
    blocks += ConversationMessageDisplayBlock(
      id = "message:${message.role}:${message.timestampMillis}",
      message = message,
      streaming = animateStreamingText && streaming,
      sourceMessageIndex = if (allowRevert && message.role == "user") messageIndex else null,
    )
  }
  return blocks
}

private fun ConversationTranscriptEvent.isConversationTextEvent(): Boolean {
  return type == "assistant_text" ||
    type == "error_text" ||
    type == "user_guidance" ||
    type == "user_text"
}

private fun ConversationTranscriptEvent.conversationRole(): String {
  return role.ifBlank {
    when (type) {
      "error_text" -> "error"
      "user_guidance",
      "user_text" -> "user"
      else -> "assistant"
    }
  }
}

@Composable
private fun navigationBarsBottomPaddingWhenImeHidden(): androidx.compose.ui.unit.Dp {
  val density = LocalDensity.current
  val imeBottom = WindowInsets.ime.getBottom(density)
  if (imeBottom > 0) return 0.dp
  return with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
}

@Composable
private fun EmptyWorkspacePrompt(
  state: AgentScreenState,
  controller: AgentController,
  startupError: String,
  floveraEntryOpen: Boolean,
  onToggleFloveraEntry: () -> Unit,
  onOpenConversation: () -> Unit,
  onOpenPreview: () -> Unit,
  onSubmit: (String) -> Unit,
  hasUsableApi: Boolean,
  onOpenSettings: () -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val designStyle = floveraDesignStyleEnabled()
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(20.dp),
    shape = RoundedCornerShape(if (designStyle) 8.dp else 18.dp),
    color = if (designStyle) FloveraDesignSurface.copy(alpha = 0.94f) else FloveraEmptyPanel,
    contentColor = if (designStyle) FloveraDesignText else MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, if (designStyle) FloveraDesignLine else FloveraEmptyPanelBorder),
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (startupError.isNotBlank()) {
        Text(
          text = startupError,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Text(
        text = EmptyWebPrompt,
        color = if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      if (floveraEntryOpen) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FloveraBubbleAction(
            label = "Agent",
            icon = { Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(18.dp)) },
            contentDescription = "Open agent conversation",
            onClick = onOpenConversation,
          )
          FloveraBubbleAction(
            label = "\u9884\u89c8",
            icon = { Icon(Icons.Filled.Preview, contentDescription = null, modifier = Modifier.size(18.dp)) },
            contentDescription = "Open preview picker",
            onClick = onOpenPreview,
          )
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        FloveraIconAnchor(
          contentDescription = if (floveraEntryOpen) "Close Flovera entry drawer" else "Open Flovera entry drawer",
          onClick = onToggleFloveraEntry,
        )
        Row(
          modifier = Modifier.weight(1f),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          OutlinedTextField(
            value = state.input,
            onValueChange = controller::updateInput,
            placeholder = { Text("\u63cf\u8ff0\u60f3\u8981\u7684\u9879\u76ee") },
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              unfocusedBorderColor = if (designStyle) FloveraDesignLine else FloveraEmptyPanelBorder,
              focusedContainerColor = if (designStyle) FloveraDesignBackground else MaterialTheme.colorScheme.surface,
              unfocusedContainerColor = if (designStyle) FloveraDesignBackground else MaterialTheme.colorScheme.surface,
              cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.weight(1f),
          )
          Surface(
            modifier = Modifier
              .size(52.dp)
              .semantics { contentDescription = "Send empty workspace prompt" }
              .clickable(
                enabled = state.input.isNotBlank() && hasUsableApi && !state.isRunning,
                onClick = {
                  focusManager.clearFocus()
                  onSubmit(state.input)
                },
            ),
            shape = RoundedCornerShape(if (designStyle) 15.dp else 14.dp),
            color = when {
              state.input.isBlank() || !hasUsableApi || state.isRunning -> if (designStyle) FloveraDesignElevated else MaterialTheme.colorScheme.surfaceVariant
              designStyle -> MaterialTheme.colorScheme.primary
              else -> MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = when {
              state.input.isBlank() || !hasUsableApi || state.isRunning -> if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant
              designStyle -> MaterialTheme.colorScheme.onPrimary
              else -> MaterialTheme.colorScheme.onPrimaryContainer
            },
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                if (designStyle) Icons.Filled.ArrowUpward else Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WorkspacePreview(
  state: AgentScreenState,
  controller: AgentController,
  chromeColorSamplingEnabled: Boolean,
  onChromeColorSampled: (Color?) -> Unit,
) {
  val previewPath = state.selectedPreviewPath
  val previewContent = state.selectedPreviewContent
  val mimeType = state.selectedPreviewMimeType
  val previewUri = state.selectedPreviewUri
  val htmlUrl = state.selectedHtmlUrl
  val htmlLoading = state.selectedHtmlLoading
  val htmlError = state.selectedHtmlError
  val isImagePreview = previewPath.isNotBlank() && mimeType.startsWith("image/")
  val isPdfPreview = previewPath.isNotBlank() && isPdfPreview(previewPath, mimeType)
  val isOfficePreview = previewPath.isNotBlank() && isOfficeDocumentPreview(previewPath)
  val isTextPreview = previewPath.isNotBlank() &&
    !previewPath.endsWith(".html", ignoreCase = true) &&
    !previewPath.endsWith(".htm", ignoreCase = true) &&
    !isImagePreview &&
    !isPdfPreview &&
    !isOfficePreview

  LaunchedEffect(chromeColorSamplingEnabled, isTextPreview, isImagePreview, isPdfPreview, isOfficePreview, htmlUrl) {
    if (!chromeColorSamplingEnabled || isTextPreview || isImagePreview || isPdfPreview || isOfficePreview || htmlUrl.isNullOrBlank()) {
      onChromeColorSampled(null)
    }
  }

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

  if (isOfficePreview) {
    WorkspaceOfficePreview(
      path = previewPath,
      mimeType = mimeType,
      uri = previewUri,
    )
    return
  }

  WorkspaceWebView(
    url = htmlUrl,
    loading = htmlLoading,
    startupError = htmlError,
    workspaceRootUrl = state.workspaceRootUrl,
    controller = controller,
    chromeColorSamplingEnabled = chromeColorSamplingEnabled,
    onChromeColorSampled = onChromeColorSampled,
  )
}

@Composable
private fun WorkspaceImagePreview(path: String, mimeType: String, uri: String) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().systemBarsPadding().padding(18.dp),
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
  var pageIndex by remember(uri) { mutableStateOf(0) }
  var renderedPage by remember(uri) { mutableStateOf<PdfRenderedPage?>(null) }
  var pdfError by remember(uri) { mutableStateOf<String?>(null) }
  var loading by remember(uri) { mutableStateOf(uri.isNotBlank()) }

  DisposableEffect(renderedPage?.bitmap) {
    val bitmap = renderedPage?.bitmap
    onDispose {
      if (bitmap?.isRecycled == false) bitmap.recycle()
    }
  }

  LaunchedEffect(uri, pageIndex) {
    if (uri.isBlank()) {
      renderedPage = null
      loading = false
      pdfError = "PDF preview unavailable"
      return@LaunchedEffect
    }
    loading = true
    val result = withContext(Dispatchers.IO) { renderPdfPage(context, uri, pageIndex) }
    renderedPage = result
    pdfError = if (result == null) "PDF preview unavailable" else null
    if (result != null && pageIndex >= result.pageCount) {
      pageIndex = (result.pageCount - 1).coerceAtLeast(0)
    }
    loading = false
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().systemBarsPadding().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(path, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
      DocumentPageNavigator(
        label = renderedPage?.let { "Page ${pageIndex + 1} of ${it.pageCount}" }
          ?: mimeType.ifBlank { "application/pdf" },
        pageIndex = pageIndex,
        pageCount = renderedPage?.pageCount ?: 0,
        onPrevious = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
        onNext = {
          val pageCount = renderedPage?.pageCount ?: 0
          if (pageCount > 0) pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1)
        },
      )
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
            renderedPage?.bitmap?.let { bitmap ->
              Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF preview for $path, page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
              )
            }
            if (loading) {
              CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
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
      modifier = Modifier.fillMaxSize().systemBarsPadding().padding(18.dp).verticalScroll(rememberScrollState()),
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
private fun WorkspaceWebView(
  url: String?,
  loading: Boolean,
  startupError: String,
  workspaceRootUrl: String,
  controller: AgentController,
  chromeColorSamplingEnabled: Boolean,
  onChromeColorSampled: (Color?) -> Unit,
) {
  var webError by remember(url) { mutableStateOf<String?>(null) }

  if (url.isNullOrBlank()) {
    LaunchedEffect(Unit) {
      onChromeColorSampled(null)
    }
    Box(
      modifier = Modifier.fillMaxSize(),
    ) {
      Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
      ) {}
      if (loading) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator()
            Text(
              text = "Starting workspace backend...",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyLarge,
            )
          }
        }
      } else {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = startupError.ifBlank { "" },
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
    return
  }

  AndroidView(
    modifier = Modifier.fillMaxSize().systemBarsPadding().semantics { contentDescription = "Workspace WebView" },
    factory = { context ->
      WebView(context).apply {
        webViewClient = FloveraWorkspaceWebViewClient(
          workspaceRootUrl = workspaceRootUrl,
          onError = { webError = it },
          chromeColorSamplingEnabled = chromeColorSamplingEnabled,
          onChromeColorSampled = onChromeColorSampled,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        if (chromeColorSamplingEnabled) {
          setOnScrollChangeListener { view, _, _, _, _ ->
            (view as? WebView)?.scheduleWorkspaceChromeColorSample(onChromeColorSampled)
          }
        }
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
        if (chromeColorSamplingEnabled) {
          scheduleWorkspaceChromeColorSample(onChromeColorSampled)
        }
      }
    },
    update = { webView ->
      if (webView.url != url) {
        webError = null
        onChromeColorSampled(null)
        webView.loadUrl(url)
        if (chromeColorSamplingEnabled) {
          webView.scheduleWorkspaceChromeColorSample(onChromeColorSampled)
        }
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

private fun isOfficeDocumentPreview(path: String): Boolean {
  return path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in setOf("docx", "pptx", "xlsx")
}

private data class PdfRenderedPage(
  val bitmap: Bitmap,
  val pageCount: Int,
)

private fun renderPdfPage(context: Context, uri: String, pageIndex: Int): PdfRenderedPage? {
  return runCatching {
    val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
    descriptor.use { parcel ->
      val renderer = PdfRenderer(parcel)
      try {
        if (renderer.pageCount <= 0) return null
        val safePageIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
        val page = renderer.openPage(safePageIndex)
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
          PdfRenderedPage(bitmap = bitmap, pageCount = renderer.pageCount)
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

private fun currentDisplayTargetPath(state: AgentScreenState): String {
  return state.selectedPreviewPath.ifBlank { state.selectedHtmlPath }
}

private fun currentDisplayMimeType(state: AgentScreenState): String {
  return when {
    state.selectedPreviewPath.isNotBlank() -> state.selectedPreviewMimeType
    state.selectedHtmlPath.isNotBlank() -> "text/html"
    else -> ""
  }
}

@Composable
private fun WorkspaceOfficePreview(path: String, mimeType: String, uri: String) {
  val context = LocalContext.current
  var preview by remember(uri) { mutableStateOf<WorkspaceDocumentPreview?>(null) }
  var error by remember(uri) { mutableStateOf<String?>(null) }
  var loading by remember(uri) { mutableStateOf(uri.isNotBlank()) }
  var pageIndex by remember(uri) { mutableStateOf(0) }

  LaunchedEffect(uri, path) {
    if (uri.isBlank()) {
      preview = null
      error = "Document preview unavailable"
      loading = false
      return@LaunchedEffect
    }
    loading = true
    val loaded = withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
          WorkspaceDocumentPreviewParser.parse(path, input)
        }
      }.getOrNull()
    }
    preview = loaded
    error = if (loaded == null) "Document preview unavailable" else null
    pageIndex = 0
    loading = false
  }

  val pages = preview?.pages.orEmpty()
  val page = pages.getOrNull(pageIndex)
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().systemBarsPadding().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(path, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
      DocumentPageNavigator(
        label = page?.let { "${it.title} of ${pages.size}" } ?: mimeType,
        pageIndex = pageIndex,
        pageCount = pages.size,
        onPrevious = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
        onNext = {
          if (pages.isNotEmpty()) pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex)
        },
      )
      Surface(
        modifier = Modifier.fillMaxWidth().weight(1f),
        shape = FloveraSmallShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      ) {
        when {
          loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
          }
          error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          page != null -> SelectionContainer {
            Text(
              text = page.content,
              modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DocumentPageNavigator(
  label: String,
  pageIndex: Int,
  pageCount: Int,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().height(40.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = onPrevious,
      enabled = pageIndex > 0,
      modifier = Modifier.semantics { contentDescription = "Previous page" },
    ) {
      Icon(Icons.Filled.ChevronLeft, contentDescription = null)
    }
    Text(
      text = label,
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodySmall,
    )
    IconButton(
      onClick = onNext,
      enabled = pageCount > 0 && pageIndex < pageCount - 1,
      modifier = Modifier.semantics { contentDescription = "Next page" },
    ) {
      Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
  }
}

private fun hasUsableProviderApi(settings: AppSettings): Boolean {
  val provider = ModelProviderCatalog.findProvider(settings.provider) ?: return false
  return settings.apiKeyFor(provider.id).isNotBlank()
}

private class FloveraWorkspaceWebViewClient(
  private val workspaceRootUrl: String,
  private val onError: (String) -> Unit,
  private val chromeColorSamplingEnabled: Boolean,
  private val onChromeColorSampled: (Color?) -> Unit,
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
    if (chromeColorSamplingEnabled) {
      view.scheduleWorkspaceChromeColorSample(onChromeColorSampled)
    }
    view.postDelayed(
      {
        view.evaluateJavascript(WorkspaceWebViewHardening.visibleContentCheckJs) { result ->
          if (!WorkspaceWebViewHardening.isVisibleResult(result)) {
            onError(WorkspaceWebViewHardening.visibilityFailureMessage(result))
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
private fun ConversationHeaderContext(
  displayTargetPath: String,
  displayMimeType: String,
  latestContextRecord: ContextUsageRecord?,
  language: String,
  onShowDisplay: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var contextDetailsOpen by remember(latestContextRecord?.id) { mutableStateOf(false) }
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (displayTargetPath.isNotBlank()) {
      DisplayTargetPill(
        path = displayTargetPath,
        mimeType = displayMimeType,
        onClick = onShowDisplay,
        modifier = Modifier.weight(1f),
      )
    } else {
      Spacer(modifier = Modifier.weight(1f))
    }
    if (latestContextRecord != null) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clickable { contextDetailsOpen = true }
          .semantics { contentDescription = "Context usage details" },
        contentAlignment = Alignment.Center,
      ) {
        ContextUsageRing(latestContextRecord)
      }
    }
  }
  if (contextDetailsOpen && latestContextRecord != null) {
    ContextUsageDetailsDialog(
      record = latestContextRecord,
      language = language,
      onDismiss = { contextDetailsOpen = false },
    )
  }
}

@Composable
private fun ConversationDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onOpenPanel: (AgentPanel) -> Unit,
  onShowDisplay: () -> Unit,
  onDismiss: () -> Unit,
) {
  val listState = rememberLazyListState()
  val messages = state.session?.messages.orEmpty()
  val latestContextRecord = state.session?.contextRecords?.lastOrNull()
  val displayTargetPath = currentDisplayTargetPath(state)
  val displayMimeType = currentDisplayMimeType(state)
  val bottomAnchorIndex = messages.size + if (state.assistantDraft == null) 0 else 1
  val assistantDraftScrollKey = state.assistantDraft?.let { draft ->
    val lastEventTime = draft.transcriptEvents.lastOrNull()?.timestampMillis
      ?: draft.runEvents.lastOrNull()?.timestampMillis
      ?: 0L
    val contentBucket = draft.content.length / 120
    "$contentBucket:${draft.runEvents.size}:${draft.toolEvents.size}:${draft.transcriptEvents.size}:$lastEventTime"
  }.orEmpty()
  val workspaceMessagePaths = remember(state.workspaceTree) { state.workspaceTree.workspaceMessageLinkPaths() }
  val coroutineScope = rememberCoroutineScope()
  var pendingRevertIndex by remember { mutableStateOf<Int?>(null) }
  var sessionPickerOpen by remember { mutableStateOf(false) }
  var moreMenuOpen by remember { mutableStateOf(false) }
  var conversationScrollMode by remember(state.session?.id) {
    mutableStateOf(ConversationScrollMode.BottomLocked)
  }
  val userScrollConnection = remember(state.session?.id) {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
          conversationScrollMode = ConversationScrollMode.Free
        }
        return Offset.Zero
      }
    }
  }
  val bottomInsetPadding = navigationBarsBottomPaddingWhenImeHidden()
  val designStyle = floveraDesignStyleEnabled()

  LaunchedEffect(state.session?.id) {
    conversationScrollMode = ConversationScrollMode.BottomLocked
    withFrameNanos { }
    listState.scrollToItem(bottomAnchorIndex)
  }

  LaunchedEffect(bottomAnchorIndex, assistantDraftScrollKey, conversationScrollMode) {
    if (conversationScrollMode == ConversationScrollMode.BottomLocked) {
      withFrameNanos { }
      listState.scrollToItem(bottomAnchorIndex)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      shape = RoundedCornerShape(0.dp),
      color = if (designStyle) FloveraDesignBackground else MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .imePadding()
          .padding(
            start = 10.dp,
            top = 10.dp,
            end = 10.dp,
            bottom = 10.dp + bottomInsetPadding,
          ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ConversationHeaderContext(
            displayTargetPath = displayTargetPath,
            displayMimeType = displayMimeType,
            latestContextRecord = latestContextRecord,
            language = language,
            onShowDisplay = onShowDisplay,
            modifier = Modifier.weight(1f),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = controller::newSession,
              enabled = !state.isRunning,
              modifier = Modifier.semantics { contentDescription = "New conversation" },
            ) {
              Icon(Icons.Filled.Add, contentDescription = null)
            }
            Box {
              IconButton(
              onClick = { moreMenuOpen = true },
              modifier = Modifier.semantics { contentDescription = "More" },
            ) {
                Icon(if (designStyle) Icons.Filled.Tune else Icons.Filled.Menu, contentDescription = null)
              }
              DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                DropdownMenuItem(
                  text = { Text(t(language, "New", "\u65b0\u5efa")) },
                  onClick = {
                    moreMenuOpen = false
                    controller.newSession()
                  },
                  enabled = !state.isRunning,
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Sessions", "Sessions")) },
                  onClick = {
                    moreMenuOpen = false
                    sessionPickerOpen = true
                  },
                  enabled = !state.isRunning,
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
                  text = { Text(t(language, "Skills", "\u6280\u80fd")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Skills)
                  },
                  enabled = !state.isRunning,
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Secrets", "\u5bc6\u94a5")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Secrets)
                  },
                  enabled = !state.isRunning,
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Permissions", "\u6743\u9650")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Permissions)
                  },
                  enabled = !state.isRunning,
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Rule", "\u89c4\u5219")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.AgentFile)
                  },
                  enabled = !state.isRunning,
                )
                DropdownMenuItem(
                  text = { Text(t(language, "Settings", "\u8bbe\u7f6e")) },
                  onClick = {
                    moreMenuOpen = false
                    onOpenPanel(AgentPanel.Settings)
                  },
                  enabled = !state.isRunning,
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

        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
          LazyColumn(
            modifier = Modifier
              .fillMaxSize()
              .nestedScroll(userScrollConnection)
              .testTag("conversation-list"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (messages.isNotEmpty()) {
              itemsIndexed(
                items = messages,
                key = { index, message -> "${message.timestampMillis}-${message.role}-$index" },
              ) { index, message ->
                ConversationDisplayBlocks(
                  blocks = conversationDisplayBlocksForMessage(
                    message = message,
                    messageIndex = index,
                    streaming = false,
                    includeCompression = true,
                    allowRevert = !state.isRunning,
                    includeStreamingAssistantText = true,
                    animateStreamingText = true,
                  ),
                  workspaceMessagePaths = workspaceMessagePaths,
                  language = language,
                  onOpenPath = {
                    controller.selectWorkspacePreview(it)
                    onDismiss()
                  },
                  onRevert = { pendingRevertIndex = it },
                )
              }
              state.assistantDraft?.let { draft ->
                item(key = "assistant-draft") {
                  ConversationDisplayBlocks(
                    blocks = conversationDisplayBlocksForMessage(
                      message = draft,
                      messageIndex = null,
                      streaming = true,
                      includeCompression = true,
                      allowRevert = false,
                      includeStreamingAssistantText = true,
                      animateStreamingText = true,
                    ),
                    workspaceMessagePaths = workspaceMessagePaths,
                    language = language,
                    onOpenPath = {
                      controller.selectWorkspacePreview(it)
                      onDismiss()
                    },
                    onRevert = {},
                  )
                }
              }
            }
            item(key = "conversation-bottom-anchor") {
              Spacer(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(1.dp)
                  .testTag("conversation-bottom-anchor"),
              )
            }
          }

          if (conversationScrollMode == ConversationScrollMode.Free) {
            FloatingActionButton(
              onClick = {
                coroutineScope.launch {
                  listState.animateScrollToItem(bottomAnchorIndex)
                  conversationScrollMode = ConversationScrollMode.BottomLocked
                }
              },
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .size(48.dp)
                .semantics { contentDescription = "Jump to latest and lock conversation" },
              shape = FloveraFabShape,
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
              Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(20.dp))
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

        if (state.isRunning) {
          ConversationRunStateBar(
            status = state.status,
            queuedCount = state.queuedInputs.size,
            language = language,
          )
        }

        ConversationComposer(
          state = state,
          controller = controller,
          language = language,
        )
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
private fun ConversationComposer(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val designStyle = floveraDesignStyleEnabled()
  var attachmentMenuOpen by remember { mutableStateOf(false) }
  val mediaPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.PickMultipleVisualMedia(10),
  ) { uris ->
    if (uris.isNotEmpty()) controller.importConversationAttachments(uris)
  }
  val speechLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      result.data
        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull()
        ?.let(controller::appendInputText)
    }
  }
  val hasInput = state.input.isNotBlank()
  val actionStopsRun = state.isRunning && !hasInput

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    Box {
      ComposerIconButton(
        contentDescription = "Add attachment",
        enabled = !state.isRunning,
        onClick = { attachmentMenuOpen = true },
      ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
      }
      DropdownMenu(expanded = attachmentMenuOpen, onDismissRequest = { attachmentMenuOpen = false }) {
        DropdownMenuItem(
          text = { Text(t(language, "Photo library", "\u76f8\u518c")) },
          leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
          onClick = {
            attachmentMenuOpen = false
            runCatching {
              mediaPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
              )
            }.onFailure {
              Toast.makeText(context, t(language, "Photo picker unavailable", "\u65e0\u6cd5\u6253\u5f00\u76f8\u518c\u9009\u62e9"), Toast.LENGTH_SHORT).show()
            }
          },
        )
      }
    }

    OutlinedTextField(
      value = state.input,
      onValueChange = controller::updateInput,
      placeholder = { Text(t(language, "Message Flovera", "\u548c Flovera \u5bf9\u8bdd")) },
      minLines = 1,
      maxLines = 4,
      shape = RoundedCornerShape(if (designStyle) 12.dp else 14.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = if (designStyle) FloveraDesignLine else MaterialTheme.colorScheme.outline,
        focusedContainerColor = if (designStyle) FloveraDesignSurface else MaterialTheme.colorScheme.background,
        unfocusedContainerColor = if (designStyle) FloveraDesignSurface else MaterialTheme.colorScheme.background,
        cursorColor = MaterialTheme.colorScheme.primary,
      ),
      modifier = Modifier
        .weight(1f)
        .semantics { contentDescription = "Conversation message input" },
    )

    ComposerIconButton(
      contentDescription = "Voice input",
      enabled = !state.isRunning,
      onClick = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "zh") Locale.CHINESE.toLanguageTag() else Locale.ENGLISH.toLanguageTag())
          .putExtra(RecognizerIntent.EXTRA_PROMPT, t(language, "Speak your message", "\u8bf4\u51fa\u8981\u8f93\u5165\u7684\u5185\u5bb9"))
        try {
          speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
          Toast.makeText(context, t(language, "Voice input unavailable", "\u8bed\u97f3\u8f93\u5165\u4e0d\u53ef\u7528"), Toast.LENGTH_SHORT).show()
        }
      },
    ) {
      Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
    }

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
      shape = RoundedCornerShape(if (designStyle) 15.dp else 14.dp),
      color = when {
        actionStopsRun -> MaterialTheme.colorScheme.errorContainer
        designStyle -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
      },
      contentColor = when {
        actionStopsRun -> MaterialTheme.colorScheme.onErrorContainer
        designStyle -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
      },
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (actionStopsRun) {
          Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
        } else {
          Icon(if (designStyle) Icons.Filled.ArrowUpward else Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
        }
      }
    }
  }
}

@Composable
private fun ComposerIconButton(
  contentDescription: String,
  enabled: Boolean,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  val designStyle = floveraDesignStyleEnabled()
  Surface(
    modifier = Modifier
      .size(44.dp)
      .semantics { this.contentDescription = contentDescription }
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(if (designStyle) 12.dp else 14.dp),
    color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
    contentColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
    border = BorderStroke(1.dp, if (designStyle) FloveraDesignLine else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Box(contentAlignment = Alignment.Center) {
      content()
    }
  }
}

@Composable
private fun ConversationRunStateBar(status: String, queuedCount: Int, language: String) {
  val designStyle = floveraDesignStyleEnabled()
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(if (designStyle) 8.dp else 12.dp),
    color = if (designStyle) FloveraDesignElevated.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    contentColor = if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant,
    border = BorderStroke(1.dp, if (designStyle) FloveraDesignLine else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = status.ifBlank { t(language, "Running", "\u8fd0\u884c\u4e2d") },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      if (queuedCount > 0) {
        Text(
          text = t(language, "$queuedCount queued", "\u5df2\u6392\u961f $queuedCount"),
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}

@Composable
private fun CompressionDivider(message: SessionMessage, language: String) {
  var expanded by remember(message.timestampMillis, message.content) { mutableStateOf(false) }
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    ConversationRunEventRow(
      event = AgentRunTimelineEvent(
        type = "compression",
        title = "Context compressed",
        detail = formatMessageTime(message.timestampMillis),
        timestampMillis = message.timestampMillis,
        status = "completed",
      ),
      language = language,
    )
    TextButton(
      onClick = { expanded = !expanded },
      modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
      Text(
        text = if (expanded) "Hide handoff summary" else "Show handoff summary",
        style = MaterialTheme.typography.labelSmall,
      )
    }
    if (expanded) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FloveraSmallShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
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
  streaming: Boolean = false,
  onOpenPath: (String) -> Unit = {},
  onRevert: (() -> Unit)?,
  maxContentLines: Int? = null,
) {
  val designStyle = floveraDesignStyleEnabled()
  val context = LocalContext.current
  val isUser = message.role == "user"
  val isError = message.role == "error"
  val canCopy = message.content.isNotBlank() && !streaming && (isUser || message.role == "assistant")
  val horizontal = if (isUser) Arrangement.End else Arrangement.Start
  val bubbleColor = when {
    isUser -> MaterialTheme.colorScheme.primaryContainer
    isError -> MaterialTheme.colorScheme.errorContainer
    designStyle -> FloveraDesignAssistantBubble
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val textColor = when {
    isUser -> MaterialTheme.colorScheme.onPrimaryContainer
    designStyle && !isError -> FloveraDesignText
    isError -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val bubbleBorderColor = when {
    isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
    designStyle -> FloveraDesignLine
    isError -> MaterialTheme.colorScheme.error
    else -> FloveraAssistantBubbleBorder
  }
  var selectionEnabled by remember(message.timestampMillis, message.role, message.content) { mutableStateOf(false) }
  val surfaceModifier = Modifier.fillMaxWidth(0.84f)

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = horizontal) {
    Surface(
      modifier = surfaceModifier,
      shape = RoundedCornerShape(
        topStart = if (designStyle) 12.dp else 14.dp,
        topEnd = if (designStyle) 12.dp else 14.dp,
        bottomStart = if (isUser) {
          if (designStyle) 12.dp else 14.dp
        } else {
          if (designStyle) 5.dp else 4.dp
        },
        bottomEnd = if (isUser) {
          if (designStyle) 5.dp else 4.dp
        } else {
          if (designStyle) 12.dp else 14.dp
        },
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
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
              if (canCopy) {
                IconButton(
                  onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                      ?.setPrimaryClip(ClipData.newPlainText(if (isUser) "User message" else "Assistant message", message.content))
                    Toast.makeText(
                      context,
                      if (isUser) "Copied user message" else "Copied assistant message",
                      Toast.LENGTH_SHORT,
                    ).show()
                  },
                  modifier = Modifier.semantics {
                    contentDescription = if (isUser) "Copy You message" else "Copy Assistant message"
                  },
                ) {
                  Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = textColor.copy(alpha = 0.82f), modifier = Modifier.size(18.dp))
                }
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
          }
          MarkdownMessageText(
            content = message.content,
            color = textColor,
            streaming = streaming,
            maxLines = maxContentLines,
          )
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
private fun ConversationRunEventRow(event: AgentRunTimelineEvent, language: String) {
  val designStyle = floveraDesignStyleEnabled()
  val color = if (designStyle) FloveraDesignMuted else MaterialTheme.colorScheme.onSurfaceVariant
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = "\u203A",
      color = color.copy(alpha = 0.54f),
      style = MaterialTheme.typography.bodySmall,
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
      Text(
        text = compactRunEventTitle(event, language),
        color = color.copy(alpha = 0.86f),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      compactRunEventDetail(event)?.let { detail ->
        Text(
          text = detail,
          color = color.copy(alpha = 0.62f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
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

private fun compactConversationTranscriptEvents(
  events: List<ConversationTranscriptEvent>,
): List<ConversationTranscriptEvent> {
  if (events.isEmpty()) return emptyList()
  val visible = mutableListOf<ConversationTranscriptEvent>()
  var omittedToolCalls = 0
  val toolEvents = events.filter { it.type == "tool_call" }
  val toolCutoff = (toolEvents.size - 6).coerceAtLeast(0)
  var seenToolCalls = 0
  events.forEach { event ->
    if (event.type == "tool_call") {
      seenToolCalls += 1
      if (seenToolCalls <= toolCutoff) {
        omittedToolCalls += 1
        return@forEach
      }
    }
    visible += event
  }
  if (omittedToolCalls == 0) return visible
  val insertionIndex = visible.indexOfFirst { it.type == "tool_call" }
    .let { if (it < 0) 0 else it }
  val omitted = ConversationTranscriptEvent(
    type = "tool_omitted",
    title = "Earlier tool calls hidden",
    detail = "$omittedToolCalls earlier completed tool call(s) are stored in the session tool event list.",
    status = "completed",
  )
  visible.add(insertionIndex, omitted)
  return visible
}

private fun ConversationTranscriptEvent.toTimelineEvent(): AgentRunTimelineEvent {
  return AgentRunTimelineEvent(
    type = type,
    title = title,
    detail = detail,
    timestampMillis = timestampMillis,
    status = status,
    compact = compact,
  )
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
      "assistant_text_streaming" -> event.status == "running"
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
    "assistant_text_streaming" -> t(language, "Writing update", "\u6b63\u5728\u8f93\u51fa\u8fdb\u5c55")
    "final_response_streaming" -> t(language, "Writing answer", "\u6b63\u5728\u8f93\u51fa\u56de\u7b54")
    "run_failed" -> t(language, "Run failed", "\u8fd0\u884c\u5931\u8d25")
    "run_interrupted" -> t(language, "Run interrupted", "\u8fd0\u884c\u5df2\u4e2d\u65ad")
    "compression" -> t(language, event.title, event.title)
    "guidance" -> if (event.status == "applied") {
      t(language, "Guidance applied", "\u5df2\u5e94\u7528\u5f15\u5bfc")
    } else {
      t(language, "Guidance queued", "\u5df2\u52a0\u5165\u5f15\u5bfc")
    }
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
    "workspace_command_run" -> "Ran workspace command"
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
private fun MarkdownMessageText(
  content: String,
  color: Color,
  streaming: Boolean = false,
  maxLines: Int? = null,
) {
  val normalized = remember(content) { normalizeConversationMarkdownContent(content) }
  if (maxLines != null) {
    TruncatedPlainMessageText(content = normalized, color = color, maxLines = maxLines)
  } else if (streaming) {
    StreamingPlainMessageText(content = normalized, color = color, maxLines = maxLines)
  } else if (normalized.length >= MARKDOWN_SEGMENTED_RENDER_MIN_CHARS) {
    ProgressiveSegmentedMarkwonMessageText(content = normalized, color = color)
  } else {
    MarkwonMessageText(content = normalized, color = color, maxLines = maxLines)
  }
}

@Composable
private fun ProgressiveSegmentedMarkwonMessageText(content: String, color: Color) {
  var renderMarkdown by remember(content) { mutableStateOf(false) }
  LaunchedEffect(content) {
    withFrameNanos { }
    delay(80)
    renderMarkdown = true
  }
  if (!renderMarkdown) {
    StreamingPlainMessageText(content = content, color = color)
    return
  }
  val segments = remember(content) { markdownRenderSegments(content) }
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    segments.forEachIndexed { index, segment ->
      key("markdown-segment-$index") {
        MarkwonMessageText(content = segment, color = color)
      }
    }
  }
}

@Composable
private fun TruncatedPlainMessageText(content: String, color: Color, maxLines: Int) {
  Text(
    text = content,
    color = color,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    style = MaterialTheme.typography.bodyMedium,
  )
}

@Composable
private fun StreamingPlainMessageText(content: String, color: Color, maxLines: Int? = null) {
  Text(
    text = content,
    color = color,
    maxLines = maxLines ?: Int.MAX_VALUE,
    overflow = if (maxLines == null) TextOverflow.Clip else TextOverflow.Ellipsis,
    style = MaterialTheme.typography.bodyMedium,
  )
}

@Composable
private fun MarkwonMessageText(content: String, color: Color, maxLines: Int? = null) {
  val context = LocalContext.current
  val markwon = remember(context) {
    Markwon.builder(context)
      .usePlugin(TablePlugin.create(context))
      .build()
  }
  val textColor = color.toArgb()
  AndroidView(
    modifier = Modifier.fillMaxWidth(),
    factory = { viewContext ->
      TextView(viewContext).apply {
        setTextColor(textColor)
        textSize = 14f
        includeFontPadding = true
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        if (maxLines != null) {
          setMaxLines(maxLines)
          ellipsize = TextUtils.TruncateAt.END
        }
      }
    },
    update = { textView ->
      textView.setTextColor(textColor)
      textView.textSize = 14f
      if (maxLines == null) {
        textView.setMaxLines(Int.MAX_VALUE)
        textView.ellipsize = null
      } else {
        textView.setMaxLines(maxLines)
        textView.ellipsize = TextUtils.TruncateAt.END
      }
      runCatching {
        markwon.setMarkdown(textView, content)
      }.onFailure {
        textView.text = content
      }
    },
  )
}

private fun markdownRenderSegments(content: String): List<String> {
  val segments = mutableListOf<String>()
  val current = StringBuilder()
  var inCodeFence = false
  fun flush() {
    val segment = current.toString().trimEnd()
    if (segment.isNotBlank()) segments += segment
    current.clear()
  }
  content.lineSequence().forEach { line ->
    if (line.trimStart().startsWith("```")) {
      inCodeFence = !inCodeFence
    }
    current.appendLine(line)
    val shouldSplitAtBlank = !inCodeFence && line.isBlank() && current.length >= MARKDOWN_SEGMENT_TARGET_CHARS
    val shouldSplitAtSize = !inCodeFence && current.length >= MARKDOWN_SEGMENT_MAX_CHARS
    if (shouldSplitAtBlank || shouldSplitAtSize) {
      flush()
    }
  }
  flush()
  return segments.ifEmpty { listOf(content) }
}

@Composable
private fun LegacyMarkdownMessageText(content: String, color: Color) {
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

        is MarkdownBlock.ListItem -> InlineMarkdownText(
          text = "${block.marker} ${block.text}",
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
  val estimateLabel = when {
    isTokenizerContextRecord(record) -> {
      t(language, "Tokenized from the request payload.", "\u57fa\u4e8e\u8bf7\u6c42 payload \u5206\u8bcd\u7edf\u8ba1\u3002")
    }
    isEstimatedContextRecord(record) -> {
      t(language, "Estimated from request characters.", "\u57fa\u4e8e\u8bf7\u6c42\u5b57\u7b26\u6570\u4f30\u7b97\u3002")
    }
    else -> {
      t(language, "Reported by provider.", "\u6765\u81ea provider \u62a5\u544a\u3002")
    }
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
    appendLine("- tokenUsageSource=${record.tokenUsageSource}")
    append("- estimatedRequestChars=$requestChars")
  }
}

private fun formatContextPercent(record: ContextUsageRecord, language: String): String {
  val permille = effectiveContextPermille(record) ?: return t(language, "estimate", "\u4f30\u7b97")
  if (permille in 1..99) return String.format(Locale.US, "%.1f%%", permille / 10.0)
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
    !isTokenizerContextRecord(record)
}

private fun isTokenizerContextRecord(record: ContextUsageRecord): Boolean {
  return record.tokenUsageSource.equals("tokenizer", ignoreCase = true) ||
    record.tokenUsageSource.startsWith("tokenizer_", ignoreCase = true)
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
  data class ListItem(val marker: String, val text: String) : MarkdownBlock
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

  normalizeConversationMarkdownContent(content).lines().forEach { rawLine ->
    val line = rawLine.trimEnd()
    if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
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
      parseMarkdownListItem(line) != null -> {
        flushParagraph()
        val listItem = parseMarkdownListItem(line)
        blocks += MarkdownBlock.ListItem(listItem?.marker.orEmpty(), listItem?.text.orEmpty())
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
private fun DisplayTargetPickerRow(
  path: String,
  mimeType: String,
  selected: Boolean,
  onOpen: () -> Unit,
  onShare: (() -> Unit)? = null,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpen),
    shape = FloveraSmallShape,
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Filled.Preview, contentDescription = null, modifier = Modifier.size(18.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
          mimeType.ifBlank { "display" },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (onShare != null) {
        IconButton(
          onClick = onShare,
          modifier = Modifier.semantics { contentDescription = "Share current display" },
        ) {
          Icon(Icons.Filled.Share, contentDescription = null)
        }
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
  onShowDisplay: () -> Unit,
) {
  val context = LocalContext.current
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
  val currentDisplayPath = currentDisplayTargetPath(state)
  val currentDisplayMimeType = currentDisplayMimeType(state)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Preview Display", "\u9884\u89c8\u5c55\u793a")) },
    text = {
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 120.dp, max = 420.dp)
          .testTag("preview-display-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (currentDisplayPath.isNotBlank()) {
          item {
            Text(t(language, "Current Display", "\u5f53\u524d\u5c55\u793a"), style = MaterialTheme.typography.labelLarge)
          }
          item {
            DisplayTargetPickerRow(
              path = currentDisplayPath,
              mimeType = currentDisplayMimeType,
              selected = true,
              onOpen = onDismiss,
              onShare = { shareWorkspaceFile(context, controller, currentDisplayPath) },
            )
          }
        }
        if (state.workspaceArtifacts.isNotEmpty()) {
          item {
            Text(t(language, "Generated Apps", "\u751f\u6210\u5e94\u7528"), style = MaterialTheme.typography.labelLarge)
          }
          items(state.workspaceArtifacts, key = { it.manifestPath }) { artifact ->
            WorkspaceArtifactPickerRow(
              artifact = artifact,
              serverStatus = artifactServerStatusByManifest[artifact.manifestPath],
              language = language,
              onOpen = { previewPath ->
                controller.selectHtmlFile(previewPath)
                onShowDisplay()
              },
              onStopServer = { controller.stopWorkspaceArtifactServer(artifact.manifestPath) },
            )
          }
        }
        item {
          Text(t(language, "HTML Display Files", "HTML \u5c55\u793a\u6587\u4ef6"), style = MaterialTheme.typography.labelLarge)
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
              onShowDisplay()
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
  const val visibleCheckDelayMs = 500L

  fun isVisibleResult(result: String?): Boolean {
    val raw = result.orEmpty()
    return raw.contains("\\\"visible\\\":true") || raw.contains("\"visible\":true")
  }

  fun visibilityFailureMessage(result: String?): String {
    val raw = result.orEmpty().replace("\\\"", "\"")
    val reason = Regex(""""reason"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val suffix = when (reason) {
      "no-body" -> "No document body was available after load."
      "zero-viewport" -> "The WebView reported a zero viewport."
      "empty-body" -> "The document body has no measurable content."
      "no-visible-candidates" -> "No visible main content, controls, media, or text nodes were inside the viewport."
      else -> "Check viewport height, offscreen roots, blocked resources, or missing local HTTP routes."
    }
    return "WebView content may be invisible. $suffix"
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
      if (viewportHeight <= 0 || viewportWidth <= 0) {
        return JSON.stringify({ visible: false, reason: 'zero-viewport', viewportHeight: viewportHeight, viewportWidth: viewportWidth });
      }
      var bodyHeight = body.scrollHeight || body.offsetHeight || 0;
      var bodyText = (body.innerText || body.textContent || '').trim();
      if (bodyHeight <= 0 && bodyText.length === 0 && body.children.length === 0) {
        return JSON.stringify({ visible: false, reason: 'empty-body', viewportHeight: viewportHeight, viewportWidth: viewportWidth, bodyHeight: bodyHeight });
      }
      var candidates = Array.prototype.slice.call(body.querySelectorAll('main, [role="main"], section, article, form, button, input, textarea, canvas, svg, img, video, h1, h2, p, div'))
        .filter(function (node) {
          var style = window.getComputedStyle(node);
          if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
          var rect = node.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 && rect.top < viewportHeight && rect.left < viewportWidth;
        });
      return JSON.stringify({
        visible: candidates.length > 0,
        reason: candidates.length > 0 ? 'visible-candidates' : 'no-visible-candidates',
        viewportHeight: viewportHeight,
        viewportWidth: viewportWidth,
        bodyHeight: bodyHeight,
        bodyTextLength: bodyText.length,
        visibleCandidates: candidates.length
      });
    })();
  """.trimIndent()

  val chromeColorSampleJs = """
    (function () {
      function channel(value) {
        value = String(value || '').trim();
        if (!value) return null;
        if (value.indexOf('%') === value.length - 1) {
          return Math.max(0, Math.min(255, Math.round(parseFloat(value) * 2.55)));
        }
        return Math.max(0, Math.min(255, Math.round(parseFloat(value))));
      }
      function solidColor(value) {
        value = String(value || '').trim();
        if (!value || value === 'transparent') return null;
        var match = value.match(/rgba?\(([^)]+)\)/i);
        if (!match) return null;
        var normalized = match[1].replace(/\s*\/\s*/g, ',').replace(/\s+/g, ',');
        var parts = normalized.split(',').filter(function (part) { return part !== ''; });
        if (parts.length < 3) return null;
        var alpha = parts.length > 3 ? parseFloat(parts[3]) : 1;
        if (!isFinite(alpha) || alpha < 0.12) return null;
        var red = channel(parts[0]);
        var green = channel(parts[1]);
        var blue = channel(parts[2]);
        if (red === null || green === null || blue === null) return null;
        function hex(part) {
          return part.toString(16).padStart(2, '0');
        }
        return '#' + hex(red) + hex(green) + hex(blue);
      }
      function backgroundNear(x, y) {
        var node = document.elementFromPoint(x, y);
        while (node && node.nodeType === 1) {
          var style = window.getComputedStyle(node);
          var color = solidColor(style.backgroundColor);
          if (color) return color;
          node = node.parentElement;
        }
        var body = document.body ? solidColor(window.getComputedStyle(document.body).backgroundColor) : null;
        if (body) return body;
        var root = document.documentElement ? solidColor(window.getComputedStyle(document.documentElement).backgroundColor) : null;
        return root;
      }
      var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
      var viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
      if (viewportHeight <= 0 || viewportWidth <= 0 || !document.elementFromPoint) {
        return JSON.stringify({ ok: false, reason: 'zero-viewport' });
      }
      var xs = [0.5, 0.25, 0.75];
      var offsets = [8, 32, 72];
      for (var yIndex = 0; yIndex < offsets.length; yIndex += 1) {
        var y = Math.max(1, viewportHeight - offsets[yIndex]);
        for (var xIndex = 0; xIndex < xs.length; xIndex += 1) {
          var x = Math.max(1, Math.min(viewportWidth - 1, Math.round(viewportWidth * xs[xIndex])));
          var color = backgroundNear(x, y);
          if (color) return JSON.stringify({ ok: true, color: color });
        }
      }
      return JSON.stringify({ ok: false, reason: 'no-solid-background' });
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
  onShowDisplay: () -> Unit,
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
              onShowDisplay = onShowDisplay,
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
  onShowDisplay: () -> Unit,
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
                onShowDisplay()
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
  onShowDisplay: () -> Unit,
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
                onShowDisplay()
              },
              onOpenWith = { path -> openWorkspaceFile(context, controller, path) },
              onShare = { path -> shareWorkspaceFile(context, controller, path) },
              onRename = { renameTarget = it },
              onDelete = { deleteTarget = it },
              onCopyPath = { path -> clipboard.setPrimaryClip(ClipData.newPlainText("Workspace path", path)) },
              allowMutations = !state.isRunning,
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
  allowMutations: Boolean,
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
          enabled = allowMutations,
        )
        DropdownMenuItem(
          text = { Text(t(language, "Delete", "\u5220\u9664")) },
          onClick = {
            menuOpen = false
            onDelete(node)
          },
          enabled = allowMutations,
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
    title = { Text(t(language, "Rule", "\u89c4\u5219")) },
    text = {
      OutlinedTextField(
        value = agentRulesDraft,
        onValueChange = { agentRulesDraft = it },
        label = { Text(t(language, "Workspace rules", "Workspace \u89c4\u5219")) },
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

private fun requestBackgroundKeepAlivePermissions(context: Context) {
  val activity = context.findActivity()
  if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
  ) {
    AndroidPermissionCapabilities.requestRuntimePermissions(activity)
  }
  AndroidPermissionCapabilities.openPermission(context, "battery_optimization")
}

private fun openAppNotificationSettings(context: Context) {
  AndroidPermissionCapabilities.openPermission(context, "notifications")
}

@Composable
private fun PermissionsDialog(
  language: String,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var refreshKey by remember { mutableStateOf(0) }
  val statuses = remember(refreshKey, context) { AndroidPermissionCapabilities.status(context) }
  val activity = context.findActivity()
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) refreshKey += 1
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Permissions", "\u6743\u9650")) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          t(
            language,
            "Grant Android permissions here. Flovera requests runtime permissions together, then opens each required Android system permission page in sequence.",
            "\u5728\u8fd9\u91cc\u7edf\u4e00\u6388\u6743 Android \u6743\u9650\u3002Flovera \u4f1a\u5148\u6279\u91cf\u8bf7\u6c42\u8fd0\u884c\u65f6\u6743\u9650\uff0c\u518d\u4f9d\u6b21\u6253\u5f00\u9700\u8981\u7684 Android \u7cfb\u7edf\u6388\u6743\u9875\u3002",
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              (activity as? MainActivity)?.requestAllAndroidPermissions()
                ?: activity?.let(AndroidPermissionCapabilities::requestRuntimePermissions)
              refreshKey += 1
            },
            enabled = activity != null,
          ) {
            Text(t(language, "Grant all", "\u6388\u6743\u5168\u90e8"))
          }
          OutlinedButton(
            onClick = {
              AndroidPermissionCapabilities.openAppDetails(context)
              refreshKey += 1
            },
          ) {
            Text(t(language, "App settings", "\u5e94\u7528\u8bbe\u7f6e"))
          }
        }
        statuses.forEach { status ->
          PermissionStatusItem(
            status = status,
            language = language,
            onOpen = {
              AndroidPermissionCapabilities.openPermission(context, status.capability.id)
              refreshKey += 1
            },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Done", "\u5b8c\u6210"))
      }
    },
  )
}

@Composable
private fun PermissionStatusItem(
  status: com.flovera.app.platform.AndroidPermissionStatus,
  language: String,
  onOpen: () -> Unit,
) {
  val label = if (language == "zh") status.capability.labelZh else status.capability.labelEn
  val statusText = permissionStateLabel(language, status.state)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "${status.capability.id} · $statusText",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (status.capability.type == AndroidPermissionType.Special || status.state == "denied") {
        TextButton(onClick = onOpen) {
          Text(t(language, "Open", "\u6253\u5f00"))
        }
      }
    }
  }
}

private fun permissionStateLabel(language: String, state: String): String {
  return when (state) {
    "granted" -> t(language, "Granted", "\u5df2\u6388\u6743")
    "denied" -> t(language, "Not granted", "\u672a\u6388\u6743")
    "not_applicable" -> t(language, "Not applicable", "\u4e0d\u9002\u7528")
    else -> state
  }
}

@Composable
private fun SkillsDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Skills", "\u6280\u80fd")) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          t(
            language,
            "Enabled skills enter the next request as visible descriptors. Disabled skills stay editable under .flovera/skills.",
            "\u5f00\u542f\u7684\u6280\u80fd\u4f1a\u4f5c\u4e3a\u5165\u53e3\u8bf4\u660e\u8fdb\u5165\u4e0b\u4e00\u6b21\u8bf7\u6c42\u4f53\u3002\u5173\u95ed\u7684\u6280\u80fd\u4ecd\u53ef\u5728 .flovera/skills \u4e0b\u7f16\u8f91\u548c\u67e5\u770b\u3002",
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        if (state.floveraSkills.isEmpty()) {
          Text(
            t(
              language,
              "No registered skills. Add entries in .flovera/skills/manifest.json.",
              "\u6682\u65e0\u5df2\u6ce8\u518c\u7684\u6280\u80fd\u3002\u53ef\u5728 .flovera/skills/manifest.json \u4e2d\u6dfb\u52a0\u5165\u53e3\u3002",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        } else {
          state.floveraSkills.forEach { skill ->
            FloveraSkillSettingsItem(
              skill = skill,
              language = language,
              onEnabledChange = { enabled -> controller.setFloveraSkillEnabled(skill.id, enabled) },
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Done", "\u5b8c\u6210"))
      }
    },
  )
}

@Composable
private fun SecretsDialog(
  state: AgentScreenState,
  controller: AgentController,
  language: String,
  onDismiss: () -> Unit,
) {
  val secrets = remember(state.settings.workspaceSecrets) {
    state.settings.workspaceSecrets.sortedBy { it.normalizedName }
  }
  var editingOriginalName by remember { mutableStateOf("") }
  var nameDraft by remember { mutableStateOf("") }
  var valueDraft by remember { mutableStateOf("") }
  var agentAllowedDraft by remember { mutableStateOf(true) }
  fun clearDraft() {
    editingOriginalName = ""
    nameDraft = ""
    valueDraft = ""
    agentAllowedDraft = true
  }
  fun editSecret(secret: WorkspaceSecret) {
    editingOriginalName = secret.normalizedName
    nameDraft = secret.displayLabel
    valueDraft = secret.value
    agentAllowedDraft = secret.agentAllowed
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(t(language, "Secrets", "\u5bc6\u94a5")) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (secrets.isEmpty()) {
          Text(
            t(language, "No saved secrets.", "\u6682\u65e0\u5df2\u4fdd\u5b58\u7684\u5bc6\u94a5\u3002"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        } else {
          secrets.forEach { secret ->
            WorkspaceSecretItem(
              secret = secret,
              language = language,
              onEdit = { editSecret(secret) },
              onDelete = { controller.deleteWorkspaceSecret(secret.normalizedName) },
              onAllowedChange = { allowed -> controller.setWorkspaceSecretAgentAllowed(secret.normalizedName, allowed) },
            )
          }
        }
        Text(
          if (editingOriginalName.isBlank()) t(language, "Add secret", "\u6dfb\u52a0\u5bc6\u94a5") else t(language, "Edit secret", "\u7f16\u8f91\u5bc6\u94a5"),
          style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
          value = nameDraft,
          onValueChange = { nameDraft = it },
          label = { Text(t(language, "Name", "\u540d\u79f0")) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = valueDraft,
          onValueChange = { valueDraft = it },
          label = { Text(t(language, "Secret", "\u5bc6\u94a5")) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            t(language, "Visible to agent", "\u5bf9 agent \u53ef\u89c1"),
            style = MaterialTheme.typography.bodyMedium,
          )
          Switch(
            checked = agentAllowedDraft,
            onCheckedChange = { agentAllowedDraft = it },
            modifier = Modifier.semantics { contentDescription = "Secret visible to agent switch" },
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              controller.saveWorkspaceSecret(
                originalName = editingOriginalName,
                name = nameDraft,
                label = "",
                description = "",
                value = valueDraft,
                agentAllowed = agentAllowedDraft,
              )
              clearDraft()
            },
            enabled = nameDraft.isNotBlank() && valueDraft.isNotBlank(),
          ) {
            Text(t(language, "Save", "\u4fdd\u5b58"))
          }
          if (editingOriginalName.isNotBlank() || nameDraft.isNotBlank() || valueDraft.isNotBlank()) {
            OutlinedButton(onClick = ::clearDraft) {
              Text(t(language, "Clear", "\u6e05\u7a7a"))
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(t(language, "Done", "\u5b8c\u6210"))
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
  var networkEnabledDraft by remember(state.settings.networkEnabled) { mutableStateOf(state.settings.networkEnabled) }
  var webSearchEnabledDraft by remember(state.settings.webSearchEnabled) { mutableStateOf(state.settings.webSearchEnabled) }
  var braveSearchApiKeyDraft by remember(state.settings.braveSearchApiKey) { mutableStateOf(state.settings.braveSearchApiKey) }
  var backgroundKeepAliveDraft by remember(state.settings.backgroundKeepAliveEnabled) {
    mutableStateOf(state.settings.backgroundKeepAliveEnabled)
  }
  val settingsContext = LocalContext.current
  val selectedProvider = ModelProviderCatalog.findSelectableProvider(providerDraft) ?: ModelProviderCatalog.defaultProvider
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
            ModelProviderCatalog.selectableProviders.forEach { provider ->
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
        Text(t(language, "Network", "\u7f51\u7edc"), style = MaterialTheme.typography.titleSmall)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(t(language, "Network tools", "\u7f51\u7edc\u5de5\u5177"), style = MaterialTheme.typography.bodyMedium)
            Text(
              t(
                language,
                "Enabled by default. Turn off only when a workspace must stay offline.",
                "\u9ed8\u8ba4\u5f00\u542f\u3002\u53ea\u6709\u5728 workspace \u5fc5\u987b\u79bb\u7ebf\u65f6\u624d\u5173\u95ed\u3002",
              ),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(
            checked = networkEnabledDraft,
            onCheckedChange = { networkEnabledDraft = it },
            modifier = Modifier.semantics { contentDescription = "Network tools switch" },
          )
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
                "Available when Network is enabled and a Brave Search API key is saved.",
                "\u5728\u7f51\u7edc\u5f00\u542f\u4e14\u5df2\u4fdd\u5b58 Brave Search API key \u65f6\u53ef\u7528\u3002",
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
        Text(t(language, "Background", "\u540e\u53f0"), style = MaterialTheme.typography.titleSmall)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(t(language, "Keep Flovera active", "\u4fdd\u6301 Flovera \u6d3b\u8dc3"), style = MaterialTheme.typography.bodyMedium)
            Text(
              t(
                language,
                "Shows an ongoing notification so Android is less likely to stop long workspace work.",
                "\u663e\u793a\u5e38\u9a7b\u901a\u77e5\uff0c\u964d\u4f4e Android \u505c\u6b62\u957f\u65f6\u95f4 workspace \u4efb\u52a1\u7684\u6982\u7387\u3002",
              ),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(
            checked = backgroundKeepAliveDraft,
            onCheckedChange = {
              backgroundKeepAliveDraft = it
              if (it) requestBackgroundKeepAlivePermissions(settingsContext)
            },
            modifier = Modifier.semantics { contentDescription = "Background keep-alive switch" },
          )
        }
        if (backgroundKeepAliveDraft) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { requestBackgroundKeepAlivePermissions(settingsContext) }) {
              Text(t(language, "Grant keep-alive permissions", "\u6388\u6743\u540e\u53f0\u4fdd\u6301"))
            }
            TextButton(onClick = { openAppNotificationSettings(settingsContext) }) {
              Text(t(language, "Notifications", "\u901a\u77e5"))
            }
          }
        }
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
            "Full Authority auto-applies settings proposals after an audit log. Android permissions and secrets remain app-owned boundaries.",
            "Full Authority 会在记录审计日志后自动应用设置提案。Android 权限和密钥仍然是 app 边界。",
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
            networkEnabled = networkEnabledDraft,
            webSearchEnabled = webSearchEnabledDraft,
            braveSearchApiKey = braveSearchApiKeyDraft,
            backgroundKeepAliveEnabled = backgroundKeepAliveDraft,
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
private fun FloveraSkillSettingsItem(
  skill: com.flovera.app.workspace.FloveraSkillConsoleEntry,
  language: String,
  onEnabledChange: (Boolean) -> Unit,
) {
  val title = if (language == "zh") skill.titleZh.ifBlank { skill.titleEn } else skill.titleEn.ifBlank { skill.titleZh }
  val primaryDescription = if (language == "zh") {
    skill.descriptionZh.ifBlank { skill.descriptionEn }
  } else {
    skill.descriptionEn.ifBlank { skill.descriptionZh }
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.ifBlank { skill.id }, style = MaterialTheme.typography.bodyMedium)
        Text(
          primaryDescription,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          skill.path,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      Switch(
        checked = skill.enabled,
        onCheckedChange = onEnabledChange,
        modifier = Modifier.semantics { contentDescription = "Skill ${skill.id} switch" },
      )
    }
  }
}

@Composable
private fun WorkspaceSecretItem(
  secret: WorkspaceSecret,
  language: String,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onAllowedChange: (Boolean) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
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
          Text(secret.displayLabel, style = MaterialTheme.typography.bodyMedium)
          Text(
            "${secret.normalizedName} ${if (secret.suffix.isBlank()) "" else "****${secret.suffix}"}".trim(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
          if (secret.description.isNotBlank()) {
            Text(
              secret.description,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        Switch(
          checked = secret.agentAllowed,
          onCheckedChange = onAllowedChange,
          modifier = Modifier.semantics { contentDescription = "Secret ${secret.normalizedName} switch" },
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onEdit) {
          Text(t(language, "Edit", "\u7f16\u8f91"))
        }
        TextButton(onClick = onDelete) {
          Text(t(language, "Delete", "\u5220\u9664"))
        }
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
    changes.backgroundKeepAliveEnabled?.let { "backgroundKeepAlive=$it" },
    changes.pythonRunToolFallbackEnabled?.let { "pythonRunFallback=$it" },
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
