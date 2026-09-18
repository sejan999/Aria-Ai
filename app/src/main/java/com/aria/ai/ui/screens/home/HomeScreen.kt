package com.aria.ai.ui.screens.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aria.ai.agents.MasterBrain
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.ProviderRegistry
import com.aria.ai.core.audio.AudioBridge
import com.aria.ai.core.network.NetworkMonitor
import com.aria.ai.core.vision.ScreenCaptureManager
import com.aria.ai.core.vision.VisionBridge
import com.aria.ai.data.repository.ChatLine
import com.aria.ai.data.repository.ConversationRepository
import com.aria.ai.data.repository.ProviderSettingsRepository
import com.aria.ai.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aria.ai.core.overlay.AriaOverlayFGS
import com.aria.ai.core.system.NotificationReader
import com.aria.ai.ui.components.AriaDock
import com.aria.ai.ui.components.AriaQuantumHUD
import com.aria.ai.ui.components.GlassCard
import com.aria.ai.ui.components.WaveformVisualizer
import com.aria.ai.ui.theme.AriaWarning
import com.aria.ai.ui.theme.MistDim
import com.aria.ai.ui.theme.MistWhite
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.NeonPurple

/** Everything the home HUD renders. */
data class HomeUiState(
    val statusLine: String = "Booting Aria…",
    val providerName: String = "…",
    val online: Boolean = true,
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val amplitude: Float = 0f,
    val transcript: List<ChatLine> = emptyList(),
    val screenVisionArmed: Boolean = false,
    val partialSpeech: String = ""
)

/** Internal snapshot of the current voice session. */
private data class SessionSnapshot(
    val busy: Boolean,
    val listening: Boolean,
    val preparing: Boolean,
    val partial: String
)

/**
 * Home state holder: owns the voice turn (listen → think → answer), the
 * push-to-talk state machine and the screen-vision arming flow.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val brain: MasterBrain,
    private val audio: AudioBridge,
    private val network: NetworkMonitor,
    private val registry: ProviderRegistry,
    private val settings: ProviderSettingsRepository,
    private val conversations: ConversationRepository,
    private val capture: ScreenCaptureManager,
    private val vision: VisionBridge,
    @ApplicationScope private val scope: CoroutineScope
) : ViewModel() {

    private val draft = MutableStateFlow("")
    val input: StateFlow<String> = draft.asStateFlow()

    private val preparingVoice = MutableStateFlow(false)
    private val partialSpeech = MutableStateFlow("")
    private val hint = MutableStateFlow<String?>(null)
    val hintMessage: StateFlow<String?> = hint.asStateFlow()

    init {
        network.start()
        viewModelScope.launch { conversations.ensureConversation() }
    }

    private val session = combine(
        brain.busy,
        audio.listening,
        preparingVoice,
        partialSpeech
    ) { busy, listening, preparing, partial ->
        SessionSnapshot(busy, listening, preparing, partial)
    }

    private val connectivity = combine(
        network.online,
        settings.activeProviderId
    ) { online, providerId -> online to providerId }

    val state: StateFlow<HomeUiState> = combine(
        connectivity,
        session,
        conversations.transcript,
        audio.amplitude,
        capture.active
    ) { conn, sess, transcript, amplitude, armed ->
        HomeUiState(
            statusLine = statusFor(conn.first, sess, armed),
            providerName = ProviderIds.displayName(conn.second),
            online = conn.first,
            listening = sess.listening || sess.preparing,
            thinking = sess.busy,
            amplitude = amplitude,
            transcript = transcript.takeLast(40),
            screenVisionArmed = armed,
            partialSpeech = sess.partial
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onInputChange(value: String) {
        draft.value = value
    }

    /** Sends the draft (or an explicit utterance such as a recognised phrase). */
    fun send(utterance: String = draft.value) {
        val text = utterance.trim()
        if (text.isEmpty()) return
        draft.value = ""
        scope.launch { brain.respond(text) }
    }

    /** Push-to-talk: partial recognition streams into the HUD while speaking. */
    fun startListening() {
        if (preparingVoice.value || audio.listening.value) return
        hint.value = null
        preparingVoice.value = true
        scope.launch {
            audio.listenOnce().collect { event ->
                event.partial?.let { partialSpeech.value = it }
                event.error?.let { message ->
                    hint.value = message
                    partialSpeech.value = ""
                }
                event.finalText?.let { finalText ->
                    partialSpeech.value = ""
                    preparingVoice.value = false
                    if (finalText.isBlank()) {
                        hint.value = "I did not catch that — try again."
                    } else {
                        send(finalText)
                    }
                }
            }
            preparingVoice.value = false
        }
    }

    fun stopListening() {
        audio.stopPcmCapture()
        preparingVoice.value = false
        partialSpeech.value = ""
    }

    fun onMicDenied() {
        hint.value = "Microphone permission is required for voice input."
    }

    fun projectionRequest(): Intent? = capture.requestIntent()

    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val armed = capture.onProjectionResult(resultCode, data)
        hint.value = if (armed) {
            "Screen vision armed. Ask me what I see."
        } else {
            "Screen capture was not granted."
        }
    }

    fun releaseVision() = vision.release()

    fun refreshProviders() {
        viewModelScope.launch {
            hint.value = if (registry.isActiveProviderReady()) {
                null
            } else {
                "No key stored for the active provider. Open Settings → AI Providers."
            }
        }
    }

    fun newSession() {
        viewModelScope.launch { conversations.startNewConversation() }
    }

    fun clearConversation() {
        viewModelScope.launch { conversations.clearAll() }
    }

    fun dismissHint() {
        hint.value = null
    }

    private fun statusFor(online: Boolean, sess: SessionSnapshot, armed: Boolean): String = when {
        !online -> "Offline — cloud providers unreachable"
        sess.busy -> "Thinking…"
        sess.preparing -> "Listening…"
        sess.listening -> "Capturing audio…"
        armed -> "Screen vision armed"
        else -> "Ready"
    }
}

