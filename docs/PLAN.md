# ABC Mailbox for Android: build plan

Written 12 September 2026 against the API brief (`../android-client-brief.md`), the API on `origin/main` of `sqlite-express-api` (commit `8dbdfce`, after PRs #75 sessions, #76 XChaCha20, #77 sessions review), and the web templates in `solidarity-site (4).zip`. Revised the same day after the cipher fix landed and the libsodium spike finished. This is the map for the Android client; the brief remains the map for the API.

## 1. What we are building, and what we are not

**Goal.** A native Android app that makes writing to a political prisoner feel like sending a text: find the person, read the facility's rules, write, tap send. The relay group prints and mails it.

**Who the app serves, in order.**

| Priority | Role | Why this order |
| --- | --- | --- |
| 1 | Anonymous visitor | The directory is public and useful on its own. It is also the safest surface to build first because it needs no auth or crypto. |
| 2 | `user` (writer) | The core promise: inbox, thread, compose, claim an account, recover a password. |
| 3 | `chapter` (group member) | A phone is the natural tool for a group's two chores that the web does badly: photographing a prisoner's reply to record it, and updating a letter to `printed` / `mailed` from the post office. |
| 4 | `admin` | Not on Android. Moderation queues, audit logs and record editing are desk work; the web admin pages already cover them. |

**Out of scope for v1.** Admin pages; directory editing (staff writes); moderation submissions beyond a simple "submit a correction" form; group settings and profile editing; anything the brief lists as not built (logout revocation, notifications, key rotation).

## 2. Starting point: what is already in this directory

The scaffold is Android Studio's "Empty Activity" (Compose) template: package `me.paxana.abcmailbox`, Kotlin 2.0.21, Android Gradle Plugin 8.11.2, Gradle 8.13, Compose BOM 2024.09, Material 3, `minSdk 25`, `targetSdk 36`. Three AVDs exist (Pixel 9 on API 36, Pixel 6 Pro on API 33, Pixel XL on API 31), Android Studio ships a JDK 21, and Node 26 is installed for running the API.

The scaffold is disposable and nothing in it is worth keeping except the package name and the AVDs. Phase 0 keeps the directory but rewrites the build files: the AndroidX versions in `gradle/libs.versions.toml` are about two years behind the tooling, the JDK target moves to 17, and every dependency the architecture needs is added. `minSdk 25` (Android 7.1) stays; the chosen crypto binding (section 5) supports it.

## 3. What has changed since 2018 (orientation for a returning developer)

Every one of these is a choice this plan makes; the "then" column is what you probably remember.

| Then (2018) | Now (2026) | Why we use the new thing |
| --- | --- | --- |
| XML layouts, `findViewById`, RecyclerView adapters | **Jetpack Compose**: UI is Kotlin functions; state in, events out; `LazyColumn` replaces RecyclerView | Less code, no adapter boilerplate, previews in the IDE. The scaffold already uses it. |
| `AsyncTask`, RxJava, callbacks | **Coroutines and `Flow`**: `suspend fun` for one-shot calls, `StateFlow` for screen state | Structured concurrency ties work to the ViewModel's lifetime, so no leaked requests when a screen closes. |
| ViewModel + LiveData (brand new then) | ViewModel + **`StateFlow`**, one `UiState` class per screen, collected with `collectAsStateWithLifecycle()` | LiveData still works, but Flow composes better with coroutines and Compose. |
| Dagger 2 with components, modules, and a lot of ceremony | **Hilt**: `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel`, `@Inject constructor` | Same Dagger underneath, most of the ceremony generated. |
| Multiple Activities, or Fragments with the Navigation component | **One Activity, Navigation Compose** with type-safe routes (Kotlin `@Serializable` objects) | Back stack, arguments, and deep links in one place; no Fragment lifecycle to fight. |
| `SharedPreferences`; `SQLiteOpenHelper` or early Room | **DataStore** for small settings; **Room** for tables (drafts, cached directory) | DataStore is transactional and coroutine-native. Room is what it was, with Flow return types. |
| Retrofit + OkHttp + Gson | **Retrofit + OkHttp + kotlinx.serialization** | Retrofit is unchanged. kotlinx.serialization is Kotlin-native and handles `data class` defaults without reflection. |
| `build.gradle` in Groovy, versions scattered | **Kotlin DSL** (`.gradle.kts`) and a **version catalog** (`gradle/libs.versions.toml`) | One file lists every version; the build files reference `libs.foo`. |
| `EncryptedSharedPreferences` (arrived 2019) | That library is deprecated. Use the **Android Keystore** directly to protect a small AES key, and encrypt what you store with it | Same underlying hardware-backed storage, no abandoned dependency. |
| Material 2 | **Material 3**, edge-to-edge by default, predictive back | The scaffold's `enableEdgeToEdge()` is this. |
| 32-bit and 64-bit native libs | **16 KB page size**: Android 15+ devices and Google Play require native `.so` files aligned to 16 KB | This decides which libsodium binding we can use (section 5). |

## 4. Architecture

Two Gradle modules: `:app` and `:crypto`. The crypto module is plain JVM Kotlin with no Android dependency, which makes "no Android imports in the crypto code" a compile-time rule and lets its tests run on the development machine. Everything else stays in `:app`, layered by package; more modules would be friction for one developer.

```
me.paxana.abcmailbox
├── di/            Hilt modules (network, database, crypto, session)
├── data/
│   ├── api/       Retrofit interfaces + DTOs, one file per API mount (/auth, /prisoner, ...)
│   ├── db/        Room: drafts, later a directory cache
│   ├── session/   token + current user + unlocked keys, in memory and Keystore-backed
│   └── repo/      Repositories: the only thing ViewModels talk to
├── crypto/        Pure Kotlin e2e module: keypairs, wrap/unwrap, seal/open, XChaCha20 AEAD
├── domain/        Plain models (Prisoner, Facility, Group, Thread, Letter), mode-agnostic
└── ui/
    ├── theme/     Colours and type mirroring the web (off-white, serif headings, red rule)
    ├── nav/       Route definitions, NavHost, deep links
    ├── directory/ prisoners, prisoner, facilities, facility, groups, group
    ├── auth/      login, claim, recover, recoveryCodeShown
    ├── letters/   inbox, thread, compose, attachment viewer
    └── group/     writers, handoff, recordReply, statusUpdate (phase 6)
```

**Data flow in one sentence.** Screen composables render a `UiState` from a `@HiltViewModel`; the ViewModel calls a repository; the repository calls Retrofit (and the crypto module in e2e mode) and maps DTOs to domain models; errors become a sealed `AppError` the screen knows how to show.

### The API's conventions, and how they land in code

- **Envelope.** Every response is `{data, info, success, status, name}`. One generic `ApiEnvelope<T>` DTO; lists use `PagedEnvelope<T>` adding `total`, `page`, `page_size`. Repositories unwrap to `T` or throw.
- **Errors.** A Retrofit `CallAdapter` (or a small wrapper) turns HTTP status into `AppError`: `Validation(errors: List<String>)` for 400, `Unauthorized` for 401 (clears the session), `Forbidden(info)` for 403 (the message is shown verbatim, per the brief), `NotFound`, `Conflict(info)` for 409, `Gone` for 410, `Network`, `Server`. The `info` sentence doubles as toast text.
- **No path parameters.** GET selectors are `@Query`; PUT and DELETE bodies are JSON. Retrofit needs `@HTTP(method = "DELETE", hasBody = true)` for the latter; OkHttp sends it.
- **Auth.** An OkHttp interceptor adds `Authorization: Bearer` when a token exists. A second interceptor watches for 401 and signals the session to sign out. No refresh: the token lives a week, then the user logs in again. Logout and admin revocation exist (PR #75), so a 401 mid-session is normal and must land the user on the sign-in screen without losing a draft.
- **Pagination.** Paging 3 with a `PagingSource` per list endpoint; `page` and `page_size` map directly. Chats keep server order (the brief warns not to re-sort).
- **`full=true`.** Used on single reads only, exactly as the brief says. The prisoner repository fetches `full=true` to get `prison_details`, `support_groups`, and (via the prison) `relay_groups` for the compose screen's relay picker.

### Session and secrets

- Token, user id, role, and `chapterId` go in DataStore, encrypted with a Keystore-held AES key. Not because the token is a crypto secret, but because a stolen token is a week of access.
- Sign out calls `POST /auth/logout` (added in PR #75) so the token dies server-side, then clears local state. "Sign out everywhere" sends `{"everywhere": true}`. A password change ends every other session and returns a fresh token, which the session store must adopt.
- In e2e mode the unwrapped private key (and the group key for members) lives in a process-scoped `KeyHolder`. Phase 5 adds an opt-in "stay unlocked on this device" that caches it under a Keystore key gated by device credential or biometric. Never plain disk, per the brief.
- Letter drafts are the one place plaintext must touch disk. They are stored in Room encrypted with the same Keystore-held key, so a lost phone does not leak an unsent letter.

### Encryption mode as a feature flag

The API runs server mode today and e2e later. Two build-time and one runtime switch:

- `BuildConfig.ENCRYPTION_MODE` defaults to `server`; a `-Pe2e=true` Gradle property (or a product flavour) flips it.
- Repositories depend on a `LetterCodec` interface with two implementations: `PlainCodec` (passes `messageText` through) and `E2eCodec` (crypto module). ViewModels never know which is active.
- `GET /health` reports `encryptionMode`; phase 5 checks it at startup against the build's mode and refuses to run against the other.

## 5. Cryptography on Android

The brief mandates libsodium primitives so the web, the API, and the phone interoperate. The reference is `test/e2e-client.js` in the API repository, and the server's `services/crypto.js` fixes the wire formats:

| Item | Wire format |
| --- | --- |
| Public and private keys, ciphertexts, nonces, salts | Standard base64 with padding |
| Wrapped private key | A JSON string `{"ciphertext": b64, "nonce": b64}` of `crypto_aead_xchacha20poly1305_ietf_encrypt(privateKey, ad=null, nonce, derivedKey)` |
| `kdfSalt` | base64 of 16 random bytes |
| `kdfParams` | Any JSON the client wants back. Ours: `{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}` |
| Envelope `wrappedKey` | base64 of `crypto_box_seal(contentKey, readerPublicKey)` |
| Body, relay note, file | `crypto_aead_xchacha20poly1305_ietf_encrypt` with no associated data, a 24-byte random nonce, and a 32-byte content key; a fresh nonce per item |
| Claim token | 24 chars from `0-9 A-Z` minus `I L O U`; `tokenHash` is SHA-256 hex of the trimmed, upper-cased token |

**Two things to be careful about.**

1. **Cipher, resolved.** The first draft of this plan found that the server called `crypto_secretbox_easy` (XSalsa20-Poly1305) while every document said XChaCha20-Poly1305. The API side fixed the code rather than the docs in PR #76, with a reversible migration of existing ciphertext, because it is only cheap while the server still holds every content key. The client must therefore use the XChaCha20 AEAD (`crypto_aead_xchacha20poly1305_ietf_*`) and never `crypto_secretbox`; the two refuse each other's output. The corrected brief says the same. Note that your local `main` of the API repo is behind `origin/main` and still has the old call; pull before running the API for e2e work.
2. **`kdfParams`, decided.** Both clients write `{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}` (`KdfParams` in the crypto module pins it). Argon2id at libsodium's `INTERACTIVE` cost (opslimit 2, 64 MiB) runs in well under a second on a mid-range phone; `MODERATE` (256 MiB) will not fit on low-end devices. Costs are stored per account, so they can rise for new accounts later.

**Library choice, decided by the phase 0 spike.** `com.goterl:lazysodium-android` 5.1.0 ships native libraries aligned to 4 KB (`LOAD` align `0x1000`), which fails the 16 KB page-size requirement Google Play applies to apps targeting Android 15 and later. `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` 0.9.5 (November 2025) ships all four ABIs at `0x4000`, exposes the exact functions we need (`crypto_box_keypair`, `crypto_box_seal`/`_open`, `crypto_aead_xchacha20poly1305_ietf_encrypt`/`_decrypt`, `crypto_pwhash`, `randombytes_buf`), and has a JVM artifact for host-side unit tests. It is the choice; details and the measurements are in `docs/DECISIONS.md`. The fallback, if it ever goes unmaintained, is building libsodium with the NDK and a thin JNI layer.

**Interop test, not just unit tests.** A Node script in `tools/` uses the API's own `e2e-client.js` to emit fixtures: a keypair, a password-wrapped key, a sealed envelope, an XChaCha20-Poly1305 body. The Kotlin tests must open all of them, and the reverse (Kotlin emits, Node opens) runs the other way. This is the test that proves a letter written on the phone prints at the group.

**Flows the module exposes** (each maps to a numbered flow in brief section 7): `register`, `unlockAfterLogin`, `openGroupKey`, `encryptLetter(readers)`, `decryptLetter`, `forward`, `encryptFile` / `decryptFile`, `prepareManagedWriter`, `makeClaimToken`, `claim`, `recover`. All pure functions over byte arrays and strings; no Android imports, so they run as plain JVM tests.

## 6. Screens, mapped from the web templates

The web templates are the spec for copy and hierarchy. Mobile changes the shape, not the content. The design tokens (off-white `#f2f0ed`, near-black text, red `#b33a3a` rule and alerts, serif headings, mono for tokens) become the Compose theme; dynamic colour from the scaffold is turned off so the app looks like the site.

| Web page | Android screen | Notes |
| --- | --- | --- |
| `index.html` | Home tab: featured prisoners, "find a group" | Skip the ABCF news feed in v1. |
| `prisoners.html` | Prisoners list: search box, country and status chips, paged | `q`, `country`, `status`, `featured` map straight to the API. Status notices show as the red banner from the web. |
| `prisoner-profile.html` | Prisoner detail: photo, facts card, about, interests, facility rules, support groups, "Write a letter" button | `full=true`. The web "Print info sheet" becomes a share/PDF action later. |
| `prisons.html`, `facility-profile.html` | Facilities list and detail | Stale-verification banner (`stale=true` filter and `verifiedAt` age). |
| `groups.html`, `group-profile.html` | Groups list and detail | Announcement banner, services chips, links open in the browser. |
| `login.html` | Sign in | Username and password. "Accounts are created by groups" copy stays. |
| `inbox-writer.html` | Inbox tab | One row per thread: prisoner, direction arrow, last activity. FAB "New letter". |
| `thread.html` | Thread | Letters as a timeline; status chip (`queued`, `printed`, `mailed`, `received`); status history in an expandable row; attachments open in a viewer; reply composer at the bottom. |
| `new-letter.html` | Compose | Prisoner picker (search), facility rules card, body with character and page estimate, optional note to relay group, attach a file or photo, relay-group picker only when the facility has more than one relay group (or is `relay_only`). |
| `claim.html` | Claim account | Enter token (or arrive by deep link), choose username and password, the "your password is your key" warning with the checkbox, then the recovery code shown once with a forced acknowledgement. |
| `forgot-password.html` | Recover | Username plus recovery code; sets a new password. The web page's copy predates recovery codes and needs updating on both clients. |
| `inbox-org.html` | Group inbox (phase 6) | Sections per managed writer plus "Anonymous letters"; queue of letters to print. |
| `create-writer.html`, `handoff.html` | Add writer, Hand off (phase 6) | Token shown large in mono; copy button; "never send electronically" warning. |
| (none) | Record a reply (phase 6) | Camera or file picker, converts to JPEG or PDF, posts `sender: prisoner` with the attachment. This is the feature only a phone does well. |

Navigation: bottom bar with Directory, Inbox, Account (the Inbox tab shows a sign-in prompt when signed out). Directory has its own nested stack. `abcmailbox.net/claim?token=` is a deep link into Claim (needs an `assetlinks.json` on the web host for verified App Links; unverified links still work via a chooser).

## 7. Phases

Each phase ends with something you can run on the Pixel 9 emulator against the API on `localhost:3000` (`10.0.2.2:3000` from the emulator).

**Phase 0: foundations. Done 12 September 2026.**
Delivered: git repository; Gradle 9.7 / AGP 9.3.2 / Kotlin 2.4.20 toolchain with a version catalog (the AGP 9 move and its reasons are in `docs/DECISIONS.md`; Android Studio needs updating to a 2026 release to open the project); Hilt, Navigation Compose, Retrofit, OkHttp, kotlinx.serialization, Paging, Room, DataStore, Coil, Turbine and MockWebServer wired in; `debug` build config pointing at `http://10.0.2.2:3000` with a network security config allowing cleartext only to that host; `BuildConfig.ENCRYPTION_MODE` driven by `-Pe2e=true`; the `:crypto` module with a `Sodium` facade over the ionspin libsodium binding and seven passing tests, including two that open ciphertext produced by the API's own `services/crypto.js` (`tools/make-interop-fixture.mjs`); `tools/dev-seed.py` (section 7a), idempotent and verified against a fresh seed.

**Phase 1: API client, session, theme, shell. Done 12 September 2026.**
Delivered: `ApiEnvelope` and `Page` types; `AppError` and `ApiResult` with `apiCall` mapping every HTTP status the brief lists; `AuthApi` (login, logout, user); `SessionInterceptor` that attaches the bearer token and reports a refused one; `SessionCache` to break the Retrofit/repository cycle; `SessionStore` (DataStore, AES-GCM under an Android Keystore key); `SessionRepository` interface with `DefaultSessionRepository`; Hilt modules; the theme from the site's palette with dynamic colour off; three-tab shell with type-safe routes; sign-in, account (sign out here or everywhere), and placeholder Directory and Inbox screens. Verified on the emulator against the local API: wrong password, correct password, session surviving a cold restart, server-side logout. Tests: 20 JVM unit tests (envelope, error mapping, interceptor with MockWebServer, login ViewModel with Turbine) and one instrumented Keystore round trip. `minSdk` raised to 26.

**Phase 2: public directory. Done 12 September 2026.**
Delivered: `DirectoryApi` with DTOs matching the live JSON; domain models (`Prisoner`, `Facility`, `Group`, `MailRule`) with the derived facts screens need (address lines from the free-form object, parsed dates, six-month staleness, routing labels); `PagePagingSource` and a `DirectoryRepository` exposing Paging 3 flows and single reads; a nested Directory navigation graph; the home page with featured prisoners; three list screens with search (debounced), filter chips, and infinite scroll; three detail screens (the prisoner page makes a second read for the facility's rules and relay groups). All work signed out. Verified on the emulator against the seeded API. Tests: 14 new JVM tests (mappers over captured fixtures, paging source, repository against MockWebServer), 34 in total.

**Phase 3: writer letters, server mode. Done 13 September 2026.**
Delivered: `LettersApi` (chats, send, edit, DELETE with a JSON body, multipart upload, streaming download, retention); domain `Thread` and `Letter` with a status enum and the "editable only while queued" rule; `resolveRelay`, a pure mirror of the API's relay rules so the compose screen explains the routing before sending; encrypted drafts in Room keyed by account and prisoner, autosaved on a pause and deleted on send; an Inbox graph with the inbox, a prisoner picker, and thread and compose screens reachable from both tabs; the thread shows the retention window from `GET /messaging/retention` (API PR #78). Verified on the emulator against the API: inbox, thread, compose with automatic relay, send, the thread refreshing, edit mode, and delete with confirmation. Attachments are covered by wire-level tests and a manual curl session; the file-picker UI path has not yet been exercised on a device. Tests: 20 new JVM tests (relay rules, mappers over captured fixtures, repository against MockWebServer including the DELETE body and multipart layout, compose ViewModel with fakes), 55 in total, plus an instrumented Room-and-Keystore drafts round trip.

**Phase 4: account flows (2 days).**
Claim (typed token and deep link; 404 and 410 states), recovery (server mode: not available; the screen explains, matching the web), password change (adopting the fresh token the API returns), sign out via `POST /auth/logout` with a "sign out everywhere" option, "session expired" handling. Register stays hidden behind a flag because the site says accounts come from groups.

**Phase 5: end-to-end mode (5 to 7 days, can start in parallel from phase 1).**
Crypto module and interop fixtures first; then `E2eCodec`; then the flows: unlock at login, recovery code at claim, envelopes on send (writer, relay group, managing group), decrypt on read, encrypted attachments, recovery with the sealed challenge. Switch the flag and run the whole phase 3 suite again in e2e. Optional Keystore-cached unlock.

**Phase 6: group member features (5 to 7 days).**
Group inbox; writers list; add writer (with keypair generation in e2e); hand off with token display and regenerate/revoke; move a letter to `printed` and `mailed`; record a reply from the camera; forward to a partner group (`POST /messaging/envelope`); group-key bootstrap and member hand-out (`PUT /auth/chapter-keys`, `PUT /auth/member-key`). The group-status 403 messages are shown verbatim.

**Phase 7: offline and release (3 to 4 days).**
Room cache of the directory for letter-writing nights with no signal; pull-to-refresh; app icon; release signing; ProGuard rules for serialization; a first internal build.

Total: roughly five to six working weeks for one developer, with e2e and group features being the two largest blocks.

## 7a. Development data

Nothing new is needed from the API for the writer scope. A fresh database (`DB_RESET=true`) seeds:

- `admin` / `abcpassword`, and forty writers `user1` ... `user40` with passwords `password1` ... `password40`;
- 40 prisoners in 52 prisons (generic Greek-letter names), 44 unattached rules, one chapter, and 40 chats each holding one letter from the writer side.

So signing in as `user1` gives an inbox with a thread on day one. Ids are not stable across seed runs; read them from responses.

What the seed does not give us, and what `tools/dev-seed.py` adds by calling the API as `admin` after boot: rules attached to a few prisons, two or three relay links so the relay-group picker and `relay_only` refusal can be exercised, one prisoner marked `featured` with a status notice for the list banner, a `chapter` member account in the seeded group (`POST /auth/user` with `role: chapter, chapterId`), and a managed writer with a claim token for the claim screen. The script is idempotent and runs in seconds; it is ours to maintain, so it never blocks on the API agent. If it later proves useful to the web developer, it can move into the API repo as an optional seed.

## 8. Questions and asks for the API and web developers

1. **Cipher naming: done.** PR #76 moved the server to `crypto_aead_xchacha20poly1305_ietf` and corrected every document. The web developer needs to hear that the function name in the design document changed.
2. **`kdfParams` schema: decided.** `{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}` for both clients (API PR #80); the server checks only that it is an object with a string `kdf`. The crypto module's `KdfParams` class pins the exact JSON.
3. **Mode discovery: decided.** `GET /health` now returns `encryptionMode` (`server` or `e2e`), 503 while starting (API PR #80). Phase 5 reads it at startup and refuses to run a client built for the other mode.
4. **Claim deep links.** Confirm the claim URL (`abcmailbox.net/claim?token=`) and host an `assetlinks.json` so Android can verify the link.
5. **Attachments.** Report question 3: phones produce JPEG or HEIC. The app will always convert to JPEG or PDF, so no HEIC support is needed server-side. 10 MiB is tight for a multi-page scan; 20 MB as the templates say would help.
6. **Recovery rate limiting.** Not built yet; the phone app will not add client-side throttling, so this is worth doing before e2e goes live. (Logout and revocation, previously on this list, shipped in PR #75.)
7a. **Thread reads lack two small things.** Messages embedded in `GET /chat/chat?full=true` carry `relayChapter` as an id only (no `relay_group {id, name}` as the single message read has), so the thread says "the relay group" instead of its name. And `prisoner_details` on chat rows lacks the light `prison_details` that prisoner list rows now have, so the inbox cannot show the facility. Both are the same shape as PR #79.
7. **List rows lack the facility: done.** API PR #79 puts a light `prison_details` (`id`, `prisonName`, `country`, `routing`) on every prisoner list row; the client needed no change beyond a test. Still open: a facet endpoint (distinct countries, or counts per filter) so the country chips can be data-driven instead of hard-coded.
8. **`forgot-password.html` copy** predates recovery codes and says there is no recovery path. Both clients should say: independent accounts recover with the recovery code; managed unclaimed accounts get a new claim token.

## 9. How work will be shown as it happens

Each phase gets a short write-up in `docs/` explaining the choices made and the Android concepts involved, in the spirit of section 3. Commits are small and named by the thing they add. Every phase ends with a run on the emulator and a note on what to try by hand.
