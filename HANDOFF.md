# HANDOFF — Movy TV (Android TV WebView wrapper for movy.sx)

Last updated: 2026-09-14. Read the current findings below before the historical notes.

## Current build: v1.9 (versionCode 10), published 2026-09-14

`app/build.gradle`, `release/version.json` and both public artifacts are v1.9 /
code 10. The APK and update manifest were fetched back over HTTPS and verified
byte-for-byte against the local build; the served APK reports versionCode 10 and
the same debug signing certificate as v1.8
(`d7453e8463fdfb6c89c1e37958be40007dd2f5e3e93bdb3fd8bf36793a496f40`), so
self-update from v1.8 works. The previous public v1.8 APK and manifest are backed
up at `release/rollback-v1.8-scsBA8lm/`.

Three reported problems were addressed.

**One remote press now pauses.** The reporter needed three: one to reveal the
controls, one that appeared to do nothing, and one that finally paused. Two
defects combined. `media()` derived the next state from a `pauseRequested` latch
rather than from the video, so once a request failed to land the latch was
inverted and the following press cancelled the phantom pause instead of pausing.
And a request was handed to `#ButtonPlay` (or parked waiting for it to mount)
with no check that the video ever moved. `media()` now reads `v.paused` plus any
request still in flight, and `applyPlayback()` verifies the outcome 350 ms later,
falling back to the video element directly when the site never acted. The site's
own control is still preferred so React's icon stays synchronized; the direct
fallback only runs when the alternative is a press that does nothing.

**Phones can browse in portrait.** The manifest no longer pins the activity to
landscape. `MainActivity` decides at runtime: TV boxes stay `LANDSCAPE`, everything
else browses with `FULL_USER` and switches to `SENSOR_LANDSCAPE` while the watch
surface is up, then releases it. The page reports playback through an origin
restricted `WebViewCompat.addWebMessageListener` bridge named `movyTvBridge`
(same trusted origin set as the injected script — do not widen it to arbitrary
origins), and native HTML5 fullscreen sets the same flag. `player()` also had to
change: it required the video to cover 35% of the viewport height, which a 16:9
player never does on a portrait phone, so the watch surface was not recognized at
all. It now falls back to the element's own aspect when the viewport is taller
than the video.

**Update installs hand off reliably.** `launchInstaller` ran on the IO thread and
swallowed every failure, so a download could finish with nothing happening. The
hand-off now runs on the main thread, is deferred and retried from `onResume` when
the activity is not foreground (Android blocks background activity starts), falls
back from `ACTION_VIEW` to `ACTION_INSTALL_PACKAGE`, pre-grants URI read access to
the resolved installer packages, and surfaces an "Install now" retry dialog when
it still cannot start. The `<queries>` element in the manifest keeps the installer
resolvable under Android 11+ package visibility. Separately, being sent to the
install-unknown-apps settings screen used to discard the update entirely; the
request is now remembered and the download resumes on return.

Validation: `npm test` (a new section covers a portrait viewport, the playback
bridge, and a site that drops or never mounts its play control — it fails against
the previous script), `node --check`, `:app:assembleDebug`, `:app:lintDebug`
(only the pre-existing icon/dependency/cleartext warnings). No physical TV or
phone was connected; the remote's real key codes, the rotation behavior and the
installer hand-off still need on-device confirmation.

## Previous build: v1.8 (versionCode 9), published 2026-09-13

Public APK and update manifest verified byte-for-byte over HTTPS. Previous v1.7
artifacts are backed up at `release/rollback-v1.7-cPcp0KkN/`.

v1.8 fixes the actual mobile-layout idle pause failure. The live mobile player
unmounts `#ButtonPlay` and skip controls when idle. CSS cannot reveal missing nodes.
The previous mousemove on `.vp-container` never reached the React listener on its
descendant video surface. `showControls()` now dispatches mousemove and touchmove
from the video, which makes the site mount its controls; pending pause then uses
the real button handler and focuses Resume. Live mobile Chromium reproduced the
missing button and verified pause, pinned controls, and resume with this fix.

The site's seek surface now has slider semantics and remote focus. Left/Right
seek 10 seconds and retain focus; Up/Down navigate away. Starting quality selects
the highest numeric resolution available in the current server's quality menu,
including 4K/8K labels. Later manual quality selections are respected for the show.
Automatic selection tries each newly higher resolution once, avoiding retry loops.

Validation: navigation regressions, Android build/lint, and live mobile player
verification. No physical TV was connected. Prior release notes follow.

## Previous build: v1.6 (versionCode 7), published 2026-09-13

