# ScreenHop – notes for Claude

Android app (Kotlin, Gradle) for the AYN Thor: switches controller focus between the two
screens and moves apps from one screen to the other. See `README.md` for how it works.

## Versioning and releases

- The version lives in `app/build.gradle.kts`: `versionName` (semver, e.g. `1.4.1`) and
  `versionCode` (integer). Always bump both together.
- The GitHub workflow (`.github/workflows/build.yml`) builds on every push and publishes a
  release tagged `v<versionName>`, using `RELEASE_NOTES.md` as the description. If that
  version already has a release, it is replaced.
- **Every major version gets a new version tag**: when a change is a new feature or
  otherwise user-visible milestone, increase `versionName` (minor/major) and `versionCode`
  and rewrite `RELEASE_NOTES.md` for it, so it gets its own tag and release. Small fixes
  bump the patch number. Pure docs/chore commits don't need a bump.
- Ask the user if it is unclear whether a change warrants a new version.

## Other notes

- Ideas and open issues go in `inbox.md`; the user dumps things there. Check it for context
  and remove or mark items when they are done.
- The signing key `app/screenhop.keystore` is intentionally committed so that every build
  installs over the previous one. Don't regenerate it.
- Keep it dependency-free: no root, no internet permission, no hidden APIs.
