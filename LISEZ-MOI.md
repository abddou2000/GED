# GED Marchica Med

Backend **Spring Boot 3.4.1 / Java 17** · Base **PostgreSQL 16** (Liquibase 4) · Frontend **Angular 22**.

## Ce que vous obtenez en clonant

Le code source seul. `node_modules`, `frontend/dist`, `frontend/.angular` et
`backend/target` sont ignorés : ce sont des dépendances téléchargées et des artefacts
de compilation, reconstruits par les commandes ci-dessous.

**Aucune base n'est versionnée.** Le schéma est créé par Liquibase au premier
démarrage, dans une base PostgreSQL préparée au préalable (voir « Démarrer »). En
dev, les seeders reconstruisent un jeu de démonstration, et un annuaire de
démonstration embarqué fournit les comptes de connexion (voir « Configuration
sensible »).

## Démarrer

Prérequis : JDK 17, Node et **PostgreSQL 16 ou plus**. Développé avec
`C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`, Maven 3.9.9 et PostgreSQL 16.

**Base de données, une fois par poste** (superutilisateur PostgreSQL) :

```bash
cd backend/scripts/db
psql -U postgres -d postgres -f creer-roles.sql                       # rôles ged_owner, ged_app, ged_readonly
psql -U postgres -d postgres -v base=ged_dev1 -f preparer-base.sql    # base de dev
psql -U postgres -d postgres -v base=ged_dev1_test -v tests=oui -f preparer-base.sql   # base des tests
```

Sur un poste en authentification `trust`, aucun mot de passe n'est nécessaire.
Sinon, voir `DB_PASSWORD` et `DB_OWNER_PASSWORD` dans `backend/.env.example`.

**Backend** — port 8080 :

```bash
cd backend
mvn spring-boot:run
```

En dev, aucune variable d'environnement n'est nécessaire : l'annuaire de
démonstration démarre sur le port 33389 et une paire de clés RSA de signature
des jetons est tirée au démarrage (un `WARN` le rappelle). Conséquence voulue :
**les sessions ne survivent pas à un redémarrage**, il faut se reconnecter.

## Configuration sensible

Aucun secret n'a de valeur par défaut dans un fichier versionné. Les variables
attendues sont décrites dans `backend/.env.example` :

| Variable | Rôle | Absente en dev | Absente en uat/prod |
|---|---|---|---|
| `GED_LDAP_URLS` `GED_LDAP_BASE` | Contrôleurs de domaine (LDAPS) et base de recherche | annuaire de démonstration embarqué `ldap://localhost:33389` | **refus de démarrer** (`ldap://` aussi) |
| `GED_LDAP_COMPTE_SERVICE` `GED_LDAP_MOT_DE_PASSE(_FICHIER)` | Compte de service en lecture seule | compte du simulateur | connexions impossibles |
| `GED_LDAP_TRUSTSTORE(_MOT_DE_PASSE)` | Chaîne de certificats des contrôleurs | — | magasin de la JVM |
| `GED_JWT_KEYSTORE` `GED_JWT_KEYSTORE_MOT_DE_PASSE` `GED_JWT_ALIAS` | Clé privée RSA de signature des jetons (RS256) | paire RSA tirée au démarrage, `WARN` | **refus de démarrer** |
| `GED_SESSION_DUREE_ABSOLUE` | Durée absolue d'une session | `8h` | `8h` (4 h recommandées, risque R26) |
| `GED_ADMINISTRATEURS` | sAMAccountName recevant le rôle Administrateur à leur 1re connexion | `sbennani` | aucun : personne ne peut attribuer de rôle |
| `GED_ORIGINES` | Origines CORS autorisées | repli `localhost` | repli `localhost` + `WARN` |
| `DB_HOST` `DB_PORT` `DB_NAME` | Serveur et base PostgreSQL | `localhost:5432/ged_dev1` | `localhost:5432/ged` |
| `DB_SSLMODE` | Chiffrement de la connexion à la base | `prefer` | `verify-full` |
| `DB_USER` / `DB_PASSWORD` | Compte applicatif `ged_app` (DML seulement) | `ged_app`, sans mot de passe | `ged_app`, **mot de passe obligatoire** |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | Compte `ged_owner`, utilisé par Liquibase seul | `ged_owner`, sans mot de passe | script de déploiement (Liquibase désactivé au démarrage) |

