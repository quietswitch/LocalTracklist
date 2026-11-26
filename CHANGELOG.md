# Changelog

## 1.2.1 (versionCode 8) — Maintenance release candidate

- Keep search text, caret, selection and keyboard composition independent of asynchronous library-search results.
- Show only results for the current query; preserve existing search matching, sorting and navigation.
- Add focused regression coverage for search editor state, rapid query changes and clearing.

## 1.2.0 (versionCode 7) — Published as LocalTracklist

- First public LocalTracklist release, including adaptive Now Playing layout, embedded album art in media sessions, and verified library-refresh corrections.
- Preserves the existing Android package and permanent release signing identity.

### LocalTracklist rebrand

- Renames current public branding to LocalTracklist, with the descriptor "Local music player for Android." and publisher/developer quietswitch.
- The rebrand was reviewed at version 1.1.0/code 6 before the separately versioned 1.2.0 publication, preserving `com.libreplayer`, existing data and signing identities, and application behavior.

## 1.1.0 (versionCode 6) — Published as LibrePlayer

- Builds on the public 1.0.4 release with the 28 post-release development commits that established the current development line.
- Adds reusable performance authority, deterministic fixtures, benchmark infrastructure, and an accepted packaged Baseline Profile.
- Hardens playback semantics across queue behavior, seeking, track transitions, audio focus, background/system controls, output switching, failure recovery, and bounded long-session testing.
- Establishes deterministic library identity, grouping, ordering, search, metadata-pathology, and artwork semantics.
- Adds M3U/M3U8 playlist import and export through local-only Android document access.
- Adds source-scoped library provenance and an explicit non-destructive Room v1-to-v2 migration while preserving established Song IDs and stored user references where provenance can be proven.
- Removes the top-level Songs destination, makes Albums the default library destination, and focuses artist detail on albums.
- Retains LibrePlayer's offline/local-first posture with no Internet permission, accounts, analytics, or telemetry.
- Published as LibrePlayer `v1.1.0` from commit `28d9b63c2960560f62fb2fe45937a6c0956c2d13` using the existing permanent release key. Deferred local Q3.7 removable-volume/LARGE instrumentation is excluded from this release tree and must be revised before future use because it targets the removed Songs destination.

## 1.0.4 (versionCode 5) — 2026-08-24

- Provides offline, local-first library browsing and playback for device audio.
- Includes songs, albums, artists, playlists, favorites, history, search, queue controls, background playback, and playback-state restoration.
- Adds incremental MediaStore and optional SAF library synchronization for additions, changes, and removals, with periodic and on-demand full reconciliation.
- Fixes SAF rescans so unchanged imported files can reuse cached metadata without dropping imported songs.
- Preserves cached library data when media permission is temporarily unavailable.
- Improves restored-file playback recovery, playback error reporting, and playback snapshot restoration.
- Repairs release blockers affecting Android 8–9 MediaStore compatibility, backup exclusions, menu actions, and playlist-delete confirmation.
- Removes an unused dependency-contributed network-state permission; LibrePlayer does not request Internet access.
- Includes audio details, artwork handling, local settings, and debug-only playback-pipeline diagnostics; release playback remains on Media3's stable default output path.
- Improves library deduplication and permission recovery behavior.
- Corrects the multiline Now Playing layout so longer metadata is presented consistently.
- Adds release, privacy, permission, build, and verification documentation for pseudonymous distribution.
