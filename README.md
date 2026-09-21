# AgentRing-Android 桌面副屏监视器

<p align="center">
  <strong>让闲置老旧手机重获新生 —— 专为 macOS <a href="https://github.com/haorui-lab/agentRing">agentRing</a> 打造的硬件级桌面 AI 用量副屏</strong>
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

![AgentRing-Android 运行截图](docs/screenshots/live_display.png)

## 📖 简介与设计初衷

**AgentRing-Android** 是一款专为闲置旧手机（最低兼容 **Android 5.0.2 / API 21**）量身定制的桌面副屏客户端。

在日常高强度编程工作中，我们频繁消耗各类 AI 编程助理（如 **Codex**、**Cursor**、**Antigravity** 等）的用量额度。反复切换窗口或在 macOS 菜单栏反复查看不仅容易打断编码心流，也占用宝贵的屏幕空间。

通过与电脑上的 [agentRing](https://github.com/haorui-lab/agentRing) 菜单栏应用协同，**AgentRing-Android** 能将抽屉里吃灰的老旧安卓手机化身为常驻桌面的高颜值硬件信息指示器，实时、直观地呈现各项 AI 订阅的剩余百分比圆环与重置倒计时。

### 为什么选择经典蓝牙 (Bluetooth SPP)？

- 🌐 **零网络与网段依赖**：无需局域网 Wi-Fi 支持，彻底免疫企业内网 AP 隔离、多网段漫游与 VPN 代理干扰。
- 🔌 **即开即连**：只要设备靠近电脑即可自动握手，没有配网步骤，断网也能正常显示。
- 🔋 **超低功耗与微弱发热**：仅在额度变动时传输数十字节的轻量 JSON 流，旧设备常亮不发烫，性能无损。

---

## ✨ 核心特性

- 📱 **老旧硬件极致兼容**：最低支持 Android 5.0.2 (API 21, Lollipop)，流畅运行在 2014 年代及更新的各类老旧手机/平板上。
- 🔆 **桌面级常亮与沉浸**：`FLAG_KEEP_SCREEN_ON` + `PARTIAL_WAKE_LOCK` 双层保障，强制横屏并进入粘性沉浸全屏模式（Sticky Immersive），无锁屏打扰。
- 🔵 **零门槛设备发现**：
  - 启动后自动将自身蓝牙重命名为 `AgentRing-<Model>`，常驻处于可发现与可连接状态。
  - 辅以低延迟 BLE 广播（API 21+），使现代 macOS 蓝牙面板能在 1~2 秒内迅速识别设备。
- 🛡️ **Bluedroid 协议栈守护与自愈**：
  - 连接建立后智能停用 BLE 广播，杜绝旧安卓原生 GATT 资源泄漏与底层死锁。
  - 异常断线看门狗自动重试，提供状态胶囊单触一键重启蓝牙栈机制。
- 🎨 **拟真 Apple 健身圆环体验**：
  - 纯硬件加速原生 Canvas 自绘，精简无杂质。
  - 内置符合 SwiftUI `.spring(response: 0.42, dampingFraction: 0.78)` 物理特性的阻尼回弹弹簧插值器（AppleSpringInterpolator）。
  - 支持单环（如 7 天窗口）与同心双环（主窗口 + 次窗口）。
  - **视觉临界保护**：0.2% 非零保底显示（避免微量额度视觉变空）与 99% 未满封顶保护。
- 📐 **自适应弹性布局**：
  - 动态适应 1~4+ 个 Provider，自动计算圆环比例与细分割线，完美适配横屏桌面支架与竖屏摆放。
  - 严格多层绝对基准线对齐（标题行、圆环底线、胶囊 Row 1/Row 2 顶部平齐）。
- 🔄 **差量增量渲染**：仅在数据产生实质变化时触发过渡动画，避免频繁刷新造成闪烁或重复弹簧动效。

---

## 🏗️ 架构与通讯规范

```
┌───────────────────────────────────────┐            经典蓝牙 RFCOMM (SPP)          ┌────────────────────────────────────────┐
│             macOS 客户端              │────────────────────────────────────────>│             Android 副屏               │
│           (agentRing.app)             │   Line-delimited JSON Stream ('\n')     │         (agentRing-Android)            │
│                                       │                                         │                                        │
│ • 主动探测并重连已配对的 AgentRing 设备 │                                         │ • 监听标准 SPP 串口服务                │
│ • 汇总 Codex / Cursor / Antigravity   │                                         │ • 启动 BLE 广播加速设备发现            │
│ • 定时与事件驱动推送最新配额 JSON 报文  │                                         │ • 屏幕常亮 + 拟真圆环视觉渲染          │
└───────────────────────────────────────┘                                         └────────────────────────────────────────┘
```

- **通讯通道**：经典蓝牙 SPP (Serial Port Profile) over RFCOMM
- **标准 SPP UUID**：`00001101-0000-1000-8000-00805F9B34FB`
- **帧定界**：以换行符 `\n` 结尾的单行 UTF-8 JSON 字符串
- **官方协议详情**：请参阅 [BLUETOOTH_PROTOCOL.md](BLUETOOTH_PROTOCOL.md)。

---

## 🚀 快速上手

### 1. 安装 APK 到 Android 手机

您可以直接从本项目的 [Releases 页面](https://github.com/davidhoo/agentRing-Android/releases) 下载预编译的最新 APK，或自行编译：

```bash
# 借助 ADB 一键安装到已连接的手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 配对与连接

1. 在 Android 手机上启动 **AgentRing** 应用。
2. 手机顶部状态栏将显示当前分配的蓝牙标识（如 `AgentRing-NX513J`）以及状态：`等待电脑通过蓝牙连接…`。
3. 在 Mac 上打开 **系统设置 -> 蓝牙**。
4. 在“附近设备”中找到该 `AgentRing-XX` 设备并点击**配对 (Pair)**。
5. 配对成功后，启动 macOS 端 [agentRing](https://github.com/haorui-lab/agentRing)，它将自动识别已配对的副屏并建立链路，主动推送用量数据！

---

## 🛠️ 调试与测试工具

本项目内置多种无需依赖实际 AI 凭证即可进行全链路 UI/蓝牙调测的工具：

### 1. Python 蓝牙/串口模拟器 (`tools/test_sender.py`)

```bash
# 1. 直接输出模拟的 1 / 2 / 3 Provider 协议 JSON 串
python3 tools/test_sender.py --providers 3 --dump-json

# 2. 直连已配对的 Mac 蓝牙虚拟串口循环推送（可在 /dev/cu.AgentRing* 查看）
python3 tools/test_sender.py --providers 3 --device /dev/cu.AgentRing-xxx --loop --interval 5
```

### 2. ADB 本地广播数据注入 (无需蓝牙硬件)

即使没有连接蓝牙，也可以通过 ADB 发送广播直接向手机屏幕注入任意自定义数据包以验证排版渲染：

```bash
adb shell am broadcast -a app.agentring.android.MOCK_DATA --es payload '{"timestamp":1726487626,"providers":[{"id":"codex","name":"Codex","primary":{"label":"7天","remainingPercent":80.0,"resetsAt":"3d 12h"},"rows":[{"label":"7天","percent":"80%","reset":"3d 12h"}]}]}'
```

---

## 💻 源码构建

### 环境要求
- **JDK 17+**（支持 Android Studio 捆绑的 JBR）
- **Android SDK Platform 34**（构建工具 34.0.0+，`minSdk = 21`）

### 编译步骤

```bash
# 克隆代码仓库
git clone https://github.com/davidhoo/agentRing-Android.git
cd agentRing-Android

# 如未全局配置 JDK，可指定 Android Studio 自带 JBR (macOS 示例)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK
./gradlew assembleRelease
```

编译产物路径：
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 📂 项目结构

```text
agentRing-Android/
├── app/
│   └── src/main/
│       ├── java/app/agentring/android/
│       │   ├── model/
│       │   │   └── DashboardModels.kt       # 协议数据实体解析 (Gson)
│       │   ├── service/
│       │   │   └── BluetoothServerManager.kt # 蓝牙 SPP 服务端、BLE 广播与看门狗
│       │   └── ui/
│       │       ├── ActivityRingView.kt       # 拟真圆环 Canvas 绘制与弹簧动效
│       │       └── MainActivity.kt           # 沉浸模式、弹性排版与增量差量更新
│       └── res/                              # 布局、色彩、矢量图标资源
├── docs/
│   └── screenshots/                          # 界面效果预览截图
├── tools/
│   └── test_sender.py                        # Python 串口/蓝牙测试数据发送器
├── BLUETOOTH_PROTOCOL.md                     # 蓝牙通讯协议规范与报文字典
├── LICENSE                                   # MIT 开源许可证
├── README.md                                 # 中文文档
└── README_EN.md                              # 英文文档
```

---

## ❓ 常见问题 (FAQ)

<details>
<summary><strong>Q: Mac 蓝牙列表中搜不到手机设备怎么办？</strong></summary>

1. 确认手机已经启动 AgentRing-Android 且屏幕保持常亮状态。
2. 确保系统蓝牙权限已被授予（Android 12+ 需要 `BLUETOOTH_SCAN` 和 `BLUETOOTH_ADVERTISE` 运行时权限）。
3. 点击屏幕顶部连接胶囊（状态区域），可快速触发一次完整的 Bluedroid 蓝牙协议栈重启与自愈流程。
</details>

<details>
<summary><strong>Q: 手机常亮是否会导致发热或电池损坏？</strong></summary>

- AgentRing-Android 移除了多余的后台网络轮询与高负载计算，蓝牙处于空闲监听模式时能耗极低。
- 建议将手机亮度调至适合桌面观看的柔和档位。如果手机支持电池保护（如充满至 80% 停止充电），建议开启；或接入智能插座定时补电。
</details>

<details>
<summary><strong>Q: 是否支持坚果/魅族/小米等旧系统的自动息屏限制？</strong></summary>

应用内部同时申请了 `FLAG_KEEP_SCREEN_ON` 与 `PowerManager.PARTIAL_WAKE_LOCK`。如果定制 ROM 的省电策略异常激进，建议在系统“电池管理/神隐模式”中将 AgentRing-Android 设置为“无限制/允许后台常驻”。
</details>

---

## 🤝 关联项目与致谢

- [haorui-lab/agentRing](https://github.com/haorui-lab/agentRing) — 精致小巧的 macOS 原生菜单栏 AI 额度指示器。

---

## 📄 开源许可证

本项目基于 [MIT 许可证](LICENSE) 开源。欢迎自由使用、派生及提交 Pull Request！
