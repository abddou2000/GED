GED MARCHICA MED
Matrice de conformité — Dossier d'analyse technique et d'intégration V3
Objet : comparer, exigence par exigence, le dossier technique et d'intégration V3 (réf. IPTECH — AO 07/AO/MM/26, septembre 2026) avec la GED telle qu'elle est développée aujourd'hui (backend Spring Boot et frontend Angular).
Méthode : chaque constat a été vérifié dans le code source et la configuration de l'application, et non sur les maquettes. Trois statuts sont utilisés :

| Identique | La GED répond à l'exigence telle qu'elle est écrite. |
|---|---|
| Proche | La GED couvre une partie de l'exigence, ou la couvre autrement : un complément est nécessaire. |
| Non | La GED ne couvre pas l'exigence, ou fait l'inverse de ce qui est demandé. |


# 1. Synthèse chef de projet

| Identique | Proche | Non |
|---|---|---|
| 29 exigences — 25 % | 31 exigences — 27 % | 55 exigences — 48 % |

Total : 115 exigences analysées.

## Ce qui est solide
- La stack imposée est respectée pour trois composants sur quatre : Angular, Java 17 et Spring Boot, avec séparation stricte front et back par API REST versionnée.
- La qualité de code demandée par l'Article 50 : couches contrôleur, service, dépôt et DTO, code documenté, validation avant écriture, format d'erreur uniforme, sécurité fermée par défaut.
- La chaîne OCR suit le mécanisme retenu : PDFBox lit la couche texte, sinon rendu à 300 dpi et Tesseract 5, derrière une interface qui permet de changer de moteur.
- Les contrôles d'entrée : pagination plafonnée à 200, liste blanche de tri, taille maximale par type, double validation Angular et Bean Validation.
- L'exploitation : configuration externalisée, secrets hors dépôt, JAR exécutable, sonde de santé, plus de 120 tests automatisés.

## Les écarts bloquants
- Stack imposée. Le dossier impose PostgreSQL et Liquibase. La GED tourne sur MySQL avec Flyway. Aucune dérogation n'est demandée dans le dossier, donc la migration est obligatoire. Elle conditionne aussi la recherche plein texte, bâtie sur PostgreSQL.
- Authentification. Le dossier interdit tout référentiel local de mots de passe et exige LDAPS, jeton RS256 de 15 minutes et jeton de renouvellement. La GED a un compte local BCrypt et un jeton HMAC de 2 heures.
- Autorisation. Le point d'application unique des droits, les rôles, les habilitations héritées et la confidentialité n'existent pas.
- Sécurité des fichiers. Chiffrement AES-256-GCM, empreinte SHA-256, antivirus ClamAV et détection du type réel sont absents. Les fichiers sont stockés en clair.
- Journalisation. Ni pattern de log de l'Article 50, ni journal d'audit inaltérable et scellé.

## Écarts de conception déjà connus
- OCR synchrone. Le dossier retient une file de jobs PostgreSQL traitée en tâche de fond, sans plafond de pages. La GED lit le document pendant l'aperçu, limité à 5 pages.
- Cloisonnement de l'OCR. Le dossier interdit que l'OCR alimente un champ d'index. La GED pré-remplit les index.
- Workflow. Le dossier retient des décisions sans ordre, recalculées sur la version courante. La GED est séquentielle.

## Points non évaluables dans le code
- Dimensionnement et volumétrie : hypothèses d'infrastructure à valider avec MMED.
- Équipe projet minimale et propriété intellectuelle : engagements contractuels.
- Protocole de comparaison OCR sur 300 pages : dépend de l'échantillon de la Phase 7.

## Plan de mise en conformité proposé, par ordre de priorité
- Lot 1 — Socle : migration MySQL vers PostgreSQL, Flyway vers Liquibase, clés UUID, trois rôles de base de données.
- Lot 2 — Identité : LDAPS search-then-bind, provisionnement sans rôle, jeton RS256 de 15 minutes, renouvellement en cookie httpOnly, suppression des mots de passe locaux.
- Lot 3 — Autorisation : rôles, permissions, habilitations héritées, confidentialité, point d'application unique.
- Lot 4 — Traçabilité : pattern de log avec MDC, journal d'audit en INSERT seul, déclencheurs, scellement chaîné, écran d'export.
- Lot 5 — Fichiers : chiffrement AES-256-GCM avec keystore, empreinte SHA-256, Tika, ClamAV, prévisualisation, PDF/A et veraPDF.
- Lot 6 — OCR et recherche : file de jobs asynchrone, arabe, suppression du plafond, table du texte, tsvector et index GIN.
- Lot 7 — Intégration : clés API avec portée et quotas, délégation d'identité, Idempotency-Key, format problem+json, rattachements.
- Lot 8 — Exploitation : NGINX durci et TLS, environnement UAT, intégration continue avec OWASP Dependency-Check et SBOM, Prometheus.

