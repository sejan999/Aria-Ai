package com.aria.ai.ui.screens.settings

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aria.ai.core.ai.ProviderIds
import com.aria.ai.core.ai.ProviderRegistry
import com.aria.ai.core.ai.ProviderStatus
import com.aria.ai.data.repository.ProviderSettingsRepository
import com.aria.ai.data.vault.ApiKeyVault
import com.aria.ai.data.vault.VaultLockManager
import com.aria.ai.ui.components.GlassCard
import com.aria.ai.ui.components.ProviderCard
import com.aria.ai.ui.theme.AriaError
import com.aria.ai.ui.theme.AriaSuccess
import com.aria.ai.ui.theme.DeepSpace
import com.aria.ai.ui.theme.MistDim
import com.aria.ai.ui.theme.MistWhite
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.NeonPurple
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state of the encrypted key vault screen (never carries key material). */
data class ProvidersUiState(
    val providers: List<ProviderStatus> = emptyList(),
    val requiresUnlock: Boolean = false,
    val lockSupported: Boolean = false,
    val busyProviders: Set<String> = emptySet(),
    val testResults: Map<String, String> = emptyMap(),
    val statusMessage: String? = null,
    val anyKeyStored: Boolean = false
)

/**
 * Vault state holder. Keys never enter the UI state — the screen only ever sees a
 * redacted fingerprint from [ApiKeyVault]; on devices with a lockscreen the vault
 * is gated behind the platform credential prompt via [VaultLockManager].
 */
