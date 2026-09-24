# 06 — Index — instructions

## 1. Objectif

Définir **quelles informations décrivent un document** : « Numéro de facture »,
« Date d'émission », « Fournisseur ».

C'est le premier maillon de la chaîne. Sans index, pas de plan d'indexation ;
sans plan, pas de nommage automatique ; sans cela, pas de dépôt utile.

## 2. Ce que l'écran doit afficher

### Colonnes du tableau

`Code` · `Nom index` · `Obligatoire` · `Type champs` · `Valeurs` ·
`Valeur par défaut` · `Indexé pour recherche` · `Index de groupage` · `Actions`

> Les libellés de **colonne** (« Nom index », « Type champs ») diffèrent de ceux
> du **formulaire** (« Nom de l'index », « Type de champ »). C'est ainsi dans le
> produit ; conservez cette différence.

**Instruction :** les colonnes « Valeurs » et « Valeur par défaut » affichent
**« — »** quand le type n'est pas Liste.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| Créer | ouvrir le formulaire vide, type par défaut « Texte » |
| Changer le type vers **Liste** | afficher et **rendre obligatoire** le champ « Valeurs (Options) » |
| Changer le type **hors Liste** | **vider** « Valeurs » et « Valeur par défaut » |
| Modifier | ouvrir le formulaire pré-rempli |
| Supprimer | confirmation nommant l'index |

## 4. Formulaire

Ordre imposé des champs :

| Ordre | Champ | Type | Obligatoire | Défaut |
|---|---|---|---|---|
| 1 | Nom de l'index | texte | **oui** | vide |
| 2 | Code | texte | **oui** | vide |
| 3 | Type de champ | liste | **oui** | « Texte » |
| 4 | Valeurs (Options) | texte | **si type = Liste** | vide |
| 5 | Valeur par défaut | texte | non | vide |
| 6 | Obligatoire | oui/non | — | non |
| 7 | Indexé pour recherche | oui/non | — | non |
| 8 | Index de groupage | oui/non | — | non |

### Les quatre types de champ

| Valeur | Libellé | Comportement au dépôt |
|---|---|---|
| Texte | « Texte » | saisie libre |
| Nombre | « Nombre » | saisie numérique |
| Date | « Date » | sélecteur de date, format `AAAA-MM-JJ` |
| Liste | « Liste » | liste déroulante **fermée** |

## 5. Règles à respecter

1. **Type Liste ⇒ valeurs obligatoires.** Une liste vide est refusée.
2. **Type ≠ Liste ⇒ vider les valeurs.** Ne les conservez pas en arrière-plan :
   elles réapparaîtraient à la première rebascule et l'utilisateur ne
   comprendrait pas d'où elles viennent.
3. **L'interrupteur « Obligatoire » a un effet lourd** : au dépôt, il **bloque**
   le bouton de validation tant que le champ est vide. Signalez-le à qui
   paramètre — cinq champs obligatoires transforment chaque dépôt en formulaire
   administratif.
4. **Supprimer un index utilisé par un plan laisse ce plan incomplet.** Prévenez
   dans la confirmation, ou vérifiez l'usage avant suppression.
5. Le choix du type détermine la qualité de la donnée stockée : un
   « Fournisseur » en Texte acceptera trois orthographes du même nom, en Liste
   une seule. Documentez ce point pour qui paramètre.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Nom vide | « Le nom est obligatoire » |
| Code vide | « Le code est obligatoire » |
| Type Liste sans valeurs | « Indiquez au moins une valeur » |
| Création | « Index créé. » |
| Suppression | « Index supprimé. » |
| Suppression en lot | « Voulez-vous supprimer 2 index ? » puis « 2 index supprimé(s). » |
| Corbeille vide | « Aucun index supprimé n'attend d'être restauré. » |

**Attention à l'unité :** « index » est **invariable** — jamais « index(s) ».

## 7. Dépendances

- **En amont :** aucune. C'est le point de départ de la chaîne.
- **En aval :** les index sont assemblés par les plans d'indexation (07),
  eux-mêmes portés par les types de document (08), et affichés au dépôt (11).
