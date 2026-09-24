# 11 — Dépôt et indexation — instructions

**C'est l'écran le plus important du produit.** À lire en entier avant de le
construire.

## 1. Objectif

Faire entrer un document dans le fonds, et lui attacher ses informations, **en
une seule fenêtre et un seul bouton**.

## 2. Règle fondatrice

> **Le type, le fichier, le réglage d'indexation, les champs indexés, le nom, les
> étiquettes et l'échéance tiennent sur la même page.
> Rien n'est envoyé au serveur tant que « Téléverser » n'a pas été cliqué.**

### Pourquoi — deux défauts à ne pas reproduire

L'écran fonctionnait autrefois en deux étapes, avec une seconde fenêtre
d'indexation. Cela produisait deux défauts :

1. **Les valeurs déduites étaient écrites avant toute confirmation.** Un
   opérateur qui abandonnait laissait un document réellement renommé
   `2026003_2026-02-02_ACME_?`, avec des informations que personne n'avait
   validées.
2. **La seconde fenêtre refusait de se fermer.** La seule sortie était de
   recharger la page.

**Instructions :** ne créez aucun état intermédiaire écrit sur le serveur, et ne
verrouillez jamais la fermeture de la fenêtre.

## 3. Le processus, étape par étape

```
┌─ 1. CHOIX DU TYPE DE DOCUMENT ─────────────────────────────────┐
│  premier geste, obligatoire                                    │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 2. LES CHAMPS D'INDEXATION APPARAISSENT, VIDES ───────────────┐
│  dès le choix du type, AVANT le fichier                        │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 3. CHOIX DU FICHIER ──────────────────────────────────────────┐
│  contrôlé localement, AVANT tout envoi                         │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 4. ANALYSE ───────────────────────────────────────────────────┐
│  les champs se remplissent, le nom se recompose,               │
│  un compteur indique la couverture (« 4/4 »)                   │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 5. AJUSTEMENTS ───────────────────────────────────────────────┐
│  corriger un champ, changer le nom, poser des étiquettes,      │
│  fixer une date d'expiration                                   │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 6. « TÉLÉVERSER » ────────────── le seul geste qui écrit ─────┐
│  dépôt du fichier, puis enregistrement des index dans la       │
│  foulée — rien à confirmer une seconde fois                    │
└────────────────────────────────────────────────────────────────┘
```

**Instruction sur l'étape 2 :** affichez les champs **dès le choix du type**,
avant le fichier. L'opérateur doit savoir immédiatement quelles informations lui
seront demandées. Les afficher après le fichier l'obligerait à découvrir trop
tard qu'il lui manque une donnée.

## 4. Les contrôles sur le fichier

À effectuer **localement, avant tout envoi**. Un fichier refusé ne doit jamais
traverser le réseau.

| Contrôle | Limite | Message à afficher |
|---|---|---|
| Extension | formats déclarés par le type | « Le type « Facture » n'accepte que les fichiers .pdf, .docx — celui-ci est un .png. » |
| Taille | plafond du type, borné par le plafond serveur | « Le fichier pèse 2,5 Go : la limite est de 20 Mo. » |

**Deux instructions sur ces messages :**
1. Dites **ce qui est accepté**, pas seulement ce qui est refusé.
2. Affichez la taille en **unité lisible** — « 2,5 Go », jamais
   « 2662711631 octets ».

## 5. L'indexation automatique

Prévoyez un **interrupteur** qui décide qui remplit les champs.

| Position | Comportement attendu |
|---|---|
| **Active** | l'application analyse et **propose** des valeurs |
| **Coupée** | les champs restent vides, provenance « saisie manuelle », compteur `0/N` |

Pour chaque champ, indiquez **d'où vient la valeur** :

| Provenance | Signification |
|---|---|
| Nom du fichier | déduite en découpant le nom selon la charte |
| Contenu | lue dans le document (couche texte ou reconnaissance de caractères) |
| Non reconnue | **dites pourquoi** l'application n'a pas trouvé |

