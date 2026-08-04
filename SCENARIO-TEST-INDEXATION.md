# Scénario de test — Indexation automatique

> Écran : **Indexation automatique** (5ᵉ entrée du menu) — <http://localhost:4301/indexation-automatique>
> Pré-requis : backend sur `:8080`, frontend sur `:4301`.
> Si l'écran est blanc : `Ctrl+F5`. Après une reconstruction, l'onglet resté ouvert
> réclame des fichiers qui n'existent plus et n'affiche rien.

## Le principe

Le **plan d'indexation** porte une charte : des index **ordonnés**, un **séparateur**,
une **casse**. Cette charte joue dans les deux sens.

| Sens | Ce qui se passe |
|---|---|
| **Décomposition** | Le nom du fichier est découpé au séparateur ; le n-ième segment alimente le n-ième index |
| **Composition** | Les valeurs d'index, rejointes par le séparateur, forment la **référence** du document |

**Garde-fou, non négociable** : l'analyse ne fait que *proposer*. Aucune valeur n'est
écrite tant que l'opérateur n'a pas relu et cliqué sur **Confirmer l'indexation**.
Ce qui ne passe pas le contrôle de type est **signalé**, jamais deviné.

Charte de démonstration — plan *Fiche Facture*, séparateur `_` :

```
Numéro de facture _ Date d'émission _ Fournisseur _ Priorité
```

---

## Volet « Indexer »

### Étape 1 — Lire les états

La liste affiche chaque document avec son état, déduit de ses valeurs et de sa référence.

| État | Signification |
|---|---|
| **Non indexé** | Aucune valeur d'index |
| **Partiel** | Des valeurs, mais la référence est incomplète (ou jamais composée) |
| **Indexé** | Tous les champs du plan sont renseignés, référence composée |

**Attendu** : 12 documents *Partiel* (indexés avant l'arrivée de la charte) et
1 *Indexé* — *Facture Nova juin*, passé par le parcours complet.

### Étape 2 — Le cas nominal : le fichier s'indexe tout seul

Le fichier `2026111_2026-06-10_Nova Import_Urgente.pdf` respecte la charte.
Cliquer **Analyser** sur *Facture Nova juin*.

**Attendu** : `Charte Fiche Facture · séparateur _ — 4/4 champ(s) déduits`,
aucun avertissement, chaque ligne cochée en vert, et la référence composée en direct :

```
2026111_2026-06-10_Nova Import_Urgente
```

### Étape 3 — Vérifier que rien n'a été écrit

Sans confirmer, fermer le panneau avec **Annuler**, puis rouvrir **Analyser**.

**Attendu** : le document est toujours dans le même état qu'avant.
L'analyse est en lecture seule côté serveur — elle propose, elle n'enregistre pas.

### Étape 4 — Le cas dégradé : un nom qui ne suit pas la charte

Cliquer **Analyser** sur n'importe quelle *Facture ACME* (fichier `facture-test.pdf`).

**Attendu** : `0/4 champ(s) déduits` et un avertissement nommant la charte attendue.
Chaque ligne porte son motif de rejet :

| Index | Motif |
|---|---|
| Numéro de facture | « Numéro de facture » attend un nombre. |
| Date d'émission | « Date d'émission » attend une date (AAAA-MM-JJ). |
| Fournisseur | Le nom du fichier ne compte que 2 segment(s) : rien en position 3. |
| Priorité | Le nom du fichier ne compte que 2 segment(s) : rien en position 4. |

C'est le comportement voulu : face à un doute, la GED rend la main plutôt que d'inventer.

### Étape 5 — Corriger puis confirmer

Toujours dans ce panneau, remplir les quatre champs à la main. La référence se
recompose à chaque frappe, et le compteur *« n champ(s) encore vide(s) »* décroît.
Cliquer **Confirmer l'indexation**.

**Attendu** : notification de succès, panneau fermé, la ligne passe à **Indexé**
et affiche sa référence.

### Étape 6 — Contrôle de type au moment de la confirmation

Rouvrir un document, mettre une priorité hors liste ou une date mal formée.

**Attendu** : le serveur refuse et le motif s'affiche. La validation humaine
n'autorise pas à contourner le typage des index.

---

## Volet « Rechercher »

Les critères ne sont pas écrits en dur : ils sont dérivés des index cochés
**« indexé pour recherche »**. Un index de plus = un critère de plus.

| Étape | Manipulation | Attendu |
|---|---|---|
| 7 | Sans critère | **13 documents, 6 groupes** |
| 8 | Fournisseur `acme` | **4** — TEXTE, insensible à la casse |
| 9 | Priorité `Haute` | **4** — LISTE, valeur exacte |
| 10 | Date `2026-01-01` → `2026-01-31` | **4** ; élargi au `2026-03-31` → **9** |
| 11 | N° facture Min `2026105` | **7** — comparaison numérique |
| 12 | `acme` **+** `Haute` ensemble | **2** — les critères se cumulent en ET |
| 13 | Regrouper par `Priorité` | Basse 2 · Haute 4 · Normale 4 · Urgente 3 |

### Étape 14 — Un index de plus = un critère de plus

Menu **Index** → *Créer un index* : nom `Montant TTC`, code `IDX-MONTANT`,
type `Nombre`, **Indexé pour recherche** activé.

Revenir sur l'écran : le panneau annonce **5 champ(s) indexé(s)** et un intervalle
*Montant TTC* est apparu. Aucune ligne de code touchée.

Pour le rendre *saisissable* et pas seulement *cherchable*, l'ajouter au plan
*Fiche Facture* (menu **Plan d'indexation**) — les champs du formulaire viennent
du plan du type, pas de la liste globale des index.

---

## Piège à connaître

**Le séparateur ne doit pas apparaître dans les valeurs.** Le plan de démonstration
utilisait `-`, alors qu'il contient une date ISO (`2026-06-10`) : le découpage partait
en morceaux. Il est passé à `_`. À vérifier à chaque création de plan contenant une date.

## Nettoyage

Documents ajoutés pour ce scénario : **65 à 74** et **97**.
Les documents 1 et 33 préexistaient — ne pas y toucher.

```bash
for i in $(seq 65 74) 97; do curl -s -X DELETE "http://localhost:8080/api/v1/documents/$i" >/dev/null; done
```
