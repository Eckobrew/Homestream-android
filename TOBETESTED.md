# TOBETESTED

Updated: 1 November 2026 (Europe/London).

This is the current manual acceptance checklist. Older validation reports describe earlier builds and do not establish that the current features work on real devices. Leave a box unchecked until you have verified it yourself. Record failures with the device, title, steps and observed result.

## Current baseline

- Android: `5d9b526` — custom Cast receiver `75369D0A`, session-policy checks for both setup modes, TV Connect focus fix, simplified TV sidebar, phone top-tab removal and the earlier profile/category changes. Build passed; installation updates target **HomeStream Preview**, preserving pairing/settings.
- Backend: `6e65ea2` — deployed to LXC 111 from its GitHub-built Docker image, healthy with clean startup logs. Includes the backend audit fixes and `/cast/receiver.html`. No LXC build.
- Pixel 8a and Chromecast HD (`192.168.1.100`) are the current ADB deployment targets. Phone screen timeout is restored to 30 seconds after installation.
- User confirmed the original profile-confirmation blocker cleared after matching the backend update. The TV reached Home after an explicitly requested assisted Connect press; its screenshot confirmed the focusable inactive server row and visually repeated sidebar icons.
- These observations do not verify the replacement TV remote-focus/sidebar behavior, the custom receiver launch, codecs or audio sync. Those remain manual checks below.
- Automatic backend CI passed for the deployed commit. Local Android APK assembly passed. Full backend lint still fails (113 errors/39 warnings in the audit snapshot); production dependency audit reported zero vulnerabilities. See the backend audit report for findings and limits.

## Latest TV, receiver and audit checks — start here

- [ ] Focus the bottom-right/last card in a row and the lowest visible row: the full selection border should fit the card, with no clipped white strip at the screen edge.
- [ ] Collapsed TV rail highlights should be square and centred on the icons; expanded rail highlights should follow the labelled button bounds.

- [ ] On the TV, reopen **HomeStream Preview**. The primary rail should have unique Search, Home, TV Shows, Movies, Requests, More, My List, Profiles (local paired only) and Settings destinations. Settings should remain reachable; More contains A–Z libraries, categories, YouTube and eligible downloads.
- [ ] Verify one selected rail destination at a time, a visible remote-focus outline, and readable labels when the rail expands. Check Back from a More destination.
- [ ] On the next fresh TV setup (or a spare test setup), use only the D-pad to reach and press the discovered server's **Connect** button. Do not erase your working pairing just to perform this check.
- [ ] In Google Cast Developer Console, confirm `75369D0A` points to `http://homeflix.eckonet.uk/cast/receiver.html` and the Chromecast test device is registered/restarted. Publication requires HTTPS.
- [ ] Stop any old default-receiver session and reconnect from the phone. The TV should show a rotating background, HomeStream branding, Cast icon and **Ready to cast**; the phone should have no photo/slideshow transport controls.
- [ ] Start a movie, pause, seek, change subtitles, play the next episode and stop. Playback controls should replace standby; stopping should restore the TV slideshow. The slideshow should keep rotating without the phone sending PHOTO loads.
- [ ] Switch to Kids or tighten the active profile remotely: existing receiver artwork should clear before restricted content is shown. Empty eligible libraries must not leave adult artwork behind.
- [ ] External Jellyfin-login accounts also confirm session policy before cached browsing, while keeping profile management hidden.
- [ ] For an eligible Kids title, request-quality options should load; adult/unrated POST requests must still be rejected.
- [ ] Playback progress should resume accurately; if Jellyfin cannot save it, the API should report a failure rather than claim success. Subtitle selection/preview should continue working through scoped URLs.
- [ ] Arr webhook **Test** should succeed without modifying torrents when a valid webhook token is configured. Non-admin calls and invalid event/hash data must not change torrent priority.
- [ ] Check search includes real Jellyfin library matches even if Seerr is unavailable, and avoids duplicate available/requestable copies of the same title.
- [ ] Check quality options against the actual Radarr/Sonarr configuration, especially during a provider outage; provider outages should offer Server default and never invent Radarr/Sonarr profile IDs.

## First: latest UI and category loading

