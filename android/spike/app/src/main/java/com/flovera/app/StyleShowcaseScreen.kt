package com.flovera.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Web
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class StyleKind {
  Instrument,
  Archive,
  Signal,
  Atelier,
}

private data class StyleVariant(
  val kind: StyleKind,
  val name: String,
  val summary: String,
  val background: Color,
  val surface: Color,
  val elevated: Color,
  val line: Color,
  val text: Color,
  val muted: Color,
  val accent: Color,
  val accentSoft: Color,
  val userBubble: Color,
  val assistantBubble: Color,
  val mono: Boolean = false,
)

private val StyleVariants = listOf(
  StyleVariant(
    kind = StyleKind.Instrument,
    name = "Quiet Instrument",
    summary = "Icon-derived teal, off-white surface, charcoal linework.",
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFE9F0EF),
    line = Color(0xFFD4DEDC),
    text = Color(0xFF1D232A),
    muted = Color(0xFF667174),
    accent = Color(0xFF127089),
    accentSoft = Color(0xFFD9EEF1),
    userBubble = Color(0xFFD7EFF0),
    assistantBubble = Color(0xFFEEF2F1),
  ),
  StyleVariant(
    kind = StyleKind.Archive,
    name = "Warm Archive",
    summary = "Paper, ink, durable record, calmer than chat.",
    background = Color(0xFFF3EFE6),
    surface = Color(0xFFFBF7EE),
    elevated = Color(0xFFE8DECC),
    line = Color(0xFFD0C2AA),
    text = Color(0xFF302820),
    muted = Color(0xFF766B5C),
    accent = Color(0xFF8E6F4D),
    accentSoft = Color(0xFFEADCC9),
    userBubble = Color(0xFFE3D1B8),
    assistantBubble = Color(0xFFF2EADF),
  ),
  StyleVariant(
    kind = StyleKind.Signal,
    name = "Dark Signal",
    summary = "Charcoal interface with restrained telemetry.",
    background = Color(0xFF111516),
    surface = Color(0xFF171D1F),
    elevated = Color(0xFF20282B),
    line = Color(0xFF2C373B),
    text = Color(0xFFE8EFEF),
    muted = Color(0xFF9BA8A9),
    accent = Color(0xFF74B7B6),
    accentSoft = Color(0xFF243A3C),
    userBubble = Color(0xFF213A3B),
    assistantBubble = Color(0xFF20272A),
    mono = true,
  ),
  StyleVariant(
    kind = StyleKind.Atelier,
    name = "Atelier Minimal",
    summary = "Studio surface, sparse controls, object focus.",
    background = Color(0xFFF8F8F6),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFEFEFEC),
    line = Color(0xFFDADAD5),
    text = Color(0xFF171717),
    muted = Color(0xFF666965),
    accent = Color(0xFF3D7D68),
    accentSoft = Color(0xFFDCE9E2),
    userBubble = Color(0xFFDCE9E2),
    assistantBubble = Color(0xFFF0F0ED),
  ),
)

