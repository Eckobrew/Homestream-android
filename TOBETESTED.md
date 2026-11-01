# TOBETESTED

Updated: 1 November 2026 (Europe/London).

This is the current manual acceptance checklist. Older validation reports describe earlier builds and do not establish that the current features work on real devices. Leave a box unchecked until you have verified it yourself. Record failures with the device, title, steps and observed result.

## Current baseline

- Android: `7bf88bd` — profile restyle, scrolling category rows and bounded artwork prefetch. Built locally and installed as **HomeStream Preview** on the Pixel 8a; original app preserved. Phone screen timeout restored to 30 seconds.
- Backend: `1bc8df8` — deployed to LXC 111 using the GitHub-built Docker image; container reported healthy. No build performed in the LXC.
- User confirmed: the “Connect to HomeStream to confirm your profile” blocker cleared after the matching backend deployment.
- Android TV installation of the latest APK is not confirmed.
- Functional checks below have not been performed by the coding agent. Latest Android APK assembly passed; backend CI passed. These are not substitutes for device testing.

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
