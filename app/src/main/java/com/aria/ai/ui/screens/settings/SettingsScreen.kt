package com.aria.ai.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aria.ai.agents.MasterBrain
import com.aria.ai.core.audio.AriaListenerService
import com.aria.ai.core.ml.WakeWordDetector
import com.aria.ai.core.network.NetworkMonitor
import com.aria.ai.core.overlay.AriaOverlayFGS
import com.aria.ai.core.system.NotificationReader
import com.aria.ai.core.tts.TtsEngine
import com.aria.ai.data.repository.ConversationRepository
import com.aria.ai.data.repository.ProviderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aria.ai.ui.components.GlassCard
import com.aria.ai.ui.theme.AriaError
import com.aria.ai.ui.theme.AriaSuccess
import com.aria.ai.ui.theme.AriaWarning
import com.aria.ai.ui.theme.LineGray
import com.aria.ai.ui.theme.MistDim
import com.aria.ai.ui.theme.MistWhite
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.NeonPurple

/** Settings screen state. */
data class SettingsUiState(
    val autoSpeak: Boolean = true,
    val wakeWord: String = ProviderSettingsRepository.DEFAULT_WAKE_WORD,
    val wakeWordModelPath: String = "",
    val wakeWordModelLoaded: Boolean = false,
    val gemmaModelPath: String = "",
    val notificationAccess: Boolean = false,
    val overlayActive: Boolean = false,
    val alwaysListening: Boolean = false,
    val online: Boolean = true,
    val ttsReady: Boolean = false,
    val agentNames: List<String> = emptyList(),
    val turnCount: Int = 0
)

/** Raw preference bundle used to build [SettingsUiState]. */
private data class PreferenceSnapshot(
    val autoSpeak: Boolean,
    val wakeWord: String,
    val gemmaPath: String,
    val wakeModelPath: String,
    val turnCount: Int
)

/**
 * Settings state holder: pre/post-processing toggles, wake-word model wiring,
 * service access shortcuts (notifications, accessibility, overlay, write-settings)
 * and history management.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ProviderSettingsRepository,
    private val wakeWord: WakeWordDetector,
    private val tts: TtsEngine,
    private val network: NetworkMonitor,
    private val conversations: ConversationRepository,
    private val brain: MasterBrain
) : ViewModel() {

    private val wakeModelPath = MutableStateFlow("")
    private val turnCount = MutableStateFlow(0)
    private val overlayRunning = MutableStateFlow(false)
    private val listeningActive = MutableStateFlow(false)
    private val preferences = combine(
        settings.autoSpeak,
        settings.wakeWord,
        settings.gemmaModelPath,
        wakeModelPath,
        turnCount
    ) { autoSpeak, wake, gemma, wakePath, turns ->
        PreferenceSnapshot(autoSpeak, wake, gemma.orEmpty(), wakePath, turns)
    }

    /** Overlay + always-listening flags, packed as a Pair to stay within the 5-arg combine overload. */
    private val serviceFlags: kotlinx.coroutines.flow.Flow<Pair<Boolean, Boolean>> =
        combine(overlayRunning, listeningActive) { overlay, listening -> overlay to listening }

    val state: StateFlow<SettingsUiState> = combine(
        preferences,
        NotificationReader.connected,
        tts.ready,
        network.online,
        serviceFlags
    ) { prefs, notifications, ttsReady, online, (overlay, listening) ->
        SettingsUiState(
            autoSpeak = prefs.autoSpeak,
            wakeWord = prefs.wakeWord,
            wakeWordModelPath = prefs.wakeModelPath,
            wakeWordModelLoaded = wakeWord.isModelLoaded(),
            gemmaModelPath = prefs.gemmaPath,
            notificationAccess = notifications,
            overlayActive = overlay,
            alwaysListening = listening,
            online = online,
            ttsReady = ttsReady,
            agentNames = brain.agentNames,
            turnCount = prefs.turnCount
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            turnCount.value = conversations.messageCount()
            network.start()
        }
    }

    fun setAutoSpeak(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoSpeak(enabled) }
    }

    fun setWakeWord(value: String) {
        viewModelScope.launch { settings.setWakeWord(value) }
    }

    fun setGemmaModelPath(value: String) {
        viewModelScope.launch { settings.setGemmaModelPath(value) }
    }

    /** Loads (or unloads) the TFLite wake-word model at [value]. */
    fun setWakeModelPath(value: String) {
        wakeModelPath.value = value
        viewModelScope.launch {
            if (value.isBlank()) {
                wakeWord.unload()
            } else if (wakeWord.loadModel(value)) {
                tts.speak("Wake word model loaded.")
            }
        }
    }

    fun speakPreview() {
        tts.speak("I am Aria, your voice-first execution assistant.")
    }

    fun clearHistory() {
        viewModelScope.launch {
            conversations.clearAll()
            turnCount.value = conversations.messageCount()
        }
    }

    fun startOverlay() {
        AriaOverlayFGS.start(context, "Aria • running")
        overlayRunning.value = true
    }

    fun stopOverlay() {
        AriaOverlayFGS.stop(context)
        overlayRunning.value = false
    }

    // --------------------------------------------------- always listening

    fun startAlwaysListening(context: Context) {
        val intent = Intent(context, AriaListenerService::class.java)
        ContextCompat.startForegroundService(context, intent)
        listeningActive.value = true
    }

    fun stopAlwaysListening(context: Context) {
        context.stopService(Intent(context, AriaListenerService::class.java))
        listeningActive.value = false
    }

    fun notificationAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlayPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    fun writeSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    }

