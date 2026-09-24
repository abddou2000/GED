# 10 — Documents — instructions

## 1. Objectif

Le **fonds documentaire**. Tout ce qui vient **après** le dépôt : retrouver,
consulter, télécharger, corriger, verrouiller, versionner, supprimer, restaurer.

Le dépôt lui-même est décrit dans la fiche [11](11-depot-et-indexation.md).

## 2. Ce que l'écran doit afficher

### Colonnes du tableau

`Nom` · `Type de document` · `Espace de travail` · `Taille` ·
`Date d'expiration` · `Extension` · `Chemin` · `Date de création` · `Créateur` ·
`Étiquettes` · `Actions`

C'est la liste la plus large du produit. Prévoyez un défilement horizontal
**dans le tableau**, jamais sur la page entière.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| « Téléverser un document » | ouvrir la fenêtre de dépôt (fiche 11) |
| Consulter la fiche | ouvrir le détail du document |
| **Télécharger** | récupérer le fichier **via l'application**, voir §5 règle 3 |
| Modifier | ouvrir le formulaire pré-rempli |
| Verrouiller / déverrouiller | basculer l'état de protection |
| Ajouter une version | déposer un nouveau fichier sous le même document |
| Désigner la version principale | changer la version servie au téléchargement |
| Supprimer | confirmation nommant le document |
| Restaurer | immédiat, sans confirmation |

## 4. Formulaire de modification

| Champ | Modifiable |
|---|---|
| Nom | **oui** |
| Type de document | **oui** |
| Date d'expiration | **oui** |
| Étiquettes | **oui** |
| Actif / inactif | **oui** |
| **Le fichier** | **non** — voir §5 règle 2 |
| Date de création, créateur | non |

## 5. Règles à respecter

1. **Un document n'est pas un fichier : c'est un objet qui porte plusieurs
   versions**, dont une seule est **principale**.
   - Le téléchargement sert la version principale.
   - La version principale n'est pas forcément la dernière : on doit pouvoir
     revenir en arrière.

2. **Le fichier ne se remplace jamais par une modification.** Pour changer le
   contenu, on **ajoute une version**. C'est ce qui garantit qu'un document
   consulté hier peut encore être retrouvé aujourd'hui tel qu'il était.

3. **Le téléchargement doit passer par l'application, pas par l'ouverture d'un
   nouvel onglet.**
   Raison : une navigation directe vers l'adresse du fichier **n'emporte pas la
   preuve de session** ; l'onglet s'ouvre blanc, sans message.
   **Instructions :**
   - récupérez le fichier de façon authentifiée ;
   - assainissez le nom du fichier avant l'enregistrement ;
   - en cas d'échec, affichez un **message lisible**, jamais une page vide.

4. **Le verrou protège contre la modification.** C'est un état, pas une
   permission : il n'y a qu'un utilisateur, le verrou sert à se protéger de
   soi-même sur les pièces définitives.

5. **Toute écriture sur un document doit notifier le module de signatures** —
   dépôt, suppression, restauration, y compris en lot. Sans cela, la pastille du
   menu reste figée pendant que la liste change, et l'utilisateur ne sait plus
   lequel croire.

6. **Le fichier physique n'est jamais effacé du disque**, même après suppression.
   C'est ce qui permet à la restauration de rendre un document complet et non une
   fiche vide. **Conséquence à documenter pour l'exploitant :** le stockage ne
   décroît jamais, il faut prévoir la place et une purge décidée à la main.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Suppression | « Document supprimé. » |
| Suppression en lot | « 2 document(s) supprimé(s). » |
| Restauration | « Document restauré. » |
| Restauration en lot | « 2 document(s) restauré(s). » |
| Échec de téléchargement | message explicite, jamais une page blanche |

## 7. Dépendances

- **En amont :** le dépôt (11), qui suppose toute la chaîne de paramétrage.
- **En aval :** aucune. C'est l'aboutissement du parcours.