The user manually compiled/published the earlier changes. At the start of
this follow-up, app/build.gradle and the public update manifest were v1.5 /
code 6; the checked-in release/version.json was still v1.4 / code 5. Both
local version files now match v1.6 / code 7. The public APK and update
manifest are updated and were fetched over HTTPS and verified byte-for-byte
against the local artifacts. APK: `app/build/outputs/apk/debug/app-debug.apk`;
manifest: `release/version.json`. The previous public v1.5 files are backed
up at `release/rollback-v1.5-1rwkePIq/`.

The v1.4 card focus frame and checked title badge are retained. The user's
robot-ad fix is confirmed working on their TV.

Player fixes in v1.6:
- Remove the broad player-control ID/class guessing fallback introduced in
  the manually edited v1.5. The real Quality and Audio & Subtitles buttons
  have generic classes and no descriptive IDs/aria-labels. That fallback
  incorrectly made OK on either one toggle playback instead of opening it.
- Play/pause now uses the site's `#ButtonPlay` handler when available, keeping
  React paused state and the icon synchronized with the video. A direct
  video.pause() alone left the pause icon unchanged in the live repro.
- Controls stay visible while paused; visibility uses an explicit DOM state,
  not an expiring timestamp that could disagree with the rendered controls.
- Idle OK pauses and targets the play button. If it has not mounted, wait for
  it; never focus the first player candidate (Back). Restore focus when that
  button is replaced. Do not run catalogue autofocus inside a loading player.
- Preserve the requested pause across source changes, including temporary
  video removal/resizing. The watch shell's lifetime is distinct from an
  individual video node's lifetime. The site autoplays new source loads; the
  app reapplies pause until the user explicitly resumes.
- Resolution options close their menu after activation and restore its
  trigger. Match nested text such as `720pHD` as well as `720p`. Back closes
  quality/audio/subtitle popovers and returns focus to their trigger.
- Back with paused controls visible leaves the watch surface (after closing
  any menu), rather than hiding the controls on a paused video.

Validation: live Chromium with Android TV UA covered fade → pause → idle
while paused → Quality → 720p selection/auto-close → Audio & Subtitles →
Back → Resume. The video stayed paused through all menu changes and resumed
with the proper icon. Deterministic tests include generic/unlabeled menu
buttons, source-change autoplay and temporary removal, waiting beyond fade
timers, delayed/replaced play controls, and the previous navigation suite.
`npm test`, JS syntax, Android assembleDebug and lintDebug passed (existing
lint warnings remain). Physical TV behavior still needs user verification.

## Earlier v1.3 findings (historical release status below)

### v1.3 changes

**Published build:** versionCode 4, versionName 1.3, published on 2026-09-12
with user approval. Public APK and update manifest were fetched over HTTPS
and verified byte-for-byte against the local release artifacts. Manifest:
`release/version.json`. Previous public v1.2 APK and manifest are backed up at
`release/rollback-v1.2-HWfwsOPo/`.
The APK uses the same debug signing certificate as the existing public APK;
do not change signing keys during this update.

The original iframe assumption was incorrect for the current movie/TV player.
Live Chromium inspection found `#vp-shell .vp-container video` directly in the
Movy document, plus real quality/server/subtitle buttons. Details pages also
have large background trailer videos, so only `#vp-shell video` counts as the
watch player. Closing the watch surface can leave `?play=true` in the URL;
use DOM state, not that query parameter, to identify playback.

The exact reported QR prompt was reproduced with an Android TV user agent:
`https://fl.fasolacaymans.com/rvJkhNP7AWpZomF6/wOXvr` creates a full-screen
iframe **with no src**, rendering "Confirm you're not a robot" and
"Scan the qr-code with your phone" in its same-origin document. The iframe
has z-index 2147483646 and a timed close button. This is an advertisement,
not a verification requirement of the movie player.

The site has its own Ads switch, stored as sessionStorage
`ads-enabled-session=false`. v1.3 sets it at document start, blocks
`fasolacaymans.com` at the network layer, and recognizes the exact paired QR
messages inside blank frames as a fallback. It no longer deletes arbitrary
white dialogs, popup classes, captcha/challenge elements or large iframes.
`isProtected()` still preserves video/player elements.

`WebViewCompat.addDocumentStartJavaScript` is installed before load/restore,
restricted to HTTPS Movy and Vidy origins. Older WebViews get a top-document
onPageFinished fallback and an update-WebView toast; network blocking works
independently. Do not inject a JavaScript/native bridge into arbitrary origins.
API reference: https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set)

