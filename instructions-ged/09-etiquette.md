# 09 — Étiquettes — instructions

## 1. Objectif

Offrir un **classement transversal**, libre, indépendant de la hiérarchie des
dossiers.

Une étiquette ne porte **aucune valeur** : elle est posée ou elle ne l'est pas.

| | Index | Étiquette |
|---|---|---|
| Porte | une valeur (« 2026003 ») | rien |
| Décidé par | le paramétrage | l'opérateur, au dépôt |
| Nombre par document | un par index du plan | autant qu'on veut |

**L'exemple qui éclaire :** « Urgent » peut concerner une facture, un contrat et
un compte rendu — trois types, trois espaces, trois circuits. Aucune hiérarchie
de dossiers ne permet de les regrouper ; une étiquette, si.

## 2. Ce que l'écran doit afficher

### Colonnes du tableau

`Code` · `Étiquette` · `Couleur` · `Actions`

**Instruction :** la colonne « Couleur » affiche une **pastille de la teinte**
suivie du code hexadécimal. Les deux doivent être dessinés **à partir de la même
valeur** — jamais deux sources qui pourraient diverger.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| Créer | ouvrir le formulaire, couleur pré-remplie |
| Saisir une couleur | mettre à jour la pastille d'aperçu en direct |
| Modifier | ouvrir le formulaire pré-rempli |
| Supprimer | confirmation nommant l'étiquette |
| Restaurer | immédiat, sans confirmation |

## 4. Formulaire

**L'ordre des champs est imposé et diffère de celui du tableau :**

| Ordre | Champ | Type | Obligatoire | Exemple / défaut |
|---|---|---|---|---|
| 1 | **Étiquette** | texte | **oui** | `Ex. Urgent` |
| 2 | **Code** | texte | **oui** | `Ex. TAG-URG` |
| 3 | **Couleur** | hexadécimal libre | **oui** | `#000000` en exemple, `#16406b` pré-rempli |

## 5. Règles à respecter

1. **Le libellé se saisit avant le code.** On crée une étiquette parce qu'on a un
   mot en tête, pas un identifiant. Demander le code d'abord obligerait à
   inventer un code pour une notion pas encore nommée.
2. **La couleur est un champ libre**, pas une liste fermée de teintes imposées.
3. **Pré-remplissez la couleur** avec une teinte de la marque : un enregistrement
   fait sans choix conscient doit produire une étiquette lisible, pas une
   étiquette invisible.
4. Une étiquette ne porte aucune valeur : ne prévoyez pas de champ « valeur ».
5. Supprimer une étiquette **ne supprime aucun document** : elle disparaît des
   documents qui la portaient.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Libellé vide | « Le libellé est obligatoire » |
| Code vide | « Le code est obligatoire » |
| Création | « Étiquette créée. » |
| Suppression | « Étiquette supprimée. » |
| Restauration | « Étiquette restaurée. » |
| Suppression en lot | « Voulez-vous supprimer 2 étiquette(s) ? » |
| Corbeille vide | « Aucune étiquette supprimée n'attend d'être restaurée. » |

**Attention à la bascule :** l'onglet s'intitule **« Actifs »**, au masculin
(cohérence avec les autres écrans), même si l'objet est féminin.

## 7. Dépendances

- **En amont :** aucune.
- **En aval :** les étiquettes sont proposées au dépôt (11) et affichées dans la
  liste des documents (10).
