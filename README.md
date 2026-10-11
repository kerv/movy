# Movy TV — native Android TV rebuild

## Goal

Replace the current Movy.sx WebView wrapper with a remote-first, native Android
TV experience similar to a streaming-service app:

- D-pad-friendly home rows, search, details, seasons, episodes, and similar titles
- Native playback with AndroidX Media3 / ExoPlayer
- Resolve a short-lived HLS source only when the user selects **Play**
- Prefer the highest source rendition available, including 4K where supported
- No Plex, rclone, FUSE mount, local-media library, or pre-download workflow

The user has authorized access to their Movy source material. Keep source
resolution on demand; do not build a full local media scraper or downloader.

## Current application

The existing app (`sx.movy.tv`) is a Java Android app that wraps
`https://www.movy.sx/` in a full-screen WebView. It has extensive injected
JavaScript for remote navigation, player control, popup blocking, and a
self-updater. It works, but remains website-shaped and is clunky on a TV remote.

Key files:

| Purpose | Path |
| --- | --- |
| Main activity | `app/src/main/java/sx/movy/tv/MainActivity.java` |
| Updater | `app/src/main/java/sx/movy/tv/Updater.java` |
| Existing WebView remote logic | `app/src/main/assets/tv_enhance.js` |
| Manifest | `app/src/main/AndroidManifest.xml` |
| Android app build/version | `app/build.gradle` |
| Release manifest | `release/version.json` |
| Detailed historical notes | `HANDOFF.md` |

The current project uses Java 17, Android SDK 34, minSdk 21, and already has
Leanback and AndroidX WebKit dependencies. Preserve the package ID and stable
signing key so installed devices can update in place.

## Recommended architecture

1. **Native UI**: use Leanback or Compose for TV (choose one consistently) for
   browse rows, search, detail pages, and episode selection. Every interactive
   item needs deterministic D-pad focus and Back behavior.
2. **Catalog client**: call Movy’s public metadata/catalog APIs for search,
   trending, title details, and recommendations. Cache metadata only.
3. **Resolver**: on Play, load the normal Movy title page in a tightly scoped,
   hidden resolver WebView (or an equivalent browser-compatible resolver),
   capture the HLS playlist request, then choose the highest advertised rung.
   Do not resolve or fetch media while browsing.
4. **Player**: feed the resolved HLS URL and required User-Agent/Referer headers
   to Media3 ExoPlayer. Preserve audio/subtitle/quality selection using native
   controls where possible.
5. **Resilience**: HLS URLs may expire. On a 401/403/playlist failure, resolve
   again once and retry the current position; surface a useful native error if it
   still fails.

The source was previously verified to offer valid fMP4 HLS with 4K H.264/AAC
for supported titles. The failed Plex experiment is documented separately in
`plex/README.md`; do not revive its rclone/FUSE design.

## First milestone

Deliver a native vertical slice before replacing the wrapper:

1. Native TV home screen with one metadata row and D-pad focus.
2. Native search results and a native title detail page.
3. One native Play button that resolves an explicit test title only when pressed.
4. ExoPlayer starts the HLS stream, with Back returning to the detail page.
5. Build, install, and validate with a physical Android TV remote.

Only after that succeeds should the native catalog replace the WebView home
screen. Keep the old WebView implementation available behind a temporary
fallback flag until native playback is proven on-device.

## Validation and release

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
node --check app/src/main/assets/tv_enhance.js
npm test
```

For any release, bump both `versionCode`/`versionName` in `app/build.gradle`
and the matching values in `release/version.json`. See `HANDOFF.md` for the
existing signing and updater details.
