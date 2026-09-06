package com.phoneoperator

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.phoneoperator.ai.AnthropicAIClient
import com.phoneoperator.executor.ActionExecutor
import com.phoneoperator.memory.ExecutionHistoryStore
import com.phoneoperator.memory.MemoryStore
import com.phoneoperator.security.ApiKeyStore
import com.phoneoperator.security.ConfirmationManager
import com.phoneoperator.ui.ChatScreen
import com.phoneoperator.ui.ChatViewModel
import com.phoneoperator.voice.VoiceInputManager

/**
 * The brain/hands split lives here: `AnthropicAIClient` is the only object
 * that reasons about intent and talks to the network; everything else
 * (`ActionExecutor`, `PhoneOperatorAccessibilityService`) only knows how to
 * carry out a single already-decided step. Swapping AI providers means
 * touching this one constructor call — nothing downstream changes.
 */
class MainActivity : ComponentActivity() {

    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var apiKeyStore: ApiKeyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        apiKeyStore = ApiKeyStore(applicationContext)
        val memoryStore = MemoryStore(applicationContext)
        val historyStore = ExecutionHistoryStore(applicationContext)
        val confirmationManager = ConfirmationManager()
        val executor = ActionExecutor(applicationContext)

        val aiClient = AnthropicAIClient(
            apiKeyProvider = {
                apiKeyStore.getApiKey()
                    ?: throw IllegalStateException("No API key set. Add one in Settings.")
            }
        )

        voiceInputManager = VoiceInputManager(this)

        val viewModel = ChatViewModel(aiClient, executor, memoryStore, confirmationManager, historyStore)

        ensureAccessibilityServiceEnabled()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.uiState.collectAsState()
                    var showKeyPrompt by remember { mutableStateOf(apiKeyStore.getApiKey() == null) }

                    if (showKeyPrompt) {
                        ApiKeySetupDialog(
                            onSave = { key ->
                                apiKeyStore.setApiKey(key)
                                showKeyPrompt = false
                            }
                        )
                    } else {
                        ChatScreen(
                            state = state,
                            onSend = { viewModel.onUserMessage(it) },
                            onMicTap = {
                                voiceInputManager.startListening { transcript ->
                                    viewModel.onUserMessage(transcript)
                                }
                            },
                            onStop = { viewModel.stopExecution() },
                            onConfirm = { viewModel.confirmPendingAction(it) }
                        )
                    }
                }
            }
        }
    }

    /** The app is useless without this permission; prompt once, don't nag repeatedly. */
    private fun ensureAccessibilityServiceEnabled() {
        val enabled = com.phoneoperator.accessibility.PhoneOperatorAccessibilityService.instance != null
        if (!enabled) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

/**
 * One-time, minimal setup — no onboarding carousel, just the one thing the
 * app actually needs before it can reason about anything. The key is written
 * straight to ApiKeyStore (Keystore-encrypted); it never touches logs or
 * regular SharedPreferences.
 */
@Composable
private fun ApiKeySetupDialog(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* required before the app is usable */ },
        title = { Text("Connect your AI") },
        text = {
            Column {
                Text("Paste your Anthropic API key. It's stored encrypted, on-device only.")
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (key.isNotBlank()) onSave(key.trim()) }) { Text("Save") }
        }
    )
}
