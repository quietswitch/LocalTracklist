# LocalTracklist

Local music player for Android.

Publisher/developer: quietswitch.

LocalTracklist, formerly LibrePlayer, is the same application. Published v1.0.4 and v1.1.0 releases retain the LibrePlayer name; Android package identity remains `com.libreplayer`.

LocalTracklist is a free, open-source Android music player for audio files stored on the device. It is offline and local-first: playback, library data, playlists, favorites, settings, and history remain on the device.

## Download LocalTracklist

**[Download LocalTracklist 1.2.1 for Android (APK, 19.1 MiB)](https://github.com/quietswitch/LocalTracklist/releases/download/v1.2.1/LocalTracklist-1.2.1.apk)**

Requires Android 8.0 or newer. Free, no ads or accounts. You do not need Android Studio, a GitHub account, or a computer to install the app.

1. Open the download link on your Android device.
2. Open the downloaded APK. If Android asks, allow your browser or file manager to install apps from that source, then return to the APK and tap Install.
3. Open LocalTracklist and grant access to your local audio files when prompted. You can turn off the browser or file manager's installation permission afterward.

**Already using LibrePlayer or LocalTracklist?** The official release-signed APK uses the same application ID and signing certificate as previous public releases. Back up any important local playlists first, then install the APK over the existing app. Do not uninstall the existing app or clear its data just to update. If Android rejects the update, stop rather than uninstalling; a differently signed build cannot be updated in place.

[Release notes and SHA-256 checksum](https://github.com/quietswitch/LocalTracklist/releases/tag/v1.2.1) · [All releases](https://github.com/quietswitch/LocalTracklist/releases)

The APK is the installable app; the source code ZIP and TAR.GZ files are for developers.

## Features

- Incremental MediaStore library synchronization with optional Storage Access Framework folder import
- Incremental rescans for additions, changes, and removals, plus an on-demand full library rebuild
- Songs, albums, artists, playlists, favorites, and recently played
- Search across songs, albums, and artists
- Queue playback with shuffle and repeat
- Background playback through a Media3 `MediaSessionService`
- Lock-screen, notification, Bluetooth, and headset controls
- Persistent queue and playback-position restoration
- Local playlists stored with Room
- Audio details, artwork, theme, sorting, metadata fallback, and rescan settings

## Android Requirements

- Android 8.0 (API 26) or newer
- The project compiles and targets Android API 36
- Android SDK Platform 36 and a Java 21 runtime available to Gradle are required to build the project

## Permissions

LocalTracklist requests only the permissions needed for local playback:

- `READ_MEDIA_AUDIO`: reads audio indexed by MediaStore on Android 13 and newer.
- `READ_EXTERNAL_STORAGE`: reads local audio on Android 12L and older; the manifest limits this permission to API 32.
- `FOREGROUND_SERVICE`: keeps active playback running in a foreground service.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: identifies that foreground service as media playback on supported Android versions.
- `WAKE_LOCK`: lets active playback continue reliably while the screen is off.

Folder import uses Android's system document picker, which grants access only to locations selected by the user. LocalTracklist does not request `MANAGE_EXTERNAL_STORAGE` or `INTERNET`.

## Privacy

- No ads, usage analytics, remote telemetry, crash reporting, or user accounts
- No cloud sync
- No media or metadata uploaded off-device
- App backups are disabled
- Library data, playlists, favorites, settings, and playback state are stored locally

## Build and Verify on Windows

Prerequisites are Android Studio or the Android SDK command-line tools, Android SDK Platform 36, and a Java 21 runtime. Run these commands from the repository root in Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat assembleRelease --console=plain
git diff --check
```

Build artifacts are written to:

- Debug APK: `app\build\outputs\apk\debug\app-debug.apk`
- Unsigned release APK: `app\build\outputs\apk\release\app-release-unsigned.apk`

To build and install the debug variant on a connected device or emulator:

```powershell
.\gradlew.bat installDebug --console=plain
```

The release task intentionally produces an unsigned APK. A public release APK must be signed with a private release key before installation or distribution. Signing keys, passwords, aliases, and credential files must remain outside Git. See [RELEASE.md](RELEASE.md) for the release procedure.

## Architecture

LocalTracklist is a single-module Kotlin application using Jetpack Compose, Material 3, Media3/ExoPlayer, Room, DataStore, coroutines, and Flow. Playback uses Media3 and the codecs available through the Android platform, so format support can vary by Android version and device.

## Current Limitations

- Album and artist artwork uses a deterministic representative track and may not be canonical.
- Permission behavior and settings surfaces can vary between Android device manufacturers.
- Folder import is available, but there is no dedicated folder-browsing library view.
- The default MediaStore scan includes items marked as music with a duration of at least 30 seconds.
- Codec and container support depends on the Android platform and device.
- Streaming, remote libraries, lyrics, casting, and recommendations are outside the current scope.
- Future Room schema changes require explicit migrations; the current database configuration can recreate local app data during an incompatible schema upgrade.
- Automated coverage focuses on unit-tested logic rather than a broad instrumentation/UI suite.

## Contributing

Issues and pull requests are welcome. Keep the app local-first, permissions minimal, and changes within the offline music-player scope. Add tests for pure logic where practical.

## License

LocalTracklist is available under the MIT License. See [LICENSE](LICENSE).
