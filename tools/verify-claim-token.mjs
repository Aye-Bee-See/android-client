#!/usr/bin/env node
// Independent check of a claim token the Android app made on an end-to-end server.
// Usage: node verify-claim-token.mjs <base-url> <token>
// Asks the API what it holds for the token, unwraps the writer's private key with the
// token (Argon2id + XChaCha20-Poly1305, libsodium.js), and checks that the private key
// belongs to the public key the server publishes for that writer.
import _sodium from 'libsodium-wrappers-sumo';
await _sodium.ready;
const s = _sodium;
const [base, typed] = process.argv.slice(2);
if (!base || !typed) { console.error('usage: node verify-claim-token.mjs <base-url> <token>'); process.exit(2); }
const token = typed.toUpperCase().replace(/[^0-9A-Z]/g, '');
const from64 = (t) => s.from_base64(t, s.base64_variants.ORIGINAL);

const r = await (await fetch(`${base}/auth/claim?token=${token}`)).json();
if (!r.success) { console.error('server refused the token:', r.status, r.info); process.exit(1); }
const d = r.data;
console.log('writer      :', d.writer?.name, `(id ${d.writer?.id}), group:`, d.chapter?.name, '| expires', d.expiresAt);
for (const f of ['publicKey', 'claimWrappedPrivateKey', 'claimSalt', 'claimKdfParams']) if (!d[f]) { console.error('missing', f); process.exit(1); }
const p = d.claimKdfParams;
console.log('kdf params  :', JSON.stringify(p));
const key = s.crypto_pwhash(32, token.normalize('NFKC'), from64(d.claimSalt), p.opslimit, p.memlimit, p.alg);
const box = JSON.parse(d.claimWrappedPrivateKey);
let priv;
try { priv = s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from64(box.ciphertext), null, from64(box.nonce), key); }
catch { console.error('FAIL: the token does not open the wrapped key'); process.exit(1); }
const matches = s.memcmp(s.crypto_scalarmult_base(priv), from64(d.publicKey));
console.log('token opens the wrapped key : yes');
console.log('key matches the public key  :', matches ? 'yes' : 'NO');
process.exit(matches ? 0 : 1);
