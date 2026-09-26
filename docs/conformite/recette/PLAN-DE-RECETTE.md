# Plan de recette — conformité technique de la GED Marchica Med

Rédigé par : qa. Références : Dossier d'analyse technique et d'intégration V3 (le PDF fait
foi), `FEUILLE-DE-ROUTE.md` (critères de sortie), `MATRICE-TECHNIQUE.md` (colonne « Réf. »),
`DECISIONS-REVUE-TECHNIQUE.md` (décisions **actées** D1 à D14, qui priment sur le V3).

## 1. Principes

- **Une étape n'est close que sur son critère de sortie**, démontré par des cas d'acceptation
  exécutés et tracés (DAT §9.1 : démonstration à chaque sprint ; §9.2 : non-régression).
- Chaque exigence clôturée par une étape a au moins un cas (colonne **Réf.** = référence de la
  matrice). Un cas modifié par une décision de la revue technique cite la décision (D1…D14).
- **Un contrôle qui ne sait pas échouer ne prouve rien** : chaque script de recette a son
  autotest (jeu conforme → aucun écart ; jeu non conforme → chaque défaut volontaire détecté).
- Données fictives uniquement (`recette/donnees/`), mots-témoins inventés (`zarkolinet`,
  `زركولين`, `zarkopage01…20`).
- Aucun Python dans l'outillage (D5) : bash + psql + curl sur les serveurs, Java 17 en mode
  fichier source sur les postes et l'intégration continue.
- Toute anomalie → `ANOMALIES.md` ; non-régression : `mvn test` comparé à `LIGNE-DE-BASE.md`
  (143 tests) après chaque intégration.

### Types de preuve

| Code | Preuve |
|---|---|
| **AUTO** | Test automatisé Java du dépôt (`mvn test`), écrit par le développeur du lot, relu par qa |
| **SCRIPT** | Script de recette indépendant du code applicatif (`recette/…`), sortie `RESULTAT|…` archivée |
| **REVUE** | Revue de configuration ou de code, avec la commande ou le fichier examiné |
| **MANUEL** | Scénario manuel en UAT, captures et journal d'audit joints au procès-verbal |

### Où le cas peut être vérifié

| Code | Signification |
|---|---|
| **P** | Réellement sur ce poste (PostgreSQL 16 local, JDK 17, Tesseract `fra`) |
| **S** | Sur ce poste avec un **simulateur** seulement (faux clamd, LDAP embarqué UnboundID, faux SMTP, keystore de test) : la preuve définitive reste à faire en UAT |
| **U** | En UAT seulement (NGINX, TLS et certificat MMED, AD réel, ClamAV réel, LibreOffice, veraPDF, Prometheus, forge, serveurs Linux) |

## 2. Outillage livré

| Élément | Emplacement | État |
|---|---|---|
| Ligne de base | `docs/conformite/recette/LIGNE-DE-BASE.md` | 143 tests, 0 échec (H2) |
| Jeux de données | `recette/donnees/` + `generer-donnees.sh` (Java) | Versionnés, reproductibles |
| Socle E1 | `recette/e1/` : `verifier-base-vierge.sh`, `verifier-rollback.sh`, `verifier-socle.sh`, `AnalyseurChangelogs.java` | Prêts, autotestés, pré-exécutés sur `ct/dev1` |
| Stockage E5 | `recette/e5/` : `verifier-aucun-clair.sh`, `verifier-alteration.sh`, `verifier-antivirus.sh`, `verifier-type-reel.sh`, `verifier-taille.sh`, `verifier-composants.sh` | Prêts, autotestés ; banc des composants intégrés 19/19 ; scripts HTTP exécutés contre l'application d'origine, à rejouer en vague 2 |
| Fumée | `recette/fumee/fumee.sh` | Prête, exécutée 7/7 contre l'application actuelle |
| Scripts E2 à E11 | à écrire vague par vague, sur le même modèle | — |

---

## E0 — Cadrage et outillage

Critère de sortie : chaque fusion déclenche build, tests, rapport de vulnérabilités et SBOM ; un environnement UAT existe.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E0-01 | 6.2.3 A06 | Branche d'essai ajoutant une dépendance à vulnérabilité connue (ex. `commons-text` 1.9, CVE-2022-42889) | Pousser, laisser tourner l'intégration continue | Rapport Dependency-Check produit à chaque construction ; la CVE est listée ; seuil de gravité appliqué (échec au-delà du seuil convenu) | REVUE du pipeline + rapport HTML/JSON archivé | U (base NVD à télécharger) |
| CR-E0-02 | 8.3 | Construction nominale | `mvn package` et `npm run build` en CI | SBOM CycloneDX back et front ; chaque composant a version **et** licence ; Tesseract et les modèles `fra`/`ara` figurent comme composants tiers ; licences compatibles avec la cession à MMED (§11.2) | REVUE du `bom.json` (script de comptage des composants sans licence = 0) | P (hors ligne si greffon en cache) / U |
| CR-E0-03 | 9.2 | Branche d'essai avec un test volontairement rouge | Demande de fusion | Pipeline rouge, fusion impossible ; suite verte : fusion possible | REVUE forge | U |
| CR-E0-04 | 9.3 | Profil `uat` | Démarrer le JAR avec `--spring.profiles.active=uat` et la configuration UAT | Démarre ; mêmes contraintes que `prod` (clé JWT obligatoire, `ddl-auto: validate`, Actuator restreint) ; même artefact promu sans recompilation (§10.1) | REVUE + démarrage | P (démarrage) / U |
| CR-E0-05 | 9.4 | Compte relecteur MMED | Tenter un `push` direct sur la branche principale ; se connecter avec le compte MMED | Push refusé ; le compte MMED lit branches, historique et demandes de fusion, n'écrit rien | REVUE forge | U |

## E1 — Socle de données

