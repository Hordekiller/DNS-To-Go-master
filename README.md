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

## 🚀 Recent Updates (Version 2.5.0)

- **Engine Overhaul:** Fully modernized `VpnService` implementation for Android 14 compatibility.
- **Statistics Dashboard:** New UI section for real-time traffic insights.
- **Professional Logs:** Structured logging with source identification (Cache, Upstream, Blocked) and CSV export.
- **Stability Fixes:** Resolved race conditions in connection status and improved background reliability.
- **New Servers:** Added high-performance **Zeus** and **Bogzar** DNS providers.

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
