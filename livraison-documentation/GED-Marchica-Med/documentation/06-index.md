# 06 — Index

**Rôle dans le processus :** définir **quelles informations décrivent un
document**. C'est la brique élémentaire de toute la chaîne.

---

## 1. La notion

Un index est un **champ d'information** attaché aux documents : « Numéro de
facture », « Date d'émission », « Fournisseur ».

C'est le premier maillon : sans index, aucun plan d'indexation ; sans plan, aucun
nommage automatique ; sans type de document correctement paramétré, aucun dépôt
utile.

```
   INDEX ──► PLAN D'INDEXATION ──► TYPE DE DOCUMENT ──► DÉPÔT
    ▲
    │
  on est ici
```

## 2. Ce qu'un index déclare

| Information | Obligatoire | Ce qu'elle décide |
|---|---|---|
| Nom de l'index | oui | ce que l'opérateur lira au dépôt |
| Code | oui | l'identifiant fonctionnel |
| **Type de champ** | oui | la nature de la valeur : Texte, Nombre, Date, Liste |
| Valeurs (Options) | **si type = Liste** | les choix proposés |
| Valeur par défaut | non | ce qui est pré-rempli |
| **Obligatoire** | oui/non | le dépôt sera **bloqué** si le champ est vide |
| **Indexé pour recherche** | oui/non | le champ devient un critère de recherche |
| **Index de groupage** | oui/non | le champ devient un axe de regroupement |

## 3. Les quatre types de champ

| Type | Ce qu'il produit au dépôt |
|---|---|
| **Texte** | saisie libre |
| **Nombre** | saisie numérique |
| **Date** | sélecteur de date, valeur au format `AAAA-MM-JJ` |
| **Liste** | liste déroulante **fermée** : on choisit, on ne saisit pas |

Le choix du type n'est pas cosmétique : il détermine ce que l'opérateur pourra
écrire, donc la qualité de ce qui sera stocké. Un « Fournisseur » en Texte
accepte trois orthographes du même nom ; en Liste, il n'en accepte qu'une.

## 4. La règle du type Liste

C'est la seule règle de gestion de l'écran, et elle fonctionne dans les deux
sens :

```
   type = LISTE
        │
        ├── « Valeurs (Options) » devient OBLIGATOIRE
        │        │
        │        └── laissé vide ──► refus : « Indiquez au moins une valeur »
        │
   type ≠ LISTE
        │
        └── « Valeurs » et « Valeur par défaut » sont VIDÉS
                 et affichés « — » dans le tableau
```

**Pourquoi le vidage est automatique :** une liste de valeurs conservée sur un
champ Texte serait une donnée morte qui réapparaîtrait à la première
rebascule — l'opérateur croirait avoir perdu sa saisie, ou la retrouverait sans
comprendre pourquoi.

## 5. Les trois interrupteurs, et leur portée réelle

| Interrupteur | Où l'effet se voit |
|---|---|
| **Obligatoire** | au [dépôt](11-depot-et-indexation.md) : le bouton « Téléverser » reste bloqué tant que le champ est vide |
| **Indexé pour recherche** | le champ devient un critère de recherche documentaire |
| **Index de groupage** | le champ devient un axe de regroupement des documents |

Le plus lourd de conséquences est **Obligatoire** : il déplace une contrainte du
paramétrage vers le geste quotidien de l'opérateur. À manier avec parcimonie —
cinq champs obligatoires transforment chaque dépôt en formulaire administratif.

## 6. Effet d'une suppression

Supprimer un index utilisé par un plan d'indexation **laisse le plan
incomplet** : la charte de nommage perd un de ses éléments. L'interface ne
l'empêche pas ; c'est un point à vérifier avant de supprimer un index en
service.

## 7. En aval

Les index sont assemblés par les [plans d'indexation](07-plan-indexation.md),
eux-mêmes portés par les [types de document](08-type-de-document.md).

## 8. Messages

Suppression : « Index supprimé. »
En lot, l'unité est **« index »**, invariable : « Voulez-vous supprimer 2
index ? »
État vide de la corbeille : « Aucun index supprimé n'attend d'être restauré. »