# 2. Matrice détaillée

## Architecture et stack imposée (§2)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 2.1 | Séparation stricte front et back, communication uniquement par API | Identique | Deux applications distinctes : Angular et Spring Boot, reliées par l'API REST /api/v1. | — |
| 2.2 | Front-end Angular | Identique | Angular 22, TypeScript 6, Angular Material. | — |
| 2.2 | Back-end Java 17 et Spring Boot 3 (Security, Data JPA, Bean Validation) | Identique | Java 17 LTS, Spring Boot 3.4.1 avec les trois modules. | — |
| 2.2 | SGBD PostgreSQL 16 ou plus, configuration de recherche arabe vérifiée | Non | MySQL 8 en production, H2 en développement. Aucune dérogation n'est prévue dans le dossier. | Migrer vers PostgreSQL. |
| 2.2 | Outil de migration Liquibase 4 | Non | Flyway 10. | Passer à Liquibase. |
| 2.2 | Hébergement du front sur un serveur NGINX | Proche | Le guide de déploiement donne un exemple NGINX. Aucune configuration NGINX n'est livrée. | Livrer la configuration NGINX. |
| 2.3 | Une API REST unique pour le front et les applications tierces | Identique | Le front et les tiers consomment la même API. | — |
| 2.3.2 | Briques Tesseract 5 et Apache PDFBox | Identique | Intégrées et opérationnelles. | — |
| 2.3.2 | Briques Apache Tika, ClamAV, LibreOffice, veraPDF, keystore ou KMS, Prometheus | Non | Aucune de ces briques n'est intégrée. | À intégrer selon les lots. |

Sous-total : 5 identique(s), 1 proche(s), 3 non couverte(s).

## Authentification et identités (§3)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 3.2 | Aucun référentiel local de mots de passe | Non | Table des comptes avec mot de passe BCrypt. | Supprimer au profit de LDAP. |
| 3.3 | Authentification LDAPS search-then-bind avec compte de service, Spring Security LDAP | Non | Authentification locale par e-mail et mot de passe. | Brancher Spring Security LDAP. |
| 3.3 | Provisionnement automatique sans rôle, clé objectGUID | Non | Un seul compte amorcé au démarrage. | À traiter avec LDAP. |
| 3.3 | Jeton d'accès court, sans permissions embarquées | Proche | Jeton JWT signé qui ne porte que l'identité, mais valable 120 minutes au lieu de 15. | Réduire à 15 minutes. |
| 3.4.1 | JWT signé RS256 avec clé privée en coffre, conservé en mémoire côté Angular | Non | Signature HMAC-SHA256. Jeton stocké en sessionStorage, ou localStorage avec « se souvenir de moi ». | Passer en RS256, jeton en mémoire. |
| 3.4.1 | Jeton de renouvellement en cookie httpOnly, 8 h max, table de sessions, révocation | Non | Pas de renouvellement : l'utilisateur se reconnecte après expiration. | Implémenter le renouvellement. |
| 3.4.1 | Protection CSRF : jeton uniquement dans l'en-tête Authorization | Identique | API sans état, jeton en en-tête, aucun cookie de session. | — |
| 3.4.1 | Anti-force brute : 5 essais par minute par IP et identifiant, échecs journalisés | Proche | Blocage de 10 minutes après 8 échecs, par e-mail seulement, compteur en mémoire, échecs non audités. | Ajouter le critère IP et l'audit. |
| 3.4.2 | Cache annuaire de 15 minutes, désactivation AD prise en compte en 5 minutes | Non | Non géré. | À traiter avec LDAP. |

Sous-total : 1 identique(s), 2 proche(s), 6 non couverte(s).