/**
 * Settings: assistant behaviour, wake word + on-device model wiring, service
 * access shortcuts and memory management. Privileged toggles deep-link into the
 * matching system screen instead of failing silently.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* system screens report nothing back; the status flows update themselves */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack) { Text("Back", color = MistWhite) }
            Spacer(Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = NeonCyan)
        }

        Spacer(Modifier.height(14.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Assistant", style = MaterialTheme.typography.titleMedium, color = MistWhite)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Speak replies aloud", color = MistWhite)
                    Text(
                        text = if (state.ttsReady) "TTS engine ready" else "TTS engine unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.ttsReady) AriaSuccess else AriaWarning
                    )
                }
                Switch(
                    checked = state.autoSpeak,
                    onCheckedChange = viewModel::setAutoSpeak,
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.speakPreview() }) {
                Text("Test voice", color = NeonCyan)
            }
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Wake word", style = MaterialTheme.typography.titleMedium, color = MistWhite)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.wakeWord,
                onValueChange = viewModel::setWakeWord,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Phrase (e.g. hey aria)", color = MistDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = LineGray,
                    focusedTextColor = MistWhite,
                    unfocusedTextColor = MistWhite,
                    cursorColor = NeonCyan
                )
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.wakeWordModelPath,
                onValueChange = viewModel::setWakeModelPath,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Optional .tflite keyword model path", color = MistDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = LineGray,
                    focusedTextColor = MistWhite,
                    unfocusedTextColor = MistWhite,
                    cursorColor = NeonCyan
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.wakeWordModelLoaded) {
                    "Keyword model loaded — phrase spotting active"
                } else {
                    "No model loaded — Aria falls back to the energy gate"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.wakeWordModelLoaded) AriaSuccess else MistDim
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("On-device model", style = MaterialTheme.typography.titleMedium, color = MistWhite)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.gemmaModelPath,
                onValueChange = viewModel::setGemmaModelPath,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Gemma model path (.bin / .task)", color = MistDim) },
                placeholder = { Text("/sdcard/Download/gemma-2b-it.bin", color = MistDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = LineGray,
                    focusedTextColor = MistWhite,
                    unfocusedTextColor = MistWhite,
                    cursorColor = NeonCyan
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Leave this empty until you have copied a MediaPipe-compatible model onto the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MistDim
            )
        }

        Spacer(Modifier.height(12.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Services", style = MaterialTheme.typography.titleMedium, color = MistWhite)
            Spacer(Modifier.height(4.dp))
            ServiceRow(
                title = "AI providers",
                status = "Encrypted API key vault",
                action = "Open",
                onClick = onOpenProviders
            )
            ServiceRow(
                title = "Notification access",
                status = if (state.notificationAccess) "Connected" else "Not connected",
                action = "Enable",
                onClick = { launcher.launch(viewModel.notificationAccessIntent()) }
            )
            ServiceRow(
                title = "Accessibility navigation",
                status = "Needed for home / back / lock gestures",
                action = "Enable",
                onClick = { launcher.launch(viewModel.accessibilityIntent()) }
            )
            ServiceRow(
                title = "Display over other apps",
                status = "Needed for the floating HUD",
                action = "Grant",
                onClick = { launcher.launch(viewModel.overlayPermissionIntent()) }
            )
            ServiceRow(
                title = "Floating HUD",
                status = if (state.overlayActive) "Active" else "Stopped",
                action = if (state.overlayActive) "Stop" else "Start",
                onClick = {
                    if (state.overlayActive) viewModel.stopOverlay() else viewModel.startOverlay()
                }
            )
            ServiceRow(
                title = "Modify system settings",
                status = "Needed for screen brightness control",
                action = "Grant",
                onClick = { launcher.launch(viewModel.writeSettingsIntent()) }
            )

            AlwaysListeningRow(
                enabled = state.alwaysListening,
                onToggle = { enabled ->
                    if (enabled) {
                        viewModel.startAlwaysListening(context)
                    } else {
                        viewModel.stopAlwaysListening(context)
                    }
                }
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Memory", style = MaterialTheme.typography.titleMedium, color = MistWhite)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${state.turnCount} stored turns · ${state.agentNames.size} specialist agents",
                style = MaterialTheme.typography.bodySmall,
                color = MistDim
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Agents: ${state.agentNames.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MistDim
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.clearHistory() }) {
                Text("Clear conversation history", color = AriaError)
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (state.online) "Network: online" else "Network: offline — cloud providers unreachable",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.online) AriaSuccess else AriaError
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ServiceRow(
    title: String,
    status: String,
    action: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MistWhite, style = MaterialTheme.typography.bodyLarge)
            Text(status, color = MistDim, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) { Text(action, color = NeonPurple) }
    }
}

/**
 * The always-listening control: a switch that starts/stops
 * [AriaListenerService], the microphone foreground service that watches for the
 * wake word and opens a full-duplex Gemini Live session.
 */
@Composable
private fun AlwaysListeningRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Always listening", color = MistWhite, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (enabled) {
                    "Microphone service active — say \u201cHey Aria\u201d"
                } else {
                    "Off — tap to watch for the wake word"
                },
                color = MistDim,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NeonCyan,
                checkedTrackColor = NeonPurple
            )
        )
    }
}