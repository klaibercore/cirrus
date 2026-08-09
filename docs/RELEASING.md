# Releasing Cirrus

Tagging `vX.Y.Z` builds a signed APK, verifies its signature, and publishes it to GitHub
Releases with a `.sha256` alongside it. Everything below is the one-time setup that makes
that work, plus the per-release checklist.

## The signing key

Android identifies an app by its signing certificate. Users can only upgrade in place if every
release is signed with the same key, so **losing this keystore means every installed copy is
orphaned** — they would have to uninstall and reinstall, losing their conversations. Back it up
somewhere you would still have it if this machine died.

Generate it once:

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias cirrus \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Cirrus, O=klaibercore, C=DE"
```

`keystore/` and `keystore.properties` are both gitignored. Do not move them out of the ignore
list, and do not paste the passwords into an issue, a commit message or a model prompt.

### Building a signed release locally

Create `keystore.properties` in the repository root:

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=cirrus
keyPassword=…
```

Then:

```bash
./gradlew :app:assembleRelease
```

Without that file — or without the equivalent environment variables — the release build still
runs, it just comes out unsigned. That is deliberate: cloning the repo and building release
should not require secrets you do not have.

## GitHub Actions secrets

Set these four under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i keystore/release.jks` (one line, no wrapping) |
| `KEYSTORE_PASSWORD` | the `-storepass` value |
| `KEY_ALIAS` | `cirrus` |
| `KEY_PASSWORD` | the key password |

On macOS, `base64 -i file` emits a single line. On Linux use `base64 -w0`. A wrapped value
decodes to a corrupt keystore; the workflow runs `keytool -list` immediately after decoding to
catch that early rather than 20 minutes later in the signing task.

```bash
gh secret set KEYSTORE_BASE64 < <(base64 -i keystore/release.jks)
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` must increase
   by at least one; F-Droid and Obtainium both order releases by it, not by the name.
2. Move the `Unreleased` entries in `CHANGELOG.md` under a new `## [X.Y.Z] - YYYY-MM-DD`.
3. Commit, then tag and push:

   ```bash
   git tag -a v1.0.0 -m "Cirrus 1.0.0"
   git push origin main --follow-tags
   ```

The workflow refuses to build if the tag does not match `versionName`, so a forgotten bump
fails in about a minute instead of shipping a mislabelled APK.

## Verifying a published release

```bash
sha256sum -c cirrus-1.0.0-release.apk.sha256
apksigner verify --print-certs cirrus-1.0.0-release.apk
```

The certificate fingerprint must be identical across every release. A change means either the
key was replaced — which breaks in-place upgrades for everyone — or the file is not ours.

## Installing

**[Obtainium](https://github.com/ImranR98/Obtainium)** tracks GitHub Releases directly: add
`https://github.com/klaibercore/cirrus` as an app and it picks up each tag automatically.

Or download the APK from the [Releases page](https://github.com/klaibercore/cirrus/releases)
and install it manually.
