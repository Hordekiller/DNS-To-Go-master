# DNS To Go — stabilization audit (Nov 2026)

- **Base commit:** `aa2b9d472812dd5f5637eb74f5966d3029819602` (`origin/master`, v2.6.8, versionCode 9)
- **Branch:** `fix/dns-vpn-stabilization`
- **Scope:** DNS correctness, VPN reliability, concurrency, security, regression tests. No new features.

Legend: **C** = confirmed (reproduced by test or by running the original code), **R** = risk (code reading / needs device).
Severity: S1 critical (VPN unusable / traffic broken / security), S2 high, S3 medium, S4 low.

## 1. Confirmed bugs (all fixed on this branch)

| # | Sev | Area | Finding | Evidence | Fix |
|---|-----|------|---------|----------|-----|
| 1 | S1 | VPN builder | `applyAppFilter()` called `addAllowedApplication()` for selected apps, then the code always called `addDisallowedApplication(self)`. The framework throws `UnsupportedOperationException`; the tunnel thread died → VPN never worked once any app was selected. (`DNSService.java:842,856` @aa2b9d4) | `robo/VpnBuilderRoutingTest.originalSequenceThrowsInTheFramework` (real `VpnService.Builder`, API 34) | `dns/AppRoutingPolicy`: allowlist **or** denylist, never both; own package never allowlisted; all-uninstalled selection falls back to denylist(self) |
| 2 | S1 | Lifecycle | "Works once" root cause: the old tunnel thread's `finally` set the shared `isRunning=false` and closed the shared `fileDescriptor` field. When a restart's `join(1500ms)` timed out, the old thread killed the **new** session. Also `packetPool=null` while workers still called `packetPool.release()` (NPE). | `TunnelSessionTest.stoppingAnOldSessionNeverAffectsTheNewOne`, `repeatedConnectDisconnectCyclesLeakNoThreads` (50 cycles) | `dns/TunnelSession` owns fd, threads, queues and pool per session; all service transitions serialized on one control thread |
| 3 | S1 | PacketPool | Index-based ring `head & (n-1)` returned buffers still in use when releases were out of order (one slow query + 63 fast ones → reuse → corrupted packet). | `repro/Repro.java` R5 against original code: `reused in-use buffer: true` | Pool removed; reader owns one buffer, each payload is copied before hand-off |
| 4 | S1 | Executor | `SynchronousQueue` + 32 threads: on burst `execute()` threw `RejectedExecutionException`, caught by the generic `catch`, buffer never released, query dropped silently. After 64 leaks the reader blocked forever on `semaphore.acquire()` → all DNS dead. Nested second submit (`submitResolve`) also dropped silently. | `TunnelSessionTest.overloadAnswersServfailInsteadOfDroppingOrDeadlocking` | Bounded queue (256) + fixed workers; rejection → immediate SERVFAIL; one task per query |
| 5 | S1 | Routing | Real resolver IPs (e.g. `1.1.1.1`, `8.8.8.8`) were routed into the TUN, so **all** non-DNS traffic to them from other apps (HTTPS, DoT, ping) was black-holed. TUN address `192.168.0.1/24` collided with common home LANs. | Code reading (`addDnsRoute`) + device check below | Virtual DNS `10.111.222.3` / `fd66:f83a:c650::3`, only /32 and /128 routes; TUN `10.111.222.1/24` |
| 6 | S2 | Model propagation | `MainActivity.startDNS()` always built a new `DNSModel` from the two text fields → `serverGroupId` (and DoH/DoT endpoints) lost; DoH-only providers (NextDNS, Mullvad, DNS0 Kids, RethinkDNS) started with `firstDns=""` → `addDnsServer("")` threw → tunnel died. | `DnsModelSelectionTest.pickedProviderKeepsItsGroup`, `dohOnlyProviderIsStartableWithoutIps` | `model/DnsModelSelection` keeps the picked provider unless fields were edited; validates IP literals without DNS lookups |
| 7 | S2 | IPv6 | Extension-header walk read Next Header from the byte **after** the header (UDP source port) → every IPv6 query with Hop-by-Hop/Dest-Opts dropped. AH length formula wrong; ESP "parsed". | Repro R1 (`parsed: false`); `IpPacketTest.parsesIpv6WithExtensionHeaders` | `dns/IpPacket` RFC 8200 walk; fragments/ESP rejected |
| 8 | S2 | IPv6 | Reply builder read ports at fixed offset 40 → replies to queries with ext headers went to the wrong port (4352 instead of 4000). UDP checksum 0 not mapped to 0xFFFF. | Repro R4; `IpPacketTest.ipv6ReplyUsesRealPortsAndValidChecksum` | Ports from parsed packet; checksum 0→0xFFFF; IPv4 UDP checksum now computed too |
| 9 | S2 | DNS parsing | Name parser stopped after 10 labels → two different names produced the **same** cache key and blocklist name; QTYPE read from the middle of the name (879). Cache could serve another domain's answer. | Repro R2; `DnsMessageTest.parsesNamesWithManyLabels` | `dns/DnsMessage.readName`: 255-octet / 63-label limits, compression with backward-only pointers, loop guard, escaping |
| 10 | S2 | NXDOMAIN | 76-byte buffer → `BufferOverflowException` for names > ~60 chars (blocked long tracker domains crashed the worker → client timeout); QTYPE always A; RD bit dropped. | Repro R3; `DnsMessageTest.nxdomainEchoesQuestionForLongNamesAndAnyType` | Built from the original query bytes (header + question echoed) |
| 11 | S2 | Response validation | Upstream answers were never checked (ID, QR, question). UDP socket unconnected → any source accepted. | `DnsMessageTest.validatesResponses`, `DnsQueryProcessorTest.invalidResponseFallsThroughToNextUpstream` | `isValidResponseFor()` on every upstream answer and probe; UDP `connect()` + ID loop |
| 12 | S2 | Silent loss | Any upstream failure → no reply → client waits for its full timeout. TCP/53 (and anything else to the DNS IP) silently dropped. | `DnsQueryProcessorTest.allUpstreamsFailingYieldsServfailNotSilence`, `TunnelSessionTest.tcpDnsGetsResetAndOtherTrafficIsIgnored` | SERVFAIL on failure, FORMERR on malformed; TCP → RST/ACK |
| 13 | S2 | Cache | SERVFAIL/empty answers cached 300 s; TTL never decremented; `clear()` on full (R6); key ignored QCLASS. | Repro R6; `CacheAndBlocklistTest`, `DnsQueryProcessorTest.nxdomainFromUpstreamIsCachedPerSoa` | LRU 1000; RFC 2308 negative TTL from SOA; SERVFAIL/TC/TTL 0 never cached; TTL aging; cleared on every new session |
| 14 | S2 | Privacy | Selector score `latency + priority*25ms`: a cold DoH probe (TLS handshake) systematically lost to 1-RTT UDP → users silently downgraded to plaintext. Last resort switched to Cloudflare for **any** provider (filtering providers bypassed). No strict option. | `DnsServerSelectorTest` | Encrypted always beats plaintext when it answers; new *Strict encrypted DNS* setting (no plaintext, no cross-provider fallback); fallback now a visible setting (default on = previous behavior) |
| 15 | S2 | DoT security | With no hostname, DoT did **no identity check** (any publicly trusted cert accepted). | Code reading | Always verify hostname, or IP SAN when no hostname |
| 16 | S2 | Catalog | Providers without IPs got DoT bootstrap `1.1.1.1` (Cloudflare) → DoT for `dns.nextdns.io` etc. connected to the wrong operator. | `DnsServerSelectorTest.dohOnlyProviderHasCandidatesWithoutPlainIps` | Bootstrap only from the provider's own IP; else hostname via system resolver |
| 17 | S2 | Resolver lifecycle | `isClosed()` returned true for a not-yet-connected DoT/TCP resolver → router replaced live resolvers on every call, closing them mid-connect and leaking sockets. | Code reading | Explicit `closed` flag; reconnect internally |
| 18 | S2 | Handover | 500 ms "ignore" debounce dropped the `onAvailable` following an `onLost` → underlying network stayed `null` after Wi-Fi↔mobile switch. | Code reading | Coalesce (400 ms) and always apply the latest default network |
| 19 | S2 | Min SDK | `Comparator.comparingInt`, `List.sort`, `AtomicInteger.updateAndGet`, `ConcurrentHashMap.newKeySet` are API 24, `minSdk 21` → `NoSuchMethodError` on Android 5–6 (service field initializer → service crash). | Baseline lint `NewApi` errors | Replaced with API 21 equivalents |
| 20 | S2 | Boot | Boot notification built without channel → dropped on Android 8+; its `dnsModel` extra was never read; `dnsModel` pref deleted on every stop, so always-on VPN / sticky restart had no model. | Code reading | Start directly if consent valid, else notification on a real channel; last model kept |
| 21 | S2 | Notifications | VPN start was **blocked** when POST_NOTIFICATIONS was denied (Android 13+); after two denials the app was unusable. | Code reading | Warn only; FGS runs with hidden notification |
| 22 | S3 | Metered | No `setMetered(false)` with targetSdk ≥ 29 → whole device appears metered while the DNS VPN runs. | Docs + code | `setMetered(false)` on API 29+ |
| 23 | S3 | Logging | Release Timber tree persisted DEBUG lines (every queried domain, stack traces) to SharedPreferences; each flush re-parsed the whole JSON log under a global lock on resolver threads; raw `Log.d("DNSDebug")` in release. | Code reading | Persist INFO+ only; in-memory history; Timber only |
| 24 | S3 | Blocklist | O(n) scan of every blocked domain per query. | `CacheAndBlocklistTest.blocklistLookupIsFastForLargeLists` (200k entries) | Suffix walk O(labels) |
| 25 | S3 | App filter | Toast promised "VPN will restart" but nothing restarted. | Code reading | Reconnect on leaving the screen |
| 26 | S3 | Lint | `TimberArgCount` error (`Timber.w("TUN read failed", e)`). | Baseline lint | Code removed |