- [ ] **Phone profiles:** dark cards, red HomeStream branding, rounded-square avatars and spacing match the rest of the app; no clipping around status bar, cutout or navigation bar.
- [ ] **TV profiles:** text and avatars read well at TV distance; Back, profile selection, editing and Add profile are reachable with the remote; focus is visible.
- [ ] **Continuous categories:** scroll Movies and TV Shows beyond the first three category rows without pressing a “Browse all” button. Rows appear without blank gaps or jumps.
- [ ] **Look-ahead:** scroll at normal speed and then quickly. The next two rows should benefit from prefetching the first four artwork images each, without freezing the screen or starting media/detail downloads for every title.
- [ ] **Return position:** scroll down several categories, scroll a row sideways, open a title, then press Back. Both vertical and horizontal positions remain useful, and later rows still appear when scrolling further.
- [ ] **Refresh/navigation:** switch Movies → TV Shows → Home, return, rotate the phone and reopen the app. No duplicate rows, missing rows, stale-profile cards or continuous loading loops.
- [ ] **TV scrolling:** use D-pad down across category boundaries and left/right within rows. Focus should not disappear or become trapped when off-screen rows release their views.
- [ ] **Cache bounds:** browse repeatedly and inspect cache usage in Settings/Android storage. Artwork disk cache stays within approximately 256 MB and metadata disk cache within 16 MB; do not count app binaries/data as cache. Memory artwork limits are 32 MB, or 16 MB on low-RAM devices. Clearing cache should preserve login and pairing.
- [ ] **Slow/unavailable network:** previously cached eligible content remains usable after profile confirmation; failed artwork gets a placeholder, and background refresh does not reset scroll position. If profile confirmation fails, protected cached screens must remain blocked.

## Profiles and automatic Kids restrictions — phone and TV

Use disposable test profiles/accounts where possible. Editing a DOB changes the linked Jellyfin account's parental policy.

- [ ] Add a profile using an existing non-admin Jellyfin account; add another by creating a new Jellyfin account. Confirm avatar/name/history separation.
- [ ] Enable Kids and enter a DOB; future/invalid dates and administrator accounts must be rejected.
- [ ] Confirm these birthday boundaries using parent-authorized DOB edits:

| Age | Maximum certification | Verified |
| --- | --- | --- |
| 0–3 | U | [ ] |
| 4–11 | PG | [ ] |
| 12–14 | 12 | [ ] |
| 15–17 | 15 | [ ] |
| 18+ | 18 | [ ] |

The backend recalculates on the first request after a birthday. February 29 birthdays advance March 1 in non-leap years. At 18 the profile remains in Kids mode with the 18 limit; it does not automatically become an unrestricted adult profile.

- [ ] First Kids setup creates a parent PIN. Subsequent Add/Edit actions, DOB changes and switching to a less restrictive/adult profile require the correct PIN. Missing/wrong PINs fail; switching to a stricter profile works without it.
- [ ] At each applicable limit, check Home, search, movies, series, categories, More like this, collections, episodes and requests: over-limit and unrated titles must be absent. Allowed non-library suggestions should still appear when certification data is available.
- [ ] Try a previously saved adult title/launcher link and an old playback URL after switching to Kids. They must not bypass restrictions.
- [ ] Allowed movie/episode playback, seeking, subtitles and Cast work in Kids mode. Trailers and in-progress torrent playback are unavailable.
- [ ] Tighten the active child's DOB limit on another device. On resume/periodic policy refresh, old playback stops and the browse/launcher state refreshes. Old adult cards must not reappear from cache or Back history.
- [ ] Confirm the linked Jellyfin user has the matching maximum parental rating and unrated-content blocking enabled.
- [ ] External Jellyfin-login setups expose no profile management; locally paired setups retain profiles when using the external HomeStream URL.
- [ ] Adult passwordless/username-as-password sign-ins are rejected while Kids profiles exist. Adult access remains possible with a stronger Jellyfin password or local pairing plus parent PIN.

## Playback and Cast regression checks

Use the Chromecast with Google TV **HD**, Pixel 8a and an actual Android TV device. Record the title, codecs and reported playback method when reporting failures.

