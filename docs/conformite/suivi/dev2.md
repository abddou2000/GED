# Suivi — dev2 (qualité, exploitation, traçabilité, API)

Branche `ct/dev2`. Mise à jour : 28/09/2026.

## Anomalies de la recette de la vague 5 : **corrigées sur ct/dev2**

| Anomalie | Correction | Commit |
|---|---|---|
| ANO-E9-001 (majeure, T-040) | `cleapi.SourceDepotApplications` (`@Primary`) : l'origine du dépôt est lue sur le jeton `ApplicationAuthentifiee` (et non sur le principal, qui sous délégation est la personne) : canal `API` (bureau d'ordre reconnu à son code), `applicationId`, déposant délégué, `depotDelegue` vrai. Test par l'API réelle : `ContratApiTest.depotParApplication` (dépôt par clé avec et sans `X-On-Behalf-Of` : fiche, base, recherche par canal) ; `SourceDepotApiTest` ne garde que les contraintes de base | b39fa60 |
| ANO-E11-001 (P-12) | `AppelsSortantsTest` : motifs complétés (`createSocket`, `SocketFactory`, `toURL()`, contextes et clients LDAP, écoute, résolution de nom, JDBC direct) ; `FabriqueSocketsLdaps`, `SimulateurAnnuaire`, `ConfigurationProxysDeConfiance` inventoriés ; nouveau test `inventaireJustifie` (chaque entrée reconnue par un motif et citée dans `REVUE-SSRF.md`) | 5d0a363 |
| ANO-E11-002 (P-12) | `sorties.conf.exemple` : résolveurs DNS de MMED (ou noms figés dans `/etc/hosts`), une ligne par contrôleur (D4), NGINX aligné ; `deploiement/scripts/verifier-sorties.sh` (chaque nom de `ged.env` résolu comme la JVM doit tomber sur une adresse autorisée ; éprouvé avec un résolveur simulé) ; flux local vers LibreOffice déclaré, `deploiement/libreoffice/ged-securite.xcd` (ressources liées et macros bloquées), `SERVER_ADDRESS` hors boucle locale, risque résiduel de la boucle (clamd) déclaré dans `REVUE-SSRF.md` §3 ; installation et contrôle `EXPLOITATION.md` §12 | 5d0a363 |
| ANO-E10-001 (P-17) | `GARANTIE.md` §2 : double écriture maintenue en N+1 (retour vers N sans perte), retour arrière du contract par reconstitution de l'ancienne colonne depuis la nouvelle, variante sans double écriture interdite sauf recopie livrée et éprouvée en UAT | 5b8e15b |

Tests après fusion de `conformite-technique` (b7274bc, E7 modèle, E8 et E8-API de dev1) : `mvn test` **580 verts** (0 échec), `ng test` 18 verts, `ng build` vert ; schéma et diagrammes de classes régénérés.
Vérifié sur le papier seulement : filtre systemd, couche de configuration LibreOffice
(ni systemd ni LibreOffice sur le poste).

## Dernière partie du périmètre — contrat d'API, finitions E10/E11, modélisation : **acceptée** (4d28528)

| Réf. | Exigence | Livré | Statut proposé |
|---|---|---|---|
| P-06, T-042 (§5.3.1) | chemins exacts du contrat | `contratapi` : `POST /noeuds/{id}/dossiers` (Déposer sur le parent, circuit hérité, code attribué, 201 + Location), `POST /recherches` (plein texte de `SearchIndexer` + critères d'index d'`IndexationService`, en ET, droits à la source, pagination 50 / 200, tri en liste blanche), `GET /documents/{id}/contenu?version=` (`DocumentService.telechargerVersion`, audité) ; `POST /documents`, `POST /documents/{id}/versions`, `POST /documents/{id}/rattachements` et `DELETE …/{noeudId}` vérifiés au chemin exact ; aucune règle réimplémentée ; Idempotency-Key, portée de clé, délégation et audit par les mécanismes communs ; anciens chemins conservés pour le front, leur description OpenAPI renvoie au chemin du contrat | Identique |
| T-044 (§5.3.1) | consultation des droits | `GET /documents/{id}/droits`, `GET /noeuds/{id}/droits`, `pourUtilisateur` (identifiant ou UUID) : objet visible exigé (404 sinon), soi-même ou l'utilisateur délégué sans condition, un tiers avec `GERER_ROLES_HABILITATIONS` ; droits d'une clé d'API (sa portée) ou de la délégation ; `ServiceDroitsEffectifs.calculerPourAppelant` (même fonction de décision) | Identique |
| T-065 (§6.2.1) | TLS | `securite.ControleTransportsChiffres` : démarrage refusé en uat/prod sans `sslmode=verify-full` et autorité lisible, avec une URL `ldap://` ou sans STARTTLS vers le SMTP ; tableau des liaisons dans `EXPLOITATION.md` §6 | Identique (contrôle éprouvé par test ; TLS réel vérifié sur le papier) |
| P-16 (§7.4.2) | pgaudit | `deploiement/postgresql/pgaudit.conf.exemple`, `pgaudit-roles.sql` (postgres : tout, ged_owner : DDL / droits / écritures, ged_readonly : lectures, ged_app : rien), procédure et vérification `EXPLOITATION.md` §10 | Vérifié sur le papier (pgaudit absent du poste) |
| P-10 | chiffrement du volume de la base | LUKS2 (aes-xts, argon2id) en prérequis d'installation, ouverture TPM ou Tang, séquestre des phrases, vérification : `EXPLOITATION.md` §11 | Vérifié sur le papier |
| P-12 (A10) | revue SSRF | `docs/securite/REVUE-SSRF.md` (inventaire des 8 appels sortants, aucun client HTTP) ; `securite.AppelsSortantsTest` fige l'inventaire et interdit toute destination issue d'une requête ; filtrage systemd des sorties (`ged-backend.service.d/sorties.conf.exemple`, s'applique à LibreOffice et Tesseract) | Identique |
| P-11 | modèle de menaces | `docs/securite/MODELE-DE-MENACES.md` : STRIDE par module (identité, autorisation, dépôt et stockage, recherche, cycle de vie, circuits, notifications, API, audit, exploitation, front) | Identique |
| P-17 (§10.4) | plan de garantie | `docs/exploitation/GARANTIE.md` : expand / contract type avec retour arrière par étape, règles de changeset de données, déroulé UAT puis production, contournement sous 24 h par situation | Identique |
| P-05 (§4.5, §12.1) | livrables de modélisation | `docs/modelisation/SCHEMA-BASE.md` généré depuis une base créée par Liquibase (42 tables, colonnes, contraintes, index, volumétrie à 5 ans §6.6, diagramme entité-association par groupe du §12.1) ; `CLASSES.md` (diagramme par module, généré depuis le code) ; `SEQUENCES.md` (dépôt en deux temps, OCR, recherche filtrée, délégation, archivage) ; scripts `outils/schema-base.mjs`, `outils/diagrammes-classes.mjs` | Identique |

**Anomalies de recette corrigées** :
- **ANO-E4-004** : `premier_numero` / `dernier_numero` du scellement calculés numériquement
  (min / max). L'ordre des lignes dans la chaîne (identifiant en texte, colonne de sortie
  `id::text`) est explicité (`ORDER BY 1`) et **conservé**, sinon les scellements déjà
  produits ne se vérifieraient plus. Test : période de plus de 150 lignes
  (`ScellementAuditTest.bornesNumeriques`).