@Composable
fun StyleShowcaseApp() {
  val variant = StyleVariants.first()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(variant.background)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    StyleShowcaseHeader(variant = variant)
    StyleIconBoard(variant = variant)
    StylePhoneSurface(
      variant = variant,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun StyleShowcaseHeader(variant: StyleVariant) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f),
    ) {
      Image(
        painter = painterResource(id = R.drawable.flovera_style_icon),
        contentDescription = null,
        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)),
      )
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "Flovera Style",
          color = variant.text,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "Icon, theme, and action language.",
          color = variant.muted,
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = variant.accentSoft,
      contentColor = variant.accent,
      border = BorderStroke(1.dp, variant.line),
    ) {
      Text(
        text = "Quiet base",
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun StyleTabs(
  variants: List<StyleVariant>,
  selected: Int,
  onSelect: (Int) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    variants.forEachIndexed { index, variant ->
      val active = index == selected
      Surface(
        modifier = Modifier.clickable { onSelect(index) },
        shape = RoundedCornerShape(14.dp),
        color = if (active) variant.accent else variant.surface,
        contentColor = if (active) variant.surface else variant.text,
        border = BorderStroke(1.dp, if (active) variant.accent else variant.line),
      ) {
        Text(
          text = variant.name,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun StyleIconBoard(variant: StyleVariant) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = variant.surface,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Icon language", color = variant.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
          Text("Launcher mark + action candidates", color = variant.muted, style = MaterialTheme.typography.labelSmall)
        }
        Image(
          painter = painterResource(id = R.drawable.flovera_style_icon),
          contentDescription = null,
          modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)),
        )
      }
      StyleIconCandidateRow(
        variant = variant,
        title = "Send",
        selectedLabel = "send",
        candidates = listOf(
          IconCandidate("send", Icons.AutoMirrored.Filled.Send),
          IconCandidate("up", Icons.Filled.ArrowUpward),
          IconCandidate("near", Icons.Filled.NearMe),
          IconCandidate("run", Icons.Filled.PlayArrow),
          IconCandidate("spark", Icons.Filled.Bolt),
          IconCandidate("return", Icons.AutoMirrored.Filled.KeyboardReturn),
        ),
      )
      StyleIconCandidateRow(
        variant = variant,
        title = "Preview",
        selectedLabel = "preview",
        candidates = listOf(
          IconCandidate("preview", Icons.Filled.Preview),
          IconCandidate("view", Icons.Filled.Visibility),
          IconCandidate("web", Icons.Filled.Web),
          IconCandidate("board", Icons.Filled.Dashboard),
          IconCandidate("doc", Icons.Filled.Article),
        ),
      )
      StyleIconCandidateRow(
        variant = variant,
        title = "Settings",
        selectedLabel = "settings",
        candidates = listOf(
          IconCandidate("settings", Icons.Filled.Settings),
          IconCandidate("tune", Icons.Filled.Tune),
          IconCandidate("display", Icons.Filled.DisplaySettings),
          IconCandidate("build", Icons.Filled.Build),
        ),
      )
      StyleIconCandidateRow(
        variant = variant,
        title = "Agent",
        selectedLabel = "chat",
        candidates = listOf(
          IconCandidate("chat", Icons.Filled.ChatBubble),
          IconCandidate("forum", Icons.Filled.Forum),
          IconCandidate("mind", Icons.Filled.Psychology),
          IconCandidate("bolt", Icons.Filled.Bolt),
        ),
      )
    }
  }
}

private data class IconCandidate(
  val label: String,
  val icon: ImageVector,
)

@Composable
private fun StyleIconCandidateRow(
  variant: StyleVariant,
  title: String,
  selectedLabel: String,
  candidates: List<IconCandidate>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, color = variant.muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      candidates.forEach { candidate ->
        StyleIconCandidateCell(
          variant = variant,
          label = candidate.label,
          icon = candidate.icon,
          selected = candidate.label == selectedLabel,
        )
      }
    }
  }
}

@Composable
private fun StyleIconCandidateCell(
  variant: StyleVariant,
  label: String,
  icon: ImageVector,
  selected: Boolean,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = if (selected) variant.accent else variant.elevated,
    contentColor = if (selected) variant.surface else variant.text,
    border = BorderStroke(1.dp, if (selected) variant.accent else variant.line),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun StylePhoneSurface(variant: StyleVariant, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(26.dp),
    color = variant.surface,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
    tonalElevation = 0.dp,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      StyleDisplayPlane(
        variant = variant,
        modifier = Modifier.weight(1f),
      )
      StyleBottomCommandBarV2(variant)
    }
  }
}

