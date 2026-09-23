#!/usr/bin/env node
// The split sign-in scheme (API PR #114), for scripts: prints the auth key the app would send for a password.
//   node split-auth-key.mjs <password> [salt-base64] [opslimit] [memlimit]
// Without a salt, makes one and prints both. Matches the app's derivation (SplitAuth in :crypto) and the API's vector.
import sodium from 'libsodium-wrappers-sumo';
await sodium.ready;
const [password, saltArg, ops = '2', mem = '67108864'] = process.argv.slice(2);
if (!password) { console.error('usage: split-auth-key.mjs <password> [salt-base64]'); process.exit(2); }
const salt = saltArg ? sodium.from_base64(saltArg, sodium.base64_variants.ORIGINAL) : sodium.randombytes_buf(sodium.crypto_pwhash_SALTBYTES);
const master = sodium.crypto_pwhash(32, sodium.from_string(password.normalize('NFKC')), salt, Number(ops), Number(mem), sodium.crypto_pwhash_ALG_ARGON2ID13);
const authKey = sodium.crypto_kdf_derive_from_key(32, 2, 'abcauth_', master);
console.log(JSON.stringify({ kdfSalt: sodium.to_base64(salt, sodium.base64_variants.ORIGINAL), kdfParams: { kdf: 'argon2id', alg: 2, opslimit: Number(ops), memlimit: Number(mem) }, password: sodium.to_base64(authKey, sodium.base64_variants.ORIGINAL), authScheme: 'split' }));
