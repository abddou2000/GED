# 13 — Profil — instructions

## 1. Objectif

Montrer **ce qui rattache une personne au fonds documentaire**.

C'est un écran de **consultation**. Il ne modifie rien.

## 2. Ce que l'écran doit afficher

| Bloc | Contenu |
|---|---|
| **Bandeau** | nom, initiales, adresse, dernière connexion — **si la personne a un compte** |
| **Indicateurs** | documents déposés, signatures en attente, signatures traitées |
| **Espaces possédés** | les dossiers dont la personne est propriétaire |
| **Groupes** | les groupes d'accès qui la rattachent |
| **Décisions** | son activité de signature |

## 3. Actions et comportements attendus

Aucune action d'écriture. L'écran est en lecture seule.

| Geste | Comportement attendu |
|---|---|
| Clic sur un espace possédé | ouvrir cet espace |
| Clic sur un groupe | ouvrir ce groupe |

## 4. Règle fondamentale — deux populations à distinguer

L'application ne compte qu'un seul compte, l'administrateur. L'écran doit rester
lisible pour deux populations très différentes :

| | **L'administrateur** | **Les autres employés** |
|---|---|---|
| A un compte | oui | **non** |
| Se connecte | oui | **non** |
| Adresse, dernière connexion | ont un sens | **n'en ont aucun** |
| Apparaît comme | l'opérateur | propriétaire, approbateur, membre |

**Instructions :**

1. Prévoyez une information explicite indiquant si la personne a un compte.
2. Quand elle n'en a pas, **n'affichez pas de champs vides** pour l'adresse et la
   dernière connexion : masquez-les ou indiquez qu'ils sont sans objet.
3. **Ne présentez jamais un employé sans compte comme un dossier incomplet à
   corriger.** C'est une donnée, au même titre qu'un nom de dossier.

## 5. Règles à respecter

1. **Aucune action d'écriture** sur cet écran.
2. **« Signatures en attente » est le seul chiffre actionnable** de l'écran :
   c'est ce que la personne bloque. Mettez-le en avant.
3. Ce chiffre doit dire **le même nombre** que l'accueil et que la pastille du
   menu (voir [00](00-regles-communes.md) §8, règle 3).
4. L'écran doit tenir sans débordement sur un affichage courant. Prévoyez que le
   bandeau se compacte plutôt que d'écraser les blocs du dessous.

## 6. Comment lire les indicateurs

| Indicateur | Ce qu'il dit |
|---|---|
| Documents déposés | le volume d'entrée dont cette personne est à l'origine |
| **Signatures en attente** | **ce qu'elle bloque** |
| Signatures traitées | son activité de validation passée |

## 7. Cas particuliers

Cet écran a connu deux régressions d'affichage successives, la carte
d'informations se trouvant rognée par la mise en page.

**Instruction générale :** vérifiez ce que le navigateur **affiche réellement**,
et non ce que la règle censée s'appliquer laisse attendre.

## 8. Dépendances

- **En amont :** la personne existe comme employé et a été désignée quelque
  part — propriétaire d'un espace (03), approbateur d'une étape (04), membre
  d'un groupe (05).
- **En aval :** aucune. C'est un écran terminal.
