# GED Marchica Med — remise du projet

Ce paquet contient le **code source** de l'application et une **documentation
détaillée écran par écran**.

## Ce qu'il y a dedans

```
GED-Marchica-Med/
├── documentation/     ← une fiche Markdown par interface (commencer ici)
├── frontend/          ← Angular 22 (sources)
├── backend/           ← Spring Boot 3 / Java 17 (sources)
├── LISEZ-MOI.md       ← présentation générale du projet
├── DEPLOIEMENT.md     ← procédure de mise en production, vérifiée sur MySQL réel
└── SCENARIO-TEST-INDEXATION.md
```

## Par où commencer

1. **`documentation/README.md`** — l'index des fiches et l'ordre de lecture
   conseillé.
2. **`documentation/00-vue-d-ensemble.md`** — l'architecture, l'authentification
   et les conventions valables sur tous les écrans.
3. Ensuite, la fiche de l'écran qui vous intéresse.

Les fiches expliquent **le processus**, pas l'implémentation : à quoi sert
l'écran dans la chaîne, quelles notions il manipule, quelles étapes s'enchaînent
et dans quel ordre, quelles règles de gestion s'appliquent, ce qui est refusé et
**pourquoi**, ce qui se passe en amont et en aval. Elles se lisent sans ouvrir
le code.

## Ce qui a été retiré du paquet, et pourquoi

| Retiré | Raison |
|---|---|
| `frontend/node_modules`, `backend/target`, `dist`, `.angular` | régénérés par `npm ci` et `mvn package` (346 Mo + 93 Mo) |
| `backend/storage/` | **documents réellement déposés** — ce sont des données, pas du code |
| `backend/tessdata/` | modèles de langue OCR, 29 Mo, à retélécharger selon la plateforme |
| `*.log` | journaux d'exécution, 78 Mo |
| `.git` | historique du dépôt |

Aucun secret n'est inclus : il n'y a ni `.env`, ni clé, ni mot de passe en dur.
Toutes les valeurs sensibles passent par des variables d'environnement,
documentées dans `DEPLOIEMENT.md`.

## Pour reconstruire

```bash
# Frontend
cd frontend
npm ci
npm start          # développement, proxy vers le backend local
npm run build      # production, sortie dans dist/frontend/browser

# Backend
cd backend
mvn -DskipTests package
java -jar target/ged-0.0.1-SNAPSHOT.jar
```

Prérequis : Java 17, MySQL 8, Node 20+.

Le schéma de base est créé et versionné par **Flyway** au premier démarrage
(`backend/src/main/resources/db/migration/`) — 18 tables, 34 contraintes,
17 index. Il n'y a pas de script SQL à passer à la main.

## Deux choses à savoir avant de reprendre le code

**1. Un seul utilisateur.** Le cahier des charges impose un compte unique,
l'administrateur. Il n'y a ni rôle, ni permission par profil. Les autres
personnes visibles dans les écrans (propriétaires, approbateurs, membres de
groupes) sont des **employés**, pas des comptes : ce sont des données. Les
« groupes d'accès » n'accordent aucun droit malgré leur nom.

**2. Le mode démonstration peut s'activer tout seul.** Si
`assets/config.json` est absent ou illisible, l'application bascule
**silencieusement** en mode démonstration et n'appelle plus le serveur. Après
tout déploiement, vérifiez qu'un document réel s'affiche. Détail dans
`documentation/00-vue-d-ensemble.md` §9.

## Limites connues

Elles sont listées en fin de `documentation/00-vue-d-ensemble.md` (§11) et dans
`DEPLOIEMENT.md`. Les trois principales : le limiteur de tentatives de connexion
n'est jamais purgé, la déconnexion ne révoque pas le jeton, et l'étage OCR n'a
jamais été validé de bout en bout sur un poste réel.
