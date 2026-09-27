# Brief d'équipe — Mise en conformité technique de la GED Marchica Med

## Mission

Rendre la GED conforme à 100 % au **Dossier d'analyse technique et d'intégration V3**
(référence IPTECH — AO 07/AO/MM/26). Chaque exigence est listée dans `MATRICE-TECHNIQUE.md`
avec son statut actuel ; `FEUILLE-DE-ROUTE.md` répartit les 86 exigences non conformes en
étapes E0 à E11, avec tâches et critère de sortie.

- Dossier source (PDF, 36 pages, à lire avec l'outil Read et le paramètre `pages`) :
  `C:\Users\abdou\Downloads\Dossier d'analyse technique et d'intégration V3.pdf`
- En cas de doute sur un mécanisme : **le PDF fait foi**, pas la matrice.
- Le dossier fonctionnel V3 (`MATRICE-FONCTIONNELLE.md`) fait foi pour les règles métier.

## Équipe (5 personnes, pas une de plus)

| Nom | Rôle | Copie de travail | Branche |
|---|---|---|---|
| dev1 | Développeur back-end senior — données, identité, autorisation | `C:\Users\abdou\ged-wt\dev1` | `ct/dev1` |
| dev2 | Développeur back-end senior — qualité, exploitation, traçabilité, API | `C:\Users\abdou\ged-wt\dev2` | `ct/dev2` |
| dev3 | Développeur back-end senior — fichiers, OCR, recherche, cycle de vie | `C:\Users\abdou\ged-wt\dev3` | `ct/dev3` |
| qa | Testeur | `C:\Users\abdou\ged-wt\qa` | `ct/qa` |
| pm | Chef de projet et intégrateur | `C:\Users\abdou\ged-app` | `conformite-technique` |

Les développeurs back-end prennent aussi en charge les adaptations Angular nécessaires à
leurs lots (il n'y a pas de développeur front dans l'équipe).

## Règles de travail

1. **Chacun travaille uniquement dans sa copie de travail et sur sa branche.** Ne jamais
   modifier la copie d'un autre membre. Ne jamais changer de branche dans sa copie.
2. **Commits** : petits, fréquents, message en français à l'impératif, sur sa branche
   uniquement. Terminer chaque message par la ligne :
   `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
   Ne jamais pousser (`git push` interdit), ne jamais réécrire l'historique partagé.
3. **Intégration** : seul `pm` fusionne les branches dans `conformite-technique`. Pour
   récupérer le travail intégré, un membre fait `git merge conformite-technique` dans sa
   propre branche quand `pm` le lui demande.
4. **Base de données** : PostgreSQL 16 local, `localhost:5432`, utilisateur `postgres`
   sans mot de passe (authentification `trust`). Chaque membre utilise **ses propres bases**,
   préfixées par son nom : `ged_dev1`, `ged_dev1_test`, `ged_qa_test`, etc.
   Ne jamais toucher aux bases existantes `erp`, `pressing_pos`, `postgres`, ni aux rôles
   `erp`, `pressing`. `psql` : `"C:\Program Files\PostgreSQL\16\bin\psql.exe"`.
   Les configurations `french`, `arabic`, et les extensions `unaccent`, `pg_trgm` existent.
5. **Pas de Docker** sur ce poste : pas de Testcontainers. Les tests d'intégration visent
   la base PostgreSQL locale propre à chaque membre (profil `test`, URL surchargeable par
   variable d'environnement).
6. **Ports** : ne jamais démarrer l'application sur 8080, 8081 ni 4301, ni utiliser le port
   d'annuaire 33389 ni la base `ged_demo` : ils servent à l'instance de tests manuels de
   l'utilisateur, lancée depuis `C:\Users\abdou\ged-app` (ne jamais l'arrêter). Si un
   démarrage est indispensable, utiliser 18081 (dev1), 18082 (dev2), 18083 (dev3),
   18084 (qa), 18085 (pm), et arrêter le processus ensuite.
6 bis. **Tests en parallèle** : les suites de plusieurs membres tournent en même temps sur le
   même poste. Sans réglage, elles se bloquent : même port d'annuaire simulé (33390) et
   saturation de PostgreSQL (`max_connections` = 100, un pool par contexte Spring gardé en
   cache). Pour **toute** exécution de `mvn test`, exporter :

   | Membre | `GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT` | `GED_IDENTITE_ANNUAIRE_URLS` | `GED_SMTP_PORT_TEST` |
   |---|---|---|---|
   | dev1 | 33391 | `ldap://localhost:33391` | 3031 |
   | dev2 | 33392 | `ldap://localhost:33392` | 3032 |
   | dev3 | 33393 | `ldap://localhost:33393` | 3033 |
   | qa | 33394 | `ldap://localhost:33394` | 3034 |
   | pm | 33395 | `ldap://localhost:33395` | 3035 |
   | instance manuelle de l'utilisateur | 33389 (ne pas utiliser) | — | — |

   et `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3`. Exemple (dev2, Git Bash) :
   `DB_NAME=ged_dev2 GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT=33392 GED_IDENTITE_ANNUAIRE_URLS=ldap://localhost:33392 GED_SMTP_PORT_TEST=3032 SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3 mvn -q test`.

   `GED_SMTP_PORT_TEST` fixe le port du SMTP simulé des tests de notification (GreenMail) :
   `NotificationsTest` le démarre sur ce port et `application-test.yml` y adresse
   `spring.mail.port` (3025 si la variable est absente, valeur des suites antérieures).

   Ces noms sont les **seuls à utiliser**. Spring les rattache d'office (liaison souple des
   variables d'environnement) aux propriétés `ged.identite.annuaire.embarque.port`,
   `ged.identite.annuaire.urls` et `spring.datasource.hikari.maximum-pool-size`, et une
   variable d'environnement l'emporte sur `application-test.yml`. Aucune modification de la
   configuration de test n'est donc nécessaire ; vérifié par pm (annuaire embarqué à l'écoute
   sur 33395, suite complète verte). La variable `GED_TEST_ANNUAIRE_PORT` introduite par dev1
   sur sa branche est inutile : ne pas l'utiliser, et la retirer au profit de ces deux noms
   quand elle sera intégrée.

   Limite connue : une erreur « remaining connection slots are reserved » signale une
   saturation de PostgreSQL par les suites des autres membres : relancer, sans modifier le
   code.
