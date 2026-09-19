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

Nothing below has been run against a real project yet (as of 19 September 2026 none exists). The code that has not been exercised is three calls in `data/push/Fcm.kt`: starting Firebase from the four values, asking for a token, deleting it.

1. Build and install. Account tab: the "Instant notifications" switch is enabled (not "Not available in this build").
2. Turn it on. Expect "Turned on." If it says the server cannot ring phones yet, the API has no service-account file: `GET /health` should list `"push": ["fcm"]`.
3. `GET /auth/devices` as that account lists the phone.
4. Put the app in the background. From another account, do something the first one is told about (a group member marks their letter printed). The notification should arrive within seconds, not hours.
5. Turn it off. `GET /auth/devices` no longer lists the phone, and step 4 no longer rings it (the six-hourly check still will).
6. Sign out and in as someone else with push on: the device moves to them (`GET /auth/devices` for each).
7. Try a phone without Google services if one is to hand: the switch is disabled and says why.

Until then, the hidden developer dialog (five taps on the build line) has "Simulate a push in 8 s", which does exactly what the FCM service does on a ring.
