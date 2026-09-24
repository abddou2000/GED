# 11 — Dépôt et indexation

**Rôle dans le processus : le cœur du produit.** C'est ici que tout le
paramétrage se transforme en résultat.

À lire en entier. C'est l'écran le plus dense et celui dont les règles ont le
plus de conséquences.

---

## 1. Le principe : une seule fenêtre, un seul bouton

Le type, le fichier, le réglage d'indexation, les champs indexés, le nom, les
étiquettes et l'échéance tiennent sur **la même page**.

**Rien n'est envoyé au serveur tant que « Téléverser » n'a pas été cliqué.**

### Pourquoi ce n'est plus en deux étapes

L'écran enchaînait autrefois sur une seconde fenêtre d'indexation. Les deux
défauts que cela produisait méritent d'être connus, parce qu'ils expliquent toute
la conception actuelle :

1. **Les valeurs déduites étaient écrites avant toute confirmation.** Un
   opérateur qui abandonnait en cours de route laissait derrière lui un document
   réellement renommé `2026003_2026-02-02_ACME_?`, avec des informations que
   personne n'avait validées. Le système gardait la trace d'une décision qui
   n'avait pas été prise.
2. **La seconde fenêtre refusait de se fermer.** La seule sortie était de
   recharger la page — c'est-à-dire d'abandonner sans savoir dans quel état on
   laissait le document.

Une page unique supprime les deux d'un coup : il n'y a plus d'état intermédiaire
à écrire, ni de raison de retenir l'opérateur.

**Règle de conception à préserver : rien ne doit être écrit avant le clic
final.**

## 2. Le processus, étape par étape

```
┌─ 1. CHOIX DU TYPE DE DOCUMENT ─────────────────────────────────┐
│  premier geste, obligatoire                                    │
│  le type apporte : espace, plan d'indexation, formats          │
│  acceptés, taille maximale, charte auto ou manuelle            │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 2. LES CHAMPS D'INDEXATION APPARAISSENT, VIDES ───────────────┐
│  dès le choix du type, avant même le fichier                   │
│  → l'opérateur voit tout de suite ce qu'on lui demandera       │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 3. CHOIX DU FICHIER ──────────────────────────────────────────┐
│  contrôlé AVANT tout envoi :                                   │
│   • extension non autorisée ─► refus nommant les formats admis │
│   • fichier trop lourd      ─► refus indiquant son poids réel  │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 4. ANALYSE ───────────────────────────────────────────────────┐
│  les champs SE REMPLISSENT                                     │
│  le nom se recompose en direct selon la charte                 │
│  un compteur indique la couverture : « 4/4 »                   │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 5. AJUSTEMENTS ───────────────────────────────────────────────┐
│  corriger un champ, changer le nom, poser des étiquettes,      │
│  fixer une date d'expiration                                   │
└────────────────────────────┬───────────────────────────────────┘
                             ▼
┌─ 6. « TÉLÉVERSER » ────────────── le seul geste qui écrit ─────┐
│  le fichier est déposé, puis les valeurs indexées enregistrées │
│  dans la foulée — rien à confirmer une seconde fois            │
└────────────────────────────────────────────────────────────────┘
```

**Le point d'ergonomie de l'étape 2** : afficher les champs dès le choix du type,
avant le fichier, n'est pas un détail. L'opérateur sait immédiatement s'il a en
main les informations nécessaires. Les afficher seulement après le fichier
l'obligerait à découvrir trop tard qu'il lui manque une donnée.

## 3. Les deux contrôles sur le fichier

| Contrôle | D'où vient la limite | Message |
|---|---|---|
| Extension | formats déclarés par le type | « Le type « Facture » n'accepte que les fichiers .pdf, .docx — celui-ci est un .png. » |
| Taille | plafond du type, borné par le plafond serveur | « Le fichier pèse 2,5 Go : la limite est de 20 Mo. » |

Deux principes dans ces messages : ils disent **ce qui est accepté**, pas
seulement ce qui est refusé ; et ils affichent la taille en unité lisible —
« 2,5 Go », jamais « 2662711631 octets ».

Ces refus sont calculés **localement**, avant tout envoi : un fichier refusé ne
traverse jamais le réseau.

## 4. L'indexation automatique

Un interrupteur décide qui remplit les champs.

