# GED Marchica Med

Backend **Spring Boot 3.4.1 / Java 17** · Frontend **Angular 22**.

## Ce que vous obtenez en clonant

Le code source seul. `node_modules`, `frontend/dist`, `frontend/.angular` et
`backend/target` sont ignorés : ce sont des dépendances téléchargées et des artefacts
de compilation, reconstruits par les commandes ci-dessous.

**La base H2 de développement n'est PAS versionnée** (`backend/data` est ignoré, et
c'est voulu : elle contient des données et des empreintes de mots de passe). Au premier
démarrage, les seeders reconstruisent un jeu complet — à condition que
`GED_MDP_INITIAL` soit défini, faute de quoi aucun compte n'est créé et **personne ne
peut se connecter** (voir la table des variables plus bas).

## Démarrer

Prérequis : JDK 17 et Node. Développé avec
`C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot` et Maven 3.9.9.

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

Il est créé et maintenu par **Flyway**, depuis `backend/src/main/resources/db/migration/` :

| Fichier | Contenu |
|---|---|
| `V1__schema_initial.sql` | les 18 tables, clés étrangères et contraintes d'unicité |
| `V2__index_de_performance.sql` | 17 index sur les colonnes réellement filtrées ou triées |

En **production**, Flyway applique ces migrations au démarrage, puis Hibernate
vérifie le résultat (`ddl-auto: validate`). Une base vierge se peuple donc toute
seule — vérifié sur MySQL 8.4 : 19 tables, 17 index, connexion fonctionnelle.

En **développement et en test**, Flyway est désactivé : le schéma vient de
`ddl-auto: update` sous H2, et les migrations sont écrites en dialecte MySQL.

`baseline-on-migrate` est actif : une base existante, créée jadis par
`ddl-auto: update`, accepte la première migration sans être recréée.

**Faire évoluer le modèle** : on n'édite jamais une migration déjà appliquée —
Flyway compare une empreinte et refuserait de démarrer. On ajoute `V3__…​.sql`.

## Vérifier

```bash
cd backend && mvn test
```

122 tests, tous verts.

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