Navigation changes:
- No recurring player autofocus, and OK activates the selected control.
- Both keydown and keyup are consumed for D-pad navigation. The website's
  player shortcuts run on keyup; previously moving focus also sought or
  changed volume.
- Hidden ancestors, disabled controls and nested link/button duplicates are
  excluded. Carousel items and below-fold content can scroll into view.
- Focus recovers when virtualized catalogue rows replace a card.
- The TV episode drawer (`#vp-shell [class~="z-[110]"]`) gets its own focus
  scope. Its clickable div episode cards get tabindex/role; Back uses the
  drawer's Close button. This selector was verified against the live DOM.
- Text input keeps cursor keys and native input behavior.
- While watching, OK toggles playback when controls are hidden; an arrow
  reveals controls. Arrows then move focus; OK selects the focused control.
- Controls stay visible while paused or in a menu and fade after inactivity
  during playback. No repeated focus stealing.
- Back closes a popup, then hides remote controls, then leaves the player.
  Native fullscreen exits first; ordinary web history follows afterward.
- Media Play/Pause are idempotent; rewind/forward seek 10 seconds with bounds.
  Native media dispatch is handled once, without recursively redispatching.
- Menu opens Home / Reload page / Cancel. Popups are rejected through
  onCreateWindow; external main-frame navigation cannot replace Movy.

The fit fix still uses `setUseWideViewPort(false)` and overflow containment.
The old observer repeatedly rewrote the root transform while observing those
same style changes; that feedback loop and carousel-driven scaling are gone.
Live player bounds matched 1280×720 and 960×540 viewports.

Verification:
- `node --check app/src/main/assets/tv_enhance.js`
- `npm ci`, `npx playwright install chromium`, `npm test` for deterministic
  browser regressions. An existing browser can be selected with
  `PLAYWRIGHT_CHROMIUM_EXECUTABLE=/absolute/path/to/chrome npm test`.
- Build and lint using the environment/Gradle command below, adding
  `:app:lintDebug`. Lint has existing manifest/dependency/icon warnings.
- Live browser checks covered ad absence beyond its original delay, home
  navigation, carousel/vertical scrolling, search input/results, player OK,
  quality selection (1080p → 720p), subtitle menus, episode drawer navigation
  and Back to details.
- No Android TV/ADB device was connected. Browser checks do not verify the
  physical remote, Android fullscreen callbacks, codecs, or installer UI.
  Alternate third-party/anime embeds are not verified as equivalent to the
  current movie/TV player.

## Historical handoff (v1.2; retained for setup and updater context)

## 1. What this project is

An Android TV app that wraps the streaming website **https://www.movy.sx/** in a
full-screen WebView, with:
- ad / popup / "anti-bot" interstitial blocking,
- fit-to-screen scaling for TV,
- D-pad / remote control navigation,
- native HTML5 fullscreen video support,
- a self-update mechanism that pulls new versions from `kerv.net`.

The user runs it on an **Android TV box** and installs by sideloading.

## 2. Where everything lives

| Thing | Path |
|---|---|
| Project root | `/home/kerv/movy/` |
| Main activity (Java) | `/home/kerv/movy/app/src/main/java/sx/movy/tv/MainActivity.java` |
| Self-updater (Java) | `/home/kerv/movy/app/src/main/java/sx/movy/tv/Updater.java` |
| Injected page logic (JS) | `/home/kerv/movy/app/src/main/assets/tv_enhance.js` |
| Manifest | `/home/kerv/movy/app/src/main/AndroidManifest.xml` |
| App gradle (version here) | `/home/kerv/movy/app/build.gradle` |
| FileProvider paths | `/home/kerv/movy/app/src/main/res/xml/file_paths.xml` |
| **Deployed APK** | `/home/kerv/websites/kervnet/movy.apk` → served at `https://kerv.net/movy.apk` |
| **Update manifest** | `/home/kerv/websites/kervnet/movy/version.json` → `https://kerv.net/movy/version.json` |

## 3. Build environment (already set up on this machine)

- **Android SDK**: `/home/kerv/android-sdk` (build-tools 34.0.0, platform android-34, platform-tools).
- **Gradle**: `/home/kerv/gradle-8.7/bin/gradle` (NOT on PATH; call by full path). No wrapper generated.
- **JDK**: MUST use the full JDK, not the JRE. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
  (the `openjdk-17-jdk-headless` package was installed because `jlink` was missing;
  the build fails without it).
- `local.properties` points `sdk.dir` at the SDK.

