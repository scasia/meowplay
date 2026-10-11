# MeowPlay 🐱

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Platform-Android%207.1%2B-green.svg)](https://developer.android.com)
[![Stereo](https://img.shields.io/badge/Stereo-Pioneer%20WebLink-orange.svg)]()

**MeowPlay** turns any Android device (phone, tablet, or headless dongle) into a wireless **Apple CarPlay adapter for WebLink car stereos** (such as Pioneer AVH and DMH series).

Instead of being restricted to the stock WebLink apps, MeowPlay establishes a native wireless Apple CarPlay connection with your iPhone and streams high-performance H.264 video, audio, and touchscreen interactions directly over USB to your car head unit.

---

## 🌟 Features

- 🐱 **Native Wireless Apple CarPlay**: Wirelessly pairs with your iPhone using Bluetooth LE / RFCOMM handshake and high-speed 5GHz Wi-Fi.
- ⚡ **Zero-Copy H.264 Video Streaming**: Real-time video extraction and SPS/PPS Annex B parsing piped directly to the head unit's native WebLink decoder (e.g. 1024×600 @ 60 FPS).
- 👆 **Responsive Touch Input**: Translates car stereo touchscreen coordinates to CarPlay normalized points and dispatches them asynchronously off the UI thread to prevent stream stalling.
- 📶 **Dual Wi-Fi Architecture**:
  - **Wi-Fi Hotspot Mode**: Use Android's local hotspot with configurable SSID and WPA2 passphrase.
  - **Wi-Fi Direct (P2P) Mode**: Zero-configuration peer-to-peer Wi-Fi connection with the iPhone.
- 🛠 **Built-in Diagnostic Console**: Full selectable and copyable real-time event logs for connection debugging, iAP2 status, and video frame rate monitoring.
- 🚀 **Background Service & Auto-Boot**: Foreground Android service with wake-lock support and `BOOT_COMPLETED` receiver for seamless in-car operation.

---

## 📐 Architecture

```
┌─────────────────┐             Bluetooth / Wi-Fi            ┌─────────────────────────────┐
│                 │ ◄──────────────────────────────────────► │                             │
│   Apple iPhone  │                                          │      Android Device         │
│  (Wireless CP)  │ ═══════════════════════════════════════► │   running MeowPlay 🐱       │
│                 │     H.264 Video / Audio / iAP2           │                             │
└─────────────────┘                                          └──────────────┬──────────────┘
                                                                            │
                                                                            │ USB AOA / WebLink
                                                                            │ Protocol
                                                                            ▼
                                                             ┌─────────────────────────────┐
                                                             │     Pioneer Car Stereo      │
                                                             │      (WebLink Host)         │
                                                             │  1024x600 Hardware Decoder  │
                                                             └─────────────────────────────┘
```

---

## 📋 Requirements

### Hardware
1. **Android Device**: Running Android 7.1 (API 25) or higher, with USB Accessory (AOA 2.0) support, Wi-Fi, and Bluetooth.
2. **Car Head Unit**: Pioneer or compatible aftermarket stereo supporting **WebLink** (e.g. AVH-ZL5150BT, DMH-Z5350BT, AVH-2550NEX, etc.).
3. **USB Cable**: Good quality data cable connecting the Android device to the car stereo's primary USB port.
4. **iPhone**: iPhone 5 or newer running iOS 12 or higher.

---

## 🔑 Offline MFi Authentication Setup

To authenticate with iOS devices for wireless Apple CarPlay, MFi (Made for iPhone) authentication credentials are required:

1. Obtain your MFi credentials (e.g. dumped from an authorized adapter or MFi co-processor):
   - `identity.pk8` (PKCS#8 private key)
   - `certificate.p7b` (PKCS#7 certificate chain)
2. Place both files into:
   ```
   app/src/main/assets/offline-mfi/
   ```
3. MeowPlay will automatically deploy and verify these credentials on first launch.

> **Note:** Due to Apple intellectual property restrictions, proprietary MFi cryptographic keys cannot be distributed in this repository. `*.p7b` and `*.pk8` files are excluded by `.gitignore`.

---

## 🛠 Building from Source

### Prerequisites
- JDK 17 (e.g. OpenJDK 17)
- Android SDK (API 34/37) with Android NDK (version 28+)
- CMake & Android command-line tools

### Build Steps

```bash
# Clone the repository
git clone https://github.com/your-username/meowplay.git
cd meowplay

# Ensure local.properties points to your Android SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Build Debug APK
./gradlew :app:assembleDebug
```

The compiled APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 Setup & Usage

1. **Install MeowPlay**: Sideload `app-debug.apk` onto your Android device.
2. **Grant Permissions**: Launch MeowPlay and grant requested Bluetooth, Location, and Nearby Devices permissions.
3. **Configure Wi-Fi**:
   - Choose **Wi-Fi Hotspot** or **Wi-Fi Direct (P2P)**.
   - If using Hotspot, match the SSID and passphrase to your phone's Wi-Fi hotspot configuration.
4. **Connect to Car**: Plug your Android phone into the car stereo USB port.
5. **Launch WebLink**: Tap the **WebLink** icon on your stereo screen.
6. **Pair iPhone**:
   - On your iPhone, open **Settings > Bluetooth**.
   - Pair with the Android device's Bluetooth name.
   - Confirm CarPlay prompt on your iPhone.
7. **Enjoy Wireless CarPlay!** 🐱

---

## 🤝 Credits & Acknowledgements

MeowPlay is built upon pioneering open-source automotive reverse engineering efforts:

- **[DiPlay](https://github.com/shilapi/DiPlay) & [xcertplay](https://github.com/shilapi)**: Core CarPlay protocol stack, iAP2 implementation, and offline MFi authentication architecture.
- **DiAuto**: Research on wireless CarPlay dongle reverse engineering.
- **Abalta Technologies**: Creators of WebLink protocol.

---

## 📄 License

MeowPlay is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License** as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

See the [LICENSE](LICENSE) file for details.

---

## ⚠️ Disclaimer

- Apple, CarPlay, iPhone, and iOS are trademarks of **Apple Inc.**
- Pioneer is a trademark of **Pioneer Corporation**.
- WebLink is a trademark of **Abalta Technologies, Inc.**
- This project is an independent open-source initiative and is **not affiliated with, maintained by, or endorsed by** Apple Inc., Pioneer Corporation, or Abalta Technologies.