- **ANO-E1-005** : `preference_notification` (clé `id` UUID + `uk_preference_notification_utilisateur_id`)
  et `journal_audit_scellement` (clé `id` UUID v7, la chaîne se suivant par période et non par
  identifiant) alignés par `202610021000_alignement_cles_uuid.xml` avec retour arrière ; seule
  exception déclarée dans `SchemaLiquibaseTest` : `journal_audit` (identifiant séquentiel exigé
  par le §7.4.1). Le scellement en INSERT seul est migré par `ADD COLUMN … DEFAULT` (réécriture
  sans UPDATE, aucun déclencheur contourné).

**Aussi** : port du SMTP simulé des tests surchargeable (`GED_SMTP_PORT_TEST`, 3025 par
défaut), documenté dans la règle 6 bis du brief :
SMTP simulé dev1 3031, dev2 3032, dev3 3033, qa 3034, pm 3035. Noms de schémas OpenAPI
homonymes désormais stables (tous préfixés quand le nom simple est ambigu), plus d'ordre
d'apparition.

**Fusion de `conformite-technique`** (a92c10d : T-040 et correctifs ANO-E7-001, ANO-E5-002
de dev3, recette qa de la vague 4) : `POST /recherches` du contrat suit le canal du dépôt
(critère `canal`, 400 hors des quatre valeurs ; colonne `canalDepot` avec ou sans plein
texte ; `ContratApiTest.recherche`). Schéma et diagrammes de classes régénérés après fusion.
Tests après fusion : `mvn test` **552 verts** (0 échec), `ng test` 18 verts, `ng build` vert.

**Limites** : pgaudit, LUKS, filtrage systemd et TLS réel non exécutables sur le poste
(vérifiés sur le papier) ; volumétrie estimée d'après les hypothèses du §6.6, à réévaluer sur
l'échantillon réel.

## Vague 4 — fusions E2/E3 (dev1) et E5-E7 (dev3), portée des clés et délégation, D4 : **acceptée** (439a08e)

Fusions de `conformite-technique` : 4f41279 (E2, E3 de dev1) dans cbae4e4, puis e81ecc2
(E5 à E7 de dev3). Références : matrice technique (MT) et fonctionnelle (MF), lignes
comptées à partir de 0.

| Réf. | Exigence | Livré | Statut proposé |
|---|---|---|---|
| MT 4.14 | §5.4 — portée des clés | `SourceHabilitationsApplications` : la clé est un sujet (`Sujet.id` = la clé, cache des droits par clé), `cle_api_portee` (nœud + opérations) traduite en attributions et décidée par le même `AccessPredicate` que les utilisateurs ; `OperationApi` → permissions (CONSULTATION, RECHERCHE → Consulter ; DEPOT, CREATION_DOSSIER → Déposer ; VERSEMENT → Consulter + Modifier ; RATTACHEMENT → Consulter + Modifier + Déposer ; WORKFLOW_PILOTAGE → Consulter + Modifier ; WORKFLOW_DECISION → Consulter + Valider, contrat E8-API de dev1) ; habilitations de sujet APPLICATION servies aussi (élémentaires seulement) ; API `GET/PUT /api/v1/cles-api/{id}/portee` (audit `CLE_API_PORTEE_MODIFIEE`, effet immédiat par `version_habilitations`), portée recopiée à la régénération ; `ControlePorteeApplication` branché sur `ControleAcces` ; FK `cle_api_portee → noeud` et `habilitation → application` ; éditeur de portée dans l'écran « Clés d'API » | Identique |
| MT 4.1 | §5.2 — applications au même modèle | sujet d'autorisation, audit, portée : oui ; routes réservées aux utilisateurs refusées aux clés (applications, clés, audit, auth, notifications, admin, groupes) | Identique |
| MT 4.15 | §5.5 — délégation `X-On-Behalf-Of` | `ResolveurDelegationAnnuaire` : attribut « délégation » de la clé (403 sinon), adresses sources obligatoires (403 `DELEGATION_SANS_ADRESSES`), identifiant (`sAMAccountName` ou objectGUID) résolu par les identités GED puis l'annuaire (provisionnement sans rôle), inconnu → 422 `IDENTITE_DELEGUEE_INVALIDE`, annuaire injoignable → 503 ; écriture : droits de la clé, l'utilisateur délégué est l'auteur (déposant) ; lecture (GET/HEAD) : **intersection** nœud par nœud des droits de la clé et de l'utilisateur ; double identité au journal (`acteur_utilisateur_id` = délégué, `acteur_application_id`) | **Proche** : rejet d'un compte **désactivé** en attente (QR9, D1) — option `ged.api.delegation.verifier-compte-annuaire` (existence dans l'annuaire), faux par défaut |
| P-02 / D4 | sonde annuaire par contrôleur | `identite.annuaire.SondeAnnuaire` (dev1) : un contrôleur = liaison par la source principale, comme avant ; N contrôleurs = liaison de chacun (mêmes réglages, `ConfigurationAnnuaire.construire`), `UP` / `DEGRADE` (un sur N) / `DOWN`, cache 30 s conservé ; jauge `ged_annuaire_controleur{controleur}`, alerte `GedAnnuaireControleurIndisponible` ; `DEGRADE` = 200 et compté disponible dans `ged_sante` | Identique (N contrôleurs éprouvés par test unitaire, un seul contrôleur réel) |
| MT 7.4 | §8.3 — registre des dépendances | veraPDF (double licence, **MPL-2.0 retenue** pour tout le groupe `org.verapdf`), xmpbox (Apache-2.0), Saxon-HE et rhino (MPL-2.0, transitives de veraPDF), jaxb-api (CDDL-1.1 retenue), stax-utils (BSD-4-Clause) arbitrés dans `outils/registre-dependances.mjs` ; usages de spring-boot-starter-mail, spring-security-ldap, unboundid, tika-core documentés ; `docs/DEPENDANCES.md` régénéré, aucune licence sans arbitrage | Identique |

