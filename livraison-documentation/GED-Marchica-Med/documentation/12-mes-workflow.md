# 12 — Mes workflow (signatures)

**Rôle dans le processus :** c'est ici que se joue **le seul geste engageant du
produit** — donner ou refuser un visa.

---

## 1. Ce qui distingue cet écran de tous les autres

Partout ailleurs, on paramètre, on range, on consulte. Ici, on **décide**, et la
décision engage : elle bloque ou débloque le travail de quelqu'un d'autre.

Cela justifie trois exigences qu'on ne trouve nulle part ailleurs dans
l'application :

1. **Toute décision est confirmée**, et la confirmation **nomme le document et
   l'étape**.
2. **Un refus doit être motivé** — le motif est obligatoire.
3. **Une décision reste consultable** : rien ne disparaît, tout passe à
   l'historique.

## 2. Les deux onglets

| Onglet | Contenu | Nature |
|---|---|---|
| **À traiter** | les étapes qui attendent une décision | une **file de travail** |
| **Historique** | les décisions déjà prises | une **trace** |

## 3. Le processus complet

```
                    ┌──────────────────┐
                    │    À TRAITER     │
                    └────────┬─────────┘
              ┌──────────────┴──────────────┐
              ▼                             ▼
        « Signer »                     « Rejeter »
              │                             │
   confirmation nommant             MOTIF OBLIGATOIRE
   document + étape                 (sans lui, refus impossible)
   « (étape 2 · Validation                  │
     comptable) »                           │
              │                             │
              ▼                             ▼
      l'étape est visée              le circuit s'ARRÊTE
              │                             │
   étape suivante ouverte           le motif est CONSERVÉ
              │                             │
              └──────────────┬──────────────┘
                             ▼
                    ┌──────────────────┐
                    │   HISTORIQUE     │
                    └────────┬─────────┘
                             │
                  statut REJETÉ uniquement
                             │
                             ▼
                      « Relancer »
                             │
                             ▼
        l'étape refusée revient dans « À traiter »
        les étapes DÉJÀ SIGNÉES le restent
```

## 4. Pourquoi le motif est obligatoire

Un refus sans raison renvoie le déposant à ses suppositions : il ne sait ni quoi
corriger, ni s'il doit recommencer, ni à qui demander. Le motif transforme un
blocage en instruction.

Il est **conservé** et réaffiché dans la colonne « Motif » de l'historique — y
compris après une relance, pour que la trace du premier refus ne se perde pas.

Symétriquement, **une signature accordée n'a rien à justifier** : la cellule
affiche un tiret. Un tiret se lit comme « sans objet » ; une case vide se lirait
comme « donnée manquante ».

## 5. La relance : un rejet n'est pas définitif

C'est la règle la plus importante du module, et la moins évidente.

Quand une étape est refusée, le circuit s'arrête. Mais le document n'est pas
mort : depuis l'historique, l'action **« Relancer »** le remet en marche.

Ce que la relance fait exactement :

| Étape | Ce qui lui arrive |
|---|---|
| Étape **refusée** | revient en attente de décision |
| Étapes **déjà signées** | **restent signées** — elles ne sont pas rejouées |
| Motif d'origine | conservé |

**Pourquoi les étapes signées ne sont pas rejouées :** faire re-signer quelqu'un
qui a déjà donné son accord est une perte de temps, et surtout un affaiblissement
de la valeur du visa — si signer ne veut pas dire signer définitivement, la
signature ne vaut plus rien.

Le message de confirmation le dit explicitement, pour que personne ne redoute de
tout recommencer :

> Le circuit de « Procès-verbal 12-06.pdf » est arrêté par un rejet.
> Le relancer remet l'étape refusée à traiter ; les étapes déjà signées le
> restent.

## 6. Les décisions en lot

On peut cocher plusieurs lignes et décider d'un coup. La confirmation **liste les
documents concernés** — au plus cinq noms, puis « … et N autre(s) » :

```
   Confirmez-vous la signature de :
   • Facture circuit.pdf
   • Facture janvier.pdf
   • Convention formation.pdf
```

**Pourquoi lister plutôt que compter :** « Confirmez-vous la signature de 3
documents ? » ne permet pas de vérifier qu'on n'a pas coché une ligne de trop. Le
plafond à cinq noms évite la boîte de dialogue interminable sur une sélection
massive.

## 7. La règle des compteurs

Le même nombre est affiché à trois endroits : la pastille de l'onglet, **la
pastille du menu latéral**, et le pied du tableau.

Ils sont tous recalculés à partir de la même source, à la même seconde. Ce n'est
pas de la coquetterie : un badge figé à 32 quand la liste en montre 31 fait
douter de l'application entière — l'utilisateur ne sait plus quel chiffre croire,
et un chiffre auquel on ne croit pas ne sert à rien.

Ce défaut s'est déjà produit. La règle est à préserver.

## 8. La recherche

Une recherche infructueuse ne se contente pas d'afficher une liste vide : elle
**reprend le terme saisi**.

- « Aucun document à traiter ne correspond à « zzz ». »
- « Aucune décision ne correspond à « zzz ». »

C'est ce qui permet de distinguer « il n'y a rien » de « je me suis trompé de
mot ».

## 9. En amont et en aval

- **En amont :** un document a été [déposé](11-depot-et-indexation.md) dans un
  [espace](03-espaces-de-travail.md) portant une
  [règle de workflow](04-regles-de-workflow.md).
- **En aval :** une fois toutes les étapes signées, le document poursuit sa vie
  dans le [fonds documentaire](10-documents.md). Les décisions prises depuis
  l'[accueil](02-accueil.md) ont exactement le même effet que celles prises ici.
