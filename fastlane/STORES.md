# Publishing checklist

Order: GitHub release → IzzyOnDroid → F-Droid (main repo) → Google Play.

## 1. GitHub release

Tag `v0.1.0`, attach `app-full-release.apk` (arm64-v8a + armeabi-v7a, signed with the release key).
Signing certificate SHA-256: `AE:C6:44:AE:81:38:A6:FD:30:19:03:DD:15:3F:BC:18:10:65:E9:BE:24:53:73:B4:8E:C5:D1:E6:C6:53:2B:75`

## 2. IzzyOnDroid (F-Droid client, accepts prebuilt libraries)

Open an issue at https://gitlab.com/IzzyOnDroid/repo/-/issues with:

> **App:** Anon Player — https://github.com/altyb/anon-player
> **License:** GPL-3.0-or-later
> **Description:** Private video player on libmpv, with an encrypted vault (AES-256-GCM, Android Keystore),
> decoy/wipe PIN, subtitle search and styling. No ads, no trackers, no analytics.
> **APK:** attached to GitHub releases (`app-full-release.apk`), fastlane metadata in the repo.
> **Anti-features:** none known. Network use: subtitle search (OpenSubtitles, SubDL, Jimaku) with the user's own
> API keys, and network links the user enters.

Izzy's scanner checks the APK for trackers and proprietary libraries; there are none (no Google Play Services,
no Firebase).

## 3. F-Droid main repository

F-Droid builds every app from source, including native libraries. `dev.jdtech.mpv:libmpv` is a prebuilt AAR, so
the recipe must build libmpv and FFmpeg from source (as mpv-android's recipe does in fdroiddata). Steps:
1. Fork https://gitlab.com/fdroid/fdroiddata, add `metadata/app.anon.player.yml` with a `srclibs` entry for
   libmpv-android and its build scripts.
2. Open a merge request. Expect review rounds; this is the slowest store.

## 4. Google Play

- Build: `./gradlew bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab`.
- Use Play App Signing; upload the AAB signed with the upload key above.
- A new personal developer account ($25) must run a **closed test with at least 12 testers for 14 days** before
  production access.
- Store listing: title, short and full description from `fastlane/metadata/android/en-US/` (and `ar/`);
  icon `fastlane/metadata/android/en-US/images/icon.png` (512×512); screenshots in `images/phoneScreenshots/`;
  a 1024×500 feature graphic is still needed.
- Privacy policy URL: https://github.com/altyb/anon-player/blob/main/PRIVACY.md
- Category: Video Players & Editors. Contains ads: No. Target audience: 18+ (vault feature).

**Data safety form**
- Data collected: **No.** Data shared: **No.**
  (Subtitle searches go to the subtitle service the user chose, at the user's request, with the user's own API
  key; nothing is sent to the developer.)
- Data encrypted in transit: Yes (HTTPS to subtitle services).
- Users can request deletion: not applicable (no data collected); uninstalling removes all app data.

**Permissions declarations**
- No `MANAGE_EXTERNAL_STORAGE` in the Play build, so no "All files access" declaration is needed.
