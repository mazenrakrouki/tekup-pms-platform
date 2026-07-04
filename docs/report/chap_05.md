# Chapitre 5 — Conception UML

> **Miroir Markdown** de `report/Rapport PFE TEKUP LATEX/chap_05.tex` (source officielle = `.tex`).
> Sources PlantUML et miroirs Mermaid complets : [docs/UML_DESIGN.md](../UML_DESIGN.md).
> Politique deux-niveaux UML : ADR-024 — Niveau 1 (rapport, simple) vs Niveau 2 (ingénierie,
> détaillé, sous `docs/uml/engineering/`, **hors rapport**).

## Introduction
**11 diagrammes Niveau 1** : 1 cas d'utilisation · 1 packages (10 modules) · 4 classes par domaine
(Sécurité · Projet & Équipe · Financier · Charges & KPI) · 3 séquences (connexion, affectation,
charges → KPI) · 2 activités (cycle de vie, recalcul KPI). Chaque diagramme tient sur une page et
se comprend en moins de 30 secondes.

## 1. Cas d'utilisation
4 acteurs, 12 cas d'utilisation métier. `ASSIGN_DEVELOPER` attribuée par défaut au Directeur **et**
au CP (ADR-005).

![Cas d'utilisation](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/use_case.png)

## 2. Packages — 10 modules fonctionnels

![Diagramme de packages](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/package_diagram.png)

## 3. Classes par domaine

### 3.1 Sécurité
RBAC dynamique : User–Role–Permission (mapping configurable en base, ADR-001).

![Classes Sécurité](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_security.png)

### 3.2 Projet & Équipe
Projet avec directeur + chefs de projet (succession) + équipe (resources). User/Resource séparés
(ADR-022).

![Classes Projet & Équipe](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_project_team.png)

### 3.3 Financier
Jalons de facturation + paiements ; missions ; avenants — rattachés au projet.

![Classes Financier](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_financial.png)

### 3.4 Charges & KPI
Série temporelle mensuelle (plan vs réel) ; TCC par année ; snapshots KPI historisés.

![Classes KPI](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/class_kpi.png)

## 4. Séquences

### 4.1 Connexion
Vérification du mot de passe (BCrypt), émission du jeton JWT, redirection pour première connexion.

![Séquence connexion](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/seq_login.png)

### 4.2 Affecter un développeur (permission ET portée)
Vérification permission `ASSIGN_DEVELOPER` puis portée (projet accessible).

![Séquence affectation](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/seq_assign_developer.png)

### 4.3 Saisir des charges → recalcul KPI
Saisie synchrone, recalcul KPI **asynchrone** déclenché après commit.

![Séquence charges KPI](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/seq_submit_workload.png)

## 5. Activités

### 5.1 Cycle de vie d'un projet

![Cycle de vie](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/act_project_lifecycle.png)

### 5.2 Recalcul hybride des KPI

![Recalcul KPI](../../report/Rapport%20PFE%20TEKUP%20LATEX/img/act_kpi_recompute.png)

## Conclusion
11 diagrammes simples couvrent acteurs, modules, domaine, flux clés et cycle de vie. Les
diagrammes d'ingénierie détaillés restent dans `docs/uml/engineering/` (hors rapport). Suivant :
implémentation, en commençant par l'authentification et le RBAC dynamique.
