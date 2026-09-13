# Decisions the Android client needs (agenda items)

Raised 12 September 2026 from phases 0 to 2. Each item names who it affects and the default the Android client uses until it is decided.

## Encryption contract (web + Android, README on the API side)

- ~~`kdfParams` schema~~ **Decided 12 Sep 2026 (API PR #80):** `{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}`; the server checks it is an object with a string `kdf`. The web developer still needs to hear it.
- ~~Encryption mode discovery~~ **Decided (API PR #80):** `GET /health` returns `encryptionMode`.
- **Cipher change notice for the web developer.** The design document now names `crypto_aead_xchacha20poly1305_ietf`, not `crypto_secretbox`. The two will not interoperate. Confirm the web client is on the corrected function.

## Account flows (web + Android + API)

- **Claim deep link.** Confirm the URL (`abcmailbox.net/claim?token=`) and host an `assetlinks.json` on that domain so Android can verify the link. Until then the link opens through a chooser.
- **Recovery copy.** `forgot-password.html` still says there is no recovery path. With recovery codes built, both clients should say: independent accounts recover with the recovery code; unclaimed managed accounts get a new claim token from their group.
- **Recovery rate limiting** is not built. The phone app adds no client-side throttling, so this should exist before e2e goes live.
- **Self-registration.** The API allows public `POST /auth/user`; the site says accounts come from groups. Android hides registration behind a flag. Decide whether it should ever be exposed.

## Letters (API, affects phase 3)

- **Attachment limit and types.** Templates say 20 MB; the API caps at 10 MiB. Multi-page scans from a phone camera are large. Android will always convert to JPEG or PDF, so HEIC is not needed server-side. Ask: raise to 20 MB.
- **Retention.** API PR #78 purges mailed letters and replies after a per-writer window. Decide what a thread should show in place of purged letters (a count, a date, nothing). Android will show a one-line note.

## Letters, small (API)

- **Thread reads:** embed `relay_group {id, name}` on messages inside `GET /chat/chat?full=true`, and the light `prison_details` on `prisoner_details` of chat rows, so the thread can name the relay group and the inbox can show the facility. Same pattern as PR #79.

## Directory (API)

- **Facet data for filters.** The country chips on the lists are hard-coded from the web mock-ups. A facet endpoint (distinct countries, or counts per filter value) would make them data-driven. Low priority.

## Decisions the Android side made alone (confirm or object)

- **Scope order:** anonymous directory, then writer flows, then group-member flows. Admin stays web-only.
- **Android 8.0 (API 26) minimum.** Devices below are negligible in 2026 and it removes a class of workaround code.
- **AGP 9 toolchain.** Current AndroidX releases require it; Android Studio must be a 2026 release to open the project.
- **libsodium binding:** `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`, because lazysodium-android is not 16 KB page-aligned and Google Play now requires that.