## Données et migrations (§4.1, §4.2, §12.1)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.2.1 | Aucune modification de schéma hors migration versionnée | Proche | Principe respecté avec Flyway (V1 et V2), validation du schéma au démarrage. L'outil n'est pas celui imposé. | Reprendre en changelogs Liquibase. |
| 4.2.1 | Amorçage initial par migration, référentiels métier uniquement via l'interface | Proche | Les référentiels se gèrent dans l'interface. L'amorçage passe par du code Java au démarrage, pas par migration. | Déplacer l'amorçage en changesets. |
| 4.2.2 | Conventions de nommage des changesets et des objets (snake_case, idx_, uk_, fk_) | Proche | Tables et colonnes en snake_case. Fichiers nommés au format Flyway. | Appliquer la convention Liquibase. |
| 4.2.2 | Retour arrière explicite par changeset, schéma expand et contract | Non | Aucune migration de retour arrière. | Rédiger les rollbacks. |
| 4.2.3 | Trois rôles PostgreSQL : propriétaire, application, lecture seule | Non | Un seul utilisateur de base avec tous les droits. | Créer les trois rôles. |
| 12.1 | Clés primaires UUID | Non | Entiers auto-incrémentés. | Migrer vers UUID. |
| 12.1 | Modèle logique en sept groupes de tables | Proche | Organisation documentaire, typologie, versions et circuits présents. Identités, habilitations et traçabilité absents. | Compléter le modèle. |

Sous-total : 0 identique(s), 4 proche(s), 3 non couverte(s).

## Moteur OCR et recherche plein texte (§4.3, §4.4)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.3.1 | Tesseract 5, moteur LSTM, open source | Identique | Tesseract 5 appelé en binaire, mode LSTM. | — |
| 4.3.2 | Interface de moteur permettant de changer d'OCR sans impact | Identique | Interface d'extraction commune ; chaque moteur est un composant interchangeable. | — |
| 4.3.2 | Protocole comparatif sur 300 pages (CER, WER, débit) | Non | Pas encore réalisé. | À mener dès réception de l'échantillon. |
| 4.3.3 | Cloisonnement : l'OCR n'alimente aucun champ d'index | Non | Écart inverse : les index sont pré-remplis depuis le texte OCR. | Désactiver ou rendre optionnel. |
| 4.3.4 | Traitement asynchrone : table ocr_job, worker SKIP LOCKED, réponse HTTP 202 | Non | OCR synchrone, exécuté pendant l'aperçu d'indexation. | Créer la file de jobs et le worker. |
| 4.3.4 | Langues fra et ara, langue fixable par type de document | Proche | Français et anglais, réglage global. | Ajouter l'arabe, réglage par type. |
| 4.3.4 | Couche texte PDFBox d'abord, sinon rendu à 300 dpi et Tesseract | Identique | Mécanisme identique. | — |
| 4.3.4 | Aucun plafond de pages, texte multi-pages agrégé | Proche | Pages agrégées en un seul texte, mais plafond par défaut de 5 pages. | Supprimer le plafond. |
| 4.3.4 | Aucune copie en clair sur disque persistant (tmpfs) | Non | Les pages rendues sont écrites dans un dossier temporaire sur disque, puis purgées. | Traiter en mémoire ou tmpfs. |
| 4.3.4 | Délai de 60 s par page, 3 tentatives, statut OCR_ECHEC, document « non interrogeable » | Proche | Délai maximal de 120 s par appel. Pas de reprise ni de statut d'échec. | Ajouter reprises et statut. |
| 4.3.4 | Texte stocké dans document_texte, délai de disponibilité mesuré | Non | Le texte extrait n'est pas conservé. | Créer la table et la métrique. |
| 4.4 | Recherche PostgreSQL tsvector et index GIN, configurations french et arabic | Non | Pas de recherche plein texte. | Dépend de PostgreSQL. |
| 4.4 | websearch_to_tsquery, extraits ts_headline, tri ts_rank_cd, dédoublonnage | Non | Non disponible. | Dépend de PostgreSQL. |
| 4.4.1 | Réindexation incrémentale et complète | Non | Non disponible. | Tâche d'administration. |

Sous-total : 3 identique(s), 3 proche(s), 8 non couverte(s).

