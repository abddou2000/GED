# 10 — Documents

**Rôle dans le processus :** le **fonds documentaire**. C'est là que vivent les
documents une fois déposés.

---

## 1. Ce que l'écran a à faire

Le dépôt est décrit ailleurs ([11](11-depot-et-indexation.md)). Cet écran-ci
couvre tout ce qui vient **après** : retrouver, consulter, télécharger, corriger,
verrouiller, versionner, supprimer, restaurer.

C'est la liste la plus large du produit — onze colonnes de données — parce qu'un
document porte beaucoup plus d'informations qu'un objet de paramétrage : son
type, son espace, sa taille, son extension, son chemin, sa date de création, son
créateur, son échéance et ses étiquettes.

## 2. Ce qu'un document porte

| Information | Origine |
|---|---|
| Nom | composé par la charte de nommage, ou saisi |
| Type de document | choisi au dépôt |
| Espace de travail | hérité du type |
| Fichier, extension, taille | le fichier déposé |
| Chemin | l'emplacement de stockage |
| Date de création, créateur | l'acte de dépôt |
| Date d'expiration | facultative, saisie |
| Étiquettes | posées librement |
| **Versions** | voir §3 |
| **Verrou** | voir §4 |

## 3. Le versionnement

Un document n'est pas un fichier : c'est un **objet qui porte plusieurs
versions**, dont une seule est **principale**.

```
   Document « Contrat Nova 2026 »
        ├── version 1   (déposée le 02/02)
        ├── version 2   (déposée le 14/03)   ◄── PRINCIPALE
        └── version 3   (déposée le 02/04)
```

Conséquences :

- Le **téléchargement** sert la version principale.
- Corriger un document ne consiste pas à le remplacer, mais à **ajouter une
  version** puis à la désigner principale. L'historique est conservé.
- La version principale n'est pas forcément la dernière : on peut revenir en
  arrière en désignant une version antérieure.

## 4. Le verrou

Un document peut être **verrouillé**, ce qui le protège contre la modification.
C'est un état, pas une permission : il n'y a qu'un utilisateur, le verrou sert
donc à se protéger de soi-même sur les pièces sensibles ou définitives.

## 5. Ce que la modification permet — et ce qu'elle ne permet pas

| Modifiable depuis cet écran | Non modifiable |
|---|---|
| Nom | **Le fichier lui-même** |
| Type de document | La date de création |
| Date d'expiration | Le créateur |
| Étiquettes | |
| Actif / inactif | |

**Le fichier ne se remplace jamais par une modification.** Pour changer le
contenu, on dépose une nouvelle version (§3). C'est ce qui garantit qu'un
document consulté hier peut encore être retrouvé aujourd'hui tel qu'il était.

## 6. Le téléchargement

Le fichier est récupéré par l'application, puis remis à l'utilisateur — il n'est
pas ouvert dans un nouvel onglet.

**La raison est concrète :** une navigation directe vers l'adresse du fichier
n'emporte pas la preuve de session. L'onglet s'ouvrait donc blanc, sans message.
En passant par l'application, le téléchargement est authentifié, le nom du
fichier est assaini avant l'enregistrement, et un échec produit un message
lisible au lieu d'une page vide.

## 7. La suppression

Comme partout, elle est **réversible** : le document part à la corbeille, d'où il
peut être restauré. La confirmation nomme le document.

**Ce qu'il faut savoir :** le fichier physique n'est **jamais effacé du disque**,
même après suppression. C'est ce qui permet à la restauration de rendre un
document complet, et non une fiche vide. La contrepartie est que le stockage ne
décroît jamais.

## 8. Le lien avec les signatures

Toute écriture sur un document — dépôt, suppression, restauration, y compris en
lot — **notifie le module de signatures**. C'est ce qui tient à jour la pastille
du menu.

Sans ce lien, le compteur restait figé pendant que la liste changeait, et
l'utilisateur ne savait plus lequel croire (voir [00](00-vue-d-ensemble.md) §7 et
[02](02-accueil.md) §5).

## 9. En amont

Un document n'existe que par le [dépôt](11-depot-et-indexation.md), qui suppose
lui-même toute la chaîne de paramétrage.

## 10. Message

Suppression : « Document supprimé. » · En lot : « 2 document(s) supprimé(s). »
