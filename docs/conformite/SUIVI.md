# Tableau de suivi — conformité au dossier technique et d'intégration V3

Tenu par **pm** sur la branche `conformite-technique`. Mis à jour à chaque fusion et à chaque
recette de qa. Sources : `MATRICE-TECHNIQUE.md` (115 lignes, reprises dans l'ordre de la
matrice, numéros T-001 à T-115), contrôle de cohérence de pm contre le PDF (22 lignes
ajoutées, numéros P-01 à P-22) et revue technique client (`DECISIONS-REVUE-TECHNIQUE.md`,
4 lignes ajoutées, numéros R-01 à R-04).

**Ordre de priorité** : une décision **actée** de la revue client prime sur le dossier V3 ; une
décision tentative ou une question ouverte ne change rien (le V3 s'applique). Pour le reste, le
PDF fait foi. Les lignes modifiées par la revue portent la mention « Source : revue client (Dx) ».

Dernière mise à jour : 27/09/2026 — vague 1 intégrée (`409970b`, `65eb0ae`, `95b11e1`, `7b893f6`)
et `ct/dev1` vague 2 intégrée jusqu'à `d33e581` (E2, E3, correctifs ANO-E1-001 à 003 ; fusion
`5418680`). Le lot E6 de dev3 et la vague 2 de dev2 ne sont pas encore fusionnés.

## Statuts courants

| Statut | Signification | Qui le pose |
|---|---|---|
| À faire | Non commencé. | pm |
| En cours | Travail engagé sur une branche `ct/*` (vague en cours). | pm, d'après le suivi du membre |
| Livré | Fusionné dans `conformite-technique`, tests automatisés verts après fusion. | pm |
| Vérifié | Recette de qa passée sur `conformite-technique` (script de recette et preuve archivés). | qa, reporté par pm |
| Identique | pm a relu la ligne contre le PDF : elle peut passer à « Identique » dans la matrice. | pm |
| Hors périmètre | Exclu du marché par une décision client ; non implémenté, non compté dans le taux. | pm |

Une ligne dont le statut initial est « Identique » mais que pm conteste après relecture du PDF
est marquée **« Identique contesté »** dans la colonne Init. et suivie comme un écart
(statut courant « À faire » ou « En cours »).

## 1. Tableau de bord

### Compteurs par statut courant (141 lignes)

| Statut courant | Lignes | Part |
|---|---|---|
| À faire | 61 | 43 % |
| En cours | 6 | 4 % |
| Livré | 37 | 26 % |
| Vérifié | 9 | 6 % |
| Identique | 27 | 19 % |
| Hors périmètre | 1 | 1 % |
| **Total** | **141** | 100 % |

Taux de conformité (lignes « Identique » sur les lignes dans le périmètre) : **27 / 140 = 19 %**
(23 lignes déjà conformes + T-004, T-005, T-019, T-046 relues par pm après la recette de qa).
La matrice annonçait 29 / 115 (25 %) : 6 lignes « Identique » sont contestées par pm (T-042,
T-050, T-053, T-062, T-069, T-074), 22 exigences du PDF absentes de la matrice ont été ajoutées,
et la revue client en ajoute 3 (R-01 à R-03) et en exclut 1 (R-04).

Revue client : 14 lignes existantes voient leur libellé attendu modifié (T-011, T-015, T-018,
T-028, T-035, T-060, T-075, T-079, T-101, T-106, T-111, P-01, P-02, P-04), 7 sont confirmées sans
changement (T-008, T-012, T-026, T-080, T-095, T-099, T-110), 2 renvoient à une question ouverte
(T-107, T-108).

### Compteurs par statut initial

| Statut initial | Matrice | Ajoutées par pm | Revue client | Total |
|---|---|---|---|---|
| Identique | 23 (+ 6 contestées) | 0 | 0 | 29 |
| Proche | 31 | 0 | 0 | 31 |
| Non | 55 | 22 | 3 | 80 |
| Hors périmètre | 0 | 0 | 1 | 1 |
| **Total** | 115 | 22 | 4 | 141 |

### Avancement par étape

| Étape | Contenu | Vague | Lignes | À faire | En cours | Livré | Vérifié | Identique |
|---|---|---|---|---|---|---|---|---|
| E0 | Cadrage et outillage | 1 | 6 | 0 | 2 | 4 | 0 | 0 |
| E1 | Socle de données | 1 | 13 | 1 | 1 | 3 | 4 | 4 |
| E2 | Identité et sessions | 2 | 12 | 0 | 0 | 12 | 0 | 0 |
| E3 | Autorisation et confidentialité | 2 | 7 | 0 | 1 | 6 | 0 | 0 |
| E4 | Journalisation et audit | 1 (logs) + 2 | 6 | 4 | 0 | 2 | 0 | 0 |
| E5 | Stockage sécurisé des fichiers (composants livrés, branchement en vague 2) | 1 + 2 | 9 | 1 | 1 | 2 | 5 | 0 |
| E6 | OCR asynchrone et plein texte (+ dépôt en deux temps) | 3 | 15 | 15 | 0 | 0 | 0 | 0 |
| E7 | Modèle documentaire et cycle de vie (+ espace de partage R-03) | 4 | 12 | 12 | 0 | 0 | 0 | 0 |
| E8 | Workflow, conservation, notifications | 5 | 5 | 5 | 0 | 0 | 0 | 0 |
| E8-API | Pilotage du workflow par API (revue client D8) | 5 | 2 | 2 | 0 | 0 | 0 | 0 |
| E9 | API d'intégration | 2 à 5 | 14 | 14 | 0 | 0 | 0 | 0 |
| E10 | Exploitation et infrastructure | 1 + 5 | 13 | 4 | 1 | 8 | 0 | 0 |
| E11 | Recette de conformité (lignes déjà Identique, points hors code) | 6 | 26 | 3 | 0 | 0 | 0 | 23 |
| — | Hors périmètre (R-04) | — | 1 | — | — | — | — | — |
| **Total** | | | **141** | **61** | **6** | **37** | **9** | **27** |

### Répartition par responsable

| Responsable | Lignes | Identique | Vérifié | Livré | En cours | Anomalies qa ouvertes |
|---|---|---|---|---|---|---|
| dev1 | 48 | 9 | 4 | 21 | 2 (T-025, T-097) | ANO-E1-001 à 003 corrigées (`5418680`), à revérifier par qa |
| dev2 | 55 | 12 | 0 | 14 | 3 (T-065, T-088, T-089) | — |
| dev3 | 36 | 6 | 5 | 2 | 1 (T-009) | ANO-E5-001 (corrigée sur `ct/dev3` en `8b532b3`, non encore fusionnée) |
| pm | 2 | 0 | 0 | 0 | 0 | — (P-18 engagement contractuel ; R-04 hors périmètre) |

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

