# 02 — Accueil (tableau de bord) — instructions

## 1. Objectif

Répondre en un coup d'œil à trois questions, dans cet ordre :
*qu'est-ce qui m'attend ?*, *où en est le fonds ?*, *où vais-je maintenant ?*

## 2. Ce que l'écran doit afficher

Un bandeau de salutation (nom, date du jour), puis des **blocs** que
l'utilisateur peut afficher, masquer et réordonner :

| Bloc | Contenu |
|---|---|
| **Indicateurs** | dossiers actifs, documents, signatures en attente |
| **À valider** | les étapes qui attendent une décision, **avec les boutons pour décider** |
| **Raccourcis** | 4 liens vers les gestes fréquents |
| **Documents récents** | les derniers dépôts |
| **Activité récente** | le fil des dernières actions |
| **Espaces de travail** | la répartition du fonds par dossier |
| **Répartition par type** | la volumétrie par type de document |
| **Dépôts sur 30 jours** | la courbe d'activité |

## 3. Actions et comportements attendus

### Bloc « À valider » — le seul bloc qui écrit

| Geste | Comportement attendu |
|---|---|
| Clic « Signer » | ouvrir une confirmation **nommant le document et l'étape** |
| Confirmation acceptée | l'étape est validée, la ligne quitte le bloc |
| Clic « Rejeter » | ouvrir une saisie de **motif obligatoire** |
| Motif vide | refuser la validation, afficher l'erreur |
| Rejet confirmé | le circuit s'arrête, la ligne quitte le bloc |
| Après toute décision | **mettre à jour les trois compteurs** (voir §5) |

### Bouton « Personnaliser »

| Geste | Comportement attendu |
|---|---|
| Clic | ouvrir le réglage des blocs |
| Masquer un bloc | il disparaît de l'écran, **aucune donnée n'est supprimée** |
| Réordonner | l'ordre est mémorisé **sur le poste**, pas dans le compte |

## 4. Formulaire

Aucun formulaire propre, hormis la saisie du motif de rejet :

| Champ | Type | Obligatoire |
|---|---|---|
| Motif du rejet | zone de texte | **oui** |

## 5. Règles à respecter

1. **Placez l'action là où l'information apparaît.** Les signatures en attente
   doivent être traitables **sur place**, sans quitter l'accueil.
2. **Le motif est obligatoire au rejet.** Un refus sans raison renvoie le
   déposant à ses suppositions : il ne saura ni quoi corriger, ni s'il doit
   recommencer.
3. **Les trois compteurs doivent bouger ensemble** : la tuile « En attente », la
   pastille du menu latéral et le pied du bloc. Recalculez-les depuis **une seule
   source**, à la même seconde. Ne les entretenez jamais séparément.
4. Une décision prise ici doit avoir **exactement le même effet** qu'une décision
   prise dans l'écran Mes workflow. Ce n'est pas un raccourci dégradé.
5. Ordonnez les blocs par **urgence décroissante** : ce qui bloque quelqu'un
   d'autre d'abord, l'état du fonds ensuite, la navigation en dernier.
6. Quand il n'y a rien à valider, affichez un état vide explicite : « Rien à
   valider ».

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Signature accordée | « Document signé. » |
| Signature refusée | « Signature rejetée. » |
| Motif manquant | « Ce champ est requis. » |
| Aucune signature en attente | « Rien à valider » |

## 7. Cas particuliers

- **Les préférences d'affichage sont locales au poste.** Elles ne suivent pas
  l'utilisateur d'un ordinateur à l'autre. Ne laissez pas croire l'inverse.
- L'écran ne doit pas défiler globalement sur un affichage courant. Prévoyez que
  les blocs se compactent plutôt que de déborder.

## 8. Dépendances

- **En amont :** être connecté.
- **En aval :** l'écran mène partout ; les décisions alimentent l'historique de
  Mes workflow.