| Position | Comportement |
|---|---|
| **Active** | l'application analyse le document et **propose** des valeurs |
| **Coupée** | les champs restent vides, la provenance affichée devient « saisie manuelle », le compteur tombe à `0/N` |

L'interrupteur existe parce que l'automatisme n'est pas toujours souhaitable : un
lot de documents hors norme se saisit plus vite à la main que corrigé un à un.

### D'où viennent les valeurs proposées

| Provenance | Ce que cela signifie |
|---|---|
| **Nom du fichier** | la valeur a été déduite en découpant le nom selon la charte |
| **Contenu** | la valeur a été lue dans le document (couche texte, ou reconnaissance de caractères) |
| **Non reconnue** | l'application dit **pourquoi** elle n'a pas trouvé |

Le compteur « reconnus / attendus » (« 4/4 ») résume la couverture d'un coup
d'œil.

## 5. La règle absolue : la saisie manuelle prime

**Dès que l'opérateur tape dans un champ, ce champ lui appartient.** Il ne sera
plus jamais écrasé par une proposition, même si l'analyse est relancée, même si
l'interrupteur d'indexation est basculé, même si le fichier est remplacé.

> **Erreur déjà commise, et instructive.** La première version décidait qu'un
> champ était « à l'utilisateur » en regardant la provenance de la valeur. Mais
> la provenance décrit la **proposition du serveur** — elle ne change jamais
> quand l'opérateur tape. Résultat : les saisies manuelles étaient effacées au
> moindre basculement de l'interrupteur. Il a fallu marquer explicitement les
> champs touchés à la main.

## 6. La recomposition du nom

Le nom se recompose **en direct** à partir des valeurs indexées, du séparateur et
de la casse du plan.

Exemple : `2026999_2026-06-22_Nova Import_Urgente`

Trois cas où l'automatisme ne joue pas :

| Cas | Comportement |
|---|---|
| Plan **manuel** | le nom reste saisissable, aucune recomposition |
| Type **sans plan** | aucune table de champs ; le document est « déposé sans aucun index », et l'écran le dit |
| Charte **non redécoupable** | la GED **renonce** à recomposer, le champ « Nom du document » reste vide |

Le troisième cas est le **piège du séparateur** décrit en
[07](07-plan-indexation.md) §5 : un plan qui utilise `-` avec une date ISO ne
peut plus être redécoupé.

## 7. Les champs obligatoires

Un index déclaré « Obligatoire » ([06](06-index.md)) doit être renseigné. Tant
qu'il en reste un vide :

- le bouton « Téléverser » est **bloqué** ;
- l'écran affiche le décompte (« 1 champ(s) ») ;
- la référence composée retombe à « — ».

Le blocage est **visible et expliqué**, jamais silencieux : un bouton inactif
sans raison affichée est le pire des refus.

## 8. Une subtilité qui évite un bug de confusion

Quand l'opérateur retire un fichier ou en choisit un autre, l'analyse en cours
est **annulée**, pas seulement ignorée.

Sans cette annulation, la réponse de l'analyse précédente arriverait après coup
et **repeuplerait des champs qui ne correspondent plus à rien** — l'opérateur
verrait apparaître les données d'un fichier qu'il vient d'abandonner, sans
comprendre d'où elles viennent.

## 9. Les issues du dépôt

Le dépôt se termine dans l'un de trois états, et l'écran suivant en tient compte :

| Issue | Signification |
|---|---|
| **Indexé** | document déposé et index enregistrés |
| **Sans plan** | document déposé, aucun index à saisir (type sans plan) |
| **À indexer** | document déposé, indexation restant à faire |

## 10. En amont et en aval

- **En amont :** toute la chaîne de paramétrage — [index](06-index.md),
  [plan](07-plan-indexation.md), [type](08-type-de-document.md),
  [espace](03-espaces-de-travail.md), [règle de workflow](04-regles-de-workflow.md).
- **En aval :** le document rejoint le [fonds](10-documents.md), et son circuit
  de validation démarre dans [Mes workflow](12-mes-workflow.md).

## 11. Limite connue

La reconnaissance de caractères (OCR) **n'a jamais été validée de bout en bout**
sur un poste réel. En son absence, l'analyse se rabat sur le nom du fichier.
