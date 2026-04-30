package com.example.ailinuxvmspike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SpikeScreen(controller: VmController, modifier: Modifier = Modifier) {
  val state by controller.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.prepareAssets() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Prepare Assets")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.startVm() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Start VM")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.stopVm() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Stop VM")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = { scope.launch { controller.runEchoReady() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Run echo ready")
      }
    }
    Card(
      modifier = Modifier.fillMaxWidth().height(240.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      SelectionContainer {
        Text(
          text = state.logText,
          modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}
