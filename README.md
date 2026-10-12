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

The app (`sx.movy.tv`) now launches a native Android interface. Version 1.13 adds
artwork-backed home/detail pages, movie/series filters, multiline title cards,
ratings, runtime, age ratings, genres, cast, episode selection, and similar titles.
Provider rows use the same metadata requests as Movy's provider selector.

Play opens a bounded browser resolver on the title's `?play=true` watch route.
It selects the highest advertised quality, forwards the actual request headers,
and hands playback to Media3. Loading can be cancelled and failures show retry
options. Native playback includes resume, next episode, track selection, speed,
and one automatic source refresh after applicable playlist/HTTP failures.

Continue Watching and My List currently store activity on this device. Movy
account login, profiles, and account lists remain in the preserved site interface.
Forwarding cookies alone does **not** provide account sync: Movy uses a JWT and
active-profile state in WebView local storage. Do not claim native account sync
until that adapter and native progress writes have been implemented and verified.

Native UI/loading regressions are checked using Robolectric; the resolver quality
script has browser regressions. Live Chromium verified a movie's 2160p HLS request
and the season/episode route. Physical Android TV playback still needs confirmation.

Key files:

| Purpose | Path |
| --- | --- |
| Native home/details/episodes | `app/src/main/java/sx/movy/tv/NativeActivity.java` |
| Movy metadata client | `app/src/main/java/sx/movy/tv/CatalogClient.java` |
| On-demand source resolver | `app/src/main/java/sx/movy/tv/StreamResolver.java` |
| Native Media3 player | `app/src/main/java/sx/movy/tv/PlayerActivity.java` |
| Device-local resume/saved titles | `app/src/main/java/sx/movy/tv/PlaybackStore.java` |
| Account/site fallback | `app/src/main/java/sx/movy/tv/MainActivity.java` |
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
for supported titles. The local Plex experiment is ignored by Git; do not revive
its rclone/FUSE design.

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
./gradlew :app:testDebugUnitTest
node --check app/src/main/assets/tv_enhance.js
npm test
```

For any release, bump both `versionCode`/`versionName` in `app/build.gradle`
and the matching values in `release/version.json`. See `HANDOFF.md` for the
existing signing and updater details.
