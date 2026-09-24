# 13 — Profil

**Rôle dans le processus :** montrer **ce qui rattache une personne au fonds
documentaire**.

---

## 1. Ce que l'écran fait — et ne fait pas

C'est un écran de **consultation**. Il ne modifie rien.

Il répond à une question : *quel est le lien entre cette personne et les
documents ?* Trois réponses possibles, qui coexistent :

| Lien | Ce qu'il signifie |
|---|---|
| **Propriétaire d'espaces** | elle est responsable de dossiers |
| **Membre de groupes** | elle est rattachée à des périmètres |
| **Activité** | elle a déposé des documents, et traité des signatures |

## 2. La distinction que l'écran doit porter

C'est le point le plus important, et il découle de la règle du produit : **il n'y
a qu'un seul compte, l'administrateur** (voir [00](00-vue-d-ensemble.md) §4).

L'écran doit donc rester lisible pour deux populations très différentes :

| | **L'administrateur** | **Les autres employés** |
|---|---|---|
| A un compte | oui | **non** |
| Se connecte | oui | **non** |
| Adresse, dernière connexion | ont un sens | **n'en ont aucun** |
| Apparaît comme | l'opérateur | propriétaire, approbateur, membre |

Une personne sans compte n'est pas un utilisateur en attente d'activation :
c'est une **donnée**, au même titre qu'un nom de dossier. L'écran porte
explicitement cette information, précisément pour éviter qu'on interprète
l'absence d'adresse comme un dossier incomplet à corriger.

## 3. Ce que l'écran présente

| Bloc | Contenu |
|---|---|
| **Bandeau** | nom, initiales, adresse et dernière connexion — si la personne a un compte |
| **Indicateurs** | documents déposés, signatures en attente, signatures traitées |
| **Espaces possédés** | les dossiers dont la personne est propriétaire |
| **Groupes** | les groupes d'accès qui la rattachent |
| **Décisions** | son activité de signature |

## 4. Comment lire les indicateurs

| Indicateur | Ce qu'il dit |
|---|---|
| **Documents déposés** | le volume d'entrée dont cette personne est à l'origine |
| **Signatures en attente** | **ce qu'elle bloque** — le chiffre le plus actionnable de l'écran |
| **Signatures traitées** | son activité de validation passée |

« Signatures en attente » est le seul chiffre qui appelle une action. Il rejoint
ce qu'affiche l'[accueil](02-accueil.md) et la pastille du menu — et, comme eux,
il doit dire le même nombre.

## 5. En amont et en aval

- **En amont :** la personne existe comme employé, et a été désignée quelque
  part — propriétaire d'un [espace](03-espaces-de-travail.md), approbateur d'une
  [étape](04-regles-de-workflow.md), membre d'un
  [groupe](05-groupe-d-acces.md).
- **En aval :** rien. C'est un écran terminal, de lecture.

## 6. Point d'attention

Cet écran a connu deux régressions d'affichage successives sur la même zone, la
carte d'informations se trouvant rognée. La leçon générale, valable au-delà de
cet écran : **vérifier ce que le navigateur affiche réellement**, et non ce que
la règle censée s'appliquer laisse attendre.
