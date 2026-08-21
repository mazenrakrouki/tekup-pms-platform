# -*- coding: utf-8 -*-
"""Compare key sets between en.json and fr.json for every i18n scope."""
import io, json, os, sys

def flat(o, p=''):
    out = set()
    for k, v in o.items():
        key = p + '.' + k if p else k
        if isinstance(v, dict):
            out |= flat(v, key)
        else:
            out.add(key)
    return out

root = 'public/i18n'
bad = 0
scopes = [('(root)', root)]
for d in sorted(os.listdir(root)):
    if os.path.isdir(os.path.join(root, d)):
        scopes.append((d, os.path.join(root, d)))

for name, path in scopes:
    en_p, fr_p = os.path.join(path, 'en.json'), os.path.join(path, 'fr.json')
    if not (os.path.exists(en_p) and os.path.exists(fr_p)):
        continue
    en = flat(json.load(io.open(en_p, encoding='utf-8')))
    fr = flat(json.load(io.open(fr_p, encoding='utf-8')))
    only_en, only_fr = en - fr, fr - en
    status = 'OK' if not (only_en or only_fr) else 'MISMATCH'
    if status == 'MISMATCH':
        bad += 1
    print('%-12s %-9s en=%-4d fr=%-4d' % (name, status, len(en), len(fr)))
    for k in sorted(only_en):
        print('    only in EN: ' + k)
    for k in sorted(only_fr):
        print('    only in FR: ' + k)

sys.exit(1 if bad else 0)