## 2. Repository / release risks

| Sev | Finding | Action taken | Needs owner |
|-----|---------|--------------|-------------|
| S1 | `app/signing.properties` with keystore/key passwords committed since `b45d1b8` (public repo). | Untracked + `.gitignore` | **Rotate the release key passwords** (and the key if the `.jks` was ever public — `8848b92` removed it from the tree, history still has it). History rewrite only with your approval. |
| S3 | `app/mapping.txt` (240k lines) committed by `-printmapping`. | Untracked; mapping now attached to the GitHub release | — |
| S3 | `.gitignore` ignored `release.yml`. | Removed | — |
| S3 | Release workflow builds and publishes with no tests; third-party action pinned by tag. | Added `testDebugUnitTest lintDebug` step | Pin `softprops/action-gh-release` by SHA |
| S3 | `lintOptions { abortOnError false; checkReleaseBuilds false }` — lint never gates. | Not changed (75 UnusedResources / 54 MissingTranslation would fail today) | Add a lint baseline, then enable |
| S3 | Proguard: `-keep class com.google.** { *; }`, `-dontoptimize`, stale Butterknife/GreenDao/Retrofit rules. | Not changed | Clean up |
| S4 | Unused permissions `SCHEDULE_EXACT_ALARM` (needs a Play declaration), `WAKE_LOCK`. | Removed | — |
| S2 | **targetSdk 34**: Google Play requires API 35 for updates since 31 Aug 2025 and API 36 since 31 Aug 2026. Updates on Play are blocked (Myket unaffected). | Not changed (edge-to-edge enforcement in 35 changes the XML screens; needs visual QA) | Roadmap item 1 |
| S4 | `QUERY_ALL_PACKAGES` absent → on Android 11+ the app filter lists only launcher apps. | Not changed (Play restricts that permission) | Product decision |
| S4 | Catalog data: Asiatech secondary was Shecan's IP; "Bogzar"; Freenom World is discontinued; `doh.pub` bootstrap IP unverified. | v2.7.0: Shecan removed (replaced by DNS Pro; Quad101 and DNS4EU Unfiltered added), Asiatech secondary removed | Verify remaining catalog entries on a device |

