# 04 — Règles de workflow — instructions

## 1. Objectif

Définir **qui valide un document, et dans quel ordre**.

Une règle de workflow est une suite **ordonnée** d'étapes, chacune confiée à un
approbateur.

**Point structurant :** une règle ne s'applique **jamais** directement à un
document. Elle est rattachée à un espace de travail, et c'est l'espace qui
l'impose aux documents qu'il contient.

## 2. Ce que l'écran doit afficher

### Colonnes du tableau

`Nom` · `Étapes` · `Espaces de travail` · `Actions`

- La colonne **Étapes** affiche les étapes **numérotées dans l'ordre** :
  `1 Commission, 2 Direction`.
- La colonne **Espaces de travail** liste les espaces qui utilisent cette
  règle — **en lecture seule**.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| « Créer » | ouvrir le formulaire vide |
| Modifier | ouvrir le formulaire pré-rempli, étapes comprises |
| « Ajouter une étape » | ajouter une ligne d'étape en fin de liste |
| Glisser-déposer une étape | changer son rang, **et renuméroter toutes les étapes** |
| Retirer une étape | la supprimer, **et renuméroter** |
| Supprimer la règle | confirmation nommant la règle, puis mise à la corbeille |

## 4. Formulaire

Titres des fenêtres : **« Créer Règles de Workflow »** et **« Modifier Règles de
Workflow »**.
Boutons : **« Créer »** et **« Modifier »** (exception à la règle générale).

| Champ | Type | Obligatoire | Exemple |
|---|---|---|---|
| Nom de la règle | texte | **oui** | `Ex. : Validation comptable` |
| Étapes du circuit | liste ordonnée | **au moins une** | — |

Chaque étape comporte :

| Sous-champ | Type | Obligatoire | Contrainte |
|---|---|---|---|
| Approbateur | liste d'employés | **oui** | — |
| Libellé de l'étape | texte | **oui** | 255 caractères maximum |
| Rang | calculé | automatique | jamais saisi à la main |

## 5. Règles à respecter

1. **Le rang est renuméroté à chaque changement.** Deux étapes ne doivent jamais
   porter le même rang : tout le processus de signature repose sur cet ordre.
2. **Une règle sans étape est refusée.** Elle produirait des documents
   éternellement « en attente ».
3. **Le rattachement à un espace ne se fait pas depuis cet écran.** La colonne
   « Espaces de travail » est informative. Le rattachement se fait depuis
   l'espace (fiche 03).
4. **Ne rejouez pas les circuits déjà lancés** quand une règle est modifiée. Les
   documents en cours portent leurs propres étapes.
5. Supprimer une règle **n'interrompt pas** les circuits déjà partis.
6. L'état vide doit être **identique en vue Actifs et en vue Archive** : ne
   changez pas le texte selon l'onglet.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Nom vide | « Le nom est obligatoire » |
| Aucune étape | « Au moins une étape est requise. » |
| Création | « Règle de workflow créée. » |
| Suppression | « Règle de workflow supprimée. » |
| Suppression en lot | « Voulez-vous supprimer 2 élément(s) ? » puis « 2 élément(s) supprimé(s). » |
| État vide | « Aucune règle de workflow » / « Créez votre première règle pour lancer un circuit de validation. » |

**Attention à l'unité :** cet écran utilise **« élément(s) »**, pas
« règle(s) ».

## 7. Cas particuliers

Si le projet impose un **utilisateur unique** (voir [00](00-regles-communes.md)
§1), le choix d'un approbateur par étape reste possible : les approbateurs sont
des **employés**, c'est-à-dire des données, pas des comptes.

## 8. Dépendances

- **En amont :** des employés pouvant être approbateurs.
- **En aval :** la règle est choisie à la création d'un espace de travail (03) ;
  ses étapes deviennent les lignes de Mes workflow (12).