**Se connecter en dev** : aucun mot de passe n'est stocké dans la GED ; la
connexion passe par l'annuaire. Le profil `dev` démarre un **annuaire de
démonstration** (simulateur UnboundID, `backend/src/main/resources/annuaire/annuaire-dev.ldif`),
mot de passe `dev-local-only` pour tous :

| Identifiant | Personne | Situation |
|---|---|---|
| `sbennani` | Sara Bennani | Administrateur (amorçage, portée globale) |
| `kelfassi`, `yalaoui` | Karim El Fassi, Yasmine Alaoui | provisionnés sans rôle |
| `nidrissi` | Nadia Idrissi | sans fiche employé : fiche créée, sans rôle |
| `otazi` | Omar Tazi | désactivé dans l'annuaire : connexion refusée |

Cet annuaire n'existe **que** dans les profils `dev` et `test` et refuse de
démarrer avec `prod` ou `uat`.

**Frontend** — port 4301 :

```bash
cd frontend
npm install
npm start -- --port 4301
```

## Le schéma de base de données

**PostgreSQL 16 ou plus dans tous les profils** (dev, test, prod) : il n'y a plus
de base H2 ni MySQL. Le schéma est créé et maintenu **uniquement par Liquibase**
(dossier technique §4.2), depuis `backend/src/main/resources/db/changelog/` :

| Fichier | Contenu |
|---|---|
| `db.changelog-master.xml` | changelog maître : prérequis (PostgreSQL ≥ 16, configuration de recherche `arabic`) et liste ordonnée des changesets |
| `changesets/AAAAMMJJHHmm_objet_metier.xml` | un fichier par évolution, avec son `rollback` explicite |

Liquibase s'exécute au démarrage avec le compte propriétaire `ged_owner` ;
l'application tourne ensuite avec `ged_app`, qui n'a **aucun droit DDL**. Hibernate
ne fait que vérifier (`ddl-auto: validate`, identique dans tous les profils) : une
entité qui ne correspond pas au schéma empêche le démarrage.

Conventions : tables et colonnes en `snake_case`, clé primaire `id` en **UUID v7**
(ordonné dans le temps, opaque pour les appelants), clé étrangère `<table>_id`,
index `idx_<table>_<colonnes>`, contraintes `pk_`, `uk_`, `fk_`, `ck_`. Les
suppressions sont douces : `deleted`, plus l'auteur (`supprime_par`) et la date
(`supprime_le`). `document.metadonnees` est un JSONB indexé GIN (alimenté au lot E7).

**Faire évoluer le modèle** : on n'édite jamais un changeset déjà appliqué —
Liquibase en compare l'empreinte et refuserait de démarrer. On ajoute un fichier
`changesets/AAAAMMJJHHmm_objet.xml` avec sa clause `<rollback>`, et on l'inclut à la
fin de `db.changelog-master.xml`. Les données de référence techniques passent par
des changesets `labels="data-initial"` ; les référentiels métier (types, index,
plans, espaces, workflows) se créent uniquement depuis l'interface — les jeux de
démonstration sont limités au profil `dev`.

Reprise d'une ancienne base MySQL (identifiants numériques) : `backend/scripts/reprise/`,
procédure dans `DEPLOIEMENT.md`.

## Vérifier

```bash
cd backend && mvn test
```

La suite tourne sur PostgreSQL réel, base `ged_dev1_test` (ou `<DB_NAME>_test`) :
le schéma y est **vidé puis recréé par Liquibase** à chaque exécution, et
l'application s'y connecte avec `ged_app`. Elle comprend la montée du changelog
sur un schéma vierge, le retour arrière de chaque changeset, le contrôle des
droits de `ged_app` et la reprise des données sur un export d'essai.

