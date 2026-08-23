# -*- coding: utf-8 -*-
"""
Audit i18n : distingue le texte réellement visible par l'utilisateur des simples
commentaires de code.

La première mesure comptait toute ligne contenant du français, commentaires inclus,
ce qui gonflait le chiffre des fichiers `core/` — leurs "532 lignes" sont en grande
partie des explications de code, pas de l'interface.

Deux catégories sont donc rapportées séparément :
  UI      chaîne susceptible d'être affichée (texte de gabarit, littéral passé à un
          toast, un confirm, un placeholder, un title, un aria-label)
  COMMENT commentaire ou documentation — sans effet sur la langue de l'interface
"""
import io, os, re, sys

FRENCH_ACCENT = re.compile(r'[àâäéèêëîïôöùûüçÀÂÄÉÈÊËÎÏÔÖÙÛÜÇ]')
FRENCH_WORD = re.compile(
    r'(?<![A-Za-zÀ-ÿ])('
    r'Supprimer|Annuler|Ajouter|Enregistrer|Statut|Nouveau|Nouvelle|Modifier|Rechercher|'
    r'Aucun|Aucune|Utilisateur|Utilisateurs|Compte|Actif|Inactif|Fermer|Confirmer|Creer|'
    r'Tous|Toutes|Valider|Valide|Soumettre|Charges|Jalon|Jalons|Avenant|Avenants|Paiement|'
    r'Paiements|Livrable|Livrables|Risque|Risques|Mission|Missions|Budget|Marge|Client|'
    r'Projet|Projets|Equipe|Selectionner|Chef|Bailleur|Engagement|Periode|Ressource'
    r')(?![A-Za-zÀ-ÿ])')

COMMENT = re.compile(r'^\s*(//|/\*|\*|<!--)')

def classify(line):
    if COMMENT.match(line):
        return 'COMMENT'
    return 'UI'

def scan(root):
    rows = []
    for dirpath, _dirs, files in os.walk(root):
        if 'node_modules' in dirpath:
            continue
        for name in files:
            if not name.endswith('.ts'):
                continue
            p = os.path.join(dirpath, name)
            ui = com = 0
            samples = []
            for line in io.open(p, encoding='utf-8', errors='replace').read().split('\n'):
                if not (FRENCH_ACCENT.search(line) or FRENCH_WORD.search(line)):
                    continue
                kind = classify(line)
                if kind == 'COMMENT':
                    com += 1
                else:
                    ui += 1
                    if len(samples) < 3:
                        samples.append(line.strip()[:80])
            if ui or com:
                rel = os.path.relpath(p, root).replace('\\', '/')
                rows.append((ui, com, rel, samples))
    return rows

root = sys.argv[1] if len(sys.argv) > 1 else 'src/app'
rows = scan(root)
rows.sort(key=lambda r: (-r[0], -r[1]))

tot_ui = sum(r[0] for r in rows)
tot_com = sum(r[1] for r in rows)
files_ui = len([r for r in rows if r[0]])

print('VISIBLE UI strings : %d lines across %d files' % (tot_ui, files_ui))
print('Code comments      : %d lines (no effect on interface language)' % tot_com)
print()
print('%-58s %5s %8s' % ('file', 'UI', 'comment'))
print('-' * 74)
for ui, com, rel, _s in rows:
    if ui:
        print('%-58s %5d %8d' % (rel, ui, com))
print('-' * 74)
print('%-58s %5d %8d' % ('TOTAL', tot_ui, tot_com))

if '-v' in sys.argv:
    print('\nSamples of remaining UI strings:')
    for ui, _c, rel, samples in rows:
        if ui and samples:
            print('\n  %s' % rel)
            for s in samples:
                print('    %s' % s)
