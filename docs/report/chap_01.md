# Chapitre 1 — Contexte général du projet

> **Miroir Markdown** de `report/Rapport PFE TEKUP LATEX/chap_01.tex` (pour lecture rapide ;
> la source officielle reste le fichier `.tex`).

## Introduction
Ce premier chapitre situe le projet dans son contexte. Il présente l'organisme d'accueil, analyse le
processus existant de suivi des projets — fondé sur des fichiers Excel — et en dégage les limites. De
cette étude découlent la problématique, la solution proposée (la plateforme PMS) et la méthodologie
d'ingénierie retenue pour la conduite du projet.

## 1. Cadre du projet
Le présent travail constitue un Projet de Fin d'Études (PFE) en vue de l'obtention du Diplôme National
d'Ingénieur. Il a été réalisé au sein d'une entreprise de services du numérique (ESN), qui conçoit et
met en œuvre des systèmes d'information pour ses clients dans le cadre de projets contractualisés, le
plus souvent au forfait (FP) ou en régie (T&M).

## 2. Organisme d'accueil
L'entreprise d'accueil opère en tant qu'intégrateur de solutions logicielles. Son activité repose sur
la conduite simultanée de plusieurs projets, mobilisant un *pool* de ressources (développeurs,
analystes métier, chefs de projet) affectées à des projets clients. La rentabilité de l'entreprise
dépend directement de sa capacité à maîtriser, pour chaque projet, l'équilibre entre la charge
vendue, la charge réellement consommée et le chiffre d'affaires facturé.

## 3. Étude de l'existant

### 3.1 Le processus actuel
Le suivi de chaque projet est assuré au moyen d'un classeur Excel — la « fiche de revue de projet » —
comportant une vingtaine d'onglets interconnectés. Ce classeur centralise, pour un projet donné,
l'ensemble des informations de pilotage : identification et budget du projet, taux de coût chargé des
ressources, plan de charge prévisionnel, charges réelles, jalons de facturation, missions, risques,
livrables et tableaux de bord.

| Famille d'onglets | Rôle métier |
|---|---|
| Identification | Fiche d'identification du projet : client, contrat, budget, devise, durée, chef de projet, charge vendue. |
| Coûts (TCC, Coût Prévisionnel, Coût réel Actualisé) | Calcul du coût journalier des ressources et des coûts prévisionnels et réels par mois. |
| Facturation (Avancement, Récapitulatif) | Jalons de facturation en pourcentage du contrat, suivi des montants facturés et réglés. |
| Missions | Calcul des coûts de déplacement (perdiem, billet, séjour). |
| Pilotage (Statistiques, Dashboard, Glossaire) | Consolidation des indicateurs et définitions des KPI. |
| Gouvernance (Risques, Livrables, Parties Prenantes, Changements, Avenants) | Registres de suivi de la vie du projet. |

*Tableau 1.1 — Structure fonctionnelle du classeur Excel de revue de projet.*

### 3.2 Critique de l'existant
Si ce classeur traduit une logique métier riche et éprouvée, son support — le tableur — engendre des
limites structurelles :

- **Dispersion** : un classeur par projet, dupliqué et susceptible de diverger d'une version à
  l'autre, sans source unique de vérité.
- **Fiabilité des calculs** : la dépendance à des formules et à des références entre onglets est
  fragile ; le fichier réel fourni contient d'ailleurs des cellules en erreur (`#REF!`, `#VALUE!`).
- **Visibilité financière** : l'absence de consolidation rend difficile la lecture du coût réel et de
  la marge à l'échelle du portefeuille de projets.
- **Contrôle d'accès** : un tableur ne permet pas de cloisonner finement l'information — par exemple
  d'interdire au développeur l'accès aux données financières.
- **Traçabilité** : l'historique des modifications et des décisions n'est pas conservé de manière
  fiable.

## 4. Problématique
La question centrale est donc la suivante : *comment doter l'entreprise d'un outil centralisé, fiable
et sécurisé qui automatise la logique financière des projets aujourd'hui portée par Excel, tout en
restant suffisamment configurable pour absorber l'évolution des règles métier sans refonte ?* Deux
exigences se dégagent : préserver la richesse du modèle métier existant (TCC, plan de charge, EAC,
marges, facturation) et garantir une architecture d'autorisation *dynamique*, découplée des rôles.

## 5. Solution proposée
La solution retenue est le développement d'une plateforme web centralisée, **PMS** (*Project
Management System*). Elle offre une source unique d'information et automatise le pilotage opérationnel
et financier des projets. Ses objectifs sont :

- centraliser les données des projets, ressources et équipes ;
- planifier la charge (en jours-homme) et saisir les charges réelles ;
- gérer les jalons de facturation et les missions ;
- calculer automatiquement les indicateurs financiers (EAC, marges, avancement de facturation, reste
  à faire, dérive) ;
- fournir des tableaux de bord décisionnels adaptés à chaque profil d'utilisateur ;
- reposer sur un contrôle d'accès dynamique **Rôle → Permission → Module**.

## 6. Méthodologie de travail
Le projet est conduit selon une démarche d'ingénierie **par phases successives et validées** :
analyse métier, conception de l'architecture, conception de la base de données, modélisation UML,
puis réalisation itérative des modules (authentification et RBAC, gestion des utilisateurs, des rôles
et permissions, des projets, des équipes, du TCC, du plan de charge, des charges réelles, de la
facturation, des missions, puis des KPI et du *reporting*). Chaque phase produit des livrables
techniques, met à jour la documentation et alimente le présent rapport.

La conduite du projet s'appuie également sur une **priorité des sources de vérité** : les décisions
d'architecture validées priment, suivies des instructions métier, puis de la réalité extraite du
fichier Excel, des spécifications (BRS, cas d'utilisation, SRS) et enfin du plan de projet, considéré
comme une orientation stratégique à challenger.

## Conclusion
L'étude du contexte et de l'existant a mis en évidence la nécessité de remplacer un suivi Excel
dispersé par une plateforme centralisée, fiable et configurable. Le chapitre suivant approfondit
cette ambition en analysant et en spécifiant précisément les besoins fonctionnels et non fonctionnels
du système, ainsi que les règles de gestion et le modèle financier qui en constituent le cœur métier.