### Build + deploy command (copy-paste)
```bash
cd /home/kerv/movy
export ANDROID_HOME=/home/kerv/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
/home/kerv/gradle-8.7/bin/gradle :app:assembleDebug --no-daemon
# output: app/build/outputs/apk/debug/app-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk /home/kerv/websites/kervnet/movy.apk
```
Then bump `versionCode`/`versionName` in `version.json` to match `app/build.gradle`.

Validate the injected JS after edits: `node --check app/src/main/assets/tv_enhance.js`.

## 4. Key technical facts / constraints (IMPORTANT)

- **App id / namespace**: `sx.movy.tv`. minSdk 21, target/compileSdk 34.
- **Current version**: `versionCode 3`, `versionName "1.2"` (as of this handoff).
- **Signing**: DEBUG keystore only. Self-update requires the new APK to be signed
  with the **same key** as the installed one. Debug→debug works because it's the same
  auto-generated debug key. If you switch to a release keystore, the FIRST switch needs
  a manual uninstall/reinstall on the device; after that release→release self-update works.
  **A fixed release keystore has NOT been set up yet** — consider doing this.
- **The video player is a CROSS-ORIGIN IFRAME** (embed host `vidy.st`; the site's API
  link is `https://www.vidy.st/`). This is the single most important constraint:
  - We **cannot** read or script inside that iframe (same-origin policy). So JS-based
    ad removal / control injection only works on the TOP document, not inside the player.
  - Fullscreen: handled natively via `WebChromeClient.onShowCustomView` (works when the
    embedded player calls the Fullscreen API).
  - Remote control inside the player relies on FORWARDING key events to the iframe and
    giving it focus, then letting the embedded player's own keyboard handling respond.

## 5. How each feature is implemented

### Ad / popup blocking (two layers)
1. **Network layer** (`MainActivity.java`): `shouldInterceptRequest` + `shouldOverrideUrlLoading`
   check host/url against `BLOCK_FRAGMENTS[]` (~140 entries) and return empty responses.
   Popups blocked via `onCreateWindow` returning false + `setSupportMultipleWindows(false)`.
2. **Cosmetic/structural layer** (`tv_enhance.js`, injected on `onPageFinished`):
   - `AD_SELECTORS` / `AD_IFRAME_HOSTS` remove known ad elements/iframes.
   - `isStructuralAd()` catches the **"confirm you're not a robot"** gate structurally
     (fixed/high-z-index + large + contains cross-origin ad iframe OR a top-right close
     "X" OR near-white background). This is because that gate is an iframe whose text we
     cannot read.
   - `isProtected()` whitelists the site's own player iframe (`vidy.st` / `movy.sx`) and
     any element containing a `<video>` so we never nuke the player.
   - Re-sweeps every 1s for 60s (ads inject late; the user reported the gate auto-closes
     after ~20s).

### Fit-to-screen (SOLVED per user)
- Native: `setUseWideViewPort(false)` + `setLoadWithOverviewMode(true)`.
- JS: `fixViewport()` forces `width=device-width, initial-scale=1`; `fitToWidth()` measures
  content width vs viewport and applies `transform: scale()` to `documentElement` if it
  still overflows. Runs on load/resize/mutation.

### Remote / D-pad navigation
- `tv_enhance.js` implements geometric spatial navigation (`pick(dir)` picks nearest
  focusable in the pressed direction) with a visible red focus ring.
- Player routing: `playerFrame()` finds the big player iframe/video; `focusPlayer()`
  focuses it; when focused, ALL keys pass through to the player. OK/Enter/Space focuses
  the player if not already focused.
- Native `onKeyDown` (`MainActivity.java`): Back exits fullscreen then web-history;
  media keys are `dispatchKeyEvent`-forwarded to the WebView + `PLAY_PAUSE_JS` toggles a
  native `<video>`; Menu reloads.

### Self-update (`Updater.java`)
- On startup, fetches `https://kerv.net/movy/version.json`, compares `versionCode`,
  and if newer shows an AlertDialog → downloads APK to cache → launches system installer
  via `FileProvider` (authority `${applicationId}.fileprovider`).
- Needs `REQUEST_INSTALL_PACKAGES` (in manifest) and, on Android 8+, the user must grant
  "install unknown apps" once (the app deep-links them to that settings screen).
- Fails silently if offline.

`version.json` format:
```json
{ "versionCode": 3, "versionName": "1.2", "apkUrl": "https://kerv.net/movy.apk", "notes": "..." }
```

## 6. Status of the user's reported issues

