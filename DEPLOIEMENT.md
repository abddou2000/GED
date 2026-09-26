# Mise en production — GED Marchica Med

Procédure vérifiée sur PostgreSQL 16.14 : base vierge préparée par les scripts
ci-dessous, schéma créé par Liquibase (18 tables), application démarrée avec le
compte `ged_app`, connexion fonctionnelle. Reprise d'une ancienne base vérifiée
sur un export d'essai (section 7).

---

## 1. Prérequis

| | Version |
|---|---|
| Java | 17 |
| PostgreSQL | **16 ou plus**, configuration de recherche `arabic` présente (livrée en standard) |
| Node (pour compiler le frontend) | 20+ |
| Liquibase CLI (facultatif, retour arrière manuel) | 4.29 |

L'application vérifie elle-même, avant toute migration, la version du serveur
et la présence de la configuration `arabic` : un serveur non conforme arrête
le démarrage (prérequis du changelog maître).

### 1.1 Rôles PostgreSQL (une fois par serveur)

Trois rôles distincts, **aucun superutilisateur** (dossier technique §4.2.3) :

| Rôle | Usage | Droits |
|---|---|---|
| `ged_owner` | Liquibase, au démarrage uniquement | propriétaire des schémas `ged` et `ged_liquibase` (DDL) |
| `ged_app` | l'application en fonctionnement | `SELECT, INSERT, UPDATE, DELETE` sur les tables de `ged` ; aucun DDL, aucun accès au registre des migrations |
| `ged_readonly` | diagnostic, supervision | `SELECT` sur `ged` et `ged_liquibase` |

```bash
psql -U postgres -d postgres \
     -v mdp_owner='<secret>' -v mdp_app='<secret>' -v mdp_readonly='<secret>' \
     -f backend/scripts/db/creer-roles.sql
```

Le script est **idempotent** : il crée les rôles absents et ne touche jamais un
rôle existant (ni `DROP ROLE`, ni changement de mot de passe — une rotation se
fait explicitement par `ALTER ROLE ... PASSWORD`). Les rôles étant globaux au
serveur, toutes les bases d'un même serveur les partagent. Les mots de passe
viennent du coffre de MMED ; sans eux, les rôles sont créés sans mot de passe,
ce qui ne convient qu'à un poste de développement en authentification `trust`.

### 1.2 Base, schémas et droits (une fois par base)

```bash
psql -U postgres -d postgres -v base=ged -f backend/scripts/db/preparer-base.sql
```

