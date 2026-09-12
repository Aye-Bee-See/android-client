#!/usr/bin/env python3
"""Add development data the API's seed does not include.

Run against a freshly seeded API (DB_RESET=true on first boot):

    python3 tools/dev-seed.py [http://localhost:3000]

Idempotent: every step checks before it creates. What it adds, and why:

  * activates the seeded group and creates a second, relay-only group, so
    relay-group resolution has all three cases to exercise (one group, two
    groups, relay_only);
  * attaches rules to a few prisons so the "facility rules" card has content;
  * marks prisoners featured and gives one a status notice for the list banner;
  * a `chapter` member account (member1 / password1) in the seeded group, which
    the seed lacks, so group-side screens can be tried;
  * a managed writer with a live claim token, saved to tools/dev-writer.token,
    for the claim screen.

Only the standard library is used so it runs anywhere Python 3 does.
"""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:3000").rstrip("/")


def call(method, path, body=None, token=None, ok=(200, 201)):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read() or b"{}")
        if e.code in ok:
            return e.code, payload
        raise SystemExit(f"{method} {path} -> {e.code}: {payload.get('info') or payload.get('errors')}")


def login(username, password):
    _, r = call("POST", "/auth/login", {"username": username, "password": password})
    return r["data"]["token"]["token"]


def step(msg):
    print(f"- {msg}")


status, health = call("GET", "/health")
if health.get("status") != "ok":
    raise SystemExit("API is not healthy")
admin = login("admin", "abcpassword")

# 1. Groups ---------------------------------------------------------------
_, r = call("GET", "/chapter/chapters?page_size=100", token=admin)
chapters = {c["name"]: c for c in r["data"]}
seeded = chapters.get("Test Chapter") or next(iter(chapters.values()))
call("PUT", "/chapter/chapter", {
    "id": seeded["id"], "accountStatus": "active", "networkRole": "both",
    "subregion": "Portland, OR", "country": "United States",
    "services": ["letter_collection", "letter_writing_nights", "domestic_mailing"],
    "announcement": "Letter-writing night every second Thursday at the Red & Black.",
}, token=admin)
step(f"group '{seeded['name']}' (id {seeded['id']}) is active, role both")

relay_name = "Relay Test Chapter"
if relay_name in chapters:
    relay = chapters[relay_name]
else:
    _, r = call("POST", "/chapter/chapter", {
        "name": relay_name, "location": {"city": "Minsk"},
        "subregion": "Minsk", "country": "Belarus", "networkRole": "relay",
        "about": "Prints and mails letters locally for facilities in Belarus.",
        "services": ["international_relay", "translation_assistance"],
        "vouchedBy": seeded["id"],
    }, token=admin)
    relay = r["data"]
call("PUT", "/chapter/chapter", {"id": relay["id"], "accountStatus": "active"}, token=admin)
step(f"group '{relay_name}' (id {relay['id']}) is active, role relay")

# 2. Prisons: relay links and routing ---------------------------------------
_, r = call("GET", "/prison/prisons?page_size=100", token=admin)
prisons = sorted(r["data"], key=lambda p: p["id"])[:4]
p1, p2, p3, p4 = prisons
call("PUT", "/prison/relay", {"prison": p1["id"], "chapter": seeded["id"]}, token=admin)
call("PUT", "/prison/relay", {"prison": p2["id"], "chapter": seeded["id"]}, token=admin)
call("PUT", "/prison/relay", {"prison": p2["id"], "chapter": relay["id"]}, token=admin)
call("PUT", "/prison/relay", {"prison": p3["id"], "chapter": relay["id"]}, token=admin)
call("PUT", "/prison/prison", {"id": p3["id"], "routing": "relay_only"}, token=admin)
call("PUT", "/prison/prison", {"id": p1["id"], "routing": "direct"}, token=admin)
call("PUT", "/prison/prison", {"id": p2["id"], "routing": "direct_and_scan",
                                "scanService": "JPay, $0.35 per page, account required"}, token=admin)
step(f"prison {p1['id']} '{p1['prisonName']}': one relay group (auto-picked)")
step(f"prison {p2['id']} '{p2['prisonName']}': two relay groups (writer must choose)")
step(f"prison {p3['id']} '{p3['prisonName']}': relay_only with one relay group")
step(f"prison {p4['id']} '{p4['prisonName']}': no relay group (direct mail)")

# 3. Rules ---------------------------------------------------------------
_, r = call("GET", "/rule/rules?page_size=100", token=admin)
rules = sorted(r["data"], key=lambda x: x["id"])
for rule in rules[:4]:
    call("PUT", "/prison/rule", {"prison": p1["id"], "rule": rule["id"]}, token=admin)
for rule in rules[4:6]:
    call("PUT", "/prison/rule", {"prison": p2["id"], "rule": rule["id"]}, token=admin)
step(f"attached {min(4, len(rules))} rules to prison {p1['id']} and 2 to prison {p2['id']}")

# 4. Prisoners: featured and a status notice --------------------------------
_, r = call("GET", "/prisoner/prisoners?page_size=100", token=admin)
prisoners = sorted(r["data"], key=lambda x: x["id"])
for pr in prisoners[:3]:
    call("PUT", "/prisoner/prisoner", {"id": pr["id"], "featured": True}, token=admin)
call("PUT", "/prisoner/prisoner", {
    "id": prisoners[1]["id"], "statusNotice": "In transit, location unconfirmed",
}, token=admin)
step(f"prisoners {[p['id'] for p in prisoners[:3]]} featured; {prisoners[1]['id']} has a status notice")

# 5. Group member account ------------------------------------------------------
status, r = call("GET", "/auth/user?username=member1", token=admin, ok=(200, 404))
if status == 404:
    call("POST", "/auth/user", {
        "username": "member1", "password": "password1", "email": "member1@example.com",
        "name": "Test Chapter Member", "role": "chapter", "chapterId": seeded["id"],
    }, token=admin)
    step("created chapter member account member1 / password1")
else:
    step("chapter member account member1 already exists")
member = login("member1", "password1")

# 6. Managed writer with a claim token ------------------------------------------
writer_name = "Alex (letter night)"
_, r = call("GET", "/auth/writers", token=member)
writer = next((w for w in r["data"] if w["name"] == writer_name), None)
if writer is None:
    _, r = call("POST", "/auth/writer", {
        "name": writer_name, "managerNote": "Came to the 3 August letter night.",
    }, token=member)
    writer = r["data"]
    step(f"created managed writer '{writer_name}' (id {writer['id']})")
else:
    step(f"managed writer '{writer_name}' (id {writer['id']}) already exists")
_, r = call("POST", "/auth/writer/token", {"writer": writer["id"]}, token=member)
token = r["data"].get("token")
if token:
    Path(__file__).with_name("dev-writer.token").write_text(token + "\n")
    step(f"claim token for the writer: {token} (saved to tools/dev-writer.token)")
else:
    step("claim token regenerated (e2e mode returns no token)")

print("\nSign in as user1 / password1 for a writer with a thread, member1 / password1 for the group.")
