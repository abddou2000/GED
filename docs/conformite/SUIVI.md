# Tableau de suivi — conformité au dossier technique et d'intégration V3

Tenu par **pm** sur la branche `conformite-technique`. Mis à jour à chaque fusion et à chaque
recette de qa. Source : `MATRICE-TECHNIQUE.md` (115 lignes, reprises dans l'ordre de la
matrice, numéros T-001 à T-115) et contrôle de cohérence de pm contre le PDF (22 lignes
ajoutées, numéros P-01 à P-22). Le PDF fait foi en cas de doute.

Dernière mise à jour : 26/09/2026 — vague 1 en cours (aucun commit encore sur les branches
`ct/dev1`, `ct/dev2`, `ct/dev3`, `ct/qa` au moment de la mise à jour).

## Statuts courants

| Statut | Signification | Qui le pose |
|---|---|---|
| À faire | Non commencé. | pm |
| En cours | Travail engagé sur une branche `ct/*` (vague en cours). | pm, d'après le suivi du membre |
| Livré | Fusionné dans `conformite-technique`, tests automatisés verts après fusion. | pm |
| Vérifié | Recette de qa passée sur `conformite-technique` (script de recette et preuve archivés). | qa, reporté par pm |
| Identique | pm a relu la ligne contre le PDF : elle peut passer à « Identique » dans la matrice. | pm |

Une ligne dont le statut initial est « Identique » mais que pm conteste après relecture du PDF
est marquée **« Identique contesté »** dans la colonne Init. et suivie comme un écart
(statut courant « À faire » ou « En cours »).

## 1. Tableau de bord

### Compteurs par statut courant (137 lignes)

| Statut courant | Lignes | Part |
|---|---|---|
| À faire | 78 | 57 % |
| En cours | 36 | 26 % |
| Livré | 0 | 0 % |
| Vérifié | 0 | 0 % |
| Identique | 23 | 17 % |
| **Total** | **137** | 100 % |

Taux de conformité (lignes « Identique ») : **23 / 137 = 17 %**. La matrice annonçait 29 / 115
(25 %) : 6 lignes « Identique » sont contestées par pm (T-042, T-050, T-053, T-062, T-069,
T-074) et 22 exigences du PDF absentes de la matrice ont été ajoutées.

### Compteurs par statut initial

| Statut initial | Matrice | Ajoutées par pm | Total |
|---|---|---|---|
| Identique | 23 (+ 6 contestées) | 0 | 29 |
| Proche | 31 | 0 | 31 |
| Non | 55 | 22 | 77 |
| **Total** | 115 | 22 | 137 |

### Avancement par étape

| Étape | Contenu | Vague | Lignes | À faire | En cours | Livré | Vérifié | Identique |
|---|---|---|---|---|---|---|---|---|
| E0 | Cadrage et outillage | 1 | 6 | 0 | 6 | 0 | 0 | 0 |
| E1 | Socle de données | 1 | 13 | 1 | 12 | 0 | 0 | 0 |
| E2 | Identité et sessions | 2 | 12 | 12 | 0 | 0 | 0 | 0 |
| E3 | Autorisation et confidentialité | 3 | 7 | 7 | 0 | 0 | 0 | 0 |
| E4 | Journalisation et audit | 1 (logs) + 2 | 6 | 4 | 2 | 0 | 0 | 0 |
| E5 | Stockage sécurisé des fichiers | 1 + 2 | 9 | 2 | 7 | 0 | 0 | 0 |
| E6 | OCR asynchrone et plein texte (+ dépôt en deux temps) | 3 | 15 | 15 | 0 | 0 | 0 | 0 |
| E7 | Modèle documentaire et cycle de vie | 4 | 11 | 11 | 0 | 0 | 0 | 0 |
| E8 | Workflow, conservation, notifications | 5 | 5 | 5 | 0 | 0 | 0 | 0 |
| E9 | API d'intégration | 2 à 5 | 14 | 14 | 0 | 0 | 0 | 0 |
| E10 | Exploitation et infrastructure | 1 + 5 | 13 | 4 | 9 | 0 | 0 | 0 |
| E11 | Recette de conformité (lignes déjà Identique, points hors code) | 6 | 26 | 3 | 0 | 0 | 0 | 23 |
| **Total** | | | **137** | **78** | **36** | **0** | **0** | **23** |

### Répartition par responsable

| Responsable | Lignes | En cours (vague 1) |
|---|---|---|
| dev1 | 47 | 12 (E1) |
| dev2 | 53 | 17 (E0, E10, logs E4) |
| dev3 | 36 | 7 (E5 composants) |
| pm | 1 | 0 (P-18, engagement contractuel) |

qa vérifie toutes les lignes (passage à « Vérifié ») ; il n'est responsable d'aucune ligne
de développement.

### Écarts entre la matrice et la feuille de route relevés par pm

- **T-009** (briques Tika, ClamAV, LibreOffice, veraPDF, keystore, Prometheus) est rattachée à
  E5 par la feuille de route, mais veraPDF n'arrive qu'en E7 et Prometheus en E10 : la ligne
  ne peut être close qu'en E10. Suivie en E5, clôture en E10.
- **T-025** (modèle logique en sept groupes) est rattachée à E1, mais les groupes Identités,
  Habilitations, Traçabilité et Circuits ne sont créés qu'en E2, E3, E4, E8 et E9 : clôture à
  la dernière table (E9).
- **T-043** (dépôt avec métadonnées en une opération) et **T-115** (dépôt en deux temps) sont
  déplacées de E9 et E7 vers E6 : le pipeline de dépôt appartient à dev3, qui le réécrit en E5
  et E6 (HTTP 202, job OCR dans la transaction du temps 1). Voir `PLAN-VAGUES.md`.
