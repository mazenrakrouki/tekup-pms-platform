# -*- coding: utf-8 -*-
"""Verify every transloco key referenced in components exists in the catalogues."""
import io, json, os, re, sys

def flat(o, p=''):
    out = set()
    for k, v in o.items():
        key = p + '.' + k if p else k
        out |= flat(v, key) if isinstance(v, dict) else {key}
    return out

# build the known-key universe: root keys unprefixed, scope keys prefixed with scope name
known = set()
root = 'public/i18n'
rp = os.path.join(root, 'en.json')
if os.path.exists(rp):
    known |= flat(json.load(io.open(rp, encoding='utf-8')))
for d in sorted(os.listdir(root)):
    p = os.path.join(root, d, 'en.json')
    if os.path.isdir(os.path.join(root, d)) and os.path.exists(p):
        known |= {d + '.' + k for k in flat(json.load(io.open(p, encoding='utf-8')))}

# collect referenced keys from source
ref = {}
pat = re.compile(r"'([a-zA-Z][a-zA-Z0-9_.]*)'\s*\|\s*transloco|translate\(\s*'([a-zA-Z][a-zA-Z0-9_.]*)'")
for dirpath, _, files in os.walk('src'):
    for f in files:
        if not f.endswith('.ts'):
            continue
        fp = os.path.join(dirpath, f)
        for m in pat.finditer(io.open(fp, encoding='utf-8').read()):
            ref.setdefault(m.group(1) or m.group(2), set()).add(fp)

missing = {k: v for k, v in ref.items() if k not in known}
print('referenced keys: %d   known keys: %d   missing: %d' % (len(ref), len(known), len(missing)))
for k in sorted(missing):
    print('  MISSING  %-45s  <- %s' % (k, sorted(missing[k])[0]))
sys.exit(1 if missing else 0)
