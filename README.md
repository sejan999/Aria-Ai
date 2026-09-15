<div align="center">

# 🎙️ Aria Ai

**A voice-first, autonomous AI assistant that turns your Android device into an intelligent copilot.**

[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-14%2F15-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

**Author:** [Sejan](https://github.com/ai-sejan-sfc) · **Status:** 🚧 In Development

</div>

---

## 🌟 Overview

**Aria Ai** is not just another voice assistant. It is a **fully autonomous, context-aware AI operating layer** for Android that controls your device, automates multi-step workflows, listens to notifications, screens calls, and executes system-level actions — all through natural language.

Unlike conventional assistants, Aria Ai supports **multiple AI providers** (Gemini, OpenAI, Groq, Anthropic, Mistral, OpenRouter, and on-device Gemma) with **runtime-switchable backends** and **user-entered API keys** stored securely on-device.

> **Built with 100% Pure Kotlin.** No C++, no Python, no JNI. Clean, maintainable, and buildable from Termux or GitHub Actions — no PC required.

---

## ✨ Features

### 🎙️ Voice & Audio
- Real-time bidirectional audio via `AudioRecord` + `AudioTrack` (~20–30ms latency)
- Wake-word detection ("Hey Aria") using TensorFlow Lite
- Streaming TTS with custom voice options
- Multimodal input: voice, text, images, screen content

### 🤖 Multi-Provider AI Engine
| Provider | Use Case |
|---|---|
| **Google Gemini** | Real-time audio + vision (Live API) |
| **OpenAI** | GPT-4o, GPT-4o-mini, Realtime API |
| **Groq** | Ultra-low-latency inference (Llama 3.3 70B) |
| **Anthropic** | Claude Sonnet-4, Opus-4 |
| **Mistral** | Mistral Large, Small |
| **OpenRouter** | Unified access to 100+ models |
| **On-Device Gemma 2B** | Offline fallback (MediaPipe) |

- **Runtime provider switching** from Settings
- **Automatic fallback chain**: primary → secondary → tertiary → on-device Gemma
- **User-entered API keys** — never hardcoded, always encrypted

### 🪟 Floating Holographic Overlay
- Always-on-top floating UI via `WindowManager`
- `AriaQuantumHUD` — futuristic animated dashboard (AGSL shaders)
- Real-time waveform visualizer
- Glassmorphic design with Material 3

### 📱 System Control
- App launch & control
- Wi-Fi, Bluetooth, Flashlight, DND, Airplane Mode toggles
- Hardware automation (camera, sensors, NFC, haptics)
- Multi-step intent chaining

### 🔔 Notification Intelligence
- Reads notifications via `NotificationListenerService`
- AI-powered summarization & priority filtering
- Auto-draft responses for WhatsApp / Telegram

### 📞 Call Control
- AI call screening (`CallScreeningService`)
- Real-time transcription
- Conversational auto-responses
- Spam detection

### 🎵 Media Session Control
- Cross-app media control
- Smart playlist curation
- Audio ducking when Aria speaks

### 👁️ Screen Vision
- Screen capture via `MediaProjection`
- Multimodal understanding with vision models
- Guided actions & auto-fill assistance

### 🧠 Agent Orchestration
- **MasterBrain** — goal decomposition
- Sub-agents: `NotificationAgent`, `CallScreenAgent`, `SystemAppAgent`, `HardwareAgent`, `VisionAgent`, `AutomationAgent`

### ⚡ Automation Engine
- Natural-language workflows: _"Good morning" → Wi-Fi on → read schedule → play news_
- Triggers: voice, time, location, event, battery
- Multi-step workflows (10+ actions)

### 🔐 Security & Privacy
- API keys in **Android Keystore** via `EncryptedSharedPreferences`
- **Biometric vault lock** (optional)
- `FLAG_SECURE` on key-entry screens
- **TLS 1.3** + certificate pinning for all provider endpoints
- **Privacy mode** — no data leaves the device; only on-device Gemma
- Prompt injection defense

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Aria Ai (Kotlin)                  │
├─────────────────────────────────────────────────────┤
│  UI Layer      │  Jetpack Compose + Material 3      │
│  AI Layer      │  ProviderRegistry + Adapters       │
│  Audio Layer   │  AudioRecord + AudioTrack + Pool   │
│  Network Layer │  OkHttp WebSocket + Retrofit       │
│  System Layer  │  FGS, Overlay, Notification, Call  │
│  Data Layer    │  Room + SQLCipher + Keystore       │
│  Agents Layer  │  MasterBrain + Sub-agents          │
└─────────────────────────────────────────────────────┘
```

**Design principles:**
- Provider abstraction — every AI backend implements `AIProvider`
- No hardcoded secrets — all keys flow through `ApiKeyVault` → `KeyProvider`
- Coroutine-first — `Flow` + `StateFlow` for reactive state
- Zero-latency audio — pre-allocated ring buffers, no GC in audio loop

---

## 🛠️ Tech Stack

| Category | Technology |
|---|---|
| **Language** | Kotlin 2.0.20 |
| **UI** | Jetpack Compose (Material 3), AGSL shaders |
| **Concurrency** | Coroutines, Flow, StateFlow |
| **DI** | Dagger Hilt 2.51.1 |
| **Networking** | OkHttp 4.12, Retrofit 2.11, Moshi |
| **Persistence** | Room 2.6, DataStore, EncryptedSharedPreferences |
| **On-device ML** | TensorFlow Lite, MediaPipe LLM Inference |
| **Build** | Gradle 8.7, AGP 8.5.2, JDK 17 |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 34 (Android 14) |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or later (**or** Termux + VSCode Server)
- JDK 17
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`

### Clone the Repository
```bash
git clone https://github.com/ai-sejan-sfc/AriaAi.git
cd AriaAi
```

### Build the Debug APK
```bash
./gradlew assembleDebug
```

The APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Install on Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or transfer the APK to your phone and open it.

---

## 🔑 Setting Up API Keys

Aria Ai **does not ship with any API keys**. You must enter your own keys after installation.

1. Launch **Aria Ai**
2. Go to **Settings → Providers**
3. Paste your API key into the masked field for any provider (Gemini, OpenAI, Groq, etc.)
4. Tap **Test Connection** to verify
5. Tap **Save** — the key is encrypted in Android Keystore
6. Optionally toggle **Set as Active** for the provider you want to use

### Getting API Keys

| Provider | Link |
|---|---|
| Google Gemini | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |
| OpenAI | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) |
| Groq | [console.groq.com/keys](https://console.groq.com/keys) |
| Anthropic | [console.anthropic.com](https://console.anthropic.com/) |
| Mistral | [console.mistral.ai](https://console.mistral.ai/) |
| OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) |

> **⚠️ Security note:** Your API keys are stored encrypted on-device only. They are never sent anywhere except the provider's official endpoint.

---

## 📂 Project Structure

```
AriaAi/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/aria/ai/
│       ├── AriaApplication.kt
│       ├── MainActivity.kt
│       ├── ui/                # Compose UI, theme, screens, components
│       ├── core/
│       │   ├── ai/            # Provider abstraction + adapters
│       │   ├── audio/         # AudioRecord/AudioTrack pipeline
│       │   ├── network/       # WebSocket + REST clients
│       │   ├── overlay/       # Foreground service + floating UI
│       │   ├── vision/        # Screen capture + analysis
│       │   ├── ml/            # TFLite + MediaPipe
│       │   ├── tts/           # Text-to-speech
│       │   └── system/        # Notification, Call, Media, Hardware
│       ├── agents/            # MasterBrain + sub-agents
│       ├── data/              # Room, vault, repositories
│       └── di/                # Hilt modules
└── build.gradle.kts
```

---

## 🧪 Building Without a PC

Aria Ai can be built entirely from an Android device:

**Option 1 — GitHub Actions (recommended):**
Push to `main` branch; the workflow at `.github/workflows/build.yml` builds the APK and uploads it as an artifact.

**Option 2 — Termux:**
```bash
pkg install openjdk-17 gradle aapt2 android-tools
cd ~/AriaAi
./gradlew assembleDebug --no-daemon
```

---

## 🗺️ Roadmap

- [x] Pure Kotlin architecture
- [x] Multi-provider abstraction
- [x] Encrypted API key vault
- [x] Settings UI for provider configuration
- [ ] Gemini Live WebSocket integration
- [ ] OpenAI Realtime API integration
- [ ] Floating overlay + AriaQuantumHUD
- [ ] Notification listener agent
- [ ] Call screening agent
- [ ] On-device Gemma via MediaPipe
- [ ] Automation DSL
- [ ] Wear OS companion app
- [ ] Play Store release

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

Please follow the Kotlin coding conventions and keep the project 100% Kotlin.

---

## 📜 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

## 👤 Author

**Sejan**
- GitHub: [@ai-sejan-sfc](https://github.com/ai-sejan-sfc)
- Project: [Aria Ai](https://github.com/ai-sejan-sfc/Aria)

---

## 🙏 Acknowledgements

- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Dagger Hilt](https://dagger.dev/hilt/)
- [OkHttp](https://square.github.io/okhttp/)
- [TensorFlow Lite](https://www.tensorflow.org/lite)
- [MediaPipe](https://mediapipe.dev/)
- All the AI providers that make Aria Ai possible

---

<div align="center">

**⭐ If you find Aria Ai useful, please star the repository!**

Made with ❤️ by Sejan

</div>
