# Suivi dev3 — fichiers, OCR, recherche, cycle de vie

Branche `ct/dev3`, copie `C:\Users\abdou\ged-wt\dev3`.

**Tests : `DB_NAME=ged_dev3 mvn test`** (sinon le profil `test` vise `ged_dev1_test`, la base
de dev1). Bases `ged_dev3` et `ged_dev3_test` préparées par `scripts/db/creer-roles.sql` puis
`preparer-base.sql` (`-v tests=oui` pour la base de test).

Lots : **E5** (vague 1, fusionné dans `conformite-technique`) puis **E6** (vague 1 bis, ci-dessous),
après fusion du socle E1 de dev1 dans `ct/dev3`.

## Lot E5 — Stockage sécurisé des fichiers

Livré en **composants autonomes** (paquet `com.ipt.ged.fichier`), testés, **non encore
branchés** sur le flux de dépôt (vague 2). `StorageService` sert toujours le dépôt actuel.
Depuis l'intégration du socle, les changesets E5 sont dans le **changelog maître**.

### Exigences traitées

| Réf. matrice | Exigence | Livré | Statut honnête |
|---|---|---|---|
| 2.3.2 | Briques Tika, ClamAV, LibreOffice, keystore/KMS | Tika (`tika-core` 3.2.3), client clamd, conversion LibreOffice, keystore PKCS#12 derrière `KeyProvider` | Proche : veraPDF (E7) et Prometheus (dev2) restent |
| 6.1.1 | Identifiant opaque, arborescence aa/bb, écriture unique et atomique | `FileStore` / `FileStoreDisque` | Identique au branchement |
| 6.1.2 | AES-256-GCM, DEK par version, KEK en keystore, rotation | `StockageChiffre`, format `.enc` v1, `KeystoreKeyProvider`, `RotationKek`, table `cle_fichier` | Identique au branchement + reprise |
| 6.1.4 | Empreinte SHA-256 par version, vérification périodique | empreinte en flux à l'écriture, `VerificationIntegrite`, `VerificationPeriodique` (@Scheduled mensuelle, désactivable) | Identique au branchement (colonne `version_document.empreinte`) |
| 6.1.5 | Type réel par le contenu (Tika) → 415 | `DetecteurTypeReel`, `FormatsReconnus`, liste blanche par type | Identique au branchement |
| 6.1.5 | Taille par type, plafond de plateforme → 413 | 100 Mo par défaut, plafond 200 Mo (multipart 200 Mo / requête 210 Mo), contrôle aussi pendant le flux | Identique (NGINX à aligner par dev2) |
| 6.1.5 | ClamAV, refus si indisponible → 422 `FICHIER_INFECTE` | `ClientClamd` INSTREAM TCP, échec fermé (503 `ANTIVIRUS_INDISPONIBLE`), événement `FichierInfecte` | Identique au branchement (simulateur seulement) |
| 6.1.6 | Prévisualisation déchiffrée à la volée, droits, audit | `ServicePrevisualisation`, `PrevisualisationController`, cache chiffré, événement `ApercuConsulte`, point d'extension droits | Proche : droits réels = lot autorisation |
| 12.5 | Purge : destruction cryptographique | `StockageChiffre.detruire` (DEK d'abord), `ServicePrevisualisation.invaliderCache` | Mécanisme prêt ; l'opération de purge relève d'E7 |

Chaîne au dépôt (`ControleFichiers.deposer`) : **taille → type réel → antivirus → écriture
chiffrée + empreinte**. Tout refus intervient avant l'écriture : rien n'est publié, aucune clé
enregistrée (testé).

Codes d'erreur ajoutés au `GlobalExceptionHandler`, même format qu'avant plus un champ `code` :
413 `FICHIER_TROP_VOLUMINEUX` (y compris `MaxUploadSizeExceededException`), 415
`FORMAT_NON_AUTORISE` / `APERCU_NON_DISPONIBLE`, 422 `FICHIER_INFECTE`, 503
`ANTIVIRUS_INDISPONIBLE` / `CONVERSION_INDISPONIBLE`, 404 `FICHIER_INTROUVABLE`, 500
`INTEGRITE_COMPROMISE`.

### Format des fichiers chiffrés `.enc` (version 1) — AES-256-GCM segmenté

Décision validée (orchestrateur / pm) : pas un GCM unique sur tout le fichier (le JCE garde tout
le chiffré en mémoire au déchiffrement), mais un **streaming AEAD** (construction STREAM,
Hoang–Reyhanitabar–Rogaway–Vizár, celle de Tink).

```
en-tête (21 o) : "GEDC" | version=1 (1 o) | taille de segment (4 o, BE) | IV de base (12 o, aléatoire par fichier)
segments       : chiffré AES-256-GCM du segment (≤ taille de segment, 1 Mio par défaut) | étiquette (16 o)
```

- **DEK** : AES-256 tirée pour chaque fichier (donc chaque version), jamais stockée en clair.
- **Nonce du segment i** : IV de base XOR (i sur les octets 4 à 10) XOR (indicateur « dernier » sur
  l'octet 11) — unique pour chaque couple (rang, dernier).
- **Données authentifiées** : en-tête ‖ identifiant du fichier (16 o) ‖ rang (8 o) ‖ dernier (1 o).
- Détecté : octet modifié, **troncature** (y compris à une frontière de segment), prolongement,
  permutation de segments, fichier recopié sous un autre identifiant, en-tête falsifié, mauvaise clé.
- Chaque segment est authentifié **avant** d'être rendu : aucun octet falsifié n'atteint le lecteur ;
  mémoire bornée à ~2 segments par flux (200 Mo servis sans être chargés).
- Taille en clair calculable sans déchiffrer (`Content-Length` de l'aperçu).

**Enveloppe de DEK** (colonne `cle_fichier.dek_enveloppee`, 60 o) : AES-256-GCM sous la KEK,
`IV (12) ‖ DEK chiffrée (32) ‖ étiquette (16)`, AAD = `"GED-DEK-v1" | alias de la KEK | identifiant du fichier`
(une enveloppe recopiée sur la ligne d'un autre fichier ne s'ouvre pas).

**KEK** : alias `kek-00001`, `kek-00002`… dans le keystore PKCS#12 ; la plus récente est active,
les autres désactivées (désenveloppement seul). Rotation = nouvelle KEK + réenveloppement des DEK
par lots de 500 (mise à jour conditionnelle, reprenable), **aucun fichier rechiffré** (testé :
octets `.enc` identiques avant/après). Retrait d'une KEK refusé tant qu'une DEK la référence.

### Modèle de données (changelog maître)

1. `202609271200_creation_table_cle_fichier.xml` — `cle_fichier` : `id uuid` (`pk_cle_fichier`)
   = identifiant du fichier `aa/bb/<id>.enc`, `dek_enveloppee bytea` (`ck_cle_fichier_dek_enveloppee`),
   `kek_identifiant varchar(64)` (`idx_cle_fichier_kek_identifiant` ; renommé depuis `kek_id` : la
   convention §4.2.2 réserve `*_id` aux clés étrangères UUID, or c'est un alias de keystore),
   `algorithme` défaut `AES-256-GCM` (`ck_cle_fichier_algorithme`), `cree_le`, `modifie_le`.
   Droits `ged_app` par les privilèges par défaut de `preparer-base.sql`.
2. `202609271205_ajout_fichier_chiffre_version_document.xml` — sur `version_document` (expand) :
   `cle_fichier_id uuid` (`fk_version_document_cle_fichier`, `uk_version_document_cle_fichier_id`),
   `empreinte char(64)` (`ck_version_document_empreinte`), `type_mime varchar(127)`,
   `taille_octets bigint` (`ck_version_document_taille_octets`). Nullables jusqu'à la fin de la
   reprise ; un changeset « contract » (NOT NULL, retrait de `file_path`) suivra.

Montée, retour arrière complet et remontée vérifiés par `SchemaLiquibaseTest` (schéma jetable).
Pas d'entité JPA pour `cle_fichier` (`DepotClesFichierJdbc`). Champs à ajouter à l'entité
`DocumentVersion` au branchement :

```java
@Column(name = "cle_fichier_id") private UUID cleFichierId;
@Column(name = "empreinte", length = 64) private String empreinte;
@Column(name = "type_mime", length = 127) private String typeMime;
@Column(name = "taille_octets") private Long tailleOctets;
```

### Branchement E5 (vague 2) — fait

1. Dépôt et nouvelle version : `ControleFichiers.deposer` (taille, type réel, antivirus, chiffrement)
   puis version renseignée (`cle_fichier_id`, `empreinte`, `type_mime`, `taille_octets`) ;
   compensation `StockageChiffre.detruire` si la transaction est annulée (838c162).
2. Téléchargement en flux (`StreamingResponseBody`, déchiffrement segment par segment,
   `no-store`, `nosniff`).
3. Aperçu d'indexation : mêmes contrôles taille + type réel que le dépôt, sans écriture.
4. Prévisualisation : `ResolveurFichierVersionJpa`, `ged.fichiers.previsualisation.api-active=true` ;
   bouton « Aperçu » sur la fiche document (repli téléchargement si 415/503).
5. Intégrité : `SourceEmpreintesVersions` (pages par clé sur `version_document`),
   vérification planifiée activée.
6. `StorageService` et `ged.storage.*` supprimés ; colonne `file_path` rendue facultative
   (changeset expand `202609281000`) ; le changeset **contract** (`202609281005` : retrait de
   `file_path`, `NOT NULL` sur clé/empreinte/taille) est rangé dans
   `db/changelog/version-suivante/`, **hors du changelog maître** : l'appliquer aurait cassé la
   reprise de données de dev1 (qui écrit encore `file_path`). À inclure à la version suivante,
   après la reprise en production (précondition : plus aucune version sans clé, sinon ignoré).
7. Restent hors lot : purge définitive (E7), contrôle d'accès définitif de l'aperçu (lot
   autorisation, dev1).

### Procédure de reprise des fichiers existants (en clair → chiffré)

Outil : `RepriseVersionsEnClair` (+ `LanceurReprise`, activé par `ged.fichiers.reprise.source`),
**piloté par la base** : chaque version sans `cle_fichier_id` désigne son fichier par `file_path`.

1. Arrêter l'accès utilisateurs ; sauvegarder la base et l'ancienne racine de stockage.
2. Créer le keystore de l'environnement (une fois, `creer-si-absent=true` puis retiré) et le
   **sauvegarder** avec sa phrase secrète (coffre de secrets).
3. Lancer :
   `java -jar ged.jar --spring.profiles.active=prod --spring.main.web-application-type=none --ged.fichiers.reprise.source=/srv/ged/storage/ged --ged.fichiers.reprise.rapport=/srv/ged/reprise-fichiers.csv`
   (option `--ged.fichiers.reprise.antivirus=true` pour analyser aussi le fonds historique).
   Pour chaque version : chemin confiné sous la racine, type réel, chiffrement sous une DEK
   neuve, empreinte, **relecture complète de contrôle**, puis dans une transaction mise à jour de
   la version (conditionnelle `cle_fichier_id IS NULL`) et, pour une version courante, envoi à
   l'OCR **en priorité REPRISE** (`ocr_job.priorite = 1`, R31) : le flux courant passe
   toujours devant, l'accès utilisateurs peut donc rouvrir dès la fin de cette étape sans
   attendre l'OCR de la reprise (plusieurs semaines sur 4 vCPU). Rapport CSV
   `version_id;chemin_relatif;fichier_id;empreinte_sha256;taille_octets;type_mime;statut`.
   Reprenable ; aucun original supprimé.
4. Contrôle : `SELECT count(*) FROM version_document WHERE cle_fichier_id IS NULL;` → 0.
5. Vérification d'intégrité complète (`POST /api/v1/admin/integrite/verification`,
   Administrateur, T-059 ; état par `GET` sur le même chemin), traiter les lignes en échec.
5 bis. En fin d'OCR de la reprise (file `REPRISE` vide :
   `SELECT count(*) FROM ocr_job WHERE priorite = 1 AND statut IN ('EN_ATTENTE_OCR','EN_COURS_OCR')` → 0),
   et après tout chargement de masse : `VACUUM ANALYZE document_texte;` par le compte
   propriétaire (R32, essais de charge §5.3 : sans statistiques, l'index GIN est ignoré).
   L'analyse automatique est abaissée à 2 % des lignes sur cette table (changeset
   `202610041320`) : ce passage manuel reste le filet de sécurité.
6. Seulement ensuite : effacer l'ancien stockage en clair (sur SSD, prévoir le chiffrement du
   volume), puis montée incluant le changeset « contract ».

Testé (`RepriseVersionsEnClairTest`, 4 tests) : empreintes égales au SHA-256 des originaux,
contenu relu à l'identique, originaux intacts, reprise idempotente, chemin hors racine refusé,
fichier manquant signalé sans interrompre, job OCR enfilé pour la version courante.

### Configuration (voir `application.yml`, `backend/.env.example`)

`ged.fichiers.*` : racines (`GED_STOCKAGE_RACINE`, `GED_CACHE_APERCU_RACINE`), keystore
(`GED_KEYSTORE_CHEMIN`, `GED_KEYSTORE_MDP` — **obligatoires hors dev/test : la prod refuse
désormais de démarrer sans eux**), clamd (`GED_CLAMAV_HOTE`, `GED_CLAMAV_PORT`), LibreOffice
(`GED_LIBREOFFICE`, `GED_APERCU_TRAVAIL`). Garde-fous au démarrage : antivirus non désactivable
en prod ; keystore interdit sous la racine des fichiers ; keystore absent en prod = refus (jamais
de keystore neuf silencieux). Dev : keystore jetable `./data/cles/`, antivirus neutralisé
(`GED_ANTIVIRUS_ACTIF`). Test : keystore sous `target/`.

À poser en exploitation (dev2 / MMED) :
- NGINX `client_max_body_size 210m;` (fichier 200 Mo + champs du formulaire).
- `clamd.conf` : `StreamMaxLength 200M` (sinon tout fichier > 25 Mo est refusé, échec fermé).
- **tmpfs** pour `spring.servlet.multipart.location` (Tomcat y écrit le fichier reçu, en clair,
  le temps de la requête) et pour `GED_APERCU_TRAVAIL` (conversion LibreOffice).
- Sauvegarde conjointe du référentiel chiffré, de `cle_fichier` et du keystore (§6.1.3).

### Vérifié uniquement par simulateur

- **ClamAV** : `FauxClamd` (serveur TCP de test, protocole INSTREAM/PING réel, reconnaît la
  chaîne EICAR, modes muet / coupure / limite dépassée / réponse invalide). Pas de clamd réel.
- **LibreOffice** : `FauxSoffice`, lancé comme un vrai processus avec le contrat de la ligne de
  commande `soffice --headless --convert-to pdf --outdir`. Pas de conversion réelle ; détection
  d'absence testée avec une commande inexistante.
- **KMS / HSM** et **S3** : interfaces seulement (`KeyProvider`, `FileStore`), pas d'implémentation.

### Tests E5

Paquet `com.ipt.ged.fichier` : 101 tests (FileStore, chiffrement segmenté et 10 cas d'altération,
flux de 64 Mo, keystore, rotation sur 1 203 DEK, destruction cryptographique, dépôt JDBC,
Tika sur les 12 formats et les leurres, faux clamd, chaîne de contrôle et ordre des refus,
intégrité, prévisualisation service + API, reprise, codes HTTP, garde-fous de configuration).

### Reste à faire E5

- veraPDF et copie de conservation PDF/A-2 (§6.1.4, E7).
- Sonde de santé ClamAV (`AnalyseurAntivirus.disponible()` exposée à dev2 par
  `VerificationAntivirus`).

---

## Lot E6 — OCR asynchrone et recherche plein texte

Composants autonomes (paquets `com.ipt.ged.ocr.moteur`, `com.ipt.ged.ocr.file`,
`com.ipt.ged.recherche`), tables dans le **changelog maître**, testés sur PostgreSQL 16 réel ;
**non branchés** sur le dépôt (workers, jauges et API derrière `ged.ocr.chaine.actif=false`).
Décisions client appliquées : **D5** (aucun Python : binaire Tesseract appelé depuis Java),
**D6** (objectif dépôt → recherche de **24 h**, au lieu de 5 min).

### Exigences traitées

| Réf. matrice | Exigence | Livré | Statut honnête |
|---|---|---|---|
| 4.3.3 | Cloisonnement : l'OCR n'alimente aucun champ d'index | Correctif prêt : `docs/conformite/correctifs/E6-retrait-preremplissage-ocr.patch` (back + tests + front ; vérifié : tests verts, `ng build` OK), **non appliqué** | Non → Identique à l'application du correctif |
| 4.3.4 | Asynchrone : `ocr_job`, worker SKIP LOCKED, HTTP 202 | `OcrJobQueue` / `OcrJobQueuePostgres` (réservation atomique `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)`, bail prolongé à chaque page), `TravailleurOcr`, `PoolTravailleursOcr` | Proche : HTTP 202 et enfilage au dépôt = branchement |
| 4.3.4 | Langues fra et ara, défaut fra+ara, par type | `LanguesOcr` (`ged.ocr.chaine.langue-defaut`, `langues-par-type` par code de type), `ara.traineddata` officiel (tessdata_best, comme `fra`) dans `backend/tessdata` | Identique (réglage par configuration ; colonne `type_document.langue_ocr` possible plus tard) |
| 4.3.4 | Aucun plafond de pages, texte agrégé | `ExtracteurDocumentOcr` page par page : couche texte PDFBox au-delà de 25 caractères utiles par page, sinon rendu 300 dpi + OCR ; TIFF multi-pages ; unité documentaire unique | Identique |
| 4.3.4 | Aucune copie en clair sur disque persistant | `MoteurTesseract` : image sur l'entrée standard, texte sur la sortie standard (`tesseract stdin stdout`) ; PDFBox en cache mémoire seul ; fichier déchiffré en mémoire (`StockageChiffre.lire`). L'aperçu synchrone (`ExtracteurTesseract`) n'écrit plus de PNG dans `ged.storage.temp` | Identique |
| 4.3.4 | 60 s par page, 3 tentatives (1, 5, 30 min), `OCR_ECHEC`, « non interrogeable » | délai par page (processus tué), `PolitiqueReprise`, motifs typés (`EchecOcrException`, définitifs → échec immédiat), relance manuelle, `statutsParVersion` / `versionsIndexees` | Identique (affichage « non interrogeable » = branchement) |
| 4.3.4 | Texte dans `document_texte`, délai mesuré | indexation et clôture du job dans une seule transaction ; métrique `ocr_delai_disponibilite` | Identique |
| 4.4 | tsvector + GIN, french et arabic | `document_texte.tsv` = `ged_document_tsvector(texte)`, index GIN `idx_document_texte_tsv` | Identique |
| 4.4 | websearch_to_tsquery, ts_headline, ts_rank_cd, dédoublonnage | `SearchIndexerPostgres` ; une ligne par document ; prédicat de droits en `EXISTS` fourni par `PredicatDroits` | Identique (droits réels = lot autorisation) |
| 4.4.1 | Réindexation incrémentale et complète | incrémentale à chaque job ; `ReindexationComplete` (tâche de fond, lots de 500, pause, progression) | Identique |
| 5.3.1 | Recherche filtrée par droits, paginée | `RechercheController` `GET /api/v1/recherche/plein-texte` (inactif), total sur le périmètre autorisé | Proche : critères de métadonnées et droits réels à brancher |
| 6.7 | Métrique `ocr_delai_disponibilite` | `MetriquesOcr` | Proche : exposition Prometheus = dev2 |
| 4.3.2 | Protocole comparatif 300 pages (CER, WER, débit) | — | Non traité dans cette vague |

### Tesseract par entrée et sortie standard

`tesseract stdin stdout --tessdata-dir backend/tessdata -l <langue> --oem 1 --psm 3` : image PNG
écrite par un fil, sortie et erreurs lues par deux autres (pool dédié, pas le pool commun) ;
`OMP_THREAD_LIMIT=1` (le parallélisme est porté par le nombre de workers) ; au-delà de 60 s,
processus et descendants tués → `DELAI_DEPASSE` (transitoire). Langue validée contre les modèles
présents avant tout lancement (pas d'injection d'argument).

**Arabe vérifié** (`MoteurTesseractTest`, Tesseract 5.4 du poste) : scan généré « عقد الإيجار
السنوي للشركة » reconnu à l'identique avec `ara` ; page bilingue reconnue avec `fra+ara` ; PDF
scanné de 3 pages (2 scans fr/ar + 1 page native) agrégé dans l'ordre. Constat : avec certaines
polices (Arial rendue par Java) la reconnaissance arabe s'effondre ; avec Tahoma, Simplified
Arabic ou Traditional Arabic elle est exacte. Le protocole 4.3.2 sur le corpus réel reste
indispensable.

### Recherche : normalisation retenue

- `ged_normaliser_arabe` : suppression des diacritiques (U+064B à U+065F, U+0670) et du tatweel,
  formes de l'alef (أ إ آ ٱ) → ا. `unaccent` ne touche pas l'arabe (vérifié).
- Chaque configuration ne reçoit **que les mots de son écriture** (`ged_partie_latine`,
  `ged_partie_arabe`) : sans cela `to_tsvector('arabic', …)` indexait les mots français bruts,
  mots vides compris, et « de » ou « la » retrouvaient tous les documents (constaté en test).
- Requête : `websearch_to_tsquery` française ET arabe (`ged_requete_texte`), mêmes normalisations.
- Extraits : `ts_headline` avec la configuration `ged_francais` (french + unaccent), accents
  conservés à l'affichage. Rendus en **segments** `{texte, surligne}` (marqueurs à usage privé),
  jamais en HTML : le texte OCR peut contenir `<script>`.
- Limite PostgreSQL constatée : positions de lexèmes plafonnées à 16 383 ; une **expression
  exacte** entre guillemets n'est plus fiable au-delà (un document de 800 pages reste trouvé par
  ses mots, testé).

