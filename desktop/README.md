# Movy Desktop — Windows preview

Separate native Windows client; Android remains in `app/`. WPF supplies the
desktop interface, Microsoft Edge WebView2 handles hidden Movy account/stream
connections, and mpv provides embedded native playback over a private named pipe.
There is no visible Movy website player or browsing fallback.

## Run

1. Download the Windows x64 ZIP from the GitHub Release or download page.
2. Extract **the whole ZIP** to a folder and run `Movy.exe`. Do not run it inside
   the ZIP or copy just the EXE: its .NET, WebView2 SDK, and asset files are needed.
3. Windows 10/11 x64 is the initial target; ARM64 is not packaged. The .NET runtime
   is included. Account access and stream resolution require Microsoft's
   [WebView2 Evergreen Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/).
   The app provides that official link when account initialization fails.
4. First Play asks permission to download the official mpv 0.41.0 Windows x64
   release (~39 MB), verifies its pinned SHA-256 checksum, then caches its EXE/DLLs
   under `%LOCALAPPDATA%\MovyDesktop\Player\0.41.0`. Subsequent plays reuse it.
   The upstream archive contains a nested ZIP, handled by a root-file whitelist;
   extraction never follows archive paths. See `THIRD-PARTY-NOTICES.txt`.

This is an **unsigned preview**: Windows may display an unknown-publisher warning.
Use the expected GitHub download and compare its SHA-256 with the release manifest.
Do not disable Windows security protections. No installer/admin privileges are
required. Updates are manual: extract a newer ZIP into a new folder and run it.
Android's existing signed self-updater is unchanged.

## Features and boundaries

- Native charcoal/gold sidebar: Home, Search, My List, Account, Movies, Series,
  Netflix, Prime, HBO, Disney, Apple, Paramount, and Hulu.
- Movies/Series open on Movy's Recommended collections. Library supports date,
  alphabetical, popularity, rating, and genre filters. Provider pages have
  independent movies/series Load more grids; appended titles stay in place.
- Details, metadata, cast, season/episode choices, and play/resume. Back from
  details restores the actual previous page/scroll/focus in this app session.
- Continue Watching combines this PC's progress and the selected Movy profile.
  Right-click a card to remove it on this PC; backend history is not deleted.
  Completed episodes use actual episode/air-date metadata to queue up-next.
  Recent history is capped at 40; saved titles are separate. Device-local progress
  is not written to Movy and is not shared with the Android device automatically.
- Native sign-in, profile/PIN selection, account history/lists, and sign-out. JWT
  stays inside WebView2's origin storage; it is never copied to UI/state files.
  Windows sign-in is separate from Android. Profile changes discard retained
  navigation/account-next data; local history/My List remain on this PC.
- Embedded mpv timeline, rewind/play/forward, volume, CC, fullscreen, and Settings
  for sources, quality, audio/subtitles, speed, next episode, and sync offsets.
  Audio ±2 seconds and captions ±10 seconds use 0.10-second steps and Reset;
  positive means later. These are actual mpv timing properties, not label-only UI.
  Space pauses, Left/Right seek, F11 toggles fullscreen, and two Esc presses within
  2.5 seconds return to the title. The window's Close button exits immediately.
- Caption service is queried on demand; selected sidecars are bounded downloads
  to temporary local files, without forwarding stream cookies to caption hosts.
  Files are removed on player close (an abrupt crash can leave a caption file).
- Movy remains the source. Browsing never resolves media. A metadata listing is
  not a guarantee that a stream is playable. Resolver timeout is 45 seconds.

## Shared code

`shared/native_account.js` and `shared/native_resolver.js` are the exact same
connection adapters packaged by Android and Windows. Shared provider/backend
mappings live in `shared/movy-config.json`: Gradle generates Android constants;
Windows embeds and reads that file. Both platforms test
`shared/fixtures/catalog.json`. Native UI, local persistence, and Media3/mpv
playback adapters are platform-specific; this is not a Java-to-C# transpilation.

## Build and test

Install the .NET 10 SDK. From the repository root:

```powershell
dotnet run --project desktop/Movy.Core.Tests -c Release
pwsh -File desktop/build.ps1
```

Output: `desktop/dist/win-x64/Movy.exe` and a versioned Windows ZIP. The build is
self-contained, x64, and unsigned. Linux can cross-publish the same Windows target:

```bash
dotnet run --project desktop/Movy.Core.Tests -c Release
dotnet publish desktop/Movy.Desktop -c Release -r win-x64 --self-contained true -o desktop/dist/win-x64
```

GitHub runs the core checks and a Windows smoke test (`Movy.exe --smoke-test`)
before publishing. Smoke uses native fixture artwork and generated silent WAV
audio, checks a guest WebView2 account connection, mpv IPC/seek/pause/sync, and
exports a native Home preview. It does **not** prove real Movy account login,
source resolution, video decoding, or hardware A/V synchronization; those require
Windows/device testing. Smoke mode is for a fresh test profile, not everyday use.

User data: `%LOCALAPPDATA%\MovyDesktop` (library JSON, WebView2 profile, player,
and caption cache). Closing the portable folder does not erase it. Credentials,
resolved stream URLs, and cookies are never written to the library JSON or logs.
