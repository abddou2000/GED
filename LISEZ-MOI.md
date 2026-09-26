# GED Marchica Med

Backend **Spring Boot 3.4.1 / Java 17** · Base **PostgreSQL 16** (Liquibase 4) · Frontend **Angular 22**.

## Ce que vous obtenez en clonant

Le code source seul. `node_modules`, `frontend/dist`, `frontend/.angular` et
`backend/target` sont ignorés : ce sont des dépendances téléchargées et des artefacts
de compilation, reconstruits par les commandes ci-dessous.

**Aucune base n'est versionnée.** Le schéma est créé par Liquibase au premier
démarrage, dans une base PostgreSQL préparée au préalable (voir « Démarrer »). En
dev, les seeders reconstruisent un jeu de démonstration — à condition que
`GED_MDP_INITIAL` soit défini, faute de quoi aucun compte n'est créé et
**personne ne peut se connecter** (voir la table des variables plus bas).

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

En dev, aucune variable d'environnement n'est nécessaire : la clé de signature
JWT est tirée au sort au démarrage (un `WARN` le rappelle). Conséquence voulue :
**les jetons ne survivent pas à un redémarrage**, il faut se reconnecter.

## Configuration sensible

Aucun secret n'a de valeur par défaut dans un fichier versionné. Les variables
attendues sont décrites dans `backend/.env.example` :

| Variable | Rôle | Absente en dev | Absente en prod |
|---|---|---|---|
| `GED_JWT_CLE` | Clé HMAC-SHA256 de signature des jetons (32 octets min.) | clé aléatoire tirée au démarrage, `WARN` | **refus de démarrer** |
| `GED_MDP_INITIAL` | Mot de passe d'amorçage des comptes au 1er démarrage | valeur locale `dev-local-only` | aucun compte créé (`WARN`) |
| `GED_ORIGINES` | Origines CORS autorisées | repli `localhost` | repli `localhost` + `WARN` |
| `GED_EMAIL_ADMIN` | Adresse du compte administrateur unique | `sara.bennani@marchica.ma` (profil dev) | repli `admin@<domaine>` |
| `GED_NOM_ADMIN` | Nom affiché de l'employé créé pour porter ce compte | `Administrateur GED` | `Administrateur GED` |
| `DB_HOST` `DB_PORT` `DB_NAME` | Serveur et base PostgreSQL | `localhost:5432/ged_dev1` | `localhost:5432/ged` |
| `DB_USER` / `DB_PASSWORD` | Compte applicatif `ged_app` (DML seulement) | `ged_app`, sans mot de passe | `ged_app`, **mot de passe obligatoire** |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | Compte `ged_owner`, utilisé par Liquibase seul | `ged_owner`, sans mot de passe | `ged_owner`, **mot de passe obligatoire** |

**Se connecter la première fois, en dev** : `sara.bennani@marchica.ma` / `dev-local-only`
(valeurs du profil `dev`, dans `application-dev.yml`). En production, ces deux valeurs
viennent de `GED_EMAIL_ADMIN` et `GED_MDP_INITIAL` — sans elles, l'écran de connexion
refuse tout le monde sans expliquer pourquoi : seul le journal du serveur le dit.

Générer une clé correcte :

```bash
openssl rand -base64 48
```

PowerShell, sans openssl :

```powershell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
```

Lancer en production :

```bash
export GED_JWT_CLE="$(openssl rand -base64 48)"
export GED_ORIGINES="https://ged.example.ma"
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

> **Aucune clé par défaut.** `GED_JWT_CLE` n'a de valeur dans aucun fichier
> versionné, et l'historique git n'en a jamais contenu — vérifié sur l'intégralité
> des objets du dépôt. Une clé de signature écrite dans un fichier suivi devient
> publique dès que le dépôt circule, et le reste dans l'historique : c'est la raison
> d'être de `.env.example`. En développement, faute de clé, une valeur aléatoire est
> tirée à chaque démarrage — les jetons ne survivent donc pas à un redémarrage.


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

156 tests, tous verts.

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

`backend/src/main/java/com/ipt/ged/config/SecurityConfig.java` est en
`anyRequest().authenticated()` : tout est fermé par défaut, seules la connexion,
`/error`, la sonde de santé et Swagger restent ouverts. L'authentification se
fait par jeton JWT (`security/ServiceJeton`, `security/FiltreJwt`), les rôles
étant relus en base à chaque requête.

Vérifications appliquées à chaque jeton reçu : signature, expiration, émetteur
(`ged.securite.jwt.emetteur`), et **borne haute sur la durée de vie** — un jeton
dont `exp - iat` dépasse `validite-minutes` (plus la tolérance d'horloge) est
refusé même s'il est correctement signé. Cela ferme la fabrication de jetons à
très longue durée de vie.
