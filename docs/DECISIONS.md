# Decisions

Short records of choices that are not obvious from the code. Newest first.

## 2026-09-20: the guard on "delete my account" is a page, a tick and the password, and nothing slower

**Context.** API PR #104 lets people delete their account with everything in it, irreversibly, and asks clients for "a confirmation that says in words what will be deleted and that it cannot be undone". The people who use this app may be at risk for writing to political prisoners. So the guard has two jobs that pull against each other: it must stop an accident and stop somebody else holding the phone, and it must not stand in the way of an owner who needs to leave now.

**Decision.** A full page, not a dialog (there is too much to say, and dialogs are dismissed by reflex). What goes, in words, with the account's own numbers. What it cannot do (mailed letters are paper). Then two acts: a tick on "I understand that this cannot be undone", and the password. The button is red, says "forever", and is disabled until both. The keyboard's Done key closes the keyboard and does not submit. "Keep my account" is as large as the way through. Afterwards a receipt that stays until closed.

Each act answers one threat. The password answers "somebody else": the server requires it, a stolen token or an unlocked phone is not enough, and guesses are rate limited. The tick answers "by accident", and is separate from the password because a password manager can fill a password field without the person deciding anything.

**Rejected.** A waiting period or an emailed link (the standard pattern for consumer accounts): it keeps a person's letters on a server for days after they decided they must go, and email may not be safe for them either. A countdown on the button: theatre, and the same delay. Typing the username or the word DELETE: friction that proves nothing the password has not proved better. A second "are you sure?" dialog after the button: the page already is that. Queueing the deletion when offline, as letters are: it would mean keeping the password on disk.

**Consequences.** The deletion is only as recoverable as the person's own memory of it, by design; support cannot undo it. The phone-side wipe (`AccountEraser`) must be kept in step with every new store that holds something per account: a new one needs a `forget`/`eraseFor` and a line in `AccountEraserTest`.

## 2026-09-19: push is a doorbell, opt-in, and Firebase starts only when asked

**Context.** The API rings phones through FCM with an empty payload and keeps what happened in a feed. People who write to political prisoners include people who do not want Google to know they have this app.

**Decision.** The feed is the feature; push only makes it prompt. Push is off until the person turns it on, with the trade explained at the switch. Firebase is a dependency of every build but is configured from untracked build settings, not a google-services.json, and its automatic start-up and token fetch are disabled in the manifest, so an install that never opts in never talks to Google. Everything Firebase-specific sits behind `PushProvider`.

**Consequences.** Without push, news can be up to six hours late when the app is closed, which is acceptable for mail that takes weeks. A de-Googled phone works, without push. A future build flavour without Google's libraries, or a UnifiedPush provider, touches one package. The cost of not using the google-services plugin is four values copied by hand, once.

