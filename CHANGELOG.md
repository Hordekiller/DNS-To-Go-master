# Changelog

## 2.7.0 (versionCode 11), 2026-11-12

### Changed
- Removed **Shecan** from the provider list and replaced it with **DNS Pro** (Iran).
  Asiatech no longer lists Shecan's address as its secondary resolver.
- Added **Quad101 (TWNIC)** and **DNS4EU Unfiltered**, both with DoH and DoT.
- Users whose saved choice was Shecan keep working with those addresses as a custom DNS.

### Security
- **Signing key rotated** with APK Signature Scheme v3, because the previous keystore and its
  password had been published in the repository history. Android 9+ now trusts only the new
  key (SHA-256 `ca44276a…62e8`); Android 5–8 still verify the original key
  (`59563369…1391`). Existing installs update normally.
- Release CI signs with `apksigner` and refuses to publish unless both certificates match.

## 2.6.9 (versionCode 10), 2026-11-11

Stability and DNS-correctness release; see the
[audit report](docs/audit/2026-11-stabilization.md) for details and reproduction steps.

### Fixed
- VPN failed to start whenever apps were selected for per-app routing.
- "Works only once": a stopping tunnel could close or disable the next one.
- DNS stalled under load (packet buffer reuse and leaked buffers on executor rejection).
- All non-DNS traffic to the chosen resolver's IP (for example 1.1.1.1) was blocked.
- The provider picked in the list was ignored; DoH-only providers could not start.
- IPv6 DNS with extension headers was dropped, and replies went to the wrong port.
- Long blocked domains crashed the NXDOMAIN reply; names with more than 10 labels shared
  cache entries.
- Upstream answers were not validated; failures left apps waiting instead of answering SERVFAIL.
- DNS-over-TCP from apps now gets an immediate reset instead of a timeout.
- DoT accepted any trusted certificate when no hostname was configured.
- The server selector silently preferred plaintext over DoH.
- Wi-Fi/mobile handover could leave the VPN without an underlying network.
- Crashes on Android 5–6 caused by API 24 methods.
- Boot notification was dropped on Android 8+; the VPN would not start without notification
  permission.
- Debug logs containing every queried domain were saved in release builds.

### Added
- Settings → Privacy: **Strict encrypted DNS** and **Fallback resolver** switches.
- 73 unit tests (packet fixtures, concurrency, Robolectric `VpnService.Builder`).

### Repository
- Stopped tracking `app/signing.properties` and the R8 `mapping.txt`; releases run tests and
  lint first. Removed the unused `SCHEDULE_EXACT_ALARM` and `WAKE_LOCK` permissions.

## 2.6.8 and earlier

See the [GitHub releases](https://github.com/Hordekiller/DNS-To-Go-master/releases).
