# LocalTracklist Release Procedure

LocalTracklist is published by quietswitch and was formerly named LibrePlayer. Published v1.0.4 and v1.1.0 releases keep their original names and artifacts. The rebrand was reviewed at version 1.1.0/code 6 without republishing. The separately approved next release is LocalTracklist 1.2.0/code 7. Reuse the original signing identity.

This procedure produces and verifies a signed LocalTracklist APK without placing signing material in the repository. Commands are for Windows PowerShell and must be run from the repository root.

## Prerequisites

- Java 21 available to Gradle
- Android SDK Platform 36
- A recent Android SDK Build Tools package containing `zipalign` and `apksigner`
- Android SDK Platform Tools containing `adb`
- Secure, non-repository storage for a private Android release keystore and its backup
- A connected Android 8.0 (API 26) or newer device for installation and manual acceptance checks

Use placeholders such as `<ANDROID_SDK>`, `<BUILD_TOOLS_VERSION>`, `<SECURE_KEYSTORE_PATH>`, and `<KEY_ALIAS>` locally. Do not replace them with private values in tracked files.

## Preserve the Permanent Signing Identity

The permanent release key already exists. Reuse that same key and certificate for LocalTracklist updates; do not generate a replacement for the rebrand. The initial key-creation instructions below are retained for historical reference only. The signing certificate is visible to anyone who inspects a released APK, so its subject must not contain a legal name, personal email address, organization, location, or other identifying information. Use only the public pseudonym `quietswitch` where an identity is required.

Create the key outside the repository and let `keytool` request its passwords interactively:

```powershell
keytool -genkeypair -v `
    -keystore '<SECURE_KEYSTORE_PATH>' `
    -alias '<KEY_ALIAS>' `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname 'CN=quietswitch'
```

Choose a non-identifying alias. Do not put passwords in the command, a properties file, shell history, or this repository. Inspect the certificate before release and confirm that its owner/subject contains only the intended pseudonym.

## Clean Verification

```powershell
.\gradlew.bat clean --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat assembleRelease --console=plain
git diff --check
git status --short
```

The unsigned release APK is expected at:

```text
app\build\outputs\apk\release\app-release-unsigned.apk
```

## Align and Sign

The example below targets the separately versioned LocalTracklist 1.2.0 release. Do not overwrite either historical LibrePlayer release. Set temporary PowerShell variables for the local session. The keystore path and alias shown here are placeholders:

```powershell
$buildTools = Join-Path '<ANDROID_SDK>' 'build-tools\<BUILD_TOOLS_VERSION>'
$unsignedApk = '.\app\build\outputs\apk\release\app-release-unsigned.apk'
$alignedApk = '.\app\build\outputs\apk\release\app-release-aligned.apk'
$signedApk = '.\app\build\outputs\apk\release\LocalTracklist-1.2.0.apk'
$keyStore = '<SECURE_KEYSTORE_PATH>'
$keyAlias = '<KEY_ALIAS>'

& (Join-Path $buildTools 'zipalign.exe') -p -f 4 $unsignedApk $alignedApk
& (Join-Path $buildTools 'apksigner.bat') sign --ks $keyStore --ks-key-alias $keyAlias --out $signedApk $alignedApk
```

Allow `apksigner` to request passwords interactively. Do not place passwords on the command line, in PowerShell history, in Gradle properties, or in repository files.

## Verify the Signed APK

```powershell
& (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $signedApk
Get-FileHash -Algorithm SHA256 $signedApk
```

Record the SHA-256 value next to the distributed artifact through the chosen release channel.

## Install

Use the `adb` executable from the local Android SDK:

```powershell
$adb = Join-Path '<ANDROID_SDK>' 'platform-tools\adb.exe'
& $adb devices
& $adb install $signedApk
```

For a later update signed with the same permanent key, use `adb install -r`. A debug-signed installation cannot be updated in place by a release-signed APK; use a separate device/profile for acceptance or uninstall the debug build only after accepting the loss of its local app data.

## Manual Acceptance Checklist

- Install or update succeeds on a supported Android device.
- The app launches and requests only the expected audio permission.
- MediaStore songs appear after permission is granted.
- Optional folder import uses the system picker and imports the selected folder.
- A song plays, pauses, seeks, and advances through the queue.
- Shuffle and repeat behave as selected.
- Notification, lock-screen, headset, or Bluetooth controls operate during background playback.
- Now Playing handles multiline title and artist text without overlapping controls.
- Favorites, playlists, settings, queue, and playback position survive an app restart.
- Airplane-mode playback works and no network permission appears in app details.

## Signing-Key Protection

- Keep the keystore, alias, and passwords outside the repository and outside shared build artifacts.
- Store the keystore in encrypted primary storage with at least one encrypted offline backup.
- Store passwords separately from the keystore and limit access to the release owner.
- Never commit signing properties, shell transcripts containing credentials, or signed-key backups.
- Preserve the same release key for future updates; losing it can prevent compatible update distribution.
- Before publishing, inspect `apksigner verify --verbose --print-certs` output and confirm that the certificate exposes only the `quietswitch` identity.
- Verify backups periodically without copying private material into the project directory.
