# Résultats de recette — vague 1 (E1 intégré, E5 en composants)

Exécutés par qa le 2026-09-26 sur `ct/qa` après `git merge conformite-technique` (fbb951c :
fusions E1 `409970b` et E5 `65eb0ae`). Bases préparées par les scripts de dev1
(`creer-roles.sql`, puis `preparer-base.sql -v tests=oui` sur `ged_qa` et `ged_qa_test`).
Aucun code applicatif modifié par qa.

## 1. Suite automatisée (non-régression, §9.2)

| Mesure | Ligne de base (H2) | Après intégration (PostgreSQL `ged_qa_test`) |
|---|---|---|
| Tests | 143 | **257** (0 échec, 0 erreur, 0 ignoré) |
| Classes | 21 | 39 (les 21 d'origine, chacune avec le même nombre de tests, + 18 nouvelles) |
| Durée | 1 min 02 s | 1 min 30 s |
| Base | H2 mémoire | PostgreSQL 16.14, schéma `ged` (URL relevée dans le journal : `jdbc:postgresql://localhost:5432/ged_qa_test?currentSchema=ged`) |

Nouvelles classes : `socle.SchemaLiquibaseTest` (2), `socle.SocleDonneesTest` (7),
`socle.RepriseDonneesTest` (1), `common.UuidV7Test` (3), et 14 classes `fichier.*` (100 tests).
**Aucune régression.**

## 2. Recette E1 — scripts `recette/e1/` sur le code intégré

| Script | Base | Résultat |
|---|---|---|
| `verifier-base-vierge.sh --demarrer-application` | `ged_qa_recette_e1` (supprimée puis recréée) | Base vide → 21 changesets appliqués par `ged_owner` en 9 s ; application démarrée en `ged_app` avec `ddl-auto: validate` ; **DDL identique avant et après démarrage** (V01–V05 OK) |
| `verifier-rollback.sh` | même base | Retour arrière des 21 changesets, schéma `ged` vidé (tables, séquences, vues, types, fonctions), registre vidé, remontée au **DDL identique** (R01–R04 OK) |
| `verifier-socle.sh --base-vierge` | même base | 55 contrôles : 44 OK, **4 ECHEC** (C04, C15, C16, C22), 2 AVERT (C13, C25), NA pour l'audit (E4) |
| `verifier-socle.sh` | `ged_qa_test` après `mvn test` | Mêmes constats (44 OK, 4 ECHEC) |
| `AnalyseurChangelogs.java` | sources intégrées | 17 OK, 1 AVERT (A23 : seeders de démonstration limités au profil `dev`, et `CompteSeeder` jusqu'à E2) |
| Sondes de privilèges P01–P13 | `ged_qa_recette_e1` | 13/13 : `ged_app` ne peut ni CREATE, ni ALTER, ni DROP ; `ged_readonly` ne peut ni écrire ni créer |
| `fumee.sh` avec `GED_EXIGER_UUID=1` | application intégrée sur `ged_qa`, port 18084 | 7/7 : dépôt → id UUID v7 `01a0dfe4-…`, recherche, téléchargement identique à l'octet, suppression douce |
| Contrôle en base après la fumée | `ged_qa` | Document supprimé : `supprime_par` = compte appelant, `supprime_le` renseigné (`timestamptz`) ; dates d'API en ISO 8601 `…Z` |

## 3. Recette E5 — composants (le dépôt HTTP n'est pas encore branché)

`recette/e5/verifier-composants.sh` : exerce les classes de production de dev3
(`FileStoreDisque`, `StockageChiffre`, `KeystoreKeyProvider`, `RotationKek`,
`DetecteurTypeReel`, `ClientClamd`, `ControleFichiers`) sur le jeu `recette/donnees`, puis fait
contrôler le stockage produit par `verifier-aucun-clair.sh` (script bash indépendant).
**19/19 OK.**

| Id | Contrôle | Résultat |
|---|---|---|
| K01, K02 | Dépôt chiffré des 9 fichiers autorisés ; empreinte SHA-256 du clair = `MANIFESTE.csv` | 9/9, 9 empreintes identiques |
| K03 | Relecture déchiffrée identique | 10 fichiers, dont un de 3 Mio (4 segments) |
| K04 | `verifier-aucun-clair.sh` sur le stockage produit | S01–S08 OK : `aa/bb/<uuid>.enc`, en-tête `GEDC` v1, aucune signature ni chaîne en clair, incompressible |
| K05 | Aucun keystore sous la racine | 0 |
| K06–K08 | Octet inversé, troncature, substitution d'un autre chiffré | Lecture refusée **500 `INTEGRITE_COMPROMISE` avant le premier octet** |
| K09 | Grand fichier altéré dans son dernier segment | Refus après 3 145 728 octets **vérifiés** (3 segments authentifiés livrés, le segment altéré jamais) — voir §5 |
| K10 | Après restauration | Relecture identique |
| K11, K12 | Texte, exécutable, DOCX, PNG sous `.pdf` ; vrai PDF sous `.txt` | 4 × 415 `FORMAT_NON_AUTORISE`, stockage inchangé ; vrai PDF accepté |
| K13, K14 | Limite du type (1 Mo ± 1 octet) ; type à 500 Mo | 413 `FICHIER_TROP_VOLUMINEUX` / accepté ; limite ramenée à 200 Mo, 200 Mio + 1 → 413 |
| K15, K16 | EICAR | 422 `FICHIER_INFECTE`, rien d'écrit, événement `FichierInfecte` publié — **simulateur** (FauxClamd) |
| K17 | clamd arrêté | Fichier sain refusé 503 `ANTIVIRUS_INDISPONIBLE`, rien d'écrit — **simulateur** |
| K18 | Rotation de la KEK | 11 DEK réenveloppées, 0 restante sous l'ancienne KEK, fichiers `.enc` inchangés à l'octet, documents lisibles |
| K19 | Destruction | Fichier et DEK supprimés, lecture → 404 `FICHIER_INTROUVABLE` |

Simulateurs : dépôt des DEK en mémoire (la table `cle_fichier` est livrée dans
`db/changelog/a-integrer/`, pas encore dans le changelog maître) ; clamd factice des tests de
dev3 ; keystore PKCS#12 de test.

## 4. Lignes de la matrice

| Réf. | Exigence | Verdict qa | Preuve / réserve |
|---|---|---|---|
| 2.2 | PostgreSQL 16+, configuration arabe vérifiée | **Vérifié** | C24–C26, `to_tsvector('arabic', …)` (SocleDonneesTest) ; `unaccent`/`pg_trgm` à créer pour E6 (C25, avertissement) |
| 2.2 | Liquibase 4 | **Vérifié** | A21, C19, V03 |
| 4.2.1 | Aucune modification de schéma hors migration | **Vérifié** | V01–V05 (DDL identique après démarrage), A20, A22 |
| 4.2.1 | Amorçage par migration, référentiels via l'interface | Non vérifié — reste « Proche » | C22 : aucun changeset `data-initial` ; `CompteSeeder` (tous profils). Conforme à ce que dev1 annonce (suite en E2/E3) ; statut du SUIVI à corriger : **ANO-E1-003** |
| 4.2.2 | Conventions de nommage | **Non conforme** | **ANO-E1-001** : 4 clés primaires composites |
| 4.2.2 | Retour arrière explicite, expand/contract | **Vérifié** (poste) | R01–R04, A05 ; exécution en UAT en attente de l'environnement (E0) |
| 4.2.3 | Trois rôles PostgreSQL | **Vérifié** | C30–C41, sondes P01–P13 ; tables d'audit à revérifier en E4 (C36, P20–P23) |
| 12.1 | Clés primaires UUID | **Vérifié** | C05, C06, A11 ; fumée avec `GED_EXIGER_UUID=1` |
| 12.1 | Sept groupes de tables | Non vérifiable en vague 1 | Clôture en E9 |
| 5.3.2 | JSON UTF-8, ISO 8601 UTC, UUID | **Vérifié** | Identifiants UUID, dates `…Z`, `application/json` (UTF-8 par défaut, RFC 8259) |
| 12.5 | Suppression douce avec auteur et date | **Non conforme** (nommage) | Auteur et date corrects en base ; **ANO-E1-002** : indicateur `deleted` au lieu de `supprime` |
| 12.7 | Métadonnées JSONB avec index GIN | **Vérifié** (structure) | C17, C18 ; alimentation en E7 |
| 6.1.1 | Identifiant opaque, `aa/bb`, écriture atomique | Vérifié **en composants** | K01, K04 ; bout en bout HTTP et test d'arrêt brutal en vague 2 |
| 6.1.2 | AES-256-GCM, clé par version, keystore, rotation | Vérifié **en composants** | K03–K10, K18, K19 (keystore de test) ; **ANO-E5-001** sur l'emplacement du keystore de développement |
| 6.1.4 | Empreinte SHA-256, vérification périodique | Partiel | K02 ; vérification à la demande/mensuelle : tests de dev3 (`VerificationIntegriteTest`), à rejouer par l'API en vague 2 |
| 6.1.5 | Type réel (Tika) | Vérifié **en composants** | K11, K12 |
| 6.1.5 | ClamAV, refus si indisponible | Vérifié **par simulateur** | K15–K17 ; ClamAV réel en UAT |
| 6.1.6 | Prévisualisation | Non vérifié par qa | LibreOffice absent du poste ; parcours HTTP en vague 2 |
| 2.3.2 | Briques Tika, ClamAV, LibreOffice, veraPDF, keystore, Prometheus | Partiel | Tika, keystore, client ClamAV vérifiés ; LibreOffice, veraPDF (E7), Prometheus (E10) non |

## 5. Observations sans anomalie

- **K09** : le déchiffrement en flux livre les segments déjà authentifiés avant de refuser le
  segment altéré (conception de dev3, §6.1.2 respecté : aucun octet non vérifié n'est livré).
  Conséquence pour la vague 2 : un téléchargement HTTP d'un grand fichier altéré commencera en
  200 puis sera interrompu ; le client ne doit jamais recevoir un transfert présenté comme complet.
  C'est exactement ce que contrôle `verifier-alteration.sh` (A06).
- C13 : 8 clés étrangères `supprime_par` sans index (avertissement, sans exigence du DAT).
- C25 : extensions `unaccent` et `pg_trgm` à créer avant E6 (`preparer-base.sql` ne les crée pas).
- La table `cle_fichier` et la colonne d'empreinte de `version_document` sont livrées dans
  `db/changelog/a-integrer/` : attendu, le branchement est prévu en vague 2.