@Composable
private fun StyleDisplayPlane(variant: StyleVariant, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .background(
        when (variant.kind) {
          StyleKind.Signal -> Brush.verticalGradient(listOf(Color(0xFF0E1314), variant.background))
          StyleKind.Archive -> Brush.verticalGradient(listOf(Color(0xFFF7F0E3), variant.background))
          StyleKind.Atelier -> Brush.verticalGradient(listOf(Color(0xFFFFFFFF), variant.background))
          StyleKind.Instrument -> Brush.verticalGradient(listOf(variant.surface, variant.background))
        },
      )
      .padding(14.dp),
  ) {
    when (variant.kind) {
      StyleKind.Instrument -> StyleInstrumentPlane(variant)
      StyleKind.Archive -> StyleArchivePlane(variant)
      StyleKind.Signal -> StyleSignalPlane(variant)
      StyleKind.Atelier -> StyleAtelierPlane(variant)
    }
  }
}

@Composable
private fun StyleInstrumentPlane(variant: StyleVariant) {
  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StyleArtifactPreview(variant)
      StyleConversationPreview(variant)
    }
    StyleOverlayStack(
      variant = variant,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(bottom = 8.dp),
    )
  }
}

@Composable
private fun StyleArchivePlane(variant: StyleVariant) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().weight(1f),
      shape = RoundedCornerShape(6.dp),
      color = variant.surface,
      contentColor = variant.text,
      border = BorderStroke(1.dp, variant.line),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
      ) {
        Row(verticalAlignment = Alignment.Top) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FLOVERA WORK FILE", color = variant.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("Scientific calculator", color = variant.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          }
          Surface(
            shape = RoundedCornerShape(2.dp),
            color = Color.Transparent,
            contentColor = variant.accent,
            border = BorderStroke(1.dp, variant.accent),
          ) {
            Text("READY", modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
          }
        }
        StyleArchiveRule(variant, 0.88f)
        StyleArchiveRule(variant, 0.64f)
        StyleArchiveRule(variant, 0.76f)
        StyleArchiveRule(variant, 0.52f)
        Spacer(modifier = Modifier.weight(1f))
        StyleEventRow(variant, "file note", "2 preview artifacts attached")
      }
    }
    StyleArchiveComment(variant, "Instruction and result history read like annotations, not chat bubbles.")
  }
}

@Composable
private fun StyleArchiveRule(variant: StyleVariant, fraction: Float) {
  Box(
    modifier = Modifier
      .fillMaxWidth(fraction)
      .height(1.dp)
      .background(variant.line),
  )
}

@Composable
private fun StyleArchiveComment(variant: StyleVariant, text: String) {
  Surface(
    shape = RoundedCornerShape(7.dp),
    color = variant.elevated,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      style = MaterialTheme.typography.bodySmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun StyleSignalPlane(variant: StyleVariant) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      StyleSignalMetric(variant, "RUN", "ACTIVE", Modifier.weight(1f))
      StyleSignalMetric(variant, "FILES", "02", Modifier.weight(1f))
      StyleSignalMetric(variant, "VIEW", "HTML", Modifier.weight(1f))
    }
    Surface(
      modifier = Modifier.fillMaxWidth().weight(1f),
      shape = RoundedCornerShape(4.dp),
      color = variant.surface,
      contentColor = variant.text,
      border = BorderStroke(1.dp, variant.line),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        Text("workspace://preview/index.html", color = variant.accent, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        StyleSignalLine(variant, "[tool] write_file /app/src/index.html")
        StyleSignalLine(variant, "[render] preview server hot reload")
        StyleSignalLine(variant, "[agent] final response committed")
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
          listOf(0.34f, 0.62f, 0.48f, 0.82f, 0.56f).forEach { alpha ->
            Box(
              modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(variant.accent.copy(alpha = alpha)),
            )
          }
        }
      }
    }
    StyleOverlayStack(variant)
  }
}

