#!/usr/bin/env node
// Signs in to an end-to-end API as a real client would and proves, with libsodium.js,
// that the key bundle stored there opens with the password (and optionally the recovery
// code), and that the private key matches the public key. Speaks both sign-in schemes: a split
// account (API PR #114) sends the auth key derived from the password, and its private key is
// wrapped under the wrap key of that same derivation. Use it after creating or changing an
// account from the phone:
//
//   node tools/verify-account.mjs http://localhost:3100 user1 password1 [RECOVERY-CODE]
import _sodium from 'libsodium-wrappers-sumo';
await _sodium.ready;
const s = _sodium;
const [base, username, password, recoveryCode] = process.argv.slice(2);
const from = (t) => s.from_base64(t, s.base64_variants.ORIGINAL);
const b64 = (u) => s.to_base64(u, s.base64_variants.ORIGINAL);
const norm = (c) => c.toUpperCase().replace(/[^0-9A-Z]/g, '');
const unwrap = (wrapped, secret, salt, p) => {
  const { ciphertext, nonce } = JSON.parse(wrapped);
  const key = s.crypto_pwhash(32, secret.normalize('NFKC'), from(salt), p.opslimit, p.memlimit, p.alg);
  return s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from(ciphertext), null, from(nonce), key);
};
const params = (await (await fetch(base + '/auth/login-params?username=' + encodeURIComponent(username))).json()).data;
let sent = password, wrapKey = null;
if (params?.scheme === 'split') {
  // Matches SplitAuth in :crypto: one Argon2id derivation, then the auth key (id 2) and the wrap key (id 1).
  const p = params.kdfParams;
  const master = s.crypto_pwhash(32, s.from_string(password.normalize('NFKC')), from(params.kdfSalt), p.opslimit, p.memlimit, s.crypto_pwhash_ALG_ARGON2ID13);
  sent = b64(s.crypto_kdf_derive_from_key(32, 2, 'abcauth_', master));
  wrapKey = s.crypto_kdf_derive_from_key(32, 1, 'abcwrap_', master);
}
console.log('scheme      :', params?.scheme ?? 'plain (no sign-in parameters)');
const login = await (await fetch(base + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password: sent }) })).json();
if (!login.success) { console.log('FAIL login:', login.info); process.exit(1); }
const k = login.data.keys;
console.log('kdfParams   :', JSON.stringify(k.kdfParams));
const priv = wrapKey
  ? (({ ciphertext, nonce }) => s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from(ciphertext), null, from(nonce), wrapKey))(JSON.parse(k.wrappedPrivateKey))
  : unwrap(k.wrappedPrivateKey, password, k.kdfSalt, k.kdfParams);
const derivedPublic = b64(s.crypto_scalarmult_base(priv));
console.log('password    : opens the private key');
console.log('public key  :', derivedPublic === k.publicKey ? 'matches the private key' : 'MISMATCH');
let ok = derivedPublic === k.publicKey;
if (recoveryCode) {
  const start = await (await fetch(base + '/auth/recover?username=' + encodeURIComponent(username))).json();
  let viaCode = null;
  try { viaCode = unwrap(start.data.recoveryWrappedPrivateKey, norm(recoveryCode), start.data.recoverySalt, start.data.recoveryKdfParams); } catch { /* the wrong code: the AEAD refuses */ }
  const same = viaCode !== null && b64(viaCode) === b64(priv);
  console.log('recovery    :', same ? 'the code opens the same private key' : viaCode === null ? 'FAIL: the code does not open the key' : 'MISMATCH');
  ok = ok && same;
}
process.exit(ok ? 0 : 1);