@HiltViewModel
class ProvidersSettingsViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val vault: ApiKeyVault,
    private val lock: VaultLockManager,
    private val settings: ProviderSettingsRepository
) : ViewModel() {

    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val results = MutableStateFlow<Map<String, String>>(emptyMap())
    private val message = MutableStateFlow<String?>(null)
    private val unlocked = MutableStateFlow(lock.isVaultUnlocked())
    private val keyDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val modelDrafts = MutableStateFlow<Map<String, String>>(emptyMap())

    val keyDraftsState: StateFlow<Map<String, String>> = keyDrafts.asStateFlow()
    val modelDraftsState: StateFlow<Map<String, String>> = modelDrafts.asStateFlow()

    private val selection = combine(
        settings.activeProviderId,
        settings.modelOverrides()
    ) { activeId, overrides -> activeId to overrides }


val state: StateFlow<ProvidersUiState> = combine(
        selection, busy, results, message, unlocked
    ) { picked, busyIds, testResults, statusMessage, isUnlocked ->
        val activeId = picked.first
        val overrides = picked.second
        val ordered = registry.ordered()
        ProvidersUiState(
            providers = ordered.map { provider ->
                val override = overrides[provider.id]
                ProviderStatus(
                    id = provider.id,
                    displayName = provider.displayName,
                    defaultModel = provider.defaultModel,
                    activeModel = override ?: provider.defaultModel,
                    hasKey = vault.hasKey(provider.id),
                    requiresKey = provider.requiresKey,
                    isActive = provider.id == activeId,
                    isLocal = provider.isLocal,
                    supportsVision = provider.supportsVision,
                    fingerprint = vault.fingerprint(provider.id),
                    modelOverride = override
                )
            },
            requiresUnlock = !isUnlocked,
            lockSupported = lock.isDeviceSecure(),
            busyProviders = busyIds,
            testResults = testResults,
            statusMessage = statusMessage,
            anyKeyStored = ordered.any { vault.hasKey(it.id) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    init {
        viewModelScope.launch {
            val path = settings.currentGemmaModelPath().orEmpty()
            if (path.isNotBlank()) {
                modelDrafts.update { it + (ProviderIds.ON_DEVICE_GEMMA to path) }
            }
        }
    }

    fun onKeyDraftChange(providerId: String, value: String) {
        keyDrafts.update { it + (providerId to value) }
    }

    fun onModelDraftChange(providerId: String, value: String) {
        modelDrafts.update { it + (providerId to value) }
    }

    /** Stores a key in the encrypted vault (never retained in UI state). */
    fun saveKey(providerId: String) {
        val value = keyDrafts.value[providerId].orEmpty().trim()
        if (value.isEmpty()) {
            message.value = "Paste a key before saving."
            return
        }
        vault.setKey(providerId, value)
        keyDrafts.update { it - providerId }
        message.value = "Key stored for ${ProviderIds.displayName(providerId)} " +
            "(${vault.fingerprint(providerId)})."
    }

    fun clearKey(providerId: String) {
        vault.removeKey(providerId)
        message.value = "Key removed for ${ProviderIds.displayName(providerId)}."
    }

    fun saveModelPath(providerId: String) {
        val path = modelDrafts.value[providerId].orEmpty().trim()
        viewModelScope.launch {
            settings.setGemmaModelPath(path)
            message.value = if (path.isBlank()) {
                "On-device model path cleared."
            } else {
                "On-device model path saved."
            }
        }
    }

    fun saveModelOverride(providerId: String) {
        val model = modelDrafts.value[providerId].orEmpty().trim()
        viewModelScope.launch {
            settings.setModelOverride(providerId, model)
            message.value = if (model.isBlank()) {
                "Model override cleared."
            } else {
                "${ProviderIds.displayName(providerId)} will use $model."
            }
        }
    }

    fun activate(providerId: String) {
        viewModelScope.launch {
            settings.setActiveProvider(providerId)
            message.value = "${ProviderIds.displayName(providerId)} is now the active provider."
        }
    }

    /** Round-trips a tiny prompt and records a redacted result line. */
    fun testConnection(providerId: String) {
        if (busy.value.contains(providerId)) return
        busy.update { it + providerId }
        viewModelScope.launch {
            val text = registry.testConnection(providerId).fold(
                onSuccess = { it },
                onFailure = { failure ->
                    "FAILED · " + (failure.message?.take(160) ?: failure.javaClass.simpleName)
                }
            )
            results.update { it + (providerId to text) }
            busy.update { it - providerId }
        }
    }

    fun wipeVault() {
        vault.wipeAll()
        results.value = emptyMap()
        keyDrafts.value = emptyMap()
        message.value = "Vault wiped — every stored key was deleted."
    }

    fun unlockSucceeded() {
        lock.markSessionUnlocked()
        unlocked.value = true
        message.value = "Vault unlocked for this session."
    }

    /** Platform credential prompt for the vault (null when unsupported). */
    fun requestUnlock(): android.content.Intent? = lock.credentialIntent()

    fun lockNow() {
        lock.lockSession()
        unlocked.value = false
    }

    fun dismissMessage() {
        message.value = null
    }

}

/**
 * Settings → AI Providers: the encrypted key vault.
 *
 * The window is marked FLAG_SECURE so keys can never be captured in a screenshot,
 * and every provider row can test its own credentials.
 */
@Composable
fun ProvidersSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProvidersSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keys by viewModel.keyDraftsState.collectAsStateWithLifecycle()
    val models by viewModel.modelDraftsState.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }

    val unlockLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == Activity.RESULT_OK) viewModel.unlockSucceeded() }

    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack) { Text("Back", color = MistWhite) }
            Spacer(Modifier.width(12.dp))
            Text("AI Providers", style = MaterialTheme.typography.titleLarge, color = NeonCyan)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Keys are encrypted with the Android Keystore (AES-256-GCM). They are never logged, " +
                "never stored in the conversation database and never leave this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MistDim
        )

        Spacer(Modifier.height(10.dp))

        state.statusMessage?.let { msg ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("FAILED")) AriaError else AriaSuccess
                )
                TextButton(onClick = { viewModel.dismissMessage() }) {
                    Text("OK", color = NeonCyan)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (state.requiresUnlock) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Vault locked", style = MaterialTheme.typography.titleMedium, color = MistWhite)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Authenticate with your device credentials to view or edit stored API keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistDim
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { viewModel.requestUnlock()?.let { unlockLauncher.launch(it) } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DeepSpace
                    )
                ) { Text("Unlock vault") }
            }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.providers, key = { it.id }) { status ->
                ProviderCard(
                    status = status,
                    keyDraft = keys[status.id].orEmpty(),
                    modelDraft = models[status.id].orEmpty(),
                    testResult = state.testResults[status.id],
                    busy = state.busyProviders.contains(status.id),
                    onKeyChange = { viewModel.onKeyDraftChange(status.id, it) },
                    onModelChange = { viewModel.onModelDraftChange(status.id, it) },
                    onSaveKey = { viewModel.saveKey(status.id) },
                    onSaveModel = {
                        if (status.isLocal) {
                            viewModel.saveModelPath(status.id)
                        } else {
                            viewModel.saveModelOverride(status.id)
                        }
                    },
                    onClearKey = { viewModel.clearKey(status.id) },
                    onTest = { viewModel.testConnection(status.id) },
                    onActivate = { viewModel.activate(status.id) },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { confirmWipe = true }, enabled = state.anyKeyStored) {
                Text("Wipe vault", color = AriaError)
            }
            if (state.lockSupported) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.lockNow() }) {
                    Text("Lock now", color = NeonPurple)
                }
            }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe vault?") },
            text = { Text("This deletes every stored API key from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.wipeVault()
                    confirmWipe = false
                }) { Text("Delete all", color = AriaError) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel", color = MistDim) }
            }
        )
    }
}