Critère de sortie : base vierge créée uniquement par Liquibase, rollback testé en UAT, tous les tests verts sur PostgreSQL.
Scripts : `recette/e1/` (voir son README). Entrée : branche intégrée, rôles créés par `creer-roles.sql`.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E1-01 | 2.2 SGBD | Base `ged_qa_recette_e1` | `verifier-socle.sh` (E1-C24, C25, C26) ; `SELECT to_tsvector('arabic','المكتبات')` | PostgreSQL ≥ 16, UTF-8, configurations `french` et `arabic` présentes, lemme arabe non vide | SCRIPT | P |
| CR-E1-02 | 2.2 Liquibase | Sources intégrées | `AnalyseurChangelogs.java` (A21), `verifier-socle.sh` (C19) | `liquibase-core` présent, aucune trace de Flyway (dépendance, configuration, `flyway_schema_history`, `db/migration`) | SCRIPT | P |
| CR-E1-03 | 4.2.1 aucune DDL hors migration | Base supprimée puis recréée | `verifier-base-vierge.sh --demarrer-application` (V01–V05) ; A20, A22 | Base vide → schéma complet par Liquibase seul (compte `ged_owner`) ; l'application démarre en `ged_app` ; **DDL identique avant et après démarrage** ; `ddl-auto` = validate/none partout ; ni `schema.sql` ni `data.sql` | SCRIPT | P |
| CR-E1-04 | 4.2.1 amorçage | Même base | C22, C23, A07, A08, A23 | Amorçage (rôles système, niveaux de confidentialité, valeurs par défaut) en changesets `data-initial` ; aucun référentiel métier (types, index, plans, espaces, règles) peuplé par changeset ; plus d'écriture en base par un `CommandLineRunner` | SCRIPT | P |
| CR-E1-05 | 4.2.2 conventions | Même base + sources | C01–C13, A03, A04, A10 | snake_case ; PK `id` ; FK `<table>_id` ; préfixes `idx_`, `uk_`, `fk_`, `ck_` ; un fichier par évolution `AAAAMMJJHHmm_objet.xml` | SCRIPT | P |
| CR-E1-06 | 4.2.2 rollback, expand/contract | Base migrée (poste) puis **copie** de la base UAT | `verifier-rollback.sh` (R01–R04) ; A05, A06, A12 ; en UAT : même script sur la copie restaurée | Chaque changeset a un retour arrière exécutable ; schéma vidé ; aller-retour update → rollback → update au DDL identique ; opérations destructives listées pour revue expand/contract | SCRIPT + REVUE | P puis U |
| CR-E1-07 | 4.2.3 trois rôles | Même base | C30–C41, sondes P01–P23 | Trois rôles non superutilisateurs ; `ged_owner` propriétaire de tout ; `ged_app` DML seul (CREATE, ALTER, DROP réellement refusés) ; `ged_readonly` SELECT seul ; privilèges par défaut en place ; rien pour PUBLIC | SCRIPT | P |
| CR-E1-08 | 12.1 UUID | Même base + API | C04–C06, A11 ; `GED_EXIGER_UUID=1 fumee.sh` | Aucune PK entière ni séquence (exception documentée : journal d'audit, §7.4.1) ; l'API ne renvoie que des UUID | SCRIPT | P |
| CR-E1-09 | 12.1 sept groupes | Schéma + modèle de la Phase 4 | Comparer les tables aux sept groupes du §12.1 | En E1 : groupes « organisation documentaire », « typologie », « versions et contenu » en place ; les autres groupes arrivent avec E2 à E9 et sont revérifiés en E11 (CR-E11-01) | REVUE | P |
| CR-E1-10 | 5.3.2 JSON UTF-8, ISO 8601 UTC, UUID | Réponses de l'API | Fumée + contrôle des champs date | Horodatages `timestamptz` (C14) ; dates renvoyées en ISO 8601 suffixées `Z` ; `Content-Type: application/json;charset=UTF-8` | SCRIPT + AUTO | P |
| CR-E1-11 | 12.5 suppression douce | Document déposé par l'utilisateur U | `DELETE /documents/{id}` puis lecture en base | `supprime` vrai, `supprime_par` = U, `supprime_le` renseigné (UTC) ; le document disparaît des listes et reste restaurable | SCRIPT (C15, C16) + AUTO | P |
| CR-E1-12 | 12.7 JSONB + GIN | Base migrée avec quelques documents | C17, C18 ; `EXPLAIN` d'une requête `metadonnees @> '{…}'` avec `enable_seqscan=off` | Colonne `jsonb`, index GIN utilisé par le plan | SCRIPT + REVUE | P |
| CR-E1-13 | Critère « tests verts sur PostgreSQL » | Base `ged_qa_test` | `DB_NAME_TEST=ged_qa_test mvn test` | Même nombre de tests que la ligne de base (± suppressions justifiées), 0 échec, sur PostgreSQL (pas de Testcontainers : base locale, règle 5 du brief) | AUTO | P |
| CR-E1-14 | 4.2.1 reprise | Dump MySQL de production anonymisé | Script de reprise de dev1 puis comparaison | Nombre de lignes égal par table, échantillon de fiches identique, FK valides, anciens identifiants tracés | SCRIPT + REVUE | U (dump MySQL requis) |

Pré-recette réalisée le 2026-09-26 sur `ct/dev1` non intégrée : voir §4.

## E2 — Identité et sessions

Critère de sortie : connexion avec un compte AD de test en UAT, aucun mot de passe en base, révocation effective immédiatement.
Simulateur sur poste : annuaire embarqué UnboundID en LDAPS avec comptes de test (actif, désactivé, renommé).

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E2-01 | 3.2 | Schéma migré | Rechercher toute colonne `*mot_de_passe*`, `*password*`, `*hash*` hors clés API ; lire la table des comptes | Aucune ; plus de table de mots de passe | SCRIPT (requête catalogue) | P |
| CR-E2-02 | 3.3 LDAPS search-then-bind — **D2, D4** | Compte `recette.agent` (UID) | Connexion par **UID `sAMAccountName`** ; puis même compte par **adresse e-mail** ; mauvais mot de passe ; URL `ldap://` en configuration | UID : 200. **E-mail : refusé (D2 : jamais l'e-mail)**. Mauvais mot de passe : 401 sans détail. `ldap://` : démarrage refusé. Un seul contrôleur configuré fonctionne ; avec deux, arrêt du premier → bascule (D4) | AUTO (UnboundID) + MANUEL | S puis U |
| CR-E2-03 | 3.3 provisionnement — **D2** | Compte AD inconnu de la GED | Première connexion ; puis renommage du login dans l'annuaire (même objectGUID) | Identité créée sans rôle, clé `objectGUID` ; page d'accueil vide ; après renommage : aucun doublon | AUTO + MANUEL | S puis U |
| CR-E2-04 | 3.3 jeton court | Jeton d'accès émis | Décoder le JWT | Durée 15 min (`exp - iat = 900`) ; ne porte que l'identifiant GED et l'identifiant d'annuaire ; **aucun rôle ni permission** | AUTO | P |
| CR-E2-05 | 3.4.1 RS256 | Jeton émis ; jetons forgés `HS256` et `alg: none` | Appeler l'API avec chaque jeton ; inspecter le stockage du navigateur | RS256 accepté, forgés refusés (401) ; clé privée hors dépôt (coffre) ; aucun jeton dans `localStorage` ni `sessionStorage` | AUTO + REVUE | P |
| CR-E2-06 | 3.4.1 renouvellement | Session ouverte | Renouveler ; rejouer l'ancien jeton de renouvellement ; déconnexion ; attendre 30 min d'inactivité (horloge accélérée) | Cookie `HttpOnly; Secure; SameSite=Strict` ; rotation à chaque usage ; rejeu → **toute la famille révoquée** ; déconnexion → effet immédiat ; 8 h absolues, 30 min d'inactivité ; en-tête personnalisé exigé (CSRF) | AUTO | P |
| CR-E2-07 | 3.4.1 anti-force brute | 6 échecs en une minute, même IP ; 6 échecs, même identifiant, IP différentes | Rejouer la connexion | 6e tentative → 429 dans les deux cas ; chaque échec audité (E4) | AUTO | P |
| CR-E2-08 | 3.4.2 cache annuaire — **D1, T2** | Compte dont le nom change dans l'annuaire | Afficher une liste « déposant » avant et après 15 min | `cache_annuaire` relu à expiration (15 min) ; **aucune relecture de `userAccountControl` ni tâche des 5 minutes (D1)** : vérifier leur absence | AUTO + REVUE | S |
| CR-E2-09 | 3.4.1 révocation — **D1, R1** | Compte désactivé dans l'annuaire pendant une session ouverte | Tenter une **nouvelle** connexion ; puis l'Administrateur révoque les sessions de l'utilisateur | Nouvelle connexion refusée (le bind échoue, D1) ; la session en cours persiste jusqu'à l'expiration du renouvellement (risque R1 accepté) ; **révocation manuelle effective immédiatement** (critère de sortie) | AUTO + MANUEL | S puis U |
| CR-E2-10 | 3.3 attributs — **D3** | Journal des requêtes du simulateur | Connexion | Attributs demandés = strict minimum (identifiant, objectGUID, nom, prénom, courriel) ; jamais `memberOf` ni unité d'organisation (P2) ; liste exacte à confirmer (Q3) | AUTO (UnboundID) | S |

## E3 — Autorisation et confidentialité

Critère de sortie : un utilisateur sans droit ne voit ni ne compte aucun document hors de son périmètre, sur tous les chemins testés.
Jeu d'habilitations à constituer : arborescence de 3 niveaux, 6 utilisateurs (sans rôle, Utilisateur standard, Agent d'archive, Administrateur, Direction Générale, désigné), 12 documents couvrant PUBLIC / PRIVE / CONFIDENTIEL, dont un rattaché à deux espaces.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E3-01 | 6.2.3 A01 | Document D hors périmètre de U ; identifiant inexistant | U appelle fiche, contenu, prévisualisation, versions, droits, export sur D puis sur l'inexistant | **404 identiques** (statut, corps, temps de réponse comparable) : rien ne distingue « interdit » de « absent » (P5) | AUTO + SCRIPT | P |
| CR-E3-02 | 6.4 point unique | Tous les chemins d'accès | Matrice chemins × profils : recherche, arbre, compteurs, totaux, extraits, prévisualisation, téléchargement, ZIP, API, écritures ; revue : toute requête documentaire passe par `AccessPredicate` | Aucun chemin ne contourne le filtrage ; un test automatisé par chemin | AUTO + REVUE | P |
| CR-E3-03 | 12.2 résolution — **D14** | Jeu d'habilitations | (a) héritage espace → dossiers ; (b) habilitation explicite qui restreint ; (c) rupture sans attribution ; (d) document isolé visible dans tous ses emplacements ; (e) union de deux rôles ; (f) Direction Générale sans ligne d'habilitation, nouvel espace visible aussitôt ; (g) retrait d'un droit : effet immédiat ; (h) aucun droit déduit d'un groupe AD | Chaque règle conforme au §12.2, validée par la revue (D14) ; droits effectifs consultables avec leur origine | AUTO | P |
| CR-E3-04 | 12.3 confidentialité | Documents PUBLIC, PRIVE, CONFIDENTIEL dans un espace accessible | Chaque profil consulte ; désigner puis retirer une personne ; déplacer un confidentiel vers un espace où le désigné n'a aucun droit ; exporter en ZIP | PRIVE : déposant + VOIR_PRIVE (Agent d'archive, Administrateur, DG) ; CONFIDENTIEL : désignés + VOIR_CONFIDENTIEL (Administrateur, DG) ; intersection avec l'emplacement ; retrait immédiat ; ZIP : omissions silencieuses, comptées seulement dans l'audit | AUTO | P |
| CR-E3-05 | Critère | Utilisateur sans aucun droit | Recherche par mot-témoin, arbre, tableaux de bord | 0 résultat, 0 dans chaque total et compteur, aucun extrait | AUTO + SCRIPT | P |
| CR-E3-06 | 12.2 écrans | Administrateur | Créer un rôle, composer ses permissions, habiliter un groupe, consulter les droits effectifs ; menus d'un utilisateur limité | Effet immédiat sans redéploiement ; modifications auditées avant/après ; menus masqués (confort, pas sécurité) | MANUEL | P |

