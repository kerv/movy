# Movy TV — native Android TV

A Java Android app (`sx.movy.tv`) with native home, search, title details,
episodes, account screens, and Media3 playback. Movy supplies current catalogue
metadata and resolves a stream only after Play is selected.

## Current behavior

- Artwork-backed home and title pages; readable cards, scores, runtime, age
  ratings, genres, cast, recommendations, and season/episode selection.
- Search automatically runs after a 650 ms typing pause (two or more characters).
  Manual search also works. Older requests cannot replace newer search results.
- Provider shortcuts offer Popular and Recently Released views. Load more
  appends unique cards, displays counts, and moves focus/scroll to the first new
  title. Partial request failures are retried without silently skipping a page.
  Popular is the metadata popularity ranking, not a weekly viewing chart.
  Recent sorts original movie releases and first series premieres newest first,
  excluding future/unknown dates; it does not mean newly added or new episodes.
  Movie availability uses the US region; series use Movy's network filters.
- Subtle focus scaling, screen fades, smooth scrolling, and a centered
  artwork-backed loading screen with source status and Cancel.
- Native Movy sign-in, sign-out, profile selection/PIN verification, account
  watchlists, and account Continue Watching. Login uses Movy's existing backend;
  its JWT stays in browser-origin local storage. Only account metadata reaches
  the native interface. Existing sessions at `https://www.movy.sx` are reused.
- Device-local My List and playback progress remain available separately.
  Native progress is not currently written back to the Movy account.
- Native video playback, resume, next episode, source quality choices, audio
  selection, caption selection, speed, and remote media keys.
- External VTT/SRT subtitle files advertised by Movy are merged with HLS using
  Media3's `DefaultMediaSourceFactory`; both CC and the toolbar subtitle menu
  expose the available tracks. If browser metadata is missing, native playback
  searches Movy's `subtitles.vidy.st` service with the exact title/episode ID.
  CC stays clickable during lookup, failures can be retried, and `/download`
  files use their advertised SRT/VTT format. Up to three release alternatives per
  language are offered; the selected edition is matched by track ID, not just
  language. Alternate release captions may have different timing.
  Languages get readable labels. Sources with only
  original audio do not invent extra language choices.
- Native launcher checks `https://kerv.github.io/movy/version.json` for updates.

## Browser connection boundaries

There is no visible website wrapper or site-player fallback. The old
`MainActivity`, enhancement script, and wrapper-only navigation tests have been
removed. Do not reintroduce a web browsing surface as a recovery path.

Two internal browser connections remain:

1. **Stream resolution:** a short-lived hidden WebView loads the selected title's
   normal `?play=true` route. It observes the browser's advertised source and
   subtitle URLs and playlist requests, chooses the highest advertised source,
   and forwards the actual User-Agent, Referer, Origin, and required headers to
   Media3. It has a 45-second deadline and supports cancellation. Browsing does
   not resolve media. Playlist/HTTP errors can trigger one source refresh.
2. **Account access:** a blank local document with Movy's base origin reads the
   existing JWT/profile state and calls the same account backend as the site.
   It renders no site UI. The native interface sends only fixed account actions;
   an origin-restricted bridge returns metadata, never the JWT or password.

The user has authorized access to their Movy source material. Keep media
resolution on demand; do not build a local media scraper, downloader, Plex,
rclone, or FUSE workflow. The `plex/` experiment is ignored by Git.

## Main files

| Purpose | Path |
| --- | --- |
| Native browse, account, and loading UI | `app/src/main/java/sx/movy/tv/NativeActivity.java` |
| Catalogue and provider requests | `app/src/main/java/sx/movy/tv/CatalogClient.java` |
| Account connection | `app/src/main/java/sx/movy/tv/AccountClient.java` |
| Account browser adapter | `app/src/main/assets/native_account.js` |
| Stream resolver | `app/src/main/java/sx/movy/tv/StreamResolver.java` |
| Source/subtitle browser observer | `app/src/main/assets/native_resolver.js` |
| Caption/source metadata | `app/src/main/java/sx/movy/tv/PlaybackTracks.java` |
| Native caption lookup fallback | `app/src/main/java/sx/movy/tv/SubtitleLookup.java` |
| Native Media3 player | `app/src/main/java/sx/movy/tv/PlayerActivity.java` |
| Local resume and saved titles | `app/src/main/java/sx/movy/tv/PlaybackStore.java` |
| Self-updater | `app/src/main/java/sx/movy/tv/Updater.java` |
| Release/update manifest | `release/version.json` |

## Build and validation

Java 17, Android compile/target SDK 34, minSdk 21, Gradle 8.7. `local.properties`
must point to the Android SDK. Preserve the package ID and stable release signing
key so installed devices can update in place.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
node --check app/src/main/assets/native_resolver.js
node --check app/src/main/assets/native_account.js
npm test
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Robolectric checks native loading/layout, search delay/cancellation, local resume,
and actual Media3 subtitle merging. Browser fixtures check the resolver bridge
and account login, profile scoping/PIN denial, list/resume normalization, logout,
and credential isolation. Fixtures do not prove a real signed-in account works;
that and TV caption rendering/remote behavior still require device validation.

Live browser checks have verified movie/episode HLS playlists and caption files
with the site's headers. Do not equate a valid playlist with proof of hardware
decoding on every Android TV device.

## Releases

Bump `versionCode`/`versionName` in `app/build.gradle` and matching fields in
`release/version.json`. A push to `main` runs Android unit tests and lint, builds
the signed release APK, publishes GitHub Pages, and creates a GitHub Release.

CI uses the existing `MOVY_KEYSTORE_BASE64`, `MOVY_KEYSTORE_PASSWORD`,
`MOVY_KEY_ALIAS`, and `MOVY_KEY_PASSWORD` secrets. Never replace the signing key.
Local release builds without a configured keystore fall back to a debug key and
are not suitable for distribution to release-signed installations.

Version 1.12 missed update checking in its new launcher; devices on that version
need one manual installation of a newer signed release. Later native versions
check the manifest again. `HANDOFF.md` retains historical wrapper-era notes.
