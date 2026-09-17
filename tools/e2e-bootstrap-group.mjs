#!/usr/bin/env node
// Development only: gives a group member keys and bootstraps their group's keypair on an
// end-to-end API, the way a group member's browser would. Until the phone has the group
// screens (phase 6) this is how a relay group becomes able to receive encrypted letters.
//
//   node tools/e2e-bootstrap-group.mjs http://localhost:3100 member1 password1
//
// Writes the group's keypair to tools/dev-group-key.json (git-ignored) so other tools can
// check that a letter sealed to the group really opens.
import { writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import _sodium from 'libsodium-wrappers-sumo';
await _sodium.ready;
const s = _sodium;
const [base, username, password] = process.argv.slice(2);
const b64 = (u) => s.to_base64(u, s.base64_variants.ORIGINAL);
const KDF = { kdf: 'argon2id', alg: 2, opslimit: 2, memlimit: 67108864 };
const call = async (method, path, body, token) => {
  const r = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json() };
};
const wrap = (priv, secret) => {
  const salt = s.randombytes_buf(16), nonce = s.randombytes_buf(24);
  const key = s.crypto_pwhash(32, secret.normalize('NFKC'), salt, KDF.opslimit, KDF.memlimit, KDF.alg);
  return { wrapped: JSON.stringify({ ciphertext: b64(s.crypto_aead_xchacha20poly1305_ietf_encrypt(priv, null, null, nonce, key)), nonce: b64(nonce) }), salt: b64(salt) };
};

const login = await call('POST', '/auth/login', { username, password });
if (!login.body.success) { console.error('login failed:', login.body.info); process.exit(1); }
const token = login.body.data.token.token, user = login.body.data.user;
let memberPublic = login.body.data.keys?.publicKey;
if (!memberPublic) {
  const kp = s.crypto_box_keypair(), pw = wrap(kp.privateKey, password), rc = wrap(kp.privateKey, 'DEVRECOVERYCODE0000000000');
  const put = await call('PUT', '/auth/keys', { publicKey: b64(kp.publicKey), wrappedPrivateKey: pw.wrapped, kdfSalt: pw.salt, kdfParams: KDF, recoveryWrappedPrivateKey: rc.wrapped, recoverySalt: rc.salt, recoveryKdfParams: KDF }, token);
  console.log('member keys :', put.status, put.body.info ?? '');
  memberPublic = b64(kp.publicKey);
} else console.log('member keys : already set');

const current = await call('GET', '/auth/public-key?chapter=' + user.chapterId, null, token);
if (current.body.data.publicKey) { console.log('group keys  : already set, version', current.body.data.keyVersion); process.exit(0); }
const group = s.crypto_box_keypair();
const res = await call('PUT', '/auth/chapter-keys', { chapter: user.chapterId, publicKey: b64(group.publicKey), wrappedOrgPrivateKey: b64(s.crypto_box_seal(group.privateKey, s.from_base64(memberPublic, s.base64_variants.ORIGINAL))) }, token);
console.log('group keys  :', res.status, res.body.info ?? JSON.stringify(res.body.errors));
writeFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'dev-group-key.json'), JSON.stringify({ chapter: user.chapterId, publicKey: b64(group.publicKey), privateKey: b64(group.privateKey) }, null, 1));
console.log('saved       : tools/dev-group-key.json (development only)');
