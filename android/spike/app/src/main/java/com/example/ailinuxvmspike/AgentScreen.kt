package com.example.ailinuxvmspike

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ailinuxvmspike.session.SessionMessage
import kotlinx.coroutines.launch

private enum class AgentPanel {
  Sessions,
  Files,
  AgentFile,
  Settings,
}

@Composable
fun AgentScreen(controller: AgentController, modifier: Modifier = Modifier) {
  val state by controller.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var menuOpen by remember { mutableStateOf(false) }
  var activePanel by remember { mutableStateOf<AgentPanel?>(null) }

  Box(modifier = modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Android Workspace Agent", style = MaterialTheme.typography.titleMedium)
          Text(
            "${state.settings.model} / ${state.status}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Box {
          OutlinedButton(onClick = { menuOpen = true }) {
            Text("Menu")
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        val messages = state.session?.messages.orEmpty()
        if (messages.isEmpty()) {
          item {
            EmptyConversation()
          }
        } else {
          items(messages) { message ->
            MessageBubble(message)
          }
        }
      }

      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

  when (activePanel) {
    AgentPanel.Sessions -> SessionsDialog(
      state = state,
      controller = controller,
      onDismiss = { activePanel = null },
    )

    AgentPanel.Files -> FilesDialog(
      state = state,
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
private fun EmptyConversation() {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
    color = Color.Transparent,
  ) {
    Text(
      text = "Start a session by asking the agent to create or edit files in the Android workspace.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
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
          Text(
            text = message.content,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
          )
          message.toolEvents.forEach { event ->
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = textColor.copy(alpha = 0.10f),
            ) {
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
private fun SessionsDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Sessions") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Active: ${state.session?.id ?: "none"}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = controller::newSession, modifier = Modifier.fillMaxWidth()) {
          Text("New Session")
        }
        state.sessions.forEach { session ->
          OutlinedButton(
            onClick = {
              controller.openSession(session.id)
              onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("${session.title} (${session.messages.size} messages)")
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
private fun FilesDialog(
  state: AgentScreenState,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Workspace Files") },
    text = {
      SelectionContainer {
        Text(
          text = state.workspaceFiles.ifBlank { "(empty)" },
          modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).verticalScroll(rememberScrollState()),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
        )
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
private fun AgentFileDialog(
  state: AgentScreenState,
  controller: AgentController,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("AGENT.md") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = state.agentRulesDraft,
          onValueChange = controller::updateAgentRules,
          label = { Text("Workspace agent rules") },
          minLines = 10,
          modifier = Modifier.fillMaxWidth(),
        )
      }
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
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Settings") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${state.settings.provider} / ${state.settings.model}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
          value = state.apiKeyDraft,
          onValueChange = controller::updateApiKey,
          label = { Text("DeepSeek API key") },
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
          controller.saveApiKey()
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
