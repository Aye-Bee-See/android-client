#!/usr/bin/env node
// Independent check that a group member can really open their group's key (end-to-end server).
// Usage: node verify-member-holds-group-key.mjs <base-url> <username> <password>
// Signs in, unwraps the member's own private key with the password (Argon2id + XChaCha20-Poly1305),
// opens the group key sealed to them, and checks it against the group's published public key.
import _sodium from 'libsodium-wrappers-sumo';
await _sodium.ready;
const s = _sodium;
const [base, username, password] = process.argv.slice(2);
if (!password) { console.error('usage: node verify-member-holds-group-key.mjs <base-url> <username> <password>'); process.exit(2); }
const from64 = (t) => s.from_base64(t, s.base64_variants.ORIGINAL);

const login = await (await fetch(base + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) })).json();
if (!login.success) { console.error('login failed:', login.info); process.exit(1); }
const k = login.data.keys;
if (!k?.wrappedPrivateKey) { console.error('this member has no keys of their own yet'); process.exit(1); }
const p = k.kdfParams;
const box = JSON.parse(k.wrappedPrivateKey);
const mine = s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from64(box.ciphertext), null, from64(box.nonce),
  s.crypto_pwhash(32, password.normalize('NFKC'), from64(k.kdfSalt), p.opslimit, p.memlimit, p.alg));
console.log('member      :', username, '| own key opens with the password: yes');
const org = k.orgKey;
console.log('group       :', org?.chapterName, `(id ${org?.chapterId}) key version`, org?.keyVersion);
if (!org?.wrappedOrgPrivateKey) { console.log('holds group key : NO (nothing has been sealed to this member)'); process.exit(1); }
let groupPrivate;
try { groupPrivate = s.crypto_box_seal_open(from64(org.wrappedOrgPrivateKey), from64(k.publicKey), mine); }
catch { console.log('holds group key : NO (the sealed copy does not open with this member\'s key)'); process.exit(1); }
const ok = s.memcmp(s.crypto_scalarmult_base(groupPrivate), from64(org.chapterPublicKey));
console.log('opens the sealed group key  : yes');
console.log('matches the group public key:', ok ? 'yes' : 'NO');
process.exit(ok ? 0 : 1);
