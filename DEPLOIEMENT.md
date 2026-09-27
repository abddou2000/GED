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

Toutes sont décrites dans `backend/.env.example`. Les profils `prod` et `uat`
appliquent les mêmes contrôles (le profil `uat` importe `application-prod.yml`).

| Variable | Obligatoire | Rôle |
|---|---|---|
| `GED_LDAP_URLS` | **oui** | Contrôleurs de domaine `ldaps://…:636`, séparés par des virgules, par ordre de préférence. Un seul suffit (D4). `ldap://` = refus de démarrer. |
| `GED_LDAP_BASE` | **oui** | Base de recherche, par exemple `DC=marchicamed,DC=ma`. |
| `GED_LDAP_COMPTE_SERVICE` | **oui** | DN du compte de service en lecture seule. |
| `GED_LDAP_MOT_DE_PASSE_FICHIER` ou `GED_LDAP_MOT_DE_PASSE` | **oui** | Secret du compte de service. Le fichier (déposé par le coffre) est relu à chaud quand il change. |
| `GED_LDAP_TRUSTSTORE` `GED_LDAP_TRUSTSTORE_MOT_DE_PASSE` | recommandé | Magasin PKCS#12 contenant la chaîne de certificats de MMED ; sinon, magasin de la JVM. |
| `GED_JWT_KEYSTORE` `GED_JWT_KEYSTORE_MOT_DE_PASSE` `GED_JWT_ALIAS` | **oui** | Clé privée RSA (≥ 2048 bits) des jetons RS256. Absente = refus de démarrer. |
| `GED_SESSION_DUREE_ABSOLUE` | non | Défaut `8h` ; **4 h recommandées** (risque R26). |
| `GED_ADMINISTRATEURS` | **oui au 1er démarrage** | sAMAccountName du ou des premiers administrateurs. |
| `GED_ORIGINES` | **oui** | Origines CORS du frontend. |
| `DB_HOST` `DB_PORT` `DB_NAME` | oui | Serveur et base PostgreSQL. |
| `DB_SSLMODE` `DB_SSLROOTCERT` | non | Défaut `verify-full` et `/etc/ged/pki/postgresql-ca.crt`. |
| `DB_USER` `DB_PASSWORD` | **oui** | Compte applicatif `ged_app`. |
| `DB_OWNER_USER` `DB_OWNER_PASSWORD` | pour le déploiement | Compte `ged_owner`, utilisé par le script de migration. |
| `GED_SMTP_HOTE` `GED_SMTP_PORT` | oui (e-mails) | Relais SMTP de MMED pour les notifications (DAT §12.9). Défaut `localhost:25` ; injoignable = trois tentatives puis état `ECHEC`, la notification restant visible dans l'application. |
| `GED_SMTP_STARTTLS` `GED_SMTP_AUTH` `GED_SMTP_UTILISATEUR` `GED_SMTP_MOT_DE_PASSE` | non | TLS exigé par défaut ; authentification seulement si le relais l'impose. |
| `GED_NOTIFICATION_EXPEDITEUR` `GED_URL_APPLICATION` | recommandé | Adresse d'expédition et adresse publique du front (lien « Ouvrir dans la GED » des e-mails). |

### 2.1 Annuaire (LDAPS)

- Protocole : LDAPS (636), TLS 1.2 ou 1.3 seulement ; le certificat des
  contrôleurs est validé contre `GED_LDAP_TRUSTSTORE` **et** le nom d'hôte est
  vérifié. Délais : connexion 3 s, lecture 5 s ; pool des connexions du compte
  de service.
- Identifiant de connexion : `sAMAccountName` **uniquement** (décision D2) ;
  l'adresse e-mail est refusée. Clé technique : `objectGUID`.
