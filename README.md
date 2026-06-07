# Dns To Go - Advanced DNS Security & Optimization

Dns To Go is a professional, high-performance DNS optimization and security tool for Android. It leverages modern network protocols to provide a secure, fast, and private browsing experience, especially focused on gaming and streaming performance.

## 🛡️ Key Features

- **DNS-over-HTTPS (DoH):** Uses RFC 8484 for fully encrypted DNS queries, bypassing ISP monitoring and regional blocks.
- **Local DNS Resolver:** A robust, built-in resolver that parses IP (v4/v6) and UDP/53 traffic directly from a high-performance TUN interface.
- **Per-App Routing:** Select precisely which applications should use the secure DNS tunnel.
- **Intelligent DNS Cache:** Real-time TTL management to reduce latency to nearly zero for frequent queries.
- **Advanced Ad-Blocking:** Integrated filtering engine with support for standard `hosts`, `AdGuard`, and `dnsmasq` blocklist formats.
- **IPv6 Anti-Leak:** Complete protection against DNS leaks on IPv6 networks through comprehensive traffic routing.
- **Live Monitoring & Stats:** Real-time dashboard showing today's query volume, blocked items, and performance metrics.
- **Seamless Handover:** Robust handling of network changes (Wi-Fi to LTE) without connection drops.
- **Gaming & Streaming Modes:** Pre-configured low-latency servers optimized for gaming and buffer-free streaming.

## 🚀 Release History

### 🌟 Version 2.5.0 (Latest Release)
- **Engine Overhaul:** Fully modernized `VpnService` implementation for Android 14 compatibility.
- **Statistics Dashboard:** New UI section for real-time traffic insights (Total, Blocked, %).
- **Professional Logs:** Structured logging with source identification (Cache, Upstream, Blocked) and CSV export.
- **Network Handover:** Robust handling of Wi-Fi/Mobile transitions using `setUnderlyingNetworks`.
- **Advanced Resolver:** Local packet parsing with IPv6 support and Ad-blocking engine.

### 🛠️ Version 2.1.1
- **New Servers:** Added high-performance **Zeus** and **Bogzar** DNS providers.
- **UI Sync Fix:** Resolved race conditions in connection status display (Instant UI updates).
- **Stability Fixes:** Improved background reliability and thread management.
- **Modernization:** Initial migration to Activity Result API.

### 🏗️ Version 2.0.0
- **UI Redesign:** Complete transition to Material Design 3.
- **Base Engine:** Core implementation of the secure VPN tunnel.
- **Localization:** Full support for English and Persian languages.
- **Dark Mode:** System-wide dark theme support.

## 🛠 Tech Stack

- **Architecture:** MVP (Dagger 2 for DI)
- **Networking:** OkHttp (RFC 8484), RxJava 2, Custom TUN Parser
- **Performance:** Atomic Counters, Concurrent DNS Cache
- **UI:** Material Design 3, MPAndroidChart
- **Logging:** Timber with Persistent Ring-Buffer storage

## 🏗 Build

To build the signed APK, run:
```shell
./gradlew assembleRelease
```

## 📄 License
This project is licensed under the MIT License.
Copyright (c) 2026 HordeKiller.
