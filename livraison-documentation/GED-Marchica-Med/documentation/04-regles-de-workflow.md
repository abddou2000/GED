# 04 — Règles de workflow

**Rôle dans le processus :** définir **qui valide un document, dans quel
ordre**.

---

## 1. La notion

Une règle de workflow est un **circuit de validation** : une suite **ordonnée**
d'étapes, chacune confiée à un approbateur.

Le point à comprendre en premier : **une règle ne s'applique jamais directement
à un document.** Elle est rattachée à un [espace de travail](03-espaces-de-travail.md),
et c'est l'espace qui l'impose aux documents qu'il contient.

```
   Règle « Validation comptable »
        étape 1 — Commission
        étape 2 — Direction
              │
              │  rattachée à
              ▼
   Espace « Comptabilité »
              │
              │  s'applique à
              ▼
   tout document déposé dans cet espace
```

Cette indirection est délibérée. Elle évite qu'un opérateur choisisse un circuit
au moment du dépôt — donc qu'il se trompe, ou qu'il contourne.

## 2. Ce que l'on renseigne

| Information | Obligatoire | Rôle |
|---|---|---|
| Nom de la règle | oui | ce qu'on lit partout ailleurs |
| **Étapes du circuit** | **au moins une** | la suite ordonnée |

Chaque étape porte :

| Sous-information | Obligatoire | Rôle |
|---|---|---|
| Approbateur | oui | l'employé qui vise cette étape |
| Libellé de l'étape | oui (255 car. max) | ce que l'étape signifie : « Visa comptable » |
| Rang | calculé | la position dans l'ordre |

## 3. L'ordre est une donnée, pas une présentation

Les étapes se réordonnent par glisser-déposer, et **le rang est renuméroté à
chaque changement**. L'application ne laisse jamais deux étapes porter le même
rang.

C'est essentiel : tout le processus de signature repose sur cet ordre. Une étape
2 ne s'ouvre que lorsque l'étape 1 est signée. Un ordre ambigu produirait un
circuit indéterminé.

## 4. Les règles de refus

| Situation | Message | Pourquoi |
|---|---|---|
| Nom vide | « Le nom est obligatoire » | la règle est citée dans les espaces, elle doit être reconnaissable |
| Aucune étape | « Au moins une étape est requise. » | un circuit sans étape ne valide rien ; il donnerait des documents éternellement « en attente » |

## 5. Ce que l'écran montre en lecture seule

La colonne « Espaces de travail » liste les espaces qui utilisent la règle. **On
ne peut pas rattacher un espace depuis ici** : le rattachement se fait depuis
l'espace.

Le sens de lecture est donc : *cette règle sert à ces dossiers-là*. C'est une
information de conséquence, pas un point de commande.

## 6. Effet d'une modification sur les circuits en cours

Point à connaître avant de modifier une règle utilisée : les documents dont le
circuit est **déjà lancé** portent leurs propres étapes. Modifier la règle ne les
rejoue pas rétroactivement.

De même, supprimer une règle n'interrompt pas les circuits déjà partis.

## 7. En amont et en aval

- **En amont :** il faut des employés pouvant être approbateurs.
- **En aval :** la règle est choisie à la création d'un
  [espace de travail](03-espaces-de-travail.md) ; ses étapes deviennent les
  lignes de [Mes workflow](12-mes-workflow.md).

## 8. Messages

Suppression : « Règle de workflow supprimée. »
En lot, l'unité est **« élément(s) »** : « Voulez-vous supprimer 2 élément(s) ? »

État vide : « Aucune règle de workflow — Créez votre première règle pour lancer
un circuit de validation. »