/**
 * Aria's home screen: quantum HUD, live waveform, rolling transcript and the
 * command dock. Voice is first-class — the mic requests RECORD_AUDIO on demand,
 * the eye requests screen capture for the VisionAgent.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.input.collectAsStateWithLifecycle()
    val hint by viewModel.hintMessage.collectAsStateWithLifecycle()
    val notificationsGranted by NotificationReader.connected.collectAsStateWithLifecycle()

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening() else viewModel.onMicDenied()
    }

    val visionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onProjectionResult(result.resultCode, result.data)
    }

    val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val onMicClick: () -> Unit = {
        if (micGranted) {
            viewModel.startListening()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshProviders() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ARIA AI",
                    style = MaterialTheme.typography.titleLarge,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.providerName} · ${state.statusLine}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.online) MistDim else AriaWarning
                )
            }
            OutlinedButton(onClick = onOpenProviders) { Text("Keys", color = NeonCyan) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onOpenSettings) { Text("Settings", color = MistWhite) }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AriaQuantumHUD(
                amplitude = state.amplitude,
                listening = state.listening,
                thinking = state.thinking
            )
        }

        if (state.partialSpeech.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = "“${state.partialSpeech}”", style = MaterialTheme.typography.bodyMedium, color = NeonPurple)
        }

        Spacer(Modifier.height(8.dp))
        WaveformVisualizer(amplitude = state.amplitude, active = state.listening)

        hint?.let { message ->
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = AriaWarning)
                TextButton(onClick = { viewModel.dismissHint() }) {
                    Text("Dismiss", color = NeonCyan)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        TranscriptPanel(
            lines = state.transcript,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onMicClick) { Text("Voice", color = NeonCyan) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { viewModel.projectionRequest()?.let { visionLauncher.launch(it) } }
            ) { Text(if (state.screenVisionArmed) "Vision ON" else "Vision", color = NeonCyan) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { AriaOverlayFGS.start(context, "Aria • listening") }) {
                Text("Overlay", color = NeonPurple)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.newSession() }) { Text("New", color = MistWhite) }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (notificationsGranted) {
                "Notifications: connected"
            } else {
                "Notifications: not connected (Settings → Notification access)"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MistDim
        )

        Spacer(Modifier.height(8.dp))

        AriaDock(
            text = draft,
            onTextChange = viewModel::onInputChange,
            onSend = { viewModel.send() },
            onMic = onMicClick,
            micActive = state.listening,
            busy = state.thinking
        )
    }
}

/** Rolling conversation transcript with auto-scroll. */
@Composable
private fun TranscriptPanel(
    lines: List<ChatLine>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Box(modifier = modifier) {
        if (lines.isEmpty()) {
            Text(
                text = "Say “hey Aria” or type below. Try: “flashlight on”, “what did I miss?”, " +
                    "“what's on my screen?”",
                style = MaterialTheme.typography.bodyMedium,
                color = MistDim
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lines) { line -> TranscriptLine(line) }
            }
        }
    }
}

@Composable
private fun TranscriptLine(line: ChatLine) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Text(
            text = if (line.fromUser) "You" else "Aria",
            style = MaterialTheme.typography.labelMedium,
            color = if (line.fromUser) MistDim else NeonCyan
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MistWhite
        )
    }
}