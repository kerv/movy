# Movy TV

An Android TV app that wraps the streaming site **https://www.movy.sx/** in a
full-screen WebView and makes it usable from a TV remote. It also runs on phones
and tablets.

## What it does

- **Full-screen WebView wrapper** around movy.sx, fit-to-screen scaled for TV.
- **D-pad / remote navigation** — geometric spatial focus with a visible focus
  ring, media key handling (play/pause, 10s seek), and native HTML5 fullscreen.
- **Ad / popup / "anti-bot" interstitial blocking** at both the network layer
  (`MainActivity.java`) and a cosmetic/structural layer (injected
  `app/src/main/assets/tv_enhance.js`).
- **Player control** — drives movy's in-page `#vp-shell` player directly
  (pause, resume, quality, subtitles, episode drawer) from the remote.
- **Phone support** — TV boxes stay landscape; phones browse portrait/landscape
  and rotate to landscape for playback.
- **Self-update** — on launch the app checks a small JSON manifest and, if a
  newer `versionCode` is published, downloads and installs the new APK.

## Project layout

| Thing | Path |
|---|---|
| Main activity (Java) | `app/src/main/java/sx/movy/tv/MainActivity.java` |
| Self-updater (Java) | `app/src/main/java/sx/movy/tv/Updater.java` |
| Injected page logic (JS) | `app/src/main/assets/tv_enhance.js` |
| Android manifest | `app/src/main/AndroidManifest.xml` |
| App gradle (version here) | `app/build.gradle` |
| Update manifest (published) | `release/version.json` |
| CI / deploy workflow | `.github/workflows/deploy.yml` |
| Browser regression tests | `tests/tv-navigation.cjs` |

- **App id / namespace:** `sx.movy.tv`
- **minSdk 21, target/compileSdk 34, Java 17**

## Building

Requires JDK 17 and the Android SDK (build-tools 34, platform android-34).
The Gradle wrapper is checked in, so you do not need Gradle installed.

```bash
# local.properties must point sdk.dir at your Android SDK, e.g.:
#   sdk.dir=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17

./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # release APK
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`
Release output: `app/build/outputs/apk/release/app-release.apk`

Validate the injected JS after editing it, and run the browser regression suite:

```bash
node --check app/src/main/assets/tv_enhance.js
npm test    # Playwright navigation/player regression tests
```

## Signing (important for self-update)

Android only lets an installed app self-update to a new APK **signed with the
same key**. To keep updates working, the release APK must always be signed with
one stable keystore.

`app/build.gradle` reads signing config from either:

1. a local `keystore.properties` file (not committed), or
2. environment variables (used by CI):
   `MOVY_KEYSTORE_PATH`, `MOVY_KEYSTORE_PASSWORD`, `MOVY_KEY_ALIAS`,
   `MOVY_KEY_PASSWORD`.

If neither is present, the release build falls back to the debug key so local
`assembleRelease` still succeeds (that fallback APK is not suitable for
distribution).

### Create the release keystore (one time)

```bash
keytool -genkeypair -v \
  -keystore movy-release.jks \
  -alias movy \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<store-pass>' -keypass '<key-pass>' \
  -dname "CN=Movy TV, O=movy, C=US"
```

Keep `movy-release.jks` safe and back it up — losing it means users can no
longer auto-update and must uninstall/reinstall.

### Local `keystore.properties`

```properties
storeFile=/absolute/path/to/movy-release.jks
storePassword=<store-pass>
keyAlias=movy
keyPassword=<key-pass>
```

## Continuous deployment (GitHub Pages)

`.github/workflows/deploy.yml` runs on every push to `main`:

1. Builds a signed release APK.
2. Publishes `movy.apk` and `version.json` to **GitHub Pages**.

Published URLs:

- APK: `https://kerv.github.io/movy/movy.apk`
- Manifest: `https://kerv.github.io/movy/version.json`

The in-app updater (`Updater.java`) points at these URLs.

### Required GitHub configuration

1. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
2. **Settings → Secrets and variables → Actions**, add:
   - `MOVY_KEYSTORE_BASE64` — the keystore, base64-encoded
     (`base64 -w0 movy-release.jks`)
   - `MOVY_KEYSTORE_PASSWORD`
   - `MOVY_KEY_ALIAS`
   - `MOVY_KEY_PASSWORD`

> **First switch to the GitHub-signed key:** any device that currently has a
> build signed with a *different* key (e.g. the old debug key) must uninstall
> and reinstall once, because the signatures differ. After that, all future
> auto-updates work.

## Releasing a new version

1. Edit code / JS. `node --check` the JS; run `npm test`.
2. Bump `versionCode` **and** `versionName` in `app/build.gradle`.
3. Update `release/version.json` to the same `versionCode` / `versionName`
   and add release `notes`. Keep `apkUrl` pointing at the Pages URL.
4. Commit and push to `main`. CI builds and publishes automatically.
5. On device: relaunch the app → it self-updates.

> `versionCode` in `app/build.gradle` and `release/version.json` **must** match
> and must increase for a new release, or the self-update won't trigger.

## Constraints & gotchas

- Keep `versionCode` in `app/build.gradle` and `release/version.json` in sync.
- Do not remove the `isProtected()` whitelist in `tv_enhance.js` or the player
  iframe gets removed as an "ad".
- Do not re-enable `setUseWideViewPort(true)` — it reintroduces layout overflow.
- Use a full JDK 17 (not a JRE) for builds.

See `HANDOFF.md` for detailed implementation history and open issues.