7. **Front** : `node_modules` n'existe que dans `C:\Users\abdou\ged-app\frontend`. Dans une
   copie de travail, créer une jonction avant de compiler :
   `cmd /c mklink /J frontend\node_modules C:\Users\abdou\ged-app\frontend\node_modules`
   (ne jamais la committer : elle est déjà ignorée par `.gitignore`, vérifier).
8. **Absents du poste** : ClamAV, LibreOffice, veraPDF, annuaire AD, NGINX, Prometheus.
   Implémenter le mécanisme complet derrière une interface, le configurer pour
   l'environnement de MMED, et le tester avec un simulateur (serveur clamd factice, serveur
   LDAP embarqué UnboundID, etc.). Signaler clairement dans le rapport ce qui n'a pu être
   vérifié qu'avec un simulateur.
9. **Secrets** : aucun secret dans le dépôt. Valeurs par variables d'environnement,
   `backend/.env.example` documente chaque variable.
10. **Conventions du projet** (voir `README.md`) : code et commentaires en français ; les
    commentaires expliquent **pourquoi**, pas ce que le code dit déjà. Couches contrôleur,
    service, dépôt, DTO. Validation complète avant toute écriture.
11. **Tests** : toute fonctionnalité livrée vient avec ses tests. `mvn -q test` doit rester
    vert sur sa branche avant de rendre compte. Ne jamais désactiver un test pour passer.
12. **Suivi** : chaque membre tient `docs/conformite/suivi/<nom>.md` dans sa copie : lot en
    cours, exigences traitées (référence de la matrice), ce qui reste, points bloquants,
    ce qui n'est vérifié que par simulateur.

## Définition de « terminé » pour une exigence

- Le mécanisme décrit dans le PDF est implémenté tel quel (noms de tables, codes, valeurs).
- Il est couvert par des tests automatisés qui passent.
- La ligne correspondante peut honnêtement passer à « Identique » dans la matrice.
