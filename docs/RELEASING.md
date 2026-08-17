# Releasing Cirrus

Tagging `vX.Y.Z` builds a signed APK and a desktop package for each platform, verifies the APK's
signature, and publishes everything to GitHub Releases with a `.sha256` alongside each file.
Everything below is the one-time setup that makes that work, plus the per-release checklist.

The workflow is four jobs: `version` checks the tag against both modules before anything compiles,
`android` and `desktop` build in parallel, and `publish` collects their artifacts into one release.
A failure in any build job means nothing is published — there is no half-release where the APK
shipped and the DMG did not.

## Desktop packages are not signed

The APK is signed; the desktop packages are not. Code-signing them needs an Apple Developer ID
(annual) and an Authenticode certificate (annual), and this project carries neither.

The consequences are worth stating plainly rather than discovering in an issue:

- **macOS** quarantines the app on first launch. Users have to right-click → Open, or run
  `xattr -d com.apple.quarantine /Applications/Cirrus.app`.
- **Windows** SmartScreen shows a warning on the installer.
- **Linux** does not care.

The `.sha256` beside each package is therefore the only integrity check there is, and it is
produced in the same workflow run that built the package. If you ever do buy certificates, the
signing steps belong in the `desktop` matrix job, before the artifact is named.

`jpackage` cannot cross-compile, which is why that job runs once per platform.

macOS is **Apple Silicon only**. A DMG bundles a JRE for one architecture, so the arm64 build does
not run on an Intel Mac — but GitHub has retired the `macos-13` Intel image, and a matrix entry
asking for it queues forever without ever failing, which is worse than not offering it. Adding
Intel back means a self-hosted runner. Until then the README tells Intel users to build from
source, which needs only a JDK 17.

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
2. Bump `packageVersion` in `desktop/build.gradle.kts` to the same `versionName`. It is a separate
   declaration because `jpackage` has its own rules for what a version may look like — but the two
   must agree, or the release ships a DMG labelled with last release's number.
3. Move the `Unreleased` entries in `CHANGELOG.md` under a new `## [X.Y.Z] - YYYY-MM-DD`.
4. Commit, then tag and push:

   ```bash
   git tag -a v1.7.0 -m "Cirrus 1.7.0"
   git push origin main --follow-tags
   ```

The `version` job refuses to go further if the tag does not match **both** declarations, so a
forgotten bump fails in about a minute instead of shipping a mislabelled package.

## Verifying a published release

```bash
sha256sum -c cirrus-1.7.0-release.apk.sha256
apksigner verify --print-certs cirrus-1.7.0-release.apk

sha256sum -c cirrus-1.7.0-macos-arm64.dmg.sha256      # and the rest
```

The APK's certificate fingerprint must be identical across every release. A change means either
the key was replaced — which breaks in-place upgrades for everyone — or the file is not ours.
The desktop packages have no such fingerprint; the checksum is all there is.

## Installing

**Android** — [Obtainium](https://github.com/ImranR98/Obtainium) tracks GitHub Releases directly:
add `https://github.com/klaibercore/cirrus` as an app and it picks up each tag automatically. Or
download the APK from the [Releases page](https://github.com/klaibercore/cirrus/releases) and
install it manually.

**Desktop** — download the package for your platform and install it the usual way:

```bash
sudo apt install ./cirrus-1.7.0-linux-amd64.deb    # Linux
```

On macOS, open the DMG and drag Cirrus to Applications, then clear the quarantine flag as above.
On Windows, run the MSI and click through the SmartScreen warning.
