# Dns To Go — DNS changer for Android

Dns To Go changes the DNS resolver for your Android device without root. It runs a local,
DNS-only VPN: only DNS queries enter the tunnel, and they are answered through the provider
you choose, preferably over encrypted DNS (DoH/DoT). It is aimed at Iranian users who
want anti-sanction DNS, gaming and general privacy.

**Latest release:** [v2.7.0](https://github.com/Hordekiller/DNS-To-Go-master/releases/latest)
· Android 5.0+ (minSdk 21, targetSdk 34) · English, فارسی, Türkçe

## Features

- **53 providers in 5 groups**: Iran (anti-sanction), Public, Privacy, Ad-block and International.
  Each provider can offer DoH, DoT and plain UDP; the app probes them and prefers an
  encrypted endpoint that answers.
- **Custom DNS**: enter any IPv4/IPv6 resolver address.
- **DNS-only tunnel**: the VPN advertises virtual DNS addresses (`10.111.222.3`,
  `fd66:f83a:c650::3`) and routes nothing else, so all other traffic uses your normal
  connection untouched.
- **Encrypted DNS**: DoH (RFC 8484) and DoT (RFC 7858) with certificate and hostname
  verification and EDNS padding (RFC 7830).
- **Strict encrypted DNS** (Settings → Privacy): never fall back to plaintext DNS or to
  another provider.
- **Fallback resolver** (on by default): if your provider does not answer, Cloudflare DoH is
  used and shown as *Cloudflare Fallback* in the log.
- **Per-app routing**: apply the DNS only to the apps you select.
- **DNS cache** with correct TTL handling, plus a small built-in tracker blocklist.
- **Query log** with CSV export, and daily query/blocked statistics.
- **Network handover** (Wi-Fi ↔ mobile), auto-start after reboot, and always-on VPN support.
- **IPv4 and IPv6** DNS queries, including IPv6 extension headers.

Not included yet (see the [roadmap](docs/audit/2026-11-stabilization.md#4-roadmap-next-phase-prioritized)):
importing blocklists from the UI, certificate pinning presets, and DNS-over-TCP from apps
(those connections get an immediate reset so apps retry over UDP).

## How it works

```
app ──DNS query──▶ tun0 (10.111.222.3) ──▶ DnsQueryProcessor
                                             ├─ blocklist   → NXDOMAIN
                                             ├─ cache       → cached answer (TTL aged)
                                             └─ upstreams   → DoH / DoT / UDP / TCP
                                                              (validated, else SERVFAIL)
```

| Package | Responsibility |
|---------|----------------|
| `dns/` | Pure Java, unit-tested: `IpPacket` (IPv4/IPv6/UDP/TCP), `DnsMessage` (RFC 1035 parsing and validation), `DnsQueryProcessor`, `TunnelSession` (reader/writer threads and bounded worker pool per tunnel), `AppRoutingPolicy` |
| `resolver/` | DoH (OkHttp), DoT, UDP and TCP resolvers; server selection and probing; protected sockets |
| `model/` | Provider catalog (`DnsServerRepository`), `DNSModel`, model selection |
| `dnschanger/` | `DNSService` (VpnService lifecycle on a single control thread), UI, boot receiver |

## Build

Requirements: JDK 17 and the Android SDK (platform 34).

```shell
./gradlew testDebugUnitTest lintDebug   # 77 unit tests incl. packet-level and Robolectric tests
./gradlew assembleDebug                 # debug APK
./gradlew assembleRelease               # unsigned release APK (signing is done by CI)
```

Releases are built, signed with `apksigner` (APK Signature Scheme v3 key rotation) and
published by GitHub Actions when a `v*` tag is pushed. See [docs/RELEASING.md](docs/RELEASING.md).

## Documentation

- [CHANGELOG.md](CHANGELOG.md): release history
- [docs/RELEASING.md](docs/RELEASING.md): signing keys, secrets and the release process
- [docs/audit/2026-11-stabilization.md](docs/audit/2026-11-stabilization.md): audit report, known risks and roadmap

## License

MIT License. Copyright (c) 2026 HordeKiller.
