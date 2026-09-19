# Decisions the Android client needs (agenda items)

Raised 12 September 2026 from phases 0 to 2. Each item names who it affects and the default the Android client uses until it is decided.

## Encryption contract (web + Android, README on the API side)

- **Passwords must be hashed identically on both clients (found 17 Sep 2026).** Two rules, both now implemented on Android and proven against libsodium.js in both directions: normalise every secret to Unicode **NFKC** before key derivation (`secret.normalize('NFKC')` in JavaScript), and hash its full **UTF-8** bytes. Without the first, the same visible password typed on two keyboards derives two keys. The second is where the Android libsodium binding had a bug (it passed the UTF-16 length, truncating any non-ASCII password); the app now calls the native function directly. Ask: the web client adopts NFKC, and the README states both rules next to the `kdfParams` schema.
- **Typed codes are normalised before derivation.** Claim tokens and recovery codes go into Argon2id as upper case, letters and digits only. "abcd-efgh" and "ABCDEFGH" must be one code on both clients. The token hash the server stores already uses the same normalisation.
- **The web client needs `libsodium-wrappers-sumo`.** The API's own `libsodium-wrappers` build has no `crypto_pwhash`, which is fine server-side because the server never derives keys. A browser client built on the same package would have no Argon2id.

- ~~`kdfParams` schema~~ **Decided 12 Sep 2026 (API PR #80):** `{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}`; the server checks it is an object with a string `kdf`. The web developer still needs to hear it.
- ~~Encryption mode discovery~~ **Decided (API PR #80):** `GET /health` returns `encryptionMode`.
- **Cipher change notice for the web developer.** The design document now names `crypto_aead_xchacha20poly1305_ietf`, not `crypto_secretbox`. The two will not interoperate. Confirm the web client is on the corrected function.

## Account flows (web + Android + API)

- **Claim deep link.** Confirm the URL (`abcmailbox.net/claim?token=`) and host an `assetlinks.json` on that domain so Android can verify the link. Until then the link opens through a chooser.
- **Recovery copy.** `forgot-password.html` still says there is no recovery path. With recovery codes built, both clients should say: independent accounts recover with the recovery code; unclaimed managed accounts get a new claim token from their group.
- ~~Recovery rate limiting~~ **Done (API PR #84):** sign-in, claim checks, and recovery answer 429 with `Retry-After`; the app shows the server's sentence.
- ~~Rule tags~~ **Done (API PR #86):** tags plus three typed values, a public vocabulary endpoint, and clients ignore unknown tags. The Android app acts on `no_photos`, `pageLimit`, languages, and a few advisory tags.
- **Self-registration.** The API allows public `POST /auth/user`; the site says accounts come from groups. Android hides registration behind a flag. Decide whether it should ever be exposed.

## Letters (API, affects phase 3)

- ~~Attachment limit~~ **Done (API PR #84): 20 MiB.** Previously: templates say 20 MB; the API capped at 10 MiB. Multi-page scans from a phone camera are large. Android will always convert to JPEG or PDF, so HEIC is not needed server-side. Ask: raise to 20 MB.
- **Retention.** API PR #78 purges mailed letters and replies after a per-writer window. Decide what a thread should show in place of purged letters (a count, a date, nothing). Android will show a one-line note.

## Sessions (API)

- ~~Tokens outlive the database~~ **Done (API PR #90).** Previously: After `DB_RESET=true` with the same `JWT_SECRET`, a token issued before the reset still works, and it maps to whichever reseeded account now has that id (observed 13 Sep 2026: the phone stayed "signed in" across a reset). Harmless in development, but the same holds after a restore from backup or a rebuild in production. Ask: on boot, if the database is new or restored, bump the sessions revocation marker so everything issued before is refused. Cheap and closes the gap without rotating the secret.

## Letters, small (API)

- ~~Thread reads~~ **Done (API PR #82):** messages in thread reads carry `relay_group`, chat rows carry the facility summary.

## Directory (API)

- **Facet data for filters.** The country chips on the lists are hard-coded from the web mock-ups. A facet endpoint (distinct countries, or counts per filter value) would make them data-driven. Low priority.

## Groups and end-to-end encryption (API, web)

- **Sharing a letter with a partner group is half a workflow.** The partner can read and print a shared letter, but cannot mark it printed or mailed, it never appears in their print queue, and in end-to-end mode the relay group of a letter cannot be changed. Decide: may any group holding an envelope move the status, or should there be an explicit hand-over of the letter to the partner? (Android plan, ask 10.)
- **Invitations (API PR #91) on Android?** A volunteer invited to a group could accept on their phone; it is the claim flow again. Decide whether that is in scope for v1, and whether accepting should also be the moment the inviter's client hands over the group key (today a new member waits, unseen, until a holder opens the Members screen).
- **Key rotation is web-only.** Android will not build rotation: it re-seals everything a group holds in one request and a failure half-way locks the group out. The app survives rotations made on the web. Confirm the web developer is building it, and that the Group key page tells volunteers to go there when a member should lose access for good.
- **A new group member can do nothing until someone hands them the key, and nobody is told.** The member sees a notice; the holders do not. Ask: a count of members waiting for the key on `GET /auth/member-keys` is already derivable; should the web and the app show holders a prompt ("Noor is waiting for the group key")?
- **Writers made before the switch to end-to-end have no keypair.** The API lets the managing group set one once; Android does this automatically the first time a token or a letter needs it. The web client should do the same, or those writers cannot be written for.
- **Who edits a managed writer's queued letter?** Android lets a group edit or delete queued letters only for writers it writes for (managed or anonymous), never for independent writers whose letters it merely relays. Confirm the web does the same.

## Decisions the Android side made alone (confirm or object)

- **Scope order:** anonymous directory, then writer flows, then group-member flows. Admin stays web-only.
- **Android 8.0 (API 26) minimum.** Devices below are negligible in 2026 and it removes a class of workaround code.
- **AGP 9 toolchain.** Current AndroidX releases require it; Android Studio must be a 2026 release to open the project.
- **libsodium binding:** `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`, because lazysodium-android is not 16 KB page-aligned and Google Play now requires that.