Affichez un compteur « reconnus / attendus » (« 4/4 »).

## 6. Règles à respecter

1. **Rien n'est écrit avant le clic final.** Règle non négociable.
2. **La saisie manuelle prime toujours.** Dès que l'opérateur tape dans un champ,
   ce champ lui appartient : il ne doit **plus jamais** être écrasé par une
   proposition — ni par une nouvelle analyse, ni par le basculement de
   l'interrupteur, ni par un changement de fichier.

   > **Erreur à ne pas reproduire :** ne vous fondez pas sur la *provenance* de
   > la valeur pour décider si un champ appartient à l'utilisateur. La provenance
   > décrit la proposition du serveur et ne change pas quand l'opérateur tape.
   > **Marquez explicitement les champs touchés à la main.**

3. **Annulez l'analyse en cours** quand l'opérateur retire ou change le fichier.
   Sans annulation, la réponse précédente arrive après coup et **repeuple des
   champs qui ne correspondent plus à rien** : l'opérateur voit apparaître les
   données d'un fichier abandonné.

4. **Recomposez le nom en direct** à partir des valeurs, du séparateur et de la
   casse du plan.

5. **Bloquez la validation tant qu'un champ obligatoire est vide**, et rendez le
   blocage **visible et expliqué** : affichez le décompte (« 1 champ(s) ») et
   faites retomber la référence composée à « — ». Un bouton inactif sans raison
   affichée est le pire des refus.

6. **Ne verrouillez pas la fermeture de la fenêtre** : Échap, « Annuler » et le
   voile doivent tous permettre de sortir.

## 7. Les trois cas où le nom n'est pas composé

| Cas | Comportement attendu |
|---|---|
| Plan **manuel** | le nom reste saisissable, aucune recomposition |
| Type **sans plan** | aucune table de champs ; dites que le document sera « déposé sans aucun index » |
| Charte **non redécoupable** | renoncez à recomposer, laissez « Nom du document » vide |

Le troisième cas est le **piège du séparateur** (voir fiche 07 §6 règle 3).

## 8. Les issues du dépôt

Le dépôt doit se terminer dans l'un de ces trois états :

| Issue | Signification |
|---|---|
| **Indexé** | document déposé et index enregistrés |
| **Sans plan** | document déposé, aucun index à saisir |
| **À indexer** | document déposé, indexation restant à faire |

## 9. Formulaire — récapitulatif des champs

| Champ | Obligatoire | Remarque |
|---|---|---|
| Type de document | **oui** | premier geste |
| Fichier | **oui** | contrôlé localement |
| Indexation automatique | — | interrupteur, actif par défaut |
| Champs d'indexation | **selon le paramétrage** | issus du plan du type |
| Nom du document | selon le mode | recomposé, ou saisissable |
| Étiquettes | non | sélection multiple libre |
| Date d'expiration | non | |

## 10. Messages à afficher

| Situation | Message exact |
|---|---|
| Extension refusée | « Le type « X » n'accepte que les fichiers .pdf, .docx — celui-ci est un .png. » |
| Fichier trop lourd | « Le fichier pèse 2,5 Go : la limite est de 20 Mo. » |
| Champ obligatoire vide | décompte « 1 champ(s) » + blocage du bouton |
| Type sans plan | « déposé sans aucun index » |
| Dépôt réussi | « Document déposé. » |

## 11. Dépendances

- **En amont :** toute la chaîne — index (06), plan (07), type (08),
  espace (03), règle de workflow (04).
- **En aval :** le document rejoint le fonds (10) et son circuit de validation
  démarre dans Mes workflow (12).

## 12. Limite connue à documenter

La reconnaissance de caractères (OCR) n'a jamais été validée de bout en bout sur
un poste réel. En son absence, l'analyse se rabat sur le nom du fichier.