**Branchements sur E2/E3** : acteur du journal = identité GED (`ActeurCourant.utilisateurId()`) ;
destinataires des notifications depuis `cache_annuaire`, `groupe_membre`, habilitations
(`AnnuaireDestinatairesIdentite`) ; écoute directe de `HabilitationModifiee` ; journal sous
`CONSULTER_AUDIT` (garde de dev1) ; administration des clés sous `GERER_CLES_API`. Tests :
`EvenementsLotsAuditTest` (connexions, habilitations au journal),
`EvenementsDocumentsJournalTest` (les 15 événements documents de dev3 au journal, lectures
en transaction en lecture seule comprises), `NotificationsTest.chaineReelleAutorisation`.

**Arbitrages de fusion à connaître (dev1, dev3)** :
- `GestionErreursAutorisation` supprimé et `ConflitAutorisationException` devenue une
  `ConflitException` (problem+json), comme annoncé par dev1 ; `CheminsAccesApiTest` lit
  `code`/`detail` (404 au libellé fixe, P5).
- dev3 : `EvenementDocument.acteurUtilisateurId()` rend `null` — le journal prend l'identité
  GED de la requête (l'employé n'est pas l'identité) ; `EvenementsAuditTest` et
  `ArchivageApiTest` ajustés. Les tests de dev3 qui lisaient `$.message` lisent `$.detail`
  (contrat problem+json) ; statuts 413/415 de dev3 conservés.
- `AuditService` : un événement publié dans une transaction en **lecture seule**
  (téléchargement, aperçu) est écrit dans sa propre transaction (sinon 500).
- Spécification OpenAPI : `NomsSchemasDistincts` — deux DTO homonymes (les `Ref`, les
  `Resultat`) ne sont plus fusionnés en un seul schéma ; dictionnaire complété pour E2, E3,
  E5 à E7.
- Workflow API de dev1 (bd83262, pas encore dans `conformite-technique`) : `AccesApiWorkflow`
  sera branché (`@Primary`) quand il y sera ; la portée prévoit déjà WORKFLOW_PILOTAGE et
  WORKFLOW_DECISION.

**Exploitation des tests en parallèle (pour pm)** : les suites de plusieurs membres lancées en
même temps se gênent — même port d'annuaire simulé (33390) et saturation de PostgreSQL
(`max_connections` = 100, contextes Spring de test en cache). Mes exécutions :
`GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT=33392 GED_IDENTITE_ANNUAIRE_URLS=ldap://localhost:33392
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3`.

## Après vague 3 — notifications (E8, partie reprise de dev3) et OpenAPI (T-053) : **acceptés** (93cbc7d)

Références : matrice technique (MT) et fonctionnelle (MF), lignes comptées à partir de 0.

| Réf. | Exigence | Livré | Statut proposé |
|---|---|---|---|
| MT 8.18 / T-113 | §12.9 — boîte d'envoi, e-mail SMTP, pastille in-app | paquet `notification` : table `notification` écrite dans la transaction du déclencheur (API interne `Notifications.envoyer`), expédition asynchrone par Spring Mail (dès la validation de la transaction + relève 30 s, `FOR UPDATE SKIP LOCKED`, 3 tentatives espacées 1 puis 2 min, puis `ECHEC`), modèles français figés à la création, préférence e-mail (`preference_notification`, l'in-app subsiste), API `GET /api/v1/notifications` (+ `/compteur`, `/{id}/lecture`, `/lecture`, `/preferences`), audit `NOTIFICATION_ENVOYEE` / `NOTIFICATION_ECHEC` / `PREFERENCE_NOTIFICATION_MODIFIEE` sans adresse ni texte ; Angular : pastille dans la barre supérieure (relève 1 min) et écran « Notifications » | Identique pour le moteur ; relais SMTP de MMED **simulé** (GreenMail) |
| MF 7.10 | §4.6.6 — trois cas exclusivement | énumération fermée `TypeNotification` + contrainte `ck_notification_type` | Identique |
| MF 7.10 (accès) | attribution d'un accès à un espace, jamais le retrait | écoute de `HABILITATION_MODIFIEE` (contrat `EvenementAudit`, sans compiler contre dev1) : habilitation avec rôle sur un nœud (utilisateur ou membres du groupe) et membres ajoutés à un groupe (un avis par espace du groupe) ; retrait, document, rupture seule, auteur : rien | Identique dès la fusion d'E3 (dev1) ; éprouvé avec des événements de même forme |
| MF 6.5 / MF 7.3 | §4.5.3 notification des validateurs et du déposant ; alerte d'échéance aux Agents d'archive | contrat `EvenementNotifiable` (une méthode `notification()`) pour les événements de workflow (dev1, E8) et d'échéance (dev3, E8), destinataires nommés ou par rôle | **Proche** : moteur et modèles prêts, les événements déclencheurs n'existent pas encore |
| MT 4.13 / T-053 | §5.3 — spécification OpenAPI 3 complète | `documentationapi` : `OpenApiCustomizer` + dictionnaire `champs.yml` (72 schémas, tous les champs décrits avec exemple, paramètres et corps sans schéma nommé), erreurs problem+json par opération (codes et exemple chacune : 400, 401, 403, 404, 409, 413, 415, 422, 429, 500, 503 selon l'opération), sécurité (Bearer ou `X-API-Key`, connexion publique, routes réservées aux utilisateurs), en-têtes `Idempotency-Key`, `X-On-Behalf-Of`, `Retry-After`, `Idempotency-Replayed`, `Deprecation`/`Sunset`/`Link`, pagination 50/200 ; springdoc désactivé en profil prod (en plus du refus de `SecurityConfig`) | Identique |

**Points d'extension (à brancher à la fusion, dev1)** : `AnnuaireDestinataires`
(courriel lu dans `cache_annuaire`, membres des groupes, espaces d'un groupe,
porteurs d'un rôle) — l'implémentation transitoire `AnnuaireDestinatairesLocal`
lit `compte_utilisateur` et les groupes d'accès de cette branche ; `IdentiteDestinataire`
(`ActeurCourant::employeId` ici, `ActeurCourant::utilisateurId` après E2). Les deux
sont `@ConditionalOnMissingBean` : déclarer son bean suffit.

**Pour dev1 et dev3** : un DTO ajouté ou modifié doit être décrit dans
`backend/src/main/resources/documentationapi/champs.yml` ; sinon
`SpecificationOpenApiTest` échoue et liste les entrées à ajouter. Aucune annotation
dans les contrôleurs. Sous-arbre de configuration `ged.notification` repris de dev3
(INTEGRATION.md § 3) ; `spring.mail` dans `notification.yml`.

**Limites** : relais SMTP réel non éprouvé (simulateur) ; pas de rôle « Agent
d'archive » avant E3 (une alerte d'échéance sans destinataire est journalisée, rien
n'est envoyé au hasard) ; liens des e-mails vers les écrans actuels
(`televerser/<id>`, `espaces-de-travail/<id>`), à ajuster si les routes changent ;
écran vérifié par tests et construction, pas en navigateur (pas de backend dev lancé).

**Tests** : `DB_NAME=ged_dev2 mvn -q test` vert, **369 tests**, 0 échec ; nouveaux : `NotificationsTest` 10,
`ModelesNotificationTest` 3, `SpecificationOpenApiTest` 7 ; `SchemaLiquibaseTest`
et `ClesApiTest` étendus. Front : `ng test` vert (17 tests, dont `NotificationsService` 4), `ng build` vert.
Commits : b55c1cb (moteur), 1b096b3 (Angular), 1c029ec (OpenAPI).

## Vague 3 — lot E9 socle API : **acceptée** (8731e55)

Références de la matrice technique, section « Intégration et API (§5) », lignes
comptées à partir de 0 (4.x), avec l'article du DAT.

| Réf. | Exigence (DAT) | Livré | Statut proposé |
|---|---|---|---|
| 4.9 | 5.3.2 — Idempotency-Key obligatoire sur les créations | `FiltreIdempotence` générique (routes configurables : dépôt, version, rattachement, espace, dossier), table `idempotence_cle`, réservation avant exécution, empreinte requête (multipart indépendant du boundary) et réponse 2xx mémorisées 24 h par appelant (application ou utilisateur) ; rejeu → réponse initiale + `Idempotency-Replayed`, contenu différent → 422 `IDEMPOTENCE_CONFLIT`, traitement concurrent → 409 `IDEMPOTENCE_EN_COURS`, clé absente/invalide → 400 ; purge horaire. Front : intercepteur qui pose une clé UUID neuve sur chaque POST | Identique |
| 4.10 | 5.3.2 — pagination, plafond 200, liste blanche | existant vérifié (plafond 200, tri en liste blanche) ; taille par défaut portée de 10 à 50, alias `taille` accepté | Identique |
| 4.11 | 5.3.2 — taille par type, quotas par clé | quotas 600/min et 100 000/jour par clé (réglables par application), 429 + `Retry-After`, compteurs en mémoire persistés périodiquement (`cle_api.quota_jour_*`) ; métadonnées plafonnées à 64 Ko (413 `METADONNEES_TROP_VOLUMINEUSES`) | Identique **sous réserve** : compteurs par instance (voir limites) |
| 4.12 | 5.3.2 — version majeure dans l'URL | `/api/v1` inchangé ; en-têtes `Deprecation`, `Sunset` (≥ 12 mois) et `Link rel="successor-version"` émis par préfixe déclaré (`ged.api.conventions.depreciations`), prêts pour `/api/v2` | Identique |
| 4.14 | 5.4 — clés API | tables `application`, `cle_api` ; format `ged_<env>_<identifiant>_<secret>`, secret 256 bits montré une fois, empreinte SHA-256 (comparaison à temps constant) ; `X-API-Key` ; génération, consultation, expiration 12 mois, révocation motivée, régénération avec chevauchement de 7 jours, clés expirant sous 30 jours signalées ; adresses autorisées (IP ou CIDR) ; refus d'une clé d'un autre environnement ; événements d'audit pour chaque opération et chaque appel (`APPEL_API`, `CLE_API_REFUSEE`, `QUOTA_DEPASSE`, …) sans secret ; écran Angular « Clés d'API » | **Proche** : la portée (`cle_api_portee`) est modélisée mais branchée en vague 4 |
| 4.1 | 5.2 — applications soumises au même modèle | l'application est un sujet authentifié (`ApplicationAuthentifiee`, `ROLE_APPLICATION`), journalisée comme un utilisateur ; point d'extension `ControlePorteeApplication` (permissif) | **Proche** : calcul des droits = E3 (dev1), vague 4 |
| 4.15 | 5.5 — délégation X-On-Behalf-Of | modèle prêt (`cle_api.delegation`, délégation refusée sans liste d'adresses) ; `ResolveurIdentiteDeleguee` fermé par défaut (422 `IDENTITE_DELEGUEE_INVALIDE`) | Non (préparé, vague 4) |

D8 (workflow pilotable par API) : l'énumération `OperationApi` de la portée prévoit
`WORKFLOW_PILOTAGE` et `WORKFLOW_DECISION` ; rien d'autre n'est implémenté.

**Branchement sécurité (pour dev1)** : aucune modification de `SecurityConfig`.
Une chaîne dédiée `ConfigurationSecuriteApplications`
(`@Order(HIGHEST_PRECEDENCE + 10)`) ne s'applique qu'aux requêtes `/api/**` portant
`X-API-Key` ; les autres restent sur la chaîne utilisateur. Seule contrainte :
`SecurityConfig` ne doit pas déclarer d'ordre plus prioritaire que
`HIGHEST_PRECEDENCE + 10`. Une application n'a jamais accès à
`/api/v1/applications/**`, `/api/v1/cles-api/**`, `/api/v1/audit/**`,
`/api/v1/auth/**`. Points d'extension à remplacer en E3/vague 4 (beans
`@ConditionalOnMissingBean`) : `ControlePorteeApplication`,
`ResolveurIdentiteDeleguee`, `GardeAdministrationCles` (aujourd'hui : tout
utilisateur authentifié administre les clés ; à restreindre par permission).

**Alignement stockage (dev3)** : plus aucune mention de `ged.storage.*` ni de
`GED_STORAGE_TEMP` ; `DEPLOIEMENT.md`, `ged.env.exemple` et
`SondeReferentielFichiers` suivent `ged.fichiers.racine` / `GED_STOCKAGE_RACINE`.

**Limites** : quotas comptés par instance (plusieurs instances derrière NGINX
= quota multiplié ; un compteur partagé en base serait à arbitrer) ; 64 Ko contrôlé
sur `Content-Length` pour le JSON et sur les parties non fichier en multipart ;
corps de réponse mémorisé plafonné à 1 Mo (au-delà, le rejeu rend le statut et
`Location` sans corps, toujours sans doublon) ; toute création via l'API exige désormais la clé
d'idempotence, y compris pour les scripts et clients existants.

**Tests** : `DB_NAME=ged_dev2 mvn -q test` vert, **348 tests**, 0 échec
(nouveaux : `IdempotenceApiTest` 6, `ClesApiTest` 11, `QuotasEtFormatCleApiTest` 4,
`ConventionsApiTest` 4 ; `SchemaLiquibaseTest` étendu). Les tests MockMvc reçoivent
une clé d'idempotence par défaut (`CleIdempotenceParDefautDesTests`). Front :
`ng test` vert (13 tests), `ng build` vert. Instable observé une fois, passé au
rejeu : `PrevisualisationApiTest.apercuPdf` (`ConcurrentModificationException`
dans `HeaderWriterFilter`, périmètre dev3).

## Vague 2 — acceptée (7f7c5ab)

### Amorce M1 — contrat d'erreurs problem+json : **PRÊT** (commit 6d1d09e)

À fusionner dès maintenant (dev1 et dev3 en dépendent). Contrat pour les lots :

- Lever `com.ipt.ged.common.erreur.ExceptionMetier` ou une sous-classe, avec un code
  du catalogue du domaine : `RequeteInvalideException` (400), `NonAuthentifieException`
  (401), `AccesRefuseException` (403), `RessourceIntrouvableException` (404, libellé
  fixe : absent et hors périmètre indiscernables), `ConflitException` (409),
  `RegleMetierException` (422), `TropDeRequetesException` (429, `Retry-After`
  calculé), `ServiceIndisponibleException` (503). Codes génériques :
  `CodesErreur` ; codes de domaine dans le paquet du domaine (majuscules et
  soulignés, jamais modifiés une fois publiés). `avec(nom, valeur)` ajoute un
  membre d'extension.
- Réponse : `application/problem+json` avec `type` (`urn:ged:erreur:<code>`),
  `title`, `status`, `detail`, `instance`, `code`, `traceId`, et `erreurs` (par
  champ) pour les 400 de validation. `GlobalExceptionHandler` : **dev2 seul** ;
  personne n'a à le modifier pour un nouveau refus.
- `ErreurFichierException` (dev3) reste traitée telle quelle, codes conservés.
  Suggestion à dev3 : la faire hériter d'`ExceptionMetier` (une ligne).
- **dev1** : brancher `ReponsesSecuriteProblem` (bean) dans `SecurityConfig` —
  `.exceptionHandling(e -> e.authenticationEntryPoint(r).accessDeniedHandler(r))`
  — pour que les 401/403 de la chaîne de sécurité soient au même format ; lever
  `TropDeRequetesException` pour l'anti-force brute au lieu de
  `ResponseStatusException` (celle-ci reste traduite, mais sans `Retry-After`).
- Front : `core/probleme.ts` (`messageErreur`, `codeErreur`, `erreursParChamp`),
  intercepteur en tête de chaîne ; les écrans existants lisent encore
  `error.message`/`error.errors`, alias posés par l'intercepteur.
- Tests : `ContratErreursTest` (tous les statuts, format, Retry-After, 404
  indiscernable, validation, exceptions Spring, 401/403 de la chaîne) ; tests
  existants passés de `$.message`/`$.errors` à `$.detail`/`$.erreurs`.
  `mvn test` : 303 verts.
- **Reprise de `GestionErreursIdentite` (dev1)** : une fois l'amorce fusionnée, les
  exceptions de `identite/erreur/` héritent d'`ExceptionMetier` avec leurs codes
  (`IDENTIFIANTS_REFUSES` 401, `TROP_DE_TENTATIVES` → `TropDeRequetesException` 429 avec
  `Retry-After`, `ANNUAIRE_INDISPONIBLE` 503, `ENTETE_CSRF_MANQUANT` 403,
  `RENOUVELLEMENT_REFUSE` 401) et l'advice `GestionErreursIdentite` est supprimé :
  plus aucun format d'erreur parallèle.

### Lot E4 — journal d'audit : **LIVRÉ sur ct/dev2** (commits 1925eb6 à 9c0916e)

| Réf. matrice | Exigence (DAT) | Livré | Statut proposé |
|---|---|---|---|
| 4.7 | 5.3.2 — problem+json, code métier stable | `common/erreur` (`ExceptionMetier` et sous-classes, `CodesErreur`, `Problemes`), `GlobalExceptionHandler` en RFC 7807, 401/403 de la chaîne (`ReponsesSecuriteProblem`), front `core/probleme.ts` | Identique dès que dev1 branche `ReponsesSecuriteProblem` dans `SecurityConfig` |
| 4.8 | 5.3.2 — codes 403, 404 hors périmètre, 409, 413, 415, 422, 429 | tous couverts, `Retry-After` sur 429, 404 à libellé fixe | Identique pour le format ; le « hors périmètre » réel vient d'E3 |
| 6.2 | 7.4.1 — `journal_audit` (acteur, application, IP, action, objet, avant/après, résultat, trace_id) | table partitionnée par mois, identifiant séquentiel, catalogue `ActionAudit`, `AuditService`, contrat `EvenementAudit` pour les lots | Identique pour le mécanisme ; exhaustivité après branchement dev1/dev3 (ci-dessous) |
| 6.3 | 7.4.2 — INSERT seul, déclencheurs, scellement SHA-256 chaîné exporté | privilèges `ged_app` INSERT/SELECT, déclencheur `BEFORE UPDATE OR DELETE OR TRUNCATE` (mère et partitions), scellement horaire en base et hors base (fichier ajout seul + journal technique), vérification mensuelle et à la demande tracée, métrique et alertes | Identique |
| 6.4 | 7.4.3 — écran, export CSV/JSON, rétention 10 ans | API `/api/v1/audit` (lecture seule), écran Angular « Journal d'audit », export avec scellements et SHA-256, consultation et export tracés, partitions jamais supprimées automatiquement | Proche : garde provisoire « authentifié » (compte unique aujourd'hui) ; permission `CONSULTER_AUDIT` avec E3 |

Événements tracés à ce jour : opérations d'administration des référentiels (espaces :
création, modification, déplacement, archivage, corbeille, restauration ; types, index,
plans, circuits, étiquettes, groupes : création, modification avec avant/après,
corbeille, restauration, une trace par objet pour les opérations de masse), décisions de
validation (approbation, rejet, relance), indexation enregistrée (valeurs et nom
recomposé), refus de droits (403), consultation, export et vérification du journal.

