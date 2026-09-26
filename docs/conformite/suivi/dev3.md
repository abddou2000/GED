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

### Plan de branchement E5 (vague 2)

1. **`DocumentService.upload` et `ajouterVersion`** : remplacer `ContraintesDepot.valider` (format
   par extension) + `storage.store` par
   `controleFichiers.deposer(SourceFichier.de(file), controleFichiers.regles(type.getTailleMaxMo(), type.formatsAutorises()))`,
   puis renseigner sur la version `cleFichierId`, `empreinte`, `typeMime`, `tailleOctets`.
   `ContraintesDepot.validerTypeVivant` reste. La compensation `supprimerSiTransactionAnnulee`
   appelle `stockageChiffre.detruire(fichierId)` (clé + fichier) au lieu de `storage.supprimer`.
   L'enregistrement dans `cle_fichier` participe alors à la transaction du dépôt.
2. **Téléchargement** (`DocumentController`) : `StreamingResponseBody` sur
   `stockageChiffre.lire(version.getFichierId())` au lieu de `StorageService.load` (Resource disque).
3. **Aperçu d'indexation** (`IndexationService`) : `controleFichiers.controler(...)` (mêmes règles
   que le dépôt, sans écriture).
4. **OCR** : la chaîne E6 lit déjà le fichier par `StockageChiffre.lire` (bean `SourceFichierOcr`),
   déchiffré en mémoire ; enfiler le job dans la même transaction (plan E6 ci-dessous).
5. **Prévisualisation** : implémenter `ResolveurFichierVersion` sur `version_document`, puis
   `ged.fichiers.previsualisation.api-active=true`. Le lot autorisation remplace
   `ControleAccesPrevisualisationProvisoire` (bean `controleAccesPrevisualisation` de
   `ConfigurationFichiers`) par un appel au point unique de droits (hors périmètre → 404).
   Angular : visionneuse sur `GET /api/v1/versions/{id}/apercu` (blob), repli sur le
   téléchargement si 415 `APERCU_NON_DISPONIBLE` ou 503 `CONVERSION_INDISPONIBLE`.
6. **Intégrité** : implémenter `SourceEmpreintes` (parcours paginé de `version_document`), puis
   `ged.fichiers.integrite.verification-planifiee=true`.
7. **Purge définitive** (E7, §12.5) : supprimer les lignes métier puis
   `stockageChiffre.detruire(fichierId)` et `servicePrevisualisation.invaliderCache(fichierId)`.
8. **Audit** (dev2) : écouter `ControleFichiers.FichierInfecte`,
   `VerificationIntegrite.AnomalieIntegrite` (+ alerte supervision) et
   `PrevisualisationController.ApercuConsulte` (événement distinct du téléchargement).
9. Après reprise en production : changeset « contract », suppression de `StorageService` et de
   `ged.storage.root`.

### Procédure de reprise des fichiers existants (en clair → chiffré)

Outil : `RepriseFichiersEnClair` (+ `LanceurReprise`, activé par `ged.fichiers.reprise.source`).

1. Arrêter l'accès utilisateurs ; sauvegarder la base et `ged.storage.root`.
2. Créer le keystore de l'environnement (une fois, `creer-si-absent=true` puis retiré) et le
   **sauvegarder** avec sa phrase secrète (coffre de secrets).
3. Lancer :
   `java -jar ged.jar --spring.profiles.active=prod --spring.main.web-application-type=none --ged.fichiers.reprise.source=/srv/ged/storage/ged --ged.fichiers.reprise.rapport=/srv/ged/reprise-fichiers.csv`
   (option `--ged.fichiers.reprise.antivirus=true` pour analyser aussi le fonds historique).
   Pour chaque fichier : type réel (Tika), chiffrement sous une nouvelle DEK, empreinte,
   **relecture complète de contrôle**, ligne CSV
   `chemin_relatif;fichier_id;empreinte_sha256;taille_octets;type_mime;kek_identifiant;statut`.
   Reprenable : les lignes `OK` déjà présentes sont ignorées. Aucun original supprimé.