- Attributs lus : `sAMAccountName`, `objectGUID`, `givenName`, `sn`,
  `displayName`, `mail` (notifications), `department` (s'il existe). Jamais
  `memberOf`, groupes, unité, ni `userAccountControl` (P2, D1) : un compte
  désactivé est refusé par l'annuaire lui-même au moment de la liaison.
- Annuaire indisponible : connexion impossible avec un message explicite (503),
  sessions ouvertes conservées. Sonde de santé `annuaire` : à placer dans un
  groupe de supervision, **pas** dans la sonde `readiness`.

### 2.2 Clé de signature des jetons

```bash
keytool -genkeypair -alias ged-jwt -keyalg RSA -keysize 3072 -validity 825 \
        -storetype PKCS12 -keystore /etc/ged/secrets/ged-jwt.p12 -dname "CN=ged-jwt"
```

Un magasin **par environnement**, hors du dépôt, lisible par le seul compte du
service. Changer de clé déconnecte tout le monde au plus 15 minutes plus tard
(les jetons en cours ne sont plus vérifiables).

### 2.3 Sessions

Cookie `ged_renouvellement` : `HttpOnly; Secure; SameSite=Strict;
Path=/api/v1/auth`. Le frontend et l'API doivent être servis **par la même
origine** (NGINX) : le cookie n'est pas autorisé en origine croisée. NGINX doit
transmettre l'adresse du client (`X-Forwarded-For`) et l'application doit la
lire (`server.forward-headers-strategy`) : la limitation de débit de la
connexion se fait par adresse IP.

---

## 3. Backend

```bash
cd backend
mvn -DskipTests package
java -jar target/ged-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

En `uat` et en `prod`, **Liquibase ne tourne pas au démarrage**
(`spring.liquibase.enabled=false`) : le script de déploiement applique d'abord
les migrations avec `ged_owner` (`liquibase validate` puis `update`, §8), puis
démarre l'application, qui vérifie le schéma (`ddl-auto: validate`) et se
connecte avec `ged_app`. `SPRING_LIQUIBASE_ENABLED=true` rétablit la migration
au démarrage (poste isolé). Vérifié sur une base reprise : les changesets du lot
E2 s'appliquent sur des données existantes.

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

Avec son identifiant Windows et son mot de passe d'annuaire. La personne dont le
`sAMAccountName` figure dans `GED_ADMINISTRATEURS` reçoit le rôle Administrateur
à sa **première** connexion ; toute autre personne est provisionnée **sans
rôle** et voit une page d'accueil vide jusqu'à ce qu'un Administrateur lui en
attribue un : menu **Habilitations**, qui signale les identités sans rôle ; un
premier rôle est une habilitation de portée globale ou sur un espace. Les
fiches employé reprises de l'ancienne base sont rattachées automatiquement
quand le courriel de l'annuaire égale l'adresse dérivée « prénom.nom@domaine ».

**Droits (lot E3).** Un rôle est une habilitation : un sujet (utilisateur ou
groupe GED), un rôle, une cible (portée globale, espace ou dossier, document)
et éventuellement une rupture d'héritage. L'attribution la plus spécifique
prévaut : une habilitation posée sur un espace **remplace**, sur cet espace et
en dessous, ce que la portée globale donnait. Menus **Rôles** (composition) et
**Droits effectifs** (permissions d'une personne sur un objet, avec leur
origine). Toute modification est effective immédiatement, sans reconnexion.

---

## 6. Ce qu'il faut surveiller

**Sauvegarde.** Deux choses à sauvegarder, et les deux ensemble :

- la base PostgreSQL (`pg_dump -Fc`, schémas `ged` et `ged_liquibase`),
- le référentiel de fichiers chiffrés (`ged.fichiers.racine`, variable
  `GED_STOCKAGE_RACINE`), et à part ses clés (keystore `GED_KEYSTORE_CHEMIN`,
  table `cle_fichier`) : voir `docs/exploitation/RESTAURATION.md`.

L'un sans l'autre ne permet pas de restaurer : la base porte les métadonnées,
le disque porte les documents.

**Le stockage ne fait que croître.** Aucun fichier n'est jamais effacé, même
après mise à la corbeille — c'est un choix, pour que la restauration ne mente
pas. Prévoyez la place, et une purge décidée manuellement.

**Sessions.** Un compte désactivé dans l'annuaire garde sa session jusqu'à la
durée absolue (la GED ne relit pas son état, décision D1) : en cas de départ,
l'Administrateur révoque ses sessions (menu « Sessions »). Garder le même
magasin de clé JWT d'un déploiement à l'autre.

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
   schéma, vide (ou `liquibase update`, §8). Personne ne doit s'y connecter avant
   la reprise : elle refuse une cible qui contient déjà une ligne.
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
- les comptes locaux et leurs empreintes de mot de passe ne sont **ni exportés
  ni repris** (lot E2 : authentification par l'annuaire) ; chaque personne
  retrouve sa fiche employé à sa première connexion ;
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
- Chaque lot livré pose un jalon (`tagDatabase`) : `socle-e1` (E1),
  `identite-e2` (E2), `autorisation-e3` (E3).
- **Passage au lot E3** (`autorisation-e3`) — à relire avant la montée :
  - `workspace` devient `noeud` (chemin matérialisé et nature ESPACE / DOSSIER,
    calculés pour l'existant et tenus ensuite par déclencheurs) ; `access_group`
    et `access_group_employe` deviennent `groupe_ged` et `groupe_membre` ;
  - les rôles globaux (`utilisateur_role`) deviennent des habilitations de portée
    globale, et chaque rattachement groupe / espace (`access_group_workspace`)
    une habilitation du groupe sur le nœud, **rôle Utilisateur standard** ; les
    deux tables d'origine sont supprimées (le retour arrière les recrée) ;
  - **conséquence à vérifier** : l'attribution la plus spécifique prévaut. Un
    Administrateur membre d'un groupe repris sur un espace n'a plus, sur cet
    espace, que les permissions d'Utilisateur standard (ni suppression, ni
    purge). Après la montée, l'écran **Habilitations** permet de retirer ces
    habilitations de groupe ou de leur donner le rôle voulu ;
  - `version_habilitations` est tenu par des déclencheurs (séquence
    `version_habilitations_seq`) : `ged_app` doit avoir `USAGE` sur les
    séquences du schéma, ce que `preparer-base.sql` accorde déjà par défaut.
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

1. **Limitation de débit en mémoire** : propre à chaque instance ; à déplacer
   dans un cache partagé si la GED tourne un jour sur plusieurs nœuds.
2. **Portée des clés d'API** : les applications sont des sujets
   d'habilitation prévus par le modèle (lot E3), mais leurs clés arrivent au
   lot E9 ; aucune application ne peut encore appeler l'API.
3. **L'OCR peut bloquer un thread** : la sortie d'erreur de Tesseract n'est pas
   drainée, et le délai de garde de 120 s n'est alors jamais atteint.
4. **Aucun HTTPS n'est configuré ici** : à porter par le reverse-proxy.
5. **L'étage OCR n'a jamais été validé de bout en bout** sur un poste réel ;
   les modèles de langue ne sont pas versionnés.