**Branchement attendu des autres lots** (contrat `com.ipt.ged.audit.EvenementAudit`,
écouté par `EcouteurEvenementsAudit` ; aucune modification de leur service par dev2) :
- **dev3** — `EvenementDocument extends EvenementAudit` avec ces méthodes par défaut :
  `action()` = `type()`, `objetType()` = `"DOCUMENT"`, `objetId()` = `documentId()`,
  `acteurUtilisateurId()` = `acteur().employeId()`, `acteurApplicationId()` =
  `acteur().applicationId()` ; `MetadonneesModifiees` redéfinit `avant()`/`apres()`.
  Idem pour `FichierInfecte` (`FICHIER_INFECTE`, résultat `REFUS`) et
  `AnomalieIntegrite` (`INTEGRITE_ANOMALIE`, `ECHEC`). Brancher aussi
  `VerificationAntivirus` sur `AnalyseurAntivirus::disponible`.
- **dev1** — `ConnexionReussie` (`CONNEXION_REUSSIE`, objet `UTILISATEUR`,
  `acteurUtilisateurId` = `utilisateurId`, `adresseIp`), `ConnexionEchouee`
  (`CONNEXION_REFUSEE`, résultat `REFUS`, motif = motif d'échec, `acteurNom` =
  identifiant saisi), `SessionsRevoquees` (`SESSIONS_REVOQUEES`) implémentent
  `EvenementAudit`. Faire exposer par le principal E2 l'identifiant utilisateur
  utilisé par `ActeurCourant.employeId()` (aujourd'hui l'employé).
