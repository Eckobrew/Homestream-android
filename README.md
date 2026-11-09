# HomeStream for Android and Android TV

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-Phone_%26_TV-3DDC84?logo=android&logoColor=white)](README.md)

An independent native Android client for [Eckobrew/HomeStream](https://github.com/eckobrew/homestream).
Two separate presentation layouts follow the Netflix experience on each platform:
phones use a “For You” header, category pills, inset portrait feature card and
bottom tabs; Android TVs use an expanding left navigation rail, wide backdrop
hero and large landscape rows. Both keep HomeStream branding. This is a native Kotlin app, not a wrapped webpage.

## Features

- **Movies & TV Shows Hub**: Home feed, continue-watching progress, categories, A-Z catalogs, seasons, and episode playback.
- **Dedicated Music Hub**: Complete music player with shuffle, repeat, playback speed controls, and browsing by Albums, Artists, and Songs.
- **Dedicated YouTube Hub**: Recommendations, subscriptions, queue, NAS archive, search, and direct video streaming via HomeStream proxy.
- **Dynamic Floating Pill Bottom Navigation**: Adaptive navigation on phones dynamically adjusting pills and color accents for Movies/TV (Red), Music (Spotify Green `#1DB954`), and YouTube (Red).
- **Android TV Interface**: Expanding left navigation rail, full D-pad remote support, TV browse menus, and focus indicators.
- **Smart Navigation & Backstack**: Deep back history (up to 12 levels), sub-navigation preservation in Music and YouTube, TV navigation rail focus on back press, and double-back confirmation on root home.
- Automatic next/previous episode playback locally and receiver-side Cast queues.
- Library sorting, watched-state filters and mark watched/unwatched controls.
- New & Hot discovery and device-local My List, separated by server and profile.
- Household profiles with parental controls and PIN protection.
- Media3 H.264/AAC direct play or Jellyfin HLS transcoding, resume and subtitles.
- Google Cast discovery, compatible playback negotiation, position handoff,
  expanded/mini transport controls and return to local playback.
- Local playback of in-progress download streams supplied by HomeStream.
- Device-bound encryption for the server pairing token using Android Keystore.
- TV playback works without Google Play services; Cast sender controls appear on
  phones/tablets with Google Play services.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Backend setup

Use the matching HomeStream backend with the `/api/mobile/v1` routes, including
`episodes`, `watched`, `search`, `downloads` and `stream/download`. The original
mobile routes alone do not support every feature in version 1.1.0. This Android repository has no dependency on the web source,
Node, npm, a shared filesystem or direct Jellyfin administrator configuration.

1. Apply the mobile API changes to your HomeStream server.
2. Generate a pairing secret with `openssl rand -hex 32`.
3. Set `HOMESTREAM_MOBILE_TOKEN=<secret>` in HomeStream's server environment and
   restart it. With Docker Compose, pass this variable into the application
   container; a value in the host `.env` alone does not automatically do that.
4. Open the app and enter the HomeStream base URL and the pairing secret.
   The app checks API compatibility before saving the connection.
5. Select a profile. Create/import profiles in the web frontend if necessary.

Use HTTPS outside a trusted LAN. HTTP is supported for local self-hosted servers;
it sends the pairing secret and media traffic without encryption. The token is a
household password with access to all HomeStream profiles and media requests,
not an individual-user login. Rotate the shared token to revoke that credential. Registered device tokens
are revoked through the server device registry; account sessions are revoked by
rotating the session signing secret.
The app disables backup and does not log tokens or stream URLs.

## Build

Requires JDK 17, Android SDK Platform 35 and Build Tools 35.0.0.
Android 8.0/API 26 or newer is supported on phones and TVs.

In Android Studio, select JDK 17 under Settings → Build, Execution, Deployment →
Build Tools → Gradle → Gradle JDK (use Download JDK if needed). Recent Studio
versions can bundle Java 25, which this project's Gradle 8.11.1 does not support.
For terminal builds, point `JAVA_HOME` at the same JDK 17 installation.

```sh
# Set ANDROID_HOME to your Android SDK, or create local.properties with sdk.dir.
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on a phone or Android TV:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For an emulator, `http://10.0.2.2:3000` reaches HomeStream running on the host.
A physical device needs a reachable LAN or HTTPS address. Release signing keys
are intentionally not included; create your own signing configuration before
publishing. The included CI workflow builds/tests and uploads a debug APK.

## Chromecast networking

Cast uses Google's Default Media Receiver, so no custom receiver registration is
required. The sender phone and Chromecast generally need to be on the same LAN
with multicast discovery available. Both must reach HomeStream **and** the
Jellyfin URL configured on HomeStream. Container names, localhost and a VPN route
available only to the phone will not work on the receiver. Use a receiver-reachable
address and trusted TLS certificate, and configure Jellyfin's CORS for Cast.

Playback URLs contain Jellyfin stream credentials inherited from HomeStream's
playback API. Treat them as sensitive. The pairing token is never sent to a Cast
receiver. Google Cast playback uses H.264/AAC transcoding when available.
Progress is synced while the player screen is active, including the last position
when leaving it; Chromecast can continue playing after the app leaves the screen,
but this version does not run a background service to sync subsequent watch history.

See [device validation](docs/device-validation.md) for the release checklist.

In-progress downloads use HomeStream's existing download stream endpoint. They
can buffer or lack seeking until enough data is available. They are played
locally; Cast and Jellyfin watch-history sync apply after the completed media
is imported into Jellyfin.

See [audit fixes and playback additions](docs/audit-fixes.md) for migration notes,
queue limits and validation details.