## E4 — Journalisation et audit

Critère de sortie : chaque action produit un événement ; une modification manuelle du journal fait échouer la vérification du scellement.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E4-01 | 7.1 MDC | Requête authentifiée, requête anonyme, dépôt suivi du job OCR | Lire le journal technique | Chaque ligne suit exactement `%d{HH:mm:ss.SSS} - [%X{username:-}] [%X{ip:-}] [%thread] - %X{traceId:-}/%X{spanId:-} %-5level - %logger{36} - %msg%n` ; `username` renseigné si authentifié ; même `traceId` dans le worker OCR | SCRIPT (expression régulière sur le journal) | P |
| CR-E4-02 | 7.3.1 | Configuration Logback prod | Revue ; forcer la rotation avec une taille réduite | INFO en prod, DEBUG réglable sans redéploiement ; rotation quotidienne et à 100 Mo, compression, 90 jours | REVUE + SCRIPT | P |
| CR-E4-03 | 7.4.1 | Liste des événements du §7.4.1 | Réaliser chaque action (connexion réussie/refusée, consultation, prévisualisation, téléchargement, dépôt, versement, métadonnées, déplacement, renommage, rattachement ±, archivage ±, droits, décision, suppression, restauration, purge, appel API, administration) | Un événement par action avec code stable, acteur utilisateur **et** acteur application, IP, objet, avant/après, résultat, `trace_id` ; opérations de masse : un événement par document ; refus de droits tracés | AUTO + SCRIPT | P |
| CR-E4-04 | 7.4.2 — **D11** | Journal peuplé | `verifier-socle.sh` (C36, P20–P23 deviennent applicables) ; en superutilisateur : UPDATE/DELETE/TRUNCATE ; recenser les routes de l'API et les écrans | `ged_app` INSERT/SELECT seuls ; déclencheurs de refus actifs ; **aucune fonction de modification ou de suppression du journal dans l'application, y compris pour l'Administrateur (D11)** | SCRIPT + REVUE | P |
| CR-E4-05 | 7.4.2 scellement | Journal scellé | Modifier une ligne en superutilisateur (déclencheur désactivé), lancer la vérification | Chaîne SHA-256 invalide, période désignée ; scellement exporté hors base ; la vérification est elle-même tracée | SCRIPT | P |
| CR-E4-06 | 7.4.3 | Administrateur, DG, utilisateur standard | Consulter, filtrer, paginer, exporter CSV et JSON | Réservé à l'Administrateur et à la DG ; exports avec empreintes de scellement ; consultation auditée ; partitions mensuelles jamais supprimées automatiquement | AUTO + MANUEL | P |

