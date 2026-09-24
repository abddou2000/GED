# 03 — Espaces de travail — instructions

## 1. Objectif

Définir **où** les documents sont rangés, et par conséquent **quel circuit de
validation** leur sera appliqué.

Un espace de travail est un dossier de classement qui porte trois choses : une
place dans une hiérarchie, un propriétaire, et **une règle de workflow**.

## 2. Ce que l'écran doit afficher

Trois vues sur la même donnée, accessibles par onglets : **Tableau**, **Arbre**,
**Archive**.

### Colonnes du tableau

`Code` · `Dossier parent` · `Description` · `Dossier` · `Propriétaire` ·
`Circuit` · `Statut` · `Actions`

### Vue arbre

Une arborescence dépliable. Chaque nœud affiche le nom du dossier, son code, son
propriétaire, et un menu d'actions.

## 3. Actions et comportements attendus

### Depuis le tableau

| Geste | Comportement attendu |
|---|---|
| « Créer un espace » | ouvrir le formulaire vide |
| Modifier | ouvrir le formulaire **pré-rempli** |
| Supprimer | confirmation nommant le dossier, puis mise à la corbeille |
| Restaurer | immédiat, sans confirmation |
| Actions groupées | voir [00](00-regles-communes.md) §3 |

### Depuis l'arbre — menu de chaque nœud

Le menu doit comporter **exactement ces cinq entrées** :

| Entrée | Comportement attendu |
|---|---|
| **Créer un sous-dossier** | ouvrir le formulaire avec **le parent déjà rempli** |
| **Modifier** | ouvrir le formulaire pré-rempli |
| **Archiver / Désarchiver** | changer le **statut**, pas l'existence |
| **Déplacer sous…** | demander le nouveau parent, puis déplacer le nœud **avec ses enfants** |
| **Supprimer** | confirmation, puis mise à la corbeille |

## 4. Formulaire

| Champ | Type | Obligatoire | Valeur par défaut |
|---|---|---|---|
| Code | texte | **oui** | vide |
| Nom du Workspace | texte | **oui** | vide |
| Description | texte long | non | vide |
| **Règles de Workflow** | liste | **oui** | — |
| Utilisateur Propriétaire | liste | **oui** | — |
| Espace de travail parent | liste | non | « — Aucun (racine) — » |
| Statut | liste | **oui** | « Actif » |

Valeurs du statut : **Actif**, **Inactif**, **Archivé**.

## 5. Règles à respecter

1. **Le circuit est obligatoire.** Un espace sans règle de workflow produirait des
   documents que personne ne valide.
2. **Le propriétaire est obligatoire.** Un dossier sans responsable est orphelin.
3. **Ne confondez pas le statut et la corbeille.** Ce sont deux mécanismes
   indépendants :

| | Statut | Corbeille |
|---|---|---|
| Question | le dossier est-il en service ? | le dossier existe-t-il encore ? |
| Valeurs | Actif / Inactif / Archivé | présent / supprimé |
| Geste | modifier la fiche, ou « Archiver » | « Supprimer » / « Restaurer » |

   Un dossier peut être **archivé sans être supprimé**, et inversement.

4. **Construisez le tableau et l'arbre depuis la même source de données.** Écrits
   séparément, ils divergent dès la première modification et l'utilisateur ne
   sait plus lequel dit vrai.
5. **La hiérarchie est portée par le parent nommé**, jamais par l'ordre des
   lignes. Trier le tableau par nom ne doit pas modifier l'arbre.
6. **Déplacer un dossier déplace toute sa sous-arborescence.**
7. Dans l'arbre, distinguez visuellement les dossiers racines des sous-dossiers.
8. Un nœud plié doit indiquer **combien de sous-dossiers il cache**.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Création | « Espace de travail créé. » |
| Modification | « Espace de travail modifié. » |
| Suppression | « Dossier supprimé. » |
| Suppression en lot | « 2 dossier(s) supprimé(s). » |
| Restauration | « Dossier restauré. » |
| Archivage | « Dossier archivé. » |
| Déplacement | « Dossier déplacé. » |
| Arbre vide | « Créez un espace pour commencer à ranger vos documents. » |
| Corbeille vide | « Aucun dossier supprimé n'attend d'être restauré. » |

## 7. Cas particuliers

- Un espace **archivé** n'accueille plus de nouveaux documents mais reste
  consultable.
- La suppression d'un espace contenant des documents doit être signalée dans la
  confirmation.

## 8. Dépendances

- **En amont :** au moins une règle de workflow (04) et un employé pouvant être
  propriétaire.
- **En aval :** les types de document (08) pointent vers un espace ; le dépôt
  (11) y range les fichiers ; le circuit alimente Mes workflow (12).
