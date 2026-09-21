# AgentRing-Android: Ambient Desktop Monitor

<p align="center">
  <strong>Give your old Android phone a second life as an ambient desktop AI quota monitor for <a href="https://github.com/haorui-lab/agentRing">agentRing</a> (macOS).</strong>
</p>

<p align="center">
  <a href="README.md"><strong>简体中文</strong></a> •
  <a href="README_EN.md"><strong>English</strong></a>
</p>

<p align="center">
  <a href="https://developer.android.com/about/versions/lollipop"><img src="https://img.shields.io/badge/Platform-Android%205.0%2B%20(API%2021%2B)-3DDC84?logo=android&logoColor=white" alt="Platform: Android 5.0+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Language-Kotlin%201.9-7F52FF?logo=kotlin&logoColor=white" alt="Language: Kotlin"></a>
  <a href="BLUETOOTH_PROTOCOL.md"><img src="https://img.shields.io/badge/Bluetooth-SPP%20%2F%20RFCOMM-0082FC?logo=bluetooth&logoColor=white" alt="Bluetooth SPP"></a>
  <a href="https://github.com/haorui-lab/agentRing"><img src="https://img.shields.io/badge/Companion-agentRing%20(macOS)-black?logo=apple&logoColor=white" alt="Companion App"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT"></a>
  <a href="https://github.com/davidhoo/agentRing-Android/pulls"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg" alt="PRs Welcome"></a>
</p>

---

![AgentRing-Android in action](docs/screenshots/live_display.png)

## 📖 Introduction & Philosophy

**AgentRing-Android** is a dedicated ambient desktop display application tailored specifically for idle and vintage Android devices (backward compatible all the way to **Android 5.0.2 / API 21**).

When developing with AI assistants such as **Codex**, **Cursor**, and **Antigravity**, developers frequently consume rate-limited quotas. Switching windows or constantly peeking at menu bar popovers interrupts flow and clutters workspace screens.

