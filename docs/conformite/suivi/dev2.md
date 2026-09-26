# Suivi — dev2 (qualité, exploitation, traçabilité, API)

Branche `ct/dev2`. Mise à jour : 26/09/2026.

## Lot en cours — vague 1

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