- **dev1 (E3)** — déclarer un bean `GardeConsultationAudit` fondé sur
  `CONSULTER_AUDIT` ; tracer `HABILITATION_MODIFIEE` et les 404 hors périmètre utiles.
- **dev1 (vague 2)** — `SchemaLiquibaseTest` modifié par dev2 (tables d'audit,
  partitions écartées, jalon `socle-e1` compté hors lots postérieurs) : union simple
  avec les tables d'identité.

Tests (tous sur PostgreSQL réel, `DB_NAME=ged_dev2`) : `JournalAuditInalterableTest`
(connecté en `ged_app` : UPDATE/DELETE/TRUNCATE refusés, SQLSTATE 42501 ; connecté en
`ged_owner` : déclencheur sur mère et partition ; succès annulé avec sa transaction,
refus conservé), `ScellementAuditTest` (chaîne, export, **altération par `ged_owner`
avec déclencheur désactivé → `EMPREINTE_DIFFERENTE`**, **scellement réécrit en base →
`EXPORT_DIFFERENT`**), `ConsultationAuditApiTest` (filtres, pagination, export CSV/JSON
et empreinte, injection CSV neutralisée, aucune écriture, consultation tracée),
`AuditOperationsApiTest`, `AuditDecisionsValidationTest`, `SchemaLiquibaseTest`
(conventions et retour arrière). **`mvn test` : 323 verts.** Front : 8 tests verts,
build vert ; écran vérifié dans le navigateur contre le back-end local.