### Modèle de données (changelog maître)

- `202609271210_creation_fonctions_recherche_plein_texte.xml` : `ged_unaccent`,
  `ged_normaliser_arabe`, `ged_partie_latine`, `ged_partie_arabe`, `ged_document_tsvector`,
  `ged_requete_texte` (IMMUTABLE ; `CREATE OR REPLACE` car `drop-first` ne supprime pas les
  fonctions) ; configuration `ged_francais`. Précondition : extension `unaccent` présente,
  **ajoutée à `scripts/db/preparer-base.sql`** (superutilisateur ; `ged_owner` n'en a pas le droit).
- `202609271215_creation_table_ocr_job.xml` : `ocr_job` (statut, tentatives,
  `prochaine_tentative_le`, bail `verrouille_par` / `verrouille_jusqu_a`, `motif_echec`, `nb_pages`,
  `depose_le`…), contraintes `ck_ocr_job_statut|langue|tentatives|bail`, index unique partiel
  `uk_ocr_job_version_id_actif` (un job actif par version), index partiels de réservation et de
  reprise des baux, clés étrangères vers `document`, `version_document`, `cle_fichier` (cascade).
- `202609271220_creation_table_document_texte.xml` : `document_texte(id, document_id, version_id,
  langue, texte, tsv, provenance, nb_pages, indexe_le)`, `uk_` sur `document_id` et `version_id`
  (index sur la version courante), GIN sur `tsv`.
- `202609271225_jalon_fichiers_ocr_recherche.xml` : étiquette `fichiers-ocr-e5-e6`.
- `version_id` garde le nom du §4.4 ; ajouté aux exceptions du contrôle de nommage de
  `SchemaLiquibaseTest`, qui liste désormais les tables E5/E6 et teste le retour au jalon
  `socle-e1` avec des lots postérieurs. Ce test laissait aussi un schéma jetable par exécution
  (DROP non validé : Liquibase coupe l'autocommit) : corrigé.

### Métriques (Micrometer) et supervision (lot exploitation, dev2)

Noms convenus avec dev2 : timer **`ged.ocr.delai.disponibilite`** (objectif
`GED_OCR_OBJECTIF_DISPONIBILITE`, 24 h par défaut ; 95e centile, seuil à l'objectif ;
histogramme et seuils 5m/1h/6h/24h posés par la configuration de dev2),
`ged.ocr.delai.objectif.depasse`, `ged.ocr.jobs{issue}`.

Adaptateurs prêts, à terminer au prochain `git merge conformite-technique` (branche de dev2
pas encore intégrée) :
- `FileOcrSupervisee` (nom `ocr`, `profondeur()`, `ageDuPlusAncien()`) : ajouter
  `implements FileDeTraitement` ; retirer alors de `MetriquesOcr` les jauges provisoires
  `ged.file.profondeur{file="ocr"}`, `ged.file.age.plus.ancien{file="ocr"}` et
  `ged.ocr.objectif.disponibilite`, publiées d'ici en attendant sous les noms de dev2.
- Sonde antivirus : déclarer dans `ConfigurationFichiers`
  `@Bean VerificationAntivirus verificationAntivirus(AnalyseurAntivirus a) { return a::disponible; }`
  (commentaire en place ; `ClientClamd.disponible()` fait un `zPING`, testé).

### Branchement E6 (vague 2) — fait

1. Dépôt / nouvelle version : job `ocr_job` enfilé **dans la même transaction** (`EnfilageOcr`) ;
   réponse **HTTP 202** avec `statutOcr = EN_ATTENTE_OCR` quand un job est créé, **201** sinon
   (type sans OCR). Restauration d'une version : nouveau job pour la version restaurée.
2. `ged.ocr.chaine.actif=true` (workers, jauges, API recherche et supervision). Chaîne OCR
   synchrone supprimée (`OcrService`, extracteurs, `LecteurPositionnel`…).
3. Cloisonnement §4.3.3 : correctif appliqué (177bbd4) — l'OCR n'alimente plus aucun champ
   d'index ; l'aperçu d'indexation ne lit plus le contenu.
4. Recherche : critères de métadonnées (type, espace, période de dépôt) et tris (pertinence,
   date, nom, type, indexation récente) par jointure sur `document`, documents en corbeille
   exclus (181b7f0).
5. Angular (6ab8ca4) : écran « Recherche » (extraits en segments surlignés, mention « contenu non
   interrogeable »), écran « Traitements OCR » (supervision, relance, réindexation), bandeau d'état
   OCR sur la fiche document.
6. Restent au lot autorisation : `PredicatDroitsProvisoire` et restriction des écrans
   d'administration OCR au profil Administrateur.

### Tests E6 (PostgreSQL 16, schéma jetable portant tout le changelog maître)

50 tests : file (11 : 6 workers concurrents sur 300 jobs sans doublon, SKIP LOCKED sans attente,
reprises 1/5/30 min, échec définitif, bail expiré, relance), worker de bout en bout (7 : dépôt →
interrogeable, métriques, objectif 24 h dépassé, échecs, bail perdu → rien d'indexé, pool de 3
workers), extraction (10, moteur simulé : PDF mixte, seuil, 40 pages, TIFF, natifs, motifs),
Tesseract réel (7 : arabe, bilingue, PDF scanné, délai, langue), recherche (15 : normalisation,
français, arabe, mixte, websearch, pertinence et pagination, extraits, droits, réindexations,
800 pages). Suite complète : **308 tests, 0 échec** (`DB_NAME=ged_dev3 mvn test`).

### Vérifié partiellement

- **Tesseract** : réel, mais sur scans **générés** (police propre) ; qualité sur le corpus MMED
  non mesurée (protocole 4.3.2 à exécuter à réception de l'échantillon). Les tests Tesseract sont
  ignorés sur un poste sans binaire (`GED_TESSERACT`).
- **Débit et 24 h** : aucun essai de charge (800 pages scannées réelles) ; délai mesuré sur des
  dépôts simulés.
- **Métriques** : vérifiées dans un `SimpleMeterRegistry`, pas dans Prometheus.

### Anomalies traitées

- **ANO-E5-001** (qa, sécurité) : keystore du profil dev créé en `./data/cles`, relatif au
  répertoire de lancement, donc versionnable depuis la racine du dépôt. Défaut dev déplacé hors
  du dépôt (`${user.home}/.ged-dev/cles/ged-kek.p12`), `.gitignore` défensif (`**/data/cles/`,
  `*.p12`, `*.pfx`, `*.jks`), test de non-régression. Aucun `.p12` n'a jamais été versionné.
- **`ara.traineddata` absent** (qa) : ajouté au commit `48cc65c` de `ct/dev3` (tessdata_best
  officiel, SHA-256 `ab9d157d…5896`) ; il arrivera dans `conformite-technique` avec l'intégration
  d'E6.

### Points bloquants

Aucun. À signaler à dev1 et pm : `preparer-base.sql` crée maintenant l'extension `unaccent`
(à rejouer sur les bases existantes avant la prochaine montée Liquibase).

---

## Vague 2 — événements de domaine des documents (pour l'audit de dev2)

Paquet `com.ipt.ged.document.evenement`, interface scellée `EvenementDocument` qui **étend
`com.ipt.ged.audit.EvenementAudit`** (contrat de dev2 ; `EvenementAudit`, `ResultatAudit` et
`common/erreur/ExceptionMetier` repris à l'identique de `ct/dev2`, mêmes blobs git : fusion sans
conflit). Valeurs par défaut : `action() = type()` (codes du catalogue `ActionAudit`),
`objetType() = "DOCUMENT"`, `objetId() = documentId()`, acteur utilisateur / application tiré de
`Acteur(employeId, applicationId)` (`Acteur.courant()` depuis le jeton, `Acteur.SYSTEME` pour les
traitements de fond : acteur nul, complété par le journal), résultat `SUCCES`.

| Record | `action()` | Publié par | avant / après / motif |
|---|---|---|---|
| `DocumentDepose` | `DOCUMENT_DEPOSE` | `DocumentService.upload` | après : fichier, empreinte, type, taille |
| `VersionAjoutee` | `VERSION_AJOUTEE` | `DocumentService.ajouterVersion` | avant/après : version courante ; motif : observation |
| `VersionRestauree` | `VERSION_RESTAUREE` | `DocumentService.restaurerVersion` | avant/après : version courante |
| `DocumentTelecharge` | `DOCUMENT_TELECHARGE` | téléchargement | — |
| `ApercuConsulte` | `APERCU_CONSULTE` | prévisualisation | — |
| `MetadonneesModifiees` | `METADONNEES_MODIFIEES` | `DocumentService.update` | champs modifiés seulement |
| `VerrouModifie` | `DOCUMENT_VERROUILLE` / `DOCUMENT_DEVERROUILLE` | `DocumentService.setVerrou` | `{verrouille}` avant/après |
| `DocumentSupprime` | `DOCUMENT_SUPPRIME` | corbeille (unitaire et multiple) | — |
| `DocumentRestaure` | `DOCUMENT_RESTAURE` | sortie de corbeille | — |
| `ContenuIndexe` | `CONTENU_INDEXE` | worker OCR | motif : pages, provenance, délai |
| `OcrEnEchec` | `OCR_ECHEC` | worker OCR | résultat `ECHEC`, motif |

L'enregistrement des index (`IndexationService.enregistrer`) ne publie plus
`MetadonneesModifiees` : dev2 l'audite déjà (`INDEXATION_ENREGISTREE`).

Événements de fichier, eux aussi `EvenementAudit` (objet `FICHIER`) :
`ControleFichiers.FichierInfecte` (`FICHIER_INFECTE`, résultat `REFUS`, §6.1.5) et
`VerificationIntegrite.AnomalieIntegrite` (`INTEGRITE_ANOMALIE`, résultat `ECHEC`, §6.1.4).

Contrat : une écriture publie **dans sa transaction**, avant validation ; une lecture publie après
le contrôle d'accès, avant de servir le contenu ; un refus ne publie pas d'événement document.

### Erreurs : contrat `ExceptionMetier`

`ErreurFichierException` étend `ExceptionMetier` (problem+json de dev2) ; codes et statuts
inchangés : `FICHIER_TROP_VOLUMINEUX` 413, `FORMAT_NON_AUTORISE` / `APERCU_NON_DISPONIBLE` 415,
`FICHIER_INFECTE` 422, `FICHIER_INTROUVABLE` 404, `ANTIVIRUS_INDISPONIBLE` / `CONVERSION_INDISPONIBLE` 503,
`INTEGRITE_COMPROMISE` 500. `GlobalExceptionHandler` non modifié.

### Garde-fou antivirus

Refus de démarrer avec l'antivirus désactivé hors des profils `dev` et `test`, et dans tous les
cas sous `prod` ou `uat` (006370c ; `ConfigurationFichiersTest`).

### Tests vague 2

Suite complète `DB_NAME=ged_dev3 mvn test` : **351 tests, 0 échec**. Nouveaux / réécrits :
`EvenementsAuditTest` (3), `EvenementsDocumentApiTest` (4, faux clamd, EICAR → 422 + événement),
`ApercuTelechargementApiTest` (7), `OcrApiTest` (7 : 202/201, job enfilé, re-OCR à la
restauration), `RepriseVersionsEnClairTest` (4), `SearchIndexerPostgresTest` (16, critères et
tris), `DepotRobustesseApiTest` (compensation : fichier détruit si la transaction échoue).
Front : `ng build` sans erreur. Aucun schéma `ged_verif_*` résiduel dans `ged_dev3_test`.

### Vérifié uniquement par simulateur (inchangé)

ClamAV (`FauxClamd`) et LibreOffice (`FauxSoffice`) : aucun binaire réel sur le poste.

### Notes pour dev2 / dev1

- dev2 : `GED_STORAGE_TEMP` (`ged.env.exemple`) et la mention de `ged.storage.root` dans
  `DEPLOIEMENT.md` sont obsolètes (stockage E5 : `GED_STOCKAGE_RACINE`, keystore).
- dev1 / pm : la reprise de données doit renseigner la version par `RepriseVersionsEnClair`
  (chemins `file_path` → stockage chiffré) avant d'inclure le changeset contract ;
  `preparer-base.sql` crée l'extension `unaccent` (à rejouer sur les bases existantes).

---

## Vagues 3 et 4 — dépôt en deux temps, dépôt avec métadonnées, cycle de vie (E6 fin, E7)

Consigne du coordinateur (vagues 3-4) ; D9 (bascule de la version courante) retiré de ce lot et
confié à dev1 : le modèle des versions et le verrou ne sont pas modifiés ici.

| Réf. | Exigence | Statut proposé | Preuve |
|---|---|---|---|
| T-115 (12.11) | Dépôt en deux temps : INDEXE, SANS_PLAN, A_INDEXER, reprise, 201/202 | Identique (hors Idempotency-Key, dev2) | Paquet `depot` : temps 1 = `DocumentService.upload` (inchangé : contrôles, fichier chiffré, document, version, clé, job OCR) ; temps 2 = `IndexationAuDepot` en `REQUIRES_NEW`. `document.statut_indexation` (changeset `202609291000`, ck, reprise des documents existants). Un échec du temps 2 laisse le document reçu en `A_INDEXER` avec son motif ; l'écran d'indexation reprend (`PUT /indexation/documents/{id}` → `INDEXE`). `DepotDeuxTempsApiTest` (6, sans transaction de test). |
| T-043 (5.3) | Dépôt avec métadonnées en une opération, validées contre le plan, 64 Ko | Identique | `POST /documents` : partie `metadonnees` (objet JSON, clés = code ou identifiant d'index). Validation complète **avant toute écriture** (`ValidationPlan`, règle partagée avec l'écran d'indexation) : 400 `METADONNEES_INVALIDES` avec erreurs par champ, 413 `METADONNEES_TROP_VOLUMINEUSES` au-delà de 64 Ko ; rien n'est déposé. |
| T-100 (12.5) | Purge définitive avec destruction cryptographique | Identique (permission : point d'extension) | `PurgeService` : 409 `DOCUMENT_NON_SUPPRIME` hors corbeille ; lignes métier supprimées, DEK détruites dans la transaction, fichiers effacés après validation, aperçus invalidés ; `DOCUMENT_PURGE` ; jamais automatique. Permission `Purger` : `AutorisationsCycleDeVie` (implémentation provisoire « authentifié », E3 la remplace). `PurgeApiTest` (3 : fichier indéchiffrable après purge, tout ou rien). |
| T-101 (12.6, D10) | Archivage manuel, dossier entier, drapeau, lecture seule, job par lot | Identique (contrat de dev1, implémentations provisoires jusqu'à la fusion) | Statut porté par le contrat du lot modèle (dev1, d33e581) : `ArchivageDocuments` (statut du document) et `ArchivageNoeuds` (drapeau du dossier et de sa sous-arborescence, documents à archiver par tranches), interfaces reprises à l'identique ; `ArchivageService` (empreinte revérifiée, copie PDF/A, statut, audit `DOCUMENT_ARCHIVE`) ; lecture seule totale en service (409 `DOCUMENT_ARCHIVE` sur fiche, index, versions, verrou, corbeille) **et** en base (déclencheur `trg_version_document_archive`) ; désarchivage réservé, empreinte revérifiée. Dossier entier : `ArchivageDossiers`, `job_archivage` + sélection figée (`documentsAArchiver` par tranches), tranches de 100 chacune dans sa transaction (repli document par document), bail et reprise, progression, annulation entre deux tranches (archivés conservés), rapport, un événement par document ; drapeau posé sur le dossier et sa sous-arborescence une fois toutes les tranches passées, dépôt alors refusé (Q7) ; retrait du drapeau par `marquerActif`. Recherche : archivés inclus par défaut, filtre `INCLURE` / `EXCLURE` / `SEULEMENT`, statut rendu. `ArchivageApiTest` (5), `ArchivageDossierApiTest` (5). |
| T-060 (6.1.4) | Copie de conservation PDF/A-2 validée par veraPDF, Word archivable | Identique (LibreOffice simulé) | `ConvertisseurPdfA` : PDF déjà conforme gardé ; LibreOffice (export `SelectPdfVersion=2`) pour le bureautique, Word compris ; PDFBox pour images (TIFF multipage) et PDF (identification XMP, intention sRGB) ; repli par rendu en images (« essentiellement une image », Q7) ; **chaque candidat validé par veraPDF** (bibliothèque 1.30.2, profil PDF/A-2B). Copie chiffrée comme tout fichier, original conservé, servie par défaut (`?original=true` pour l'original). Échec = archivé avec l'original, `copie_conservation.statut = ECHEC` + motif, journalisé. `ConvertisseurPdfATest` (6, veraPDF réel). |
| T-114 (12.10) | Export ZIP en flux avec manifeste | Identique (prédicat : point d'extension) | `ExportDossiers` : `ZipOutputStream` sans fichier temporaire, déchiffrement au fil de l'eau, manifeste en tête (UTF-8, `;`, colonnes du §12.10), version courante, doublons de nom suffixés ; `PredicatDroits` (celui de la recherche, remplacé par E3) ; `DOCUMENT_EXPORTE` par document ; au-delà de 500 documents ou 2 Go : `job_export` (sélection figée), archive chiffrée, « Mes exports », expiration 7 jours. `ExportApiTest` (3). |

### Anomalie corrigée : course sur les en-têtes (signalée par dev2)

`PrevisualisationApiTest.apercuPdf` : `ConcurrentModificationException` intermittente. Cause :
le corps `StreamingResponseBody` s'écrit dans un autre fil ; le `HeaderWriterFilter` de Spring
Security écrit ses en-têtes à l'engagement de la réponse (fil d'écriture) **et** en sortie de
chaîne (fil de la requête) : deux fils modifiaient la même table d'en-têtes (en production :
en-têtes incohérents possibles). Correction (55bb079) : téléchargement, aperçu et export servis
par `InputStreamResource`, copiés en flux dans le fil de la requête ; l'archive ZIP est produite
par un fil dédié dans un tube, jamais au contact de la réponse. Les tests vérifient
`asyncNotStarted()`. Le test de la branche de dev2 est la version E5 d'origine : corrigé par la
fusion de ce lot (le correctif est dans le contrôleur, pas dans le test).

### Points d'extension et fusion

- Contrat d'archivage de dev1 (d33e581) : `common.StatutConservation`,
  `document.archivage.ArchivageDocuments`, `workspace.archivage.ArchivageNoeuds` repris **à
  l'identique** (mêmes blobs git). Implémentations provisoires de cette branche
  (`cycledevie.provisoire.ArchivageDocumentsProvisoire`, `ArchivageNoeudsEspaces`, sur
  `workspace`), déclarées seulement en l'absence d'une autre : **à supprimer à la fusion**,
  `ArchivageDocumentsJdbc` / `ArchivageNoeudsJdbc` de dev1 prenant le relais. L'archiviste passé
  au contrat est l'employé dans cette branche ; à la fusion, passer l'identité GED
  (`ActeurCourant.utilisateurId()`).
- Colonnes `document.statut_conservation`, `archive_le`, `archive_par` : posées par le changeset
  `202609301000` de dev1 ; le changeset provisoire `202609301005` les crée à l'identique ici
  (sans la clé étrangère vers `utilisateur`) et passe en MARK_RAN quand elles existent. Champs de
  `UploadDocument` alignés sur ceux de dev1. Le déclencheur de gel des versions
  (`202609301010`) vient après.
- Garde d'écriture : `DocumentService.refuserSiArchive` et le contrôle d'`IndexationService`
  (409 `DOCUMENT_ARCHIVE`, même code) sont à remplacer à la fusion par
  `GardeEcriture.exigerModifiable` de dev1.
- `AutorisationsCycleDeVie` (Purger, Archiver, désarchivage réservé) : à fournir par E3 (dev1).
- `Dossiers` (nom du dossier, documents de la sous-arborescence avec chemins, pour l'export) :
  implémentation sur `workspace` ; à remplacer par une implémentation sur `noeud` /
  `document_rattachement` à la fusion.
- Renommages du socle de dev1 à reporter à la fusion : `deleted` → `supprime`,
  `workspace` → `noeud`, emplacement principal du document ; SQL concernés : `DossiersEspaces`,
  `ExportDossiers.details` (colonnes `objet`, `date_document` du socle commun de dev1 à lire au
  lieu de `metadonnees`), `SearchIndexerPostgres`, `PurgeService` (rattachements à supprimer).
- Idempotency-Key du dépôt (§12.11 « rejeu sans effet ») : lot de dev2.

### Vérifié uniquement par simulateur ou partiellement

- **LibreOffice** : absent du poste ; export PDF/A-2 simulé (`FauxSoffice` produit un vrai PDF/A,
  que veraPDF valide réellement). LibreOffice ≥ 7.4 requis en production (option d'export en JSON).
- **veraPDF** : réel (bibliothèque). **ClamAV** : faux clamd (inchangé).
- Charge : aucun essai d'archivage ou d'export de plusieurs milliers de documents.

### Dépendances ajoutées (registre de dev2 à régénérer)

`org.verapdf:validation-model-jakarta` 1.30.2 (GPL-3.0+ ou MPL-2.0+, **MPL-2.0 retenue** :
compatible avec la cession à MMED, bibliothèque non modifiée) et ses transitives
(`verapdf-*`, `Saxon-HE` MPL-2.0, `rhino` MPL-2.0, `xmlresolver` Apache-2.0, `jaxb` EDL/BSD) ;
`org.apache.pdfbox:xmpbox` 3.0.8 (Apache-2.0). À ajouter à la table des usages de
`outils/registre-dependances.mjs` (dev2).

---

## Fusion de `conformite-technique` (E2, E3 de dev1) dans `ct/dev3`

- **Contrats de dev1 branchés, implémentations provisoires supprimées** :
  - `cycledevie.provisoire` ;
  - `AutorisationsCycleDeVie` : remplacée par `ControleAcces` (`PURGER`, `ARCHIVER` ; 404 hors périmètre, 403 sinon) ;
  - `PredicatDroitsProvisoire` et `ControleAccesPrevisualisationProvisoire` : remplacés par les beans `@Primary` d'`AccessPredicate`.
- **Archivage** : `ArchivageNoeudsJdbc` et `ArchivageDocumentsJdbc` de dev1 portent désormais le drapeau et le statut. L'archiviste passé au contrat est l'identité GED (`utilisateurId`) ; il est conservé sur le job (`job_archivage.archiviste_utilisateur_id`).
- **Gardes d'écriture** : `GardeEcriture.exigerModifiable` sur la fiche, le versement et la restauration de version. Même code 409 `DOCUMENT_ARCHIVE` pour la suppression, le verrou et l'indexation d'un document archivé.
- **Changesets** :
  - colonnes de conservation : celui de dev1 (`202609301000`) est gardé, le mien (`202609301005`, provisoire) est supprimé ;
  - gel des versions renuméroté `202609301020` : l'identifiant `202609301010-1` était déjà pris par dev1 ;
  - expand du stockage chiffré renuméroté `202609281002` : l'identifiant `202609281000-1` était déjà pris par dev1 ;
  - tables d'association `job_*_element` : clé `id` en `uuid_v7()` et contrainte uk_ sur le couple (convention ANO-E1-001).
- **Renommages du socle reportés** (`deleted` → `supprime`, `workspace` → `noeud`, `workspace_id` → `noeud_principal_id`) :
  - `DossiersEspaces` devient `DossiersNoeuds` ;
  - mises à jour dans `SearchIndexerPostgres`, `CriteresMetadonnees` (critère d'espace : emplacement principal **ou** rattachement), `ExportDossiers` (colonnes `objet`, `date_document` et `confidentialite` du socle commun), ainsi que dans les tests (`BasePostgres`, `SearchIndexerPostgresTest`).
- **Rattachements** :
  - export : chaque document une seule fois, tous ses chemins dans la sélection au manifeste (principal d'abord) ;
  - recherche : prédicat `EXISTS` de dev1 sur les emplacements, une ligne par document ;
  - purge : suppression des rattachements, désignations et habilitations du document.
- **Supervision OCR** : `/api/v1/admin/ocr/**` et la réindexation exigent `SUPERVISER_TRAITEMENTS`.
- **Front** :
  - menu : Recherche et Mes exports pour tous, Traitements OCR sous `SUPERVISER_TRAITEMENTS` (garde de route) ;
  - fiche document : actions conditionnées aux permissions (`MODIFIER`, `ARCHIVER`) ;
  - statut du dossier lu par `GET /api/v1/archivage/dossiers/{id}`.
- **Tests de dev1 adaptés** : dépôts de vrais PDF dans `CheminsAccesApiTest` (le contrôle du type réel refuse « pdf » en 415) ; `FichierVersion` en UUID ; le test `PrevisualisationApiTest` est remplacé par `ApercuTelechargementApiTest`.
- **Non anticipé** : le lot E7 modèle de dev1 (b739ed3). dev1 réconciliera `DocumentService`, `ValidationPlan` et les événements doublons.

---

## Essais de charge et de volumétrie (§4.3.2, §4.3.4, §6.6, D6)

Rapport : [`docs/exploitation/ESSAIS-DE-CHARGE.md`](../../exploitation/ESSAIS-DE-CHARGE.md)
(méthode, mesures, limites, projection, recommandations). Banc : paquet de test
`com.ipt.ged.charge` (suffixe `IT`, à la demande), base dédiée jetable `ged_dev3_charge`.

- **OCR** : 6 à 10 s par page et par cœur (best, fonds bilingue) ; seuil §4.3.2 tenu,
  cible §4.3.4 / §6.6 (1 à 3 s ; 20 000 pages en 2-4 h sur 4 vCPU) **hors d'atteinte**
  (~13 h) : écart à signaler au client (rapport § 6.3).
- **Configuration livrée** : langue par défaut `ara+fra` (CER arabe 10,3 → 5,1 %, +4 % de
  temps) ; `GED_OCR_LANGUE_DEFAUT`, `GED_OCR_CHAINE_WORKERS` documentés.
- Rejetés après mesure : `tessdata_fast` (CER arabe × 2), `--psm 6`, OpenMP multi-fil.
- **À faire (lot E6, avant la reprise)** : séparer la reprise du flux dans `ocr_job`
  (priorité), sinon D6 est violé pendant toute la reprise (~33 jours sur 4 vCPU).
- **Recherche** : ne tient pas la volumétrie cible sur les termes fréquents (10 à 40 s à
  50 000 documents) ; correctifs proposés (total plafonné, ensemble classé borné, droits
  avant classement, `VACUUM ANALYZE` après chargement, multicritère historique en SQL).
- **ANO-E5-003** corrigée (e91edec) : intégrité vérifiée avant de servir l'export
  synchrone, refus `INTEGRITE_COMPROMISE` en problem+json.
- Vérifié sur un poste portable partagé et bridé par intermittence : valeurs absolues à
  confirmer sur le serveur de recette ; CER sur corpus synthétique, protocole §4.3.2 sur
  l'échantillon réel toujours dû.

---

## Tour 1 de la mise en conformité (`ct/dev3-r1`, depuis `claude/inspiring-lovelace-10bg1c` @ `71bdc1d`)

Poste : conteneur Linux, 4 vCPU partagés avec six autres membres ; bases `ged_dev3` /
`ged_dev3_test`. `ct/dev5-r1` fusionnée à la demande de pm (`8dc269e`) avant de finir
ANO-E7-004 : sa liste blanche `dateDocument` et son départage par identifiant sont gardés tels
quels, le tri par défaut s'appuie dessus.

| Id | État | Commit(s) | Cause, correctif, preuve |
|---|---|---|---|
| ANO-E7-004 | Corrigée | `88625f3` (+ résolution `8dc269e`) | Cause : `Tri.pageable` retombait sur `id DESC` ; `POST /recherches` sans texte triait en Java sur la date de dépôt et son énumération n'avait pas la date du document. Correctif : tri par défaut `date_document DESC, id DESC` sur `GET /documents` (et corbeille) et sur `POST /recherches` sans texte ; `DATE_DOCUMENT` ajouté au contrat (plein texte compris, et à l'écran de recherche) ; `dateDocument` rendu dans les résultats. Test : `ContratApiTest.triParDateDuDocument` (a 01/02, b 01/05, c 01/03 → b, c, a), rouge sans le correctif (vérifié). |
| R31 / T-035 | Corrigé | `46efa0d` | Cause : `ocr_job` servi `ORDER BY depose_le` ; la reprise (~150 000 jobs) aurait bloqué le flux pendant des semaines (D6). Correctif : colonne `ocr_job.priorite` (0 flux, 1 reprise ; changeset `202610041310`, retour arrière explicite, aucune donnée métier perdue), réservation `ORDER BY priorite, depose_le, id`, `RepriseVersionsEnClair` enfile en `REPRISE`, âge de file (métrique D6) sur le flux seul. Tests : `OcrJobQueuePostgresTest.fluxCourantAvantReprise` (rouge avec l'ancien ORDER BY, vérifié), `RepriseVersionsEnClairTest` (priorité 1). T-035 peut remonter à « Livré ». |
| R32 / P-14 | Corrigé, remesuré | `9ebe15d`, `8e538d5` | Correctifs du § 5.4 appliqués ; remesure à 50 000 documents : `docs/exploitation/ESSAIS-DE-CHARGE.md` § 5.5 (terme très fréquent 2,4 s → 0,4 s ; 8 utilisateurs p95 6,7 s → 2,1 s ; multicritère par type 4,0 s → 0,04 s ; tout le fonds 4,2 s / 463 Mo → 0,26 s / 212 Mo ; `POST /recherches` paginé 54 ms). Tests : `SearchIndexerPostgresTest.plafond` (total « plus de N », tri exact sous le plafond ; rouge sans le correctif : champ absent, tri sur candidats arbitraires), `ContratApiTest.criteresIndexEnSql` (règle par nature inchangée). Changeset `202610041320` (analyse automatique à 2 %). Limite : pertinence classée sur les 5 000 premières correspondances au-delà du plafond. |
| Tests LibreOffice de la référence | Corrigé | `8873d70` | Cause : `ArchivageApiTest.conversionEnEchec` et `ApercuTelechargementApiTest.apercuBureautiqueSansLibreOffice` utilisaient le `soffice` du PATH. Correctif : le profil de test désigne un binaire introuvable (`application-test.yml`) ; chaque test vérifie d'abord que le convertisseur se déclare indisponible, puis prouve toujours le 503 et la copie ECHEC. |
| T-059 | Corrigé | `21bc675` | Manque : aucun point d'entrée de vérification à la demande (recette E5-A08 NA, plan CR-E5-04). Livré : `POST /api/v1/admin/integrite/documents/{id}` (versions et copies de conservation, bilan par fichier), `POST`/`GET /api/v1/admin/integrite/verification` (fonds entier en tâche de fond, 409 si une passe tourne, état et bilan). Réservé Administrateur + `SUPERVISER_TRAITEMENTS` ; tracé `INTEGRITE_VERIFIEE` (nouveau code d'`ActionAudit`), divergences en `INTEGRITE_ANOMALIE`. La passe du fonds n'est plus conditionnée à la planification. Tests : `IntegriteALaDemandeApiTest` (3), `VerificationIntegriteTest.passeEnFond`. Reste « tâche mensuelle non exercée » par qa (planification inchangée). |
| T-060 / T-064 | Prouvé avec LibreOffice réel | `078017b` | LibreOffice 24.2.7.2 installé sur le poste. `LibreOfficeReelApiTest` (profil de test, `soffice` du PATH) : aperçu d'un Word en PDF (texte rendu, `ApercuConsulte`, cache chiffré relu) ; archivage d'un Word, copie `LIBREOFFICE` validée **PDF/A-2B par veraPDF 1.30.2**, original servi sur `?original=true`. 2 tests verts, non ignorés. La réserve « LibreOffice simulé » de T-009/T-060/T-064 est levée sur ce poste (à confirmer par qa). |

### Autres lignes de SUIVI.md à mon nom (tâche 6)

Aucune ne demande de code ou de documentation supplémentaire de ma part ce tour-ci :

- **T-009** : LibreOffice désormais réel (ci-dessus) ; reste la recette qa.
- **T-028** : **bloqué** — échantillon MMED (Q09, R23) et réponse à QR8 attendus.
- **T-031** (langue par type), **T-039** (réindexation complète), **P-09** (relance manuelle),
  **T-115** (issues `SANS_PLAN`, `A_INDEXER`) : code et tests automatisés en place ; seule la
  recette qa manque.
- **T-034** : livré, en attente de recette.
- **T-101** : ANO-E7-005 appartient à dev1.
- **P-13** : livré par dev2.

### Points pour pm

1. T-035 peut remonter (R31 levé) ; P-14 : correctifs appliqués et remesurés (§ 5.5), l'écart
   de débit OCR (R30) reste à porter à MMED.
2. Prochain gain de la recherche pour les utilisateurs à droits restreints : réécrire le
   prédicat `AccessPredicate.predicatSql` (dev1) en jointure sur les emplacements autorisés
   au lieu d'un `EXISTS` par document (0,4 à 0,5 s restants sur les listes sans texte).
3. `POST /indexation/recherche` n'est plus appelé par l'écran et reste non paginé par
   contrat : à retirer ou à paginer à la prochaine version de l'API.
4. Front : `ng build` refuse le Node 22.22.2 du poste ; construit avec le Node 24 déposé par
   dev5 dans le bloc-notes (vert, avertissements de budget préexistants).
5. Banc de charge : schéma jetable `ged_charge` dans `ged_dev3` (le compte propriétaire ne peut
   pas créer de base) ; à supprimer en fin d'essais (`DROP SCHEMA ged_charge CASCADE`).

### Tests du tour 1

Suite back complète (`29d0480`) : **609 tests, 3 échecs**, aucun nouveau : les deux de la
référence dépendant de l'ordre ou des données (`WorkflowApiTest.employesWithAccount`,
`WorkSpaceApiTest.moveIntoDescendant`) et `SupervisionIntegrationTest.portDeManagement`, dû à
`GED_MANAGEMENT_PORT` exporté par l'environnement d'équipe (même constat chez dev5). Les deux
échecs LibreOffice de la référence sont corrigés. Front : `ng build` vert.

## Tour 2 de la mise en conformité (`ct/dev3-r2`, depuis `claude/inspiring-lovelace-10bg1c` @ `08c710c`)

Même poste (conteneur Linux partagé, bases `ged_dev3` / `ged_dev3_test`).

| Id | État | Commit(s) | Cause, correctif, preuve |
|---|---|---|---|
| ANO-F-011 | Corrigée | `552fdf0`, `1c2779e`, `0ab45de` | Cause : `RechercheMetadonnees.Requete`, `RechercheContratRequest` et les paramètres de `GET /recherche/plein-texte` ne portaient ni la date du document, ni la confidentialité, ni le déposant ; Jackson (et Spring pour la chaîne de requête) ignorait tout nom inconnu. Correctif : `recherche.CriteresDocument` (`dateDocumentDu`/`dateDocumentAu` bornes incluses, `confidentialite` PUBLIC/PRIVE/CONFIDENTIEL dans le périmètre autorisé, `deposantUtilisateurId` ; à défaut d'identité du déposant — documents antérieurs à `202610011000` ou repris — l'employé auteur du dépôt rattaché à l'identité), fragments SQL communs aux trois recherches. Paramètre inconnu : 400 `PARAMETRE_INCONNU` (problem+json, membre `parametre`, chemin complet pour un critère imbriqué, ex. `criteres[0].valeurr`) — corps marqués `@ChampsInconnusRefuses` (module Jackson `ModuleChampsInconnus`, les autres corps de l'API gardent leur comportement), chaîne de requête contrôlée par `ParametresConnus`. Documentation de l'API complétée (`champs.yml`). Test : `CriteresImposesApiTest` (critères sur les trois recherches, déposant ancien, refus des paramètres inconnus). Rouge sans le correctif par construction : sur `08c710c` les champs n'existent pas, ils sont ignorés (3 résultats au lieu de 1, 200 au lieu de 400, constat de qa2). |
| ANO-E6-001 | Corrigée (par R32) | `9ebe15d` (tour 1), test `55b3d88` | Cause : `ts_headline` sur le texte entier normalisé (157 Mo → allocation de 1,5 Gio refusée). La borne de l'extrait du tour 1 (`left(texte, 32 768)`) la lève : **vérifié en réel** sur PostgreSQL 16, même expression sur 157 286 388 caractères : `invalid memory alloc request size 1610612736` en 27 s sans la borne, extrait de 928 caractères en 0,15 s avec. Test : `SearchIndexerPostgresTest.texteDe150Mo` (texte de 150 Mo écrit par la base, recherche qui répond, extrait borné ; 1,8 s). |
| ANO-E5-005 | Corrigée | `5006a3b`, `ce1f039` | Cause : l'événement `AnomalieIntegrite` n'avait que l'audit pour auditeur. Correctif : `MetriquesIntegrite` — compteur `ged_integrite_anomalies_total{statut}` (toute divergence : passe mensuelle, passe ou vérification à la demande ; publié à 0 au démarrage) et jauge `ged_integrite_derniere_passe_anomalies` (dernière passe terminée du fonds, gardée pendant la suivante) ; règle `GedIntegriteFichiersAnomalie` (critique : détection dans l'heure, ou dernière passe en anomalie jusqu'à une passe conforme). `promtool check rules` : 13 règles, SUCCESS ; `promtool test rules deploiement/prometheus/alertes-integrite.test.yml` : SUCCESS (déclenchement puis extinction). Tests : `VerificationIntegriteTest.metriques`, compteur vérifié dans le contexte réel (`IntegriteALaDemandeApiTest.document`), `SupervisionIntegrationTest.alertesSurDesMetriquesPubliees` (les deux séries sont publiées). La commande à la demande existe depuis le tour 1 (`21bc675`). `EXPLOITATION.md` §7 complété. |
| `POST /indexation/recherche` | Paginé | `028004b`, `e21b86e` | Gardé (seul chemin qui regroupe par index de groupage) mais paginé : `page`, `taille` (50 par défaut, plafond 200) ; réponse `PageResponse` de groupes (`content`, `total` des documents, `page`, `size`, `totalPages`) ; avec groupage, tri par valeur de groupe (ordre binaire) puis du plus récent ; `total` d'un groupe sur tout l'ensemble ; seule la page est lue. Corps strict (`PARAMETRE_INCONNU`). Test : `CriteresImposesApiTest.rechercheParIndexPaginee`. |
| T-034 | Aligné sur le §4.3.4 | `5fe9df1` | Le dossier : « trois tentatives avec délais croissants (1, 5 puis 30 minutes) ; après échec, le job passe à OCR_ECHEC », sans exception pour les fichiers corrompus. Le worker clôturait d'emblée les motifs « définitifs » (O6). Correctif : tout échec suit la politique de reprise (motif conservé ; « définitif » n'est plus qu'une information de diagnostic). Test : `TravailleurOcrPostgresTest.pdfCorrompu` (reprise à 1 min, `OCR_ECHEC` après 4 exécutions, motif `FICHIER_CORROMPU`), `echecs` et `fichierAbsent` adaptés. |
| T-059 (course) | Corrigée | `e61c708` | `IntegriteALaDemandeApiTest.fonds` échouait par intermittence (`enCours: false` dans la réponse 202 : un petit fonds était vérifié avant la lecture de l'état). `demarrerEnFond` rend l'état au lancement, porté par la réponse 202. |

### Points pour pm

1. **dev5 (écran de recherche multicritère)** : sur `POST /documents/recherche`, les nouveaux
   champs sont `dateDocumentDu`, `dateDocumentAu` (AAAA-MM-JJ), `confidentialite`
   (`PUBLIC`, `PRIVE`, `CONFIDENTIEL`), `deposantUtilisateurId` (UUID de l'identité GED).
   Tout champ inconnu est désormais **refusé en 400** : ce point d'entrée pagine par `page` et
   **`size`** (pas `taille`, contrairement à `POST /recherches`). Harmoniser à la prochaine
   version du contrat.
2. `GET /recherche/plein-texte` : `size` est accepté (alias posé par `FiltreConventionsApi` sur
   tout GET) mais non lu ; le défaut de `taille` y reste 20, pas 50 (DAT §5.3.2).
3. `POST /indexation/recherche` : la réponse passe d'une liste de groupes à une page de groupes
   (changement de contrat ; aucun appel du front).
4. **T-034 change de comportement** : un fichier corrompu, protégé ou non pris en charge passe en
   `OCR_ECHEC` après 4 exécutions (~36 min) au lieu d'immédiatement ; l'observation O6 de la
   recette vague 8 est levée. À revérifier par qa.
5. Risque hors de mes tâches : `GET /api/v1/ocr/documents/{id}/texte` renvoie le texte extrait
   **entier** (157 Mo pour le fichier de la recette E6-001) : à borner ou à servir en flux.
6. `deploiement/prometheus/alertes.yml` (dev2) : une règle ajoutée (`ged-integrite`), 13 règles.

### Tests du tour 2

Suite back complète (`0ab45de`, `mvn -B -q test`, sans `GED_MANAGEMENT_PORT` ni `SERVER_PORT`) :
**639 tests, 0 échec, 0 erreur** (référence : 630 ; +9 : `CriteresImposesApiTest` ×6,
`texteDe150Mo`, `VerificationIntegriteTest.metriques`, `pdfCorrompu`). Une première passe
avait relevé 6 échecs dans `IndexationApiTest` et `IndexationAutomatiqueApiTest` (lecture de
l'ancienne forme de réponse de `POST /indexation/recherche`), corrigés par `e21b86e`.
`promtool check rules` et `promtool test rules` : SUCCESS. Front non modifié (pas de build).

## Tour 3 de la mise en conformité (`ct/dev3-r3`, depuis `claude/inspiring-lovelace-10bg1c` @ `ff20f21`)

Même poste (conteneur Linux partagé, bases `ged_dev3` / `ged_dev3_test`).

| Id | État | Commit | Cause, correctif, preuve |
|---|---|---|---|
| P-08 (contrat d'API) | Aligné sur le §5.3.2 | `d47c3de` | Cause : le tour 2 (`552fdf0`, ANO-F-011) refusait tout champ ou paramètre inconnu des quatre recherches (400 `PARAMETRE_INCONNU`), contre la politique de compatibilité du dossier (évolutions additives, champs inconnus ignorés par le serveur) recettée en vague 8. **Décision** : le dossier prime ; le champ inconnu est **ignoré** (la recherche répond 200 comme sans lui) et **signalé** dans l'en-tête de réponse `GED-Champs-Ignores` (chemins séparés par des virgules, `criteres[0].valeurr` pour un critère imbriqué ; encodage en pourcentage hors ASCII ; 20 noms au plus). Le besoin d'ANO-F-011 (« ignoré en silence ») reste couvert sans casser un client plus récent. Mise en œuvre : `@ChampsInconnusSignales` (ex-`ChampsInconnusRefuses`) sur les corps de `POST /documents/recherche`, `POST /recherches`, `POST /indexation/recherche` et leurs critères ; `ParametresConnus.signaler` pour `GET /recherche/plein-texte`. `PARAMETRE_INCONNU` n'est plus émis (gardé au catalogue). Une valeur invalide (`confidentialite: SECRET`, bornes inversées) reste en 400 : ce n'est pas un champ inconnu. OpenAPI : politique de compatibilité écrite dans la description générale, en-tête `ChampsIgnores` documenté sur les recherches. Tests : `CriteresImposesApiTest.parametreInconnuIgnoreEtSignale` (200, total non filtré de 3, en-tête attendu, absent quand tout est connu ; rouge par construction sur `ff20f21` : 400 au lieu de 200), `ChampsIgnoresTest` (encodage, dédoublonnage, borne), `SpecificationOpenApiTest.compatibilite`. |
| T-050 | Harmonisé | `4bc6f47` | Cause : `GET /recherche/plein-texte` lisait `taille` avec un défaut de 20 et ignorait `size` (que `FiltreConventionsApi` pose pourtant sur tout GET) ; chaque corps de recherche n'acceptait qu'un des deux noms. Correctif : `Tri.taillePage(size, taille)` — `size`, sinon son alias `taille` ; absent ou < 1 : 50 ; au plus 200 — utilisé par les quatre recherches. Ajouts sans casse : `size` dans `POST /recherches` et `POST /indexation/recherche`, `taille` dans `POST /documents/recherche` ; `size` l'emporte si les deux sont donnés ; la page plein texte (`PageResultats`, aussi rendue par `POST /recherches`) rend `size` en plus de `taille`. Test : `CriteresImposesApiTest.paginationHomogene` (défaut 50, `size` et `taille` lus avec `page`, plafond 200, priorité de `size` ; rouge par construction sur `ff20f21` : taille 20, `size` ignoré). |
| `GET /ocr/documents/{id}/texte` (point 5 du tour 2) | Borné par plage | `c8ae886` | Cause : le texte extrait était lu entier (`SELECT texte`) et rendu entier en JSON (157 Mo pour le fichier d'ANO-E6-001). Correctif : lecture par plage en base, `substr(texte, debut + 1, longueur + 1)` (PostgreSQL ne lit et ne décompresse que le début du texte jusqu'à la fin de la plage ; le caractère de plus dit s'il y a une suite) et `octet_length(texte)` (taille stockée, sans détoaster). Paramètres `debut` (0 par défaut) et `longueur` (1 000 000 de caractères par défaut et au plus) ; hors bornes : 400 `PARAMETRE_INVALIDE`. Réponse : mêmes champs, `texte` = la plage, plus `debut`, `longueur`, `suite`, `tailleOctets`. Rangs en caractères (points de code, arabe et hors plan de base compris). **Droits et audit inchangés** : même chemin, donc même garde (`GardeDroitsRequetes` : document lisible, sinon 404 ; `CheminsAccesApiTest` vert) ; aucune trace de lecture n'existait pour ce point d'entrée, il n'en a pas davantage. Tests : `OcrApiTest.texteParPlage` (texte de 3 000 000 de caractères écrit par la base : 1 000 000 rendus et `suite`, plage du milieu, fin, plafond, 400 ; rouge par construction sur `ff20f21` : 3 000 000 rendus), `SearchIndexerPostgresTest.texteDe150Mo` (plages au début, à la fin et au-delà du texte de 157 286 388 caractères) et `texteParPlage`. |

### Points pour pm

1. **P-08 / ANO-F-011** : le comportement de la recherche face à un champ inconnu change à nouveau
   (400 → 200 avec en-tête `GED-Champs-Ignores`). À recetter par qa (P-08 : « champs inconnus
   ignorés ») et par qa2 (ANO-F-011 : l'appelant voit le champ ignoré). SUIVI.md (P-08, ligne 23
   « paramètre inconnu → 400 `PARAMETRE_INCONNU` ») est à mettre à jour par pm.
2. **dev5** : l'écran de recherche plein texte envoie lui-même `taille=20` (`recherche-plein-texte.ts`,
   `taille = 20`) ; le serveur applique désormais 50 par défaut, mais l'écran garde sa taille. À
   aligner sur 50 si pm le juge utile. Le front peut lire `GED-Champs-Ignores` (même origine) pour
   avertir l'utilisateur d'un critère non appliqué.
3. **T-050** : reste hors de mes tâches `GET /workflow/a-traiter` (20 par défaut, plafond 100),
   relevé par pm.
4. `GET /ocr/documents/{id}/texte` : changement de contrat **additif** (champs ajoutés) mais un
   texte de plus de 1 000 000 de caractères n'est plus rendu entier ; aucun appel du front.

### Tests du tour 3

Suite back complète (`c8ae886`, `mvn -B -q test`, sans `GED_MANAGEMENT_PORT` ni `SERVER_PORT`) :
**668 tests, 0 échec, 0 erreur** (référence : 661 ; +7 : `ChampsIgnoresTest` ×3,
`SpecificationOpenApiTest.compatibilite`, `CriteresImposesApiTest.paginationHomogene`,
`OcrApiTest.texteParPlage`, `SearchIndexerPostgresTest.texteParPlage` ;
`parametreInconnuRefuse` devient `parametreInconnuIgnoreEtSignale`). Front non modifié (pas de build).

## Tour 5 de la mise en conformité (`ct/dev3-r5`, depuis `claude/inspiring-lovelace-10bg1c` @ `4090da3`)

Même poste (conteneur Linux partagé, bases `ged_dev3` / `ged_dev3_test`).

| Id | État | Commit | Cause, correctif, preuve |
|---|---|---|---|
| ANO-E6-002 (T-115) | Corrigée | `cf1261f` | **Cause** : sous une charte **manuelle**, `IndexationService.recomposerReference` enregistrait la référence composée (`document.reference`, `varchar(255)`) sans contrôle de longueur — le contrôle `Limites.controler` n'existait qu'en charte automatique. Une valeur d'index texte de 300 caractères n'était donc refusée que par la base. Par le PUT, le filet `DataIntegrityViolationException` du gestionnaire commun répondait proprement 400 `DONNEE_REFUSEE` ; au dépôt, `DepotService` attrapait l'échec du temps 2 et recopiait `e.getMessage()` dans `motifIndexation`, c'est-à-dire le texte JDBC complet (requête `update document set …`, colonnes de la table). **Correctif** : (1) en charte manuelle, la référence composée est contrôlée avant écriture et refusée en 400 `DONNEE_REFUSEE` (code que le PUT renvoyait déjà, donc inchangé pour l'appelant) avec un message qui nomme le champ et sa limite : « Le champ « référence composée du document » ne peut pas dépasser 255 caractères (reçu : 300) : raccourcissez les valeurs d'index qui la composent. » Même règle pour le dépôt et pour la saisie, puisque les deux passent par `IndexationService.enregistrer`. (2) `DepotService.motif` : `motifIndexation` ne recopie que les refus métier (`ExceptionMetier`, `IllegalArgumentException`, déjà rendus tels quels par l'API) ; un refus du schéma reçoit le même libellé que par l'API (`GlobalExceptionHandler.causeLisible`, rendue publique) ; toute autre panne, un libellé générique (« incident technique lors de l'enregistrement ; reprenez l'indexation depuis l'écran d'indexation. ») ; le détail technique (pile comprise) va au journal seulement (`log.warn` avec l'exception). Le dépôt reste conforme au §12.11 : 201/202, temps 1 acquis, issue `A_INDEXER`, reprise par l'écran d'indexation. Charte automatique inchangée (message « nom composé du document », 400 `REQUETE_INVALIDE` par le PUT). Aucun changement de schéma, donc aucun changeset Liquibase. **Tests** : `DepotDeuxTempsApiTest.planManuelValeurTropLongue` (plan manuel, 300 caractères → `A_INDEXER`, motif métier sans « SQL », « update document », « could not execute » ni « varying », aucune valeur écrite ; même valeur par PUT → 400 `DONNEE_REFUSEE` avec le même libellé ; 200 caractères → 200, `INDEXE`, nom saisi intact) et `DepotServiceMotifTest` ×3 (refus du schéma, panne technique, refus métier, par `DepotService.deposer` avec collaborateurs simulés). **Rouges sans le correctif** (vérifié en retirant le code de `cf1261f` : 3 échecs, le motif renvoyé contenait le texte SQL brut), verts avec. |

### Points pour pm

1. **ANO-E6-002** à revérifier par qa (`qa/r4/ref-longue.sh`) : le dépôt en plan manuel répond désormais
   `A_INDEXER` avec le motif « Métadonnées non enregistrées : Le champ « référence composée du document » ne
   peut pas dépasser 255 caractères (reçu : 300) : … » ; le PUT garde 400 `DONNEE_REFUSEE`, mais son `detail`
   devient ce même message (il disait « Donnée refusée par la base : une valeur dépasse la longueur autorisée… »).
   T-115 peut remonter si qa confirme.
2. Hors périmètre, non traité : le refus n'est pas avancé au temps 1 (le dépôt n'est pas refusé en 400) ;
   la référence dépend des jetons système de la charte (date, heure) et du document, que seul le temps 2
   connaît. Le §12.11 admet `A_INDEXER` avec reprise ; c'est l'issue retenue, comme en charte automatique.

### Tests du tour 5

Suite back complète (`cf1261f`, `mvn -B -q -o test` sur `ged_dev3_test`, sans `GED_MANAGEMENT_PORT` ni
`SERVER_PORT`) : **691 tests, 0 échec, 0 erreur, 0 ignoré (115 classes)** (référence après le tour 4, vague 11 de
qa : 687 ; +4 : `DepotDeuxTempsApiTest.planManuelValeurTropLongue`, `DepotServiceMotifTest` ×3). Front non
modifié (pas de build).

## Tour 6 de la mise en conformité (`ct/dev3-r6`, depuis `claude/inspiring-lovelace-10bg1c` @ `a8346ce`)

Même poste (conteneur Linux partagé : Xeon 2,1 GHz, 4 cœurs ; Tesseract 5.3.4 du paquet Ubuntu ;
bases `ged_dev3` / `ged_dev3_test`).

| Id | État | Commits | Cause, correctif, preuve |
|---|---|---|---|
| P-14 / R30 (débit OCR) | **Cible §4.3.4 tenue sur le corpus** (2,90 s par page et par cœur) ; à confirmer sur l'échantillon et le serveur de MMED | `5e573d0`, `1a0de56`, `89ec903`, `3b03465` | **Cause** : modèles `tessdata_best` en flottants et rendu à 300 dpi : 5,35 s de CPU par page et par cœur sur ce poste (Tesseract seul 5,07 s ; qa, vague 11 : 5,1 s). **Mesure** (`BancTesseractIT#reglagesDebit`, nouveau : temps CPU exact des processus Tesseract par `/proc/self/stat`, temps CPU Java de la chaîne, CER par cellule ; `openMpTempsCpu`) : pistes une par une — OpenMP libre = double de CPU (un fil par processus conservé) ; `tessdata_fast` −47 % mais arabe dégradé 9,1 à 9,6 % ; **modèles best compactés en entiers** (`combine_tessdata -c`, même réseau) −41 % ; 200 dpi −11 % ; 250 dpi annulé par le rendu Java ; psm 4, binarisation d'Otsu, PGM rejetés (qualité ou pas de gain) ; `tessedit_do_invert=0` −2 à −3 % (bruit) non livré ; saut des pages à couche texte déjà livré. **Correctif** (réglable) : `ged.ocr.modeles=entiers` par défaut (`ModelesEntiers` : copie compactée des modèles livrés au démarrage, refaite si l'empreinte change, repli sur les modèles livrés sans l'outil ; `precis` pour revenir), `ged.ocr.chaine.dpi=200` (300 auparavant) ; variables `GED_OCR_MODELES`, `GED_OCR_MODELES_ENTIERS_REPERTOIRE`, `GED_OCR_COMBINE_TESSDATA`, `GED_OCR_DPI`. **Avant / après** (40 pages, même exécution) : 5,35 → **2,90 s** par page et par cœur, 11,2 → **20,7 p/min/cœur** ; CER français propre 0,86 → 0,77 %, arabe propre 2,65 → 2,90 %, arabe dégradé 7,11 → 8,38 % (seuils 5 et 10 %). Banc réduit de qa rejoué (300 dpi fixé par le banc) : 5,21 → 2,98 s de CPU Tesseract. **Tests** : `ReglageDebitOcrTest` ×5 (défauts livrés — rouge sur `a8346ce` : `ged.ocr.modeles` absent, vérifié en remettant `application.yml` et `ProprietesChaineOcr` de la base —, `precis`, valeur inconnue refusée, valeur vide = défaut, repli sans outil, copie compactée lue par Tesseract) ; `ModelesEntiersTest` ×4 (conversion, `int_mode=1` et même architecture que le modèle livré, réutilisation, refait si la source change, aucun fichier de travail laissé, reconnaissance `ara+fra` réelle). Les cas réels sont ignorés sans `GED_TESSERACT` ; joués ici avec `/usr/bin/tesseract` : 27/27 verts avec `MoteurTesseractTest` et `ExtracteurDocumentOcrTest`. Documentation : `ESSAIS-DE-CHARGE.md` § 2.4 (tableaux, commande, réserves, projection calculée), synthèse et § 6 ; `DEPLOIEMENT.md` § 3.2 ; `EXPLOITATION.md` § 2 ; `.env.example`, `ged.env.exemple`. `combine_tessdata` ajouté à l'inventaire des appels sortants (`REVUE-SSRF.md`, `AppelsSortantsTest`). |

### Points pour pm

1. **R30 / P-14** : l'écart de 3 à 6 fois n'existe plus sur ce poste et ce corpus (2,90 s par page et
   par cœur, borne haute de la cible « 1 à 3 s »). Restent : le serveur de MMED (fréquence soutenue
   à mesurer en UAT), l'échantillon de la Phase 7 (Q09 : CER réel, en particulier à 200 dpi sur des
   corps de 8 pt, tampons, manuscrit). RISQUES.md (R30 : « `tessdata_fast` écarté », « worker à
   16 vCPU ») et la synthèse de SUIVI.md sont à mettre à jour par pm ; la demande de correction des
   §4.3.4 / §6.6 à MMED peut être revue. Projection calculée (non mesurée) : 20 000 pages en ~4 h sur
   4 cœurs de ce poste, contre ~7 h 25 avant.
2. **qa** : le banc réduit `recette/e10/banc-ocr-reduit.sh` fixe 300 dpi et les modèles du dépôt
   (`BancOcrReduit` construit lui-même le moteur) ; il ne mesure donc pas le réglage livré. Pour le
   rejouer avec les modèles compactés : `GED_TESSDATA=<copie compactée>` (2,98 s mesurés ici) ; pour
   le 200 dpi, `BancTesseractIT#reglagesDebit` (`GED_BANC_REGLAGES=best,best_int_200`). À revérifier.
3. **dev2** : `combine_tessdata` doit être présent sur le serveur à côté de `tesseract` (paquet
   `tesseract-ocr` sous Debian/Ubuntu ; à vérifier sur la distribution de MMED) ; sinon l'OCR démarre
   avec les modèles précis (avertissement au journal). Le registre et le SBOM ne changent pas (la
   copie compactée est dérivée des modèles inscrits). `deploiement/uat/demontrer-deploiement.sh` non
   modifié.
4. Le commit `5e573d0` (banc) référence `ModelesEntiers`, ajouté au commit suivant `1a0de56` : la
   branche compile à partir de `1a0de56` (historique non réécrit).

### Tests du tour 6

Suite back complète (`3b03465`, `mvn -B -q -o test` sur `ged_dev3_test`, sans `GED_MANAGEMENT_PORT` ni
`SERVER_PORT`, sans `GED_TESSERACT`) : **703 tests, 0 échec, 0 erreur, 3 ignorés (119 classes)** ; les
3 ignorés sont les cas à Tesseract réel de `ReglageDebitOcrTest` et `ModelesEntiersTest` (joués à part
avec `GED_TESSERACT=/usr/bin/tesseract` : verts). Nouveaux : `ReglageDebitOcrTest` ×5,
`ModelesEntiersTest` ×4. Une première passe (`1a0de56`) avait relevé 1 échec,
`AppelsSortantsTest.inventaireFerme` (processus `combine_tessdata` non inscrit), corrigé par `3b03465`.
Front non modifié (pas de build).

## Tour 7 de la mise en conformité (`ct/dev3-r7`, depuis `claude/inspiring-lovelace-10bg1c` @ `f50dc91`)

| Id | État | Commits | Cause, correctif, preuve |
|---|---|---|---|
| ANO-E6-003 (Mineure, P-14) | **Corrigée** | `4fc5b95` | **Cause** : `ModelesEntiers.preparerModele` écrivait la même marque `copie` quand `combine_tessdata` manquait et quand l'outil refusait un modèle (`osd`, sans réseau LSTM), puis réutilisait toute marque d'empreinte inchangée sans nouvel essai : après installation de l'outil, l'application restait sur les modèles précis et répétait l'avertissement « … indisponible ? ». **Correctif** : la marque porte le mode réellement obtenu — `entiers`, `non-convertible` (outil exécuté, modèle refusé : pas réessayé tant que la source ne change pas) ou `repli` (outil indisponible) ; disponibilité de l'outil sondée une fois au démarrage (`outilDisponible` : lancé sans argument, il rend 1 ; introuvable, il ne démarre pas, ou 126/127) ; une marque `repli` — ou `copie`, ancienne marque ambiguë, pour les répertoires de travail persistants déjà touchés — est **refaite dès que l'outil est là** ; avertissement distinct « (… indisponible) … conversion refaite au premier démarrage où l'outil sera présent » / « Aucun modèle OCR convertible en entiers par … ». **Tests** (`ModelesEntiersTest`, outil simulé par un script POSIX qui compacte tout sauf `osd` et note chaque conversion) : `outilRetabli` (démarrage sans outil → `repli` ; même répertoire, outil rétabli → `fra` converti, `osd` `non-convertible` ; 3e démarrage : rien de refait ; outil de nouveau absent : copie compactée conservée), `ancienneMarqueCopie` (marque `copie` d'une version précédente refaite), `disponibilite`. **Rouges sans le correctif** (ancien `ModelesEntiers` remis avec seulement `mode()` et `outilDisponible` ajoutés pour compiler) : `outilRetabli` « expected: <repli> but was: <copie> », `ancienneMarqueCopie` « expected: <[fra]> but was: <[]> ». **Sur le JAR** (phases B puis B3 de `recette/e10/recette-p14-ocr.sh` réduites au démarrage, vrai `/usr/bin/combine_tessdata`, même `GED_OCR_MODELES_ENTIERS_REPERTOIRE`) : B → avertissement « (/inexistant/combine_tessdata indisponible) », marques `ara`, `fra`, `eng`, `osd` à `repli`, réglage `repli` ; B3 → « Modèles OCR compactés en entiers … : [ara, eng, fra] », marques `entiers` (`osd` : `non-convertible`), réglage `entiers` ; 3e démarrage identique sans reconversion. Le script complet de qa (dépôt, OCR, recherche) n'a pas été rejoué ici (compte de recette et annuaire simulé de qa). |
| Observations qa (P-14) : mode de modèles et dpi | **Fait** | `46171b8` | Au démarrage, une ligne INFO « Réglage OCR : modèles `<entiers|precis|repli|installation>` (`<répertoire>`), pages PDF rendues à `<n>` dpi » (bean `ReglageOcr`, construit par `ConfigurationChaineOcr.reglage`, d'où le moteur tire son répertoire de modèles) ; `GET /api/v1/ocr/etat` rend les mêmes valeurs (nouveaux champs `modeles`, `dpi`, décrits dans `documentationapi/champs.yml`). `installation` = `ged.ocr.tessdata` vide (modèles de l'installation de Tesseract). **Tests** : `ReglageDebitOcrTest.modeEmploye` (precis, repli sans outil, installation), `ReglageDebitOcrTest.journalDuReglage` (ligne exacte au journal, dpi réglé à 300), `OcrApiTest.etat` (champs `modeles` = réglage du contexte, `dpi` = 200). Documentation : `DEPLOIEMENT.md` § 3.2, `EXPLOITATION.md` § 2, `ESSAIS-DE-CHARGE.md` § 2.4 (redémarrage suffisant après installation de l'outil, ligne de réglage, champs de l'état). Front non modifié (champs ajoutés ignorés par `recherche.model.ts`). |

### Points pour pm

1. **qa** : ANO-E6-003 à vérifier avec `recette/e10/recette-p14-ocr.sh` (phases B puis B3) ; attendu en
   B3 : « Modèles OCR compactés en entiers … » et marques `entiers` (`osd` : `non-convertible`). Les
   marques `copie` laissées par le JAR précédent sont aussi reprises (pas besoin de vider le répertoire).
   Le script peut désormais lire le réglage dans `/api/v1/ocr/etat` (`modeles`, `dpi`) plutôt que par l'espion.
2. Cas résiduel connu, non traité : un `combine_tessdata` présent mais qui refuse tous les modèles pour une
   autre raison (binaire défectueux qui démarre et rend un code autre que 126/127) les marque
   `non-convertible` ; l'avertissement le dit alors (« Aucun modèle OCR convertible en entiers par … »)
   et le contournement reste de vider le répertoire de travail après réparation.
3. Le test à outil simulé est ignoré sous Windows (script POSIX) ; les cas à Tesseract réel restent
   ceux du tour 6.

### Tests du tour 7

Suite back complète (`46171b8`, `mvn -B -q -o test` sur `ged_dev3_test`, sans `GED_MANAGEMENT_PORT` ni
`SERVER_PORT`, sans `GED_TESSERACT`) : **711 tests, 0 échec, 0 erreur, 3 ignorés (119 classes)** ; les 3
ignorés sont les cas à Tesseract réel (joués à part avec `GED_TESSERACT=/usr/bin/tesseract` :
`ModelesEntiersTest` 7/7, `ReglageDebitOcrTest` 7/7, `MoteurTesseractTest` 8/8,
`ExtracteurDocumentOcrTest` 10/10). Nouveaux : `ModelesEntiersTest` ×3, `ReglageDebitOcrTest` ×2,
`OcrApiTest.etat` étendu. Front non modifié (pas de build).

## Tour 10 de la mise en conformité (`ct/dev3-r10`, depuis `claude/inspiring-lovelace-10bg1c` @ `8ab6e19`)

| Id | État | Commits | Cause, correctif, preuve |
|---|---|---|---|
| ANO-F-037 (Mineure, F-58) | **Corrigée** | `f11a347`, `2e5ec77` | **Cause** : la date du document par défaut (`ServiceModeleDocument.appliquerAuDepot`, valeur initiale d'`UploadDocument.dateDocument`) était `LocalDate.now()`, donc le jour du **fuseau de la JVM**, alors que les échéances, le filtre « échéance dépassée » et l'alerte de 6 h prennent le jour de Casablanca (`Echeances.ZONE`). Revue de tous les `LocalDate.now(`, `LocalDateTime.now(`, `Year(Month).now(`, `ZoneId.systemDefault()` du code applicatif : deux autres usages métier dépendaient du même fuseau — les jetons « date », « heure », « mois », « jour », « année » de la charte de nommage (`IndexationService.composerDepuisCharte`, nom du document et aperçu) et la courbe « dépôts par jour » du tableau de bord (`StatsController.depots`, `ZoneId.systemDefault()`). Restent volontairement hors champ (techniques) : partitions mensuelles du journal d'audit en UTC (`TachesAudit`, `AuditService`), date de création XMP du PDF/A (`FabriquePdfA`, instant avec décalage, juste quel que soit le fuseau), instants `Instant.now()`. **Correctif** : `Echeances` devient la seule source de l'« aujourd'hui » métier (`aujourdhui(Clock)`, nouveau `maintenant(Clock)` = date et heure à Casablanca) ; pas d'horloge injectable commune dans le code (chaque classe prend `Clock.systemUTC()` et un réglage de test, cf. `AlertesEcheanceConservation.setHorloge`) : même schéma pour `ServiceModeleDocument` et `IndexationService` (`setHorloge`, paquet). `DEPLOIEMENT.md` § 3.3 : fuseau métier fixé dans le code, le fuseau du serveur (`TZ`, `-Duser.timezone`) n'a plus d'effet sur les dates métier ; seules les tâches techniques planifiées sans fuseau explicite suivent l'heure du serveur. **Tests** (horloge fixée au 03/10/2026 23 h 30 UTC = 04/10 0 h 30 à Casablanca) : `ModeleDocumentApiTest.dateParDefautDeCasablanca` (dépôt sans date : `GET /documents/{id}` → `dateDocument` 2026-10-04, base `date_document` 2026-10-04, `echeance_conservation` 2027-10-04 pour 12 mois depuis `DATE_DOCUMENT`) ; `IndexationControlesApiTest.jetonsALHeureDeCasablanca` (`POST /indexation/apercu` → `nomPropose` « 261004_003000_ACME_100 ») ; `JourMetierTest` (unitaire, JVM basculée sur `Etc/GMT+12` puis `Pacific/Kiritimati`, qui encadrent Casablanca : à tout instant l'un des deux est à une autre date qu'elle) : date initiale d'une fiche neuve et dernier jour de la courbe des dépôts = jour de Casablanca. **Rouges sans le correctif** (calculs remis à l'identique, réglages d'horloge conservés pour compiler) : 4/4 — « expected: <2026-10-04> but was: <2026-10-03> », `nomPropose` « 261003_234842_ACME_100 », « … sous Etc/GMT+12 : 2026-10-03 au lieu du jour de Casablanca 2026-10-04 » (×2). |

### Points pour pm

1. **qa2** : ANO-F-037 à vérifier sur l'instance de recette (JVM en UTC, `TZ` non positionné) : dépôt sans date du
   document entre 0 h et 1 h à Casablanca (23 h – 0 h UTC) → « Date du document » = « Date de création », échéance
   comptée depuis ce jour (QA2-NOTE 12 mois : 04/10/2026 → 04/10/2027).
2. Les documents déjà déposés avec la date de la veille ne sont **pas** corrigés (on ne sait pas distinguer une date
   saisie d'une date par défaut) ; sur l'instance de recette, seul le dépôt de l'essai de qa2 est concerné.
3. La copie `livraison-documentation/GED-Marchica-Med/DEPLOIEMENT.md` (figée depuis le premier commit) n'a pas été
   touchée ; seule `DEPLOIEMENT.md` à la racine est tenue à jour.

### Tests du tour 10

Suite back complète (`2e5ec77`, `mvn -B -q -o test` sur `ged_dev3_test`, sans `GED_MANAGEMENT_PORT` ni
`SERVER_PORT`, sans `GED_TESSERACT`) : **720 tests, 0 échec, 0 erreur, 3 ignorés (120 classes)** ; les 3
ignorés sont les cas à Tesseract réel (inchangés). Nouveaux : `JourMetierTest` ×2,
`ModeleDocumentApiTest.dateParDefautDeCasablanca`, `IndexationControlesApiTest.jetonsALHeureDeCasablanca`.
Front non modifié (pas de build).