Légende Init. : I = Identique, P = Proche, N = Non, IC = Identique contesté par pm, — = hors périmètre.

### Architecture et stack imposée (§2)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-001 | 2.1 | Séparation stricte front et back, communication uniquement par API | I | E11 | dev2 | Identique | Revue d'architecture en recette : aucun accès front hors `/api/v1`. |
| T-002 | 2.2 | Front-end Angular | I | E11 | dev2 | Identique | `frontend/package.json` ; version LTS courante confirmée en recette. |
| T-003 | 2.2 | Back-end Java 17 et Spring Boot 3 (Security, Data JPA, Bean Validation) | I | E11 | dev1 | Identique | `backend/pom.xml`. |
| T-004 | 2.2 | SGBD PostgreSQL 16 ou plus, configuration de recherche arabe vérifiée | N | E1 | dev1 | Identique | Profils dev, test, uat, prod sur PostgreSQL ; driver MySQL et H2 retirés ; test qui vérifie la présence de la configuration `arabic` ; `mvn test` vert sur PostgreSQL. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié (C24–C26, configuration `arabic`). Relu par pm contre le §2.2 et le §4.1 : PostgreSQL 16, configuration arabe présente. |
| T-005 | 2.2 | Outil de migration Liquibase 4 | N | E1 | dev1 | Identique | Flyway retiré du `pom.xml` ; changelog maître Liquibase ; base vierge créée uniquement par Liquibase. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié (A21, C19, V03). Relu par pm contre le §2.2.1 et le §4.2 : Liquibase 4. |
| T-006 | 2.2 | Hébergement du front sur un serveur NGINX | P | E10 | dev2 | Livré | Configuration NGINX livrée dans le dépôt, servant le paquet Angular et `config.json`. Vérifiable seulement par relecture sur ce poste (NGINX absent). **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : vérifié sur papier (NGINX absent du poste). |
| T-007 | 2.3 | Une API REST unique pour le front et les applications tierces | I | E11 | dev2 | Identique | Recette E9 : une application de test utilise les mêmes points d'entrée que le front. |
| T-008 | 2.3.2 | Briques Tesseract 5 et Apache PDFBox | I | E11 | dev3 | Identique | À revérifier après E6 (modèle `ara` ajouté). Confirmé par la revue client D5 : appel du binaire Tesseract depuis Java, aucun Python. |
| T-009 | 2.3.2 | Briques Apache Tika, ClamAV, LibreOffice, veraPDF, keystore ou KMS, Prometheus | N | E5 (clôture E10) | dev3 | En cours | Chaque brique intégrée derrière une interface, testée avec un simulateur si absente du poste ; Prometheus livré par dev2 en E10 ; veraPDF en E7. Tika, keystore PKCS#12, client ClamAV et prévisualisation LibreOffice livrés en composants (fusion `65eb0ae`) ; veraPDF (E7) et Prometheus (E10) restent à livrer. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : Tika, keystore et client ClamAV vérifiés ; LibreOffice, veraPDF et Prometheus non. |