4. Appliquer le rapport en base :
   ```sql
   CREATE TEMP TABLE reprise (chemin_relatif text, fichier_id uuid, empreinte char(64),
       taille_octets bigint, type_mime varchar(127), kek_identifiant varchar(64), statut text);
   \copy reprise FROM 'reprise-fichiers.csv' WITH (FORMAT csv, DELIMITER ';', HEADER true)
   UPDATE version_document v SET cle_fichier_id = r.fichier_id, empreinte = r.empreinte,
          taille_octets = r.taille_octets, type_mime = r.type_mime
     FROM reprise r WHERE r.statut = 'OK' AND v.file_path = r.chemin_relatif;
   SELECT count(*) FROM version_document WHERE cle_fichier_id IS NULL;  -- doit valoir 0
   ```
   (les lignes `cle_fichier` sont déjà écrites par l'outil).
5. Lancer une vérification d'intégrité complète, traiter les lignes en échec.
6. Seulement ensuite : effacer l'ancien stockage en clair (outil d'effacement du support ; sur
   SSD, l'effacement logique ne garantit pas la destruction : prévoir le chiffrement du volume),
   puis changeset « contract ».

Testé sur un dossier d'exemple (`<espace>/<uuid>.<ext>` : pdf, docx, png, txt) : empreintes
égales au SHA-256 des originaux, contenu relu à l'identique, originaux intacts, idempotence,
fichier infecté et fichier trop gros signalés sans interrompre la reprise.

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

- Branchement (plan ci-dessus) : vague 2, sur demande de pm.
- veraPDF et copie de conservation PDF/A-2 (§6.1.4, E7).
- Sonde de santé ClamAV (`AnalyseurAntivirus.disponible()` prête) : à brancher par dev2 avec les
  autres sondes (6.7).

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

### Métriques (Micrometer)

`ocr_delai_disponibilite` (timer, seuil d'histogramme à l'objectif, 95e centile),
`ocr_delai_disponibilite_objectif_secondes` (86 400), `ocr_delai_objectif_depasse` (compteur),
`ocr_file_profondeur`, `ocr_file_age_plus_ancien_secondes`, `ocr_jobs{issue}`. Règle d'alerte
proposée à dev2 : `ocr_file_age_plus_ancien_secondes > 0.8 * ocr_delai_disponibilite_objectif_secondes`.
Exposition : l'actuator ne publie aujourd'hui que `health` (dev2).

### Plan de branchement E6 (vague 2, avec E5)

1. **Dépôt** (`DocumentService.upload` / `ajouterVersion`, temps 1 du §12.11) : après l'écriture
   chiffrée E5, dans la même transaction,
   `ocrJobQueue.enfiler(new NouveauJob(doc, version, cleFichierId, typeMime, languesOcr.pour(type.getCode()), maintenant))` ;
   réponse **HTTP 202** avec `EN_ATTENTE_OCR` (API d'intégration, dev2).
2. `ged.ocr.chaine.actif=true` (workers, jauges, API recherche et supervision) ;
   `ged.ocr.chaine.workers` selon les cœurs du serveur.
3. **Restauration d'une version** (`restaurerVersion`) : enfiler un job pour la version restaurée
   (l'index porte sur la version courante).
4. **Purge** (E7) : les lignes `ocr_job` et `document_texte` partent en cascade avec le document.
5. **Recherche** : le lot autorisation remplace `PredicatDroitsProvisoire` (bean `predicatDroits`
   de `ConfigurationChaineOcr`) ; critères de métadonnées ajoutés en `FragmentSql` ; tri par date,
   type ou nom par jointure sur `document` ; Angular : écran de recherche avec extraits (segments)
   et mention « contenu non interrogeable » (via `statutsParVersion`).
6. **Supervision** : `/api/v1/admin/ocr/*` et `/api/v1/admin/recherche/reindexation` à restreindre
   au profil Administrateur (lot autorisation) ; écran Angular de supervision.
7. **Cloisonnement 4.3.3** : appliquer `docs/conformite/correctifs/E6-retrait-preremplissage-ocr.patch`
   (`git apply`). Il retire de `IndexationService` la lecture du contenu dans l'aperçu et
   l'analyse (`lireSansDeposer`, `ocr.lire`, `valeurs.deduire`, `manqueUnChamp`, dépendances
   `OcrService` et `ExtracteurValeurs`) et n'y laisse que le nom de fichier ; adapte les tests 5
   et 6 d'`OcrApiTest` (qui décrivaient le pré-remplissage) ; retire du front l'encart « Contenu
   lu » et l'étiquette « lu dans le document » (`document-upload.html`). Ensuite : supprimer
   `ExtracteurValeurs`, `LecteurPositionnel`, `LectureTsv`, `ged.ocr.langue` / `pages-max` et
   l'extraction synchrone.

### Tests E6 (PostgreSQL 16, schéma jetable portant tout le changelog maître)

50 tests : file (11 : 6 workers concurrents sur 300 jobs sans doublon, SKIP LOCKED sans attente,
reprises 1/5/30 min, échec définitif, bail expiré, relance), worker de bout en bout (7 : dépôt →
interrogeable, métriques, objectif 24 h dépassé, échecs, bail perdu → rien d'indexé, pool de 3
workers), extraction (10, moteur simulé : PDF mixte, seuil, 40 pages, TIFF, natifs, motifs),
Tesseract réel (7 : arabe, bilingue, PDF scanné, délai, langue), recherche (15 : normalisation,
français, arabe, mixte, websearch, pertinence et pagination, extraits, droits, réindexations,
800 pages). Suite complète : **307 tests, 0 échec** (`DB_NAME=ged_dev3 mvn test`).

### Vérifié partiellement

- **Tesseract** : réel, mais sur scans **générés** (police propre) ; qualité sur le corpus MMED
  non mesurée (protocole 4.3.2 à exécuter à réception de l'échantillon). Les tests Tesseract sont
  ignorés sur un poste sans binaire (`GED_TESSERACT`).
- **Débit et 24 h** : aucun essai de charge (800 pages scannées réelles) ; délai mesuré sur des
  dépôts simulés.
- **Métriques** : vérifiées dans un `SimpleMeterRegistry`, pas dans Prometheus.

### Points bloquants

Aucun. À signaler à dev1 et pm : `preparer-base.sql` crée maintenant l'extension `unaccent`
(à rejouer sur les bases existantes avant la prochaine montée Liquibase).
