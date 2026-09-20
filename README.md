# ABC Mailbox for Android

The Android client for Aye Bee See, a correspondence network for political prisoners. Writers find a prisoner, read the facility's mail rules, and write; a support group prints and mails the letter and records the reply.

- `docs/PLAN.md`: what is being built, in what order, and why.
- `docs/PUSH.md`: what push does and does not carry, and how to turn it on once a Firebase project exists.
- `docs/TRANSLATING.md`: the three languages, how to review or add one, and the glossary.
- `docs/DECISIONS.md`: choices that are not obvious from the code (toolchain, crypto library).
- The API lives in `Aye-Bee-See/sqlite-express-api`; its README is the endpoint reference and `android-client-brief.md` (next to this directory) is the map.

## Layout

| Module | What |
| --- | --- |
| `:app` | The Android app: Compose UI, Hilt, Retrofit, Room. Package `me.paxana.abcmailbox`. |
| `:crypto` | Plain JVM Kotlin. Every libsodium call goes through `Sodium.kt`; tests run on the development machine and include fixtures produced by the API's own crypto code. |
| `tools/` | `dev-seed.py` adds development data to a seeded API; `capture-contract.py` with `ContractCheckTest` checks the app against a running API after the API changes; `make-interop-fixture.mjs` regenerates the crypto test fixture. |

## Build

Requires JDK 17 or newer on the path; use a long-term-support release (17, 21, 25) or Android Studio's bundled one (`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). OpenJDK 20.0.2 on Apple silicon crashes the Gradle daemon during `lint` (a JIT bug: `Field too big for insn` in `hs_err_pid*.log`); that is the JDK, not the project. Android Studio must be a 2026 release to open the project; the command line needs only the wrapper.

```bash
./gradlew :crypto:test :app:assembleDebug
```

### Build types

| Type | For | Shrunk (R8) | Server | Installs as |
| --- | --- | --- | --- | --- |
| `debug` | development | no (about 24 MB) | emulator's host by default; hidden server dialog on the Account tab (five taps on the build line) | `me.paxana.abcmailbox` |
| `internal` | testers, and checking the shrunk build, until there is a domain | yes (about 4 MB) | same as debug, plain HTTP allowed | `me.paxana.abcmailbox.internal` |
| `release` | the store | yes | HTTPS only; `-PapiBaseUrl=https://…` sets the address | `me.paxana.abcmailbox` |

```bash
./gradlew :app:assembleInternal
```

Release signing reads `keystore.properties` (copy `keystore.properties.example`; git ignores the real one) or the `ABC_KEYSTORE_*` environment variables. Without them `assembleRelease` produces an unsigned APK. Every push runs the tests, lint and the internal build on GitHub Actions and keeps the APK as an artifact.

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
2. `python3 tools/dev-seed.py` adds relay links, a group member (`member1` / `password1`), and a managed writer with a claim token.
3. Start an emulator and install: `./gradlew :app:installDebug`. Debug builds talk to `http://10.0.2.2:3000`, the emulator's name for the host machine.
4. Sign in as `user1` / `password1` for a writer who already has a thread.

## Run on a physical phone

Debug builds have a hidden server setting: on the Account tab, tap the "Build … mode" line five times. Enter the address of the machine running the API and tap "Save and check"; the app calls `/health` there and reports the result. Saving signs you out, because a token is only valid for the server that issued it. Two ways to reach your machine:

- **USB, any network.** With the phone connected and USB debugging on, run `adb reverse tcp:3000 tcp:3000` and enter `127.0.0.1` in the dialog. The phone's own port 3000 is tunnelled to the machine. The tunnel lasts until the cable is unplugged or `adb reverse --remove-all`.
- **Same Wi-Fi.** Enter the machine's LAN address (for example `192.168.1.20`; port 3000 is assumed). Both devices must be on the same network, the API must listen on all interfaces (it does), and the machine's firewall must allow incoming connections for node.

Install the debug APK with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`, or run from Android Studio with the phone selected. Debug builds allow plain HTTP to any host; release builds do not.
