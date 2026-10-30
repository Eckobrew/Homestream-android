# HomeStream for Android and Android TV

An independent native Android client for [Eckobrew/HomeStream](https://github.com/eckobrew/homestream).
Two separate presentation layouts follow the Netflix experience on each platform:
phones use a “For You” header, category pills, inset portrait feature card and
bottom tabs; Android TVs use an expanding left navigation rail, wide backdrop
hero and large landscape rows. Both keep HomeStream branding. This is a native Kotlin app, not a wrapped webpage.

## Features

- Phone/tablet mobile layout and dedicated Android TV layout with D-pad focus and playback controls.
- New & Hot discovery and device-local My List, separated by server and profile.
- HomeStream home feed, continue-watching progress, movies/TV libraries and search.
- Household profiles, media details, seasons and episode playback.
- Media3 H.264/AAC direct play or Jellyfin HLS transcoding, resume and subtitles.
- Google Cast discovery, compatible playback negotiation, position handoff,
  expanded/mini transport controls and return to local playback.
- Local playback of in-progress download streams supplied by HomeStream.
- Device-bound encryption for the server pairing token using Android Keystore.
- TV playback works without Google Play services; Cast sender controls appear on
  phones/tablets with Google Play services.

## Backend setup

The stock HomeStream repository needs the additional `/api/mobile/v1` API routes.
Those changes are maintained separately in the local HomeStream checkout on
`feat/mobile-api`. This Android repository has no dependency on the web source,
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
not an individual-user login. Rotate it on the server to revoke paired clients.
The app disables backup and does not log tokens or stream URLs.

## Build

Requires Android Studio or JDK 17, Android SDK Platform 35 and Build Tools 35.0.0.
Android 8.0/API 26 or newer is supported on phones and TVs.

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