@Composable
private fun StyleSignalMetric(variant: StyleVariant, label: String, value: String, modifier: Modifier) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(4.dp),
    color = variant.elevated,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
  ) {
    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(label, color = variant.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
      Text(value, color = variant.accent, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun StyleSignalLine(variant: StyleVariant, text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(modifier = Modifier.size(5.dp).background(variant.accent))
    Text(text, color = variant.muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun StyleAtelierPlane(variant: StyleVariant) {
  Box(modifier = Modifier.fillMaxSize()) {
    Surface(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .height(210.dp),
      shape = RoundedCornerShape(28.dp),
      color = variant.surface,
      contentColor = variant.text,
      border = BorderStroke(1.dp, variant.line),
    ) {
      Box(modifier = Modifier.padding(18.dp)) {
        Text("Canvas", color = variant.muted, style = MaterialTheme.typography.labelMedium)
        Surface(
          modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.62f).height(92.dp),
          shape = RoundedCornerShape(2.dp),
          color = variant.elevated,
          border = BorderStroke(1.dp, variant.line),
        ) {}
        Text(
          text = "Result has the stage. Controls stay quiet.",
          modifier = Modifier.align(Alignment.BottomStart),
          color = variant.text,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    Column(
      modifier = Modifier.align(Alignment.BottomCenter),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      StyleBubble(
        variant = variant,
        text = "Create a spare product page with one precise interaction.",
        alignEnd = true,
        color = variant.userBubble,
      )
      StyleOverlayStack(variant)
    }
  }
}

@Composable
private fun StyleArtifactPreview(variant: StyleVariant) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = variant.elevated,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Preview, contentDescription = null, modifier = Modifier.size(17.dp), tint = variant.accent)
        Text("index.html", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.weight(1f))
        Text("live", color = variant.accent, style = MaterialTheme.typography.labelSmall)
      }
      StylePreviewRows(variant)
    }
  }
}

@Composable
private fun StylePreviewRows(variant: StyleVariant) {
  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    listOf(0.86f, 0.62f, 0.74f, 0.48f).forEachIndexed { index, fraction ->
      Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (index == 0) variant.accent else variant.line),
        )
        Box(
          modifier = Modifier
            .fillMaxWidth(fraction)
            .height(if (index == 0) 11.dp else 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (index == 0) variant.text.copy(alpha = 0.16f) else variant.line.copy(alpha = 0.72f)),
        )
      }
    }
  }
}

@Composable
private fun StyleConversationPreview(variant: StyleVariant) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    StyleBubble(
      variant = variant,
      text = "Make the result area feel larger and keep the controls quiet.",
      alignEnd = true,
      color = variant.userBubble,
    )
    StyleEventRow(variant, "write_file", "Updated index.html")
    StyleBubble(
      variant = variant,
      text = "Adjusted the main display and kept the conversation history available.",
      alignEnd = false,
      color = variant.assistantBubble,
    )
  }
}