Autres ajustements de la vague :
- `server.forward-headers-strategy: native` ; la valve Tomcat ne croit
  `X-Forwarded-For` que des proxys de `ged.journalisation.proxys-de-confiance`
  (même règle que le filtre MDC), au lieu de tous les réseaux privés.
- Sonde `annuaire` : celle de dev1 fait foi (même nom de bean : la mienne est retirée) ;
  exclue du groupe `readiness`, alerte dédiée `GedAnnuaireIndisponible`.
- Exploitation mise à jour pour E2 (`GED_LDAP_*`, `GED_JWT_KEYSTORE*`,
  `GED_ADMINISTRATEURS`, `GED_SESSION_DUREE_ABSOLUE`), sauvegarde de la clé RS256.
- **Pour dev3** : le contrôle « antivirus obligatoire en prod » de
  `fichier/ConfigurationFichiers` ne couvre pas le profil `uat` (le profil `uat` importe
  `prod` mais le contrôle teste le nom de profil) ; à étendre, fichier non modifié par dev2.

Reste pour dev2 : vérification du scellement sur la volumétrie réelle (5 millions
d'enregistrements par an : durée de la vérification mensuelle à mesurer en UAT) ;
branchement des événements dev1/dev3 à contrôler après leurs fusions.

## Vague 1 — livrée (fusion 95b11e1)

E0 (outillage), E4 partie journalisation technique (7.1, 7.3.1), E10 (exploitation).
Décisions de la revue technique intégrées : **D4** (un seul contrôleur de domaine,
sonde à N contrôleurs), **D6** (objectif de disponibilité en recherche : 24 h,
paramétrable), **D5** (aucun Python dans les livrables). Alignement sur le lot
stockage de dev3 (210 Mo par requête, clamd `StreamMaxLength 200M`, tmpfs des
fichiers en clair, `GED_KEYSTORE_*`).

## Exigences traitées (référence de la matrice : section.ligne)

| Réf. | Exigence (DAT) | Livré | Statut proposé |
|---|---|---|---|
| 5.14 | 6.2.3 A06 — OWASP Dependency-Check à chaque construction | plugin `dependency-check-maven` 12.2.2 lié à `verify`, échec à CVSS ≥ `ged.cvss.seuil` (7), job CI dédié, fichier de suppressions encadré | Identique **sous réserve** du secret `NVD_API_KEY` (sans clé, la base NVD répond 429 : analyse non exécutée sur le poste) |
| 7.4 | 8.3 — registre des dépendances, version et licence | SBOM CycloneDX Maven (`target/bom.json`, `bom.xml`) et npm (`npm run sbom`), registre `docs/DEPENDANCES.md` généré par `outils/registre-dependances.mjs`, licences classées au regard de la cession (11.2) | Identique |
| 7.6 | 9.2 — non-régression | `.github/workflows/ci.yml` : tests back sur `postgres:16`, tests front, à chaque push et demande de fusion | Identique (CI non encore exécutée sur la forge : aucun push autorisé) |
| 7.7 | 9.3 — UAT et déploiement par module | profil `uat` (importe `prod`, test d'équivalence), `deployer.sh --module back|front` | **Proche** : le déploiement « par processus métier » (DAT 9.3) supposerait des modules activables séparément ; la granularité livrée est back / front |
| 7.8 | 9.4 — branche protégée, accès MMED | procédure exacte `docs/exploitation/FORGE.md` (GitHub, GitLab, Gitea), remise Article 45 | **Proche** tant que pm n'a pas appliqué les réglages sur la forge (action externe) |
| 6.0 | 7.1 — pattern de l'Article 50, MDC | `FiltreContexteRequete` (ip via XFF de confiance, traceId/spanId W3C), `FiltreUtilisateurJournalisation` (username), `DecorateurTacheMdc`, `logback-spring.xml` au pattern exact | Identique |
| 6.1 | 7.3.1 — niveaux, rotation, rétention | INFO par défaut, niveau externalisé, rotation quotidienne et 100 Mo, gzip, 90 jours | Identique |
| 0.5 | 2.2 — front hébergé sur NGINX | `deploiement/nginx/ged.conf`, config.json d'environnement hors paquet | Identique (vérifié sur le papier) |
| 5.9 | 6.2.1 — TLS 1.2 min, LDAPS, base chiffrée | TLS 1.2/1.3 NGINX ; `sslmode=verify-full` dans le déploiement et la sauvegarde ; LDAPS dans la sonde | **Proche** : l'URL JDBC de l'application (lot E1) doit porter `sslmode`/`sslrootcert` ; LDAPS applicatif = E2 |
| 5.10 | 6.2.2 — NGINX durci | server_tokens, HSTS, CSP Angular (sans script en ligne), X-Frame-Options, nosniff, limitation de débit, HTTPS forcé | Identique (vérifié sur le papier ; CSP éprouvée sous Chromium) |
| 5.17 | 6.5 — sauvegarde, RPO/RTO, restauration testée | scripts base (WAL, physique, logique), fichiers après la base, clés chiffrées à part, restauration pas à pas, rapprochement des orphelins, test à blanc exécuté | Identique (exercice UAT sur données réelles à planifier avant la mise en production) |
| 5.19 | 6.7 — Micrometer, Prometheus, alertes | `micrometer-registry-prometheus`, port de management interne, sondes base/référentiel/annuaire/antivirus/files, métriques GED, `alertes.yml` aux seuils du DAT | Identique (règles vérifiées sur le papier) |
| 7.11 | 10.1 — procédure scriptée | `deployer.sh` : sauvegarde, Liquibase validate/tag/update par ged_owner, arrêt progressif, sonde, test de fumée, retour arrière | Identique (non exécuté de bout en bout : pas de systemd sur le poste) |
| 7.12 | 10.2 — JAR en service système derrière NGINX | `ged-backend.service` durci, `EnvironmentFile` 0400, redémarrage automatique | Identique (vérifié sur le papier) |

Également couvert : 5.13 (6.2.3 A05, Actuator restreint au réseau interne) et 5.18
(6.7, sondes LDAP, ClamAV, file OCR) — finitions prévues en E11.

## Tests

`mvn -q test` : **vert**, 182 tests, 0 échec (ligne de base de la branche : 143 ; nouveaux :
`FiltreContexteRequeteTest` (MDC rempli et vidé, en-têtes forgés, XFF de confiance
ou non, dispatch d'erreur), `DecorateurTacheMdcTest`, `PatternJournalisationTest`
(pattern exact lu dans `logback-spring.xml`, format produit, rotation/rétention),
`SondesTest`, `SondeAnnuaireTest`, `SecuritePortManagementTest`,
`SupervisionIntegrationTest` (sondes, métriques Prometheus, port de management,
ordre des filtres, exécuteur asynchrone), `ProfilUatTest` — 39 tests).
Front : `ng test` vert (2 tests), `ng build` de production vert.

## Vérifié uniquement par simulateur ou sur le papier

- **ClamAV** : clamd simulé (protocole `zPING`/`PONG` réel).
- **Annuaire LDAP** : annuaire LDAP v3 simulé (BER, liaison anonyme, RootDSE), client
  JNDI réel ; un, deux, aucun contrôleur joignable.
- **NGINX** : non installé, configuration relue ; CSP servie par un serveur local
  aux mêmes en-têtes, paquet compilé chargé sous Chromium sans violation.
- **Prometheus / Alertmanager** : non installés, syntaxe YAML contrôlée, PromQL relu.
- **OWASP Dependency-Check** : configuration acceptée par le plugin, analyse
  bloquée par l'absence de clé NVD (HTTP 429).
- **systemd, deployer.sh** : syntaxe contrôlée ; appels du test de fumée rejoués à
  la main contre le back-end local (port 18082, management 18092 : santé et
  Prometheus servis sur le port de management, 401/404 sur le port de l'API,
  traceId propagé, username et ip dans les journaux).
- **Sauvegarde / restauration** : exécutées réellement (compte rendu dans
  `docs/exploitation/RESTAURATION.md`), sur jeu d'essai synthétique ; archivage WAL
  et restauration à un instant donné sur une instance PostgreSQL jetable.

## Données laissées en place (essais)

- Base `ged_dev2`, schéma `essai_restauration` (jeu d'essai du test à blanc) et base
  `ged_dev2_restauration` (cible de restauration, recréée à chaque exercice).
- Base H2 locale du profil dev (`backend/data`, non versionnée) : deux documents
  « FUMEE-test-… » déposés puis mis à la corbeille lors de l'essai du test de fumée.

## Points pour les autres membres (à arbitrer par pm)

- **pm — pom.xml** : en plus de `<build><plugins>` et de la dépendance Prometheus,
  une propriété `<ged.cvss.seuil>7</ged.cvss.seuil>` a été ajoutée dans
  `<properties>` : sans elle, le seuil ne peut pas être à la fois une valeur par
  défaut et surchargeable (`-Dged.cvss.seuil=`). Dépendance Prometheus placée après
  `spring-boot-starter-validation`, hors des zones modifiées par dev1.
- **pm — application.yml** : une ligne `spring.config.import: classpath:exploitation.yml`
  et remplacement du bloc `management` final par un renvoi vers `exploitation.yml`.
- **pm — forge** : créer le secret `NVD_API_KEY`, appliquer `FORGE.md`.
- **dev1 (E1)** : ajouter `sslmode`/`sslrootcert` à l'URL JDBC (6.2.1) ; en UAT/PROD
  `SPRING_LIQUIBASE_ENABLED=false` (la migration passe par `deployer.sh` avec
  `ged_owner`, l'application n'a plus besoin de ses identifiants) ; vérifier en UAT
  que `liquibase status` via le CLI ne voit aucun changeset en attente sur une base
  migrée par l'application (mêmes chemins de changelog).
- **dev1 (E2)** : les contrôles « fail-closed » propres au profil `prod`
  (`ServiceJeton` : clé obligatoire ; `SecurityConfig` : Swagger fermé) ne
  s'appliquent pas au profil `uat` ; à étendre à `uat` avec l'authentification
  RS256. `SecurityConfig` ouvre encore `/actuator/health` sur le port de l'API :
  sans effet (Actuator n'y est plus servi), à nettoyer. Sonde annuaire : lit
  `spring.ldap.urls` par défaut.
- **dev3 (E5/E6)** : brancher `VerificationAntivirus` sur `AnalyseurAntivirus::disponible` ;
  déclarer une `FileDeTraitement` pour `ocr_job` ; publier le délai OCR sous le
  Timer `ged.ocr.delai.disponibilite` (histogramme déjà configuré, objectif 24 h).
- **Registre des dépendances** : `mysql-connector-j` (GPL-2.0 avec exception FOSS)
  est signalé ; il disparaît avec E1. Après chaque fusion modifiant les
  dépendances, régénérer `docs/DEPENDANCES.md` (`mvn package`, `npm run sbom`,
  `node outils/registre-dependances.mjs`).

## Reste à faire

- Exécuter la CI sur la forge (après push par pm) et l'analyse OWASP avec la clé NVD.
- Premier passage réel de `deployer.sh` en DEV Linux, puis rollback Liquibase testé
  en UAT (DAT 10.1).
- `nginx -t`, contrôle TLS externe, `promtool check rules` à l'installation.
- Exercice de restauration en UAT sur données réelles (RTO chronométré).
- 7.7 : décider avec MMED de la granularité du « déploiement par processus métier ».