## E5 — Stockage sécurisé des fichiers

Critère de sortie : aucun fichier en clair sur le disque ; un fichier altéré est détecté ; un fichier infecté est refusé.
Scripts : `recette/e5/` (voir son README). Exécutés le 2026-09-26 contre l'application actuelle : échecs attendus (E5 non intégré), ce qui prouve qu'ils détectent.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E5-01 | 2.3.2 briques | `pom.xml`, configuration | Revue | Tika, client ClamAV, conversion LibreOffice, keystore PKCS#12 via `KeyProvider`, Micrometer présents (veraPDF en E7) | REVUE | P |
| CR-E5-02 | 6.1.1 | Tout le jeu `recette/donnees` déposé | `verifier-aucun-clair.sh` (S01, S06, S07) ; arrêt brutal (`kill -9`) pendant un dépôt de 150 Mo | `aa/bb/<uuid>.enc` dérivé de l'identifiant ; aucun fichier partiel publié, seul un `.tmp` subsiste puis est nettoyé | SCRIPT + MANUEL | P |
| CR-E5-03 | 6.1.2 chiffrement | Même stockage | `verifier-aucun-clair.sh` (S02–S05) ; rotation de la KEK ; recherche de keystore sous la racine | En-tête `GEDC` v1, aucune signature ni chaîne en clair, contenu incompressible ; après rotation : fichiers `.enc` **inchangés** (même SHA-256), DEK réenveloppées (`cle_fichier`), anciens documents lisibles ; aucune clé à côté des fichiers | SCRIPT + AUTO | P (keystore de test) / U |
| CR-E5-04 | 6.1.4 empreinte | `pdf_texte_fr_convention.pdf` | Déposer ; comparer `version_document.empreinte` au `MANIFESTE.csv` ; altérer puis lancer la vérification à la demande | Empreinte du clair identique au manifeste ; divergence détectée (A08), alerte et événement d'audit (A09) | SCRIPT | P |
| CR-E5-05 | 6.1.5 type réel | `faux_pdf_*.pdf`, DOCX et PNG renommés | `verifier-type-reel.sh` (R01–R06) | 415 `FORMAT_NON_AUTORISE` sans rien écrire ; vrai PDF accepté quelle que soit l'extension | SCRIPT | P |
| CR-E5-06 | 6.1.5 antivirus | EICAR reconstitué à la volée | `verifier-antivirus.sh` (V01–V03), puis `--antivirus-arrete` (V04) | 422 `FICHIER_INFECTE` sans rien écrire ; ClamAV arrêté → 503 `ANTIVIRUS_INDISPONIBLE` (échec fermé) ; refus audité (V05, dès E4) | SCRIPT | S (faux clamd) puis U |
| CR-E5-07 | 6.1.5 taille | Fichiers creux 200 Mio + 1, limite du type ± 1 | `verifier-taille.sh` (T01–T04) | 413 (`FICHIER_TROP_VOLUMINEUX`) sans rien écrire ; borne incluse acceptée ; derrière NGINX, 413 de `client_max_body_size` | SCRIPT | P / U (NGINX) |
| CR-E5-08 | 6.1.6 prévisualisation | PDF, PNG, DOCX | Prévisualiser en surveillant les dossiers temporaires ; utilisateur hors périmètre ; `verifier-aucun-clair.sh --racine-cache` | PDF et images servis déchiffrés en flux, aucune copie en clair ; DOCX converti par LibreOffice, cache **chiffré** ; 404 hors périmètre ; événement d'audit distinct | SCRIPT + AUTO | S (LibreOffice absent) / U |
| CR-E5-09 | 6.1.2 altération | Fichiers stockés | `verifier-alteration.sh` (A01–A07) | Octet inversé, troncature, substitution, fin de grand fichier altérée : aucun contenu altéré servi ; restauration → lecture identique | SCRIPT | P |
| CR-E5-10 | Reprise | Stockage existant en clair | Lancer la reprise de dev3 puis `verifier-aucun-clair.sh` sur toute la racine | 0 écart ; toute version a une empreinte non nulle | SCRIPT | P / U |

