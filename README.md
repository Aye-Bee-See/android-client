# PaperKites for Android

The Android client for PaperKites (`paperkites.org`), a correspondence network for political prisoners. Writers find a prisoner, read the facility's mail rules, and write; a support group prints and mails the letter and records the reply. The project was called ABC Mailbox, then letters.support, before 16 October 2026: the code's package (`me.paxana.abcmailbox`), the `abcmailbox://` links and some file names still say so, and none of them is seen by the people using the app. The app identifier, the name a phone and a store know it by, is `org.paperkites.app` (decided 12 November 2026; it cannot change after the first store upload).

- `docs/PLAN.md`: what is being built, in what order, and why.
- `docs/PUSH.md`: why the app has no push notifications, and how it learns of news instead.
- `docs/TRANSLATING.md`: the three languages, how to review or add one, and the glossary.
- `docs/DECISIONS.md`: choices that are not obvious from the code (toolchain, crypto library).
- The API lives in `Aye-Bee-See/sqlite-express-api`; its README is the endpoint reference and `android-client-brief.md` (next to this directory) is the map.

## Layout

| Module | What |
| --- | --- |
| `:app` | The Android app: Compose UI, Hilt, Retrofit, Room. Package `me.paxana.abcmailbox`. |
| `:crypto` | Plain JVM Kotlin. Every libsodium call goes through `Sodium.kt`; tests run on the development machine and include fixtures produced by the API's own crypto code. |
| `tools/` | `dev-seed.py` adds development data to a seeded API; `capture-contract.py` with `ContractCheckTest` checks the app against a running API after the API changes; `make-interop-fixture.mjs` regenerates the crypto test fixture; `split-auth-key.mjs` derives the auth key a split-scheme account signs in with, for scripts (API PR #114). Invite codes (API PR #116) need no tool: a group admin makes a batch under Account → Invite codes, and the writer's screen is "I have an invite code" on the sign-in page.

## Build

Requires JDK 17 or newer on the path; use a long-term-support release (17, 21, 25) or Android Studio's bundled one (`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). OpenJDK 20.0.2 on Apple silicon crashes the Gradle daemon during `lint` (a JIT bug: `Field too big for insn` in `hs_err_pid*.log`); that is the JDK, not the project. Android Studio must be a 2026 release to open the project; the command line needs only the wrapper.

```bash
./gradlew :crypto:test :app:assembleDebug
```

### Build types

| Type | For | Shrunk (R8) | Server | Installs as |
| --- | --- | --- | --- | --- |
| `debug` | development | no (about 24 MB) | emulator's host by default; hidden server dialog on the Account tab (five taps on the build line) | `org.paperkites.app` |
| `internal` | testers, and checking the shrunk build | yes (about 5 MB) | the test API, `https://abctest.letters.support/`, always; plain HTTP allowed and the server dialog kept, so it can still reach a laptop | `org.paperkites.app.internal` |
| `release` | the store | yes | production, `https://api.paperkites.org/`; HTTPS only, no server dialog | `org.paperkites.app` |

```bash
./gradlew :app:assembleInternal
```

### Servers

- **Production** is `https://api.paperkites.org/` (the website is `https://paperkites.org`), live since 13 November 2026. It holds real accounts, runs end-to-end, refuses accounts that would send their password (`REQUIRE_SPLIT_AUTH`), sends no push, and is updated only when the owner runs an update by hand, so it can be behind the API's `main`. **Do not test against it**: nothing made there can be assumed to be removable.
- **The test API** is `https://abctest.letters.support/`. It follows the API's `main` every hour and is where testing happens. The `internal` build talks to it, and nothing on the command line changes that.

The store build needs no flag:

```bash
./gradlew :app:assembleRelease
```

`-PapiBaseUrl=https://…` still overrides the address for a release-shaped build against another server (`./gradlew :app:assembleRelease -PapiBaseUrl=https://abctest.letters.support/`). It is deliberately the exception and not the rule: a flag that has to be remembered for the store build is one day forgotten, and people would then make their accounts on the test server.

### Links that open the app

The website hands out three links, each built from its own address: `https://paperkites.org/join?code=…` (the QR code on an invite slip), `/claim?token=…` (a handoff) and `/invitation?token=…` (a volunteer or a new group). The app answers all three, and prints the same join link on the slips it makes. `abcmailbox://join` and `abcmailbox://claim` still work and need nothing from a server.

For Android to open the https links in the app without asking, `https://paperkites.org/.well-known/assetlinks.json` has to name the app, as JSON (`Content-Type: application/json`, no redirect):

```json
[{ "relation": ["delegate_permission/common.handle_all_urls"],
   "target": { "namespace": "android_app", "package_name": "<the app identifier>",
               "sha256_cert_fingerprints": ["<SHA-256 of the certificate the installed app is signed with>"] } }]
```

The identifier is `org.paperkites.app`. What is left to settle is the signing key, which is the owner's. The fingerprint is read with `keytool -list -v -keystore <the keystore> -alias <the alias>` (the `SHA256:` line), or `./gradlew :app:signingReport` once `keystore.properties` exists. If the app goes through Google Play with Play App Signing, the certificate on people's phones is Google's app signing key, not the upload key: the fingerprint to publish is then the one Play Console shows under App integrity, and the upload key's is added beside it only if builds are also handed out directly. Until the file is there, Android 12 and later open these links in the browser, where the website handles them; `adb shell pm get-app-links <identifier>` shows what Android made of the file.

Release signing reads `keystore.properties` (copy `keystore.properties.example`; git ignores the real one) or the `ABC_KEYSTORE_*` environment variables. For Google Play that key is the upload key: Google's app signing key signs what people install, and a lost upload key can be replaced through Play support. Only a build handed out directly is signed by this key itself, and such an install can only ever be updated by the same key. Without them `assembleRelease` produces an unsigned APK. Every push runs the tests, lint and the internal build on GitHub Actions and keeps the APK as an artifact.

### Store listing

`fastlane/metadata/android/` holds what a store shows, in the layout both F-Droid and fastlane's `supply` read: for each of `en-US`, `es-ES` and `ru-RU`, a `title.txt` (30 characters at most on Google Play), a `short_description.txt` (80), a `full_description.txt` (4,000), `changelogs/<versionCode>.txt`, and seven screenshots under `images/phoneScreenshots/`. Nothing in the build reads these files; they are pasted or uploaded.

The screenshots show invented people, prisons and a group, on a local API with no real data: the API's own seed is real prisoners and must never appear in a store. They are 1080 × 1920 because Google Play refuses a screenshot more than twice as tall as it is wide, which a current phone's screen is (`adb shell wm size 1080x1920` for the session, `wm size reset` afterwards); the status bar is System UI's demo mode; the language is set per app with `adb shell cmd locale set-app-locales <identifier> --locales es`.

Still to make: the 512 × 512 icon and the 1024 × 500 feature graphic Google Play asks for. The Spanish and Russian text was written with the app's glossary (`docs/TRANSLATING.md`) and, like the app's own strings, wants a native speaker's read before release.

### Tests

```bash
./gradlew :crypto:test :app:testDebugUnitTest :app:lintDebug
```

```bash
./gradlew :app:connectedDebugAndroidTest
```

The second needs an emulator or phone (Room SQL, the database migration, the Keystore) and uninstalls the debug app when it finishes. After the API changes, run the contract check described in `tools/capture-contract.py`.

One build works against both API modes: the app asks `GET /health` which letter contract the server speaks (`server` or `e2e`) and encrypts on the device when it must. The crypto is proven against libsodium.js in both directions; see `tools/` (`npm install` there once, then `node make-e2e-fixture.mjs`, `node verify-kotlin-fixture.mjs`, `node verify-account.mjs`; for the group side, `node verify-claim-token.mjs`, `node verify-member-holds-group-key.mjs`, `node verify-group-can-read.mjs`).

## Run against a local API

1. In the API repository: `cp .env.example .env`, set `ENCRYPTION_KEY` from `npm run keygen`, then `DB_RESET=true npm start`.
2. `python3 tools/dev-seed.py` adds relay links, a group member (`member1` / `password1`), and a managed writer with a claim token. On an API that knows the split sign-in scheme it makes `member1` that way; on an end-to-end API it cannot (an account there must come with keys, made on the holder's device), so run it before `REQUIRE_SPLIT_AUTH` is switched on, or with it off: under the flag nobody makes an account for someone else, by design (the API's brief, item 24).
3. Start an emulator and install: `./gradlew :app:installDebug`. Debug builds talk to `http://10.0.2.2:3000`, the emulator's name for the host machine.
4. Sign in as `user1` / `password1` for a writer who already has a thread.

## Run on a physical phone

Debug builds have a hidden server setting: on the Account tab, tap the "Build … mode" line five times. Enter the address of the machine running the API and tap "Save and check"; the app calls `/health` there and reports the result. Saving signs you out, because a token is only valid for the server that issued it. Two ways to reach your machine:

- **USB, any network.** With the phone connected and USB debugging on, run `adb reverse tcp:3000 tcp:3000` and enter `127.0.0.1` in the dialog. The phone's own port 3000 is tunnelled to the machine. The tunnel lasts until the cable is unplugged or `adb reverse --remove-all`.
- **Same Wi-Fi.** Enter the machine's LAN address (for example `192.168.1.20`; port 3000 is assumed). Both devices must be on the same network, the API must listen on all interfaces (it does), and the machine's firewall must allow incoming connections for node.

Install the debug APK with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`, or run from Android Studio with the phone selected. Debug builds allow plain HTTP to any host; release builds do not.
