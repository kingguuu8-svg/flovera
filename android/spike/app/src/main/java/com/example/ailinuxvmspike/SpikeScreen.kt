package com.example.ailinuxvmspike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SpikeScreen(controller: VmController, modifier: Modifier = Modifier) {
  val state by controller.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val terminalScrollState = rememberScrollState()
  val diagnosticsScrollState = rememberScrollState()

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "Linux status: ${state.linuxStatus.name.lowercase()}",
      style = MaterialTheme.typography.titleMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.prepareAssets() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Prepare Linux")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.startVm() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Start Linux")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.pauseLinux() } }, modifier = Modifier.weight(1f)) {
        Text("Pause")
      }
      Button(onClick = { scope.launch { controller.resumeLinux() } }, modifier = Modifier.weight(1f)) {
        Text("Resume")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.stopVm() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Shutdown")
      }
    }
    OutlinedTextField(
      value = state.terminalCommand,
      onValueChange = controller::updateTerminalCommand,
      label = { Text("Terminal command") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.runTerminalCommand() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Run Command")
      }
    }
    Text(
      text = "Terminal",
      style = MaterialTheme.typography.titleSmall,
    )
    Card(
      modifier = Modifier.fillMaxWidth().weight(1f),
      colors = CardDefaults.cardColors(containerColor = Color(0xFF101418)),
    ) {
      SelectionContainer {
        Text(
          text = state.terminalText,
          modifier = Modifier.fillMaxSize().verticalScroll(terminalScrollState).padding(12.dp),
          color = Color(0xFFE6F3E6),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    Text(
      text = "Diagnostics",
      style = MaterialTheme.typography.titleSmall,
    )
    Card(
      modifier = Modifier.fillMaxWidth().height(144.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      SelectionContainer {
        Text(
          text = state.diagnosticsText,
          modifier = Modifier.fillMaxSize().verticalScroll(diagnosticsScrollState).padding(12.dp),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}
