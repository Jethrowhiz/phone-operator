package com.phoneoperator.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Deliberately plain: no dashboards, no heavy chrome. A message list, an
 * input row, a mic button, and a slim execution/stop strip that only
 * appears while something is running. See UI DESIGN section of the spec.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onMicTap: () -> Unit,
    onStop: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            items(state.entries) { entry ->
                when (entry) {
                    is ChatEntry.UserMessage -> Text("You: ${entry.text}", Modifier.padding(vertical = 4.dp))
                    is ChatEntry.AiMessage -> Text(entry.text, Modifier.padding(vertical = 4.dp))
                    is ChatEntry.ExecutionStep -> {
                        val prefix = when (entry.status) {
                            StepStatus.DONE -> "✓"
                            StepStatus.FAILED -> "✗"
                            StepStatus.RUNNING -> "…"
                        }
                        Text("$prefix ${entry.label}", Modifier.padding(start = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }

        state.pendingConfirmationText?.let { text ->
            ConfirmationBar(text, onConfirm)
        }

        if (state.isExecuting) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Working on it…")
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMicTap) {
                Icon(Icons.Filled.Mic, contentDescription = "Voice input")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tell your phone what to do…") }
            )
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    onSend(input)
                    input = ""
                }
            }) {
                Icon(Icons.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ConfirmationBar(actionText: String, onConfirm: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Confirm: $actionText")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onConfirm(false) }) { Text("Cancel") }
            Button(onClick = { onConfirm(true) }) { Text("Confirm") }
        }
    }
}
