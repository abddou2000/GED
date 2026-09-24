# 05 — Groupes d'accès

**Rôle dans le processus :** rattacher des employés à des espaces de travail, à
titre d'organisation.

---

## 1. Avertissement — le nom ment

**Un groupe d'accès n'accorde aucun droit et n'en retire aucun.**

C'est le point à lire avant tout le reste. Le nom vient du cahier des charges et
il évoque une gestion de permissions qui **n'existe pas** dans ce produit :
l'application n'a qu'un utilisateur, l'administrateur, et toutes les actions lui
sont ouvertes sans exception (voir [00](00-vue-d-ensemble.md) §4).

Ce que le groupe fait réellement : il **note** quels employés sont associés à
quels dossiers. C'est une information d'organisation, comparable à un
organigramme — pas une barrière.

Si le module est repris un jour, **le vocabulaire de permission ne doit pas être
réintroduit** tant que le mécanisme n'existe pas derrière : un écran qui fait
croire qu'il protège quelque chose est pire qu'un écran qui n'existe pas.

## 2. La notion

Un groupe met en relation deux ensembles :

```
      EMPLOYÉS                    ESPACES DE TRAVAIL
   ┌──────────────┐              ┌──────────────────┐
   │ Sara B.      │              │ Comptabilité     │
   │ Karim M.     │──── groupe ──│ Achats           │
   │ Nadia T.     │  « Comptes » │                  │
   └──────────────┘              └──────────────────┘
```

Un employé peut appartenir à plusieurs groupes ; un espace peut être couvert par
plusieurs groupes.

## 3. Ce que l'on renseigne

| Information | Obligatoire | Rôle |
|---|---|---|
| Code | oui | identifiant fonctionnel |
| Nom de group | oui | libellé affiché (l'orthographe est celle du produit) |
| Description | non | |
| Liste des WorkSpaces | non | les espaces couverts |
| Membres | non | les employés rattachés |

Un groupe **vide est accepté** : on peut le créer avant de le peupler.

## 4. Ce que l'écran affiche

La liste montre des **compteurs**, pas des listes : « Espaces : 1 »,
« Membres : 8 ». Le détail se lit sur la fiche du groupe, dans l'ordre : Code,
Espaces couverts, Membres, puis la liste nominative des espaces.

Le raisonnement : dans un tableau, un compteur se compare d'une ligne à l'autre ;
une liste de huit noms ne se compare pas, elle encombre.

## 5. Effet d'une suppression

Supprimer un groupe **ne supprime ni les employés ni les espaces**. Il défait
seulement le rattachement. La confirmation le dit en nommant l'ampleur :
« 3 membre(s) perdent ce rattachement ».

## 6. En amont et en aval

- **En amont :** il faut des employés et des [espaces de travail](03-espaces-de-travail.md).
- **En aval :** les groupes d'un employé apparaissent sur son
  [profil](13-profil.md). Aucun autre écran n'en dépend — ce qui confirme que le
  module est descriptif, pas structurant.

## 7. Message

Suppression : « Groupe d'accès supprimé. »