## Intégration et API (§5)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 5.1 | Point d'entrée de réception : enregistrer, horodater, identifier source et déposant | Proche | Dépôt, horodatage et déposant présents. La source n'est pas enregistrée. | Ajouter la source. |
| 5.2 | Applications tierces soumises au même modèle d'habilitation et à la même journalisation | Non | Pas d'identité applicative. | Dépend des clés API. |
| 5.3 | Opérations : création de dossier, téléchargement, versement | Identique | Création d'espace, téléchargement et ajout de version disponibles. | Aligner les chemins du contrat. |
| 5.3 | Opérations : dépôt avec métadonnées en une seule opération | Proche | Le dépôt et la saisie des index sont deux appels distincts. | Accepter les métadonnées au dépôt. |
| 5.3 | Opérations : dépôt pour le compte d'un utilisateur, rattachement, consultation des droits | Non | Non disponibles. | Nouveaux points d'entrée. |
| 5.3.1 | Recherche multicritère et plein texte filtrée par droits, paginée | Proche | Recherche multicritère sur index, non paginée, sans plein texte ni filtrage par droits. | Compléter. |
| 5.3.2 | JSON UTF-8, dates ISO 8601 en UTC, identifiants opaques UUID | Proche | JSON et dates ISO. Identifiants numériques. | Passer en UUID. |
| 5.3.2 | Erreurs au format problem+json (RFC 7807) avec code métier stable | Proche | Format d'erreur uniforme avec dictionnaire par champ, mais format maison sans code métier. | Adopter ProblemDetail. |
| 5.3.2 | Codes 403, 404 hors périmètre, 409, 413, 415, 422, 429 | Proche | 400, 401, 403, 404 et 409 utilisés. 413, 415, 422 et 429 absents. | Compléter les codes. |
| 5.3.2 | En-tête Idempotency-Key obligatoire sur les créations | Non | Non géré. | Ajouter la table d'idempotence. |
| 5.3.2 | Pagination page et taille, plafond 200, tri sur liste blanche | Identique | Plafond de 200 lignes et liste blanche des colonnes de tri. | — |
| 5.3.2 | Taille de fichier paramétrable par type, quotas de requêtes par clé | Proche | Taille par type gérée. Pas de quota de requêtes. | Ajouter les quotas. |
| 5.3.2 | Version majeure dans l'URL (/api/v1) | Identique | Toutes les routes sous /api/v1. | — |
| 5.3 | Spécification OpenAPI 3 complète | Identique | Générée par springdoc, fermée en production. | Compléter les exemples de payload. |
| 5.4 | Clés API : cycle de vie, secret haché SHA-256, X-API-Key, portée, quotas, expiration, IP | Non | Aucune clé API. | Nouveau module. |
| 5.5 | Délégation d'identité par X-On-Behalf-Of, double identité dans l'audit | Non | Non géré. | Dépend des clés API et de l'audit. |

Sous-total : 4 identique(s), 7 proche(s), 5 non couverte(s).

## Sécurité applicative et infrastructure (§6)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 6.1.1 | Fichiers stockés hors base | Identique | Stockage sur disque, la base ne porte que les métadonnées. | — |
| 6.1.1 | Identifiant opaque, arborescence aa/bb, écriture unique et atomique | Proche | Nom UUID par fichier, sous-dossier par espace, nouvelle version dans un nouveau fichier. Écriture directe sans fsync ni renommage. | Écriture atomique et arborescence aa/bb. |
| 6.1.2 | Chiffrement AES-256-GCM, clé par version, clé maîtresse en keystore, rotation | Non | Fichiers stockés en clair. | Service de chiffrement et KeyProvider. |
| 6.1.4 | Empreinte SHA-256 par version, vérification périodique | Non | Aucune empreinte. | Calculer au dépôt. |
| 6.1.4 | Copie de conservation PDF/A-2 validée par veraPDF | Non | Non géré. | LibreOffice et veraPDF. |
| 6.1.5 | Type réel détecté par le contenu (Apache Tika) | Non | Contrôle sur l'extension du nom de fichier. | Intégrer Tika. |
| 6.1.5 | Taille maximale par type, plafond de plateforme | Identique | Taille par type contrôlée à toutes les entrées, plafond de 100 Mo. | Aligner sur 200 Mo NGINX. |
| 6.1.5 | Antivirus ClamAV, refus si indisponible | Non | Aucune analyse antivirus. | Intégrer ClamAV. |
| 6.1.6 | Prévisualisation déchiffrée à la volée, droits appliqués, audit | Non | Téléchargement uniquement. | Visionneuse intégrée. |
| 6.2.1 | TLS 1.2 minimum, certificat MMED, LDAPS et base chiffrés | Non | Aucune configuration TLS livrée. | À poser au déploiement. |
| 6.2.2 | NGINX durci : server_tokens off, HSTS, CSP, limitation de débit, HTTPS forcé | Non | Pas de configuration NGINX livrée. | Livrer la configuration. |
| 6.2.3 | A01 : refus par défaut, 404 pour un objet hors périmètre | Proche | Toutes les routes exigent un appelant authentifié. Pas de contrôle par objet. | Dépend de l'autorisation. |
| 6.2.3 | A03 : requêtes paramétrées, liste blanche de tri, Bean Validation | Identique | JPA paramétré, tri sur liste blanche, validation des DTO. | — |
| 6.2.3 | A05 : Actuator restreint | Identique | Seule la sonde de santé est exposée, sans détail. | Restreindre au réseau interne. |
| 6.2.3 | A06 : OWASP Dependency-Check à chaque construction | Non | Non configuré. | Ajouter le plugin Maven. |
| 6.3 | Double validation, Angular et Spring Boot | Identique | Formulaires réactifs avec validateurs et Bean Validation indépendante côté serveur. | — |
| 6.4 | Contrôle d'accès côté back par un point d'application unique | Non | Pas de contrôle d'accès par objet. | Service d'autorisation central. |
| 6.5 | Sauvegarde base, fichiers et clés, RPO et RTO, restauration testée | Proche | Le guide de déploiement impose de sauvegarder base et fichiers ensemble. Pas de RPO, RTO ni test de restauration. | Rédiger et tester la procédure. |
| 6.7 | Sonde de santé Actuator | Identique | Sonde de santé exposée. | Ajouter les sondes LDAP, ClamAV, file OCR. |
| 6.7 | Métriques Micrometer et Prometheus, alertes | Non | Métriques non exposées. | Exposer Prometheus. |

