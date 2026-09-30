# Résultats de recette — vague 9 (tour 2 : anomalies corrigées au tour 1, lignes livrées au tour 1)

Exécutés par qa le 30/09/2026 sur `ct/qa-r2`, créée depuis `claude/inspiring-lovelace-10bg1c`
(**08c710c**, tour 1 intégré : `1056f9c` dev1, `ae57911` dev3/dev5, `9c715df` dev2, `f28d172` dev4,
`eaa9a22` recette de la vague 8). Les corrections du tour 2 (développeurs au travail en même temps)
ne sont **pas** intégrées : elles seront revérifiées au tour 3. Aucun code applicatif modifié.

## 1. Environnement

| Élément | Ce poste | Réel / simulé |
|---|---|---|
| Application | JAR construit sur `ct/qa-r2` (hors ligne), profil dev, API 18084, management 18094, base `ged_qa` (montée par Liquibase de 103 à **108 changesets** au démarrage : changesets du tour 1 appliqués sur une base peuplée), `ged_qa_test` pour `mvn test` | réel |
| ClamAV | clamd 1.5.4, port 13394, base d'une signature (EICAR) | réel ; signatures officielles : MMED |
| LibreOffice | `soffice` **24.2.7.2** (`/usr/lib/libreoffice`) : aperçu DOCX et archivage Word → PDF/A-2B | **réel** (journal : « LibreOffice détecté ([/usr/bin/soffice]) ») |
| veraPDF, Tesseract | embarqué ; 5.x | réels |
| Annuaire | UnboundID embarqué (dev) ; `userAccountControl` modifié en LDAP pendant la recette (D15) | simulé |
| PostgreSQL | instance partagée (bases `ged_qa*` seulement, **lecture** hors application) ; **instance PostgreSQL 16 privée et jetable** (127.0.0.1:55494, `/tmp/qa-pg-prive`) pour les recettes qui créent, migrent, défont ou détruisent des bases (T-092, T-073/P-13, ANO-E8-004) | réel |
| systemd | `systemd-analyze verify` 255, racine jetable installée par `git archive` | réel (pas de démarrage de service) |
| cryptsetup, générateurs systemd, pgaudit | `cryptsetup` (image fichier LUKS2), `systemd-cryptsetup-generator` / `systemd-fstab-generator`, `postgresql-16-pgaudit` (instance jetable, socket seule) | réels |
| Front | Node 24.21.0 de l'équipe ; `ng build` **vert**, `ng test` **84/84** | réel |
| CI | GitHub Actions, dépôt `abddou2000/GED`, exécution n° 6 sur `08c710c` (lecture seule par l'API) | réel |

Une instance applicative (A, intégrité toutes les 5 min), arrêtée avant `mvn test` ; une tentative
de démarrage refusée (D15, cache à 6 min) ; instance PostgreSQL jetable arrêtée et supprimée en fin de vague.

## 2. Revérification des anomalies « Corrigée »

| Anomalie | Correctif | Recette rejouée | Constat | Verdict |
|---|---|---|---|---|
| ANO-E7-004 (P-21, §12.7) | 88625f3 (dev3) | `e7/RecetteComplements` P21-01/02 ; **`e10/RecetteTour2`** E7004-R2-01 à 03 | Liste, `POST /recherches` et `sortBy=dateDocument` : [b, c, a] (date du document décroissante) ; `tri=DATE_DOCUMENT` du contrat accepté (200) ; les trois routes donnent le même ordre ; un dépôt sans date reçoit la date du dépôt (colonne `date_document` NOT NULL : aucun cas « sans date ») | **Vérifiée** |
| ANO-E7-005 (D10, §12.6) | 1a71d2e (dev1) | R03-03 ; `RecetteTour2` E7005-R2-01 à 05 | Sous-dossier sous un dossier archivé refusé 409 `DOSSIER_ARCHIVE` par un membre **et** par l'Administrateur ; déplacement d'un dossier actif (`PATCH …/parent`, fiche `PUT`) : 409 ; déplacement et rattachement d'un document : 409 ; dossier enfant mis à la corbeille avant l'archivage puis restauré : revient ARCHIVE, dépôt 409. **Mais** un **document** mis à la corbeille avant l'archivage puis restauré revient ACTIF dans le dossier archivé et accepte versement (202) et modification (200) | **Vérifiée** pour le scénario et les dossiers ; nouveau contournement → **ANO-E7-006** |
| ANO-E8-004 (§4.2.2, §10.4) | 229401d (dev1) | **`e10-sauvegarde/recette-ano-e8-004.sh`** sur une copie peuplée de `ged_qa` (pg_dump en lecture → instance jetable) | Avant : 108 changesets, 1 diffusion, 46 circuits dont 2 annulés, 12 décisions, 1 règle de type, 1 validateur par rôle, 17 nœuds sans règle. `rollbackCount 12` : 8 changesets sans perte défaits (les 5 du tour 1, 031000-2, 031000-1 — 0 marque d'échéance —, 1200), puis **refus en 202610021130** : « il perdrait 1 habilitation(s) du rôle (diffusions…) … Rien n'a été supprimé », code 1 ; décomptes identiques après. Remontée : 108 changesets, **63 tables identiques** (md5 ; seul le compteur d'invalidation `version_habilitations` avance, effet voulu). Contrôle préalable SQL de `DEPLOIEMENT.md` §8 exécutable tel quel (0, 1, 2, 3, 1, 1, 17) | **Vérifiée** |
| ANO-E10-003 (T-073, T-092, T-093) | b778186 (dev2) | **`e10/verifier-executables.sh`** 28/28 ; `recette-t073-p13.sh` | 16 scripts en 100755, `commun.sh` / `commun-sauvegarde.sh` en 100644 mais seulement sourcés ; racine installée par `git archive` : `systemd-analyze verify` des trois unités sans « is not executable » ; 9 appels directs (sauvegarde, WAL, restauration, rapprochement, déploiement, fumée) sans « Permission denied » ; la recette T-073 tourne **sans `chmod` local** | **Vérifiée** |
| ANO-E10-004 (T-092) | b778186 (dev2) | `test-fumee.sh` livré contre l'instance **réelle** ; `recette-t092.sh` (adaptée) T092-05, T092-16 | Connexion, dépôt (avec `Idempotency-Key`), recherche, corbeille : « Test de fumée réussi », code 0, document en corbeille (`supprime = t`). Le corps produit par le filtre jq livré (`{identifiant, motDePasse}`) est accepté par `DemandeConnexion` ; `deployer.sh --verifier` code 0 contre le contrat réel | **Vérifiée** |
| ANO-E10-005 (T-092) | b778186 (dev2) | `recette-t092.sh` D3, D4, D4bis | D3 : déploiement v2, fumée en échec **après** la connexion (recherche mise en échec par la GED simulée), retour automatique vers v1, base laissée migrée ; D4 : `--retour-arriere --base` défait le changeset de v2 « changesets de …/versions/…/ged.jar » (le JAR qui a migré), v1 reste active, point de retour consommé ; D4bis : un second `--base` refuse « aucun point de retour Liquibase enregistré ». Témoin D5 inchangé | **Vérifiée** |

## 3. Lignes « Livré » et réserves levées au tour 1

| Ligne | Recette | Résultat | Verdict proposé |
|---|---|---|---|
| T-055 / D15 (§5.5) | `e9/RecetteApi` **25/25** ; **`RecetteTour2`** D15-R2-01/02 ; démarrage à 6 min | Cas 22 de la vague 4 rejoué : compte désactivé (`otazi`, 514) → **422 `IDENTITE_DELEGUEE_INVALIDE`**, motif au journal « compte désactivé dans l'annuaire (otazi) » (`CLE_API_REFUSEE`), rien pour l'application. Compte **désactivé après coup** (`yalaoui` 512 → 514 en LDAP) : encore accepté aussitôt (cache), **refusé après 120 s** ; réactivé : de nouveau accepté après 120 s. Cache à 6 min : démarrage refusé « PT6M hors de [0, 5 min] (décision D15…) ». Annuaire simulé (UnboundID) | **Vérifié** (réserve UAT : droit de lecture de `userAccountControl` du compte de service sur l'AD de MMED, R28) |
| T-025 (§12.1) | montée réelle de `ged_qa` (103 → 108) ; `recette-ano-e8-004.sh` (retour arrière puis remontée) | Colonnes `droit_*` absentes de `groupe_ged`, `name` → `nom` sur `groupe_ged`, `noeud`, `regle_workflow` ; table `reprise_droits_groupe` créée (vide : aucun droit vrai dans la base de qa) ; retour arrière de 202610041010/1020 puis remontée sur base peuplée **sans aucune différence** de contenu. Écart 2 (`groupe_membre.employe_id`) conservé et justifié par dev1 ; autres colonnes anglaises (`document.name`, `file_name`…) listées par dev1 comme hors P2 | **Vérifié** pour les écarts 1 et 3 ; l'écart 2 attend la validation de MMED (ligne « Proche » tant qu'elle manque) |
| T-059 (§6.1.4, E5-A08) | **`RecetteTour2`** T059-R2-01 à 03 | Vérification d'un document à la demande : 200 conforme ; tiers, déposant : 403 ; inconnu : 404. Octet du chiffré modifié dans le coffre : `conforme:false`, statut `ALTERE`, audit `INTEGRITE_ANOMALIE` (objet FICHIER) + `INTEGRITE_VERIFIEE` en ÉCHEC (« {ALTERE=1} ») ; téléchargement pendant l'altération 500 `INTEGRITE_COMPROMISE` ; fichier restauré : conforme. Fonds entier : 202, seconde demande 409, état consultable (`{CONFORME=81}` en < 1 s), lancement audité. Tâche périodique exercée en vague 8 | **Vérifié** ; reste ANO-E5-005 (ni métrique ni alerte sur divergence) |
| T-060 / T-064 (§6.1.4, §6.1.6) | `e7/RecetteCycleDeVie` **23/23** | Archivage d'un Word : copie PDF/A-2B méthode `LIBREOFFICE`, **VALIDE par veraPDF** ; aperçu DOCX converti par **LibreOffice 24.2.7.2 réel** puis servi du cache (les libellés « LibreOffice simulé » du script datent de la vague 3) | **Réserve UAT levée sur ce poste** ; reste le LibreOffice du serveur de MMED |
| T-035 (R31, D6) | base réelle ; suite `mvn test` | Changeset 202610041310 appliqué sur la base peuplée : 107 jobs existants en priorité 0 (flux), index partiel `idx_ocr_job_priorite_depose_le_attente` ; tous les dépôts de la vague en priorité 0. Le scénario « flux pendant une reprise » n'est **pas rejouable en réel** ici (reprise des versions en clair = données MySQL de MMED) : couvert par `OcrJobQueuePostgresTest.fluxCourantAvantReprise` et `RepriseVersionsEnClairTest` (§6) | **Vérifié partiellement** : à rejouer lors de la reprise à blanc de la Phase 7 |
| P-14 (R32) | relecture d'`ESSAIS-DE-CHARGE.md` §5.5 ; `SearchIndexerPostgresTest.plafond`, `ContratApiTest.criteresIndexEnSql` (§6) | Remesure de dev3 à 50 000 documents cohérente (terme très fréquent 0,4 s, 8 utilisateurs p95 2,1 s, multicritère 0,04 s) ; non rejouée à ce volume par qa | **Vérifié sur le papier** ; l'écart de débit OCR (R30) attend MMED |
| T-070 (A06) | `outils/essai-dependency-check.sh` | Miroir NVD synthétique : 10 contrôles verts (échec à CVSS ≥ 7, 5,3 rapporté sans bloquer, suppression datée). CI n° 6 : l'étape « Contrôler la chaîne d'analyse » passe, « Analyser les dépendances » échoue (secret `NVD_API_KEY` absent) | **Reste « Livré »** : l'analyse réelle attend la clé NVD |
| T-087 (§9.2) | CI GitHub n° 6 (`08c710c`), `mvn test`, `ng test` | Jobs back-end (tests PostgreSQL, JAR, SBOM), front-end (tests, paquet, SBOM, audit) et registre des licences **verts** ; seul le job OWASP échoue (T-070) ; suite locale au §6 ; front 84/84 | **Vérifié** (le job OWASP relève de T-070) |
| T-089 (§9.4) | `docs/exploitation/FORGE.md` ; API GitHub (lecture) | État relevé conforme au document : dépôt personnel public, **aucune branche protégée** (`main`, `conformite-technique`, `claude/…` : `protected: false`) | **Reste « Livré (procédure) »** : réglages à appliquer par le propriétaire (Q19) |
| T-092 (§10.1) | `recette-t092.sh` **12/12** ; fumée réelle | Voir ANO-E10-004 et ANO-E10-005 ; garde-fous, `validate` avant `update`, migration par `ged_owner`, retour automatique et manuel | **Vérifié** (systemd simulé : exécution réelle en UAT) |
| T-093 (§10.2) | `verifier-executables.sh` | `ged-backend.service`, `ged-sauvegarde.service` et `.timer` acceptés par `systemd-analyze verify` avec les scripts tels que le dépôt les livre | **Vérifié** sur ce poste (démarrage réel sous systemd : UAT) |
| T-073 (§6.5) | `recette-t073-p13.sh` 33 OK / 3 ÉCHEC / 4 AVERT (sans `chmod` local) | Sauvegarde et restauration complètes comme en vague 8 ; ANO-E10-003 levée ; **ANO-E10-006** (droits de niveau base perdus, T073-39) toujours ouverte | Non conforme (ANO-E10-006, correction du tour 2 attendue) |
| P-13 (§6.5) | même script | P13-04 (aperçu signalé à ré-importer), P13-10 (purge sans recontrôle), P13-11/12 : **ANO-E10-007** toujours ouverte | Non conforme |
| T-088 (§9.3) | `ng test` (84/84, dont `modules.service.spec.ts`, `app.routes.spec.ts`, `shell.spec.ts`) ; `recette-t092.sh` | Critère 2 (menus masqués) : tests verts. Critère 1 : l'enchaînement de `deployer.sh` est éprouvé par T-092 ; `deploiement/uat/demontrer-deploiement.sh` **non rejoué** : il crée et supprime une base sur `localhost:5432` en superutilisateur (hôte et port écrits en dur), ce que qa ne fait pas sur l'instance partagée (O3). **ANO-E10-008** toujours ouverte | Non conforme (ANO-E10-008) |
| T-075, P-02, T-085, P-19 | — | Aucune livraison depuis la vague 8 : ANO-E10-002, ANO-E2-002, ANO-E0-002, ANO-E0-003 toujours ouvertes (corrections du tour 2 en cours) | inchangé |
| P-05 (`SEQUENCES.md`) | relecture contre le code ; `SequencesDocumenteesTest` (§6) | Les cinq flux correspondent au code relu (dépôt en deux temps, OCR, recherche filtrée, délégation, archivage). Réserve de forme : la note du flux 4 dit encore D15 « à livrer par dev1 (T-055) » alors que la lecture de `userAccountControl` est livrée, et le 503 en cas d'annuaire injoignable n'est pas représenté | **Relu** : candidat à « Identique » après correction de la note |
| P-10 (LUKS) | `deploiement/luks/essai-entete-luks.sh`, `essai-crypttab.sh` (réels) | Image LUKS2 (argon2id, aes-xts 512) : sauvegarde de l'en-tête, en-tête détruit → plus reconnu, restauré → phrase acceptée ; générateurs systemd réels : variante TPM (`tpm2-device=auto`, `cryptsetup.target`, montage `local-fs`) et variante Tang (`_netdev`, `remote-cryptsetup.target`, montage `remote-fs`) | Réserves de la vague 5 **levées** ; ouverture TPM/Tang réelle : UAT |
| P-11 (menaces) | relecture ; **`e10/verifier-renvois-tests.sh`** | §3 bis (compromission de la KEK, rotation immédiate), §10 bis (port de management), §0 (clamd local), §12 (échec fermé) présents ; **37 renvois vers des tests, tous existants** ; `RotationImmediateKekTest` au §6 | Réserves de la vague 5 **levées** |
| P-16 (pgaudit) | `deploiement/postgresql/essai-pgaudit.sh` (pgaudit réel, instance jetable) | **16 contrôles verts** (DBA nominatif et `SET ROLE` tracés, `ged_sauvegarde` tracé, `ged_app` non tracé, paramètres jamais écrits, limite superutilisateur visible par `log_statement`) | Réserves de la vague 5 **levées** ; activation sur le serveur de MMED : UAT |
| P-17 (garantie) | relecture de `GARANTIE.md` | Point de départ des 24 h, RPO base 15 min / fichiers 24 h, §4 bis fournisseurs tiers présents ; les retours arrière avec perte sont gardés (ANO-E8-004 vérifiée) | Réserves de la vague 5 **levées** |

## 4. Non-régression (recettes existantes, instance A)

- E3 autorisation **27/28** : E3-05 (total de la liste du tiers 40 au lieu de 38) vient du jeu de
  données : les deux documents que le tiers a déposés dans l'espace d'échange de R03
  (`qamch-…-CPS`, `qamch-…-archive`, habilitation de groupe) juste avant. Ce n'est pas une
  régression : la vague 8 donnait 28/28 sur un jeu sans R03.
- E7 cycle de vie **23/23** ; compléments E7 7/8 : T101-02 (annulation entre deux tranches) exige
  une instance lancée avec des tranches de 2, ce qui n'était pas le cas ici (réglage de l'environnement).
- E8 workflow **30/30** ; E9 API **25/25** ; fumée livrée 4/4.

## 5. Anomalie nouvelle

| Id | Membre | Gravité | Résumé |
|---|---|---|---|
| ANO-E7-006 | dev1 | Mineure | Document mis à la corbeille avant l'archivage de son dossier, puis restauré : il revient ACTIF sous le dossier archivé et accepte versement (202) et modification (200) |

Observations (sans anomalie) :
- **O1** : après un démarrage refusé (D15, cache à 6 min), la JVM ne s'arrête pas : « Application
  run failed » à 14:17:02, processus toujours vivant 2 minutes plus tard. C'est probablement
  l'annuaire embarqué du profil dev. À vérifier sous le profil prod : sous systemd, un service
  bloqué dans cet état ne serait pas redémarré.
- **O2** : les scripts de recette de la vague 8 ont dû suivre le tour 1 : colonne `noeud.nom`
  (T-025) dans `recette-t073-p13.sh` ; contrat de fumée corrigé dans `recette-t092.sh` (GED simulée :
  nouvel état `recherche`).
- **O3** : `deploiement/uat/demontrer-deploiement.sh` fixe `DB_HOST=localhost`, `DB_PORT=5432` et
  crée ou supprime sa base en superutilisateur : il n'est pas rejouable contre une instance jetable.
  Le paramétrer (PGHOST/PGPORT) permettrait de le rejouer sans toucher à l'instance partagée.

## 6. Suite automatisée (`mvn test`, `ged_qa_test`)

`mvn -B -q -o test` sur `ged_qa_test`, instance arrêtée, pool Hikari de 3 : **630 tests, 0 échec,
0 erreur, 0 ignoré** (104 classes, 3 min). C'est l'état de référence après le tour 1 (630 verts) :
aucune régression. Tests cités par la recette, tous verts : `OcrJobQueuePostgresTest`
(`fluxCourantAvantReprise`, T-035), `RepriseVersionsEnClairTest`, `IntegriteALaDemandeApiTest` (T-059),
`EtatCompteAnnuaireTest` (D15), `RotationImmediateKekTest` (P-11), `SequencesDocumenteesTest` (P-05),
`ScriptsExploitationTest` (ANO-E10-003/004), `SchemaLiquibaseTest` (T-025, ANO-E8-004).
Front : `ng build` vert (un avertissement de budget CSS sur une police), `ng test` **84/84**.

## 7. Ce qui est éprouvé en réel sur ce poste, et ce qui reste propre à MMED

Réel ici : LibreOffice 24.2.7.2 (aperçu, PDF/A validé par veraPDF), clamd 1.5.4, Tesseract,
PostgreSQL 16 (montée et retours arrière Liquibase sur base peuplée, restauration), `deployer.sh` /
`test-fumee.sh` contre la vraie application et contre PostgreSQL, `systemd-analyze verify`,
générateurs systemd de `crypttab`, cryptsetup LUKS2 sur image, pgaudit, Dependency-Check sur miroir,
CI GitHub.
Reste à MMED / UAT : AD réel (lecture de `userAccountControl` par le compte de service, bascule entre
contrôleurs), signatures ClamAV officielles, démarrage sous systemd réel et NGINX avec le certificat
de MMED, ouverture LUKS par TPM ou Tang, pgaudit sur le serveur de base, clé NVD, protection de
branche (Q19), reprise depuis MySQL (priorité OCR du flux pendant la reprise, R31).