| # | Issue | Status |
|---|---|---|
| A | Ads / tons of ads | Network + cosmetic blocking in place. Mostly working. |
| B | Page showed only 2/3 / overflow | **FIXED** (user confirmed fit-to-width is good). |
| C | Remote navigation wonky on page | Improved with spatial nav. Needs real-device tuning (esp. carousels). |
| D | "Scan with your phone" ad | Addressed via structural killer. |
| E | **"Confirm you're not a robot" white div w/ top-right X, auto-closes ~20s** | **NOT CONFIRMED FIXED.** New structural `isStructuralAd()` targets it (v1.2) but was not verified on device. THIS IS THE TOP OPEN ITEM. |
| F | **Player remote UX ("any button pause/play", arrow to player controls like quality/skip, Netflix-style)** | **PARTIAL.** We forward keys + focus the iframe, but because the player is a cross-origin iframe we cannot directly drive its controls. Depends on the embedded player honoring forwarded key events. NOT verified on device. |

## 7. Biggest open problems for the next assistant

1. **The "not a robot" gate (Issue E).** If the structural killer still misses it, the
   next step is to get the ACTUAL DOM of the gate from a real device. Options:
   - Use `chrome://inspect` remote debugging against the TV box's WebView to read the
     overlay's tag/classes/iframe src, then add a precise rule.
   - Consider blocking at the network layer instead: identify the ad iframe's host from
     devtools Network tab and add it to `BLOCK_FRAGMENTS` in `MainActivity.java` (network
     blocking is more reliable than cosmetic removal).
   - `setSafeBrowsingEnabled` / a hosts-style blocklist are also options.

2. **Player controls over the remote (Issue F).** The cross-origin iframe is the blocker.
   Investigate:
   - Does `vidy.st` expose a `postMessage` API? If so we could send play/pause/seek
     messages instead of relying on key forwarding. Check its embed docs / JS.
   - Does movy expose a direct video source (HLS/m3u8) we could play in a native
     `ExoPlayer`/`VideoView` with a proper TV player UI? That would give full remote
     control and bypass the iframe entirely — the cleanest long-term fix, but a larger
     rewrite. The site footer disclaims hosting; streams come from third parties.
   - Verify whether the embedded player even accepts keyboard input when focused.

3. **No real-device verification has been possible in this environment** (no emulator/
   device/ADB target). Everything compiles and the JS passes `node --check`, but runtime
   behavior on the actual TV box is unverified for issues C, E, F. Prioritize getting
   `adb` access to the box (`adb connect <tv-ip>:5555`) for remote debugging.

4. **Consider a release keystore** before shipping more updates, so signing is stable.

## 8. Things NOT to break

- Do not remove `isProtected()` whitelist logic or you'll nuke the player iframe.
- Do not re-enable `setUseWideViewPort(true)` — that reintroduces the overflow (Issue B).
- Keep `versionCode` in `app/build.gradle` and `version.json` in sync, and always bump
  `versionCode` for a new release or self-update won't trigger.
- Keep using the full JDK for builds (`JAVA_HOME` above), not the JRE.

## 9. Quick test loop

1. Edit code / JS. `node --check` the JS.
2. Bump `versionCode`/`versionName` in `app/build.gradle`.
3. Build + copy APK (section 3).
4. Update `version.json` to the new version.
5. On device: relaunch app → it should prompt to self-update. Or `adb install -r movy.apk`.
6. For DOM debugging use `chrome://inspect` from a desktop Chrome against the TV WebView.

## 10. Current implementation (2026-09-13)

The older notes above describe the original iframe approach and are retained as history.
The current app injects `app/src/main/assets/tv_enhance.js` at document start and controls
Movy's in-page `#vp-shell` player directly. The robot QR iframe is precisely removed and
`fasolacaymans.com` is blocked natively; the user confirmed that issue is fixed.

The previous **v1.7 / versionCode 8** release attempted to fix the
idle-player pause case where only the header controls appeared: `revealTransport()` now
walks up from `#ButtonPlay` and forces whichever real ancestors hide/translate the
transport row. This is intentionally independent of the site's volatile `.bottom-0`
Tailwind class. The regression test includes that alternate wrapper and verifies pause,
skip, quality and subtitle navigation.

The prior public v1.6 APK and manifest are backed up at
`release/rollback-v1.6-8pQzLk/`. HTTPS deployment was verified byte-for-byte against
the local APK and manifest.

Verification:
```bash
PLAYWRIGHT_CHROMIUM_EXECUTABLE=/home/kerv/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome npm test
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/kerv/android-sdk /home/kerv/gradle-8.7/bin/gradle :app:assembleDebug :app:lintDebug --no-daemon
```
