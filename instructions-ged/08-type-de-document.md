# 08 — Types de document — instructions

## 1. Objectif

**C'est la pièce qui relie tout.** Le type permet à l'opérateur de ne rien
décider au dépôt.

```
              TYPE DE DOCUMENT « Facture »
                        │
     ┌──────────┬───────┴────────┬──────────────┐
     ▼          ▼                ▼              ▼
  Espace de   Plan          Formats de     Taille
  travail     d'indexation  fichier        maximale
     │          │                │              │
  où ranger   quelles infos   ce qui est    ce qui est
  + quel      + quel nom      accepté       refusé
  circuit
```

Un opérateur qui choisit « Facture » a, sans le savoir, choisi le dossier, le
plan de nommage, le circuit de validation et les formats acceptés.

## 2. Ce que l'écran doit afficher

### Colonnes du tableau

`Code` · `Type de document` · `Description` · `Espace de travail` ·
`Plan d'indexation` · `Types autorisés` · `Taille max` · `Actions`

### Fiche d'un type

Ordre imposé : **Code**, **Espace de travail**, **Types autorisés**, **Taille
maximale**, **Plan d'indexation**, **Description**.

**Instruction :** un type sans plan affiche « Aucun » dans la fiche, et
**« — »** dans la colonne du tableau.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| Créer | ouvrir le formulaire, taille pré-remplie à 10 |
| Saisir une taille < 5 | refuser, avec le message dédié |
| Choisir « — Aucun — » comme plan | accepter — cas valide (voir §7) |
| Modifier | ouvrir le formulaire pré-rempli |
| Consulter la fiche | ouvrir le détail |
| Supprimer | confirmation nommant le type |

## 4. Formulaire

| Champ | Type | Obligatoire | Défaut |
|---|---|---|---|
| Code | texte | **oui** | vide |
| Type de document | texte | **oui** | vide |
| **Description** | texte long | **oui** | vide |
| Espace de travail | liste | **oui** | — |
| Plan d'indexation | liste | **non** | « — Aucun — » |
| Types de fichier | cases à cocher multiples | **oui** | aucun |
| Taille max (Mo) | nombre | **oui** | 10, **minimum 5** |

Formats proposés : `pdf`, `docx`, `doc`, `xlsx`, `xls`.

## 5. Règles à respecter

1. **La description est obligatoire ici**, contrairement aux autres écrans.
   Raison : le type est **la seule chose que l'opérateur choisit au dépôt**. Un
   intitulé ambigu (« Divers », « Document 2 ») produit un classement faux à
   chaque dépôt, et l'erreur se répète indéfiniment.
2. **Taille minimum : 5 Mo.** En dessous, la plupart des PDF scannés seraient
   refusés et le paramétrage deviendrait une gêne quotidienne.
3. **Au moins un format doit être coché.** Un type qui n'accepte aucun fichier ne
   sert à rien.
4. **Un type sans plan d'indexation est valide** (voir §7).
5. **Le type doit transmettre au dépôt** : les formats acceptés, la taille
   maximale, le plan d'indexation, et le caractère automatique ou manuel de la
   charte. Ces informations doivent être disponibles **sans appel
   supplémentaire**, pour que le contrôle du fichier se fasse localement.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Taille < 5 | « 5 Mo minimum » |
| Description vide | « La description est obligatoire » |
| Aucun format coché | « Le type est obligatoire » |
| Code vide | « Le code est obligatoire » |
| Création | « Type de document créé. » |
| Suppression | « Type de document supprimé. » |
| Suppression en lot | « 2 type(s) supprimé(s). » |

**Attention :** libellé complet « Type de document supprimé. », jamais « Type
supprimé. ».

## 7. Cas particulier — le type sans plan d'indexation

Cas **valide et fréquent** : un compte rendu de réunion n'a aucune information
structurée à porter.

**Instructions pour l'écran de dépôt :**

1. N'affichez **aucune table de champs**.
2. Ne composez **aucun nom** : gardez celui du fichier, ou celui saisi.
3. **Dites-le explicitement** à l'opérateur : le document sera « déposé sans
   aucun index ». Ne le laissez pas chercher des champs qui n'existent pas.

## 8. Dépendances

- **En amont :** un espace de travail (03) et — sauf cas du §7 — un plan
  d'indexation (07).
- **En aval :** le type est le **premier choix** du dépôt (11), et une colonne de
  la liste des documents (10).
