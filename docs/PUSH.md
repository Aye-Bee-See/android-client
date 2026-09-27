# Push notifications: taken out of the Android app

**27 September 2026.** The Android app has no push notifications and contains no Firebase or Google Play services code. It reads the account's notification feed (`GET /auth/notifications`) itself: whenever it is opened, and every three hours in the background, which Android stretches while the phone sleeps. The reasons are in `docs/DECISIONS.md` (2026-09-27): a ring through Google tells Google that a phone has this app, when it is rung, and from what address, and for mail that takes weeks the speed is not worth that to the people this app is for.

## What the phone still does

- **The feed check** (`data/activity`): on opening, every three hours (`WorkManagerActivityScheduler`), and on demand from the developer dialog ("Check for news in 8 s"). What it finds is worded on the phone and shown as a notification on one of three channels (replies and returned letters as a banner; letter progress and the group queue quietly). Nothing about who or what leaves the phone to show it.
- **Retiring old registrations** (`data/push/PushRetirement.kt`): a phone that had push on in an earlier release removes its device record from the server (`DELETE /auth/device`) the next time it runs signed in, then forgets the two settings the switch kept. A record already gone counts as done; no connection means it tries again next time. Google's copy of the old push address goes stale: without the Firebase library the app cannot ask Google to delete it, and nothing reaches the phone through it.

## If push ever comes back

Not through Firebase. [UnifiedPush](https://unifiedpush.org/) lets the person choose a distributor app (ntfy, for example), and the server rings that distributor directly, through a service the project could host itself. It needs a UnifiedPush sender on the API (its push providers are pluggable), the connector library and a choice of distributor in the app, and the distributor installed by the person who wants it.

## History

Push was built as an opt-in doorbell on 19 September 2026 and taken out on 27 September; the implementation and its first-run checklist are in the git history of this file and of `data/push/`.
