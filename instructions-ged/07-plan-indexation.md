# 07 — Plans d'indexation — instructions

## 1. Objectif

Assembler des index et définir **comment ils composent le nom d'un document**.

C'est l'apport central du produit : **le nom du document devient une conséquence
de ses données**, il ne dépend plus de la discipline de celui qui dépose.

```
   numéro : 2026003
   date   : 2026-02-02   ──── charte ────►   2026003_2026-02-02_acme
   fourn. : ACME
```

## 2. Ce que l'écran doit afficher

### Colonnes du tableau

`Code` · `Nom du plan` · `Index` · `Charte de nommage` · `Actions`

**Instruction :** la colonne « Charte de nommage » affiche la charte
**recomposée** (`numéro de facture_date d'émission`), pas la liste des jetons.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| Créer | ouvrir le formulaire **en page pleine**, pas en fenêtre modale |
| Ajouter un jeton à la charte | l'ajouter en fin de suite, **et recomposer l'aperçu** |
| Réordonner les jetons | recomposer l'aperçu immédiatement |
| Changer le séparateur | recomposer l'aperçu immédiatement |
| Changer la casse | recomposer l'aperçu immédiatement |
| Séparateur `-` + un jeton date | **afficher un avertissement** (voir §6) |

**Instruction d'ergonomie :** c'est le **seul écran dont le formulaire occupe une
page entière**. Composer une charte demande de voir en même temps les index
disponibles, l'ordre choisi, le séparateur, la casse et l'aperçu. Une fenêtre
modale ne peut pas tenir tout cela.

## 4. Formulaire

| Champ | Type | Obligatoire | Défaut |
|---|---|---|---|
| Code | texte | **oui** | vide |
| Nom du plan | texte | **oui** | vide |
| Liste des index | sélection multiple ordonnée | **non** | vide |
| Charte de nommage | suite ordonnée de jetons | **oui** | vide |
| Séparateur | liste | **oui** | `_` |
| Convertir en | liste | **oui** | « Minuscule » |
| Mode d'indexation | automatique / manuel | — | automatique |

Valeurs du séparateur : `-` et `_`.
Valeurs de la casse : « Majuscule » et « Minuscule ».

## 5. La charte de nommage — deux natures de jetons

| Nature | Origine | Résolu par |
|---|---|---|
| **Jeton d'index** | un index du plan | la valeur saisie ou déduite au dépôt |
| **Jeton système** | date du jour, heure | **le serveur, au moment du dépôt** |

**Instruction :** documentez que les jetons système sont résolus côté serveur.
Le nom réellement enregistré **peut donc différer de l'aperçu**. Ce n'est pas un
défaut, c'est la définition d'un jeton système.

## 6. Règles à respecter

1. **Un plan sans index est accepté.** Il sert de coquille en attendant que les
   index existent.
2. **Le séparateur ne doit jamais apparaître à l'intérieur d'une valeur.**
3. **Règle du séparateur et des dates — la plus importante de l'écran.**
   Une charte qui utilise `-` alors qu'elle contient une date au format
   `AAAA-MM-JJ` produit un nom **impossible à redécouper** : on ne peut plus
   savoir si `2026-06-10` est une date ou trois éléments.
   **Conséquence au dépôt :** l'application renonce à recomposer le nom, et le
   champ « Nom du document » reste **vide**.
   **Instruction : avertissez l'utilisateur dès qu'un plan combine `-` et une
   date, et recommandez `_`.**
4. **Recomposez l'aperçu en direct** à chaque changement. L'utilisateur doit
   juger le résultat, pas une formule.
5. En **mode manuel**, le nom du document reste saisissable au dépôt et aucune
   recomposition n'a lieu.

## 7. Messages à afficher

| Situation | Message exact |
|---|---|
| Code vide | « Le code est obligatoire » |
| Nom vide | « Le nom du plan est obligatoire » |
| Création | « Plan d'indexation créé. » |
| Suppression | « Plan d'indexation supprimé. » |
| Suppression en lot | « 2 plan(s) supprimé(s). » |

**Attention :** le libellé complet « Plan d'indexation supprimé. », jamais
« Plan supprimé. ».

## 8. Dépendances

- **En amont :** des index (06).
- **En aval :** un plan est choisi par un type de document (08) et pilote
  entièrement l'étape d'indexation du dépôt (11).