Sous-total : 6 identique(s), 3 proche(s), 11 non couverte(s).

## Journalisation et audit (§7)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 7.1 | Pattern de log imposé avec username, ip, traceId, spanId (MDC) | Non | Format de log Spring par défaut, pas de MDC. | Filtre MDC et pattern. |
| 7.3.1 | Niveaux de log, rotation quotidienne et par taille, rétention 90 jours | Non | Pas de configuration de rotation. | Configurer Logback. |
| 7.4.1 | Table journal_audit : acteur, application, IP, action, objet, avant et après, trace_id | Non | Aucun journal d'audit métier. | Créer le journal. |
| 7.4.2 | INSERT seul, déclencheurs de refus, scellement SHA-256 chaîné et exporté | Non | Non géré. | À concevoir avec le journal. |
| 7.4.3 | Écran de consultation, export CSV et JSON, rétention 10 ans | Non | Non géré. | Écran d'administration. |

Sous-total : 0 identique(s), 0 proche(s), 5 non couverte(s).

## Qualité logicielle, tests et déploiement (§8 à §10)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 8.1 | Code documenté | Identique | Code et commentaires en français, décisions expliquées. | — |
| 8.1 | Séparation des couches : DAO, DTO, services, ressources, pages | Identique | Dépôts JPA, DTO, services, contrôleurs REST, composants Angular. | — |
| 8.2.1 | Validation complète avant toute écriture, transaction unique | Identique | Tout est vérifié avant l'écriture du fichier, avec compensation en cas d'échec. | — |
| 8.2.1 | Format d'erreur uniforme et chaîne de sécurité fermée par défaut | Identique | Gestionnaire d'erreurs commun, routes fermées sauf connexion et santé. | — |
| 8.3 | Registre des dépendances avec version et licence (SBOM) | Non | Non produit. | Générer un SBOM CycloneDX. |
| 9.1 | Tests fonctionnels avec Spring Test | Identique | Plus de 120 tests automatisés. | Démo par sprint. |
| 9.2 | Tests de non-régression | Proche | Suite exécutée à la construction, sans intégration continue. | Mettre en place la CI. |
| 9.3 | Environnement UAT et déploiement par module | Non | Profils dev et prod seulement. | Créer le profil UAT. |
| 9.4 | Dépôt Git, branche protégée, accès en lecture pour MMED | Proche | Dépôt Git existant. Protection de branche et accès MMED à établir. | Configurer la forge. |
| 10.1 | Configuration externalisée, même artefact promu sans recompilation | Identique | Profils Spring et variables d'environnement, config.json du front lu à l'exécution. | — |
| 10.1 | Secrets hors dépôt, injectés à l'exécution | Identique | Aucun secret par défaut ; clé de signature obligatoire en production. | — |
| 10.1 | Procédure scriptée : CI, sauvegarde, migration, test de fumée, retour arrière | Proche | Procédure manuelle documentée avec vérification de santé. Ni script ni CI. | Scripter le déploiement. |
| 10.2 | JAR exécutable en service système derrière NGINX, serveur Linux | Proche | JAR exécutable prêt. Service système et NGINX à mettre en place. | Unités systemd et NGINX. |
| 10.3 | Code source complet et documentation d'installation | Identique | Code complet et guide de mise en production. | Procès-verbal à la mise en production. |

