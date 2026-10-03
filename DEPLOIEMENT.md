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
- **Seule exception (décision D15)** : à chaque appel délégué (`X-On-Behalf-Of`
  d'une application), le compte de service lit le seul attribut
  `userAccountControl` du compte désigné (recherche par `objectGUID`) ; bit
  ACCOUNTDISABLE (0x2) = refus 422 `IDENTITE_DELEGUEE_INVALIDE`, motif au journal
  d'audit. Le compte de service doit donc pouvoir lire cet attribut (c'est le
  cas par défaut dans AD pour les utilisateurs authentifiés). État gardé en
  cache `GED_DELEGATION_CACHE_ETAT_COMPTE` (2 min par défaut, 5 min au plus :
  le démarrage est refusé au-delà). La connexion interactive ne lit toujours
  pas cet attribut.
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

**Construction et SBOM** : `mvn package` produit le SBOM (`target/bom.json`,
`target/bom.xml`, complété de Tesseract et de ses modèles par
`outils/completer-sbom.mjs`) et exige pour cela un accès **en ligne** au dépôt
Maven. Hors ligne (`mvn -o package`, poste isolé, forge sans accès), la
construction échoue (« SBOM JSON absent ») ; passer alors
`-Dged.sbom.completer.skip=true` : le JAR est produit, mais le **SBOM est
incomplet** et ne vaut pas registre des dépendances (T-085) — le régénérer en
ligne avant toute livraison.

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

### 3.1 Construction hors ligne et SBOM

`mvn package` produit le SBOM CycloneDX (`target/bom.json`, `target/bom.xml`)
puis `outils/completer-sbom.mjs` y ajoute Tesseract et les modèles de
`backend/tessdata` (T-085) ; Node est requis. Le greffon CycloneDX exige le mode
en ligne : en **construction hors ligne** (`mvn -o package`, poste isolé, forge
sans accès), il ne produit aucun SBOM. La construction **réussit** alors avec
l'avertissement :

```
[AVERTISSEMENT] SBOM INCOMPLET : …/target/bom.json absent, Maven hors ligne (…) ;
le JAR est construit SANS SBOM, à ne pas livrer tel quel : …
```

