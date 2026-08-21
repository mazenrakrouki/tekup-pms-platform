# -*- coding: utf-8 -*-
"""Detect French UI text in .ts sources.

Accent matching alone undercounts: many French UI words carry no accent
(Supprimer, Annuler, Ajouter, Statut...). This combines both signals and
excludes comments and transloco key references, so the count reflects
strings that still need extraction.
"""
import io
import os
import re
import collections

ACCENT = re.compile(u'[à-ÿÀ-Ý]')

WORDS = """Supprimer Annuler Ajouter Enregistrer Modifier Aucun Aucune Statut Livrable
Rechercher Fermer Valider Retour Nouveau Nouvelle Chef Projet Projets Favoris Assign
Ressources Utilisateurs Charge Jalon Avenant Paiement Devis Risque Equipe Toutes
Selectionner Gerer Suivant Precedent Elements Recents Debut Facturation Prevu"""
WORD = re.compile(r'\b(' + '|'.join(WORDS.split()) + r')')

COMMENT = re.compile(r'^\s*(//|/\*|\*)')
KEYREF = re.compile(r"'[a-zA-Z][A-Za-z0-9_.]*'\s*\|\s*transloco")
TRANSLATE = re.compile(r"translate\(\s*'[^']*'")


def scan():
    per_file = collections.Counter()
    for dirpath, _dirs, files in os.walk('src'):
        for name in files:
            if not name.endswith('.ts'):
                continue
            path = os.path.join(dirpath, name)
            for line in io.open(path, encoding='utf-8'):
                if COMMENT.match(line):
                    continue
                probe = KEYREF.sub('', line)
                probe = TRANSLATE.sub('', probe)
                if ACCENT.search(probe) or WORD.search(probe):
                    per_file[path] += 1
    return per_file


if __name__ == '__main__':
    counts = scan()
    print('files with French: %d    lines: %d' % (len(counts), sum(counts.values())))
    print('')
    for path, n in counts.most_common():
        pretty = path.replace(os.sep, '/').replace('src/app/', '')
        print('%4d  %s' % (n, pretty))