Sous-total : 8 identique(s), 4 proche(s), 2 non couverte(s).

## Mécanismes techniques des règles fonctionnelles (§12)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 12.2 | Permissions, rôles, habilitations sur nœud ou document, héritage et rupture | Non | Colonnes de droits inertes, aucune résolution. | Moteur d'autorisation. |
| 12.3 | Confidentialité PUBLIC, PRIVE, CONFIDENTIEL et personnes désignées | Non | Non géré. | Colonne et prédicat SQL. |
| 12.4 | Rattachement d'un document à plusieurs espaces | Non | Un document, un espace. | Table de rattachement. |
| 12.5 | Déplacement transactionnel avec sous-arborescence et anti-cycle | Proche | Déplacement d'espace avec contrôle anti-cycle. Pas de déplacement de document ni d'audit. | Compléter. |
| 12.5 | Suppression douce avec auteur et date | Proche | Indicateur de suppression, sans auteur ni date. | Ajouter supprime_par et supprime_le. |
| 12.5 | Purge définitive avec destruction cryptographique | Non | Pas de purge. | Dépend du chiffrement. |
| 12.6 | Archivage : statut du document, lecture seule, empreinte, PDF/A, job par lot | Non | Statut archivé sur les espaces seulement. | Statut de conservation du document. |
| 12.7 | Méta-modèle d'index : nature dont booléen, obligatoire, défaut, liste, recherche | Proche | Tout est géré sauf le booléen. | Ajouter le booléen. |
| 12.7 | Plan d'indexation et charte de nommage automatique | Identique | Identique. | — |
| 12.7 | Métadonnées en JSONB avec index GIN | Non | Table de valeurs d'index séparée. | Dépend de PostgreSQL. |
| 12.7 | Type : durée de conservation, confidentialité par défaut, plan versionné, re-typologisation | Non | Le type porte le plan, les formats et la taille. Le reste est absent. | Compléter le type. |
| 12.8 | Versions : numéro, empreinte, auteur, une seule version courante | Proche | Version principale unique et date. Ni numéro, ni empreinte, ni auteur. | Compléter la table. |
| 12.8 | Verrou avec auteur, date et motif | Proche | Verrou booléen sans auteur, date ni motif. | Enrichir le verrou. |
| 12.8 | Règle de workflow rattachable à un espace, un dossier ou un type ; validateur nommé ou par rôle | Proche | Rattachement à l'espace, validateur nommé. | Compléter. |
| 12.8 | Circuit figé au dépôt | Identique | Signatures copiées au dépôt, la règle ne rejoue jamais. | — |
| 12.8 | Décisions VALIDE, REFUSE, ANNULEE sans ordre, statut recalculé sur la version courante | Non | Circuit séquentiel ; un rejet rouvre l'étape précédente. | Refondre le calcul du statut. |
| 12.8 | Annulation de circuit et diffusion | Non | Non géré. | Nouvelles fonctions. |
| 12.9 | Échéance de conservation et tâche planifiée d'alerte | Non | Date d'expiration par document, sans tâche d'alerte. | Tâche quotidienne. |
| 12.9 | Notifications : boîte d'envoi, e-mail SMTP et pastille in-app | Non | Aucune notification. | Table notification et envoi asynchrone. |
| 12.10 | Export de dossier en ZIP en flux avec manifeste CSV | Non | Non disponible. | ZipOutputStream et manifeste. |
| 12.11 | Dépôt en deux temps : fichier reçu, puis indexation INDEXE, SANS_PLAN ou A_INDEXER | Proche | Le fichier est déposé puis indexé par un second appel. Pas de statut d'indexation. | Ajouter le statut et la reprise. |

Sous-total : 2 identique(s), 7 proche(s), 12 non couverte(s).