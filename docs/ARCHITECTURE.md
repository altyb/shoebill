# Architecture

Shoebill is a single-activity Jetpack Compose app. Playback is [mpv](https://mpv.io) through libmpv-android; music
uses AndroidX Media3; the vault uses Tink. Kotlin throughout, `minSdk 26`.

## Where things live

All code is under `app/src/main/java/dev/altayeb/shoebill/` unless noted.

| Package | What it does |
|---|---|
| `player/` | The video player: mpv wrapper (`MpvPlayer`), screen, gestures, panels, subtitle style, picture-in-picture, casting (`cast/`). |
| `library/` | Home screen and the library: folders, videos, search, network sources (SMB, WebDAV, SFTP, FTP), playlists. |
| `music/` | Music tab, now-playing page, the Media3 service, home-screen widget and Quick Settings tile. |
| `vault/` | The encrypted vault: PIN and lockout, file encryption (`crypto/`), gallery import and return, backup and restore. |
| `subs/` | Subtitle search (OpenSubtitles, SubDL, Jimaku), charset detection, automatic sync. |
| `settings/` | Settings storage (`AppSettings`) and the settings screens. |
| `ui/`, `data/` | Shared Compose pieces; small data helpers. |
| `app/src/full/.../download/` | The downloader (yt-dlp). Only in the `full` flavor. |

## Two flavors

- `full` is the GitHub and F-Droid build. It has the downloader and asks for "All files access".
- `play` is the Google Play build. `app/src/play/` holds a stub for the downloader, and moving files into the vault
  shows Android's delete dialog.

Code that must differ goes in `src/full` or `src/play`; everything else is in `src/main`.

## Tests

The logic that matters is kept free of Android classes, in `logic/` and `crypto/` packages. Three small Gradle
projects under `tests/` compile exactly those folders and run plain JVM tests:

| Folder | Covers |
|---|---|
| `tests/vault` | Vault crypto, PIN handling, backup format. |
| `tests/subs` | Subtitle search results and parsing. |
| `tests/library` | Library logic, casting, downloader logic, player logic. |

```
cd tests/vault && ./gradlew test      # likewise tests/subs and tests/library
```

`tests/e2e/` holds Python scripts that drive a real phone or emulator over adb and check the player, music,
notifications and deep links. They need the debug build installed (`./gradlew installFullDebug`). CI runs the library
tests and a debug build of both flavors.

## Things to know before changing code

- Vault crypto labels and the `.shoebillvault` format must never be renamed: old backups have to keep restoring.
- Nothing decrypted from the vault is written to disk, and vault files can never be cast.
- The app ID is `dev.altayeb.shoebill`. Changing it, or the signing key, stops installed copies from updating.
