#!/usr/bin/env python3
"""Regenerate the app's fallback mail-rule vocabulary from a running API.

    python3 tools/gen-mail-rules.py [http://localhost:3000]

Run it when the API adds tags. The app works without this (it fetches the live
vocabulary), but labels for new tags then appear offline too.
"""
import json, sys, urllib.request
base = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:3000").rstrip("/")
d = json.load(urllib.request.urlopen(base + "/prison/mail-rules"))["data"]
esc = lambda s: s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
out = ['package me.paxana.abcmailbox.domain', '', '/**',
       ' * GENERATED from `GET /prison/mail-rules` (tools/gen-mail-rules.py); do not edit by hand.', ' *',
       ' * The fallback vocabulary, so facility rules render before the live one has been',
       ' * fetched and when the phone is offline. The live vocabulary wins when present, which',
       ' * is how a tag added on the server gets a proper label without an app release.', ' */',
       'internal object CompiledMailRules {',
       '  val categories: List<String> = listOf(' + ", ".join('"%s"' % c for c in d["categories"]) + ')', '',
       '  val rules: List<MailRule> = listOf(']
out += ['    MailRule("%s", "%s", "%s", "%s"),' % (r["tag"], r["category"], esc(r["label"]), esc(r.get("description") or "")) for r in d["rules"]]
out += ['  )', '}', '']
open("app/src/main/java/me/paxana/abcmailbox/domain/CompiledMailRules.kt", "w").write("\n".join(out))
print("wrote", len(d["rules"]), "rules")
