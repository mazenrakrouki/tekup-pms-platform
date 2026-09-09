# -*- coding: utf-8 -*-
"""
Détecte tout texte d'interface écrit en dur dans les gabarits Angular, quelle que
soit la langue.

Pourquoi cet outil en plus de `i18n-audit.py` : celui-ci cherchait des indices de
français (accents, mots d'une liste). Il laissait donc passer « Ouvert »,
« Registre des risques », « Impact » — c'est-à-dire exactement les chaînes
signalées à l'usage. Chercher la langue est un mauvais critère ; le bon critère
est : ce nœud de texte passe-t-il par le système de traduction ?

Est signalé tout texte litteral rendu entre balises, ainsi que les attributs
visibles (placeholder, title, aria-label) qui ne sont pas liés.
"""
import io, os, re, sys

# Texte entre balises, hors interpolation.
TEXT_NODE = re.compile(r'>\s*([^<>{}\n]*[A-Za-zÀ-ÿ][^<>{}\n]*?)\s*<')
# Texte suivant une icône fermante, cas fréquent : <i class="bi ..."></i>Libellé
AFTER_ICON = re.compile(r'</i>\s*([^<>{}\n]*[A-Za-zÀ-ÿ][^<>{}\n]*)')
# Attributs visibles non liés (sans crochets).
ATTR = re.compile(r'(?<!\[)\b(placeholder|title|aria-label|alt)="([^"{}]*[A-Za-zÀ-ÿ][^"{}]*)"')

COMMENT = re.compile(r'^\s*(//|/\*|\*|<!--)')

# Fragments techniques à ignorer : classes utilitaires, unités, symboles.
IGNORE = re.compile(
    r'^(?:[A-Z]{2,6}|\d+|[%€$]|TND|EUR|USD|FCFA|JH|MD|EV|KPI|PPR|PPP|TCC|DI|N/A|'
    r'true|false|null|px|rem|em|auto|none|bi|fa)$', re.I)

def looks_technical(s):
    if IGNORE.match(s):
        return True
    if len(s) < 3:
        return True
    # Chaîne sans lettre minuscule et très courte : sigle.
    if s.isupper() and len(s) <= 6:
        return True
    # Fragment de code / chemin / expression.
    if re.search(r'[(){};=]|=>|\|\||&&|\bfunction\b|\bconst\b', s):
        return True
    return False

def scan(root):
    rows = []
    for dirpath, _dirs, files in os.walk(root):
        if 'node_modules' in dirpath:
            continue
        for name in files:
            if not name.endswith('.ts'):
                continue
            p = os.path.join(dirpath, name)
            hits = []
            for n, line in enumerate(io.open(p, encoding='utf-8', errors='replace').read().split('\n'), 1):
                if COMMENT.match(line):
                    continue
                cands = []
                for m in TEXT_NODE.finditer(line):
                    cands.append(m.group(1))
                for m in AFTER_ICON.finditer(line):
                    cands.append(m.group(1))
                for m in ATTR.finditer(line):
                    cands.append(m.group(2))
                for c in cands:
                    c = c.strip()
                    if not c or c.startswith('{{') or looks_technical(c):
                        continue
                    hits.append((n, c))
            if hits:
                rel = os.path.relpath(p, root).replace('\\', '/')
                rows.append((len(hits), rel, hits))
    return rows

root = sys.argv[1] if len(sys.argv) > 1 else 'src/app'
rows = sorted(scan(root), key=lambda r: -r[0])
total = sum(r[0] for r in rows)

print('Hardcoded UI text: %d occurrences across %d files' % (total, len(rows)))
print()
print('%-58s %s' % ('file', 'count'))
print('-' * 68)
for count, rel, _h in rows:
    print('%-58s %5d' % (rel, count))
print('-' * 68)
print('%-58s %5d' % ('TOTAL', total))

if '-v' in sys.argv:
    print()
    for count, rel, hits in rows:
        print('\n%s' % rel)
        for n, txt in hits[:12]:
            print('  %5d  %s' % (n, txt[:78]))
