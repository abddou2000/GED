# 05 — Groupes d'accès — instructions

## 1. Objectif

Rattacher des employés à des espaces de travail, **à titre d'organisation
uniquement**.

## 2. Avertissement — à lire avant de construire l'écran

**Un groupe d'accès n'accorde aucun droit et n'en retire aucun.**

Le nom vient du cahier des charges et il évoque une gestion de permissions qui
**n'existe pas** dans ce produit.

**Instructions :**

1. **N'employez aucun vocabulaire de permission** sur cet écran : pas de
   « droits », « autorisations », « restrictions », « accès limité ».
2. Ne laissez pas croire que le groupe protège ou limite quoi que ce soit.
3. Présentez-le comme ce qu'il est : un rattachement, comparable à un
   organigramme.

Un écran qui fait croire qu'il protège quelque chose est pire qu'un écran qui
n'existe pas.

## 3. Ce que l'écran doit afficher

### Colonnes du tableau

`Code` · `Groupe` · `Espaces` · `Membres` · `Actions`

**Instruction :** les colonnes « Espaces » et « Membres » affichent des
**compteurs**, pas des listes de noms. Dans un tableau, un compteur se compare
d'une ligne à l'autre ; une liste de huit noms encombre sans se comparer.

### Fiche d'un groupe

Ordre imposé : **Code**, **Espaces couverts**, **Membres**, puis la liste
nominative des espaces de travail rattachés.

## 4. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| Créer | ouvrir le formulaire vide |
| Modifier | ouvrir le formulaire pré-rempli |
| Consulter la fiche | ouvrir le détail du groupe |
| Supprimer | confirmation **précisant combien de membres perdent le rattachement** |
| Restaurer | immédiat, sans confirmation |

## 5. Formulaire

| Champ | Type | Obligatoire |
|---|---|---|
| Code | texte | **oui** |
| Nom de group | texte | **oui** |
| Description | texte long | non |
| Liste des WorkSpaces | sélection multiple | non |
| Membres | sélection multiple | non |

> Le libellé « Nom de group » est celui du produit. Conservez-le tel quel.

## 6. Règles à respecter

1. **Un groupe vide est accepté.** On doit pouvoir le créer avant de le peupler.
2. **Supprimer un groupe ne supprime ni les employés ni les espaces.** Seul le
   rattachement disparaît.
3. La confirmation de suppression doit annoncer l'ampleur :
   > 3 membre(s) perdent ce rattachement.
4. Un employé peut appartenir à plusieurs groupes ; un espace peut être couvert
   par plusieurs groupes.

## 7. Messages à afficher

| Situation | Message exact |
|---|---|
| Création | « Groupe d'accès créé. » |
| Suppression | « Groupe d'accès supprimé. » |
| Restauration | « Groupe d'accès restauré. » |
| Code vide | « Le code est obligatoire » |
| Nom vide | « Le nom est obligatoire » |

## 8. Dépendances

- **En amont :** des employés et des espaces de travail (03).
- **En aval :** les groupes d'un employé apparaissent sur son profil (13).
  Aucun autre écran n'en dépend — ce qui confirme que le module est descriptif,
  pas structurant.
