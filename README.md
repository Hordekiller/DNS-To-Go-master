# Dns To Go - Advanced DNS Optimizer

Dns To Go is a high-performance, gamer-focused DNS optimization tool for Android. It helps users reduce latency, bypass regional restrictions, and improve security with a modern, high-tech interface.

## ✨ Features
- **Gaming Mode:** Automatically finds and connects to the fastest DNS server for gaming.
- **Streaming Mode:** Optimizes your connection for buffer-free streaming on platforms like YouTube and Netflix.
- **Live Monitoring:** Real-time ping testing and latency charts.
- **Network Insights:** Automatic ISP detection and network type monitoring.
- **Bilingual Support:** Fully localized in English and Persian.
- **Modern UI:** Gamer-style dark theme with neon accents and smooth animations.
- **Customizable:** Add and save your own custom DNS servers.
- **Advanced Logging:** Dedicated log system to track connection status, errors, and system events.

## 🚀 Recent Updates

### Version 2.1.1 (Current)
- **Log System:** New "Logs" section for real-time tracking of connection attempts, errors, and background events.
- **New Servers:** Added high-performance **Zeus** and **Bogzar** DNS servers.
- **Bug Fix:** Resolved the "App Restart Required" bug; connection status now updates instantly.
- **Stability:** Fixed various race conditions and improved background service reliability.
- **Modernization:** Refactored code to use Activity Result API and improved Android 14 compatibility.

### Version 2.0.0
- Complete UI overhaul with Material Design 3.
- Improved stability on Android 12, 13, and 14.
- Real-time QPS (Queries Per Second) and Health Check.
- Persistent notification for background stability.

## 🛠 Tech Stack
- **Architecture:** MVP (Dagger 2 for DI)
- **Networking/Reactive:** RxJava 2 & RxAndroid
- **Monitoring:** MPAndroidChart for real-time latency graphs
- **View Binding:** ButterKnife (Modernization in progress)
- **Logging:** Timber with persistent storage bridge

## 🏗 Build
To build the APK, run:
```shell
./gradlew assembleDebug
```

## 📄 License
This project is licensed under the MIT License.
Copyright (c) 2026 HordeKiller.
