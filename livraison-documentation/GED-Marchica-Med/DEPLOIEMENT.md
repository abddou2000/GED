# Mise en production — GED Marchica Med

Procédure vérifiée sur MySQL 8.4 réel : base vierge, schéma créé par Flyway,
19 tables, 17 index, connexion fonctionnelle.

---

## 1. Prérequis

| | Version |
|---|---|
| Java | 17 |
| MySQL | 8.x |
| Node (pour compiler le frontend) | 20+ |

Créer une base **vide**, en `utf8mb4` — le nom des documents contient des
accents, et une base en `latin1` les mutilerait sans erreur :

```sql
CREATE DATABASE ged CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'ged'@'%' IDENTIFIED BY '<mot-de-passe-fort>';
GRANT ALL PRIVILEGES ON ged.* TO 'ged'@'%';
FLUSH PRIVILEGES;
```

L'utilisateur a besoin des droits **DDL** : c'est Flyway qui crée les tables au
premier démarrage.

---

## 2. Variables d'environnement

| Variable | Obligatoire | Rôle |
|---|---|---|
| `GED_JWT_CLE` | **oui** | Clé de signature, 32 octets minimum. **Absente en prod = refus de démarrer**, volontairement. |
| `GED_MDP_INITIAL` | **oui au 1er démarrage** | Mot de passe du compte administrateur amorcé. Vide = **aucun compte créé**, et personne ne peut se connecter. |
| `GED_EMAIL_ADMIN` | recommandé | Adresse du compte unique. Défaut : `admin@marchica.ma`. |
| `GED_NOM_ADMIN` | non | Nom affiché. Défaut : `Administrateur GED`. |
| `GED_ORIGINES` | **oui** | Origines CORS du frontend. Sans elle, repli sur `localhost` — le frontend déployé sera refusé. |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | oui | Connexion MySQL. |

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

Au premier démarrage, chercher ces trois lignes dans le journal :

```
Migrating schema `ged` to version "1 - schema initial"
Migrating schema `ged` to version "2 - index de performance"
Successfully applied 2 migrations
```

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

- la base MySQL,
- le dossier de stockage (`ged.storage.root`), qui contient les fichiers.

L'un sans l'autre ne permet pas de restaurer : la base porte les métadonnées,
le disque porte les documents.

**Le stockage ne fait que croître.** Aucun fichier n'est jamais effacé, même
après mise à la corbeille — c'est un choix, pour que la restauration ne mente
pas. Prévoyez la place, et une purge décidée manuellement.

**Redémarrage = reconnexion.** Les jetons ne survivent pas à un redémarrage si
`GED_JWT_CLE` change. Gardez la même clé d'un déploiement à l'autre.

---

## 7. Limites connues à cette date

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