Crée la base (UTF-8) si elle manque, les schémas `ged` (tables) et
`ged_liquibase` (registre des migrations, hors de portée de `ged_app`), retire
l'accès à `PUBLIC`, et pose les **privilèges par défaut** : chaque table créée
plus tard par Liquibase est aussitôt utilisable par `ged_app` en DML et lisible
par `ged_readonly`, sans `GRANT` à écrire dans les migrations. Rejouable sans
effet de bord. Variables facultatives : `-v tests=oui` (base de tests : droit de
créer des schémas jetables) et `-v reprise=oui` (le temps d'une reprise, §7) ;
rejouer le script sans elles retire ce droit.

---

## 2. Variables d'environnement

| Variable | Obligatoire | Rôle |
|---|---|---|
| `GED_JWT_CLE` | **oui** | Clé de signature, 32 octets minimum. **Absente en prod = refus de démarrer**, volontairement. |
| `GED_MDP_INITIAL` | **oui au 1er démarrage** | Mot de passe du compte administrateur amorcé. Vide = **aucun compte créé**, et personne ne peut se connecter. |
| `GED_EMAIL_ADMIN` | recommandé | Adresse du compte unique. Défaut : `admin@marchica.ma`. |
| `GED_NOM_ADMIN` | non | Nom affiché. Défaut : `Administrateur GED`. |
| `GED_ORIGINES` | **oui** | Origines CORS du frontend. Sans elle, repli sur `localhost` — le frontend déployé sera refusé. |
| `DB_HOST` `DB_PORT` `DB_NAME` | oui | Serveur et base PostgreSQL (défauts `localhost`, `5432`, `ged`). |
| `DB_USER` `DB_PASSWORD` | **oui** | Compte applicatif `ged_app` (défaut `ged_app`). |
| `DB_OWNER_USER` `DB_OWNER_PASSWORD` | **oui** | Compte `ged_owner`, utilisé par Liquibase seul au démarrage (défaut `ged_owner`). |
| `DB_SCHEMA` `DB_SCHEMA_LIQUIBASE` | non | Schémas, défauts `ged` et `ged_liquibase`. |

Générer la clé :

```bash
openssl rand -base64 48
```

> **Ne réutilisez pas** `Marchica@2026` : ce mot de passe est écrit dans la
> documentation de l'archive de démonstration.

---

## 3. Backend

```bash
cd backend
mvn -DskipTests package
java -jar target/ged-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Au premier démarrage, Liquibase (compte `ged_owner`) applique le changelog ;
chercher dans le journal :

```
Creating database history table with name: ged_liquibase.databasechangelog
Running Changeset: db/changelog/changesets/202609261000_creation_table_employe.xml::202609261000-1::ged
...
Successfully released change log lock
Started GedApplication
```

Aux démarrages suivants, seuls les changesets nouveaux s'appliquent. Hibernate
vérifie ensuite le schéma (`ddl-auto: validate`) et l'application ouvre son
pool de connexions avec `ged_app`.

Puis vérifier :

```bash
curl -s http://<hote>:8080/actuator/health      # {"status":"UP"}
curl -s -o /dev/null -w "%{http_code}\n" http://<hote>:8080/v3/api-docs   # 401 attendu
```

Le **401 sur `/v3/api-docs` est le bon résultat** : la documentation de l'API
est fermée en production.

---

## 4. Frontend

```bash
cd frontend
npm ci
npm run build
```

Le résultat va dans `dist/frontend/browser/`. Deux points à ne pas manquer :

**a. `assets/config.json` doit exister et contenir :**

```json
{ "demo": false }
```

> **Piège connu, et il est sérieux.** Si ce fichier est absent, illisible, ou
> si l'hébergeur répond une page HTML à sa place (404 déguisée), l'application
> bascule **silencieusement en mode démonstration** : elle n'appelle plus le
> serveur du tout et affiche un jeu d'essai. Personne n'est averti. Après le
> déploiement, ouvrez l'application et vérifiez qu'un document réel s'affiche —
> pas un document nommé « Facture Atlas janvier ».

**b. `/api` doit pointer vers le backend.** Le frontend appelle des chemins
relatifs. Exemple Nginx :

```nginx
location /api/ { proxy_pass http://127.0.0.1:8080; }
```

Le routage se fait par ancre (`/#/accueil`), donc aucune réécriture d'URL
n'est nécessaire pour les routes de l'application.

---

## 5. Première connexion

Adresse = `GED_EMAIL_ADMIN`, mot de passe = `GED_MDP_INITIAL`.

Si la connexion échoue avec « E-mail ou mot de passe incorrect », regardez le
**journal du serveur** : quand aucun compte n'a été créé, l'écran affiche ce
message-là — il ne sait pas distinguer les deux cas.

---

## 6. Ce qu'il faut surveiller

**Sauvegarde.** Deux choses à sauvegarder, et les deux ensemble :

- la base PostgreSQL (`pg_dump -Fc`, schémas `ged` et `ged_liquibase`),
- le dossier de stockage (`ged.storage.root`), qui contient les fichiers.

L'un sans l'autre ne permet pas de restaurer : la base porte les métadonnées,
le disque porte les documents.

**Le stockage ne fait que croître.** Aucun fichier n'est jamais effacé, même
après mise à la corbeille — c'est un choix, pour que la restauration ne mente
pas. Prévoyez la place, et une purge décidée manuellement.

**Redémarrage = reconnexion.** Les jetons ne survivent pas à un redémarrage si
`GED_JWT_CLE` change. Gardez la même clé d'un déploiement à l'autre.

---

## 7. Reprise d'une base existante (MySQL, identifiants numériques)

Les scripts sont dans `backend/scripts/reprise/` ; l'ancien schéma de référence
est `reference/ancien-schema-mysql.sql`. Principe : export de l'ancienne base au
format texte de `COPY`, chargement dans un schéma de transit `reprise_source`,
transfert vers le schéma Liquibase en **une seule transaction**, contrôles.

| Fichier | Rôle |
|---|---|
| `exporter-mysql.sh` | export de chaque table de l'ancienne base (client `mysql`, SELECT seul) |
| `01_schema_source.sql` | schéma de transit `reprise_source`, colonnes de l'ancien modèle |
| `02_reprise.sql` | transfert vers le schéma cible, UUID v7, table de correspondance |
| `03_controles.sql` | contrôles de complétude et de fidélité (statut OK / ECART par ligne) |
| `importer-postgres.sh` | enchaîne chargement, transfert, contrôles et export de la correspondance |

1. Arrêter l'ancienne application ; sauvegarder la base MySQL **et** le dossier
   de stockage des fichiers.
2. Exporter (compte MySQL en lecture seule suffisant) :
   ```bash
   MYSQL_HOST=... MYSQL_USER=... MYSQL_PWD=... MYSQL_DATABASE=ged \
     backend/scripts/reprise/exporter-mysql.sh /srv/reprise/export
   ```
3. Préparer la base cible (§1.1, puis §1.2 avec `-v reprise=oui`), puis démarrer
   une fois la nouvelle application **sans l'utiliser** : Liquibase crée le
   schéma, vide (ou `liquibase update`, §8). `GED_MDP_INITIAL` doit rester vide :
   la reprise refuse une cible qui contient déjà une ligne.
4. Charger, transférer, contrôler (compte `ged_owner`) :
   ```bash
   PGHOST=... PGDATABASE=ged PGUSER=ged_owner PGPASSWORD=... \
     backend/scripts/reprise/importer-postgres.sh /srv/reprise/export ged
   ```
   Code de sortie 0 : tous les contrôles sont OK (`controles.txt`). Code 2 : au
   moins un écart, à analyser avant mise en service. Toute erreur de transfert
   (clé étrangère orpheline dans l'ancienne base, valeur hors contrainte) annule
   la transaction entière : la cible reste vide.
5. Archiver `correspondance.csv` (ancien identifiant numérique → nouvel UUID,
   par table) avec le compte rendu, puis supprimer le transit
   (`DROP SCHEMA reprise_source CASCADE;`) et rejouer `preparer-base.sql` sans
   `-v reprise=oui`.

Ce que fait la conversion :

- chaque ligne reçoit un **UUID v7** dont l'horodatage est son `created_at` ;
  à instant égal, l'ancien identifiant départage : l'ordre chronologique des
  listes est conservé ;
- les horodatages MySQL (UTC) deviennent des `timestamptz` ; `bit` devient `boolean` ;
- dans la charte de nommage des plans, les jetons numériques qui désignaient un
  index deviennent son UUID ; les jetons système (`DATE`…) restent tels quels ;
- les chemins de fichiers sont repris **à l'identique** : les fichiers ne
  bougent pas sur le disque (le sous-dossier garde l'ancien numéro d'espace,
  sans incidence : le chemin complet est en base) ;
- les adresses de connexion sont ramenées en minuscules ; les empreintes BCrypt
  sont reprises telles quelles (les mots de passe restent valables) ;
- `supprime_par` et `supprime_le` restent vides pour les éléments déjà en
  corbeille : l'ancien modèle ne savait ni qui ni quand.

Les bases H2 des anciens postes de développement ne sont pas reprises : elles ne
contenaient que le jeu de démonstration, régénéré par le profil `dev`.

**Vérification** : la chaîne complète (export au format de `exporter-mysql.sh`,
chargement, transfert, contrôles, démarrage de l'application sur le résultat et
lecture par l'API) a été exécutée sur un export d'essai construit depuis
l'ancienne structure (`backend/src/test/resources/reprise/jeu-essai/`) ; le test
`RepriseDonneesTest` la rejoue à chaque `mvn test`. `exporter-mysql.sh` lui-même
n'a **pas** pu être exécuté : aucun serveur MySQL n'était disponible.

---

## 8. Migrations : évolutions et retour arrière

- Toute modification du schéma est un **nouveau** changeset
  `changesets/AAAAMMJJHHmm_objet_metier.xml`, inclus à la fin de
  `db.changelog-master.xml`, avec sa clause `<rollback>`. Un changeset appliqué
  ne se modifie jamais.
- Une opération destructrice (suppression de colonne portant des données,
  changement de type) suit le schéma **expand / contract** : ajout, bascule du
  code, suppression dans une version ultérieure — et elle est précédée d'une
  sauvegarde ciblée de la table.
- Chaque lot livré pose un jalon (`tagDatabase`) : le lot E1 pose `socle-e1`.
- **Retour arrière** avec la Liquibase CLI 4.29 (compte `ged_owner`), depuis le
  dossier `backend/src/main/resources` ou le contenu `BOOT-INF/classes` du JAR :
  ```bash
  liquibase --search-path=. --changelog-file=db/changelog/db.changelog-master.xml \
    --url="jdbc:postgresql://<hote>:5432/ged?currentSchema=ged" \
    --username=ged_owner --password="$DB_OWNER_PASSWORD" \
    --default-schema-name=ged --liquibase-schema-name=ged_liquibase \
    rollback-sql --tag=socle-e1        # aperçu du SQL, sans rien exécuter
  # puis : rollback --tag=socle-e1   (ou rollback-count --count=N)
  ```
  `validate` et `status` s'utilisent de la même façon avant un déploiement.
- Aucune migration n'est déployée en production sans que son retour arrière ait
  été exécuté avec succès en UAT. En continu, le test `SchemaLiquibaseTest`
  déroule **tous** les changesets sur un schéma vierge, vérifie les conventions
  de nommage, puis exécute le retour arrière de chacun et remonte le tout.

---

## 9. Limites connues à cette date

Elles ne bloquent pas un démarrage, mais il faut les connaître :

1. **Le limiteur de tentatives de connexion grandit sans borne.** Aucune purge :
   sur une route publique, poster des adresses différentes fait enfler la
   mémoire. À surveiller, ou à corriger avant une exposition sur Internet.
2. **La déconnexion ne révoque pas le jeton** : il reste valide jusqu'à son
   expiration (2 h). Un jeton copié continue de fonctionner.
3. **L'OCR peut bloquer un thread** : la sortie d'erreur de Tesseract n'est pas
   drainée, et le délai de garde de 120 s n'est alors jamais atteint.
4. **Aucun HTTPS n'est configuré ici** : à porter par le reverse-proxy.
5. **L'étage OCR n'a jamais été validé de bout en bout** sur un poste réel ;
   les modèles de langue ne sont pas versionnés.