### Signing key rotation (v2.7.0)

The release keystore **and** its real password were public in this repository's
history (commits `bf27b2b` … `8e46d9a`), so anyone could sign an APK that installs as
an update. Changing the password cannot fix that. v2.7.0 therefore rotates the key with
APK Signature Scheme v3 (`--rotation-min-sdk-version 28`):

| Devices | Verified against | Certificate SHA-256 |
|---------|------------------|---------------------|
| Android 5–8 (API 21–27), v1/v2 | original key | `59563369…e15384aaa2221391` |
| Android 9+ (API 28+), v3 lineage | new key `dnstogo-2026` (RSA 4096) | `ca44276a…2ad8c0ee6e2e962e8` |

- Existing installs update normally. From v2.7.0 on, Android 9+ devices accept updates
  only from the new key (or one it signs into a new lineage).
- Android 5–8 still trust only the original key; that residual risk cannot be removed
  without abandoning those users.
- CI signs with `apksigner` and refuses to publish unless both fingerprints match.
- The new key, its password and the lineage live outside the repo
  (`~/.android-keys/dns-to-go/`, mode 700) and in GitHub secrets
  (`NEW_KEYSTORE_BASE64`, `NEW_STORE_PASSWORD`, `NEW_KEY_ALIAS`, `SIGNING_LINEAGE_BASE64`).
  **Back them up offline. Losing them means no more updates on Android 9+.**

