# 07 — Plans d'indexation

**Rôle dans le processus :** assembler des index et définir **comment ils
composent le nom d'un document**.

---

## 1. La notion

Un plan d'indexation répond à deux questions à la fois :

1. **Quelles informations** doit-on connaître sur ce document ? → la liste des
   index.
2. **Comment ces informations forment-elles son nom ?** → la charte de nommage.

La seconde question est l'apport réel du produit. Sans elle, on aurait un
formulaire de saisie de plus. Avec elle, **le nom du document devient une
conséquence de ses données** — il ne dépend plus de la discipline de celui qui
dépose.

```
   valeurs indexées                       nom composé
   ┌────────────────────┐                 ┌──────────────────────────┐
   │ numéro : 2026003   │                 │                          │
   │ date   : 2026-02-02│ ──── charte ──► │ 2026003_2026-02-02_acme  │
   │ fourn. : ACME      │                 │                          │
   └────────────────────┘                 └──────────────────────────┘
```

## 2. Ce que l'on renseigne

| Information | Obligatoire | Ce qu'elle décide |
|---|---|---|
| Code | oui | identifiant fonctionnel |
| Nom du plan | oui | ce qu'on lit dans les types de document |
| Liste des index | **non** | quelles informations seront demandées |
| **Charte de nommage** | oui | l'ordre des éléments du nom |
| **Séparateur** | oui | ce qui sépare les éléments : `-` ou `_` (défaut `_`) |
| **Convertir en** | oui | Majuscule ou Minuscule |
| Mode d'indexation | oui/non | automatique ou manuel |

Un **plan sans index est accepté** : il peut servir de coquille en attendant que
les index soient créés.

## 3. La charte de nommage

La charte est une **suite ordonnée de jetons**. Deux natures de jetons coexistent :

| Nature | Origine | Résolu par |
|---|---|---|
| **Jeton d'index** | un index du plan | la valeur saisie ou déduite au dépôt |
| **Jeton système** | date du jour, heure | **le serveur, au moment du dépôt** |

Conséquence à connaître : parce que les jetons système sont résolus côté serveur,
**le nom réellement enregistré peut différer de l'aperçu** affiché à l'écran. Ce
n'est pas un défaut, c'est la définition même d'un jeton système.

## 4. La recomposition, en direct

Changer le séparateur ou la casse recompose immédiatement l'aperçu. C'est ce qui
permet de juger une charte avant de l'enregistrer : on voit le résultat, pas une
formule.

```
   [numéro de facture] [date d'émission] [fournisseur]
   séparateur : _        casse : Minuscule
                     │
                     ▼
        2026003_2026-02-02_acme

   séparateur : -        casse : Majuscule
                     │
                     ▼
        2026003-2026-02-02-ACME      ← problème, voir §5
```

## 5. Le piège du séparateur et des dates

**Le séparateur ne doit jamais apparaître à l'intérieur d'une valeur.**

Une charte qui utilise `-` alors qu'elle contient une date au format ISO
(`2026-06-10`) produit un nom que l'application **ne peut plus redécouper** : il
devient impossible de savoir si `2026-06-10` est une date ou trois éléments
distincts.

Conséquence concrète au dépôt : la GED **renonce** à recomposer le nom, et le
champ « Nom du document » reste **vide**. Le document n'est pas cassé, mais
l'automatisme ne joue plus.

> **Règle pratique : dès qu'un plan comporte une date, utilisez `_`.**

## 6. Mode automatique ou manuel

| Mode | Ce qui se passe au dépôt |
|---|---|
| **Automatique** | l'application déduit les valeurs et compose le nom |
| **Manuel** | le nom reste **saisissable** par l'opérateur ; aucune recomposition |

Le mode manuel existe pour les documents dont le nom relève d'une convention
externe qu'aucune charte ne saurait reproduire.

## 7. Une particularité d'ergonomie

C'est **le seul écran du produit dont le formulaire occupe une page entière**
plutôt qu'une fenêtre modale. La raison est simple : composer une charte demande
de voir en même temps la liste des index disponibles, l'ordre choisi, le
séparateur, la casse et l'aperçu. Une boîte de dialogue ne peut pas tenir tout
cela sans devenir illisible.

## 8. En amont et en aval

- **En amont :** il faut des [index](06-index.md).
- **En aval :** un plan est choisi par un
  [type de document](08-type-de-document.md) ; il pilote entièrement l'étape
  d'indexation du [dépôt](11-depot-et-indexation.md).

## 9. Message

Suppression : « Plan d'indexation supprimé. » — le libellé complet, pas « Plan
supprimé. »
