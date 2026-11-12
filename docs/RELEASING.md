# Releasing Dns To Go

## Signing keys

Since v2.7.0 releases are signed with **APK Signature Scheme v3 key rotation**:

| Devices | Signature | Key (alias) | Certificate SHA-256 |
|---------|-----------|-------------|---------------------|
| Android 5–8 (API 21–27) | v1 + v2 | original key (`my-key-alias`) | `59563369139ebf1f8cb4ee87c079a76865ac2ef24ec2f939e15384aaa2221391` |
| Android 9+ (API 28+) | v3 with lineage | new key (`dnstogo-2026`, RSA 4096) | `ca44276a677a1e06068b538882d9c890ee9d576109fa50e2ad8c0ee6e2e962e8` |

The lineage file proves the new key was authorised by the original one, so existing installs
accept the update. **Every future release must be signed with both keys and the same lineage.**

The maintainer keeps the signing material outside the repository, in
`~/.android-keys/dns-to-go/` (mode 700):

| File | Purpose |
|------|---------|
| `old-release-key.jks` | original key, still needed for Android 5–8 |
| `new-release-key.jks` | rotated key (PKCS12) |
| `new-store-password.txt` | password of the new keystore and key |
| `lineage.bin` | rotation proof, original key → new key |

Keep an offline backup of this folder. If the new key or the lineage is lost, Android 9+
devices can no longer receive updates. Never commit any of these files.

## GitHub secrets

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | base64 of `old-release-key.jks` |
| `KEY_ALIAS` | `my-key-alias` |
| `STORE_PASSWORD`, `KEY_PASSWORD` | original keystore and key password |
| `NEW_KEYSTORE_BASE64` | base64 of `new-release-key.jks` |
| `NEW_KEY_ALIAS` | `dnstogo-2026` |
| `NEW_STORE_PASSWORD` | contents of `new-store-password.txt` |
| `SIGNING_LINEAGE_BASE64` | base64 of `lineage.bin` |

To set the four rotation secrets from the maintainer machine:

```shell
K=$HOME/.android-keys/dns-to-go; R=Hordekiller/DNS-To-Go-master
base64 -w0 $K/new-release-key.jks | gh secret set NEW_KEYSTORE_BASE64 -R $R
base64 -w0 $K/lineage.bin          | gh secret set SIGNING_LINEAGE_BASE64 -R $R
tr -d '\n' < $K/new-store-password.txt | gh secret set NEW_STORE_PASSWORD -R $R
printf %s dnstogo-2026             | gh secret set NEW_KEY_ALIAS -R $R
```

If any secret is missing, the *Check signing secrets* step fails and lists them.

## Release steps

1. Bump `versionCode` and `versionName` in `app/build.gradle` and add a section to
   `CHANGELOG.md`.
2. Run `./gradlew testDebugUnitTest lintDebug assembleRelease` locally.
3. Merge to `master`, then tag and push: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`.
4. The *Build & Release* workflow runs the tests and lint, builds the unsigned APK, signs it
   with the rotation lineage, checks both certificate fingerprints, and publishes the APK and
   `mapping.txt` to GitHub Releases.
5. Download the published APK and verify it:
   ```shell
   apksigner verify --print-certs --min-sdk-version 21 --max-sdk-version 27 app-release.apk  # expect 5956…1391
   apksigner verify --print-certs --min-sdk-version 28 app-release.apk                       # expect ca44…62e8
   ```

## Signing locally (only if CI is unavailable)

```shell
K=$HOME/.android-keys/dns-to-go
OLD_PASS=… NEW_PASS=$(cat $K/new-store-password.txt) apksigner sign \
  --in app/build/outputs/apk/release/app-release-unsigned.apk --out app-release.apk \
  --ks $K/old-release-key.jks --ks-key-alias my-key-alias --ks-pass env:OLD_PASS --key-pass env:OLD_PASS \
  --next-signer --ks $K/new-release-key.jks --ks-key-alias dnstogo-2026 --ks-pass env:NEW_PASS --key-pass env:NEW_PASS \
  --lineage $K/lineage.bin --rotation-min-sdk-version 28
```

v2.7.0 was signed this way, because the rotation secrets had not yet been added to the
repository.
