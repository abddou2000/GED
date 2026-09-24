# 03 — Espaces de travail

**Rôle dans le processus :** définir **où** les documents sont rangés, et par
conséquent **qui les valide**.

---

## 1. La notion

Un espace de travail est un **dossier de classement**. C'est la brique la plus
structurante du produit, parce qu'elle porte deux choses à la fois :

| Ce qu'il porte | Conséquence |
|---|---|
| Un emplacement dans la hiérarchie | le document a une place, pas seulement un nom |
| **Une règle de workflow** | tout document déposé dedans suit **ce** circuit |
| Un propriétaire | un employé responsable du dossier |

Le second point est le plus important et le moins visible : **le circuit de
validation d'un document n'est pas choisi au dépôt, il est hérité de l'espace.**
L'opérateur qui dépose n'a donc rien à décider — et ne peut pas se tromper de
circuit.

## 2. La hiérarchie

Les espaces s'emboîtent : un espace peut avoir un parent, ou être à la racine.
D'où deux façons de les regarder, sur la **même** donnée :

| Vue | À quoi elle sert |
|---|---|
| **Tableau** | comparer, trier, chercher, agir en lot |
| **Arbre** | comprendre l'organisation, déplacer, créer un sous-dossier au bon endroit |

**Règle de conception :** les deux vues doivent être construites à partir de la
même source. Écrites séparément, elles divergent dès la première modification, et
l'utilisateur ne sait plus laquelle dit vrai.

## 3. Ce que l'on renseigne

| Information | Obligatoire | Ce qu'elle décide |
|---|---|---|
| Code | oui | identifiant fonctionnel du dossier |
| Nom du Workspace | oui | ce qu'on lit à l'écran |
| Description | non | |
| **Règles de Workflow** | **oui** | **le circuit appliqué aux documents déposés ici** |
| Utilisateur Propriétaire | oui | l'employé responsable — une donnée, pas un compte |
| Espace de travail parent | non | la place dans la hiérarchie ; « — Aucun (racine) — » sinon |
| Statut | oui | Actif / Inactif / Archivé |

## 4. Statut et corbeille : deux mécanismes distincts

C'est le point le plus subtil de l'écran, et la source d'erreur la plus
fréquente.

| | **Statut** | **Corbeille** |
|---|---|---|
| Question | le dossier est-il **en service** ? | le dossier **existe-t-il** encore ? |
| Valeurs | Actif / Inactif / Archivé | présent / supprimé |
| Comment on change | on modifie la fiche, ou « Archiver » depuis l'arbre | « Supprimer » / « Restaurer » |
| Où ça se voit | colonne « Statut » | bascule « Archive » |

Un dossier peut être **archivé sans être supprimé** — il n'accueille plus de
nouveaux documents mais reste consultable — et **supprimé alors qu'il était
actif**. Les deux axes sont indépendants.

## 5. Le processus depuis l'arbre

L'arbre n'est pas un affichage décoratif : c'est là que se font les gestes de
structure.

```
   sur un nœud de l'arbre
            │
            ├── « Créer un sous-dossier »  ─► le parent est pré-rempli,
            │                                 impossible de se tromper de place
            ├── « Modifier »
            ├── « Archiver / Désarchiver » ─► change le STATUT, pas l'existence
            ├── « Déplacer sous… »         ─► change le PARENT
            ├── « Supprimer »              ─► met à la corbeille (confirmation)
            └── « Ouvrir le dossier »      ─► la fiche du dossier
```

## 6. Les règles de gestion

1. **Un dossier hérite sa place de son parent nommé**, pas de sa position dans
   une liste. Trier le tableau par nom ne doit pas modifier l'arbre.
2. **Le circuit est obligatoire.** Un espace sans règle de workflow créerait des
   documents que personne ne valide.
3. **Le propriétaire est obligatoire.** Un dossier sans responsable est un
   dossier orphelin.
4. **Déplacer un dossier déplace ses enfants avec lui** : la sous-arborescence
   suit.

## 7. En amont et en aval

- **En amont :** il faut au moins une [règle de workflow](04-regles-de-workflow.md)
  et un employé pouvant être propriétaire.
- **En aval :** les [types de document](08-type-de-document.md) pointent vers un
  espace ; le [dépôt](11-depot-et-indexation.md) y range les fichiers ; le
  circuit de l'espace alimente [Mes workflow](12-mes-workflow.md).

## 8. Messages

Suppression : « Dossier supprimé. » · Archivage : « Dossier archivé. » ·
Déplacement : « Dossier déplacé. »

État vide de l'arbre : « Créez un espace pour commencer à ranger vos
documents. »
