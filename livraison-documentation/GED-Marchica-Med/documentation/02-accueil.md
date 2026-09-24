# 02 — Accueil (tableau de bord)

**Rôle dans le processus :** répondre en un coup d'œil à trois questions, dans
cet ordre — *qu'est-ce qui m'attend ?*, *où en est le fonds ?*, *où vais-je
maintenant ?*

---

## 1. Le raisonnement de l'écran

Un tableau de bord qui se contente d'afficher des chiffres oblige à repartir
ailleurs pour agir. Celui-ci fait l'inverse : **il place l'action là où
l'information apparaît.** Les signatures en attente ne sont pas seulement
comptées, elles sont **traitables sur place**.

L'ordre des blocs suit l'urgence décroissante : ce qui bloque quelqu'un d'autre
d'abord, l'état du fonds ensuite, la navigation en dernier.

## 2. Les blocs et ce qu'ils racontent

| Bloc | Question à laquelle il répond |
|---|---|
| **Indicateurs** | combien de dossiers, de documents, de signatures en attente ? |
| **À valider** | qu'est-ce qui attend **ma** décision ? — et permet de la prendre |
| **Raccourcis** | quels sont les quatre gestes que je fais le plus souvent ? |
| **Documents récents** | qu'est-ce qui est entré récemment ? |
| **Activité récente** | que s'est-il passé ? |
| **Espaces de travail** | comment le fonds se répartit-il entre les dossiers ? |
| **Répartition par type** | quelle est la nature de ce qu'on stocke ? |
| **Dépôts sur 30 jours** | le rythme d'entrée augmente-t-il ou ralentit-il ? |

## 3. La personnalisation

Tous les blocs ne servent pas tout le monde. Le bouton **« Personnaliser »**
permet d'afficher, masquer et réordonner les blocs.

Deux points à connaître :

- Le choix est **mémorisé sur le poste**, pas dans le compte : il ne suit pas
  l'utilisateur d'un ordinateur à l'autre.
- Masquer un bloc ne supprime aucune donnée ; c'est un réglage d'affichage.

## 4. Le seul processus qui écrit : « À valider »

Ce bloc rejoue, en raccourci, le geste complet de l'écran
[Mes workflow](12-mes-workflow.md).

```
             une ligne = une étape qui attend ma décision
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
         « Signer »                      « Rejeter »
              │                               │
    confirmation qui NOMME            saisie d'un MOTIF
    le document et l'étape            (obligatoire, sans lui
              │                        le refus est impossible)
              ▼                               ▼
      l'étape est validée            le circuit s'ARRÊTE
              │                               │
              └───────────────┬───────────────┘
                              ▼
              la ligne quitte le bloc « À valider »
                              │
                              ▼
              les trois compteurs se mettent à jour
```

**Pourquoi le motif est exigé au rejet :** un refus sans raison renvoie le
déposant à ses suppositions. Il ne saura ni quoi corriger ni s'il doit
recommencer. Le motif est conservé et réaffiché plus tard dans l'historique.

## 5. La règle des trois compteurs

Le même nombre est affiché à trois endroits : la tuile « En attente », la
pastille du menu latéral, et le pied du bloc « À valider ».

**Ils doivent bouger ensemble.** Un badge figé à 32 pendant que la liste en
montre 31 fait douter de toute l'application : l'utilisateur ne sait plus lequel
croire. La règle du produit est donc : **un seul nombre, recalculé à la même
seconde à partir de la même source**, jamais trois compteurs entretenus
séparément.

C'est un défaut qui s'est déjà produit et qui a été corrigé ; c'est une règle à
préserver.

## 6. En amont et en aval

- **En amont :** il faut être [connecté](01-connexion.md).
- **En aval :** l'écran mène partout. Les décisions prises ici ont exactement le
  même effet que si elles avaient été prises dans
  [Mes workflow](12-mes-workflow.md) — ce n'est pas un raccourci dégradé.

## 7. Limite connue

L'écran déborde verticalement sur les petites hauteurs d'affichage
(environ 147 px de trop en 1280×720 ; aucun débordement en 1440×900). Défaut
identifié, non corrigé à ce jour.
