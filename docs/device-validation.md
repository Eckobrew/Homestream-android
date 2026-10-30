# Device validation

Automated tests cover media eligibility, tick precision, the versioned API path,
profile scoping, progress payloads, pairing errors and preventing credential
forwarding across HTTP redirects. Android lint and APK assembly are separate
checks. Real receiver discovery and hardware codec support require real devices.

Before a release, use your deployed HomeStream/Jellyfin configuration:

| Device/scenario | Expected behavior |
| --- | --- |
| Phone, fresh installation | Pairing validates URL/token; invalid token remains editable |
| Phone, rotation or reopen | Home reloads; saved server/token/profile survive |
| Phone, unavailable server | Actionable error, retry and settings controls |
| Phone, search | Matching Jellyfin movies and series; empty state for no results |
| Phone, profiles | Each profile shows its own resume/history |
| Phone/TV, series | Correct season and episode play; unavailable Seerr episodes cannot play |
| Phone/TV, seek/resume | Seek, leave, reopen and resume within reporting interval |
| Phone/TV, subtitles | VTT tracks selectable in player settings |
| Phone/TV, HEVC/incompatible codec | Compatible-HLS retry plays through Jellyfin transcoding |
| Phone, browse layout | Mobile header/categories, portrait feature card and bottom tabs; no TV rail |
| TV, browse layout | Left rail, wide hero and landscape rows; no phone bottom tabs |
| My List | Add/remove persists locally and stays separate for each server/profile |
| TV, no touch hardware | Launcher entry visible; all browsing/play actions reachable by D-pad |
| TV, remote transport keys | Play/pause/seek/back work without a touchscreen |
| TV without Google Play services | Local playback works; Cast sender controls are absent |
| Chromecast, discovery | Cast button lists receiver on same reachable network |
| Chromecast, handoff | Playback transfers near current position and local playback pauses |
| Chromecast, controls | Expanded/mini controls pause, seek and select subtitles |
| Chromecast, disconnect | Explicit stop-and-return action resumes locally near receiver position |
| Chromecast, incompatible URL | Load failure returns local playback with a useful error |
| Pairing revocation | Rotating the server token rejects future API requests |

No physical phone, Android TV or Chromecast was attached to the development
workspace. These checks must be completed on your devices before a production
release. This project does not imply certification by Netflix, Google or a TV
manufacturer.