- **T-097** (rattachement multiple) est scindée : table `document_rattachement` et union des
  droits en E3 (le point d'application unique en a besoin), points d'entrée en E7.
- La feuille de route prévoit Testcontainers en E1 : interdit sur ce poste (pas de Docker). Les
  tests visent la base PostgreSQL locale de chaque membre.

## 2. Tableau détaillé

Légende Init. : I = Identique, P = Proche, N = Non, IC = Identique contesté par pm.

### Architecture et stack imposée (§2)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-001 | 2.1 | Séparation stricte front et back, communication uniquement par API | I | E11 | dev2 | Identique | Revue d'architecture en recette : aucun accès front hors `/api/v1`. |
| T-002 | 2.2 | Front-end Angular | I | E11 | dev2 | Identique | `frontend/package.json` ; version LTS courante confirmée en recette. |
| T-003 | 2.2 | Back-end Java 17 et Spring Boot 3 (Security, Data JPA, Bean Validation) | I | E11 | dev1 | Identique | `backend/pom.xml`. |
| T-004 | 2.2 | SGBD PostgreSQL 16 ou plus, configuration de recherche arabe vérifiée | N | E1 | dev1 | En cours | Profils dev, test, uat, prod sur PostgreSQL ; driver MySQL et H2 retirés ; test qui vérifie la présence de la configuration `arabic` ; `mvn test` vert sur PostgreSQL. |
| T-005 | 2.2 | Outil de migration Liquibase 4 | N | E1 | dev1 | En cours | Flyway retiré du `pom.xml` ; changelog maître Liquibase ; base vierge créée uniquement par Liquibase. |
| T-006 | 2.2 | Hébergement du front sur un serveur NGINX | P | E10 | dev2 | En cours | Configuration NGINX livrée dans le dépôt, servant le paquet Angular et `config.json`. Vérifiable seulement par relecture sur ce poste (NGINX absent). |
| T-007 | 2.3 | Une API REST unique pour le front et les applications tierces | I | E11 | dev2 | Identique | Recette E9 : une application de test utilise les mêmes points d'entrée que le front. |
| T-008 | 2.3.2 | Briques Tesseract 5 et Apache PDFBox | I | E11 | dev3 | Identique | À revérifier après E6 (modèle `ara` ajouté). |
| T-009 | 2.3.2 | Briques Apache Tika, ClamAV, LibreOffice, veraPDF, keystore ou KMS, Prometheus | N | E5 (clôture E10) | dev3 | En cours | Chaque brique intégrée derrière une interface, testée avec un simulateur si absente du poste ; Prometheus livré par dev2 en E10 ; veraPDF en E7. |

### Authentification et identités (§3)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-010 | 3.2 | Aucun référentiel local de mots de passe | N | E2 | dev1 | À faire | Table des comptes et `CompteSeeder` supprimés ; aucune colonne de mot de passe dans le schéma (requête sur `information_schema`). |
| T-011 | 3.3 | Authentification LDAPS search-then-bind avec compte de service, Spring Security LDAP | N | E2 | dev1 | À faire | Tests avec serveur LDAP embarqué UnboundID (simulateur) : recherche par `sAMAccountName` ou `userPrincipalName`, puis bind avec le DN trouvé ; mot de passe jamais stocké ni journalisé. |
| T-012 | 3.3 | Provisionnement automatique sans rôle, clé objectGUID | N | E2 | dev1 | À faire | Test : première connexion crée `utilisateur` sans rôle, clé `objectGUID` ; changement de login sans doublon. |
| T-013 | 3.3 | Jeton d'accès court, sans permissions embarquées | P | E2 | dev1 | À faire | Test : jeton de 15 minutes, porte l'identifiant GED et l'identifiant d'annuaire, aucune permission. |
| T-014 | 3.4.1 | JWT signé RS256 avec clé privée en coffre, conservé en mémoire côté Angular | N | E2 | dev1 | À faire | Test de signature RS256, clé privée lue hors dépôt ; Angular : aucune écriture en `localStorage` ni `sessionStorage` (recherche dans le code et test). |
| T-015 | 3.4.1 | Jeton de renouvellement en cookie httpOnly, 8 h max, table de sessions, révocation | N | E2 | dev1 | À faire | Tests : cookie `HttpOnly; Secure; SameSite=Strict`, durée absolue 8 h, inactivité 30 min, rotation à chaque usage, réutilisation d'un jeton consommé qui révoque la famille, table `session`. |
| T-016 | 3.4.1 | Protection CSRF : jeton uniquement dans l'en-tête Authorization | I | E11 | dev1 | Identique | À revérifier après E2 avec P-03 (point de renouvellement fondé sur cookie). |
| T-017 | 3.4.1 | Anti-force brute : 5 essais par minute par IP et identifiant, échecs journalisés | P | E2 | dev1 | À faire | Tests : 6e essai en une minute refusé en 429, par IP et par identifiant ; chaque échec produit un événement d'audit (écouteur de dev2, E4). |
| T-018 | 3.4.2 | Cache annuaire de 15 minutes, désactivation AD prise en compte en 5 minutes | N | E2 | dev1 | À faire | Tests : table `cache_annuaire` expirant à 15 min ; `userAccountControl` relu à chaque renouvellement et au plus toutes les 5 min (paramétrable). |

### Données et migrations (§4.1, §4.2, §12.1)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-019 | 4.2.1 | Aucune modification de schéma hors migration versionnée | P | E1 | dev1 | En cours | `ddl-auto: validate` sur tous les profils ; aucun DDL hors changelog. |
| T-020 | 4.2.1 | Amorçage initial par migration, référentiels métier uniquement via l'interface | P | E1 | dev1 | En cours | Seeders Java d'amorçage supprimés ; changesets étiquetés `data-initial` sans donnée métier propre à un environnement. |
| T-021 | 4.2.2 | Conventions de nommage des changesets et des objets (snake_case, idx_, uk_, fk_) | P | E1 | dev1 | En cours | Fichiers `AAAAMMJJHHmm_objet_metier.xml` ; contrôle automatique des noms d'objets (`idx_<table>_<colonnes>`, `uk_`, `fk_`, `ck_`, clé `id`, clés étrangères `<table>_id`). |
| T-022 | 4.2.2 | Retour arrière explicite par changeset, schéma expand et contract | N | E1 | dev1 | En cours | Chaque changeset porte un `rollback` ; test `updateTestingRollback` sur base vierge. |
| T-023 | 4.2.3 | Trois rôles PostgreSQL : propriétaire, application, lecture seule | N | E1 | dev1 | En cours | Script idempotent des rôles `ged_owner`, `ged_app`, `ged_readonly`, aucun superutilisateur ; application exécutée sous `ged_app`. |
| T-024 | 12.1 | Clés primaires UUID | N | E1 | dev1 | En cours | Toutes les clés primaires en `uuid` ; front adapté (identifiants en chaîne). |
| T-025 | 12.1 | Modèle logique en sept groupes de tables | P | E1 (clôture E9) | dev1 | En cours | Toutes les tables du tableau 12.1 présentes, avec leurs noms exacts, après E9. |

### Moteur OCR et recherche plein texte (§4.3, §4.4)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-026 | 4.3.1 | Tesseract 5, moteur LSTM, open source | I | E11 | dev3 | Identique | À revérifier après E6. |
| T-027 | 4.3.2 | Interface de moteur permettant de changer d'OCR sans impact | I | E11 | dev3 | Identique | Interface `OcrEngine` (nom du PDF) utilisée par le worker. |
| T-028 | 4.3.2 | Protocole comparatif sur 300 pages (CER, WER, débit) | N | E6 | dev3 | À faire | Outillage de mesure CER/WER/débit prêt et testé sur un jeu interne ; exécution et rapport dès réception de l'échantillon MMED. |
| T-029 | 4.3.3 | Cloisonnement : l'OCR n'alimente aucun champ d'index | N | E6 | dev3 | À faire | Pré-remplissage des index retiré (back et Angular) ; test : aucun champ d'index écrit par la chaîne OCR. |
| T-030 | 4.3.4 | Traitement asynchrone : table ocr_job, worker SKIP LOCKED, réponse HTTP 202 | N | E6 | dev3 | À faire | Tests : dépôt qui répond 202 avec `EN_ATTENTE_OCR` ; deux workers concurrents sans double traitement ; interface `OcrJobQueue`. |
| T-031 | 4.3.4 | Langues fra et ara, langue fixable par type de document | P | E6 | dev3 | À faire | Modèles `fra` et `ara` dans `tessdata`, défaut `fra+ara`, langue par type ; test sur un scan arabe. |
| T-032 | 4.3.4 | Aucun plafond de pages, texte multi-pages agrégé | P | E6 | dev3 | À faire | Paramètre de plafond supprimé ; test sur un PDF de plus de 5 pages ; traitement page par page. |
| T-033 | 4.3.4 | Aucune copie en clair sur disque persistant (tmpfs) | N | E6 | dev3 | À faire | Déchiffrement et rendu en mémoire ou dans un répertoire tmpfs configurable ; test qui vérifie l'absence de fichier en clair après traitement. |
| T-034 | 4.3.4 | Délai de 60 s par page, 3 tentatives, statut OCR_ECHEC, document « non interrogeable » | P | E6 | dev3 | À faire | Tests : 60 s par page, reprises à 1, 5 et 30 min, `OCR_ECHEC` avec motif, marquage « contenu non interrogeable » visible dans les résultats. |
| T-035 | 4.3.4 | Texte stocké dans document_texte, délai de disponibilité mesuré | N | E6 | dev3 | À faire | Table `document_texte(document_id, version_id, langue, texte, tsv)` ; métrique `ocr_delai_disponibilite` exposée. |
| T-036 | 4.3.4 | Couche texte PDFBox d'abord, sinon rendu à 300 dpi et Tesseract | I | E11 | dev3 | Identique | À revérifier après E6 (chaîne déplacée dans le worker). |
| T-037 | 4.4 | Recherche PostgreSQL tsvector et index GIN, configurations french et arabic | N | E6 | dev3 | À faire | `tsv = to_tsvector('french', texte) \|\| to_tsvector('arabic', texte)`, `unaccent`, index GIN ; interface `SearchIndexer`. |
| T-038 | 4.4 | websearch_to_tsquery, extraits ts_headline, tri ts_rank_cd, dédoublonnage | N | E6 | dev3 | À faire | Tests : guillemets et exclusion, extraits calculés sur la seule page affichée après filtre de droits, tri par pertinence, une ligne par document (`EXISTS` sur les emplacements). |
| T-039 | 4.4.1 | Réindexation incrémentale et complète | N | E6 | dev3 | À faire | Test de mise à jour de la seule ligne concernée à chaque version ; tâche de réindexation complète réservée à l'Administrateur, avec progression. |

### Intégration et API (§5)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-040 | 5.1 | Point d'entrée de réception : enregistrer, horodater, identifier source et déposant | P | E9 | dev2 | À faire | Colonne source ou canal renseignée à chaque dépôt (interface, application appelante) ; test par clé API. |
| T-041 | 5.2 | Applications tierces soumises au même modèle d'habilitation et à la même journalisation | N | E9 | dev2 | À faire | Tests : une application n'accède qu'à sa portée via `AccessPredicate` ; chaque appel audité avec `acteur_application_id`. |
| T-042 | 5.3 | Opérations : création de dossier, téléchargement, versement | IC | E9 | dev2 | À faire | Contesté : les chemins du contrat (`POST /noeuds/{id}/dossiers`, `GET /documents/{id}/contenu`, `POST /documents/{id}/versions`) ne sont pas ceux de la GED. Test de contrat sur les chemins du 5.3.1. |
| T-043 | 5.3 | Opérations : dépôt avec métadonnées en une seule opération | P | E6 | dev3 | À faire | `POST /documents` multipart (fichier + métadonnées JSON) en une requête ; avancé en E6 avec le dépôt en deux temps. |
| T-044 | 5.3 | Opérations : dépôt pour le compte d'un utilisateur, rattachement, consultation des droits | N | E9 | dev2 | À faire | `X-On-Behalf-Of` (dev2), `POST/DELETE /documents/{id}/rattachements` (dev1, E7), `GET /documents/{id}/droits` et `/noeuds/{id}/droits?pourUtilisateur` (dev1, E3). |
| T-045 | 5.3.1 | Recherche multicritère et plein texte filtrée par droits, paginée | P | E6 | dev3 | À faire | `POST /recherches` : critères en ET avec le plein texte, filtre de droits à la source, pagination, total limité au périmètre. |
| T-046 | 5.3.2 | JSON UTF-8, dates ISO 8601 en UTC, identifiants opaques UUID | P | E1 | dev1 | En cours | Identifiants UUID dans tous les DTO ; dates sérialisées en UTC ; test de sérialisation. |
| T-047 | 5.3.2 | Erreurs au format problem+json (RFC 7807) avec code métier stable | P | E9 (avancé en vague 2) | dev2 | À faire | `application/problem+json`, champ `code` stable, dictionnaire d'erreurs par champ pour les 400 ; catalogue des codes documenté. |
| T-048 | 5.3.2 | Codes 403, 404 hors périmètre, 409, 413, 415, 422, 429 | P | E9 | dev2 | À faire | Test par code ; 413/415/422 posés par dev3 (E5), 404 hors périmètre par dev1 (E3), 429 par dev1 (connexion) et dev2 (quotas). |
| T-049 | 5.3.2 | En-tête Idempotency-Key obligatoire sur les créations | N | E9 | dev2 | À faire | Table `idempotence_cle` ; tests : rejeu identique = réponse initiale, contenu différent = 422, mémorisation 24 h par application. |
| T-050 | 5.3.2 | Pagination page et taille, plafond 200, tri sur liste blanche | IC | E3 | dev1 | À faire | Contesté : le PDF exige une taille par défaut de 50 et un total calculé sur le seul périmètre autorisé. Tests après E3. |
| T-051 | 5.3.2 | Taille de fichier paramétrable par type, quotas de requêtes par clé | P | E9 | dev2 | À faire | Tests : 600 requêtes par minute et 100 000 par jour par clé, 429 avec `Retry-After`. |
| T-052 | 5.3.2 | Version majeure dans l'URL (/api/v1) | I | E11 | dev2 | Identique | Voir aussi P-08 (politique de compatibilité). |
| T-053 | 5.3 | Spécification OpenAPI 3 complète | IC | E9 | dev2 | À faire | Contesté : le PDF exige des schémas de payload champ par champ ; exemples absents. Spécification relue et exemples ajoutés. |
| T-054 | 5.4 | Clés API : cycle de vie, secret haché SHA-256, X-API-Key, portée, quotas, expiration, IP | N | E9 | dev2 | À faire | Format `ged_<env>_<identifiant>_<secret>` (256 bits), affichage unique, empreinte SHA-256, expiration 12 mois, chevauchement 7 jours, liste d'adresses, `cle_api_portee`, écran d'administration. |
| T-055 | 5.5 | Délégation d'identité par X-On-Behalf-Of, double identité dans l'audit | N | E9 | dev2 | À faire | Tests : clé sans attribut délégation = 403 ; identité invalide ou désactivée = 422 `IDENTITE_DELEGUEE_INVALIDE` ; écriture avec les droits de l'application et déposant délégué ; lecture en intersection ; audit avec les deux identités. |

### Sécurité applicative et infrastructure (§6)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-056 | 6.1.1 | Fichiers stockés hors base | I | E11 | dev3 | Identique | À revérifier après E5 (interface `FileStore`). |
| T-057 | 6.1.1 | Identifiant opaque, arborescence aa/bb, écriture unique et atomique | P | E5 | dev3 | En cours | `/<racine>/aa/bb/<uuid>.enc` ; écriture fichier temporaire, `fsync`, renommage ; test d'interruption. |
| T-058 | 6.1.2 | Chiffrement AES-256-GCM, clé par version, clé maîtresse en keystore, rotation | N | E5 | dev3 | En cours | IV de 96 bits unique, DEK par version enveloppée par la KEK (table `cle_fichier`), keystore PKCS#12 via `KeyProvider`, rotation par réenveloppement ; test d'altération détectée. |
| T-059 | 6.1.4 | Empreinte SHA-256 par version, vérification périodique | N | E5 | dev3 | En cours | `version_document.empreinte` du contenu en clair ; tâche mensuelle et commande à la demande ; divergence = alerte et événement d'audit. |
| T-060 | 6.1.4 | Copie de conservation PDF/A-2 validée par veraPDF | N | E7 | dev3 | À faire | Copie PDF/A-2 à l'archivage (LibreOffice ou PDFBox), validée par veraPDF, chiffrée ; original conservé ; échec non bloquant et signalé. Simulateur pour LibreOffice sur ce poste. |
| T-061 | 6.1.5 | Type réel détecté par le contenu (Apache Tika) | N | E5 | dev3 | En cours | Test : fichier renommé refusé en 415 ; liste blanche par type (PDF, TIFF, JPEG, PNG, texte, CSV, Office, OpenDocument). |
| T-062 | 6.1.5 | Taille maximale par type, plafond de plateforme | IC | E5 | dev3 | À faire | Contesté : plafond de plateforme de 200 Mo aligné sur NGINX et sur les limites multipart de Spring (100 Mo aujourd'hui). Test 413. |
| T-063 | 6.1.5 | Antivirus ClamAV, refus si indisponible | N | E5 | dev3 | En cours | Protocole clamd `INSTREAM` ; tests avec serveur clamd factice : infecté = 422 `FICHIER_INFECTE` et audit ; indisponible = refus. Vérifié seulement par simulateur sur ce poste. |
| T-064 | 6.1.6 | Prévisualisation déchiffrée à la volée, droits appliqués, audit | N | E5 | dev3 | En cours | PDF et images en flux sans copie en clair ; bureautique convertie par LibreOffice, cache chiffré ; mêmes contrôles que le téléchargement ; événement d'audit distinct ; visionneuse Angular. |
| T-065 | 6.2.1 | TLS 1.2 minimum, certificat MMED, LDAPS et base chiffrés | N | E10 | dev2 | En cours | Configuration NGINX (TLS 1.2+, suites modernes) ; `sslmode=verify-full` et LDAPS configurés pour UAT et PROD. Relecture seulement sur ce poste. |
| T-066 | 6.2.2 | NGINX durci : server_tokens off, HSTS, CSP, limitation de débit, HTTPS forcé | N | E10 | dev2 | En cours | Configuration livrée avec `server_tokens off`, HSTS, `X-Content-Type-Options`, `X-Frame-Options`, CSP adaptée à Angular, `limit_req` sur connexion et API, redirection HTTPS, `client_max_body_size 200m`. |
| T-067 | 6.2.3 | A01 : refus par défaut, 404 pour un objet hors périmètre | P | E3 | dev1 | À faire | Tests de chaque chemin d'accès : objet hors périmètre = 404, indistinct d'un objet absent. |
| T-068 | 6.2.3 | A03 : requêtes paramétrées, liste blanche de tri, Bean Validation | I | E11 | dev1 | Identique | À revérifier après E6 (requêtes plein texte natives paramétrées). |
| T-069 | 6.2.3 | A05 : Actuator restreint | IC | E10 | dev2 | En cours | Contesté : le PDF exige Actuator limité au réseau interne. Port de gestion séparé ou restriction NGINX, et test. |
| T-070 | 6.2.3 | A06 : OWASP Dependency-Check à chaque construction | N | E0 | dev2 | En cours | Plugin au build Maven et contrôle npm ; seuil de blocage ; rapport archivé ; délai de correction des critiques (30 jours) documenté. |
| T-071 | 6.3 | Double validation, Angular et Spring Boot | I | E11 | dev2 | Identique | Échantillon de formulaires relu en recette, y compris les nouveaux écrans. |
| T-072 | 6.4 | Contrôle d'accès côté back par un point d'application unique | N | E3 | dev1 | À faire | Service `AccessPredicate` unique, utilisé par recherche, arbre, compteurs, extraits, prévisualisation, téléchargement, export ZIP et API ; test d'architecture qui interdit tout autre chemin. |
| T-073 | 6.5 | Sauvegarde base, fichiers et clés, RPO et RTO, restauration testée | P | E10 | dev2 | En cours | Procédure et scripts (base + WAL, fichiers après la base, clés séparées) ; RPO 15 min / 24 h, RTO 8 h documentés ; compte rendu de restauration à blanc. |
| T-074 | 6.7 | Sonde de santé Actuator | IC | E10 | dev2 | En cours | Contesté : le PDF exige des sondes PostgreSQL, LDAP, référentiel de fichiers, ClamAV et file OCR. Chaque propriétaire livre sa sonde (dev1 LDAP, dev3 fichiers, ClamAV, OCR). |
| T-075 | 6.7 | Métriques Micrometer et Prometheus, alertes | N | E10 | dev2 | En cours | `/actuator/prometheus` ; indicateurs du 6.7 (temps de réponse, taux 5xx, appels par clé, file OCR, délai de disponibilité, disque, échéance du compte de service et des certificats) ; règles d'alerte aux seuils initiaux. |

### Journalisation et audit (§7)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-076 | 7.1 | Pattern de log imposé avec username, ip, traceId, spanId (MDC) | N | E4 | dev2 | En cours | Pattern exact de l'Article 50 dans `logback-spring.xml` ; filtre MDC ; propagation aux threads asynchrones et aux appels OCR et LDAP ; test sur une ligne de log. |
| T-077 | 7.3.1 | Niveaux de log, rotation quotidienne et par taille, rétention 90 jours | N | E4 | dev2 | En cours | INFO en production, DEBUG activable sans redéploiement ; rotation quotidienne et à 100 Mo, compression, 90 jours. |
| T-078 | 7.4.1 | Table journal_audit : acteur, application, IP, action, objet, avant et après, trace_id | N | E4 | dev2 | À faire | Table partitionnée par mois avec les colonnes exactes du 7.4.1 ; horodatage UTC. |
| T-079 | 7.4.2 | INSERT seul, déclencheurs de refus, scellement SHA-256 chaîné et exporté | N | E4 | dev2 | À faire | `ged_app` limité à INSERT et SELECT ; déclencheur `BEFORE UPDATE OR DELETE OR TRUNCATE` ; `journal_audit_scellement` horaire exporté hors base ; test : une modification manuelle fait échouer la vérification. |
| T-080 | 7.4.3 | Écran de consultation, export CSV et JSON, rétention 10 ans | N | E4 | dev2 | À faire | Écran filtrable réservé Administrateur et Direction Générale (garde branchée en E3) ; consultation auditée ; export avec empreintes de scellement ; partitions jamais supprimées automatiquement. |

### Qualité logicielle, tests et déploiement (§8 à §10)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-081 | 8.1 | Code documenté | I | E11 | dev2 | Identique | Revue en recette des nouveaux paquets. |
| T-082 | 8.1 | Séparation des couches : DAO, DTO, services, ressources, pages | I | E11 | dev2 | Identique | Revue en recette ; aucune entité exposée par un contrôleur. |
| T-083 | 8.2.1 | Validation complète avant toute écriture, transaction unique | I | E11 | dev3 | Identique | À revérifier après E6 : exception voulue du dépôt en deux temps (12.11). |
| T-084 | 8.2.1 | Format d'erreur uniforme et chaîne de sécurité fermée par défaut | I | E11 | dev2 | Identique | À revérifier après le passage à problem+json (dictionnaire par champ conservé). |
| T-085 | 8.3 | Registre des dépendances avec version et licence (SBOM) | N | E0 | dev2 | En cours | SBOM CycloneDX Maven et npm produit à chaque build, avec licences, y compris Tesseract et modèles. |
| T-086 | 9.1 | Tests fonctionnels avec Spring Test | I | E11 | dev2 | Identique | Nombre de tests suivi à chaque fusion (jamais en baisse) ; démonstration par sprint. |
| T-087 | 9.2 | Tests de non-régression | P | E0 | dev2 | En cours | Intégration continue : build, tests, JAR et paquet Angular à chaque fusion ; plan de non-régression de qa. |
| T-088 | 9.3 | Environnement UAT et déploiement par module | N | E0 | dev2 | En cours | Profil `uat` ; déploiement incrémental par module documenté. |
| T-089 | 9.4 | Dépôt Git, branche protégée, accès en lecture pour MMED | P | E0 | dev2 | En cours | Configuration de forge documentée (branche protégée, relecteurs en lecture seule). Non vérifiable sur ce poste (aucune forge, `git push` interdit). |
| T-090 | 10.1 | Configuration externalisée, même artefact promu sans recompilation | I | E11 | dev2 | Identique | À revérifier après ajout du profil `uat`. |
| T-091 | 10.1 | Secrets hors dépôt, injectés à l'exécution | I | E11 | dev2 | Identique | Contrôle à chaque fusion : aucun secret dans le dépôt ; `.env.example` à jour. |
| T-092 | 10.1 | Procédure scriptée : CI, sauvegarde, migration, test de fumée, retour arrière | P | E10 | dev2 | En cours | Script unique DEV/UAT/PROD : sauvegarde, `liquibase validate` puis `update` par `ged_owner`, déploiement, sondes, test de fumée (connexion, dépôt, recherche), retour arrière. |
| T-093 | 10.2 | JAR exécutable en service système derrière NGINX, serveur Linux | P | E10 | dev2 | En cours | Unité systemd livrée ; serveur distinct de la base. Relecture seulement sur ce poste. |
| T-094 | 10.3 | Code source complet et documentation d'installation | I | E11 | dev2 | Identique | Guide d'installation mis à jour en E11 ; procès-verbal de mise en production préparé. |

### Mécanismes techniques des règles fonctionnelles (§12)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-095 | 12.2 | Permissions, rôles, habilitations sur nœud ou document, héritage et rupture | N | E3 | dev1 | À faire | Tables `role`, `permission`, `role_permission`, `groupe_ged`, `groupe_membre`, `habilitation` ; 9 permissions élémentaires + permissions d'administration + `VOIR_PRIVE`, `VOIR_CONFIDENTIEL` ; 4 rôles système ; tests de résolution (plus spécifique, cumul, rupture, document isolé, `acces_global`). |
| T-096 | 12.3 | Confidentialité PUBLIC, PRIVE, CONFIDENTIEL et personnes désignées | N | E3 | dev1 | À faire | Colonne obligatoire, `document_confidentiel_designe` ; prédicat SQL appliqué à la source ; niveau par défaut par type ; déposant désigné par défaut ; tests de chaque niveau. |
| T-097 | 12.4 | Rattachement d'un document à plusieurs espaces | N | E3 (table et droits) + E7 (API) | dev1 | À faire | `document.noeud_principal_id`, `document_rattachement` unique ; droits en union ; suppression, déplacement et export conformes au 12.4 ; audit `RATTACHEMENT_AJOUTE` et `RATTACHEMENT_RETIRE`. |
| T-098 | 12.5 | Déplacement transactionnel avec sous-arborescence et anti-cycle | P | E7 | dev1 | À faire | Mise à jour du chemin matérialisé en une requête ; déplacement de document ; 409 si verrouillé ; audit origine et destination. |
| T-099 | 12.5 | Suppression douce avec auteur et date | P | E1 | dev1 | En cours | Colonnes `supprime`, `supprime_par`, `supprime_le` ; cascade sur la sous-arborescence (E3/E7). |
| T-100 | 12.5 | Purge définitive avec destruction cryptographique | N | E7 | dev3 | À faire | Purge depuis la corbeille seulement, permission Purger ; suppression des lignes, du fichier et de la DEK ; audit conservé ; test : fichier indéchiffrable après purge. |
| T-101 | 12.6 | Archivage : statut du document, lecture seule, empreinte, PDF/A, job par lot | N | E7 | dev3 | À faire | `document.statut_conservation` ; lecture seule en service et contrainte en base ; `job_archivage` par tranches de 100 avec reprise et annulation ; désarchivage réservé ; un événement par document. |
| T-102 | 12.7 | Méta-modèle d'index : nature dont booléen, obligatoire, défaut, liste, recherche | P | E7 | dev1 | À faire | Nature booléenne ajoutée (back et Angular) ; test. |
| T-103 | 12.7 | Plan d'indexation et charte de nommage automatique | I | E11 | dev1 | Identique | À revérifier après E7 (plan versionné). |
| T-104 | 12.7 | Métadonnées en JSONB avec index GIN | N | E1 (colonne) + E7 (validation) | dev1 | En cours | `document.metadonnees` JSONB, index GIN, index d'expression dates et nombres ; validation contre le plan en E7. |
| T-105 | 12.7 | Type : durée de conservation, confidentialité par défaut, plan versionné, re-typologisation | N | E7 | dev1 | À faire | Durée en mois et point de départ ; plan versionné ; `RESTRICT` sur un type utilisé ; `job_retypage` avec table de correspondance, rapport et audit par document. |
| T-106 | 12.8 | Versions : numéro, empreinte, auteur, une seule version courante | P | E7 | dev1 | À faire | `version_document(id, document_id, numero, empreinte, auteur_id, cree_le, courante)` ; index unique partiel ; désignation d'une version antérieure. |
| T-107 | 12.8 | Verrou avec auteur, date et motif | P | E7 | dev1 | À faire | Verrou posé et levé par l'Administrateur, audité ; 409 sur toute écriture (fiche, versement, déplacement, réindexation, archivage). |
| T-108 | 12.8 | Règle de workflow rattachable à un espace, un dossier ou un type ; validateur nommé ou par rôle | P | E8 | dev1 | À faire | `regle_workflow` et `regle_validateur` ; validateur par rôle résolu au moment de la décision. |
| T-109 | 12.8 | Circuit figé au dépôt | I | E11 | dev1 | Identique | À revérifier après E8 (tables `circuit`, `circuit_validateur`). |
| T-110 | 12.8 | Décisions VALIDE, REFUSE, ANNULEE sans ordre, statut recalculé sur la version courante | N | E8 | dev1 | À faire | Table `decision` ; tests : deux validateurs dans les deux ordres ; nouveau versement qui rend caduques les décisions. Reprise des circuits existants. |
| T-111 | 12.8 | Annulation de circuit et diffusion | N | E8 | dev1 | À faire | Annulation avec motif (statut `ANNULE`, décisions conservées, notification) ; diffusion par habilitation de lecture ; validateur désactivé signalé à l'Administrateur. |
| T-112 | 12.9 | Échéance de conservation et tâche planifiée d'alerte | N | E8 | dev3 | À faire | `document.echeance_conservation` recalculée ; tâche quotidienne avec verrou de tâche ; filtre « échéance dépassée » ; aucune suppression automatique. |
| T-113 | 12.9 | Notifications : boîte d'envoi, e-mail SMTP et pastille in-app | N | E8 | dev3 | À faire | Table `notification` écrite dans la transaction de l'événement ; envoi asynchrone, 3 reprises ; 3 cas exclusivement ; préférence e-mail ; tests avec serveur SMTP simulé. |
| T-114 | 12.10 | Export de dossier en ZIP en flux avec manifeste CSV | N | E7 | dev3 | À faire | `ZipOutputStream` sans fichier temporaire ; `manifeste.csv` UTF-8 avec les colonnes du 12.10 ; omission silencieuse des documents non autorisés ; traitement de fond au-delà de 500 documents ou 2 Go ; `DOCUMENT_EXPORTE` par document. |
| T-115 | 12.11 | Dépôt en deux temps : fichier reçu, puis indexation INDEXE, SANS_PLAN ou A_INDEXER | P | E6 | dev3 | À faire | Temps 1 et temps 2 en transactions séparées ; issues `INDEXE`, `SANS_PLAN`, `A_INDEXER` et reprise ; HTTP 201 ou 202 ; rejeu sans effet (Idempotency-Key). |

### Exigences du PDF absentes de la matrice (ajoutées par pm)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| P-01 | 3.2, 3.3 | Ajoutée par pm — Attributs AD jamais lus à des fins d'autorisation : `memberOf`, unité d'organisation et groupes exclus explicitement de la liste des attributs demandés (principe P2) | N | E2 | dev1 | À faire | Test : la requête LDAP ne demande que les attributs du 3.3 ; aucun droit dérivé d'un groupe AD. |
| P-02 | 3.3 | Ajoutée par pm — Liaison à l'annuaire : LDAPS 636, TLS 1.2, truststore MMED, au moins deux contrôleurs avec bascule, délais 3 s (connexion) et 5 s (lecture), pool du compte de service, secret rechargé à chaud, expiration du secret surveillée ; annuaire indisponible : aucune connexion possible, aucun mode dégradé, sessions conservées jusqu'à expiration, signalement à la supervision | N | E2 | dev1 | À faire | Configuration externalisée ; tests avec le simulateur (bascule, délai dépassé, annuaire arrêté) ; sonde LDAP. |
| P-03 | 3.4.1 | Ajoutée par pm — Protection CSRF du point de renouvellement fondé sur cookie : en-tête personnalisé exigé en plus de `SameSite=Strict` | N | E2 | dev1 | À faire | Test : renouvellement sans l'en-tête refusé. |
| P-04 | 3.4.2 | Ajoutée par pm — Cycle de vie de l'identité : page d'accueil vide pour un compte sans rôle ; compte désactivé : sessions révoquées, rôles sans effet sans suppression des lignes, validations nominatives en cours signalées sur le tableau de bord de l'Administrateur ; réactivation qui retrouve les rôles ; compte supprimé traité comme désactivé | N | E2 (signalement des validations en E8) | dev1 | À faire | Tests avec le simulateur ; écran Angular pour le compte sans rôle. |
| P-05 | 2.3.1, 4.5 | Ajoutée par pm — Livrables de modélisation : schéma de base détaillé (types, index, volumétrie par table), diagrammes de classes et de séquences par flux | N | E1 (mise à jour E11) | dev1 | À faire | Documents livrés dans `docs/`, générés ou relus contre le schéma Liquibase. |
| P-06 | 5.3.1 | Ajoutée par pm — Points d'entrée du contrat : `POST /noeuds/{id}/dossiers`, `POST /documents` (multipart), `POST /recherches`, `GET /documents/{id}/contenu?version`, `POST /documents/{id}/versions`, `POST /documents/{id}/rattachements` et `DELETE …/{noeudId}`, `GET /documents/{id}/droits` et `/noeuds/{id}/droits?pourUtilisateur`, avec droit requis et règle de rejeu du tableau | N | E9 | dev2 | À faire | Test de contrat automatisé sur ces 8 opérations (chemin, méthode, droit, Idempotency-Key). Implémentation par le propriétaire de chaque lot. |
| P-07 | 5.3.2 | Ajoutée par pm — Limites : 64 Ko de métadonnées par requête ; codes de succès 200, 201, 202 et 204 | N | E9 | dev2 | À faire | Tests : métadonnées de plus de 64 Ko refusées ; codes de succès conformes. |
| P-08 | 5.3.2 | Ajoutée par pm — Versionnement et compatibilité : évolutions additives dans une version majeure, `/api/v2` en cas de rupture, ancienne version maintenue 12 mois, en-têtes `Deprecation` et `Sunset`, champs inconnus ignorés | N | E9 | dev2 | À faire | Politique rédigée dans la spécification OpenAPI ; mécanisme d'en-têtes testé. |
| P-09 | 4.3.4 | Ajoutée par pm — Écran de supervision des traitements OCR : état des jobs, motif d'échec, relance manuelle ; nombre de workers paramétrable | N | E6 | dev3 | À faire | Écran Angular réservé à l'Administrateur ; test de relance. |
| P-10 | 6.1.3 | Ajoutée par pm — Chiffrement du volume de la base (LUKS ou équivalent), seule protection du texte extrait indexé | N | E10 | dev2 | À faire | Exigence d'infrastructure documentée dans le guide de déploiement ; à confirmer par MMED. |
| P-11 | 6.2.3 | Ajoutée par pm — A04 : modélisation des menaces par module (Phase 4), échec fermé | N | E10 | dev2 | À faire | Document de modélisation des menaces par module ; tests d'échec fermé (ClamAV, LDAP, KMS indisponibles). |
| P-12 | 6.2.3 | Ajoutée par pm — A10 SSRF : aucun appel sortant construit à partir d'une entrée utilisateur ; destinations sortantes limitées à l'annuaire, au relais SMTP et au KMS | N | E11 | dev2 | À faire | Revue de code outillée des clients sortants ; configuration des destinations. |
| P-13 | 6.5 | Ajoutée par pm — Cohérence base et fichiers après restauration : fichiers orphelins identifiés puis nettoyés après 7 jours ; documents dont le fichier manque signalés « à ré-importer » | N | E5 | dev3 | À faire | Tâche de rapprochement testée (orphelin, fichier manquant). |
| P-14 | 6.6 | Ajoutée par pm — Dimensionnement et volumétrie : hypothèses mesurées sur l'échantillon de la Phase 7 (index GIN, débit OCR, audit) | N | E11 | dev2 | À faire | Rapport de mesure. Non évaluable dans le code ; dépend de l'échantillon MMED. |
| P-15 | 7.4.1 | Ajoutée par pm — Catalogue des événements d'audit à code stable (liste du 7.4.1), un événement par document pour les opérations de masse, refus de droits tracés, adresse IP transmise par NGINX (en-tête de confiance) | N | E4 (puis chaque étape) | dev2 | À faire | Énumération des codes ; test par événement à chaque étape suivante ; audit des refus. |
| P-16 | 7.4.2 | Ajoutée par pm — Extension `pgaudit` sur les sessions d'administration de la base ; séparation des rôles (administrateur GED sans accès base, administrateur base sans compte GED) | N | E10 | dev2 | À faire | Guide de déploiement ; à confirmer par MMED. |
| P-17 | 10.4 | Ajoutée par pm — Garantie : plan de migration des données pour toute correction touchant le schéma ; contournement d'une panne majeure sous 24 heures | N | E10 | dev2 | À faire | Procédure de support et de correction rédigée. |
| P-18 | 11.1 | Ajoutée par pm — Équipe projet minimale (Art. 29) : chef de projet, développeurs back et front, UI/UX, opérateurs de numérisation | N | E11 | pm | À faire | Engagement contractuel, hors code ; à traiter par IPTECH. |
| P-19 | 11.2 | Ajoutée par pm — Propriété intellectuelle : dépôt remis complet avec son historique ; licences des bibliothèques compatibles avec la cession, vérifiées dans le registre | N | E0 | dev2 | En cours | Colonne licence du SBOM ; liste des licences refusées au build (point d'attention : veraPDF GPL/MPL, ClamAV GPL en service externe). |
| P-20 | 12.5 | Ajoutée par pm — Renommage : permission Modifier, unicité du nom dans le dossier parent (409), audit avant et après | N | E7 | dev1 | À faire | Tests : doublon = 409, événement d'audit. |
| P-21 | 12.7 | Ajoutée par pm — Socle commun de colonnes du document : objet, date du document (clé de tri prioritaire), niveau de confidentialité, durée de conservation déduite du type | N | E7 (colonnes posables dès E1) | dev1 | À faire | Colonnes dédiées ; tri par défaut sur la date du document. |
| P-22 | 12.2.3 | Ajoutée par pm — Consultation des droits effectifs avec leur origine (rôle, nœud d'attribution, héritage, rattachement, confidentialité), par API et écran d'administration ; modification des droits effective immédiatement (compteur `version_habilitations`) et auditée avant et après | N | E3 | dev1 | À faire | Tests : origine exposée ; retrait de droit effectif sans délai ; événement d'audit. |
