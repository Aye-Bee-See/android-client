# Push notifications: turning them on

The app works without push: it reads the account's notification feed when it opens and every six hours. Push makes that prompt. It is off until a Firebase project exists and, after that, off on each phone until its owner turns it on (Account tab, "Instant notifications").

## What goes through Google

A ring with the payload `{"type":"sync"}`, identical for every event. No names, no text, no ids. The phone then fetches the feed from the API over its own connection and words the notification itself. Google learns that the phone has the app and when it is rung; that is why the switch explains the trade and defaults to off.

## Setting it up (once)

1. Create a Firebase project. The API's `FCM_SERVICE_ACCOUNT_FILE` comes from the same project (API README, "Turning it on").
2. In the project, add an Android app for each package name you will install: `me.paxana.abcmailbox` (debug and release share it) and `me.paxana.abcmailbox.internal`. No SHA certificate is needed for messaging.
3. Download `google-services.json` and copy four values into `firebase.properties` beside `firebase.properties.example` (which says which value is which). Do not commit either file; `.gitignore` already covers them. The internal build has its own `applicationId` value: build it with `ABC_FIREBASE_APP_ID=<that value>` in the environment, which overrides nothing else.
4. On CI, set the four `ABC_FIREBASE_*` variables as repository secrets if the CI-built APK should have push.

## Checking it, the first time

First run: 19 September 2026, project `abc-mailbox`, API instance with `"push": ["fcm"]`, Pixel 9 emulator with Google Play services, debug build. Steps 1 to 5 passed: the switch was live; turning it on answered "Turned on." and the server listed the phone (without its token); a status change by another account produced "One of your letters has been printed." **three seconds** later with the app in the background, and "One of your letters is in the post." **one second** later with the app's process killed (the push started it); turning it off emptied the server's device list, and a reply recorded afterwards did not ring the phone but was in the feed for the next check. Steps 6 and 7 have not been run: a second account taking the phone over, and a phone without Google services. Nor has a physical phone, where battery optimisation can delay a push in ways an emulator never shows.

1. Build and install. Account tab: the "Instant notifications" switch is enabled (not "Not available in this build").
2. Turn it on. Expect "Turned on." If it says the server cannot ring phones yet, the API has no service-account file: `GET /health` should list `"push": ["fcm"]`.
3. `GET /auth/devices` as that account lists the phone.
4. Put the app in the background. From another account, do something the first one is told about (a group member marks their letter printed). The notification should arrive within seconds, not hours.
5. Turn it off. `GET /auth/devices` no longer lists the phone, and step 4 no longer rings it (the six-hourly check still will).
6. Sign out and in as someone else with push on: the device moves to them (`GET /auth/devices` for each).
7. Try a phone without Google services if one is to hand: the switch is disabled and says why.

Until then, the hidden developer dialog (five taps on the build line) has "Simulate a push in 8 s", which does exactly what the FCM service does on a ring.

## What the person sees when news arrives

Found on the second run (19 September 2026): a reply was recorded from the iOS app, the push reached the Android emulator 0.6 s later, the feed check ran, a notification was posted, and the person at the emulator saw nothing. Two reasons, both fixed.

**The notification made no banner.** The single channel was created at default importance, which on Android means an icon in the status bar and a sound, no banner. On a muted emulator, or a phone in a pocket, that is nothing. There are now three channels, so Android's own settings let a person choose per kind:

| Channel | id | Importance | Carries |
|---|---|---|---|
| Replies and letters that need you | `activity-replies` | high (banner) | a prisoner's reply was recorded; a letter came back; someone was moved or freed *and* a letter of yours is waiting (phase 12) |
| Letter progress | `activity-progress` | default | printed, mailed, a directory change decided |
| Group queue | `activity-queue` | default | a letter is waiting for the group |

A batch goes out on the channel of its most important entry. A channel's importance is fixed when it is first created (afterwards only the person can change it), so the old `activity` channel is deleted, not edited. Channels are made when the app starts (`ActivityNotifier.prepare()` from `AbcApplication.onCreate`), so they are listed in system settings before the first notification, and their names follow a change of language.

**With the app open, nothing happened at all.** The notification was posted over the person's own screen, and the screens did not change. Now the repository asks whether anyone is watching:

- `ActivityRepository.arrivals` is a `SharedFlow`. `sync()` sends news there if it has a subscriber, and to the notifier if it has none.
- The only subscriber is the app shell, inside `repeatOnLifecycle(STARTED)`: it exists exactly while the app is visible. A ViewModel must never subscribe: it outlives the screen, would count as "someone is watching" in the background, and would silence every notification.
- The shell raises `LocalNewsTick`; the Inbox, an open conversation and the group's queue reload on it (`ReloadOnNews { … }`). An open conversation that grew scrolls to the new letter.
- If the news is about the conversation on screen, that is all: it appears, and the feed is marked read. Otherwise a bar at the bottom says the same sentence the notification would, with "View".

Checked on the emulator against the FCM-enabled instance: app open on another tab, the bar within a second, badge 1 to 2, no system notification; conversation open, the reply appeared and scrolled into view with no bar; app in the background, a heads-up banner on `activity-replies`, and tapping it opened that conversation. Pinned by `ActivityRepositoryTest` ("with the app on screen the news goes to the screen…").

**Testing without Firebase.** "Simulate a push in 8 s" enqueues the feed check from the background, without the temporary network pass a real FCM message brings, so Android may park the job ("Constraints not met", `onBlockedStatusChanged true` in logcat). That is the simulation, not the feature. Run it by hand: `adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler me.paxana.abcmailbox <job id>` (ids from `adb shell dumpsys jobscheduler | grep abcmailbox`; WorkManager files its jobs under that namespace).
