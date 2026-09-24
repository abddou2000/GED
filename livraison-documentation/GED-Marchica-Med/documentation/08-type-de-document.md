# 08 — Types de document

**Rôle dans le processus :** **la pièce qui relie tout.** C'est le type qui
permet à l'opérateur de ne rien décider au dépôt.

---

## 1. Pourquoi cet écran est le pivot

Au dépôt, on ne demande pas où ranger le fichier, comment le nommer, ni qui doit
le signer. On demande **de quel type de document il s'agit**. Le type porte
toutes les réponses :

```
              TYPE DE DOCUMENT « Facture »
                        │
     ┌──────────┬───────┴────────┬──────────────┐
     ▼          ▼                ▼              ▼
  Espace de   Plan          Formats de     Taille
  travail     d'indexation  fichier        maximale
     │          │                │              │
     ▼          ▼                ▼              ▼
  où ranger   quelles infos   ce qui est    ce qui est
  + quel      + quel nom      accepté       refusé
  circuit     composé
```

Un opérateur qui choisit « Facture » a, sans le savoir, choisi le dossier
« Comptabilité », le plan de nommage, le circuit de validation comptable, et
accepté que seuls des PDF de moins de 20 Mo passent.

**C'est là que se joue la promesse du produit : classer sans y penser.**

## 2. Ce que l'on renseigne

| Information | Obligatoire | Ce qu'elle décide |
|---|---|---|
| Code | oui | identifiant fonctionnel |
| Type de document | oui | le libellé choisi au dépôt |
| **Description** | **oui** | contrairement aux autres écrans — voir §3 |
| **Espace de travail** | oui | où le document sera rangé, donc **quel circuit le validera** |
| Plan d'indexation | **non** | quelles informations et quel nom — « — Aucun — » possible |
| **Types de fichier** | oui | les extensions acceptées : pdf, docx, doc, xlsx, xls |
| **Taille max (Mo)** | oui | plafond, **minimum 5**, défaut 10 |

## 3. Pourquoi la description est obligatoire ici

Sur tous les autres écrans, la description est facultative. Ici elle ne l'est
pas, et c'est délibéré : le type est **la seule chose que l'opérateur choisit au
dépôt**. Un intitulé ambigu (« Divers », « Document 2 ») produit un classement
faux à chaque dépôt, et l'erreur se répète indéfiniment. La description force à
expliciter ce que le type recouvre.

## 4. Le cas du type sans plan d'indexation

C'est un cas **valide et fréquent**. Un compte rendu de réunion, par exemple, n'a
aucune information structurée à porter.

| Conséquence | Détail |
|---|---|
| Aucun champ à remplir | l'écran de dépôt n'affiche aucune table d'index |
| Aucun nom composé | le nom reste celui du fichier, ou celui saisi |
| Le document est déposé | **sans aucun index** — l'écran le dit explicitement |

L'application ne considère pas cela comme une anomalie. Elle le **dit**, pour
que l'opérateur ne cherche pas des champs qui n'existent pas.

## 5. Les règles de refus

| Situation | Message | Pourquoi |
|---|---|---|
| Taille inférieure à 5 Mo | « 5 Mo minimum » | en dessous, la plupart des PDF scannés seraient refusés — le paramétrage deviendrait une gêne quotidienne |
| Description vide | « La description est obligatoire » | voir §3 |
| Aucun format coché | « Le type est obligatoire » | un type qui n'accepte aucun fichier ne sert à rien |

## 6. Ce que le type transmet au dépôt

Le type ne se contente pas d'être choisi : il **arme les contrôles** de l'écran
de dépôt.

| Ce qu'il transmet | Ce que le dépôt en fait |
|---|---|
| Formats acceptés | refuse un fichier d'extension non listée, en nommant les extensions permises |
| Taille maximale | refuse un fichier trop lourd, en affichant son poids réel |
| Plan d'indexation | affiche les champs, déduit les valeurs, compose le nom |
| Charte automatique ou manuelle | rend le nom saisissable, ou non |

Ces contrôles sont faits **avant tout envoi** : un fichier refusé ne part jamais
sur le réseau.

## 7. En amont et en aval

- **En amont :** il faut un [espace de travail](03-espaces-de-travail.md), et —
  sauf cas du §4 — un [plan d'indexation](07-plan-indexation.md).
- **En aval :** le type est le premier choix du
  [dépôt](11-depot-et-indexation.md), et une colonne de la liste des
  [documents](10-documents.md).

## 8. Message

Suppression : « Type de document supprimé. » — libellé complet, pas « Type
supprimé. »
