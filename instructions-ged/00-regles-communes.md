# 00 — Règles communes à toutes les interfaces

À appliquer sur **tous** les écrans. Les autres fiches ne les répètent pas.

---

## 1. Règle fondamentale : un seul utilisateur

**L'application ne comporte qu'un compte : l'administrateur.**

Instructions :

1. **Ne créez aucun mécanisme de rôles, de permissions ou de profils.**
2. Toutes les actions sont ouvertes, sans exception, sur tous les écrans.
3. Les autres personnes visibles dans l'application — propriétaires d'espaces,
   approbateurs d'étapes, membres de groupes — sont des **employés**, c'est-à-dire
   des **données**. Elles ne se connectent pas.
4. Ne présentez jamais un employé comme un utilisateur en attente d'activation.
5. Les « groupes d'accès » **n'accordent aucun droit**. N'employez aucun
   vocabulaire de permission sur cet écran.

## 2. La corbeille — à implémenter sur les 8 écrans de liste

Écrans concernés : espaces de travail, règles de workflow, groupes d'accès,
index, plans d'indexation, types de document, étiquettes, documents.

Instructions :

1. Prévoyez une bascule **« Actifs » / « Archive »** au-dessus de la liste.
2. **Aucune suppression n'est définitive** : supprimer déplace vers la corbeille.
3. **Supprimer une ligne demande une confirmation**, et cette confirmation
   **doit nommer l'objet** :
   > « Facture janvier » sera déplacé vers la corbeille.
4. **Restaurer une ligne ne demande aucune confirmation** : l'action est
   immédiate. Ne créez pas de boîte de dialogue pour ce geste.
5. **Restaurer en lot demande une confirmation** (voir §3).
6. Le message de confirmation doit tenir en **une phrase**. N'ajoutez pas de
   paragraphe d'avertissement.

## 3. La sélection multiple

Instructions :

1. Chaque liste porte une colonne de cases à cocher en première position.
2. Une barre d'actions groupées n'apparaît **que si au moins une ligne est
   cochée**.
3. Le **compteur figure dans le bouton** : « Supprimer (2) ».
4. Le verbe du bouton **change selon la vue** : « Supprimer (2) » en vue Actifs,
   « Restaurer (2) » en vue Archive.
5. La confirmation d'une action groupée annonce le nombre :
   > Voulez-vous supprimer 2 étiquette(s) ?
6. **L'unité suit l'objet de l'écran** : « étiquette(s) », « index » (invariable),
   « plan(s) », « type(s) », « groupe(s) », « dossier(s) », « document(s) », et
   **« élément(s) »** pour les règles de workflow.

## 4. Les formulaires

Instructions :

1. Les formulaires s'ouvrent en **fenêtre modale**, sauf le plan d'indexation
   (voir sa fiche).
2. Le bouton de validation s'intitule **« Ajouter »** en création et **« Mettre à
   jour »** en modification.
   *Exception : les règles de workflow utilisent « Créer » et « Modifier ».*
3. Le bouton d'annulation s'intitule **« Annuler »**.
4. Les champs obligatoires portent un **astérisque** après leur libellé.
5. À la validation, si un champ obligatoire est vide :
   - affichez le message d'erreur **sous le champ concerné**,
   - **placez le curseur dans le premier champ fautif**,
   - **ne fermez pas** la fenêtre.
6. L'erreur s'efface dès que l'utilisateur corrige la saisie.
7. Une erreur serveur non interprétée affiche **« Une erreur est survenue. »**.
   N'affichez jamais un message technique.

## 5. Les listes

Instructions :

1. Toute liste est **paginée**, avec le nombre total affiché en pied de tableau.
2. Prévoyez une **recherche** au-dessus de la liste.
3. Une recherche sans résultat affiche **« Aucun résultat »** et **reprend le
   terme saisi** :
   > Aucun document ne correspond à « zzz ».
4. Une liste vide (aucune donnée du tout) affiche un **état vierge** distinct de
   l'état « aucun résultat », avec une invitation à créer le premier élément.
5. Ne confondez jamais ces deux états : l'un dit « vous n'avez rien créé »,
   l'autre dit « votre recherche ne donne rien ».

## 6. Les messages

Instructions :

1. **Nommez toujours l'objet** : « Supprimer cette étiquette », jamais
   « Supprimer l'élément ».
2. Après une action réussie, affichez un message court : « Étiquette créée. »,
   « Document supprimé. »
3. Le message emploie le **libellé complet** de l'objet : « Plan d'indexation
   supprimé. », pas « Plan supprimé. » ; « Type de document supprimé. », pas
   « Type supprimé. ».

## 7. Les icônes

Instructions :

1. Un même geste utilise **la même icône sur tous les écrans** :

| Geste | Icône |
|---|---|
| Modifier | crayon |
| Supprimer | corbeille |
| Restaurer | flèches circulaires |
| Valider / signer | cercle coché |
| Rejeter | cercle croisé |
| Menu d'actions | trois points verticaux |
| Consulter la fiche | œil |

2. Toute icône cliquable porte un **libellé accessible** (`title` et
   `aria-label`). Aucun bouton d'icône ne doit en être dépourvu.

## 8. Ce qu'il ne faut pas faire

1. **N'écrivez rien sur le serveur avant confirmation de l'utilisateur.**
2. **Ne verrouillez jamais la fermeture d'une fenêtre modale** : l'utilisateur
   doit toujours pouvoir sortir par Échap, par « Annuler » ou par le voile.
3. **N'affichez pas deux compteurs du même nombre entretenus séparément.** S'ils
   divergent, l'utilisateur ne sait plus lequel croire. Recalculez-les depuis une
   source unique.
4. **N'employez pas d'emoji** dans l'interface.
