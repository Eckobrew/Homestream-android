# Kids profiles (phone and Android TV)

Available on locally paired household setups, including away from home after pairing. External Jellyfin-login setups do not expose profile management.

A parent creates/edits a profile, enables Kids, enters the child's date of birth and links a non-administrator Jellyfin user (or creates one). The backend derives the maximum certification in UTC:

| Age | Maximum rating |
| --- | --- |
| 0–3 | U |
| 4–11 | PG |
| 12–14 | 12 |
| 15–17 | 15 |
| 18+ | 18 |

Limits update on the first server request after a birthday. Leap-day birthdays advance March 1 in non-leap years. The profile stays in Kids mode at 18: unrated titles remain hidden and leaving still requires the parent PIN.

The first Kids profile creates a 4–8 digit parent PIN. Profile management and switching to a more permissive profile require it. The PIN is hashed on the server and never saved by Android. The date of birth is stored on your HomeStream server, not sent to Seerr/TMDB. Administrator accounts cannot be Kids accounts. Adult Jellyfin accounts need passwords different from their usernames for new password-based HomeStream logins while Kids profiles exist; local pairing and the parent PIN remain available.

Restrictions apply to home, search, libraries, categories, related titles, collections, episodes, launcher Watch Next, requests and playback. Unrated/unknown certifications are excluded; actual GB certifications are preferred over US equivalents. Unrated trailers and in-progress torrent playback are disabled in Kids mode. Restricted streams/artwork use scoped URLs, not exposed household API credentials. Existing streams are stopped when policy changes are detected. Profile selection and policy revisions separate caches and launcher entries. A connection is required to confirm the active profile before restoring cached browse screens.

## Manual verification (user performs these)

1. On phone and TV, add a Kids profile with a new non-admin Jellyfin account and a DOB yielding PG. Confirm PG is shown; try future/invalid dates and administrator accounts.
2. Check U/PG library titles are visible, 12/15/18 and unrated titles absent from every browse/search/recommendation/collection surface. Try opening a known adult title via a saved launcher link.
3. Play an allowed movie and episode locally and cast them. Check seeking, subtitles and next-episode playback. Check adult and unrated playback/requests are rejected, including old saved links.
4. Switch to a stricter profile without a PIN. Try switching back with no PIN, a wrong PIN and the correct PIN. Add/edit profiles must ask for the PIN once it is set.
5. Change the DOB across each threshold with the parent PIN. Confirm the displayed rating and linked Jellyfin parental policy follow the DOB. Check the new profile has no previous adult cache/history or launcher cards.
6. While another device is on the Kids profile, tighten its DOB limit. On its next resume/periodic policy refresh, confirm playback stops and browse content refreshes. Disconnect the network and reopen: old adult cache must not be restored.
7. Confirm a Jellyfin-login external setup still has no Profiles feature, and a paired setup continues to work through the external URL.

Builds and CI logs are checked by the coding agent; live functional verification is intentionally left to the user.
