# Plan des vagues — mise en conformité technique

Auteur : pm. Base : `FEUILLE-DE-ROUTE.md` (dépendances E0 à E11), `SUIVI.md` (141 lignes),
`INTEGRATION.md` (procédure de fusion), `DECISIONS-REVUE-TECHNIQUE.md` (revue client : une
décision actée prime sur le dossier V3). Pour le reste, le PDF fait foi.

## 1. Principes retenus

1. **Respecter les dépendances de données, pas l'enchaînement strict des étapes.** La feuille
   de route enchaîne E1 → E2 → E3 → E4 → E5 → E6 (31 semaines) avec l'hypothèse « deux back, un
   front ». L'équipe compte trois développeurs back-end : on parallélise tout ce qui ne dépend
   que d'un **contrat** (interface Java, table, code d'erreur), en livrant ce contrat tôt.
2. **Amorces.** Quand un lot a besoin d'un contrat d'un autre lot de la même vague, le
   propriétaire du contrat le livre en premier commit (interface, énumération, changeset,
   implémentation par défaut) ; pm le fusionne seul dans `conformite-technique` (« fusion
   d'amorce », J+2 ou J+3) et les autres membres font `git merge conformite-technique`.
3. **Un fichier chaud = un propriétaire par vague.** Les autres membres ne le modifient pas ;
   ils passent par une extension prévue (interface, sous-classe, écouteur d'événement).
4. **Les principes de la feuille de route restent vrais à la fusion** : les droits (E3) sont
   fusionnés avant que la recherche plein texte (E6) ne soit livrée, et chaque fonction livrée
   après la vague 2 produit ses événements d'audit dès sa première version.
5. **qa vérifie la vague précédente pendant la vague en cours** sur `conformite-technique`
   (passage « Livré » → « Vérifié ») et prépare les scripts de recette de la vague suivante.

## 2. Modifications apportées à la proposition de départ, et pourquoi

| # | Proposition | Plan retenu | Raison |
|---|---|---|---|
| M1 | E9 socle API (problem+json, codes) en vague 3 par dev2 | **Format problem+json, classe `ExceptionMetier` et catalogue des codes avancés en tout début de vague 2** (amorce J+2) ; Idempotency-Key reste en vague 3 | En vague 2, dev1 crée les erreurs d'authentification (401, 429) et dev3 les erreurs de fichier (413, 415, 422 `FICHIER_INFECTE`). Si le format change en vague 3, tout est à reprendre et `GlobalExceptionHandler` est modifié par trois personnes à la fois. Avec l'amorce, dev1 et dev3 lèvent des sous-classes d'`ExceptionMetier` et ne touchent plus jamais au gestionnaire. |
| M2 | E4 journal d'audit en vague 2, sans précision | E4 en vague 2, avec **amorce `AuditService` + énumération des codes d'événements (J+2)**, et **écoute des événements Spring Security** pour les connexions | E4 dépend de E3 dans la feuille de route (écran réservé Administrateur et DG). Le cœur (table, déclencheurs, scellement, export) n'en dépend pas ; la garde de l'écran est branchée en vague 3 par une permission `CONSULTER_AUDIT`. Les événements `AuthenticationSuccessEvent` et `AbstractAuthenticationFailureEvent` évitent que dev2 modifie le code d'authentification de dev1. |
| M3 | dev3 « branchement E5 + reprise » en vague 2 | Idem, **plus P-13 (rapprochement base-fichiers), sondes fichiers et ClamAV, plafond 200 Mo, et extraction du pipeline de dépôt dans un paquet `depot`** | Le pipeline de dépôt sera réécrit trois fois (E5, E6, 12.11) : l'isoler dès la vague 2 évite que `DocumentService` (379 lignes) soit un point de conflit permanent avec dev1 (versions, verrou, déplacement en vague 4). |
| M4 | 12.11 (dépôt en deux temps) et « dépôt avec métadonnées » en E7 et E9 | **Avancés en vague 3 chez dev3**, avec E6 | Le temps 1 écrit le fichier chiffré, le document et le job OCR dans une même transaction et répond 202 : c'est le cœur d'E6. Les confier à un autre membre en vague 4 ferait réécrire le même service par deux personnes. |
| M5 | Rattachements en E7 (vague 4, dev1) | **Table `document_rattachement` et union des droits en vague 3 (E3, dev1)** ; points d'entrée REST en vague 4 | La fonction de décision `peut()` est une union sur les emplacements, rattachements compris (12.2.3) ; la recherche de dev3 (vague 3) interroge les emplacements par `EXISTS`. La table doit exister avant les deux. |
| M6 | dev2 = E9 clés API et délégation en vague 4 | **Cycle de vie des clés, filtre `X-API-Key`, quotas, adresses, Idempotency-Key en vague 3** ; portée (`cle_api_portee` branchée sur `AccessPredicate`) et délégation en vague 4 | En vague 3, dev2 n'aurait sinon qu'Idempotency-Key (le format d'erreur étant avancé en M1) pendant les 4 semaines d'E3. La portée, elle, a besoin du moteur d'E3 : elle passe par un point d'extension `SourceHabilitations` que dev1 prévoit en E3, dev2 n'ajoutant qu'une implémentation (nouveau fichier). |
| M7 | dev3 = E7 cycle de vie **puis E8** en vague 4 | **E8 déplacé en vague 5 et partagé** : dev1 = workflow (circuits, décisions, statut) ; dev3 = conservation et notifications | Le calcul du statut repose sur la version courante (12.8), que dev1 refond en vague 4 : le workflow ne peut pas démarrer avant. Le workflow (paquets `workflow`, `signature`) et les notifications sont indépendants en fichiers et se parallélisent bien. |
| M8 | Vague 5 = E8 restant, finitions, E11 | **Vague 5 = E8 + finitions E9/E10 ; vague 6 = E11 recette** | La recette de conformité doit porter sur un code gelé ; la mêler aux derniers développements rend la matrice instable. |
| M10 | — (revue client D8) | **Nouvelle étape E8-API, placée entre E8 et E9, en vague 5** : dev1 conçoit les points d'entrée du workflow « API d'abord » (les mêmes pour le front et pour l'intranet, T-007) et en livre le contrat en amorce J+3 ; dev2 les ouvre aux applications tierces (opérations de workflow dans la portée des clés, délégation pour les décisions, Idempotency-Key, audit à double identité, OpenAPI, test de contrat) | Le pilotage par API dépend du nouveau modèle de circuit (E8) et de la délégation (E9, vague 4). Deux API distinctes pour le front et l'intranet violeraient l'exigence d'API unique. |
| M9 | — | **Choix de conception imposés en vague 1-2 pour éviter les conflits futurs** : filtre MDC hors de `SecurityConfig` (dev2), helper de test `support/Comptes.java` à signature stable (dev1), un sous-arbre YAML par membre, un fichier de changeset par évolution | Voir `INTEGRATION.md` §3. |

Durée estimée : **environ 18 semaines** (vagues de 3, 3, 4, 4, 3 et 2 semaines) contre 31 en
enchaînement strict, hors attente des informations de MMED (voir `RISQUES.md`).

## 3. Vue d'ensemble

| Vague | Durée | dev1 | dev2 | dev3 | qa | Amorce |
|---|---|---|---|---|---|---|
| 1 (en cours) | 3 sem. | E1 socle de données | E0 outillage, E10 exploitation, logs E4 (7.1, 7.3.1) | E5 composants autonomes | Ligne de base, plan de recette, scripts | — |
| 2 | 3 sem. | E2 identité et sessions | M1 problem+json, puis E4 journal d'audit | E5 branchement, reprise des fichiers, prévisualisation, P-13 | Recette vague 1 ; scripts E2, E4, E5 | dev2 J+2 : `ExceptionMetier`, `AuditService` |
| 3 | 4 sem. | E3 autorisation, confidentialité, rattachements (table) | E9 : Idempotency-Key, clés API (cycle de vie, filtre, quotas) ; finitions E4 | E6 OCR asynchrone, plein texte, 12.11, dépôt avec métadonnées | Recette vague 2 ; scripts E3, E6 | dev1 J+3 : `AccessPredicate`, `ControleAcces`, `Sujet`, `SourceHabilitations` |
| 4 | 4 sem. | E7 modèle : types, index booléen, JSONB validé, versions (bascule automatique, D9), verrou, rattachements REST, déplacement, renommage, socle commun, espace de partage (R-03) | E9 : portée, délégation, source du dépôt, 5.2 ; E10 : restauration | E7 cycle de vie : purge, archivage PDF/A, export ZIP | Recette vague 3 ; scripts E7, E9 | dev1 J+2 : colonnes de cycle de vie, `GardeEcriture` |
| 5 | 3 sem. | E8 workflow parallèle, points d'entrée « API d'abord » | **E8-API (R-01, R-02)** ; finitions E9 (contrat, OpenAPI, 64 Ko, Deprecation) et E10 ; P-11 | E8 conservation et notifications | Recette vague 4 ; scripts E8 et E8-API | dev3 J+2 : `NotificationService` ; dev1 J+3 : contrat REST du workflow |
| 6 | 2 sem. | Corrections de recette | Corrections, documentation d'installation, P-12, P-14 | Corrections, protocole OCR si échantillon reçu | **E11 : rejeu des 137 lignes** | — |

Chemin critique : E1 → E2 → E3 → E7 (modèle) → E8 (workflow), porté par dev1 (48 lignes, 17
semaines). dev1 ne doit recevoir aucune tâche hors de ce chemin ; toute finition transverse va
à dev2.

## 4. Détail par vague

### Vague 1 — en cours

| Membre | Lot | Paquets et fichiers touchés |
|---|---|---|
| dev1 | E1 : PostgreSQL, Liquibase, UUID, trois rôles, amorçage en changesets, `supprime_par`/`supprime_le`, colonne `metadonnees` JSONB, script de reprise MySQL | `backend/pom.xml` (dépendances base), `application*.yml` (`spring.datasource`, `spring.jpa`, `spring.liquibase`), `db/changelog/**` (création), `db/migration/**` (suppression), **toutes les entités et tous les DTO** (UUID), seeders, dépôts, tests, `frontend/src/app/**` (types d'identifiants) |
| dev2 | E0 : CI, OWASP Dependency-Check, SBOM, profil `uat` ; E10 : NGINX, systemd, script de déploiement, Prometheus, sauvegarde ; 7.1 et 7.3.1 | `backend/pom.xml` (plugins, `micrometer-registry-prometheus`), `application*.yml` (`logging`, `management`), `application-uat.yml` (création), `logback-spring.xml`, nouveau paquet `journalisation` (filtre MDC), `deploiement/**`, fichier de CI à la racine, `frontend/package.json` (script SBOM) |
| dev3 | E5 composants autonomes : `FileStore`, chiffrement AES-256-GCM, `KeyProvider` PKCS#12, SHA-256, Tika, client ClamAV, convertisseur de prévisualisation | `backend/pom.xml` (Tika, éventuellement JODConverter), nouveaux paquets `stockage`, `controle`, `previsualisation`, `application*.yml` (`ged.stockage`, `ged.antivirus`) |
| qa | Ligne de base, plan de recette, scripts | `docs/conformite/recette/**`, scripts de recette |

**Risques de conflit.** Élevé entre dev1 et tous : le passage aux UUID modifie toutes les
entités et le front. dev3 doit coder ses composants **sans dépendre des entités** (identifiants
passés en `UUID`, aucune entité JPA dans `stockage`) ; dev2 ne touche à aucune entité.
`pom.xml` et `application.yml` : conflits d'union, résolus par pm.

**Ordre de fusion** : dev2 (outillage, pour que la CI et les contrôles valident les fusions
suivantes) → dev1 (socle) → dev3 (compile contre le socle) → qa.

**Critère de sortie** : base vierge créée par Liquibase, rollback testé, `mvn test` vert sur
PostgreSQL, build Angular vert, SBOM et rapport OWASP produits.

### Vague 2

| Membre | Lot | Paquets et fichiers touchés |
|---|---|---|
| dev1 | E2 : LDAPS search-then-bind par `sAMAccountName` seul (D2), provisionnement `objectGUID`, attributs AD au strict minimum (D3), N contrôleurs dont un seul suffit (D4), `cache_annuaire` **sans relecture de `userAccountControl`** (D1), `session`, RS256 15 min, renouvellement en cookie avec révocation manuelle des sessions d'un utilisateur, anti-force brute, P-01 à P-04, sonde LDAP | Nouveau paquet `identite` ; suppression de `security/CompteUtilisateur*`, `CompteSeeder`, `ServiceUtilisateurs` ; **propriétaire de `config/SecurityConfig.java`** ; `backend/src/test/.../support/Comptes.java` (signatures conservées) ; `application*.yml` (`ged.identite`) ; `pom.xml` (`spring-security-ldap`, UnboundID en test) ; Angular `core/auth.*`, `core/session.service.ts`, écran de connexion, intercepteur de renouvellement |
| dev2 | **M1 (J+2)** : `ExceptionMetier`, catalogue des codes, `GlobalExceptionHandler` en problem+json ; **E4** : `journal_audit` partitionné, déclencheurs, droits, scellement horaire exporté, vérification, écran et export, P-15 ; instrumentation des modules de référentiel | **Propriétaire de `common/GlobalExceptionHandler.java`** ; nouveaux paquets `common/erreur`, `audit` ; services de `typedocument`, `index`, `planindexation`, `workspace`, `workflow`, `signature`, `etiquette`, `accessgroup`, `indexation`, `employe` (appels d'audit uniquement) ; changesets `audit` ; Angular `core/api.ts` (lecture problem+json), `features/audit` |
| dev3 | E5 branché : dépôt, versement, téléchargement, prévisualisation chiffrés ; 413/415/422 ; reprise des fichiers existants (chiffrement + empreinte) ; P-13 ; sondes fichiers et ClamAV ; plafond 200 Mo ; événements d'audit de ses parcours | Nouveau paquet `depot` (extrait de `DocumentService`) ; `document/StorageService` (remplacé), `document/DocumentController` (téléchargement, prévisualisation) ; changesets `cle_fichier`, empreinte ; `application.yml` (`spring.servlet.multipart`) ; Angular `features/documents` (visionneuse) |
| qa | Recette vague 1 ; scripts E2 (simulateur LDAP), E4 (altération du journal), E5 (fichier altéré, infecté) | `docs/conformite/recette/**` |

**Risques de conflit et parades.**
- `GlobalExceptionHandler` : dev2 seul ; dev1 et dev3 lèvent des sous-classes d'`ExceptionMetier`
  après l'amorce.
- Audit : dev2 **ne touche pas** au paquet `document` ni à `depot` ni à `identite` ; dev3 et
  dev1 appellent `AuditService` après l'amorce ; les connexions passent par les événements
  Spring Security.
- `SecurityConfig` : dev1 seul. Le filtre MDC de dev2 (vague 1) est enregistré hors de la
  chaîne de sécurité ; s'il doit lire l'utilisateur authentifié, il le fait par
  `SecurityContextHolder`, sans modification de `SecurityConfig`.
- `support/Comptes.java` : dev1 seul, signatures publiques inchangées (les tests de dev2 et dev3
  continuent d'obtenir un jeton par la même méthode).
- `indexation` : dev2 y ajoute des appels d'audit en vague 2, dev3 y retire le pré-remplissage
  OCR en vague 3 : séquentiel, sans conflit.

**Ordre de fusion** : amorce dev2 (J+2) → fin de vague : dev1 (identité, dont dépendent les
tests de tous) → dev2 → dev3 → qa.

**Critère de sortie** : connexion par le simulateur LDAP, aucun mot de passe en base, chaque
action existante auditée, une modification manuelle du journal détectée, aucun fichier en clair,
un fichier altéré détecté, un fichier infecté refusé.

### Vague 3

| Membre | Lot | Paquets et fichiers touchés |
|---|---|---|
| dev1 | **Amorce J+3** : `AccessPredicate`, `ControleAcces`, `Sujet` (utilisateur, groupe, application), point d'extension `SourceHabilitations`, implémentation permissive. **E3** : rôles, permissions, groupes, habilitations, nœuds à chemin matérialisé (reprise de `workspace`), confidentialité et personnes désignées, `document_rattachement` et union des droits, 404 hors périmètre, arbre et compteurs au périmètre, pagination (T-050), P-22, écrans d'administration, menus par permission ; garde de l'écran d'audit | Nouveaux paquets `autorisation`, `noeud` ; `workspace` (propriétaire) ; `document` (contrôleurs de fiche, versions, téléchargement, prévisualisation : **contrôles d'accès uniquement**, propriétaire à partir de cette vague) ; `accessgroup` (remplacé par `groupe_ged`) ; Angular `layout/**` (menus), `features/administration/**` |
| dev2 | Idempotency-Key (`idempotence_cle`) ; tables `application`, `cle_api` ; génération, affichage unique, SHA-256, expiration, chevauchement, adresses autorisées, quotas et 429 `Retry-After` ; filtre `X-API-Key` ; écran des clés ; finitions E4 | Nouveaux paquets `cleapi`, `idempotence` ; **propriétaire de `SecurityConfig` pour cette vague** (ajout du filtre) ; `application*.yml` (`ged.api`) ; Angular `features/cles-api`, entrée de menu |
| dev3 | E6 : `ocr_job`, worker `SKIP LOCKED`, déchiffrement en mémoire ou tmpfs, `fra+ara` et langue par type, aucun plafond, 60 s par page et 3 reprises, `OCR_ECHEC`, `document_texte`, tsvector et GIN, `POST /recherches` filtrée par `AccessPredicate`, réindexation, P-09 ; retrait du pré-remplissage (T-029) ; **12.11 et dépôt avec métadonnées** ; outillage du protocole OCR | `ocr`, `indexation` (retrait du pré-remplissage), `depot`, nouveau paquet `recherche` ; `typedocument` (colonne de langue OCR seulement) ; `tessdata/` (modèle `ara`) ; Angular `features/recherche`, `features/indexation`, `features/supervision-ocr` |
| qa | Recette vague 2 ; scripts E3 (chaque chemin d'accès), E6 (délai de disponibilité d'un scan arabe de 20 pages et d'un document de 800 pages, borne de 24 h, D6) | `docs/conformite/recette/**` |

**Risques de conflit et parades.**
- Recherche et droits : dev3 code contre l'interface de l'amorce ; la recette de filtrage se
  fait après la fusion de dev1. Un test d'architecture (dev1) interdit toute requête de document
  qui ne passe pas par `AccessPredicate`.
- `SecurityConfig` : dev2 seul ; si dev1 active la sécurité de méthode, il le fait dans une
  classe de configuration distincte.
- Menus Angular (`layout`) : dev1 propriétaire ; dev2 et dev3 ajoutent une entrée par ligne dans
  la liste des menus (conflit d'union trivial).
- `typedocument` : dev3 n'y ajoute que la langue OCR ; dev1 y travaille en vague 4.

**Ordre de fusion** : amorce dev1 (J+3) → fin de vague : dev1 (E3) → dev3 (E6, dont les tests de
filtrage exigent le vrai moteur) → dev2 → qa.

**Critère de sortie** : un utilisateur sans droit ne voit ni ne compte rien hors de son
périmètre ; un scan arabe ou français est trouvable par son contenu dans le délai maximal de
**24 heures** (revue client D6, au lieu des 5 minutes du V3 ; le délai réel est mesuré et suivi),
dans le seul périmètre de l'utilisateur ; une clé API révoquée est refusée.

### Vague 4

| Membre | Lot | Paquets et fichiers touchés |
|---|---|---|
| dev1 | **Amorce J+2** : colonnes `statut_conservation`, `archive_le`, `archive_par`, `echeance_conservation`, **drapeau d'archivage sur `noeud`** (D10) et service `GardeEcriture` (verrou + archivé, 409). **E7 modèle** : nature booléenne, validation JSONB contre le plan, index d'expression, type (durée, point de départ, confidentialité par défaut, plan versionné, `RESTRICT`, `job_retypage`), versions (numéro, auteur, empreinte, index unique partiel ; **le versement rend la nouvelle version courante et fige l'ancienne en lecture seule**, D9), verrou (auteur, date, motif), rattachements REST, déplacement de document et de dossier, renommage (P-20), socle commun (P-21), **espace de partage simple (R-03, D12)** : nature « échange » du nœud, sans édition en ligne | `document` (entités, versions, verrou), `typedocument`, `index`, `planindexation`, `noeud`, `indexation` (validation) ; Angular fiches document, types, index |
| dev2 | Portée des clés (`cle_api_portee`, implémentation `SourceHabilitationsApplication`), délégation `X-On-Behalf-Of`, T-041, T-044 (partie délégation), source du dépôt (T-040), consultation des droits pour un tiers ; E10 : restauration à blanc, P-17 | `cleapi`, nouveau fichier dans `autorisation` (implémentation seulement), `depot` (colonne source seulement) ; `deploiement/**` |
| dev3 | E7 cycle de vie : purge avec destruction de la DEK, archivage **manuel uniquement, d'un document ou d'un dossier entier** (D10 : drapeau posé sur le nœud, `job_archivage` par tranches de 100 sur ses documents, aucune tâche automatique ; PDF/A-2 obligatoire par LibreOffice ou PDFBox, Word accepté, veraPDF ; désarchivage), export ZIP en flux avec `manifeste.csv` et traitement de fond | Nouveau paquet `cycledevie` ; `stockage` (destruction de DEK) ; `pom.xml` (veraPDF) ; Angular corbeille, archivage, liste des exports |
| qa | Recette vague 3 ; scripts E7 et E9 | `docs/conformite/recette/**` |

**Risques de conflit et parades.**
- Entités document et nœud : dev1 propriétaire ; dev3 n'utilise que les colonnes de l'amorce
  (dont le drapeau d'archivage du nœud) et `GardeEcriture`, sans modifier les entités.
- `autorisation` : dev2 n'ajoute qu'un fichier d'implémentation du point d'extension.
- `depot` : dev2 n'ajoute que la source ; dev3 ne touche pas au dépôt dans cette vague.
- `pom.xml` : veraPDF (dev3) ; licence à contrôler dans le SBOM (P-19).

**Ordre de fusion** : amorce dev1 (J+2) → fin de vague : dev1 → dev3 → dev2 → qa.

**Critère de sortie** : un document archivé est intouchable et possède sa copie PDF/A validée ;
un export ZIP ne contient que ce que l'utilisateur peut voir ; une application dépose, recherche
et télécharge dans sa seule portée, pour son compte ou pour le compte d'un utilisateur.

### Vague 5

| Membre | Lot | Paquets et fichiers touchés |
|---|---|---|
| dev1 | **Amorce J+3** : contrat REST du workflow (chemins, DTO, codes d'erreur) et interface de service. E8 : workflow parallèle (D7) : règle rattachable à un espace, un dossier ou un type ; validateur nommé ou par rôle ; `circuit`, `circuit_validateur`, `decision` ; statut recalculé sur la version courante ; annulation, diffusion ; **réaffectation d'un validateur en attente par l'Administrateur** (la GED ne détecte plus les comptes désactivés, D1 ; figement : QR1) ; reprise des circuits existants ; appel de `NotificationService` ; points d'entrée REST uniques pour le front et les tiers | `workflow`, `signature`, `autorisation` (appel de notification) ; Angular écran de validation |
| dev2 | **E8-API (R-01, R-02, revue client D8)**, après l'amorce de dev1 : opérations de workflow ajoutées à la portée des clés (`cle_api_portee`), délégation `X-On-Behalf-Of` pour la désignation des validateurs et pour les décisions rendues depuis l'intranet (le délégué doit être validateur du circuit), Idempotency-Key, audit à double identité, OpenAPI avec exemples, test de contrat ; puis P-06, T-042, T-053, P-07, P-08 ; finitions E10 (sondes finales, script complet) ; P-11 | `cleapi` (opérations de portée), tests de contrat, spécification OpenAPI, `deploiement/**`, `docs/**` ; **aucune modification des contrôleurs de `workflow`** (propriété de dev1) |
| dev3 | **Amorce J+2** : `NotificationService`. E8 : `document.echeance_conservation`, tâche quotidienne avec verrou de tâche, filtre « échéance dépassée » ; table `notification`, envoi SMTP asynchrone avec 3 reprises, pastille, préférence e-mail | Nouveaux paquets `conservation`, `notification` ; `recherche` (filtre d'échéance) ; `pom.xml` (`spring-boot-starter-mail`) ; Angular centre de notifications |
| qa | Recette vague 4 ; scripts E8 ; préparation E11 | `docs/conformite/recette/**` |

**Risques de conflit et parades.** dev2 teste l'API du workflow contre le contrat de l'amorce ;
si un point d'entrée manque ou diverge, il le demande à dev1 au lieu de le coder. La charge de
dev2 augmente avec E8-API : P-12 et P-14 sont reportés en vague 6.

**Ordre de fusion** : amorces dev3 (J+2) et dev1 (J+3) → fin de vague : dev3 → dev1 → dev2
(E8-API exige le vrai workflow) → qa.

**Critère de sortie** : deux validateurs décident dans n'importe quel ordre ; chaque ouverture,
décision et annulation notifie les bonnes personnes ; une application de test (intranet simulé)
désigne les validateurs d'un circuit et rend une décision pour le compte d'un validateur, avec
double identité dans l'audit ; test de contrat vert sur les 8 opérations du 5.3.1 et sur les
points d'entrée du workflow.

### Vague 6 — E11 recette de conformité

- qa rejoue les 140 lignes dans le périmètre de `SUIVI.md` (R-04 est hors périmètre) sur `conformite-technique` gelée ; les développeurs
  corrigent sur leur branche ; pm fusionne les corrections une par une.
- pm passe chaque ligne à « Identique » après relecture contre le PDF, met à jour
  `MATRICE-TECHNIQUE.md`, prépare le procès-verbal.
- Exécution du protocole OCR (T-028) et des mesures de volumétrie (P-14) si l'échantillon de
  MMED est reçu ; sinon, ces lignes restent ouvertes et sont signalées comme telles.

## 5. Répartition des lignes du suivi par vague

| Vague | dev1 | dev2 | dev3 |
|---|---|---|---|
| 1 | E1 (13 lignes) | E0 (6), E10 (9 lignes en cours), T-076, T-077 | E5 composants (7) |
| 2 | E2 (12) | T-047, T-048 (catalogue), E4 : T-078 à T-080, P-15 | Branchement de E5, T-062, P-13 |
| 3 | E3 (7) | T-049, T-051, T-054 (cycle de vie des clés) | E6 (15, dont T-043 et T-115) |
| 4 | E7 modèle (7) + R-03 | T-040, T-041, T-044, T-055, P-10, P-16, P-17 | E7 cycle de vie (4) |
| 5 | E8 workflow (3), contrat REST du workflow | E8-API (R-01, R-02), T-042, T-053, P-06 à P-08, P-11, E10 restant | E8 conservation et notifications (2) |
| 6 | Revérification des 23 lignes « Identique » par leurs responsables ; P-18 et R-04 (pm) | P-12, P-14 | |

Les lignes à cheval sur plusieurs vagues (T-009, T-025, T-048, T-074, T-097, T-104) sont
suivies dans `SUIVI.md` avec leur étape de clôture.
