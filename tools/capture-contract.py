#!/usr/bin/env python3
"""Capture one live response from every read endpoint the Android app uses.

  python3 tools/capture-contract.py <out-dir> [--server http://localhost:3000] [--e2e http://localhost:3100]
                                     [--e2e-claim-token TOKEN]

Writes <out-dir>/<mode>.<name>.json. The JUnit test ContractCheckTest decodes each file with the
app's DTO classes and reports fields the app expects that the API no longer sends:

  ABC_CONTRACT_DIR=<out-dir> ./gradlew :app:testDebugUnitTest --tests '*ContractCheck*' --rerun

Needs the development data from tools/dev-seed.py (user1, member1). Read-only, except that in server
mode it issues a claim token for one managed writer that has none, to capture the claim check.
"""
import json, os, sys, urllib.request, urllib.error, argparse

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("--server", default="http://localhost:3000"); ap.add_argument("--e2e", default="http://localhost:3100")
ap.add_argument("--e2e-claim-token", default=None)
ap.add_argument("--e2e-writer-password", default="password1", help="user1's password on the e2e server, if a recovery test changed it")
args = ap.parse_args()
os.makedirs(args.out, exist_ok=True)

def call(base, method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r: return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"{}")
    except OSError as e: return 0, {"error": str(e)}

def capture(mode, base, claim_token=None, writer_password="password1"):
    saved = []
    def save(name, status, payload):
        if status == 200 or status == 201:
            json.dump(payload, open(os.path.join(args.out, f"{mode}.{name}.json"), "w"), indent=1); saved.append(name)
        else: print(f"  [{mode}] {name}: skipped, HTTP {status} {payload.get('info') or payload.get('error') or ''}")
    def get(name, path, token=None): save(name, *call(base, "GET", path, token=token))

    status, health = call(base, "GET", "/health")
    if status != 200: print(f"[{mode}] {base} is not reachable, skipping"); return
    save("health", status, health)
    tokens = {}
    for who, pw in (("user1", writer_password), ("member1", "password1")):
        s, r = call(base, "POST", "/auth/login", {"username": who, "password": pw})
        if s == 200: tokens[who] = (r["data"]["token"]["token"], r["data"]["user"]); save(f"login.{who}", s, r)
        else: print(f"  [{mode}] cannot sign in as {who}: {r.get('info')}")
    # Public directory
    get("prisoners", "/prisoner/prisoners?page=1&page_size=5")
    get("prisoners.featured", "/prisoner/prisoners?featured=true&page_size=3")
    s, r = call(base, "GET", "/prisoner/prisoners?page_size=1"); pid = r["data"][0]["id"] if s == 200 and r.get("data") else 1
    get("prisoner", f"/prisoner/prisoner?id={pid}&full=true")
    get("prisons", "/prison/prisons?page=1&page_size=5"); get("prison", "/prison/prison?id=1&full=true"); get("mailRules", "/prison/mail-rules")
    get("chapters", "/chapter/chapters?page=1&page_size=5"); get("chapter", "/chapter/chapter?id=1&full=true")
    # A writer
    if "user1" in tokens:
        t, u = tokens["user1"]
        get("user", f"/auth/user?id={u['id']}", t); get("keys.writer", "/auth/keys", t); get("retention", "/messaging/retention", t)
        get("chats", "/chat/chats?page=1&page_size=5", t)
        s, r = call(base, "GET", "/chat/chats?page_size=1", token=t)
        if s == 200 and r.get("data"):
            get("chat", f"/chat/chat?id={r['data'][0]['id']}&full=true", t)
            get("chatByPrisoner", f"/chat/chat?prisoner={r['data'][0]['prisoner']}&full=true", t)
        s, r = call(base, "GET", "/messaging/messages?page_size=50", token=t)
        if s == 200 and r.get("data"):
            # Prefer a letter that has an attachment, so the attachment shape is checked too.
            ids = [m["id"] for m in r["data"]]
            mid = next((i for i in ids if (call(base, "GET", f"/messaging/attachments?message={i}", token=t)[1].get("data") or [])), ids[0])
            get("message", f"/messaging/message?id={mid}&full=true", t); get("attachments", f"/messaging/attachments?message={mid}", t)
        get("notifications", "/auth/notifications?page_size=20", t)
        get("publicKey.user", f"/auth/public-key?user={u['id']}", t); get("publicKey.chapter", "/auth/public-key?chapter=1", t)
        if mode == "e2e": get("recoverStart", "/auth/recover?username=user1")
    # A group member
    if "member1" in tokens:
        t, u = tokens["member1"]; group = u.get("chapterId")
        get("keys.member", "/auth/keys", t); get("queue", f"/messaging/messages?relayChapter={group}&page_size=5", t)
        get("writers", "/auth/writers?page_size=100", t); get("chats.member", "/chat/chats?page=1&page_size=5", t)
        if mode == "e2e": get("memberKeys", f"/auth/member-keys?chapter={group}", t)
        if mode == "server" and claim_token is None:
            s, r = call(base, "GET", "/auth/writers?page_size=100", token=t)
            free = [w for w in (r.get("data") or []) if not w.get("claimToken") and not w.get("anonymousForChapter")]
            if free:
                s, r = call(base, "POST", "/auth/writer/token", {"writer": free[0]["id"]}, t); save("issueToken", s, r)
                claim_token = (r.get("data") or {}).get("token")
    if claim_token: get("claimInfo", f"/auth/claim?token={''.join(c for c in claim_token.upper() if c.isalnum())}")
    print(f"[{mode}] {len(saved)} responses from {base} ({health.get('encryptionMode')} mode)")

capture("server", args.server)
capture("e2e", args.e2e, args.e2e_claim_token, args.e2e_writer_password)
