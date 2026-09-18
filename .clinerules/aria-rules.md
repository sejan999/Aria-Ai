# Aria Ai — Cline project rules

## Architecture
- **100% Pure Kotlin.** No C++/NDK, no Python, no JNI, no Chaquopy, no CMakeLists.
- Single `:app` module. Kotlin 2.0.20 + Compose (BOM 2024.09.03) + Hilt (kapt) + Room + DataStore.
- minSdk 26, target/compileSdk 34, JDK 17 toolchain (built with Gradle 8.7).

## Request flow (never bypass it)
`UI → ViewModel → MasterBrain → (route) sub-agent | ProviderRegistry → AIProvider adapter → KeyProvider → ApiKeyVault`

## Hard rules
1. **Never hardcode API keys.** Keys live only in `ApiKeyVault`
   (EncryptedSharedPreferences + Android Keystore). Adapters fetch via
   `KeyProvider.requireKey()` **at call time** and throw `MissingApiKeyException` when absent.
2. **Never log key material.** Route any provider error text through
   `KeyRedactor.scrub()` before logging, displaying or throwing. The OkHttp
   interceptor logs only method/host/path/status.
3. **All AI calls go through `ProviderRegistry`.** UI code never touches adapters directly.
4. **Every provider adapter must** implement `AIProvider`, stream via `AiHttp.streamSse`,
   end its stream with a `finished` chunk, and keep the key out of URLs where the
   API allows headers (Gemini: `x-goog-api-key`).
5. **Compose only** for UI; the overlay HUD uses classic views because it lives in a
   Service context (no Compose lifecycle owner there).
6. **Voice pipeline** = `PcmAudioRecorder` (AudioRecord 16 kHz mono PCM-16) +
   platform `SpeechRecognizer` + `PcmAudioPlayer` (AudioTrack). Wake word is
   energy-gated with an optional TFLite audio-classifier model.
7. **On-device LLM** = MediaPipe `LlmInference` (Kotlin task API). Model file path is
   user-supplied in Settings; never bundle a model in the repo.
8. System integrations (notifications, telephony, media, accessibility) must
   permission-check at runtime and return friendly sentences — never crash.
9. Room stores conversations + automations only. **Secrets never go into Room.**
10. Do not read, copy or reference the archived legacy project
    (`~/Aria_old_hybrid_*`); this codebase is written from scratch.

## Build
```bash
JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.12+1-ms ./gradlew assembleDebug --no-daemon
```
Fix-forward loop: build → read `tail -120` of the log → patch → rebuild until
`BUILD SUCCESSFUL`.