## E6 — OCR asynchrone et recherche plein texte

Critère de sortie (modifié par **D6**) : un scan arabe ou français de 20 pages est trouvable par son contenu, dans le périmètre de l'utilisateur, **dans un délai de 24 heures au plus** (le V3 disait 5 minutes ; on mesure et on rapporte le délai réel).
Données : `bash recette/donnees/generer-donnees.sh --pages-scan 20 --sortie genere/`. Tests OCR **en Java seul, sans service supplémentaire (D5)**.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E6-01 | 4.3.2 protocole — **D5** | Échantillon MMED de 300 pages, vérité terrain de 100 pages | Exécuter le protocole (outil de calcul CER/WER en Java) | Rapport CER, WER, débit ; seuils ≤ 5 % (fr imprimé), ≤ 10 % (ar), ≥ 6 pages/min/cœur | SCRIPT + REVUE | U (échantillon) |
| CR-E6-02 | 4.3.3 cloisonnement | `scan_fr_courrier.pdf` | Déposer, attendre l'OCR, ouvrir la fiche | Aucun champ d'index pré-rempli ; plus aucun point d'entrée de pré-remplissage | AUTO | P |
| CR-E6-03 | 4.3.4 asynchrone | Même scan ; deux workers | Déposer ; lire `ocr_job` ; faire tourner deux workers sur 20 jobs | HTTP 202 `EN_ATTENTE_OCR` immédiat ; chaque job traité une seule fois (`SKIP LOCKED`) | AUTO | P |
| CR-E6-04 | 4.3.4 langues | `scan_ar_courrier.pdf`, type réglé sur `ara` | Déposer ; rechercher `زركولين` | Trouvé ; `fra+ara` par défaut, réglable par type | AUTO | P si `ara.traineddata` installé (absent aujourd'hui) |
| CR-E6-05 | 4.3.4 sans plafond | `scan_fr_20p.pdf` | Déposer ; rechercher `zarkopage20` | Trouvé : la 20e page est traitée ; traitement page par page (mémoire bornée) | AUTO + SCRIPT | P |
| CR-E6-06 | 4.3.4 tmpfs | Même scan | Surveiller les dossiers temporaires persistants pendant l'OCR (`verifier-aucun-clair.sh` en boucle sur ces dossiers) ; revue du montage | Aucune page rendue ni PDF déchiffré sur disque persistant | SCRIPT + REVUE | P / U (tmpfs Linux) |
| CR-E6-07 | 4.3.4 échecs | PDF corrompu, PDF protégé par mot de passe | Déposer avec délais de reprise raccourcis | 3 tentatives (1, 5, 30 min), `OCR_ECHEC` avec motif, document téléchargeable et signalé « non interrogeable », visible à l'écran de supervision, relance manuelle | AUTO | P |
| CR-E6-08 | 4.3.4 délai — **D6** | Scan de 20 pages ; pièce jointe de 400 à 800 pages | Mesurer dépôt → disponibilité ; lire la métrique | `document_texte` renseigné ; métrique `ocr_delai_disponibilite` exposée ; objectif et alerte à **24 h** (D6) ; délai réel consigné | SCRIPT + REVUE | P / U |
| CR-E6-09 | 4.4 tsvector | Base avec les jeux déposés | Requêtes catalogue ; recherche « amenagement » (sans accent) ; recherche du témoin arabe dans `pdf_texte_ar_courrier.pdf` (formes de présentation) | `tsv` = french ∥ arabic, index GIN ; insensible aux accents ; texte arabe normalisé (NFKC) retrouvé | SCRIPT + AUTO | P |
| CR-E6-10 | 4.4 requête | Deux documents dont un rattaché à deux espaces | Expression entre guillemets, exclusion `-mot`, tri par pertinence | `websearch_to_tsquery` ; extraits `ts_headline` sur la seule page affichée ; tri `ts_rank_cd` ; **une ligne par document** (EXISTS) | AUTO | P |
| CR-E6-11 | 4.4.1 réindexation | Document versionné | Nouvelle version sans le témoin ; réindexation complète | L'ancien témoin n'est plus trouvé ; réindexation complète réservée à l'Administrateur, recherche disponible pendant | AUTO | P |
| CR-E6-12 | 5.3.1 | Utilisateur sans droit | Rechercher `zarkolinet` | 0 résultat, 0 au total, aucun extrait ; pagination plafonnée à 200 | AUTO | P |

## E7 — Modèle documentaire et cycle de vie

Critère de sortie : un document archivé est intouchable, possède sa copie PDF/A validée, et un export ZIP ne contient que ce que l'utilisateur peut voir.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E7-01 | 6.1.4 PDF/A — **D10** | `document_fr.docx`, `pdf_texte_fr_convention.pdf` | Archiver | Copie PDF/A-2 générée et **validée par veraPDF**, chiffrée ; l'original conservé ; les fichiers Word restent archivables, **conversion PDF obligatoire** (D10) ; conversion en échec → archivé quand même, anomalie journalisée | AUTO + SCRIPT | S (LibreOffice, veraPDF absents) / U |
| CR-E7-02 | 12.4 | Document D, espaces A (principal) et B | Rattacher à B ; supprimer depuis B ; supprimer depuis A | Aucun fichier copié (nombre de `.enc` inchangé) ; droits = union ; retrait = lien seul ; suppression depuis A = corbeille partout ; rattacher au principal refusé ; `RATTACHEMENT_AJOUTE/RETIRE` audités | AUTO + SCRIPT | P |
| CR-E7-03 | 12.5 déplacement | Dossier à 3 niveaux, document verrouillé | Déplacer le dossier, puis dans sa propre sous-arborescence ; déplacer le verrouillé ; renommer en doublon | Chemins mis à jour en une transaction ; anti-cycle refusé ; 409 verrouillé ; 409 doublon ; origine et destination auditées | AUTO | P |
| CR-E7-04 | 12.5 purge | Document en corbeille ; document actif | Purger | Actif : refusé ; corbeille : lignes métier et fichier supprimés, **DEK détruite** (`cle_fichier`), audit conservé | SCRIPT + AUTO | P |
| CR-E7-05 | 12.6 archivage — **D10** | Dossier de 250 documents | Archiver **manuellement le dossier entier** ; tenter toute écriture | Aucun archivage automatique (D10) ; drapeau sur le dossier et ses documents ; traitement de fond par tranches de 100 ; lecture seule pour tous (409/422), doublé d'une contrainte en base ; empreinte revérifiée ; désarchivage réservé, audité ; un événement par document | AUTO | P |
| CR-E7-06 | 12.7 méta-modèle | Plan avec index texte, nombre, date, liste, **booléen**, obligatoire, défaut | Déposer avec métadonnées valides, puis sans l'obligatoire, puis avec une valeur hors liste | JSONB validé contre le plan ; 422 sur manquant ou invalide ; index d'expression dates/nombres utilisés | AUTO | P |
| CR-E7-07 | 12.7 type — **D13** | Type utilisé par des documents | Modifier le plan ; supprimer le type ; re-typologiser un lot | Nouvelle version du plan, anciens documents inchangés ; suppression refusée (RESTRICT), désactivation seule ; `job_retypage` avec rapport et audit ; pas de validateur restreint à un type (D13 : hors périmètre) | AUTO | P |
| CR-E7-08 | 12.8 versions — **D9**, Q4 | Document avec une version | Ajouter une version ; tenter deux versions courantes en base | **L'ancienne version passe automatiquement au statut archivé, en lecture seule, et la nouvelle lui est liée explicitement (D9)** ; numéro, empreinte, auteur ; index unique partiel : une seule courante. « Désigner une ancienne version courante » : cas maintenu selon le V3 en attendant Q4 | AUTO + SCRIPT | P |
| CR-E7-09 | 12.8 verrou — Q4 | Document verrouillé | Toute écriture (fiche, versement, déplacement, réindexation, archivage) | 409 partout (gel complet retenu par l'équipe, Q4) ; auteur, date, motif ; posé et levé par l'Administrateur, audité | AUTO | P |
| CR-E7-10 | 12.10 export ZIP | Dossier mêlant documents visibles, privés non visibles, un rattaché | Exporter en utilisateur limité ; exporter 600 documents | Flux sans fichier temporaire ; `manifeste.csv` UTF-8 complet ; rattaché présent une fois avec tous ses chemins ; non autorisés omis sans trace dans l'archive ; `DOCUMENT_EXPORTE` par document ; au-delà de 500 documents ou 2 Go : traitement de fond | AUTO + SCRIPT | P |
| CR-E7-11 | 12.11 deux temps | Métadonnées invalides au dépôt | Déposer ; reprendre l'indexation | Fichier conservé, issue `A_INDEXER`, reprise possible ; `INDEXE` ou `SANS_PLAN` sinon ; 201 ou 202 (OCR) | AUTO | P |
| CR-E7-12 | Espace de partage — **D12** | Espace d'échange « marchés » | Déposer, télécharger, modifier en local, verser une nouvelle version | Parcours complet ; aucune édition en ligne ni co-édition | MANUEL | P |

## E8 — Workflow, conservation et notifications

Critère de sortie : deux validateurs décident dans n'importe quel ordre ; chaque ouverture, décision et annulation notifie les bonnes personnes.
Simulateur : serveur SMTP embarqué (GreenMail ou équivalent Java).

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E8-01 | 12.8 règle — **D13** | Règles sur un espace, un dossier, un type ; validateur nommé et par rôle | Déposer dans chaque cas | Circuit figé au dépôt (copie de la règle) ; validateur par rôle résolu à la décision ; aucune restriction « validateur par type de document » (D13) | AUTO | P |
| CR-E8-02 | 12.8 décisions — **D7** | Deux validateurs V1, V2 | Ouvrir : les deux notifiés **en même temps** ; décider V2 puis V1, puis l'inverse ; refuser sans motif ; verser une nouvelle version | Parallèle, sans ordre (D7), aucun validateur optionnel ; VALIDE quand tous ont validé la version courante ; un REFUSE suffit ; motif obligatoire ; nouvelle version → décisions caduques, EN_COURS ; logique entièrement côté back | AUTO | P |
| CR-E8-03 | 12.8 annulation, diffusion — Q1 | Circuit en cours | Annuler avec motif ; diffuser un document validé | ANNULE, décisions conservées, validateurs notifiés ; diffusion par habilitation de lecture, sans copie. Réaffectation d'un validateur : testée seulement si Q1 est tranchée | AUTO | P |
| CR-E8-04 | 12.9 échéance | Documents échus ; deux instances de l'application | Lancer la tâche quotidienne sur les deux instances | Exécutée une seule fois (verrou de tâche) ; Agents d'archive notifiés ; filtre « échéance dépassée » ; aucune suppression | AUTO | P |
| CR-E8-05 | 12.9 notifications | Ouverture, décision, annulation, attribution d'accès, fin de conservation | Déclencher chaque cas ; faire échouer le SMTP ; désactiver l'e-mail d'un utilisateur | Uniquement ces trois familles ; ligne `notification` dans la transaction de l'événement ; 3 reprises ; pastille et liste in-app même e-mail désactivé | AUTO (SMTP simulé) | S / U (relais MMED) |
| CR-E8-06 | **D8** workflow par API | Clé API avec délégation | Désigner des validateurs, ouvrir un circuit, valider nominativement depuis l'application tierce ; même chose avec une clé non habilitée | Opérations possibles par API, décisions attribuées à la personne déléguée, double identité dans l'audit ; clé non habilitée → 403 | AUTO + SCRIPT | P |

## E9 — API d'intégration

Critère de sortie : une application de test dépose, recherche et télécharge par clé API, dans sa seule portée, et chaque appel est audité.
Script : `GED_RECETTE_CLE_API=… GED_IDEMPOTENCE=1 recette/fumee/fumee.sh` avec les chemins du contrat (voir `recette/fumee/README.md`).

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E9-01 | 5.1 | Dépôt par la clé du bureau d'ordre | Déposer | Horodatage, source/canal et déposant enregistrés | AUTO | P |
| CR-E9-02 | 5.2 | Clé portée sur l'espace A seulement | Lire, rechercher, déposer dans B | 404 / aucun résultat / 403 ; `acteur_application_id` dans l'audit | AUTO + SCRIPT | P |
| CR-E9-03 | 5.3 dépôt avec métadonnées | Multipart fichier + JSON | `POST /documents` | Une seule opération, métadonnées validées | AUTO | P |
| CR-E9-04 | 5.3 délégation, rattachement, droits | Points d'entrée du §5.3.1 | `POST/DELETE /documents/{id}/rattachements`, `GET /documents/{id}/droits?pourUtilisateur=` | Conformes au contrat ; consultation des droits d'un tiers soumise à la permission d'administration | AUTO | P |
| CR-E9-05 | 5.3.2 problem+json | Chaque erreur provoquée | Lire les en-têtes et le corps | `application/problem+json`, code métier stable, dictionnaire par champ pour les 400 | AUTO | P |
| CR-E9-06 | 5.3.2 codes | Scénarios dédiés | Provoquer 403, 404 hors périmètre, 409, 413, 415, 422, 429 | Code attendu à chaque fois | AUTO + SCRIPT (E5) | P |
| CR-E9-07 | 5.3.2 Idempotency-Key | Création sans clé, rejeu, clé réutilisée avec un autre corps | Appeler | Sans clé : refus ; rejeu : réponse initiale, aucun doublon ; autre corps : 422 ; mémoire 24 h | AUTO | P |
| CR-E9-08 | 5.3.2 quotas | Quota abaissé en test | 601e requête en une minute | 429 avec `Retry-After` ; quota journalier idem | AUTO | P |
| CR-E9-09 | 5.4 clés | Génération depuis l'écran | Créer, relire, régénérer, révoquer ; appeler depuis une IP non autorisée | Format `ged_<env>_<id>_<secret>`, secret affiché une fois, stocké en SHA-256 seulement (vérifié en base) ; expiration 12 mois ; chevauchement 7 jours ; IP hors liste → refus ; révocation immédiate | AUTO + SCRIPT | P |
| CR-E9-10 | 5.5 délégation — **D1, D2** | Clé avec et sans attribut « délégation » | `X-On-Behalf-Of` avec UID valide, inconnu, invalide ; clé sans attribut | Sans attribut : 403 ; inconnu ou invalide : 422 `IDENTITE_DELEGUEE_INVALIDE` ; écriture : droits de l'application, délégué = déposant ; lecture : intersection. **Point à trancher : le §5.5 exige de rejeter un compte désactivé, or D1 supprime la lecture de l'état AD** (voir §5) | AUTO | S |

## E10 — Exploitation et infrastructure

Critère de sortie : déploiement DEV, UAT et PROD par le même script, restauration testée dans le RTO, alertes reçues.

| Id | Réf. | Cas et données | Étapes | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|---|
| CR-E10-01 | 2.2 NGINX | Paquet Angular | Déployer derrière NGINX ; modifier `config.json` sans reconstruire | Front servi par NGINX ; configuration lue à l'exécution | REVUE + MANUEL | U |
| CR-E10-02 | 6.2.1 TLS | Instance UAT | `openssl s_client -tls1_1` / `-tls1_2` ; chaîne ; connexion base et LDAPS | TLS 1.0/1.1 refusés, 1.2+ acceptés ; certificat MMED et chaîne complète ; `sslmode=verify-full`, LDAPS vérifié | SCRIPT | U |
| CR-E10-03 | 6.2.2 durcissement | Instance UAT | `curl -I http://…`, `curl -I https://…` ; 20 connexions/s sur `/auth/login` | Redirection 301 vers HTTPS ; pas de version dans `Server` ; HSTS, X-Content-Type-Options, X-Frame-Options, CSP présents ; limitation de débit ; `client_max_body_size` 200 Mo (CR-E5-07) | SCRIPT | U (revue de la configuration sur poste) |
| CR-E10-04 | 6.5 sauvegarde | Base et fichiers UAT | Sauvegarde, puis **restauration à blanc** sur un serveur vierge | Base avant fichiers, clés séparées ; durée ≤ RTO 8 h ; RPO 15 min (WAL) ; contrôle d'intégrité par échantillon (`verifier-aucun-clair.sh` + empreintes) ; orphelins détectés ; compte rendu | MANUEL + SCRIPT | U |
| CR-E10-05 | 6.7 supervision — **D6** | Instance UAT | Interroger `/actuator/prometheus` depuis l'extérieur puis depuis le réseau interne ; couper PostgreSQL, clamd, LDAP | Métriques sur le port de management interne seulement ; sondes PostgreSQL, LDAP, fichiers, ClamAV, file OCR ; alertes : sonde > 2 min, 5xx > 2 % sur 5 min, disque > 80 %, **délai OCR > 24 h (D6)** — alerte reçue | SCRIPT + MANUEL | S (Prometheus absent) / U |
| CR-E10-06 | 10.1 procédure | Script de déploiement de dev2 | Déployer en DEV puis UAT ; injecter une fumée en échec | Même script partout : sauvegarde, `liquibase validate` puis `update` par `ged_owner`, déploiement, sondes, `recette/fumee/fumee.sh`, **retour arrière automatique** si la fumée échoue | SCRIPT | P (DEV local) / U |
| CR-E10-07 | 10.2 service système | Serveur Linux UAT | Revue de l'unité systemd, redémarrage forcé | Utilisateur non root, redémarrage automatique, secrets en 0400 hors dépôt, serveur distinct de la base | REVUE | U |

## E11 — Recette de conformité

Critère de sortie : matrice à 100 % « Identique », validée par MMED.

| Id | Réf. | Cas | Résultat attendu | Preuve | Où |
|---|---|---|---|---|---|
| CR-E11-01 | Toutes (115) | Rejouer la matrice ligne à ligne, avec pour chaque ligne le cas et la trace d'exécution | 115 « Identique » ; sept groupes du §12.1 présents (reprise de CR-E1-09) | REVUE | U |
| CR-E11-02 | Finitions | Chemins du contrat (§5.3.1), plafond 200 Mo (T04), Actuator interne, sondes supplémentaires | Conformes | SCRIPT | U |
| CR-E11-03 | 6.2.3 | Revue OWASP Top 10 et test d'intrusion (ZAP de base + scénarios manuels) | Aucune vulnérabilité critique ou haute ouverte | REVUE + rapport | U |
| CR-E11-04 | 9.2 | Campagne complète : `mvn test`, tests Angular, tous les scripts `recette/` | Tout vert, comparé à la ligne de base | AUTO + SCRIPT | U |
| CR-E11-05 | D1–D14 | Chaque décision actée vérifiée par son cas (tableau §3) | Toutes conformes ; questions Q1–Q5 tranchées ou reportées par écrit | REVUE | U |
| CR-E11-06 | 10.3 | Documentation d'installation, procès-verbal de mise en production | Documentation rejouée par un tiers sans aide | MANUEL | U |

## 3. Correspondance des décisions de la revue technique

| Décision | Cas concernés | Effet sur la recette |
|---|---|---|
| D1 — pas de relecture de l'état AD | CR-E2-08, CR-E2-09, CR-E9-10 | Vérifier l'**absence** de relecture `userAccountControl` et de la tâche des 5 min ; révocation manuelle des sessions (R1) |
| D2 — connexion par UID, jamais l'e-mail | CR-E2-02, CR-E2-03, CR-E9-10 ; fumée (`GED_CHAMP_IDENTIFIANT`) | Connexion par e-mail refusée |
| D3 — attributs AD minimum | CR-E2-10 | Liste exacte en attente de Q3 |
| D4 — un seul contrôleur aujourd'hui | CR-E2-02 | Fonctionne à un, bascule à deux |
| D5 — aucun Python | CR-E6-01, tout l'outillage `recette/` | Scripts en bash, outils en Java ; générateur Python retiré |
| D6 — délai OCR 24 h | Critère E6, CR-E6-08, CR-E10-05 | Objectif et alerte à 24 h ; délai réel mesuré |
| D7 — workflow parallèle | CR-E8-02 | Notification simultanée, aucun validateur optionnel |
| D8 — workflow par API | CR-E8-06 (nouveau) | Nouveau cas |
| D9 — nouvelle version archive l'ancienne | CR-E7-08 | Ancienne version archivée, lien explicite |
| D10 — archivage manuel, dossier entier, drapeau | CR-E7-01, CR-E7-05 | Aucun automatisme, drapeau sur dossiers, PDF obligatoire |
| D11 — journal non modifiable par l'interface | CR-E4-04 | Aucune route ni écran de modification/suppression |
| D12 — espace de partage simple | CR-E7-12 (nouveau) | Pas de co-édition |
| D13 — pas de validateur restreint à un type | CR-E7-07, CR-E8-01 | Ne pas implémenter (vérifier l'absence) |
| D14 — habilitations validées | CR-E3-03 | Confirme le cas |

## 4. Pré-recette E1 sur `ct/dev1` (non intégrée, 2026-09-26)

> Mise à jour : la recette réelle après intégration (`fbb951c`) confirme ces constats ; ils sont
> inscrits au registre (ANO-E1-001 à 003, ANO-E5-001). Résultats complets :
> `RESULTATS-VAGUE-1.md`.


Exécutée sur un export en lecture seule de `ct/dev1@f0b3314`, base jetable `ged_qa_recette_e1`.
Ce ne sont **pas** des anomalies au registre (lot non livré) ; remarques transmises pour
correction avant intégration.

| Contrôle | Constat |
|---|---|
| V01–V03, R01–R04 | OK : 20 changesets appliqués par `ged_owner` en 11 à 14 s ; retour arrière complet, schéma vidé, aller-retour au DDL identique |
| C01–C14, C17–C21, C30–C41, P01–P13 | OK : nommage, UUID, JSONB + GIN, rôles et 13 sondes de privilèges conformes |
| A01–A12, A20–A22, A24, A25 | OK |
| **C15 / C16** | Indicateur de suppression douce nommé `deleted` au lieu de `supprime` (§12.5) sur 8 tables |
| **C04** | Clé primaire composite sur `access_group_employe`, `access_group_workspace`, `document_etiquette`, `plan_index` (§4.2.2 : clé primaire `id`) |
| **C22 / A23** | Aucun changeset `data-initial` ; 9 classes `*Seeder` écrivent encore en base au démarrage (§4.2.1) |
| C13 (avert.) | 8 clés étrangères `supprime_par` sans index |
| C25 (avert.) | `unaccent` et `pg_trgm` non créées par `preparer-base.sql` (requis en E6) |

## 5. Points à trancher signalés par la recette

1. **D1 × §5.5** : la délégation d'identité doit rejeter un compte désactivé, mais D1 supprime la
   lecture de l'état AD. Sans source d'état, un compte désactivé reste délégable. À trancher par
   pm avec MMED (proposition : rejet si l'identité n'a aucune session valide récente, ou relecture
   ponctuelle de l'état à la seule délégation).
2. **E6, modèle `ara`** : `ara.traineddata` absent du poste et de `backend/tessdata` ; CR-E6-04 ne
   peut pas s'exécuter tant qu'il n'est pas fourni.
3. **E5, point d'entrée de vérification d'intégrité à la demande** : chemin à fixer par dev3
   (`GED_API_VERIF_INTEGRITE`).

## 6. Synthèse : ce qui est vérifiable sur ce poste

| Domaine | Réel sur le poste | Simulateur seulement | UAT seulement |
|---|---|---|---|
| Base, Liquibase, rôles (E1) | Tout | — | Rollback sur copie UAT, reprise MySQL |
| Identité (E2) | Jetons, sessions, anti-force brute | AD → UnboundID en LDAPS | AD réel de MMED |
| Droits, audit (E3, E4) | Tout | — | — |
| Fichiers (E5) | Chiffrement, empreintes, Tika, tailles, altération | ClamAV → faux clamd ; LibreOffice | ClamAV réel, 413 NGINX, prévisualisation bureautique |
| OCR, recherche (E6) | Français, recherche PostgreSQL | — | Arabe tant que `ara` manque ; protocole 300 pages |
| Cycle de vie (E7) | Tout sauf PDF/A | LibreOffice, veraPDF | Conversion et validation réelles |
| Workflow, notifications (E8) | Tout | SMTP simulé | Relais SMTP MMED |
| API (E9) | Tout | — | — |
| Exploitation (E10) | Script de déploiement en DEV, fumée | Prometheus | NGINX, TLS, systemd, sauvegarde/restauration, alertes |
