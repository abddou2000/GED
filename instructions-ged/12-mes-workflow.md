# 12 — Mes workflow (signatures) — instructions

## 1. Objectif

C'est ici que se joue **le seul geste engageant du produit** : donner ou refuser
un visa.

Partout ailleurs on paramètre, on range, on consulte. Ici on **décide**, et la
décision bloque ou débloque le travail de quelqu'un d'autre.

## 2. Trois exigences propres à cet écran

À appliquer ici et nulle part ailleurs avec cette rigueur :

1. **Toute décision est confirmée**, et la confirmation **nomme le document et
   l'étape**.
2. **Un refus doit être motivé** — le motif est obligatoire.
3. **Rien ne disparaît** : toute décision passe à l'historique.

## 3. Ce que l'écran doit afficher

Deux onglets :

| Onglet | Contenu | Nature |
|---|---|---|
| **À traiter** | les étapes qui attendent une décision | une file de travail |
| **Historique** | les décisions déjà prises | une trace |

### Colonnes « À traiter »

`Document` · `Type` · `Espace de travail` · `Étape` · `Actions`

### Colonnes « Historique »

`Document` · `Type` · `Étape` · `Statut` · `Date` · `Motif` · `Actions`

**Instruction :** l'onglet « À traiter » porte une **pastille** avec le nombre
d'étapes en attente.

## 4. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| « Signer » | confirmation **nommant document et étape**, puis validation |
| « Rejeter » | saisie d'un **motif obligatoire**, puis arrêt du circuit |
| Après une décision | la ligne **quitte « À traiter »** et **paraît dans « Historique »** |
| « Relancer » (historique, statut Rejeté uniquement) | remettre l'étape refusée à traiter |
| Signer en lot | confirmation **listant les documents** |
| Rejeter en lot | motif obligatoire, confirmation listant les documents |
| Après toute action | **mettre à jour les trois compteurs** |

## 5. Le processus complet

```
                    ┌──────────────────┐
                    │    À TRAITER     │
                    └────────┬─────────┘
              ┌──────────────┴──────────────┐
              ▼                             ▼
        « Signer »                     « Rejeter »
              │                             │
   confirmation nommant             MOTIF OBLIGATOIRE
   document + étape                        │
              ▼                             ▼
      l'étape est visée              le circuit s'ARRÊTE
      étape suivante ouverte         le motif est CONSERVÉ
              │                             │
              └──────────────┬──────────────┘
                             ▼
                    ┌──────────────────┐
                    │   HISTORIQUE     │
                    └────────┬─────────┘
                  statut REJETÉ uniquement
                             ▼
                      « Relancer »
                             ▼
        l'étape refusée revient dans « À traiter »
        les étapes DÉJÀ SIGNÉES le restent
```

## 6. Règles à respecter

1. **Le motif est obligatoire au rejet.** Un refus sans raison renvoie le
   déposant à ses suppositions : il ne saura ni quoi corriger, ni s'il doit
   recommencer. Le motif transforme un blocage en instruction.

2. **Conservez le motif** et réaffichez-le dans la colonne « Motif » de
   l'historique — **y compris après une relance**, pour que la trace du premier
   refus ne se perde pas.

3. **Une signature accordée n'a rien à justifier** : affichez un **tiret** dans
   la colonne Motif. Un tiret se lit « sans objet » ; une case vide se lirait
   « donnée manquante ».

4. **La relance ne rejoue pas les étapes déjà signées.** Seule l'étape refusée
   revient en attente.
   **Pourquoi :** faire re-signer quelqu'un qui a déjà donné son accord est une
   perte de temps et un affaiblissement du visa — si signer ne veut pas dire
   signer définitivement, la signature ne vaut plus rien.

5. **Les décisions en lot listent les documents**, elles ne les comptent pas.
   « Confirmez-vous la signature de 3 documents ? » ne permet pas de vérifier
   qu'on n'a pas coché une ligne de trop.
   **Plafonnez la liste à 5 noms**, puis « … et N autre(s) ».

6. **Les trois compteurs doivent bouger ensemble** : la pastille de l'onglet, la
   pastille du menu latéral, le pied du tableau. Recalculez-les depuis **une
   seule source**, à la même seconde.

7. **Une recherche infructueuse reprend le terme saisi** — cela distingue « il
   n'y a rien » de « je me suis trompé de mot ».

8. Une décision prise depuis l'accueil doit avoir **exactement le même effet**
   qu'une décision prise ici.

## 7. Messages à afficher

| Situation | Message exact |
|---|---|
| Confirmation de signature (une ligne) | « Confirmez-vous la signature de « Facture circuit.pdf » (étape 2 · Validation comptable) ? » |
| Confirmation de signature (lot) | « Confirmez-vous la signature de : » + liste à puces |
| Demande de motif | « Indiquez le motif du rejet de : » + liste |
| Motif vide | « Ce champ est requis. » |
| Signature accordée | « Document signé. » / « 3 document(s) signé(s). » |
| Signature refusée | « Signature rejetée. » / « 3 document(s) rejeté(s). » |
| Confirmation de relance | « Le circuit de « Procès-verbal 12-06.pdf » est arrêté par un rejet. Le relancer remet l'étape refusée à traiter ; les étapes déjà signées le restent. » |
| Relance effectuée | « Circuit relancé. » |
| Recherche infructueuse (à traiter) | « Aucun résultat » / « Aucun document à traiter ne correspond à « zzz ». » |
| Recherche infructueuse (historique) | « Aucune décision ne correspond à « zzz ». » |

## 8. Dépendances

- **En amont :** un document déposé (11) dans un espace (03) portant une règle de
  workflow (04).
- **En aval :** une fois toutes les étapes signées, le document poursuit sa vie
  dans le fonds documentaire (10).
