# Recette E1 — socle de données

Scripts indépendants du code applicatif (bash, psql, Java 17 en mode fichier source ; **aucun Python**, décision D5). Tous
produisent des lignes `RESULTAT|id|OK/ECHEC/AVERT/NA|libellé|détail` puis `BILAN|…` ;
code de sortie 0 si aucun `ECHEC`. Connexion par les variables libpq (`PGHOST`,
`PGPORT`, `PGUSER`, `PGPASSWORD` ou `~/.pgpass`) ; aucun secret dans les scripts.

| Script | Prouve | Modifie la base ? |
|---|---|---|
| `verifier-base-vierge.sh` | Critère de sortie E1 : base vierge créée **uniquement** par Liquibase (compte `ged_owner`), puis tous les contrôles ci-dessous ; avec `--demarrer-application`, l'application démarre en `ged_app` et le DDL reste identique (Hibernate ne crée rien) | Oui : base jetable `ged_qa_recette_e1` (refuse tout nom qui n'est pas `ged_qa*` ou `*recette*`) |
| `verifier-rollback.sh` | Retour arrière de tous les changesets, schéma vidé, registre vidé, aller-retour update → rollback → update au DDL identique ; `--pas-a-pas` isole le changeset fautif | Oui : même base jetable |
| `verifier-socle.sh` | 38 contrôles de catalogue (nommage snake_case/idx_/uk_/fk_/ck_, PK `id` uuid, pas d'auto-incrément, suppression douce, `metadonnees` JSONB + GIN, registre Liquibase, `data-initial`, rôles et privilèges) + 17 sondes réelles `SET ROLE` (ged_app sans DDL, ged_readonly sans écriture, audit en INSERT seul) | Non (sondes annulées par sous-transaction) |
| `AnalyseurChangelogs.java` (`java -Dfile.encoding=UTF-8 AnalyseurChangelogs.java --backend backend`) | Revue statique : nom des fichiers `AAAAMMJJHHmm_objet.xml`, `<rollback>` sur tout changeset non auto-réversible, données étiquetées `data-initial`, aucun référentiel métier amorcé, `ddl-auto` validate/none, pas de Flyway/MySQL/H2, pas d'amorçage Java | Non |
| `autotest/lancer-autotest.sh` | Les contrôles eux-mêmes : schéma conforme → 0 écart ; schéma non conforme → 36 écarts volontaires détectés ; changelogs conformes/non conformes → 16 écarts détectés | Base `ged_qa_test` (schémas `recette_ok`, `recette_ko`, rôles `qa_ged_*`) |
| `autotest/lancer-autotest-liquibase.sh BACKEND` | `verifier-rollback.sh` détecte un changeset sans rollback et un faux rollback | Base jetable `ged_qa_recette_e1_at`, supprimée à la fin |

`exceptions.txt` liste les seuls écarts aux conventions **imposés par le DAT lui-même**
(identifiant séquentiel et clé composite du journal d'audit partitionné, §7.4.1 et §7.4.3 ;
nom de colonne `supprime_par`, §12.5). Toute autre exception est refusée.

## Enchaînement type (poste qa, après intégration de E1)

```
export PGHOST=localhost PGUSER=postgres
bash recette/e1/verifier-base-vierge.sh --demarrer-application
bash recette/e1/verifier-rollback.sh
bash recette/e1/verifier-socle.sh           # sur n'importe quelle base migrée (PGDATABASE=…)
```

En UAT : `verifier-socle.sh` s'exécute sur la base UAT avec le compte `ged_readonly`
(`--sans-sondes`, car les sondes exigent de pouvoir prendre les trois rôles) ; les
scripts destructifs s'exécutent sur une **copie** restaurée (`ged_uat_recette`), jamais
sur la base UAT elle-même. Sans Maven sur le serveur, `GED_LIQUIBASE_CMD` désigne la
CLI Liquibase officielle.

## Liquibase sans téléchargement

`lib/LiquibaseRecette.java` est lancé en mode fichier source avec le classpath
d'exécution du backend, résolu **hors ligne** (`mvn -o dependency:build-classpath`) :
la recette migre avec exactement le `liquibase-core` et le pilote PostgreSQL livrés. Le
greffon Maven Liquibase n'est pas utilisé : il exige des artefacts absents du dépôt
local (`liquibase-commercial`, `commons-lang3` 3.15).
