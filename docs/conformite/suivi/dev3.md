# Suivi dev3 — fichiers, OCR, recherche, cycle de vie

Branche `ct/dev3`, copie `C:\Users\abdou\ged-wt\dev3`.

## Lot en cours : E5 — Stockage sécurisé des fichiers

Livré en **composants autonomes** (paquet `com.ipt.ged.fichier`), testés, **non encore
branchés** sur le flux de dépôt : dev1 migre les entités vers PostgreSQL / Liquibase / UUID.
Aucune entité, migration existante ni identifiant n'a été modifié. `StorageService` reste en
place et sert toujours le dépôt actuel.

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
`IV (12) ‖ DEK chiffrée (32) ‖ étiquette (16)`, AAD = `"GED-DEK-v1" | kek_id | identifiant du fichier`
(une enveloppe recopiée sur la ligne d'un autre fichier ne s'ouvre pas).

**KEK** : alias `kek-00001`, `kek-00002`… dans le keystore PKCS#12 ; la plus récente est active,
les autres désactivées (désenveloppement seul). Rotation = nouvelle KEK + réenveloppement des DEK
par lots de 500 (mise à jour conditionnelle, reprenable), **aucun fichier rechiffré** (testé :
octets `.enc` identiques avant/après). Retrait d'une KEK refusé tant qu'une DEK la référence.

### Modèle de données proposé (à intégrer par dev1)

Changesets dans `backend/src/main/resources/db/changelog/a-integrer/` (hors changelog maître) :

1. `202609261200_cle_fichier.xml` — table `cle_fichier` :
   `id uuid PK (pk_cle_fichier)` = identifiant du fichier `aa/bb/<id>.enc`, `dek_enveloppee bytea`,
   `kek_id varchar(64)` (`idx_cle_fichier_kek_id`), `algorithme varchar(32)` défaut `AES-256-GCM`
   (`ck_cle_fichier_algorithme`), `cree_le`, `modifie_le` (dernier réenveloppement) ; droits
   `ged_app` (SELECT/INSERT/UPDATE/DELETE) si le rôle existe, `ged_readonly` exclu.
2. `202609261205_version_document_fichier.xml` — sur `version_document` (expand) :
   `fichier_id uuid` (`fk_version_document_fichier_id` → `cle_fichier.id`, `uk_version_document_fichier_id`),
   `empreinte char(64)` (`ck_version_document_empreinte` : hex minuscule), `type_mime varchar(127)`,
   `taille_octets bigint` (`ck_version_document_taille_octets`). Nullables jusqu'à la fin de la
   reprise ; un changeset « contract » (NOT NULL, retrait de `file_path`) suivra.

Vérifié : `update`, `rollback` (3 changesets) puis `update` à nouveau, sur PostgreSQL 16, base
`ged_dev3` (table `version_document` minimale créée pour l'essai), avec liquibase-maven-plugin
4.29.2 hors du dépôt.

Pas d'entité JPA pour `cle_fichier` : `DepotClesFichierJdbc` (JdbcTemplate, requêtes paramétrées)
suffit et n'interfère pas avec le modèle de dev1. Record métier : `com.ipt.ged.fichier.cles.CleFichier`.
Côté `version_document`, champs à ajouter à l'entité de dev1 :

```java
@Column(name = "fichier_id") private UUID fichierId;          // cle_fichier.id
@Column(name = "empreinte", length = 64) private String empreinte;
@Column(name = "type_mime", length = 127) private String typeMime;
@Column(name = "taille_octets") private Long tailleOctets;
```

### Plan de branchement (quand le lot dev1 sera intégré)

1. **`DocumentService.upload` et `ajouterVersion`** : remplacer `ContraintesDepot.valider` (format
   par extension) + `storage.store` par
   `controleFichiers.deposer(SourceFichier.de(file), controleFichiers.regles(type.getTailleMaxMo(), type.formatsAutorises()))`,
   puis renseigner sur la version `fichierId`, `empreinte`, `typeMime`, `tailleOctets`.
   `ContraintesDepot.validerTypeVivant` reste. La compensation `supprimerSiTransactionAnnulee`
   appelle `stockageChiffre.detruire(fichierId)` (clé + fichier) au lieu de `storage.supprimer`.
   L'enregistrement dans `cle_fichier` participe alors à la transaction du dépôt.
2. **Téléchargement** (`DocumentController`) : `StreamingResponseBody` sur
   `stockageChiffre.lire(version.getFichierId())` au lieu de `StorageService.load` (Resource disque).
3. **Aperçu d'indexation** (`IndexationService`) : `controleFichiers.controler(...)` (mêmes règles
   que le dépôt, sans écriture).
4. **OCR** (`OcrService`, lot E6) : lit aujourd'hui un `Path` en clair (`storage.chemin`). À passer
   sur `stockageChiffre.lire(...)` déchiffré en mémoire ou dans un `tmpfs` (§6.1.3).
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
   `chemin_relatif;fichier_id;empreinte_sha256;taille_octets;type_mime;kek_id;statut`.
   Reprenable : les lignes `OK` déjà présentes sont ignorées. Aucun original supprimé.
4. Appliquer le rapport en base :
   ```sql
   CREATE TEMP TABLE reprise (chemin_relatif text, fichier_id uuid, empreinte char(64),
       taille_octets bigint, type_mime varchar(127), kek_id varchar(64), statut text);
   \copy reprise FROM 'reprise-fichiers.csv' WITH (FORMAT csv, DELIMITER ';', HEADER true)
   UPDATE version_document v SET fichier_id = r.fichier_id, empreinte = r.empreinte,
          taille_octets = r.taille_octets, type_mime = r.type_mime
     FROM reprise r WHERE r.statut = 'OK' AND v.file_path = r.chemin_relatif;
   SELECT count(*) FROM version_document WHERE fichier_id IS NULL;  -- doit valoir 0
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
- **Liquibase** : changesets validés manuellement sur PostgreSQL 16 (`ged_dev3`), pas dans
  `mvn test` (Liquibase n'est pas encore au `pom.xml`).

### Tests

Paquet `com.ipt.ged.fichier` : 101 tests (FileStore, chiffrement segmenté et 10 cas d'altération,
flux de 64 Mo, keystore, rotation sur 1 203 DEK, destruction cryptographique, dépôt JDBC,
Tika sur les 12 formats et les leurres, faux clamd, chaîne de contrôle et ordre des refus,
intégrité, prévisualisation service + API, reprise, codes HTTP, garde-fous de configuration).

### Reste à faire / dépendances

- Branchement (plan ci-dessus) après intégration du lot dev1 : sur demande de pm.
- veraPDF et copie de conservation PDF/A-2 (§6.1.4, E7).
- Sonde de santé ClamAV (`AnalyseurAntivirus.disponible()` prête) : à brancher par dev2 avec les
  autres sondes (6.7).

### Points bloquants

Aucun.
