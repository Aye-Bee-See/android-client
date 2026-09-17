#!/usr/bin/env node
// Produces end-to-end fixtures with libsodium.js exactly as a browser client would:
// Argon2id-wrapped private keys (password and recovery code), a letter with
// envelopes, an encrypted file, and a sealed recovery challenge.
//
//   cd tools && npm install && node make-e2e-fixture.mjs
//
// Writes crypto/src/test/resources/interop/e2e-fixture.json. The API's own
// libsodium build has no crypto_pwhash (the server never derives keys), which is
// why this uses the -sumo build; the web client needs it for the same reason.
import { writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import _sodium from 'libsodium-wrappers-sumo';

await _sodium.ready;
const s = _sodium;
const b64 = (u8) => s.to_base64(u8, s.base64_variants.ORIGINAL);
const KDF = { kdf: 'argon2id', alg: 2, opslimit: 2, memlimit: 67108864 };

const aead = (plain, key) => {
  const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES);
  const bytes = typeof plain === 'string' ? s.from_string(plain) : plain;
  return { ciphertext: b64(s.crypto_aead_xchacha20poly1305_ietf_encrypt(bytes, null, null, nonce, key)), nonce: b64(nonce) };
};
const wrap = (privateKey, secret) => {
  const salt = s.randombytes_buf(s.crypto_pwhash_SALTBYTES);
  const key = s.crypto_pwhash(32, secret.normalize('NFKC'), salt, KDF.opslimit, KDF.memlimit, KDF.alg);
  return { wrapped: JSON.stringify(aead(privateKey, key)), salt: b64(salt), params: KDF };
};

// Non-ASCII on purpose: a byte-length or normalisation mismatch between clients shows up here.
const password = 'pässwörd-密码-пароль';
// The same password typed on a keyboard that emits decomposed characters (a + combining diaeresis).
const passwordDecomposed = password.normalize('NFD');
const recoveryCode = 'DJ69G5K7XBMYFWW4P4PYTJ8C';
const kp = s.crypto_box_keypair();
const group = s.crypto_box_keypair();

const contentKey = s.randombytes_buf(32);
const body = 'Dear Bill, the tomatoes are in. Überraschung: ça marche — even with accents.';
const note = 'Two pages, please.';
const fileBytes = s.randombytes_buf(1500);
const challenge = s.randombytes_buf(32);

const fixture = {
  password, passwordDecomposed, recoveryCode,
  recoveryCodeAsTyped: 'dj69-g5k7 xbmy-fww4-p4py-tj8c',
  publicKey: b64(kp.publicKey), privateKey: b64(kp.privateKey),
  groupPublicKey: b64(group.publicKey), groupPrivateKey: b64(group.privateKey),
  passwordWrapped: wrap(kp.privateKey, password),
  recoveryWrapped: wrap(kp.privateKey, recoveryCode),
  letter: {
    body, note,
    ...aead(body, contentKey),
    relayNote: aead(note, contentKey),
    contentKey: b64(contentKey),
    envelopes: [
      { readerType: 'user', readerId: 7, wrappedKey: b64(s.crypto_box_seal(contentKey, kp.publicKey)) },
      { readerType: 'chapter', readerId: 1, keyVersion: 1, wrappedKey: b64(s.crypto_box_seal(contentKey, group.publicKey)) },
    ],
  },
  file: (() => { const e = aead(fileBytes, contentKey); return { plain: b64(fileBytes), ciphertext: e.ciphertext, nonce: e.nonce }; })(),
  recovery: { challenge: b64(challenge), sealedChallenge: b64(s.crypto_box_seal(challenge, kp.publicKey)) },
  tokenHash: { token: recoveryCode, sha256: s.to_hex(s.crypto_hash_sha256(s.from_string(recoveryCode))) },
};

const out = resolve(dirname(fileURLToPath(import.meta.url)), '../crypto/src/test/resources/interop/e2e-fixture.json');
writeFileSync(out, JSON.stringify(fixture, null, 1) + '\n');
console.log('wrote', out);