**Rejected.** Registering every signed-in phone automatically (the usual practice, and the wrong default here). Polling more often instead of offering push at all (battery, and still slower). Putting any wording in the push, even generic (the API does not, and iOS's visible alert is its only exception).

## 2026-09-19: the outbox trusts the server's idempotency keys and stops guessing

**Decision.** Supersedes the look-up described in "the outbox never posts a letter twice": with API PR #97 each letter and file carries a key, repeated on every retry, and the comparison of writer, time and text is removed together with its database column. A timed-out send from the compose screen is now queued.

## 2026-09-19: words come from resources through `Strings`, and tests read the same files

**Context.** Three languages. Much of the app's text is produced outside composables, where there is no `Context`.

**Decision.** One injected interface, `Strings` (`get`, `plural`, `byName`, `language`), implemented over Android resources in production and over the resource XML files in JVM tests. Composables use `stringResource()` directly. Mail rules and group services are looked up by a name built from the server's key (`rule_<tag>`, `service_<key>`), protected from the resource shrinker by `res/raw/keep.xml`.

**Consequences.** ViewModels still expose plain `String`s, so screens and tests stayed simple, at the cost that a message already on screen keeps its language if the language changes mid-screen (it is right again at the next action). Tests assert real sentences and would notice a changed one. A new language is a folder of XML.

**Rejected.** A `UiText` sealed type resolved in the UI (correct in every corner, but it touches every state class and every test for a case, changing language mid-screen, that barely occurs). Passing `Context` into ViewModels (leaks, and unusable in JVM tests). This strikes the deferral recorded on the same day in phase 7c, which held only until a language was chosen.

## 2026-09-19: the outbox never posts a letter twice, and prefers waiting to guessing

**Context.** Letters written offline are sent later by a background worker. A request that times out may or may not have arrived, and the API cannot deduplicate.

**Decision.** Record each step (letter, then each file) the moment it succeeds. After an attempt with an unknown outcome, look for the letter on the server before posting again; if the server cannot be asked, wait. Queue from the compose screen only when the request certainly never left the phone. Keep queued letters encrypted under the Keystore key, and in end-to-end mode seal them at send time. Notifications name nobody.

**Consequences.** A letter can be delayed by caution but not doubled by haste. In end-to-end mode the look-up can miss a letter it cannot open, which leaves a small window for a duplicate; an idempotency key on the API would close it (plan, ask 14). WorkManager's minimum back-off means a letter typically leaves within a minute of the network returning, not instantly; "Try to send now" covers someone who is watching.

**Rejected.** Queueing on any network error (a timeout would then create a queued copy of a letter that may already be there). Sealing end-to-end letters when they are written (needs the relay group's public key and key version, which may be missing or rotated by the time there is a connection). Deleting queued letters at sign-out (the writer would lose an evening's letters to a tap).

## 2026-09-19: offline means a downloaded public copy, not an HTTP cache

**Context.** Volunteers write letters together in rooms with no signal and need addresses and mail rules there.

**Decision.** Download the whole public directory into Room and query it locally, network first with the copy as fallback. The download carries no session token, so the copy is exactly what the public sees. Rows are stored as the API's JSON with a few indexed columns.

**Consequences.** Search and filters work offline for every record, not only pages someone happened to open. The API can add fields without a migration here. The cost is a full download (about 100 KB for the development data, of the order of a megabyte for a thousand records) roughly once a day. A record unpublished since the last download stays visible offline until the next one; online, the server's 404 wins.

**Rejected.** An OkHttp response cache (only seen pages, no search). Paging's `RemoteMediator` (built for feeds too large to hold; this directory is small, and per-query remote keys would be more code than the whole copy). Caching whatever the signed-in user can see (would put unpublished records and verification notes on disk).

## 2026-09-19: an `internal` build type stands in for release until there is a domain

**Decision.** Release is strict (HTTPS only, no developer tools) and cannot talk to anything yet. `internal` is shrunk identically but keeps the server override and plain HTTP, with its own application id. Every R8-sensitive path is verified on it.

## 2026-09-17: group and custody keys live in memory only, and are checked when opened

**Context.** A group member reads through up to three keys: their own, the group's (sealed to them), and those of unclaimed writers (sealed to the group). The member's own keypair is already kept on disk under an Android Keystore key so they are not asked for a password at every launch.

**Decision.** Only the member's own key is persisted. `GroupKeyring` opens the group key and the custody keys from the server's copies once per sign-in (one `GET /auth/keys`, one `GET /auth/writers`), holds them in memory, and zeroes them at sign-out. Every sealed private key that is opened has its public key recomputed (`crypto_scalarmult_base`) and compared with the published one. Decoding stays synchronous: repositories call `codec.ready()` first, which is a no-op for writers and in server mode.

**Consequences.** A stolen, unlocked phone yields what the signed-in member could read anyway, and nothing more is written to flash. A member removed from the group loses access at their next launch without any local clean-up. The cost is two small requests per launch for group members. A substituted key blob fails loudly instead of decrypting to garbage or, worse, being used to seal new letters.

**Rejected.** Persisting the group key beside the member's (more secrets on disk for no user-visible gain). Making every decode function `suspend` so keys could load lazily (it ripples through every mapper and paging source for the sake of one fetch).

## 2026-09-17: group key rotation stays on the website

**Decision.** The app does not rotate group keys; it only survives rotations done elsewhere (409 `KeyVersionError`: reload the keyring, re-seal, retry once). Reasons in `docs/PLAN.md`, phase 6b.

## 2026-09-17: call `crypto_pwhash` directly, normalise secrets to NFKC

**Context.** The Kotlin-to-Node interop test failed for a password containing "ä" while ASCII passwords passed. The ionspin binding's `PasswordHash.pwhash` passes `String.length` (UTF-16 units) as the password's byte length, so libsodium hashes a truncated UTF-8 sequence for any non-ASCII password and derives a different key than libsodium.js.

**Decision.** `Sodium.deriveKey` calls the binding's JNA interface directly with the real UTF-8 byte length (JNA as a compile-only dependency; `jna.encoding` pinned to UTF-8), and normalises the secret to Unicode NFKC first, because one visible password can be several code point sequences. Fixtures in both directions use non-ASCII passwords so a regression cannot hide.

**Consequences.** The web client must apply NFKC too (see `docs/MEETING-ITEMS.md`). Worth reporting upstream to the binding. If the binding is ever swapped, this function is the one place to revisit.

## 2026-09-12: minSdk 26

**Context.** The scaffold chose 25 (Android 7.1). API 26 adds `java.time` and `java.util.Base64`, both used by the session and crypto code; below 26 they need backports or `android.util` equivalents.

**Decision.** `minSdk = 26`. Devices below Android 8.0 are a negligible share in 2026, and the libsodium binding's own floor is not lower.

**Consequences.** None expected. Revisit only if a partner group reports users on older phones.

## 2026-09-12: ViewModels depend on a `SessionRepository` interface

**Context.** The first draft injected the concrete repository into `LoginViewModel`, which made the ViewModel untestable without DataStore, Retrofit, and the Keystore.

**Decision.** A small interface (`state`, `login`, `logout`) with `DefaultSessionRepository` bound in Hilt. Tests use a scripted fake. The same shape will be used for every repository that a ViewModel depends on.

## 2026-09-12: build on AGP 9.3.2 / Gradle 9.7 / Kotlin 2.4.20, opting out of AGP 9's built-in Kotlin

**Context.** The scaffold shipped with AGP 8.11.2, the newest version the installed Android Studio (Narwhal 2025.1.1, build 251) can open. Every attempt to keep that ceiling failed in turn: KSP 2.3.x requires AGP 8.12; Hilt 2.59.1+ requires AGP 9.0 and Hilt 2.59 references an AGP class that 8.11 lacks; and finally the current AndroidX core (1.19.0) requires AGP 9.1 while OkHttp 5.5 requires compileSdk 37. Staying on AGP 8 would have meant pinning nearly every library to mid-2025 releases.

**Decision.** AGP 9.3.2 (the newest inside Kotlin 2.4.20's stated range), Gradle 9.7.0, Kotlin 2.4.20, KSP 2.3.12 (built against Kotlin 2.3.20; no KSP exists for 2.4 yet, and it works), Hilt 2.60.1, compileSdk 37. AGP downloaded the Android 37 platform and Build-Tools 36 itself on first build, because the SDK licence files were already accepted.

`gradle.properties` sets `android.builtInKotlin=false` and `android.newDsl=false`. AGP 9 bundles its own Kotlin compiler and a reworked DSL; the opt-out keeps the conventional `kotlin-android` plugin and the `android { }` block every reference still shows. AGP 9.4 still honours the opt-out. Migrating to built-in Kotlin is a separate, deliberate task.

**Consequences.** The project cannot be opened in Android Studio Narwhal 2025.1.1; it needs a 2026 release (the IDE will say so and offer the update). Command-line builds are unaffected. When Studio is updated, revisit AGP 9.4 and the built-in Kotlin migration together.

**Rejected.** Pinning to AGP 8.11 (see context). Adopting built-in Kotlin immediately (unfamiliar DSL for a returning developer, and compiler-plugin support for serialization and Compose under it was not verified).

## 2026-09-12: libsodium binding is `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`

**Context.** The API's end-to-end mode uses libsodium primitives (X25519 sealed boxes, XChaCha20-Poly1305 AEAD, Argon2id). The client must use the same library family so ciphertext interoperates with the web client and the server's migration tooling. Google Play requires native libraries in apps targeting Android 15+ to be aligned to 16 KB pages.

**Measured.** `llvm-readelf -l` on the `.so` files inside each AAR, looking at the `LOAD` segment alignment:

| Artifact | Version | Released | arm64-v8a | armeabi-v7a | x86 | x86_64 |
| --- | --- | --- | --- | --- | --- | --- |
| `com.goterl:lazysodium-android` | 5.1.0 | 2022 | 0x1000 | 0x1000 | 0x1000 | 0x1000 |
| `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings-android` | 0.9.5 | 2025-11-22 | 0x4000 | 0x4000 | 0x4000 | 0x4000 |

0x1000 is 4 KB and fails the requirement; 0x4000 is 16 KB and passes.

**Decision.** Use the ionspin bindings. They load libsodium through JNA, expose the functions we need by their libsodium names, and publish a `-jvm` artifact, so the crypto module's tests run on the development machine without an emulator.

**Consequences.** JNA adds a small native dependency of its own (also 16 KB aligned in current releases; verify when bumping). The library's API is a Kotlin `LibsodiumInterface`; wrap it behind our own `Sodium` interface so a future swap (for example an NDK build with a thin JNI layer, the fallback) touches one file.

**Rejected.** lazysodium-android (alignment, no release since 2022). Building libsodium with the NDK ourselves (a day of work, and a build to maintain, for no gain while a maintained binding exists). BouncyCastle (has X25519 and ChaCha20-Poly1305 but no XChaCha20 or sealed boxes, so we would be assembling primitives by hand).
