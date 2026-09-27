# Registre des anomalies de recette

Tenu par : qa. Une anomalie = un écart constaté entre le comportement livré et le dossier
technique V3 (le PDF fait foi) ou le critère de sortie d'une étape, **reproductible** par
les étapes indiquées. Les écarts déjà inventoriés dans `MATRICE-TECHNIQUE.md` avant
livraison ne sont pas recopiés ici : on n'enregistre que ce qu'une livraison n'a pas
tenu.

## Gravité

| Niveau | Définition |
|---|---|
| Bloquante | Le critère de sortie de l'étape ne peut pas être déclaré atteint (ex. fichier en clair sur le disque, objet hors périmètre visible). |
| Majeure | Une exigence de la matrice n'est pas tenue, mais le critère de sortie reste démontrable par ailleurs. |
| Mineure | Écart de forme (nommage, message, code d'erreur métier) sans effet sur la sécurité ni l'intégrité. |

## Statuts

`Ouverte` → `Corrigée` (le développeur indique le commit) → `Vérifiée` (qa a rejoué les
étapes et le script de recette concerné) ou `Rouverte`. `Rejetée` uniquement avec la
référence du PDF qui justifie le comportement.

## Registre

| Identifiant | Étape | Réf. matrice | Gravité | Description | Étapes de reproduction | Constaté sur (branche @ commit) | Statut |
|---|---|---|---|---|---|---|---|
| ANO-E1-001 | E1 | 4.2.2 | Majeure | Quatre tables ont une clé primaire composite au lieu de la colonne `id` exigée par le DAT §4.2.2 (« clé primaire `id` ») : `access_group_employe (access_group_id, employe_id)`, `access_group_workspace (access_group_id, workspace_id)`, `document_etiquette (document_id, etiquette_id)`, `plan_index (plan_indexation_id, position)`. Choix assumé par dev1 (`SchemaLiquibaseTest.ASSOCIATIONS` les exempte), mais le DAT n'en prévoit aucune et `plan_index` n'est pas une simple association. Correction : clé `id uuid` + contrainte `uk_<table>_<colonnes>` sur le couple, ou dérogation écrite de MMED (alors ajoutée à `recette/e1/exceptions.txt`). | `bash recette/e1/verifier-base-vierge.sh` → `E1-C04 ECHEC` ; ou `verifier-socle.sh` sur toute base migrée | `ct/qa` @ `0556637` (conformite-technique `fbb951c`) | Ouverte |
| ANO-E1-002 | E1 | 12.5 | Majeure | L'indicateur de suppression douce s'appelle `deleted` sur les 8 tables à corbeille (`access_group`, `document`, `etiquette`, `index_def`, `plan_indexation`, `type_document`, `workflow_ged`, `workspace`) alors que le DAT §12.5 nomme le triplet « `supprime`, `supprime_par`, `supprime_le` ». `supprime_par` et `supprime_le` sont corrects (types et alimentation vérifiés en base après une suppression par l'API). La définition de « terminé » du brief exige les noms du PDF : la ligne ne peut pas passer à « Identique ». | `verifier-socle.sh` → `E1-C15`, `E1-C16 ECHEC` ; `SELECT table_name FROM information_schema.columns WHERE table_schema='ged' AND column_name='deleted'` | `ct/qa` @ `0556637` | Ouverte |
| ANO-E1-003 | E1 | 4.2.1 | Mineure | `SUIVI.md` (T-020) annonce « Livré : seeders Java d'amorçage supprimés ; changesets `data-initial` » alors que le changelog ne contient **aucun** changeset `data-initial` et que 9 seeders écrivent encore au démarrage (8 limités aux profils `dev`/`test`, `CompteSeeder` dans tous les profils). Le suivi de dev1 dit lui-même « Proche » (suite en E2/E3) : c'est le statut du tableau de pilotage qui est faux, pas une régression. | `verifier-socle.sh --base-vierge` → `E1-C22 ECHEC` ; `java -Dfile.encoding=UTF-8 recette/e1/AnalyseurChangelogs.java` → `E1-A23` | `ct/qa` @ `0556637` | Ouverte |
| ANO-E5-001 | E5 | 6.1.2 | Mineure (sécurité) | Le profil `dev` crée le keystore des clés maîtresses à `./data/cles/ged-kek.p12`, **relatif au répertoire courant**, et `application-dev.yml` affirme « data/ n'est pas versionné » ; or `.gitignore` n'ignore que `backend/data/`. Lancée depuis la racine du dépôt, l'application crée `data/cles/ged-kek.p12` que `git add -A` versionnerait (une KEK dans la forge). Correction : ignorer `data/` à la racine (ou `**/data/cles/`, `*.p12`) et/ou résoudre le chemin par rapport à un répertoire fixe. | Depuis la racine du dépôt : `DB_NAME=ged_qa java -jar backend/target/ged-0.0.1-SNAPSHOT.jar --server.port=18084` puis `git status` → `?? data/` | `ct/qa` @ `0556637` | Ouverte |

Format de l'identifiant : `ANO-<étape>-<numéro à 3 chiffres>`, par exemple `ANO-E1-001`.
