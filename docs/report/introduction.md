# Introduction générale

> **Miroir Markdown** de `report/Rapport PFE TEKUP LATEX/introduction.tex` (pour lecture rapide ;
> la source officielle reste le fichier `.tex`).

La gestion de projets informatiques constitue aujourd'hui un enjeu stratégique majeur pour les
entreprises de services du numérique. Au-delà du pilotage opérationnel (équipes, plannings,
livrables), c'est la maîtrise de la dimension *financière* des projets — coûts réels, marges,
budgets, facturation — qui détermine la rentabilité et la pérennité de l'activité.

Dans de nombreuses organisations, ce suivi repose encore sur des fichiers Excel dispersés. Ce mode
de fonctionnement, s'il offre une grande souplesse, présente des limites importantes : absence de
centralisation, risques d'erreurs de calcul, faible visibilité sur les coûts réels, difficulté de
consolidation des indicateurs et traçabilité limitée des décisions. Le fichier de référence fourni
par l'entreprise — un classeur de revue de projet comportant vingt-et-un onglets — illustre à la
fois la richesse métier de ce suivi et la fragilité de son support.

C'est dans ce contexte que s'inscrit ce projet de fin d'études, dont l'objectif est de concevoir et
de développer une **plateforme web centralisée de gestion et de pilotage financier des projets
informatiques**, désignée par l'acronyme **PMS** (*Project Management System*). Cette plateforme
vise à remplacer le processus basé sur Excel par une source unique et fiable d'information, tout en
préservant — et en automatisant — la logique métier réelle : taux de coût chargé (TCC), plan de
charge, charges réelles, indicateurs de performance (EAC, marges, avancement de facturation),
missions et facturation.

Une exigence architecturale structure l'ensemble du projet : le système ne doit jamais coupler
directement un rôle à un module. L'autorisation repose sur un modèle dynamique
**Rôle → Permission → Module**, de sorte que l'évolution des responsabilités métier ne nécessite
qu'une modification de configuration, sans aucune modification du code source. La plateforme doit
ainsi être configurable, maintenable, évolutive et sécurisée.

Ce rapport rend compte de la démarche d'ingénierie suivie, organisée en phases successives et
validées. Le **premier chapitre** présente le contexte général du projet : l'organisme d'accueil,
l'étude de l'existant, la problématique et la solution proposée. Le **deuxième chapitre** est
consacré à l'analyse et à la spécification des besoins : acteurs, besoins fonctionnels et non
fonctionnels, règles de gestion et modèle financier. Les chapitres suivants détailleront la
conception (architecture, base de données, UML) puis la réalisation des différents modules, avant
un chapitre dédié aux tests et au déploiement.