Paired seamlessly with [agentRing](https://github.com/haorui-lab/agentRing) on macOS, **AgentRing-Android** transforms an unused smartphone on your desktop stand into a dedicated, hardware-level AI quota monitor that presents remaining balances, quota rings, and reset countdowns in real time.

### Why Classic Bluetooth (SPP / RFCOMM)?

- 🌐 **Zero Network Restrictions**: Eliminates reliance on local Wi-Fi subnets, completely immune to enterprise AP isolation, corporate VPN tunnels, and captive portals.
- 🔌 **Instant Near-Field Connection**: Reconnects automatically as soon as your device is nearby—no manual IP addresses, ports, or Wi-Fi configurations needed. Works even in Airplane mode.
- 🔋 **Minimal Power & Heat**: Sends compact line-delimited JSON payloads only upon quota updates. Operates smoothly 24/7 without thermal throttling or overheating.

---

## ✨ Key Features

- 📱 **Broad Vintage Hardware Support**: Runs smoothly on Android 5.0.2+ (API 21, Lollipop), extending the lifespan of devices from 2014 onwards.
- 🔆 **Ambient Always-On & Immersive**: Powered by `FLAG_KEEP_SCREEN_ON` and `PowerManager.PARTIAL_WAKE_LOCK`, with forced landscape orientation and sticky immersive full-screen display.
- 🔵 **Zero-Config Bluetooth Discovery**:
  - Automatically renames the device to `AgentRing-<Model>` and stays discoverable.
  - Utilizes low-latency BLE advertising (API 21+) to allow modern macOS Bluetooth settings to discover and pair in 1–2 seconds.
- 🛡️ **Bluedroid Stack Self-Healing Watchdog**:
  - Automatically stops BLE advertising upon RFCOMM connection to prevent native GATT resource leaks on legacy Android stacks.
  - Includes connection watchdogs and a one-tap Bluetooth stack recovery trigger (accessible via the status capsule).
- 🎨 **Apple Fitness Ring Aesthetics**:
  - Rendered entirely on hardware-accelerated Canvas.
  - Implements a custom physics spring interpolator (`AppleSpringInterpolator`) matching SwiftUI's `.spring(response: 0.42, dampingFraction: 0.78)` dynamic characteristics.
  - Supports single rings (e.g. 7-day window) and concentric double rings (Primary + Secondary windows).
  - **Threshold Protection**: 0.2% non-zero visual floor (ensuring low quotas don't appear empty) and 99% cap for near-full quotas.
- 📐 **Adaptive Multi-Provider Grid**:
  - Dynamically adjusts ring scaling and subtle divider lines for 1 to 4+ providers.
  - Rigid multi-tier vertical baseline alignment across all columns (provider titles, ring centers, and detail capsules).
- 🔄 **Incremental Diff Rendering**: Skips redundant redraws when payload values haven't changed, preventing flickering and repeated animations.

---

## 🏗️ Architecture & Communication Protocol

```
┌───────────────────────────────────────┐            Classic Bluetooth RFCOMM (SPP)       ┌────────────────────────────────────────┐
│             macOS Client              │────────────────────────────────────────────────>│            Android Monitor             │
│           (agentRing.app)             │         Line-delimited JSON ('\n')              │          (agentRing-Android)           │
│                                       │                                                 │                                        │
│ • Discovers & auto-connects to paired │                                                 │ • Listens on SPP RFCOMM Socket         │
│   AgentRing devices                   │                                                 │ • Broadcasts BLE discovery beacon      │
│ • Polls Codex, Cursor, Antigravity    │                                                 │ • Always-on screen & spring rings      │
│ • Pushes JSON quota stream on update  │                                                 │ • Incremental diff rendering           │
└───────────────────────────────────────┘                                                 └────────────────────────────────────────┘
```

- **Protocol**: Classic Bluetooth SPP (Serial Port Profile) over RFCOMM
- **SPP Standard UUID**: `00001101-0000-1000-8000-00805F9B34FB`
- **Framing**: UTF-8 single-line JSON terminated with `\n` (JSON Lines)
- **Specification Document**: See [BLUETOOTH_PROTOCOL.md](BLUETOOTH_PROTOCOL.md) for full schema details.

---

## 🚀 Quick Start

### 1. Install APK on Android Device

Download the latest pre-built APK from the [Releases](https://github.com/davidhoo/agentRing-Android/releases) tab, or build from source:

```bash
# Install via ADB to connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Pair and Connect

1. Launch **AgentRing** on your Android phone.
2. The top status capsule will display your assigned device name (e.g., `AgentRing-NX513J`) and status `Waiting for computer connection...`.
3. Open **System Settings -> Bluetooth** on your Mac.
4. Under "Nearby Devices", select `AgentRing-XX` and click **Pair**.
5. Once paired, launch [agentRing](https://github.com/haorui-lab/agentRing) on macOS. It will automatically detect your paired screen and push live quota metrics!

---

## 🛠️ Testing & Simulation Tools

You can verify UI rendering and Bluetooth functionality without needing live AI API credentials:

### 1. Python Serial / Bluetooth Push Simulator (`tools/test_sender.py`)

```bash
# 1. Print formatted mock JSON payload for 1, 2, or 3 providers
python3 tools/test_sender.py --providers 3 --dump-json

# 2. Continuously stream to Mac Bluetooth serial device (found under /dev/cu.AgentRing*)
python3 tools/test_sender.py --providers 3 --device /dev/cu.AgentRing-xxx --loop --interval 5
```

### 2. ADB Local Broadcast Injection (No Bluetooth Needed)

Test UI rendering and layouts directly on emulator or phone via ADB broadcast:

```bash
adb shell am broadcast -a app.agentring.android.MOCK_DATA --es payload '{"timestamp":1726487626,"providers":[{"id":"codex","name":"Codex","primary":{"label":"7 Days","remainingPercent":80.0,"resetsAt":"3d 12h"},"rows":[{"label":"7 Days","percent":"80%","reset":"3d 12h"}]}]}'
```

---

## 💻 Building from Source

### Prerequisites
- **JDK 17+** (or the bundled JBR inside Android Studio)
- **Android SDK Platform 34** (Build-tools 34.0.0+, `minSdk = 21`)

### Build Steps

```bash
# Clone the repository
git clone https://github.com/davidhoo/agentRing-Android.git
cd agentRing-Android

# Point to Android Studio bundled JBR if JDK is not in system PATH (macOS example)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

Build outputs:
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 📂 Project Structure

```text
agentRing-Android/
├── app/
│   └── src/main/
│       ├── java/app/agentring/android/
│       │   ├── model/
│       │   │   └── DashboardModels.kt       # Protocol data model & Gson parser
│       │   ├── service/
│       │   │   └── BluetoothServerManager.kt # SPP server, BLE advertiser & watchdog
│       │   └── ui/
│       │       ├── ActivityRingView.kt       # Custom Canvas ring with spring physics
│       │       └── MainActivity.kt           # Immersive mode, layout & diff rendering
│       └── res/                              # Layouts, colors, and drawables
├── docs/
│   └── screenshots/                          # Preview screenshots
├── tools/
│   └── test_sender.py                        # Python mock data & serial sender
├── BLUETOOTH_PROTOCOL.md                     # Bluetooth sync protocol specification
├── LICENSE                                   # MIT License
├── README.md                                 # Chinese Documentation
└── README_EN.md                              # English Documentation
```

---

## ❓ FAQ & Troubleshooting

<details>
<summary><strong>Q: My Mac cannot find the Android phone in the Bluetooth list.</strong></summary>

1. Ensure the AgentRing-Android app is running and the screen remains on.
2. Verify Bluetooth permissions have been granted (on Android 12+, `BLUETOOTH_SCAN` and `BLUETOOTH_ADVERTISE` runtime permissions are required).
3. Tap the status capsule at the top of the phone screen to trigger an immediate restart and self-healing cycle of the Bluedroid Bluetooth stack.
</details>

<details>
<summary><strong>Q: Does keeping the screen on cause overheating or battery degradation?</strong></summary>

- AgentRing-Android eliminates network polling and high-power background threads; idle Bluetooth listening consumes negligible energy.
- Set display brightness to a moderate, comfortable ambient level. If your phone supports battery charge protection (e.g. stopping at 80%), enable it, or use a smart plug timer.
</details>

<details>
<summary><strong>Q: Does it work with aggressive battery savers on Xiaomi / Meizu / Smartisan ROMs?</strong></summary>

The app requests both `FLAG_KEEP_SCREEN_ON` and `PowerManager.PARTIAL_WAKE_LOCK`. On aggressive customized OEM ROMs, add AgentRing to the system's "Battery Saver Whitelist" or set it to "No Restrictions".
</details>

---

## 🤝 Related Projects & Credits

- [haorui-lab/agentRing](https://github.com/haorui-lab/agentRing) — Native macOS menu bar AI quota monitor.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE). Feel free to use, fork, and submit Pull Requests!