## 3. Remaining risks (need a real device / network)

- Stub resolvers that do not send EDNS may receive UDP replies > 512 bytes (we no longer truncate; TCP retry would get a RST). Android's resolver sends EDNS, so impact should be small.
- Android "Private DNS" strict mode bypasses VPN DNS entirely (pre-existing, platform behavior).
- DoT is still one serialized connection per server (head-of-line blocking under load); DoH uses OkHttp pooling.
- Bootstrap: system DNS is tried first, provider IPs second. A *poisoned* system answer that serves a valid-looking TLS cert is not possible without a CA compromise, but a poisoned answer that resets TLS costs one failed route.
- Behavior on OEM ROMs (MIUI/ColorOS background limits), Android 5–6 devices, real Wi-Fi↔LTE handover, VPN revocation by another VPN app and always-on/lockdown mode are **not** verified (see the validation section for what was).

## 4. Roadmap (next phase, prioritized)

1. targetSdk 35 → 36 with edge-to-edge QA; keep minSdk 21 or raise to 23/24 to drop legacy branches.
2. Lint baseline + `abortOnError true`; CI on pull requests (unit tests, lint, R8 build).
3. Instrumented tests on an emulator matrix (API 24/29/34/36) for start/stop/revoke/handover.
4. DoT connection pooling/pipelining; DoH over HTTP/2 reuse metrics; per-upstream health with backoff.
5. Settings UI to show the selected protocol/server and strict-mode status in the notification.
6. Blocklist import UI (the parser exists, nothing calls it), with size limits.
7. Catalog as versioned data (JSON) with verified bootstrap IPs and DoH/DoT hostnames.
8. Optional: proper TCP/53 support (minimal TCP stack) instead of RST.
