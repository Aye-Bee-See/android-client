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
- **How does someone who cannot come to a letter night get an account? (raised by the project owner, 19 Sep 2026; product decision, then API + web + Android.)** Today a writer's account comes from one of three places: an admin makes it; a group makes a managed writer for them and hands it over with a claim token; or public registration, which the API allows and both clients hide. All of the group routes assume the person and the group meet. Someone housebound, in a town with no group, or abroad has no way in unless a group does the managed-writer routine for them at a distance. Options, cheapest first:
  1. **Remote hand-off, no new work.** A group creates a managed writer and sends the claim token over Signal. Works today. Costs: someone in a group has to do it by hand for each person, and until the person claims the account the group holds it (and, in end-to-end mode, its key).
  2. **Writer invite codes (small API build, recommended).** A third kind of invitation beside "new group" and "new member": a group issues a code, the person registers with it, and the account is theirs from the first minute; the group never holds it or its key. The account records which group invited them. Sub-questions: single-use codes handed to one person, or one reusable code with an expiry and a cap, for an online letter night or a flyer? May only groups invite, or may an existing writer invite a friend?
  3. **Ask to join (larger build).** A public form, "ask a group for an account", that lands in the chosen group's queue; approving it issues a code. Needs rate limiting and a way to choose or be assigned a group, and it puts strangers' requests in volunteers' laps.
  4. **Open registration.** Already built; turning it on is a client decision (the item above). No vouching of any kind, which is the opposite of how groups join.
  Also to decide: what about a person with **no group in their country**, since the inviting group need not be the group that mails their letters (any relay group of the facility does that)? And should an online letter night (a video call where a group does option 1 or 2 live) be the recommended practice? Default until decided: option 1, with registration hidden.

## Letters (API, affects phase 3)

- ~~Attachment limit~~ **Done (API PR #84): 20 MiB.** Previously: templates say 20 MB; the API capped at 10 MiB. Multi-page scans from a phone camera are large. Android will always convert to JPEG or PDF, so HEIC is not needed server-side. Ask: raise to 20 MB.
- **Retention.** API PR #78 purges mailed letters and replies after a per-writer window. Decide what a thread should show in place of purged letters (a count, a date, nothing). Android will show a one-line note.

## Sessions (API)

- ~~Tokens outlive the database~~ **Done (API PR #90).** Previously: After `DB_RESET=true` with the same `JWT_SECRET`, a token issued before the reset still works, and it maps to whichever reseeded account now has that id (observed 13 Sep 2026: the phone stayed "signed in" across a reset). Harmless in development, but the same holds after a restore from backup or a rebuild in production. Ask: on boot, if the database is new or restored, bump the sessions revocation marker so everything issued before is refused. Cheap and closes the gap without rotating the secret.

## Letters, small (API)

- ~~Idempotency for sending letters~~ **Done (API PR #97), adopted by Android.** The web client should send `Idempotency-Key` too: a double click on Send has the same exposure.
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

## Before a public release (project owner, web)

- **Push notifications (project owner, web).** Android is ready for the API's push framework (PR #96) and waits for a Firebase project; `docs/PUSH.md` in the Android repo has the steps and a first-run checklist. Two things to decide. (1) **Opt-in.** Android leaves push off until the person turns it on, and says why: Google learns that the phone has the app and when it is rung. Should the web client (Web Push) do the same? (2) **Who owns the Firebase project**, since its service-account key can ring every registered phone.
- **The feed without push.** Android reads `GET /auth/notifications` when it opens and every six hours, so nobody needs push to learn that a reply came. The web client can show the same feed.
- **Contrast on the website.** The templates' muted grey `#767676` on the paper background `#F2F0ED` is 3.99:1; WCAG AA asks 4.5:1 for body text. It passes only on pure white. Android now uses `#6C6C6C` (4.62:1). The web developer should check the same pair.
- **Languages: decided 19 Sep 2026, English, Spanish, Russian. Built.** Two follow-ups. (1) **Find reviewers**: the Spanish and Russian were machine-written and need a native-speaker read before release; `docs/TRANSLATING.md` in the Android repo is the guide, with a glossary that the web site should share so both clients use the same words. (2) **A style decision for Spanish**: the app uses the gender-neutral "persona presa" and "remitente"; groups that write "presxs" may want their own convention.
- **The server speaks English (API).** Refusals and validation errors are shown verbatim and arrive in English. Android now sends `Accept-Language`. Decide: localise on the server, or give every error a stable code clients can translate (Android plan, ask 15). The web client needs the same answer.
- **Release keystore.** The project owner makes it (command in `keystore.properties.example`) and backs it up with its passwords: an app can only ever be updated by the key that first signed it. Decide who holds it.
- **Domain and hosting** unblock, for Android: the release API address, verified `https` claim links (needs `assetlinks.json` on the domain), and the Play listing, which also needs a privacy policy URL.
- **Testers before then** can use the `internal` build (an APK from each CI run) pointed at any reachable server.

## Decisions the Android side made alone (confirm or object)

- **Scope order:** anonymous directory, then writer flows, then group-member flows. Admin stays web-only.
- **Android 8.0 (API 26) minimum.** Devices below are negligible in 2026 and it removes a class of workaround code.
- **AGP 9 toolchain.** Current AndroidX releases require it; Android Studio must be a 2026 release to open the project.
- **libsodium binding:** `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings`, because lazysodium-android is not 16 KB page-aligned and Google Play now requires that.
