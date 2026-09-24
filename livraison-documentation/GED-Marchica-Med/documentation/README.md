# GED Marchica Med — le processus, interface par interface

Une fiche par écran. Chaque fiche explique **le processus** : à quoi sert
l'écran dans la chaîne, quelles notions il manipule, quelles étapes s'enchaînent
et dans quel ordre, quelles règles s'appliquent, ce qui est refusé et pourquoi,
et ce qui se passe avant et après.

Ce sont des fiches **de compréhension**, pas des spécifications techniques. On
doit pouvoir les lire sans ouvrir le code.

## Par où commencer

**[00-vue-d-ensemble.md](00-vue-d-ensemble.md)** pose le processus global et les
notions communes. Les fiches d'écran s'appuient dessus sans le répéter.

## Les fiches

| # | Interface | Rôle dans le processus |
|---|---|---|
| 01 | [Connexion](01-connexion.md) | entrer dans l'application |
| 02 | [Accueil](02-accueil.md) | savoir ce qui attend, et agir vite |
| 03 | [Espaces de travail](03-espaces-de-travail.md) | où les documents sont rangés |
| 04 | [Règles de workflow](04-regles-de-workflow.md) | qui valide, dans quel ordre |
| 05 | [Groupes d'accès](05-groupe-d-acces.md) | qui est rattaché à quoi |
| 06 | [Index](06-index.md) | quelles informations décrivent un document |
| 07 | [Plans d'indexation](07-plan-indexation.md) | comment ces informations composent un nom |
| 08 | [Types de document](08-type-de-document.md) | la pièce qui relie tout |
| 09 | [Étiquettes](09-etiquette.md) | classement transversal |
| 10 | [Documents](10-documents.md) | le fonds documentaire |
| 11 | [Dépôt et indexation](11-depot-et-indexation.md) | **le cœur du produit** |
| 12 | [Mes workflow](12-mes-workflow.md) | signer ou refuser |
| 13 | [Profil](13-profil.md) | ce qui rattache une personne au fonds |

## L'ordre de lecture

Le produit ne se comprend pas dans l'ordre du menu, mais dans l'**ordre de
dépendance du paramétrage**. Rien ne peut être déposé tant que la chaîne
suivante n'existe pas :

```
   06 Index          « quelles informations décrivent un document »
        │
        ▼
   07 Plan d'indexation   « comment ces informations composent un nom »
        │
        ▼
   08 Type de document    « quel plan, quel espace, quels fichiers acceptés »
        │
        ▼
   03 Espace de travail   « où l'on range, et quel circuit s'applique »
        ▲
        │
   04 Règle de workflow   « qui valide, dans quel ordre »

                   ▼
   11 Dépôt d'un document        ← tout converge ici
                   ▼
   12 Circuit de signature
                   ▼
   10 Le document vit dans le fonds
```

**Lisez donc : 06 → 07 → 08 → 04 → 03 → 11 → 12 → 10.**

Les écrans 01, 02, 05, 09 et 13 se lisent isolément, à n'importe quel moment.

## Convention

Les libellés affichés à l'écran sont cités **entre guillemets** : « Ajouter ».
