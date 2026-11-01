# Publishing checklist

Order: GitHub release → F-Droid (main repo, from 2027-01-30) → Google Play.

## 1. GitHub release

Tag `v0.1.0`, attach the four `app-full-<abi>-release.apk` files from `app/build/outputs/apk/full/release/` (arm64-v8a, armeabi-v7a, x86_64, universal; signed with the release key). Version codes: release number × 10 + slot (universal 0, armeabi-v7a 1, arm64-v8a 2, x86_64 3); fastlane changelogs carry one file per code.
Signing certificate SHA-256: `AE:C6:44:AE:81:38:A6:FD:30:19:03:DD:15:3F:BC:18:10:65:E9:BE:24:53:73:B4:8E:C5:D1:E6:C6:53:2B:75`

## 2. IzzyOnDroid: not eligible

Its [inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) rejects apps whose code was written with generative AI, and the 52 MB APK is over its 30 MB limit. Do not submit without honest disclosure.

## 3. F-Droid main repository

Not before 2027-01-30: Quality point 6 of the inclusion policy asks for 3 months of active development unless the app has proven users. First commit was 2026-10-30. Disclosing AI assistance is encouraged.


F-Droid builds every app from source, including native libraries. `dev.jdtech.mpv:libmpv` is a prebuilt AAR, so
the recipe must build libmpv and FFmpeg from source (as mpv-android's recipe does in fdroiddata). Steps:
1. Fork https://gitlab.com/fdroid/fdroiddata, add `metadata/dev.altayeb.shoebill.yml` with a `srclibs` entry for
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
- Privacy policy URL: https://github.com/altyb/shoebill/blob/main/PRIVACY.md
- Category: Video Players & Editors. Contains ads: No. Target audience: 18+ (vault feature).

**Data safety form**
- Data collected: **No.** Data shared: **No.**
  (Subtitle searches go to the subtitle service the user chose, at the user's request, with the user's own API
  key; nothing is sent to the developer.)
- Data encrypted in transit: Yes (HTTPS to subtitle services).
- Users can request deletion: not applicable (no data collected); uninstalling removes all app data.

**Permissions declarations**
- No `MANAGE_EXTERNAL_STORAGE` in the Play build, so no "All files access" declaration is needed.
