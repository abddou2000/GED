# Suivi — dev2 (qualité, exploitation, traçabilité, API)

Branche `ct/dev2`. Mise à jour : 27/09/2026.

## Vague 2 — en cours

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
