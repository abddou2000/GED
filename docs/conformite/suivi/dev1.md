# Suivi — dev1

## Lot en cours

**E1 — Socle de données** : terminé côté développement, branche `ct/dev1`, en attente
d'intégration par pm. Prochain lot prévu : E2 (identité et sessions).

## Exigences traitées (références « Réf. » de MATRICE-TECHNIQUE.md)

| Réf. | Exigence | Statut proposé | Preuve |
|---|---|---|---|
| 2.2 | SGBD PostgreSQL 16 ou plus, configuration arabe vérifiée | Identique | H2 et MySQL retirés ; PostgreSQL dans dev, test, prod. Prérequis du changelog maître (version ≥ 16, `pg_ts_config` `arabic`) bloquant ; `SocleDonneesTest.stackPostgresql` (dont `to_tsvector('arabic', …)`). |
| 2.2 | Outil de migration Liquibase 4 | Identique | Flyway retiré ; `liquibase-core` 4.29 ; `db/changelog/db.changelog-master.xml`. |
| 4.2.1 | Aucune modification de schéma hors migration versionnée | Identique | `ddl-auto: validate` dans tous les profils ; `ged_app` sans aucun droit DDL (`SocleDonneesTest.compteApplicatifSansDdl`, SQLSTATE 42501). |
| 4.2.1 | Amorçage initial par migration, référentiels métier via l'interface | Proche | Jeux de démonstration (espaces, types, index, plans, étiquettes, groupes, employés) limités au profil `dev` : plus rien d'amorcé en prod. Convention `labels="data-initial"` posée. Restent : `CompteSeeder` (Java, supprimé par E2) ; aucune donnée de référence technique n'existe encore (permissions, rôles système, niveaux de confidentialité arrivent en E3, en changesets `data-initial`). |
| 4.2.2 | Conventions de nommage (snake_case, idx_, uk_, fk_) | Identique | 1 fichier par évolution `AAAAMMJJHHmm_objet_metier.xml` ; tables renommées au singulier pour que chaque FK soit `<table>_id` (`document`, `version_document`, `type_document`, `index_def`, `plan_indexation`, `plan_index`…) ; `pk_ uk_ fk_ ck_ idx_` vérifiés par `SchemaLiquibaseTest`. |
| 4.2.2 | Retour arrière explicite par changeset, expand / contract | Identique (hors exécution UAT) | `<rollback>` explicite sur chaque changeset ; `SchemaLiquibaseTest` : montée complète sur schéma vierge, retour arrière de TOUS les changesets, remontée ; jalon `socle-e1`. Procédure expand/contract et retour arrière CLI dans `DEPLOIEMENT.md` §8. Pas d'UAT disponible pour l'exécution « en UAT » (E0). |
| 4.2.3 | Trois rôles PostgreSQL | Identique | `backend/scripts/db/creer-roles.sql` (idempotent, jamais de DROP ni de changement de mot de passe) + `preparer-base.sql` (par base : schémas `ged` / `ged_liquibase`, privilèges par défaut). Liquibase en `ged_owner`, application en `ged_app` (vérifié en test, en dev et en prod). |
| 12.1 | Clés primaires UUID | Identique | Toutes les entités en UUID v7 (`IdentifiantUuid`, `UuidV7`), DTO, contrôleurs, dépôts, tests, front. Vérifié en base par `SchemaLiquibaseTest.verifierClesUuid`. |
| 12.1 | Modèle logique en sept groupes de tables | Proche | Tables existantes alignées sur les noms du §12.1 quand la correspondance est directe. Les tables des autres groupes (utilisateur, session, habilitation, noeud, journal_audit…) relèvent des lots E2 à E9. |
| 5.3.2 | JSON UTF-8, dates ISO 8601 UTC, identifiants opaques UUID | Identique pour les identifiants | Identifiants d'API = UUID (chaînes) ; identifiant mal formé → 400 au format commun. Format des dates non modifié par ce lot. |
| 12.5 | Suppression douce avec auteur et date | Identique (suppression douce) | `supprime_par` / `supprime_le` sur les 8 tables à corbeille, renseignés à la suppression (simple et multiple), vidés à la restauration ; contrainte `ck_<table>_suppression`. `SocleDonneesTest.suppressionDouceAvecAuteurEtDate`. Purge : lot E7. |
| 12.7 | Métadonnées en JSONB avec index GIN | Proche | `document.metadonnees` JSONB NOT NULL `{}` + `idx_document_metadonnees` GIN + `ck_document_metadonnees` ; mappé dans l'entité. Pas encore alimenté (lot E7). |

Tâche E1 « tests d'intégration sur PostgreSQL réel » : faite sur PostgreSQL local
(`ged_dev1_test`), sans Testcontainers (pas de Docker sur le poste, cf. brief).

## Livrables annexes

- Reprise des données : `backend/scripts/reprise/` (export MySQL, transit, transfert en
  une transaction avec table de correspondance ancien id → UUID, contrôles),
  documentée dans `DEPLOIEMENT.md` §7 ; `RepriseDonneesTest` sur un export d'essai.
- Front Angular : identifiants en chaînes UUID, `ng build` OK ; jeu de démonstration
  converti.
- Documentation : `LISEZ-MOI.md`, `DEPLOIEMENT.md`, `backend/.env.example`.

## Tests

`mvn test` : 156 tests, 0 échec (143 existants adaptés + 13 nouveaux :
`SchemaLiquibaseTest` 2, `SocleDonneesTest` 7, `RepriseDonneesTest` 1, `UuidV7Test` 3).

## Ce qui reste / points d'attention pour l'intégration

- Tables renommées (`documents_file` → `document`, `document_versions` →
  `version_document`, `indices` → `index_def`, etc.) : tout travail parallèle
  qui écrit du SQL natif ou une migration sur les anciens noms doit être rebasé
  sur ce changelog. Flyway n'existe plus : toute nouvelle évolution est un
  changeset Liquibase.
- `StorageService.store(...)` : seule modification, le type du paramètre
  `workspaceId` (`Long` → `UUID`) ; rien d'autre n'a été touché au stockage.
- Chaque membre doit exécuter `creer-roles.sql` (déjà fait sur ce serveur, rôles
  partagés) puis `preparer-base.sql -v base=<sa base>` (et `-v tests=oui` pour sa
  base de test).
- Exécution du retour arrière « en UAT » : dépend de l'environnement UAT (E0).

## Vérifié uniquement hors cible

- `exporter-mysql.sh` n'a pas été exécuté : pas de serveur MySQL sur le poste. Le
  reste de la chaîne de reprise a été exécuté sur un export d'essai au même format.
- Mots de passe des rôles : poste en authentification `trust` ; le chemin « mot de
  passe fourni » de `creer-roles.sql` n'a pas été exercé.
