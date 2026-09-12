# ABC Mailbox for Android

The Android client for Aye Bee See, a correspondence network for political prisoners. Writers find a prisoner, read the facility's mail rules, and write; a support group prints and mails the letter and records the reply.

- `docs/PLAN.md`: what is being built, in what order, and why.
- `docs/DECISIONS.md`: choices that are not obvious from the code (toolchain, crypto library).
- The API lives in `Aye-Bee-See/sqlite-express-api`; its README is the endpoint reference and `android-client-brief.md` (next to this directory) is the map.

## Layout

| Module | What |
| --- | --- |
| `:app` | The Android app: Compose UI, Hilt, Retrofit, Room. Package `me.paxana.abcmailbox`. |
| `:crypto` | Plain JVM Kotlin. Every libsodium call goes through `Sodium.kt`; tests run on the development machine and include fixtures produced by the API's own crypto code. |
| `tools/` | `dev-seed.py` adds development data to a seeded API; `make-interop-fixture.mjs` regenerates the crypto test fixture. |

## Build

Requires JDK 17 or newer on the path (Android Studio's bundled JDK works). Android Studio must be a 2026 release to open the project; the command line needs only the wrapper.

```bash
./gradlew :crypto:test :app:assembleDebug
```

Build for end-to-end encryption mode (the API's future mode) with `-Pe2e=true`.

## Run against a local API

1. In the API repository: `cp .env.example .env`, set `ENCRYPTION_KEY` from `npm run keygen`, then `DB_RESET=true npm start`.
2. `python3 tools/dev-seed.py` adds relay links, a group member (`member1` / `password1`), and a managed writer with a claim token.
3. Start an emulator and install: `./gradlew :app:installDebug`. Debug builds talk to `http://10.0.2.2:3000`, the emulator's name for the host machine.
4. Sign in as `user1` / `password1` for a writer who already has a thread.