561 tests, tous verts (annuaire simulé par UnboundID). Le simulateur des tests
écoute sur le port 33390 par défaut : deux copies de travail qui lancent leurs
tests en même temps en choisissent deux différents par
`GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT` et `GED_IDENTITE_ANNUAIRE_URLS`.

## Points d'entrée

| Quoi | Où |
|---|---|
| Indexation automatique (analyse puis confirmation) | `backend/src/main/java/com/ipt/ged/indexation/` |
| Chaîne d'OCRisation (port + adaptateurs) | `backend/src/main/java/com/ipt/ged/ocr/` |
| Écran correspondant | `frontend/src/app/features/indexation/` |
| Charte de nommage (séparateur, ordre, casse) | `backend/.../planindexation/PlanIndexation.java` |
| Jetons de design et style des dialogues | `frontend/src/styles.scss` |
| Scénario de test pas à pas | `SCENARIO-TEST-INDEXATION.md` |

## L'OCRisation

L'architecture ne s'engage sur aucun moteur : `ExtracteurTexte` est un port, et les
extracteurs sont essayés par ordre de priorité.

| Étage | Moteur | État |
|---|---|---|
| 1 | PDFBox — couche texte des PDF natifs | actif, aucune installation requise |
| 2 | Tesseract — scans et images | **à valider** : le binaire doit être installé sur le serveur |

`GET /api/v1/ocr/diagnostic` indique quels extracteurs répondent.
L'étage 2 a été écrit et compilé mais n'a **pas pu être exécuté** sur le poste de
développement, Tesseract n'y étant pas installé.

Réglages dans `application.yml`, section `ged.ocr` : `enabled`, `commande`, `langue`,
`dpi`, `pages-max`.

## Sécurité — état

**Authentification par l'annuaire** (dossier technique §3.3) : recherche du
`sAMAccountName` par un compte de service en lecture seule, puis liaison avec le
DN trouvé (Spring Security LDAP). La GED ne stocke **aucun mot de passe**. Une
identité inconnue est provisionnée à la volée, clé `objectGUID`, **sans rôle**.
Code : `backend/src/main/java/com/ipt/ged/identite/`.

**Sessions** (§3.4.1) : jeton d'accès JWT **RS256** de 15 minutes (identité
seule, jamais de permission), gardé en mémoire par Angular ; jeton de
renouvellement opaque en cookie `HttpOnly; Secure; SameSite=Strict`, rotation à
chaque usage, réutilisation = révocation de toute la session, inactivité 30 min,
durée absolue 8 h ; déconnexion et révocation par l'Administrateur immédiates.
Connexion limitée à 5 tentatives par minute par IP et par identifiant (429).

`config/SecurityConfig.java` : tout est fermé par défaut ; une identité sans rôle
n'accède qu'à `/api/v1/auth/me` (page d'accueil vide) ; `/api/v1/admin/**` exige
le rôle Administrateur. Les rôles sont relus en base à chaque requête.

**Autorisation** (lot E3, §12.2 à §12.4) : rôles composés de permissions
(9 élémentaires, 7 d'administration, `VOIR_PRIVE`, `VOIR_CONFIDENTIEL`),
habilitations d'un utilisateur ou d'un groupe GED sur la portée globale, un nœud
(hérité en dessous, rupture d'héritage possible) ou un document. Point
d'application unique : `autorisation/AccessPredicate` (nœuds accessibles,
décision `peut`, filtres « à la source » en JPA et en SQL, cache par sujet
invalidé par le compteur `version_habilitations`), façade `ControleAcces`
(404 hors périmètre, 403 permission manquante). Confidentialité PUBLIC / PRIVE /
CONFIDENTIEL et personnes désignées ; rattachement d'un document à plusieurs
espaces (droits en union). Code : `backend/src/main/java/com/ipt/ged/autorisation/`.
