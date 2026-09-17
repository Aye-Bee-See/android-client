#!/usr/bin/env node
// The other direction: opens what the Kotlin crypto module produced
// (crypto/build/interop/kotlin-fixture.json, written by KotlinToNodeFixtureTest)
// with libsodium.js. Exit code 0 means a browser client can read the phone's output.
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import _sodium from 'libsodium-wrappers-sumo';

await _sodium.ready;
const s = _sodium;
const from = (t) => s.from_base64(t, s.base64_variants.ORIGINAL);
const f = JSON.parse(readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../crypto/build/interop/kotlin-fixture.json'), 'utf8'));
let failures = 0;
const check = (name, ok) => { console.log((ok ? 'ok   ' : 'FAIL ') + name); if (!ok) failures++; };

const unwrap = (w, secret) => {
  const { ciphertext, nonce } = JSON.parse(w.wrapped);
  const key = s.crypto_pwhash(32, secret.normalize('NFKC'), from(w.salt), w.params.opslimit, w.params.memlimit, w.params.alg);
  return s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from(ciphertext), null, from(nonce), key);
};

check('kdfParams is the agreed schema', JSON.stringify(f.passwordWrapped.params) === '{"kdf":"argon2id","alg":2,"opslimit":2,"memlimit":67108864}');
check('password-wrapped private key opens', s.to_base64(unwrap(f.passwordWrapped, f.password), s.base64_variants.ORIGINAL) === f.privateKey);
check('recovery-wrapped private key opens with the normalised code', s.to_base64(unwrap(f.recoveryWrapped, f.recoveryCode), s.base64_variants.ORIGINAL) === f.privateKey);

const mine = f.letter.envelopes.find((e) => e.readerType === 'user');
const key = s.crypto_box_seal_open(from(mine.wrappedKey), from(f.publicKey), from(f.privateKey));
check('writer envelope opens', s.to_base64(key, s.base64_variants.ORIGINAL) === f.letter.contentKey);
const groupEnv = f.letter.envelopes.find((e) => e.readerType === 'chapter');
check('group envelope opens and names its key version', groupEnv.keyVersion === 3 && s.to_base64(s.crypto_box_seal_open(from(groupEnv.wrappedKey), from(f.groupPublicKey), from(f.groupPrivateKey)), s.base64_variants.ORIGINAL) === f.letter.contentKey);
const dec = (c, n) => s.to_string(s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from(c), null, from(n), key));
check('body decrypts', dec(f.letter.ciphertext, f.letter.nonce) === f.letter.body);
check('relay note decrypts', dec(f.letter.relayNote.ciphertext, f.letter.relayNote.nonce) === f.letter.note);
check('file decrypts', s.to_base64(s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from(f.file.ciphertext), null, from(f.file.nonce), key), s.base64_variants.ORIGINAL) === f.file.plain);
check('token hash matches', s.to_hex(s.crypto_hash_sha256(s.from_string(f.tokenHash.token))) === f.tokenHash.sha256);

process.exit(failures === 0 ? 0 : 1);
