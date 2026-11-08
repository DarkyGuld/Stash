# Release and upstream updates

## This release

- Package: `com.stash.app`
- Version: `0.9.130` (versionCode 160)
- Signing key: `stash-release` (not the debug key)
- In-app updater: `https://github.com/DarkyGuld/Stash/releases/latest`
- APK: `app/build/outputs/apk/release/app-release.apk`
- Release page: `https://github.com/DarkyGuld/Stash/releases/tag/v0.9.130`

The official Stash APK and the old `com.stash.app.debug` build use different
signing keys. Uninstall them before installing this release; app data is not
migrated. After installing, sign in to Spotify and add the Eclipse addon again.

## Signing (keep this safe)

The release key is **not** in the repository:

- Keystore: `/home/adrian/.stash-release.jks`
- `keystore.properties` in the repo root (gitignored): `storeFile`,
  `storePassword`, `keyAlias=stash-release`, `keyPassword`

Back up both. Every future release must be signed with the same key, or the
in-app updater and Android will reject the update. If the key is lost, users
must uninstall and reinstall (data loss).

Build:

```bash
JAVA_HOME=/home/adrian/.local/jdk17 \
  ./gradlew :app:assembleRelease --max-workers=1
```

Verify:

```bash
aapt dump badging app/build/outputs/apk/release/app-release.apk | head -3
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Publishing an update

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build `:app:assembleRelease`.
3. Publish a normal (not draft/pre-release) GitHub release on
   `DarkyGuld/Stash` with the APK asset:

```bash
gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk \
  --repo DarkyGuld/Stash --title "Stash X.Y.Z (Eclipse)" --notes "..."
```

The updater reads `releases/latest`, picks the first `.apk` asset, verifies its
signature against the installed app, and installs it.

## Updating alongside upstream

- `origin` = `https://github.com/rawnaldclark/Stash.git` (upstream)
- `fork` = `https://github.com/DarkyGuld/Stash.git` (our releases)

Merge upstream changes into `feat/eclipse-addons`:

```bash
git fetch origin
git merge origin/master
# resolve conflicts, run the targeted tests and assembleDebug/assembleRelease
```

Then bump the version, build, and publish a new fork release as above. Keep the
Eclipse-specific commits in `feat/eclipse-addons`; do not rewrite the release
key or the updater URLs.
