# GED — Partie front

Application **Angular 22**, TypeScript 6, Angular Material.
Onze écrans : accueil, documents, indexation automatique, espaces de travail,
workflows, groupes d'accès, index, plans d'indexation, types de document, étiquettes.

---

## À lire avant de démarrer

> **Ce front ne fonctionne pas seul.** Il consomme l'API du backend Spring Boot sur
> `http://localhost:8080`. Sans backend démarré, l'écran de connexion s'affiche, mais
> toutes les listes restent vides et la console du navigateur montre des erreurs réseau.
>
> Demandez l'archive du backend, ou voyez la section *Pointer vers un autre backend*.

---

## Prérequis

| | Version |
|---|---|
| **Node.js** | 20.19+, 22.12+ ou 24+ — Angular 22 refuse les versions antérieures |
| **npm** | fourni avec Node (développé avec npm 11.12.1) |

Vérifiez : `node --version`

## Démarrer

```bash
npm install
npm start
```

L'application écoute sur **http://localhost:4200**.

Le backend accepte **n'importe quel port de `localhost`** en CORS — inutile donc de
reproduire le port 4301 utilisé côté développement. Pour en imposer un malgré tout :

```bash
npm start -- --port 4301
```

## Compiler pour la production

```bash
npm run build
```

Sortie dans `dist/frontend`. Budget de bundle initial : 500 kB (l'application est
actuellement à ~354 kB, chaque écran étant chargé à la demande).

---

## Pointer vers un autre backend

Une seule ligne à changer — `src/app/core/api.ts` :

```ts
export const API_BASE = 'http://localhost:8080/api/v1';
```

Remplacez l'hôte et le port si le backend tourne ailleurs. Aucun fichier de proxy
Angular n'est utilisé : les appels partent directement du navigateur vers cette adresse.

---

## Ce qu'on peut voir sans backend

| Écran | Sans backend |
|---|---|
| `/login` | s'affiche normalement (aucun appel serveur) |
| Tous les autres | la coque s'affiche, les listes restent vides |

## Vérifier que la liaison fonctionne

Backend démarré, ouvrez <http://localhost:4200/indexation-automatique>. La liste des
documents doit se remplir. Si elle reste vide, ouvrez la console du navigateur : une
erreur CORS ou `ERR_CONNECTION_REFUSED` indique que le backend n'écoute pas sur 8080.

---

## Organisation du code

```
src/app/
├── core/          services transverses, jetons de design, dialogues, icônes
│   ├── api.ts             ← l'adresse du backend
│   ├── confirm.service.ts  dialogues de confirmation
│   ├── notify.service.ts   notifications
│   └── ged-icons.ts        icônes Lucide enregistrées dans MatIconRegistry
├── layout/shell/  barre supérieure, menu horizontal, tiroir sous 992 px
├── features/      un dossier par écran
│   ├── indexation/        analyse, confirmation, recherche par index
│   ├── document/          dépôt et liste
│   ├── workspace/  workflow/  index/  plan-indexation/ …
└── app.routes.ts  routes, toutes chargées à la demande
```

Composants **standalone**, signaux Angular, Material 3.
Les styles communs sont dans `src/styles.scss` (jetons de couleur, style des dialogues)
et `src/app/core/_ui.scss` (classes utilitaires : `.page`, `.card`, `.toolbar`, tableaux).

## Conventions à respecter

- **Aucun `!important`** dans les feuilles de style — la règle tient sur tout le projet.
  Pour l'emporter sur Angular Material, réglez ses jetons `--mat-*` ou doublez une classe.
- Les écrans n'appellent jamais `HttpClient` directement : chaque module a son service.
- Le libellé des index et des critères vient du serveur ; **rien n'est codé en dur**
  dans les écrans.

---

## Réserve

L'authentification est **factice**. `core/session.service.ts` est une coque locale : le
formulaire de connexion n'appelle aucun service, aucun jeton n'est émis. Côté backend,
toutes les routes sont ouvertes. C'est un chantier à part entière, non couvert ici.