Un tel JAR sert aux essais ; le JAR livré à MMED est accompagné de son SBOM :
reconstruire en ligne (`mvn package`) ou prendre le JAR et le SBOM produits par
la CI. Si un `target/bom.json` d'une construction précédente est présent, il est
complété mais **non régénéré** (avertissement « n'a pas été régénéré ») :
`mvn clean` avant une construction de livraison.

**En CI** (variable `CI` positionnée, comme sur GitHub Actions), un SBOM absent
reste une **erreur** (code 2, construction en échec), même hors ligne : le
contrôle n'est pas affaibli. Pour reproduire ce comportement sur un poste :
`CI=true mvn -o package`. `-Dged.sbom.completer.skip=true` désactive l'étape
entièrement (SBOM sans Tesseract ni modèles, sans avertissement).

### 3.2 OCR : modèles et résolution (débit, P-14)

Réglage par défaut retenu après mesure (`docs/exploitation/ESSAIS-DE-CHARGE.md` § 2.4 :
5,35 → 2,90 s de CPU par page et par cœur, CER dans les seuils du DAT §4.3.2) :

| Propriété | Variable | Défaut | Rôle |
|---|---|---|---|
| `ged.ocr.modeles` | `GED_OCR_MODELES` | `entiers` | `entiers` : au démarrage, copie des modèles de `GED_TESSDATA` compactée en entiers par `combine_tessdata -c` (même réseau que `tessdata_best`, ~40 % de CPU en moins) ; refaite si un modèle change (empreinte SHA-256). `precis` : modèles livrés tels quels. Toute autre valeur empêche le démarrage. |
| `ged.ocr.modeles-entiers.repertoire` | `GED_OCR_MODELES_ENTIERS_REPERTOIRE` | `${java.io.tmpdir}/ged-tessdata-entiers` | Répertoire de la copie compactée (quelques Mo ; doit être inscriptible par le compte `ged` : sous `/var/lib/ged`, ou le tmpfs, où elle est refaite à chaque redémarrage du serveur). |
| `ged.ocr.modeles-entiers.combine-tessdata` | `GED_OCR_COMBINE_TESSDATA` | à côté de `GED_TESSERACT` (`/usr/bin/combine_tessdata`) | Outil de conversion, livré par le paquet `tesseract-ocr` (Debian, Ubuntu) avec `tesseract`. |
| `ged.ocr.chaine.dpi` | `GED_OCR_DPI` | `200` | Résolution du rendu des pages PDF scannées (300 auparavant). Revenir à `300` si l'échantillon de MMED montre des corps de 8 pt ou moins mal lus (+14 % de CPU). |

Vérification au démarrage, dans le journal : « Modèles OCR compactés en entiers dans … :
[ara, eng, fra] ». **Sans `combine_tessdata`** (ou répertoire non inscriptible), l'application
démarre quand même avec les modèles précis et l'écrit en avertissement (« Aucun modèle OCR
compacté en entiers … débit réduit ») : l'OCR fonctionne, au débit d'avant (~5,4 s par page).
Les modèles inscrits au registre des dépendances et au SBOM restent ceux de `backend/tessdata`
(la copie en est dérivée, rien n'est ajouté au paquet). Inchangés : `--psm 3`, `--oem 1`,
langue `ara+fra`, `OMP_THREAD_LIMIT=1`, un worker OCR par cœur (`GED_OCR_CHAINE_WORKERS`).

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
- les appartenances aux groupes sont reprises **en attente**
  (`groupe_membre_attente`) : le membre d'un groupe GED est une identité GED
  (T-025), et aucune n'existe avant la première connexion. Elles deviennent
  des appartenances réelles (`groupe_membre`) à la première connexion de chaque
  personne, sans action de l'Administrateur ; d'ici là elles n'apportent aucun
  droit (contrôle 8 : membres et attente réunis) ;
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
  `identite-e2` (E2), `autorisation-e3` (E3), `modele-e7` (E7, partie modèle),
  `workflow-e8` (E8, workflow de validation).
- **Passage au lot E3** (`autorisation-e3`) — à relire avant la montée :
  - `workspace` devient `noeud` (chemin matérialisé et nature ESPACE / DOSSIER,
    calculés pour l'existant et tenus ensuite par déclencheurs) ; `access_group`
    et `access_group_employe` deviennent `groupe_ged` et `groupe_membre` ;
  - les rôles globaux (`utilisateur_role`) deviennent des habilitations de portée
    globale ; les deux tables d'origine (`utilisateur_role`,
    `access_group_workspace`) sont supprimées (le retour arrière les recrée) ;
  - les anciens liens groupe / espace (`access_group_workspace`) ne deviennent
    **pas** des habilitations (décision du point 9, lot E7, changeset
    `202610011000`) : dans l'ancienne application ils n'autorisaient rien, et
    une habilitation de groupe sur un espace aurait restreint un Administrateur
    membre du groupe (l'attribution la plus spécifique prévaut). Ils sont
    consignés dans le **rapport de reprise** `reprise_lien_groupe_espace`,
    présenté en tête de l'écran **Habilitations** : l'Administrateur y pose
    lui-même les droits voulus (« Préparer l'attribution »). Groupes et membres
    sont conservés. Sur une base déjà montée en E3, le changeset retire les
    habilitations de groupe issues de la reprise (rôle Utilisateur standard,
    sans auteur) et les porte au rapport ; son retour arrière les restitue ;
  - `version_habilitations` est tenu par des déclencheurs (séquence
    `version_habilitations_seq`) : `ged_app` doit avoir `USAGE` sur les
    séquences du schéma, ce que `preparer-base.sql` accorde déjà par défaut.
- **Passage au lot E7, partie modèle** (`modele-e7`) :
  - `version_document.is_default` devient `courante` ; les versions reçoivent
    un numéro dans l'ordre de dépôt ; un document qui avait plusieurs versions
    « principales », ou aucune, garde la plus récente comme courante (index
    unique partiel ensuite) ; l'historique est en lecture seule (déclencheur) ;
  - chaque plan d'indexation reçoit sa version 1, et chaque document la
    version 1 du plan de son type ; `date_document` reprend la date de dépôt ;
  - les échéances de conservation se calculent dès qu'un type reçoit une durée.
- **Passage au lot E8, workflow de validation** (`workflow-e8`) — à relire avant la montée :
  - `workflow_ged` et `workflow_ged_etape` deviennent `regle_workflow` et
    `regle_validateur` (colonne `regle_workflow_id` sur `noeud`, désormais
    facultative, et sur `type_document`) ; un validateur est nommé
    (`employe_id`) ou désigné par rôle (`role_id`, `perimetre_noeud_id`) ;
  - les signatures séquentielles (`workflow_ged_signature`) deviennent un
    circuit par document (`circuit`), un validateur par ancienne étape
    (`circuit_validateur`, même identifiant) et une décision par étape signée ou
    rejetée (`decision`, sur la version courante ; un rejet sans motif reçoit
    « Refus repris sans motif »). Statut : REFUSE s'il y avait un rejet, VALIDE
    si tout était signé, EN_COURS sinon. La table d'origine est supprimée ; le
    retour arrière la recrée à partir des circuits. **Retour arrière avec perte
    bloqué (ANO-E8-003)** : l'ancien modèle ne sait représenter ni les circuits
    annulés, ni les validateurs par rôle, ni les réaffectations, ni l'historique
    des décisions (décisions caduques ou retirées). S'il en existe, le retour
    arrière au-delà de `workflow-e8` s'arrête avec leur décompte et ne supprime
    rien. Le poursuivre est une **décision explicite** de l'exploitant, après
    export de ces données (`circuit`, `circuit_validateur`, `decision`) : ajouter
    à l'URL de la CLI `&options=-c%20ged.retour_arriere_avec_perte%3Doui`, puis
    relancer la même commande ; la même garde protège les autres changesets
    destructeurs du lot et des lots suivants (voir « Retour arrière sans perte »
    ci-dessous) ;
  - un rôle ordinaire **Lecteur (diffusion)** (`LECTEUR`, permission Consulter)
    est livré : c'est lui que la diffusion d'un document validé attribue ;
  - une règle sans validateur ne masque plus celle d'un nœud ancêtre ; les
    nœuds repris portent souvent une telle règle vide : vérifier dans l'écran
    « Règles de Workflow » les règles effectivement voulues ;
  - la reprise MySQL (`02_reprise.sql`) produit directement ce modèle ;
    contrôles 3, 4, 18 et 19 de `03_controles.sql` ;
  - propriété `ged.workflow.inactivite-jours` (90 par défaut) : au-delà, un
    validateur nommé sans connexion est signalé à l'Administrateur.
- **Montée sur une base qui contient des documents archivés (ANO-E1-006)** : la
  numérotation des versions existantes (`202609301050`) écrit sur les versions
  de documents archivés ; le gel de ces versions (`trg_version_document_archive`)
  est suspendu par `202609301049` et rétabli par `202609301051`, application
  arrêtée. Si une montée s'interrompt entre les deux, relancer la montée : elle
  reprend et rétablit le gel. Contrôle après montée :
  `SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_version_document_archive'` → `O`.
- **Alerte d'échéance de conservation (T-112)** : table `verrou_tache` et colonne
  `document.echeance_signalee_le` (`202610031000`) ; à la première exécution, tous
  les documents déjà échus sont signalés d'un coup aux Agents d'archive.
- **Modèle de référence §12.1 (T-025)** : `202610041010` retire les huit colonnes
  `droit_*` de `groupe_ged` (inertes depuis E3) après avoir consigné leurs
  valeurs vraies dans le rapport `reprise_droits_groupe` ; `202610041020`
  renomme `name` en `nom` dans `groupe_ged`, `noeud` et `regle_workflow`
  (contrainte `uk_groupe_ged_nom`). L'API ne change pas (propriété JSON
  `name`). **Toute requête SQL externe** (rapport, export, supervision) qui lit
  ces colonnes est à adapter avant la montée. Retour arrière sans perte :
  colonnes et valeurs d'origine recréées depuis le rapport.
- **Membres des groupes GED = identités GED (T-025, écart 2, décision du client
  du 03/10)** : `202610061000` (expand) ajoute `groupe_membre.utilisateur_id`,
  rempli par `utilisateur.employe_id`, et déplace les appartenances des
  employés **sans identité** (jamais connectés : cas de toute la reprise) dans
  `groupe_membre_attente`, avec le même identifiant de ligne ; `202610061010`
  (contract) retire `groupe_membre.employe_id`. Les appartenances en attente
  n'apportent aucun droit ; elles deviennent réelles à la **première
  connexion** de la personne, sans action de l'Administrateur. L'API des
  groupes ne change pas (`userIds` = fiches employé, ou identités ; nouveau
  champ `pendingUserIds`). **Toute requête SQL externe** qui lit
  `groupe_membre.employe_id` est à adapter (jointure par `utilisateur_id`, et
  `groupe_membre_attente` pour les personnes jamais connectées). Contrôle après
  montée : `SELECT (SELECT count(*) FROM ged.groupe_membre) + (SELECT count(*)
  FROM ged.groupe_membre_attente)` = nombre de lignes de `groupe_membre` avant
  la montée. Retour arrière **des deux changesets ensemble**
  (`rollback-count --count=2` s'ils sont les derniers) : `employe_id` est
  reconstitué, les appartenances en attente reviennent dans `groupe_membre`
  avec leur identifiant ; rien n'est perdu.
- **Index d'expression d'une métadonnée fréquente** (§12.7) : un changeset par
  champ, sur les fonctions immuables de la base, par exemple :
  ```sql
  CREATE INDEX idx_document_meta_date_facture ON ged.document (ged.meta_date(metadonnees, 'DATE_FACTURE'));
  CREATE INDEX idx_document_meta_montant ON ged.document (ged.meta_nombre(metadonnees, 'MONTANT'));
  ```
  La recherche (`POST /api/v1/documents/recherche`) emploie ces mêmes
  expressions : l'index sert sans autre changement. Les critères liste et
  booléen passent par l'index GIN existant (`idx_document_metadonnees`).
  Depuis `202610051000` (ANO-E7-007), `meta_date` et `meta_nombre` n'ont plus
  de bloc `EXCEPTION` ni d'erreur de conversion possible : elles sont
  réellement `PARALLEL SAFE`, l'index se construit en parallèle et la
  recherche tient dans un plan parallèle. Sémantique inchangée (valeur absente
  ou mal formée → `NULL`) : un index d'expression déjà construit reste valide,
  sans reconstruction.
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
- **Retour arrière sans perte (ANO-E8-003, ANO-E8-004)** : tout changeset dont
  le retour arrière supprimerait des données produites en service porte une
  garde. S'il en trouve, il refuse avec leur décompte **avant toute
  suppression**, et la commande s'arrête (code 1). Changesets gardés :

  | Changeset | Données qui seraient perdues |
  |---|---|
  | `202610031000-1` (alerte d'échéance) | marques `document.echeance_signalee_le` (les Agents d'archive seraient notifiés de nouveau) |
  | `202610021130-1` (rôle Lecteur) | habilitations du rôle `LECTEUR` (diffusions de documents validés), permissions ajoutées au rôle |
  | `202610021120-1` (reprise des signatures) | circuits annulés, validateurs par rôle, réaffectations, historique des décisions |
  | `202610021100-2` (règles de workflow) | règles rattachées à un type, validateurs de règle par rôle ; règle arbitraire donnée aux nœuds sans règle (impossible s'il n'existe aucune règle) |

  Les changesets défaits avant le refus (bascule du gel des versions
  `202609301049` / `202609301051`, verrou des tâches planifiées `verrou_tache`,
  composition des rôles `202610041000`, droits hérités des groupes
  `202610041010`, colonnes `nom` `202610041020`, membres des groupes
  `202610061010` / `202610061000`) ne perdent rien : la base reste
  cohérente et une nouvelle montée (`update`) la ramène à son état de départ.
  Poursuivre malgré la perte annoncée est une décision explicite
  (`ged.retour_arriere_avec_perte = oui`, ci-dessus), après export des données
  concernées. **Contrôle préalable**, à exécuter avant tout retour arrière
  au-delà de `workflow-e8` (compte `ged_owner`, schéma `ged`) : tout résultat
  non nul annonce un refus.
  ```sql
  SELECT (SELECT count(*) FROM ged.document WHERE echeance_signalee_le IS NOT NULL) AS signalements_echeance,
         (SELECT count(*) FROM ged.habilitation WHERE role_id = '0192a000-0000-7000-8000-000000000005') AS diffusions,
         (SELECT count(*) FROM ged.circuit WHERE statut = 'ANNULE') AS circuits_annules,
         (SELECT count(*) FROM ged.circuit_validateur WHERE employe_id IS NULL OR reaffecte_le IS NOT NULL) AS validateurs_role_ou_reaffectes,
         (SELECT count(*) FROM ged.type_document WHERE regle_workflow_id IS NOT NULL) AS regles_de_type,
         (SELECT count(*) FROM ged.regle_validateur WHERE employe_id IS NULL) AS validateurs_de_regle_par_role,
         (SELECT count(*) FROM ged.noeud WHERE regle_workflow_id IS NULL) AS noeuds_sans_regle;
  ```
  (la première colonne n'existe plus si `202610031000` est déjà défait : la retirer.)
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

---

## 10. UAT : déploiement incrémental par module métier (DAT §9.3)

Le CPS (Article 3, phase 5) demande un déploiement UAT « par processus métier,
pour détecter les bugs rapidement ». La GED est livrée en un seul JAR et un
seul paquet front ; chaque processus métier est un **module activable** par
configuration, sans recompiler.

### 10.1 Modules

| Code | Processus métier | Dossier fonctionnel / technique | Arrêt quand inactif |
|---|---|---|---|
| socle (toujours actif) | identité et habilitations, arborescence, dépôt et consultation, typologie et indexation, journal d'audit | DF §4.1, §4.3, §4.8, §4.9, §4.12 | — |
| `ocr` | OCRisation et recherche plein texte | DF §4.2, §4.4 ; DAT §4.4 | routes `/ocr`, `/recherche`, supervision OCR ; chaîne OCR arrêtée (`ged.ocr.chaine.actif`) |
| `workflow` | circuits de validation et diffusion | DF §4.5 ; DAT §12.8, D8 | routes `/workflow`, `/workflowgeds` |
| `cycledevie` | archivage, conservation, purge, re-typologisation | DF §4.6, §4.7 ; DAT §12.6, §12.9 | routes d'archivage, de conservation, de purge et de re-typologisation ; alertes d'échéance arrêtées |
| `export` | export de dossiers | DF §4.4 ; DAT §12.10 | routes `/exports` |
| `notifications` | notifications dans l'application et par e-mail | DF §4.5.3, §4.6.6 ; DAT §12.9 | routes `/notifications` ; aucune notification écrite, expédition arrêtée |
| `integration` | API d'intégration : clés d'API, bureau d'ordre, délégation | DF §4.10 ; DAT §5 | routes `/applications`, `/cles-api` ; **toute** requête portant `X-API-Key` |

Une route d'un module inactif répond **404** `application/problem+json`, code
`MODULE_INACTIF`, avant toute authentification. Le catalogue (codes, routes,
propriétés arrêtées) est dans `com.ipt.ged.modules.ModuleMetier` ; l'état est lu
au démarrage.

### 10.2 Configuration

- Propriété `ged.modules.<code>.actif` (vrai par défaut), ou variable
  `GED_MODULES_<CODE>_ACTIF`, dans `/etc/ged/modules.env` (lu par le service
  après `ged.env`, voir `deploiement/systemd/ged-backend.service`).
- Un code inconnu (faute de frappe) empêche le démarrage.
- Un module inactif impose l'arrêt de ses traitements de fond, même si un
  réglage fin est resté actif (source `ged-modules-inactifs` dans
  `/actuator/env`).
- État effectif : `GET /api/v1/modules` (tout utilisateur connecté ; le front
  y masque les menus des modules inactifs) et la métrique
  `ged_module_actif{module}` (1 ou 0) du port de management.

### 10.3 Procédure UAT

1. Déployer la version avec les seuls modules à recetter actifs, par exemple le
   socle seul :
   ```bash
   cat > /etc/ged/modules.env <<'FIN'
   GED_MODULES_OCR_ACTIF=false
   GED_MODULES_WORKFLOW_ACTIF=false
   GED_MODULES_CYCLEDEVIE_ACTIF=false
   GED_MODULES_EXPORT_ACTIF=false
   GED_MODULES_NOTIFICATIONS_ACTIF=false
   GED_MODULES_INTEGRATION_ACTIF=false
   FIN
   chown root:ged /etc/ged/modules.env && chmod 0640 /etc/ged/modules.env
   deploiement/scripts/deployer.sh uat --jar ged.jar --front front.tar.gz
   ```
2. Recette métier du socle, puis ouverture d'un module à la fois, dans un ordre
   qui respecte les dépendances (OCR avant l'export si la recette de l'export
   porte sur le texte ; notifications avec ou après le workflow) :
   ```bash
   deploiement/scripts/deployer.sh uat --activer-module ocr
   deploiement/scripts/deployer.sh uat --modules          # état effectif
   ```
   Le script écrit `/etc/ged/modules.env` (copie de l'état précédent dans
   `/var/lib/ged/deploiement/modules-precedent.env`), redémarre le service,
   attend la sonde, vérifie que l'application publie bien l'état demandé, puis
   lance le test de fumée ; en cas d'échec, il rétablit l'état précédent.
3. Anomalie bloquante sur un module : `--desactiver-module <code>` le referme
   sans toucher aux autres ; la correction suit la procédure ordinaire (§ 8,
   `GARANTIE.md`).
4. Production : tous les modules actifs (fichier `modules.env` absent ou vide).

### 10.4 Limites

- Le schéma de base est commun : les migrations d'un module sont appliquées
  même s'il est inactif (elles sont compatibles « expand / contract »).
- Module `workflow` inactif : un dépôt sous une règle déjà rattachée à un nœud
  ou à un type n'ouvre **aucun circuit** ; le document est utilisable d'emblée,
  comme sans règle (journal technique : « Dépôt … sans circuit : … module
  workflow inactif »). Le dépôt, qui relève du socle, n'est pas refusé. Après
  réactivation, ces documents ne sont pas soumis rétroactivement : un circuit
  s'ouvre à la main (`POST /api/v1/workflow/documents/{id}/circuits`) si la
  validation est requise. Les circuits ouverts **avant** la désactivation restent
  en l'état (document inactif jusqu'à la réactivation, sans perte), et un
  versement sur un tel document recalcule encore son statut.
- Un redémarrage du service est nécessaire pour changer un module (quelques
  secondes d'indisponibilité, sans perte : arrêt progressif).
- Procédure vérifiée par les tests (`ModulesTest`, `ModulesInactifsApiTest`) et
  par la démonstration du § 10.5 (systemd simulé) ; reste à la rejouer sur le
  serveur UAT, sous systemd.

### 10.5 Démonstration de `deployer.sh` et de ses retours arrière

`deploiement/uat/demontrer-deploiement.sh` (root, 10 à 15 minutes) joue sur un
poste Linux le scénario complet avec les scripts livrés, sans modification : JAR
et paquet Angular construits depuis le dépôt, PostgreSQL (base jetable
`<nom>_deploiement`, recréée par `preparer-base.sql`), Liquibase CLI de même
version que le JAR, NGINX avec `deploiement/nginx/ged.conf` (ports, certificat
autosigné et chemins réécrits), unité `ged-backend.service` réelle. Seul systemd
est simulé (`deploiement/uat/systemctl-simule` : fichiers d'environnement,
`User=ged`, `ExecStartPre`, `ExecStart`, arrêt par `SIGTERM` puis `SIGKILL` ; ni
durcissement, ni redémarrage automatique).

**Serveur PostgreSQL** : la base `<nom>_deploiement` est supprimée puis recréée
en superutilisateur. Rien n'est écrit en dur (observation O3 de la recette
vague 9) :

| Variable | Rôle | Défaut |
|---|---|---|
| `DEMO_PGHOST`, `DEMO_PGPORT` | serveur vu par la GED (`ged.env`), Liquibase (`liquibase.env`), la sauvegarde (`sauvegarde.env`, `.pgpass`) et les contrôles | `localhost`, `5432` |
| `DEMO_PGSUPER` | rôle superutilisateur (suppression et création de la base, `preparer-base.sql`) | `postgres` |
| `DEMO_PGSUPER_COMPTE` | compte système sous lequel ce `psql` tourne (authentification `peer`) ; vide = compte courant (mot de passe par `PGPASSWORD` ou `PGPASSFILE`) | `postgres` |
| `DEMO_PGSUPER_HOTE` | hôte ou répertoire de socket de la connexion superutilisateur | socket par défaut de `psql` |
| `DEMO_CREER_ROLES=oui` | crée `ged_owner` et `ged_app` s'ils manquent (`creer-roles.sql`, mots de passe `DB_OWNER_PASSWORD`, `DB_PASSWORD`) | non |
| `DEMO_INSTANCE_JETABLE=oui` | le script crée sa propre instance (voir ci-dessous) ; les variables précédentes sont alors ignorées, sauf `DEMO_PGPORT` | non |

Rejouer **sans toucher à l'instance partagée** (recette) :

```bash
# Instance jetable créée, utilisée puis supprimée par le script : initdb sous le
# compte postgres dans $DEMO_REPERTOIRE/pg, écoute 127.0.0.1:$DEMO_PGPORT
# (défaut SERVER_PORT + 700) ; superutilisateur par la socket du répertoire,
# ged_owner et ged_app par TCP avec mot de passe (scram-sha-256).
export DB_OWNER_PASSWORD=... DB_PASSWORD=...          # mots de passe choisis pour l'essai
DEMO_INSTANCE_JETABLE=oui DEMO_REPERTOIRE=/tmp/ged-demo-qa SERVER_PORT=18084 \
  GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT=33394 deploiement/uat/demontrer-deploiement.sh

# Ou une instance déjà démarrée par ailleurs (ici 127.0.0.1:55432, socket /tmp/pg-qa) :
DEMO_PGHOST=127.0.0.1 DEMO_PGPORT=55432 DEMO_PGSUPER_HOTE=/tmp/pg-qa DEMO_CREER_ROLES=oui \
  deploiement/uat/demontrer-deploiement.sh
```

Sans aucune de ces variables, le comportement est celui d'avant : serveur
`localhost:5432`, base recréée par le compte système `postgres`.

| Étape | Commande | Ce qui est contrôlé |
|---|---|---|
| E1 | `deployer.sh dev --jar v1 --front f1 --sans-retour-auto`, puis `--verifier` | validate, tag, update sur base vierge ; service sous le compte `ged` ; sonde ; test de fumée complet une fois le type documentaire du compte de fumée renseigné |
| E2 | `--jar v2 --front f2` | sauvegarde préalable, point de retour Liquibase, changeset de v2 appliqué, front servi par NGINX, fumée |
| E3 | `--retour-arriere --base` | base ramenée au point de retour, v1 et f1 rétablis, fumée |
| E4 | `--jar v2 --module back` | redéploiement du back seul |
| E5 | `--front f2 --module front`, puis `--retour-arriere --module front` | front basculé puis rétabli, back-end jamais redémarré (même PID) |
| E6 | `--jar v3 --module back` (v3 défectueuse) | fumée en échec, **retour arrière automatique** vers v2, base laissée migrée (expand) |
| E7 | `--retour-arriere --base` | changeset de v3 défait avec le JAR qui l'a appliqué, v2 conservée |
| E8 | `--desactiver-module workflow`, puis `--activer-module workflow` | route en 404 `MODULE_INACTIF` à travers NGINX, puis rouverte |

**En UAT, sous systemd** (critère 1 de la validation T-088) : mêmes étapes avec
les vrais `/etc/ged/*.env`, sans le systemctl simulé ; les artefacts v2 et v3
sont soit les livrables réels successifs, soit ceux que produit le script
(section « Préparation »). À vérifier en plus sur le serveur : directives de
durcissement de l'unité (`systemd-analyze security ged-backend`),
`Restart=on-failure` (tuer la JVM : redémarrage sous 10 s), `RequiresMountsFor`
du tmpfs, contrôles `verifier_prerequis_stockage` (sautés en `dev`).
