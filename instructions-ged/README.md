# GED Marchica Med — instructions par interface

Ce dossier contient **les instructions de chaque interface** de l'application.
Un fichier par écran. Aucun code.

Chaque fiche est **autonome** : on peut la transmettre seule à quelqu'un qui doit
construire ou reprendre cet écran.

## Comment chaque fiche est structurée

1. **Objectif** — à quoi sert l'écran
2. **Ce que l'écran doit afficher** — la liste exacte des éléments
3. **Actions et comportements attendus** — chaque geste, et ce qui doit se passer
4. **Formulaire** — champs, caractère obligatoire, valeurs par défaut, contrôles
5. **Règles à respecter** — numérotées, impératives
6. **Messages à afficher** — les textes exacts
7. **Cas particuliers**
8. **Dépendances** — ce qu'il faut avant, ce qui dépend après

## Les fiches

| # | Interface | Fichier |
|---|---|---|
| 00 | Règles communes à toutes les interfaces | [00-regles-communes.md](00-regles-communes.md) |
| 01 | Connexion | [01-connexion.md](01-connexion.md) |
| 02 | Accueil (tableau de bord) | [02-accueil.md](02-accueil.md) |
| 03 | Espaces de travail | [03-espaces-de-travail.md](03-espaces-de-travail.md) |
| 04 | Règles de workflow | [04-regles-de-workflow.md](04-regles-de-workflow.md) |
| 05 | Groupes d'accès | [05-groupe-d-acces.md](05-groupe-d-acces.md) |
| 06 | Index | [06-index.md](06-index.md) |
| 07 | Plans d'indexation | [07-plan-indexation.md](07-plan-indexation.md) |
| 08 | Types de document | [08-type-de-document.md](08-type-de-document.md) |
| 09 | Étiquettes | [09-etiquette.md](09-etiquette.md) |
| 10 | Documents | [10-documents.md](10-documents.md) |
| 11 | Dépôt et indexation | [11-depot-et-indexation.md](11-depot-et-indexation.md) |
| 12 | Mes workflow (signatures) | [12-mes-workflow.md](12-mes-workflow.md) |
| 13 | Profil | [13-profil.md](13-profil.md) |

## À lire en premier

**[00-regles-communes.md](00-regles-communes.md)** contient les règles valables
sur **toutes** les interfaces : la corbeille, la sélection multiple, la
pagination, les confirmations, la règle de l'utilisateur unique. Les autres
fiches s'y réfèrent sans les répéter.

## Ordre de construction recommandé

Les écrans ne sont pas indépendants. Rien ne peut être déposé tant que la chaîne
suivante n'existe pas :

```
   06 Index  ─►  07 Plan d'indexation  ─►  08 Type de document
                                                   │
   04 Règle de workflow  ─►  03 Espace de travail ─┘
                                    │
                                    ▼
                      11 Dépôt  ─►  12 Signatures  ─►  10 Documents
```

**Construire dans cet ordre : 06 → 07 → 08 → 04 → 03 → 11 → 12 → 10.**

Les écrans 01, 02, 05, 09 et 13 peuvent être construits à tout moment.
