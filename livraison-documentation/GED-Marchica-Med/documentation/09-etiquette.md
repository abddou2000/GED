# 09 — Étiquettes

**Rôle dans le processus :** offrir un **classement transversal**, libre,
indépendant de la hiérarchie des dossiers.

---

## 1. La notion, et ce qui la distingue d'un index

Les deux décrivent un document, mais ils ne répondent pas à la même question :

| | **Index** | **Étiquette** |
|---|---|---|
| Porte | une **valeur** (« 2026003 ») | **rien** — elle est posée ou elle ne l'est pas |
| Décidé par | le paramétrage (plan du type) | l'opérateur, librement, au dépôt |
| Nombre par document | un par index du plan | autant qu'on veut |
| Sert à | décrire, nommer, rechercher | retrouver un ensemble hétérogène |

L'exemple qui éclaire la différence : « Urgent » n'a pas de valeur. Un document
est urgent ou il ne l'est pas. Et « Urgent » peut concerner une facture, un
contrat et un compte rendu — trois types, trois espaces, trois circuits. Aucune
hiérarchie de dossiers ne permet de les regrouper ; une étiquette, si.

## 2. Ce que l'on renseigne

L'ordre du formulaire compte, et il n'est pas celui du tableau :

| Ordre | Information | Obligatoire | Exemple |
|---|---|---|---|
| 1 | **Étiquette** | oui | `Urgent` |
| 2 | **Code** | oui | `TAG-URG` |
| 3 | **Couleur** | oui | `#16406b` pré-rempli |

**Pourquoi le libellé avant le code :** on crée une étiquette parce qu'on a un
mot en tête, pas un code. Demander le code d'abord oblige à inventer un
identifiant pour une notion qu'on n'a pas encore nommée.

## 3. La couleur

Le champ est **libre**, en hexadécimal — pas une liste fermée de teintes
imposées. Il est pré-rempli avec le bleu de la marque, de sorte qu'un
enregistrement fait sans choix conscient produise quand même une étiquette
lisible plutôt qu'une étiquette invisible.

La pastille affichée dans le tableau et le code hexadécimal sont dessinés **à
partir de la même valeur** : il n'y a pas deux sources qui pourraient diverger.

## 4. Les règles de refus

| Situation | Message |
|---|---|
| Libellé vide | « Le libellé est obligatoire » |
| Code vide | « Le code est obligatoire » |

## 5. Où les étiquettes servent

| Écran | Usage |
|---|---|
| [Dépôt](11-depot-et-indexation.md) | l'opérateur en pose autant qu'il veut |
| [Documents](10-documents.md) | colonne « Étiquettes », et critère de tri |

## 6. Effet d'une suppression

Supprimer une étiquette ne supprime aucun document : elle disparaît des
documents qui la portaient. Comme toute suppression du produit, elle est
réversible depuis la corbeille.

## 7. Messages

Création : « Étiquette créée. » · Suppression : « Étiquette supprimée. » ·
Restauration : « Étiquette restaurée. »

État vide de la corbeille : « Aucune étiquette supprimée n'attend d'être
restaurée. »
