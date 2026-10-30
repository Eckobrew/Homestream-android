# Local validation — 2026-10-30

The standalone app built successfully using JDK 17 and Android SDK 35.

| Check | Result |
| --- | --- |
| `assembleDebug` | Passed; installable debug APK generated |
| `testDebugUnitTest` | 15 tests passed, 0 failures |
| `lintDebug` | Passed, 0 errors; advisory dependency/style warnings remain |
| Pairing HTTP client | Versioned URLs, selected profile, actionable 401, no redirect credential forwarding |
| Progress payload | Correct 64-bit ticks, selected profile, paused/stopped fields |
| Media behavior | Resume precision, unavailable Seerr episodes, Jellyfin IDs, in-progress download eligibility |
| Robolectric phone launch | Pairing dialog opens and validates a missing server; mobile-only header and bottom tabs |
| Robolectric TV launch | All six core rail actions are focusable and clickable without touch; phone tabs absent |
| Native-rendered platform layouts | Portrait inset feature on phone; wide feature and left rail on TV; PNG previews generated |
| My List | Add/remove persists and stays separate for each server and profile |
| HomeStream production build | Passed in the separate backend checkout |
| HomeStream mobile API integration | Passed, including disabled/invalid auth, media contracts, input validation, fake Jellyfin negotiation and profile-linked progress/stop |
| Live site | Frontend and auth endpoint responded; supplied login returned 401; no authenticated or playback checks performed |

HomeStream's full pre-existing lint check reports errors outside the new mobile
API. The new API route passes its targeted ESLint check. No backend was deployed,
no live profile or watch history was changed, and no GitHub push or PR was made.

No physical Android, Android TV or Chromecast was attached. Discovery, hardware
decoding, CORS/TLS on the receiver and actual playback handoff still need the
[device validation checklist](device-validation.md). Local tests do not establish
successful playback against the live household server.

## Platform layout previews

`PlatformLayoutTest` renders the native views with Robolectric's native graphics
engine and writes `app/build/reports/previews/phone.png` and `android-tv.png`.
They verify the actual layout code, not design mockups. The local preview run
used public artwork already referenced by HomeStream's demo metadata; no artwork
is bundled into the APK or source repository. CI uses an offline placeholder.
The preview phone lacks Google Play services, so its Cast button is absent.

## 1.0.1 startup crash regression

A Pixel 8a running Android 17 build CP3A.261005.005 was reported to close
immediately. Local reproduction found that directly constructing the Google
Cast MiniControllerFragment skipped its `onInflate` style/button initialization
and threw a NullPointerException while creating the controller view. Earlier
phone launch tests skipped this branch because Google Play services was absent.

The controller now uses the SDK's XML inflation lifecycle. Two additional tests
create/rebuild the real Cast controller UI and recreate its hosting Activity;
both pass. The original programmatic construction failed the regression test.
Version 1.0.1 (version code 2) passes APK assembly, all 15 tests and Android lint.
Its signing certificate matches 1.0.0, allowing installation as an update.
The reported Pixel/Android 17 firmware is not available locally for direct
hardware verification.