### Authentification et identités (§3)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-010 | 3.2 | Aucun référentiel local de mots de passe | N | E2 | dev1 | Livré | Table des comptes et `CompteSeeder` supprimés ; aucune colonne de mot de passe dans le schéma (requête sur `information_schema`). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-011 | 3.3 | Authentification LDAPS search-then-bind avec compte de service, Spring Security LDAP. **Revue client D2** : connexion par l'UID AD (`sAMAccountName`) uniquement, jamais par l'adresse e-mail (`userPrincipalName` et `mail` refusés comme identifiant) | N | E2 | dev1 | Livré | Tests avec serveur LDAP embarqué UnboundID (simulateur) : recherche par `sAMAccountName`, bind avec le DN trouvé ; une saisie au format e-mail est refusée ; mot de passe jamais stocké ni journalisé. Source : revue client (D2). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : vérifié seulement avec l'annuaire simulé (UnboundID). |
| T-012 | 3.3 | Provisionnement automatique sans rôle, clé objectGUID (**confirmé par la revue client D2** : l'identifiant unique AD reste nécessaire) | N | E2 | dev1 | Livré | Test : première connexion crée `utilisateur` sans rôle, clé `objectGUID` ; changement de login sans doublon. Source : revue client (D2). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : vérifié seulement avec l'annuaire simulé (UnboundID). |
| T-013 | 3.3 | Jeton d'accès court, sans permissions embarquées | P | E2 | dev1 | Livré | Test : jeton de 15 minutes, porte l'identifiant GED et l'identifiant d'annuaire, aucune permission. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-014 | 3.4.1 | JWT signé RS256 avec clé privée en coffre, conservé en mémoire côté Angular | N | E2 | dev1 | Livré | Test de signature RS256, clé privée lue hors dépôt ; Angular : aucune écriture en `localStorage` ni `sessionStorage` (recherche dans le code et test). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-015 | 3.4.1 | Jeton de renouvellement en cookie httpOnly, 8 h max, table de sessions, révocation (mécanisme V3 conservé, question QR2 ; **revue client D1** : plus de révocation automatique sur désactivation AD, révocation manuelle par l'Administrateur) | N | E2 | dev1 | Livré | Tests : cookie `HttpOnly; Secure; SameSite=Strict`, durée absolue paramétrable (8 h, réduction proposée à MMED, risque R26), inactivité 30 min, rotation, réutilisation qui révoque la famille, table `session`, révocation de toutes les sessions d'un utilisateur par l'Administrateur. Source : revue client (D1). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-016 | 3.4.1 | Protection CSRF : jeton uniquement dans l'en-tête Authorization | I | E11 | dev1 | Identique | À revérifier après E2 avec P-03 (point de renouvellement fondé sur cookie). |
| T-017 | 3.4.1 | Anti-force brute : 5 essais par minute par IP et identifiant, échecs journalisés | P | E2 | dev1 | Livré | Tests : 6e essai en une minute refusé en 429, par IP et par identifiant ; chaque échec produit un événement d'audit (écouteur de dev2, E4). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-018 | 3.4.2 | **Revue client D1** : cache annuaire de 15 minutes ; la relecture de `userAccountControl` (au renouvellement et toutes les 5 minutes) est **supprimée** — un compte désactivé est bloqué par l'échec de l'authentification AD. Cache conservé (décision tentative T2 : choix de l'équipe) | N | E2 | dev1 | Livré | Tests : table `cache_annuaire` expirant à 15 min ; aucune lecture de `userAccountControl` ni tâche périodique ; compte désactivé dans le simulateur = connexion refusée. Source : revue client (D1, T2). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : vérifié seulement avec l'annuaire simulé (UnboundID). |

### Données et migrations (§4.1, §4.2, §12.1)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-019 | 4.2.1 | Aucune modification de schéma hors migration versionnée | P | E1 | dev1 | Identique | `ddl-auto: validate` sur tous les profils ; aucun DDL hors changelog. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié (V01–V05 : DDL identique après démarrage). Relu par pm contre le §4.2.1. |
| T-020 | 4.2.1 | Amorçage initial par migration, référentiels métier uniquement via l'interface | P | E1 | dev1 | Livré | Seeders Java d'amorçage supprimés ; changesets étiquetés `data-initial` sans donnée métier propre à un environnement. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. **ANO-E1-003** : aucun changeset `data-initial`, `CompteSeeder` actif dans tous les profils ; le statut « Livré » était erroné. Suite chez dev1 (E2/E3). Correctif ANO-E1-003 livré (fusion `5418680`) : rôles système en changeset `data-initial`, `CompteSeeder` supprimé ; à revérifier par qa. |
| T-021 | 4.2.2 | Conventions de nommage des changesets et des objets (snake_case, idx_, uk_, fk_) | P | E1 | dev1 | Livré | Fichiers `AAAAMMJJHHmm_objet_metier.xml` ; contrôle automatique des noms d'objets (`idx_<table>_<colonnes>`, `uk_`, `fk_`, `ck_`, clé `id`, clés étrangères `<table>_id`). **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. **ANO-E1-001** (majeure, dev1) : 4 tables à clé primaire composite au lieu de `id` (`access_group_employe`, `access_group_workspace`, `document_etiquette`, `plan_index`). Correctif ANO-E1-001 livré (fusion `5418680`) : clé `id` UUID sur les tables d'association ; à revérifier par qa. |
| T-022 | 4.2.2 | Retour arrière explicite par changeset, schéma expand et contract | N | E1 | dev1 | Vérifié | Chaque changeset porte un `rollback` ; test `updateTestingRollback` sur base vierge. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié sur le poste (R01–R04). « Identique » après exécution du retour arrière en UAT (règle du §4.2.2). |
| T-023 | 4.2.3 | Trois rôles PostgreSQL : propriétaire, application, lecture seule | N | E1 | dev1 | Vérifié | Script idempotent des rôles `ged_owner`, `ged_app`, `ged_readonly`, aucun superutilisateur ; application exécutée sous `ged_app`. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié (C30–C41, sondes P01–P13). « Identique » après E4 : INSERT et SELECT seuls sur les tables d'audit (§4.2.3). |
| T-024 | 12.1 | Clés primaires UUID | N | E1 | dev1 | Vérifié | Toutes les clés primaires en `uuid` ; front adapté (identifiants en chaîne). **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié (C05, C06, fumée `GED_EXIGER_UUID=1`). Pas « Identique » tant qu'ANO-E1-001 est ouverte : `plan_index` a une clé (uuid, entier). ANO-E1-001 corrigée à la fusion `5418680` : peut passer « Identique » après revérification de qa. |
| T-025 | 12.1 | Modèle logique en sept groupes de tables | P | E1 (clôture E9) | dev1 | En cours | Toutes les tables du tableau 12.1 présentes, avec leurs noms exacts, après E9. Partie E1 livrée (fusion `409970b`) ; les groupes Identités, Habilitations, Traçabilité et Circuits restent à créer. |

### Moteur OCR et recherche plein texte (§4.3, §4.4)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-026 | 4.3.1 | Tesseract 5, moteur LSTM, open source | I | E11 | dev3 | Identique | À revérifier après E6. Confirmé par la revue client D5 : appel du binaire Tesseract depuis Java, aucun Python. |
| T-027 | 4.3.2 | Interface de moteur permettant de changer d'OCR sans impact | I | E11 | dev3 | Identique | Interface `OcrEngine` (nom du PDF) utilisée par le worker. |
| T-028 | 4.3.2 | Protocole de mesure sur 300 pages (CER, WER, débit). **Revue client D5** : tests en Java uniquement, sans Python ni service supplémentaire — la comparaison avec PaddleOCR et EasyOCR (Python) n'est plus exécutable, question QR8 | N | E6 | dev3 | À faire | Outillage Java de mesure CER/WER/débit de Tesseract contre les seuils du 4.3.2, testé sur un jeu interne ; exécution et rapport dès réception de l'échantillon MMED. Source : revue client (D5). |
| T-029 | 4.3.3 | Cloisonnement : l'OCR n'alimente aucun champ d'index | N | E6 | dev3 | À faire | Pré-remplissage des index retiré (back et Angular) ; test : aucun champ d'index écrit par la chaîne OCR. |
| T-030 | 4.3.4 | Traitement asynchrone : table ocr_job, worker SKIP LOCKED, réponse HTTP 202 | N | E6 | dev3 | À faire | Tests : dépôt qui répond 202 avec `EN_ATTENTE_OCR` ; deux workers concurrents sans double traitement ; interface `OcrJobQueue`. |
| T-031 | 4.3.4 | Langues fra et ara, langue fixable par type de document | P | E6 | dev3 | À faire | Modèles `fra` et `ara` dans `tessdata`, défaut `fra+ara`, langue par type ; test sur un scan arabe. |
| T-032 | 4.3.4 | Aucun plafond de pages, texte multi-pages agrégé | P | E6 | dev3 | À faire | Paramètre de plafond supprimé ; test sur un PDF de plus de 5 pages ; traitement page par page. |
| T-033 | 4.3.4 | Aucune copie en clair sur disque persistant (tmpfs) | N | E6 | dev3 | À faire | Déchiffrement et rendu en mémoire ou dans un répertoire tmpfs configurable ; test qui vérifie l'absence de fichier en clair après traitement. |
| T-034 | 4.3.4 | Délai de 60 s par page, 3 tentatives, statut OCR_ECHEC, document « non interrogeable » | P | E6 | dev3 | À faire | Tests : 60 s par page, reprises à 1, 5 et 30 min, `OCR_ECHEC` avec motif, marquage « contenu non interrogeable » visible dans les résultats. |
| T-035 | 4.3.4 | Texte stocké dans document_texte, délai de disponibilité mesuré. **Revue client D6** : délai maximal dépôt → disponibilité en recherche de **24 heures** (au lieu de 5 min et de 60 min au 95e centile) | N | E6 | dev3 | À faire | Table `document_texte(document_id, version_id, langue, texte, tsv)` ; métrique `ocr_delai_disponibilite` ; test de disponibilité d'un document de 800 pages largement sous 24 h. Source : revue client (D6). |
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
| T-046 | 5.3.2 | JSON UTF-8, dates ISO 8601 en UTC, identifiants opaques UUID | P | E1 | dev1 | Identique | Identifiants UUID dans tous les DTO ; dates sérialisées en UTC ; test de sérialisation. **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié (UUID, dates `…Z`, JSON UTF-8 ; dépôts déjà en multipart). Relu par pm contre le §5.3.2. |
| T-047 | 5.3.2 | Erreurs au format problem+json (RFC 7807) avec code métier stable | P | E9 (avancé en vague 2) | dev2 | À faire | `application/problem+json`, champ `code` stable, dictionnaire d'erreurs par champ pour les 400 ; catalogue des codes documenté. |
| T-048 | 5.3.2 | Codes 403, 404 hors périmètre, 409, 413, 415, 422, 429 | P | E9 | dev2 | À faire | Test par code ; 413/415/422 posés par dev3 (E5), 404 hors périmètre par dev1 (E3), 429 par dev1 (connexion) et dev2 (quotas). |
| T-049 | 5.3.2 | En-tête Idempotency-Key obligatoire sur les créations | N | E9 | dev2 | À faire | Table `idempotence_cle` ; tests : rejeu identique = réponse initiale, contenu différent = 422, mémorisation 24 h par application. |
| T-050 | 5.3.2 | Pagination page et taille, plafond 200, tri sur liste blanche | IC | E3 | dev1 | Livré | Contesté : le PDF exige une taille par défaut de 50 et un total calculé sur le seul périmètre autorisé. Tests après E3. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-051 | 5.3.2 | Taille de fichier paramétrable par type, quotas de requêtes par clé | P | E9 | dev2 | À faire | Tests : 600 requêtes par minute et 100 000 par jour par clé, 429 avec `Retry-After`. |
| T-052 | 5.3.2 | Version majeure dans l'URL (/api/v1) | I | E11 | dev2 | Identique | Voir aussi P-08 (politique de compatibilité). |
| T-053 | 5.3 | Spécification OpenAPI 3 complète | IC | E9 | dev2 | À faire | Contesté : le PDF exige des schémas de payload champ par champ ; exemples absents. Spécification relue et exemples ajoutés. |
| T-054 | 5.4 | Clés API : cycle de vie, secret haché SHA-256, X-API-Key, portée, quotas, expiration, IP | N | E9 | dev2 | À faire | Format `ged_<env>_<identifiant>_<secret>` (256 bits), affichage unique, empreinte SHA-256, expiration 12 mois, chevauchement 7 jours, liste d'adresses, `cle_api_portee`, écran d'administration. |
| T-055 | 5.5 | Délégation d'identité par X-On-Behalf-Of, double identité dans l'audit | N | E9 | dev2 | À faire | Tests : clé sans attribut délégation = 403 ; identité invalide ou désactivée = 422 `IDENTITE_DELEGUEE_INVALIDE` ; écriture avec les droits de l'application et déposant délégué ; lecture en intersection ; audit avec les deux identités. Rejet d'un compte désactivé non garanti depuis D1 : question QR9, risque R28. |

### Sécurité applicative et infrastructure (§6)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-056 | 6.1.1 | Fichiers stockés hors base | I | E11 | dev3 | Identique | À revérifier après E5 (interface `FileStore`). |
| T-057 | 6.1.1 | Identifiant opaque, arborescence aa/bb, écriture unique et atomique | P | E5 | dev3 | Vérifié | `/<racine>/aa/bb/<uuid>.enc` ; écriture fichier temporaire, `fsync`, renommage ; test d'interruption. **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié **en composants** (K01, K04) ; bout en bout HTTP et arrêt brutal en vague 2. |
| T-058 | 6.1.2 | Chiffrement AES-256-GCM, clé par version, clé maîtresse en keystore, rotation | N | E5 | dev3 | Vérifié | IV de 96 bits unique, DEK par version enveloppée par la KEK (table `cle_fichier`), keystore PKCS#12 via `KeyProvider`, rotation par réenveloppement ; test d'altération détectée. **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié **en composants** (K03–K10, K18, K19, keystore de test). ANO-E5-001 (mineure, dev3) : keystore de dev créé sous `./data/` non ignoré à la racine. |
| T-059 | 6.1.4 | Empreinte SHA-256 par version, vérification périodique | N | E5 | dev3 | Livré | `version_document.empreinte` du contenu en clair ; tâche mensuelle et commande à la demande ; divergence = alerte et événement d'audit. **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : partiel (K02) ; vérification à la demande et mensuelle à rejouer par l'API en vague 2. |
| T-060 | 6.1.4 | Copie de conservation PDF/A-2 validée par veraPDF (**revue client D10** : conversion en PDF obligatoire à l'archivage, fichiers Word archivables) | N | E7 | dev3 | À faire | Copie PDF/A-2 à l'archivage (LibreOffice ou PDFBox), validée par veraPDF, chiffrée ; original conservé ; échec non bloquant et signalé ; Word accepté. Nature de la conversion (image ou texte) : question QR7. Simulateur pour LibreOffice sur ce poste. Source : revue client (D10). |
| T-061 | 6.1.5 | Type réel détecté par le contenu (Apache Tika) | N | E5 | dev3 | Vérifié | Test : fichier renommé refusé en 415 ; liste blanche par type (PDF, TIFF, JPEG, PNG, texte, CSV, Office, OpenDocument). **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié **en composants** (K11, K12). |
| T-062 | 6.1.5 | Taille maximale par type, plafond de plateforme | IC | E5 | dev3 | Vérifié | Contesté : plafond de plateforme de 200 Mo aligné sur NGINX et sur les limites multipart de Spring (100 Mo aujourd'hui). Test 413. **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié **en composants** (K13, K14 : limite du type, plafond 200 Mo, 413). |
| T-063 | 6.1.5 | Antivirus ClamAV, refus si indisponible | N | E5 | dev3 | Vérifié | Protocole clamd `INSTREAM` ; tests avec serveur clamd factice : infecté = 422 `FICHIER_INFECTE` et audit ; indisponible = refus. Vérifié seulement par simulateur sur ce poste. **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : vérifié **par simulateur** (K15–K17, FauxClamd) ; ClamAV réel en UAT. |
| T-064 | 6.1.6 | Prévisualisation déchiffrée à la volée, droits appliqués, audit | N | E5 | dev3 | Livré | PDF et images en flux sans copie en clair ; bureautique convertie par LibreOffice, cache chiffré ; mêmes contrôles que le téléchargement ; événement d'audit distinct ; visionneuse Angular. **Livré en composants autonomes** : fusion `65eb0ae` (ct/dev3 `32e8fc2`), tests verts ; **non branché** sur le dépôt, le versement et le téléchargement (vague 2) : ne peut pas passer « Identique » avant ce branchement. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : non vérifié (LibreOffice absent, parcours HTTP en vague 2). |
| T-065 | 6.2.1 | TLS 1.2 minimum, certificat MMED, LDAPS et base chiffrés | N | E10 | dev2 | En cours | Configuration NGINX (TLS 1.2+, suites modernes) ; `sslmode=verify-full` et LDAPS configurés pour UAT et PROD. Relecture seulement sur ce poste. Configuration NGINX TLS livrée (fusion `95b11e1`) ; reste `sslmode`/`sslrootcert` sur l'URL JDBC (dev1) et LDAPS (E2). |
| T-066 | 6.2.2 | NGINX durci : server_tokens off, HSTS, CSP, limitation de débit, HTTPS forcé | N | E10 | dev2 | Livré | Configuration livrée avec `server_tokens off`, HSTS, `X-Content-Type-Options`, `X-Frame-Options`, CSP adaptée à Angular, `limit_req` sur connexion et API, redirection HTTPS, `client_max_body_size 200m`. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : vérifié sur papier ; CSP éprouvée sous Chromium. |
| T-067 | 6.2.3 | A01 : refus par défaut, 404 pour un objet hors périmètre | P | E3 | dev1 | Livré | Tests de chaque chemin d'accès : objet hors périmètre = 404, indistinct d'un objet absent. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-068 | 6.2.3 | A03 : requêtes paramétrées, liste blanche de tri, Bean Validation | I | E11 | dev1 | Identique | À revérifier après E6 (requêtes plein texte natives paramétrées). |
| T-069 | 6.2.3 | A05 : Actuator restreint | IC | E10 | dev2 | Livré | Contesté : le PDF exige Actuator limité au réseau interne. Port de gestion séparé ou restriction NGINX, et test. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Actuator servi sur un port de management séparé, avec sa propre chaîne de sécurité. |
| T-070 | 6.2.3 | A06 : OWASP Dependency-Check à chaque construction | N | E0 | dev2 | Livré | Plugin au build Maven et contrôle npm ; seuil de blocage ; rapport archivé ; délai de correction des critiques (30 jours) documenté. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : analyse non exécutée (secret `NVD_API_KEY` absent, la NVD répond 429) ; job CI prêt. |
| T-071 | 6.3 | Double validation, Angular et Spring Boot | I | E11 | dev2 | Identique | Échantillon de formulaires relu en recette, y compris les nouveaux écrans. |
| T-072 | 6.4 | Contrôle d'accès côté back par un point d'application unique | N | E3 | dev1 | Livré | Service `AccessPredicate` unique, utilisé par recherche, arbre, compteurs, extraits, prévisualisation, téléchargement, export ZIP et API ; test d'architecture qui interdit tout autre chemin. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-073 | 6.5 | Sauvegarde base, fichiers et clés, RPO et RTO, restauration testée | P | E10 | dev2 | Livré | Procédure et scripts (base + WAL, fichiers après la base, clés séparées) ; RPO 15 min / 24 h, RTO 8 h documentés ; compte rendu de restauration à blanc. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Sauvegarde et restauration exécutées à blanc sur jeu synthétique ; exercice UAT sur données réelles à planifier. |
| T-074 | 6.7 | Sonde de santé Actuator | IC | E10 | dev2 | Livré | Contesté : le PDF exige des sondes PostgreSQL, LDAP, référentiel de fichiers, ClamAV et file OCR. Chaque propriétaire livre sa sonde (dev1 LDAP, dev3 fichiers, ClamAV, OCR). **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Sondes base, référentiel de fichiers, annuaire, antivirus et files de traitement. Depuis la fusion `5418680` : sonde « annuaire » = celle du lot identité (dev1), hors `readiness` (§3.3), alerte `GedAnnuaireIndisponible` ; métrique par contrôleur (D4) à réintroduire si besoin. |
| T-075 | 6.7 | Métriques Micrometer et Prometheus, alertes | N | E10 | dev2 | Livré | `/actuator/prometheus` ; indicateurs du 6.7 (temps de réponse, taux 5xx, appels par clé, file OCR, délai de disponibilité, disque, échéance du compte de service et des certificats) ; règles d'alerte aux seuils initiaux, **seuil du délai OCR à 24 h** (revue client D6). **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : règles d'alerte vérifiées sur papier (Prometheus absent du poste). |

### Journalisation et audit (§7)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-076 | 7.1 | Pattern de log imposé avec username, ip, traceId, spanId (MDC) | N | E4 | dev2 | Livré | Pattern exact de l'Article 50 dans `logback-spring.xml` ; filtre MDC ; propagation aux threads asynchrones et aux appels OCR et LDAP ; test sur une ligne de log. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-077 | 7.3.1 | Niveaux de log, rotation quotidienne et par taille, rétention 90 jours | N | E4 | dev2 | Livré | INFO en production, DEBUG activable sans redéploiement ; rotation quotidienne et à 100 Mo, compression, 90 jours. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-078 | 7.4.1 | Table journal_audit : acteur, application, IP, action, objet, avant et après, trace_id | N | E4 | dev2 | À faire | Table partitionnée par mois avec les colonnes exactes du 7.4.1 ; horodatage UTC. |
| T-079 | 7.4.2 | INSERT seul, déclencheurs de refus, scellement SHA-256 chaîné et exporté (**revue client D11** : minimum exigé = aucune modification ni suppression, y compris par l'Administrateur via l'interface ; l'accès direct à la base n'est pas couvert ; le scellement du V3 est conservé) | N | E4 | dev2 | À faire | `ged_app` limité à INSERT et SELECT ; déclencheur `BEFORE UPDATE OR DELETE OR TRUNCATE` ; aucune fonction applicative de modification ou de suppression du journal ; `journal_audit_scellement` horaire exporté hors base ; test : une modification manuelle fait échouer la vérification. Source : revue client (D11). |
| T-080 | 7.4.3 | Écran de consultation, export CSV et JSON, rétention 10 ans (**revue client D11** : liste des actions auditées du §4.9.4 validée) | N | E4 | dev2 | À faire | Écran filtrable réservé Administrateur et Direction Générale (garde branchée en E3), sans action de modification ni de suppression ; consultation auditée ; export avec empreintes de scellement ; partitions jamais supprimées automatiquement. Source : revue client (D11). |

### Qualité logicielle, tests et déploiement (§8 à §10)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-081 | 8.1 | Code documenté | I | E11 | dev2 | Identique | Revue en recette des nouveaux paquets. |
| T-082 | 8.1 | Séparation des couches : DAO, DTO, services, ressources, pages | I | E11 | dev2 | Identique | Revue en recette ; aucune entité exposée par un contrôleur. |
| T-083 | 8.2.1 | Validation complète avant toute écriture, transaction unique | I | E11 | dev3 | Identique | À revérifier après E6 : exception voulue du dépôt en deux temps (12.11). |
| T-084 | 8.2.1 | Format d'erreur uniforme et chaîne de sécurité fermée par défaut | I | E11 | dev2 | Identique | À revérifier après le passage à problem+json (dictionnaire par champ conservé). |
| T-085 | 8.3 | Registre des dépendances avec version et licence (SBOM) | N | E0 | dev2 | Livré | SBOM CycloneDX Maven et npm produit à chaque build, avec licences, y compris Tesseract et modèles. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. SBOM rejoué par pm après fusion : 121 composants, licences renseignées. |
| T-086 | 9.1 | Tests fonctionnels avec Spring Test | I | E11 | dev2 | Identique | Nombre de tests suivi à chaque fusion (jamais en baisse) ; démonstration par sprint. |
| T-087 | 9.2 | Tests de non-régression | P | E0 | dev2 | Livré | Intégration continue : build, tests, JAR et paquet Angular à chaque fusion ; plan de non-régression de qa. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : CI jamais exécutée (aucun push autorisé). |
| T-088 | 9.3 | Environnement UAT et déploiement par module | N | E0 | dev2 | En cours | Profil `uat` ; déploiement incrémental par module documenté. Profil `uat` livré (fusion `95b11e1`) ; déploiement par module limité à back et front, pas par processus métier (§9.3) : Proche. |
| T-089 | 9.4 | Dépôt Git, branche protégée, accès en lecture pour MMED | P | E0 | dev2 | En cours | Configuration de forge documentée (branche protégée, relecteurs en lecture seule). Non vérifiable sur ce poste (aucune forge, `git push` interdit). Procédure `docs/exploitation/FORGE.md` livrée (fusion `95b11e1`) ; réglages de forge non appliqués (aucune forge accessible, push interdit ; Q19). |
| T-090 | 10.1 | Configuration externalisée, même artefact promu sans recompilation | I | E11 | dev2 | Identique | À revérifier après ajout du profil `uat`. |
| T-091 | 10.1 | Secrets hors dépôt, injectés à l'exécution | I | E11 | dev2 | Identique | Contrôle à chaque fusion : aucun secret dans le dépôt ; `.env.example` à jour. |
| T-092 | 10.1 | Procédure scriptée : CI, sauvegarde, migration, test de fumée, retour arrière | P | E10 | dev2 | Livré | Script unique DEV/UAT/PROD : sauvegarde, `liquibase validate` puis `update` par `ged_owner`, déploiement, sondes, test de fumée (connexion, dépôt, recherche), retour arrière. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. `deployer.sh` ; réserve : exécution réelle en UAT. |
| T-093 | 10.2 | JAR exécutable en service système derrière NGINX, serveur Linux | P | E10 | dev2 | Livré | Unité systemd livrée ; serveur distinct de la base. Relecture seulement sur ce poste. **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : unité systemd vérifiée sur papier. |
| T-094 | 10.3 | Code source complet et documentation d'installation | I | E11 | dev2 | Identique | Guide d'installation mis à jour en E11 ; procès-verbal de mise en production préparé. |

### Mécanismes techniques des règles fonctionnelles (§12)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| T-095 | 12.2 | Permissions, rôles, habilitations sur nœud ou document, héritage et rupture (**confirmé par la revue client D14** : héritage, rupture, document isolé et liste des permissions validés) | N | E3 | dev1 | Livré | Tables `role`, `permission`, `role_permission`, `groupe_ged`, `groupe_membre`, `habilitation` ; 9 permissions élémentaires + permissions d'administration + `VOIR_PRIVE`, `VOIR_CONFIDENTIEL` ; 4 rôles système ; tests de résolution (plus spécifique, cumul, rupture, document isolé, `acces_global`). Source : revue client (D14). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-096 | 12.3 | Confidentialité PUBLIC, PRIVE, CONFIDENTIEL et personnes désignées | N | E3 | dev1 | Livré | Colonne obligatoire, `document_confidentiel_designe` ; prédicat SQL appliqué à la source ; niveau par défaut par type ; déposant désigné par défaut ; tests de chaque niveau. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| T-097 | 12.4 | Rattachement d'un document à plusieurs espaces | N | E3 (table et droits) + E7 (API) | dev1 | En cours | `document.noeud_principal_id`, `document_rattachement` unique ; droits en union ; suppression, déplacement et export conformes au 12.4 ; audit `RATTACHEMENT_AJOUTE` et `RATTACHEMENT_RETIRE`. Partie E3 livrée (fusion `5418680`) : table, union des droits, suppression, et aussi `POST`/`DELETE /documents/{id}/rattachements` ; reste l'export ZIP conforme au §12.4 (E7, dev3). |
| T-098 | 12.5 | Déplacement transactionnel avec sous-arborescence et anti-cycle | P | E7 | dev1 | À faire | Mise à jour du chemin matérialisé en une requête ; déplacement de document ; 409 si verrouillé ; audit origine et destination. |
| T-099 | 12.5 | Suppression douce avec auteur et date (**confirmé par la revue client D14** : corbeille pour toute suppression) | P | E1 | dev1 | Livré | Colonnes `supprime`, `supprime_par`, `supprime_le` ; cascade sur la sous-arborescence (E3/E7). Source : revue client (D14). **Livré** : fusion `409970b` (ct/dev1 `8ca3760`), 156 tests verts sur PostgreSQL ; en attente de recette qa. **ANO-E1-002** (majeure, dev1) : indicateur nommé `deleted` au lieu de `supprime` sur 8 tables ; `supprime_par` et `supprime_le` corrects. Correctif ANO-E1-002 livré (fusion `5418680`) : `supprime` au lieu de `deleted`, cascade sur la sous-arborescence des nœuds ; à revérifier par qa. |
| T-100 | 12.5 | Purge définitive avec destruction cryptographique | N | E7 | dev3 | À faire | Purge depuis la corbeille seulement, permission Purger ; suppression des lignes, du fichier et de la DEK ; audit conservé ; test : fichier indéchiffrable après purge. |
| T-101 | 12.6 | **Revue client D10** : archivage par **action manuelle** d'un utilisateur, jamais automatique ; applicable à **un dossier entier** en une fois ; **drapeau d'archivage sur les documents et sur les nœuds (dossiers)** ; lecture seule, empreinte, PDF/A ; job par lot | N | E7 | dev3 | À faire | `document.statut_conservation` et drapeau sur `noeud` ; archivage d'un dossier = `job_archivage` sur ses documents (tranches de 100, reprise, annulation) ; aucune tâche d'archivage automatique ; lecture seule en service et contrainte en base ; désarchivage réservé ; un événement par document. Dépôt dans un dossier archivé : question QR7. Source : revue client (D10). |
| T-102 | 12.7 | Méta-modèle d'index : nature dont booléen, obligatoire, défaut, liste, recherche | P | E7 | dev1 | À faire | Nature booléenne ajoutée (back et Angular) ; test. |
| T-103 | 12.7 | Plan d'indexation et charte de nommage automatique | I | E11 | dev1 | Identique | À revérifier après E7 (plan versionné). |
| T-104 | 12.7 | Métadonnées en JSONB avec index GIN | N | E1 (colonne) + E7 (validation) | dev1 | Vérifié | `document.metadonnees` JSONB, index GIN, index d'expression dates et nombres ; validation contre le plan en E7. Partie E1 livrée (fusion `409970b`) : colonne JSONB et index GIN ; validation contre le plan en E7. Recette qa vague 1 (`recette/RESULTATS-VAGUE-1.md`) : structure vérifiée (C17, C18). La validation contre le plan reste en E7. |
| T-105 | 12.7 | Type : durée de conservation, confidentialité par défaut, plan versionné, re-typologisation | N | E7 | dev1 | À faire | Durée en mois et point de départ ; plan versionné ; `RESTRICT` sur un type utilisé ; `job_retypage` avec table de correspondance, rapport et audit par document. |
| T-106 | 12.8 | Versions : numéro, empreinte, auteur, une seule version courante. **Revue client D9** : l'utilisateur rattache explicitement le nouveau fichier au document existant ; la nouvelle version devient courante et **l'ancienne est automatiquement conservée en lecture seule dans l'historique** (ce n'est pas l'archivage de D10) | P | E7 | dev1 | À faire | `version_document(id, document_id, numero, empreinte, auteur_id, cree_le, courante)` ; index unique partiel ; versement = bascule automatique de la version courante ; versions antérieures immuables. Désignation d'une version antérieure comme courante conservée (V3) en attendant QR6. Source : revue client (D9). |
| T-107 | 12.8 | Verrou avec auteur, date et motif (gel complet du V3 conservé, question QR4) | P | E7 | dev1 | À faire | Verrou posé et levé par l'Administrateur, audité ; 409 sur toute écriture (fiche, versement, déplacement, réindexation, archivage). |
| T-108 | 12.8 | Règle de workflow rattachable à un espace, un dossier ou un type ; validateur nommé ou par rôle (le rattachement d'une **règle** à un type reste dû ; ne pas confondre avec R-04, hors périmètre) | P | E8 | dev1 | À faire | `regle_workflow` et `regle_validateur` ; validateur par rôle résolu au moment de la décision. |
| T-109 | 12.8 | Circuit figé au dépôt | I | E11 | dev1 | Identique | À revérifier après E8 (tables `circuit`, `circuit_validateur`). |
| T-110 | 12.8 | Décisions VALIDE, REFUSE, ANNULEE sans ordre, statut recalculé sur la version courante (**confirmé par la revue client D7** : parallèle, tous les validateurs sollicités en même temps, aucun validateur optionnel, logique entièrement côté back) | N | E8 | dev1 | À faire | Table `decision` ; tests : deux validateurs dans les deux ordres ; nouveau versement qui rend caduques les décisions ; aucun validateur facultatif. Reprise des circuits existants. Source : revue client (D7). |
| T-111 | 12.8 | Annulation de circuit et diffusion ; validateur empêché : **réaffectation explicite par l'Administrateur** d'un validateur en attente, tracée (revue client D1 : la GED ne détecte plus les comptes désactivés ; figement du circuit : question QR1) | N | E8 | dev1 | À faire | Annulation avec motif (statut `ANNULE`, décisions conservées, notification) ; diffusion par habilitation de lecture ; réaffectation d'un validateur en attente par l'Administrateur, auditée. Source : revue client (D1, QR1). |
| T-112 | 12.9 | Échéance de conservation et tâche planifiée d'alerte | N | E8 | dev3 | À faire | `document.echeance_conservation` recalculée ; tâche quotidienne avec verrou de tâche ; filtre « échéance dépassée » ; aucune suppression automatique. |
| T-113 | 12.9 | Notifications : boîte d'envoi, e-mail SMTP et pastille in-app | N | E8 | dev3 | À faire | Table `notification` écrite dans la transaction de l'événement ; envoi asynchrone, 3 reprises ; 3 cas exclusivement ; préférence e-mail ; tests avec serveur SMTP simulé. |
| T-114 | 12.10 | Export de dossier en ZIP en flux avec manifeste CSV | N | E7 | dev3 | À faire | `ZipOutputStream` sans fichier temporaire ; `manifeste.csv` UTF-8 avec les colonnes du 12.10 ; omission silencieuse des documents non autorisés ; traitement de fond au-delà de 500 documents ou 2 Go ; `DOCUMENT_EXPORTE` par document. |
| T-115 | 12.11 | Dépôt en deux temps : fichier reçu, puis indexation INDEXE, SANS_PLAN ou A_INDEXER | P | E6 | dev3 | À faire | Temps 1 et temps 2 en transactions séparées ; issues `INDEXE`, `SANS_PLAN`, `A_INDEXER` et reprise ; HTTP 201 ou 202 ; rejeu sans effet (Idempotency-Key). |

### Exigences du PDF absentes de la matrice (ajoutées par pm)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| P-01 | 3.2, 3.3 | Ajoutée par pm — Attributs AD jamais lus à des fins d'autorisation : `memberOf`, unité d'organisation et groupes exclus (principe P2). **Revue client D3** : lire le strict minimum (`sAMAccountName`, `objectGUID`, nom, prénom, courriel pour les notifications) ; tout autre attribut n'est ajouté que sur besoin | N | E2 | dev1 | Livré | Test : la requête LDAP ne demande que la liste minimale, configurable ; aucun droit dérivé d'un groupe AD. Attributs exacts à retirer : question QR3. Source : revue client (D3). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| P-02 | 3.3 | Ajoutée par pm — Liaison à l'annuaire : LDAPS 636, TLS 1.2, truststore MMED, délais 3 s (connexion) et 5 s (lecture), pool du compte de service, secret rechargé à chaud, expiration du secret surveillée ; annuaire indisponible : aucune connexion possible, aucun mode dégradé, sessions conservées jusqu'à expiration. **Revue client D4** : liste de N contrôleurs avec bascule, fonctionnement nominal avec **un seul** contrôleur (MMED n'en a qu'un aujourd'hui) | N | E2 | dev1 | Livré | Configuration externalisée d'une liste de contrôleurs ; tests avec le simulateur (un contrôleur, deux avec bascule, délai dépassé, annuaire arrêté) ; sonde LDAP. Source : revue client (D4). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. Réserve : vérifié seulement avec l'annuaire simulé (UnboundID). La sonde « annuaire » retenue est celle de dev1 (liaison du compte de service, hors groupe `readiness`) ; la **métrique par contrôleur (D4)** de l'ancienne sonde de dev2 a disparu à la fusion : à réintroduire dans la sonde de dev1 si l'alerte en a besoin (à confier à dev2). |
| P-03 | 3.4.1 | Ajoutée par pm — Protection CSRF du point de renouvellement fondé sur cookie : en-tête personnalisé exigé en plus de `SameSite=Strict` | N | E2 | dev1 | Livré | Test : renouvellement sans l'en-tête refusé. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
| P-04 | 3.4.2 | Ajoutée par pm — Cycle de vie de l'identité : page d'accueil vide pour un compte sans rôle ; compte réactivé qui retrouve ses rôles. **Revue client D1** : la GED ne lit plus l'état du compte AD — désactivation, départ ou mutation sont gérés par l'AD (échec d'authentification) ; la GED ne gère que ses habilitations | N | E2 | dev1 | Livré | Tests avec le simulateur : compte sans rôle ; compte désactivé dans l'AD = connexion refusée ; rôles conservés à la réactivation. Écran Angular pour le compte sans rôle. Source : revue client (D1). **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |
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
| P-19 | 11.2 | Ajoutée par pm — Propriété intellectuelle : dépôt remis complet avec son historique ; licences des bibliothèques compatibles avec la cession, vérifiées dans le registre | N | E0 | dev2 | Livré | Colonne licence du SBOM ; liste des licences refusées au build (point d'attention : veraPDF GPL/MPL, ClamAV GPL en service externe). **Livré** : fusion `95b11e1` (ct/dev2 `79d3bd3`), 296 tests verts sur PostgreSQL ; en attente de recette qa. Licences présentes dans le SBOM. |
| P-20 | 12.5 | Ajoutée par pm — Renommage : permission Modifier, unicité du nom dans le dossier parent (409), audit avant et après | N | E7 | dev1 | À faire | Tests : doublon = 409, événement d'audit. |
| P-21 | 12.7 | Ajoutée par pm — Socle commun de colonnes du document : objet, date du document (clé de tri prioritaire), niveau de confidentialité, durée de conservation déduite du type | N | E7 (colonnes posables dès E1) | dev1 | À faire | Colonnes dédiées ; tri par défaut sur la date du document. |
| P-22 | 12.2.3 | Ajoutée par pm — Consultation des droits effectifs avec leur origine (rôle, nœud d'attribution, héritage, rattachement, confidentialité), par API et écran d'administration ; modification des droits effective immédiatement (compteur `version_habilitations`) et auditée avant et après | N | E3 | dev1 | Livré | Tests : origine exposée ; retrait de droit effectif sans délai ; événement d'audit. **Livré** : fusion `5418680` (ct/dev1 `d33e581`), 360 tests verts sur PostgreSQL ; en attente de recette qa. |

### Exigences issues de la revue technique client (voir `DECISIONS-REVUE-TECHNIQUE.md`)

| N° | Réf. | Exigence | Init. | Étape | Resp. | Statut | Preuve attendue |
|---|---|---|---|---|---|---|---|
| R-01 | Revue D8 | Source : revue client — Pilotage nominatif du workflow par API pour l'intranet et les applications tierces : créer et modifier les règles de circuit, désigner les validateurs, consulter l'état d'un circuit, annuler un circuit | N | E8-API | dev2 | À faire | Points d'entrée REST documentés dans l'OpenAPI, soumis aux clés API (portée), à Idempotency-Key et à l'audit ; chaque action attribuée à une personne nommée (délégation `X-On-Behalf-Of`) ; test de contrat. |
| R-02 | Revue D8 | Source : revue client — Validation depuis une application tierce : décision VALIDE, REFUSE (motif obligatoire) ou ANNULEE rendue par l'intranet pour le compte du validateur désigné | N | E8-API | dev2 | À faire | Test : une clé habilitée à déléguer enregistre la décision du validateur désigné, avec double identité dans l'audit ; refus si le délégué n'est pas validateur du circuit ; même calcul de statut que dans l'interface. |
| R-03 | Revue D12 | Source : revue client — Espace de partage simple pour les fichiers en élaboration (cas des marchés, CPS) : déposer, télécharger, modifier localement, téléverser une nouvelle version ; aucune co-édition ni édition en ligne ; aucune édition dans l'espace d'archive | N | E7 | dev1 | À faire | Nature d'espace « échange » sur le nœud ; test du cycle déposer, télécharger, verser une nouvelle version sous habilitations de groupe ; absence de toute fonction d'édition en ligne. |
| R-04 | Revue D13 | Source : revue client — Restriction d'un validateur à un type de document (un profil ne valide que certains types) : jugée trop détaillée, **hors périmètre du marché** | — | — | pm | Hors périmètre | Ne pas implémenter. Confirmation écrite à obtenir (le compte rendu dit « probablement »). Ne concerne pas T-108. |
