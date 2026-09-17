#!/usr/bin/env node
// Development only: opens the newest letter relayed by a group using the group private key
// saved by e2e-bootstrap-group.mjs. Proves that a letter sealed on the phone is readable by
// the relay group, which is the whole point of the second envelope.
//   node tools/verify-group-can-read.mjs http://localhost:3100 member1 password1
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import _sodium from 'libsodium-wrappers-sumo';
await _sodium.ready;
const s = _sodium, from = (t) => s.from_base64(t, s.base64_variants.ORIGINAL);
const [base, username, password] = process.argv.slice(2);
const g = JSON.parse(readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'dev-group-key.json'), 'utf8'));
const login = await (await fetch(base + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) })).json();
const list = await (await fetch(`${base}/messaging/messages?relayChapter=${g.chapter}&page_size=100`, { headers: { Authorization: 'Bearer ' + login.data.token.token } })).json();
const m = list.data[list.data.length - 1];
if (!m) { console.log('FAIL: no letters relayed by group', g.chapter); process.exit(1); }
const env = m.envelopes.find((e) => e.readerType === 'chapter' && e.readerId === g.chapter);
console.log('letter      :', m.id, '| messageText on the server:', m.messageText, '| group envelope keyVersion:', env?.keyVersion);
const key = s.crypto_box_seal_open(from(env.wrappedKey), from(g.publicKey), from(g.privateKey));
console.log('group reads :', JSON.stringify(s.to_string(s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, from(m.ciphertext), null, from(m.nonce), key))));