@Composable
private fun StyleBubble(variant: StyleVariant, text: String, alignEnd: Boolean, color: Color) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.82f),
      shape = RoundedCornerShape(
        topStart = 15.dp,
        topEnd = 15.dp,
        bottomStart = if (alignEnd) 15.dp else 5.dp,
        bottomEnd = if (alignEnd) 5.dp else 15.dp,
      ),
      color = color,
      contentColor = variant.text,
      border = BorderStroke(1.dp, variant.line.copy(alpha = 0.72f)),
    ) {
      Text(
        text = text,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun StyleEventRow(variant: StyleVariant, title: String, detail: String) {
  Row(
    modifier = Modifier.padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(">", color = variant.accent, style = MaterialTheme.typography.labelMedium)
    Text(title, color = variant.text, style = MaterialTheme.typography.labelSmall, fontFamily = if (variant.mono) FontFamily.Monospace else null)
    Text(detail, color = variant.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun StyleOverlayStack(variant: StyleVariant, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Bottom),
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = variant.surface.copy(alpha = 0.95f),
      contentColor = variant.text,
      border = BorderStroke(1.dp, variant.line),
      tonalElevation = 0.dp,
    ) {
      Text(
        text = "Updated calculator layout and wrote 2 preview files. Open Conversation for the full transcript.",
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun StyleBottomCommandBar(variant: StyleVariant) {
  Surface(
    color = variant.surface,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(variant.accent),
        )
        Text(
          text = "Running · index.html · text/html",
          color = variant.muted,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
          modifier = Modifier.weight(1f).height(44.dp),
          shape = RoundedCornerShape(15.dp),
          color = variant.background,
          contentColor = variant.text,
          border = BorderStroke(1.dp, variant.line),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(17.dp), tint = variant.muted)
            Text("Message Flovera", color = variant.muted, style = MaterialTheme.typography.bodyMedium)
          }
        }
        Surface(
          modifier = Modifier.size(44.dp),
          shape = RoundedCornerShape(15.dp),
          color = variant.accent,
          contentColor = variant.surface,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
          }
        }
        Surface(
          modifier = Modifier.size(44.dp),
          shape = RoundedCornerShape(15.dp),
          color = variant.elevated,
          contentColor = variant.muted,
          border = BorderStroke(1.dp, variant.line),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun StyleBottomCommandBarV2(variant: StyleVariant) {
  val radius = when (variant.kind) {
    StyleKind.Archive -> 6.dp
    StyleKind.Signal -> 4.dp
    StyleKind.Atelier -> 22.dp
    StyleKind.Instrument -> 15.dp
  }
  val statusText = when (variant.kind) {
    StyleKind.Archive -> "Filed / index.html / revision 04"
    StyleKind.Signal -> "RUNNING / PID 47 / HTML"
    StyleKind.Atelier -> "index.html"
    StyleKind.Instrument -> "Running / index.html / text/html"
  }
  val prompt = when (variant.kind) {
    StyleKind.Archive -> "Add instruction to work file"
    StyleKind.Signal -> "command flovera"
    StyleKind.Atelier -> "Ask Flovera"
    StyleKind.Instrument -> "Message Flovera"
  }
  Surface(
    color = variant.surface,
    contentColor = variant.text,
    border = BorderStroke(1.dp, variant.line),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = if (variant.kind == StyleKind.Atelier) 9.dp else 7.dp),
      verticalArrangement = Arrangement.spacedBy(if (variant.kind == StyleKind.Signal) 7.dp else 5.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
          modifier = Modifier
            .size(if (variant.kind == StyleKind.Signal) 6.dp else 8.dp)
            .clip(RoundedCornerShape(if (variant.kind == StyleKind.Signal) 1.dp else 4.dp))
            .background(variant.accent),
        )
        Text(
          text = statusText,
          color = variant.muted,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = if (variant.mono) FontFamily.Monospace else null,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
          modifier = Modifier.weight(1f).height(if (variant.kind == StyleKind.Atelier) 48.dp else 44.dp),
          shape = RoundedCornerShape(radius),
          color = variant.background,
          contentColor = variant.text,
          border = BorderStroke(1.dp, variant.line),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.ChatBubble, contentDescription = null, modifier = Modifier.size(17.dp), tint = variant.muted)
            Text(
              text = prompt,
              color = variant.muted,
              style = MaterialTheme.typography.bodyMedium,
              fontFamily = if (variant.mono) FontFamily.Monospace else null,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Surface(
          modifier = Modifier.size(if (variant.kind == StyleKind.Atelier) 48.dp else 44.dp),
          shape = RoundedCornerShape(radius),
          color = variant.accent,
          contentColor = variant.surface,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
          }
        }
        if (variant.kind != StyleKind.Atelier) {
          Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(radius),
            color = variant.elevated,
            contentColor = variant.muted,
            border = BorderStroke(1.dp, variant.line),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            }
          }
        }
      }
    }
  }
}
