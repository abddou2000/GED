# Diagrammes de séquence des flux principaux (P-05, DAT §4.5)

Cinq flux : dépôt en deux temps, OCR asynchrone, recherche filtrée par les
droits, appel délégué d'une application, archivage. Les noms sont ceux des
classes du code (voir `CLASSES.md`) ; les tables, ceux du schéma
(`SCHEMA-BASE.md`). À tenir à jour à chaque changement de flux :
`SequencesDocumenteesTest` fait échouer la suite si une classe citée ici
disparaît ou change de nom.

Relu contre le code le 30/09/2026 (intégration `71bdc1d`) : chaîne des filtres
complétée (modules métier, journalisation) ; dépôt complété (source du dépôt
T-040, modèle documentaire §12.7, circuit de validation §12.8, dossier archivé) ;
OCR précisé (bail prolongé par page, reprise et échec définitif) ; recherche
complétée (canal, archives, échéance dépassée T-112) ; délégation : garde des
règles de workflow (D8) et décision D15 à venir.

Filtres communs à toute requête d'API (ordre d'exécution, `FilterRegistrationBean`) :
`FiltreContexteRequete` (traceId, adresse de confiance) → filtre des modules
métier (`ConfigurationModules` : 404 `MODULE_INACTIF` avant toute authentification
si le module de la route est désactivé, T-088) → chaîne de sécurité (`FiltreCleApi`
pour une clé d'API, `FiltreJwt` sinon) → `FiltreUtilisateurJournalisation`
(identité dans le MDC) → `FiltreConventionsApi` (64 Ko, pagination) →
`FiltreIdempotence` (créations) → contrôleur.

## 1. Dépôt en deux temps (§12.11, §5.3.1 `POST /documents`)

```mermaid
sequenceDiagram
  autonumber
  participant C as Client (interface ou application)
  participant F as Filtres (sécurité, idempotence)
  participant D as DepotController / DepotService
  participant S as DocumentService
  participant M as ServiceModeleDocument
  participant O as SourceDepot
  participant K as ControleFichiers + clamd
  participant X as StockageChiffre
  participant P as AccessPredicate
  participant W as ServiceCircuits
  participant I as IndexationAuDepot
  participant B as PostgreSQL
  participant A as AuditService
  C->>F: POST /api/v1/documents (multipart : file, metadonnees) + Idempotency-Key
  F->>B: réserver idempotence_cle (EN_COURS)
  F->>D: requête
  D->>D: lire les métadonnées (MetadonneesDepot, 64 Ko)
  rect rgb(235, 242, 250)
  Note over S,B: Temps 1 : transaction courte, tout ce qui peut échouer est contrôlé avant d'écrire le fichier
  D->>S: upload(fichier, type, métadonnées, objet, date du document)
  S->>P: exiger DEPOSER sur l'emplacement du type (404 / 403)
  S->>S: type vivant, dossier archivé : 409 DOSSIER_ARCHIVE
  S->>M: appliquerAuDepot : version du plan, valeurs par défaut, objet, date (§12.7)
  S->>K: taille, type réel (Tika), liste blanche, antivirus (échec fermé)
  K->>X: écrire le fichier chiffré (AES-256-GCM, cle_fichier)
  S->>O: courante() : canal, application, déposant, dépôt délégué (T-040)
  S->>B: INSERT document (statut_indexation provisoire), version_document n° 1 courante
  S->>W: ouvrirAuDepot : règle applicable figée dans la transaction (§12.8)
  S->>B: INSERT ocr_job (si texte à extraire)
  S->>A: événement DocumentDepose → DOCUMENT_DEPOSE (déposant, application, empreinte), même transaction
  end
  rect rgb(240, 248, 240)
  Note over I,B: Temps 2 : transaction séparée
  D->>I: indexer(document, valeurs)
  I->>B: INSERT document_index_valeur, statut_indexation = INDEXE
  I-->>D: type sans plan : SANS_PLAN, rien reçu ou échec : A_INDEXER (le temps 1 reste acquis)
  end
  D-->>F: 201 (ou 202 si OCR en attente), document et issue d'indexation
  F->>B: mémoriser la réponse (TERMINEE, 24 h)
  F-->>C: réponse, un rejeu identique renvoie la même, sans doublon
```

## 2. OCR asynchrone (§4.3.4)

```mermaid
sequenceDiagram
  autonumber
  participant W as TravailleurOcr (pool)
  participant Q as OcrJobQueuePostgres
  participant S as SourceFichierOcr (déchiffrement en flux)
  participant E as ExtracteurDocumentOcr
  participant T as Tesseract (processus)
  participant R as SearchIndexer
  participant B as PostgreSQL
  participant A as AuditService
  loop tant que la file n'est pas vide
    W->>Q: reserver(1, bail)
    Q->>B: UPDATE ocr_job … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED LIMIT 1)
    Q-->>W: job (document, version, cle_fichier, langue)
    W->>S: ouvrir(fichier) : déchiffrement en flux
    W->>E: extraire(contenu, type, langue ara+fra)
    loop chaque page
      alt couche texte PDF présente
        E->>E: PDFBox (page native)
      else page image
        E->>T: rendu 300 dpi, Tesseract LSTM, délai par page
      end
      W->>Q: prolonger le bail (bail perdu : résultat abandonné, un autre worker reprend)
    end
    alt succès
      W->>R: transaction : indexer (document_texte, tsvector)
      W->>Q: terminer (même transaction, refus = annulation)
      W->>A: événement ContenuIndexe → CONTENU_INDEXE (délai dépôt → disponibilité)
      W->>W: MetriquesOcr : délai mesuré contre l'objectif (24 h, D6)
    else échec transitoire
      W->>Q: echouer : reprise programmée (3 tentatives : 1, 5 puis 30 min)
    else échec définitif (fichier corrompu, tentatives épuisées)
      W->>Q: echouer : OCR_ECHEC
      W->>A: événement OcrEnEchec → OCR_ECHEC (document « non interrogeable »)
    end
  end
```

## 3. Recherche filtrée par les droits (§4.4, §5.3.1 `POST /recherches`)

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant K as ContratApiController / ServiceContratApi
  participant I as IndexationService
  participant P as AccessPredicate
  participant R as SearchIndexerPostgres
  participant B as PostgreSQL
  C->>K: POST /api/v1/recherches {texte, criteres, noeudId, typeDocumentId, canal, deposeDu, deposeAu, archives, echeanceDepassee, page, taille}
  opt critères d'index
    K->>I: rechercher(critères)
    I->>P: spécification « documents consultables » (droits à la source)
    I->>B: documents candidats et valeurs d'index
    I-->>K: identifiants retenus
  end
  alt texte présent
    K->>R: rechercher(texte, filtres CriteresMetadonnees : nœud, type, canal, dates, archives, échéance, page, taille)
    R->>P: prédicat SQL des droits (emplacements, confidentialité)
    R->>B: websearch_to_tsquery, ts_rank_cd, ts_headline, LIMIT / OFFSET, total sur le périmètre
    R-->>K: page de résultats avec extraits en segments
  else critères seuls
    K->>K: bornes de dépôt, tri en liste blanche, pagination
  end
  K-->>C: {resultats, total, page, taille} (total limité au périmètre autorisé)
```

## 4. Appel d'une application pour le compte d'un utilisateur (§5.4, §5.5)

```mermaid
sequenceDiagram
  autonumber
  participant App as Application cliente
  participant F as FiltreCleApi
  participant Q as QuotasCleApi
  participant D as ResolveurDelegationAnnuaire
  participant L as Annuaire (LDAPS)
  participant P as AccessPredicate
  participant H as SourceHabilitationsApplications
  participant Svc as Service métier
  participant A as AuditService
  App->>F: requête + X-API-Key + X-On-Behalf-Of: identifiant
  F->>F: format, empreinte SHA-256 (temps constant), environnement, révocation, expiration
  F->>F: application active, adresse source autorisée
  F->>Q: consommer (600 / min, 100 000 / jour) — sinon 429 + Retry-After
  F->>D: clé autorisée à déléguer ? adresses déclarées ?
  D->>D: identité GED connue ?
  opt identité jamais connectée
    D->>L: rechercher par identifiant (compte de service)
  end
  D->>L: userAccountControl seul, par objectGUID (D15 ; cache court ≤ 5 min)
  alt compte désactivé (bit 0x2), absent ou état illisible
    D-->>F: 422 IDENTITE_DELEGUEE_INVALIDE (motif au journal CLE_API_REFUSEE, rien provisionné)
  else compte actif
    opt identité jamais connectée
      D->>D: provisionner sans rôle (cache_annuaire)
    end
  end
  D-->>F: principal de l'utilisateur délégué (sinon 422 IDENTITE_DELEGUEE_INVALIDE)
  Note over D,L: Décision D15 (30/09) : lecture de userAccountControl, compte désactivé = 422, à livrer par dev1 (T-055)
  F->>F: contexte de sécurité : sujet = la clé, principal = l'utilisateur
  F->>Svc: requête
  Note over F,Svc: Avant le contrôleur, GardeDroitsRequetes (intercepteur), règles de workflow (D8) : GardeReglesWorkflowApplications exige délégation, portée WORKFLOW_PILOTAGE et GERER_REFERENTIELS de la personne
  Svc->>P: décision (permission, objet)
  P->>H: attributions du sujet
  alt lecture (GET, HEAD)
    H->>P: droits de la clé ∩ droits de l'utilisateur, nœud par nœud
  else écriture
    H->>P: portée de la clé (cle_api_portee)
  end
  Svc->>A: événement : acteur_utilisateur_id = délégué, acteur_application_id = application
  Svc-->>F: réponse
  F->>A: APPEL_API (méthode, chemin, statut, clé)
  F-->>App: réponse
```

## 5. Archivage d'un document (§12.6, §6.1.4)

```mermaid
sequenceDiagram
  autonumber
  participant U as Agent d'archive
  participant C as CycleDeVieController
  participant S as ArchivageService
  participant P as ControleAcces
  participant V as CopiesConservation
  participant LO as LibreOffice (processus)
  participant VP as veraPDF
  participant X as StockageChiffre
  participant B as PostgreSQL
  participant A as AuditService
  U->>C: POST /api/v1/documents/{id}/archivage
  C->>S: archiver(id)
  S->>P: exiger ARCHIVER sur le document (404 / 403)
  rect rgb(245, 245, 235)
  Note over S,X: Phase 1 hors transaction : contrôles et conversion
  S->>S: document ni verrouillé, ni en corbeille, ni déjà archivé
  S->>V: produire(version courante)
  V->>X: déchiffrer la version (tmpfs)
  alt déjà PDF/A valide
    V->>VP: valider
  else
    V->>LO: conversion PDF/A-2 (profil jetable)
    V->>VP: valider la copie
  end
  V->>X: stocker la copie chiffrée (cle_fichier)
  end
  rect rgb(235, 242, 250)
  Note over S,B: Phase 2 dans une transaction : état revérifié sous verrou
  S->>B: document verrouillé (SELECT … FOR UPDATE), statut ARCHIVE, copie_conservation
  S->>A: DOCUMENT_ARCHIVE (empreinte, copie VALIDE ou ANOMALIE)
  end
  S-->>C: issue ARCHIVE (copie validée) ou ANOMALIE (original seul, à reprendre)
  C-->>U: 200
```