- [ ] Compatible files direct-play/direct-stream where supported; unsupported video/audio falls back to a working transcode. An HLS label alone does not prove that video is being transcoded.
- [ ] Cast audio stays in sync with video, including on the HDMI ARC/optical soundbar setup. Compare the same scene with local playback and after seeking; report whether sound is early or late and roughly by how much.
- [ ] Cast handoff preserves position and pauses local playback. Pause, seek, subtitles, previous/next episode and autoplay work across season boundaries, skipping unavailable episodes.
- [ ] The phone's Cast controls remain portrait and respect cutouts/system bars.
- [ ] Idle Cast UI says “Ready to cast,” has no slideshow transport controls on the phone and does not expose “Default media player.” Starting/stopping real playback changes controls correctly.
- [ ] Disconnect/stop-and-return resumes local playback sensibly. Receiver/load failures show a useful error rather than leaving a stuck player.
- [ ] Local phone/TV playback preserves seek/resume, subtitles, watched status and episode progress bars.
- [ ] Adult profile only: play an actively downloading qBittorrent item, seek within available data and let playback approach a missing piece. It should wait/resume sensibly rather than serve corrupt bytes. Kids mode must reject this path.

## Android TV launcher and general navigation

- [ ] Watch Next/Continue watching appears on a supported Google TV launcher after meaningful playback. The launcher decides final placement/visibility.
- [ ] Opening a launcher card resumes the correct episode/profile. Completed, dismissed and previous-profile entries are removed appropriately; policy changes do not leave adult entries visible.
- [ ] TV browsing, player controls, dialogs and profile editor work entirely with D-pad/remote transport keys. Devices without Google Play services retain local playback.
- [ ] Back returns to the previous browse/details screen rather than always going Home. Transitions are smooth and retain useful focus/scroll state.
- [ ] “Marked watched” fits the current UI and updates history; More like this fills the available screen and is sensibly sized.
- [ ] Recommendations are relevant rather than forced Jellyfin filler, and can include non-library titles. Collections do not show unavailable items as playable.
- [ ] Local auto-discovery/pairing works, then the same pairing works away from home using the configured external URL. External setup uses Jellyfin sign-in; there are only the two setup choices.

## Failure notes

| Device / build | Steps and title | Expected | Actual / approximate time | Status |
| --- | --- | --- | --- | --- |
| | | | | |


## Playback, Cast remote and remote access fixes — 1 November 2026

Android installed on Pixel 8a: `ed54295` (Google Cast remote restored). Android TV: `c9a8527`. Backend pulled and deployed in LXC 111: `2b77a58`. Phone timeout restored to 30 seconds.
Verification: local Android compilation, backend TypeScript compilation, code review and existing device/Jellyfin logs. No manual playback/UI acceptance tests performed by Codex.

- [ ] Phone: resume an episode at least five minutes in, both locally and over Cast; it must resume at the saved position.
- [ ] Series details: Resume should choose the in-progress episode rather than the first episode. Check an episode from a later season.
- [ ] Cast: header Cast button and the mini-player open the Google Cast remote with artwork, timeline and volume controls; no second HomeStream backdrop remote.
- [ ] Cast: play/pause and seeking work; the total duration stays at the library runtime while the stream buffers. Artwork/title must not flash with progress updates.
- [ ] Cast queue: next episode updates title, runtime and progress to the new episode.
- [ ] Native Android TV: standard transport controls, without the phone action-button row or debug stream-mode toggle; hardware next/previous keys still skip episodes.
- [ ] Phone/TV profile screen: current profile is identified; dark surfaces, white text, red branding, clear TV focus and cutout-safe phone layout.
- [ ] Web HTTP and HTTPS: Jellyfin login persists after reload; incorrect credentials show an error. External sessions cannot switch household profiles.
- [ ] LAN/Tailscale via homeflix.eckonet.uk: auth status reports the verified client/router address instead of an empty string. With subnet SNAT, the Proxmox LAN address is expected rather than the original 100.x address.
- [ ] Internet without Tailscale: still requires login. A forged forwarding header must not grant household access.
- [ ] Kids restrictions and parent PIN remain enforced. Identifying a trusted network does not bypass child restrictions.
- [ ] Continue Watching reflects progress for the selected household profile after leaving playback and reopening the app.


### Google Cast remote preference

- Restored the Google SDK expanded controller as the only full-screen Cast remote, with portrait lock and cutout/system-bar insets retained.
- Removed the alternate HomeStream backdrop remote. Starting a cast opens the Google controller; Back returns to browse.
- Cast progress is recorded independently of the player Activity while the app process is alive, including when browsing or using the Google controller. No progress is written during initial buffering.
- [ ] Check the Google remote’s play/pause, timeline, volume, subtitles and queue controls.
- [ ] Back should return to browsing rather than another full-screen remote; resume/progress should continue to update while browsing.
- Verification: local compilation and source review; manual device acceptance remains with the user.
