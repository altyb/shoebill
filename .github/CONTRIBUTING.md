# Contributing to Shoebill

Bug reports and fixes are welcome. Open an issue first for anything large. See [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for where things live.

- **Bugs:** say your phone, Android version and app version, and what you did.
- **Translations:** copy `app/src/main/res/values/strings.xml` to `values-<language>/strings.xml`, translate it, and open a pull request. Store text is in `fastlane/metadata/android/`.
- **Code:** keep it small, match the code around it, and run `cd tests/vault && ./gradlew test` before you send it.
- **Security problems:** do not open a public issue. Use GitHub's private "Report a vulnerability" on the Security tab.

Contributions are accepted under the project's licence (GPL-3.0-or-later).
