# Recette E6 — OCR asynchrone et recherche plein texte

`bash recette/lib/lancer-java.sh recette/e6/RecetteOcrRecherche.java RecetteOcrRecherche`
(Java 17 + Jackson du backend, compilé à la volée avec `lib/ClientGed.java` ; aucun Python, D5).

Prérequis : le jeu d'habilitations de la recette E3 (`recette/e3/verifier-autorisation.sh`) et les
scans de 20 pages (`bash recette/donnees/generer-donnees.sh --pages-scan 20 --sortie recette/donnees/genere`).
Variables : `GED_URL`, `GED_RECETTE_MOT_DE_PASSE`, comptes `GED_E3_*`, `GED_DONNEES`,
`GED_E6_TYPE_A` (défaut `TD-FACT`), `GED_E6_TYPE_B` (défaut `TD-QA-RH`), `GED_E6_ATTENTE_MAX_S`.

Contrôles : 202 `EN_ATTENTE_OCR` au dépôt ; texte extrait (provenance, pages, langue, délai) ;
recherche du témoin français, **arabe** (scan et couche texte en formes de présentation),
`zarkopage20` (20e page : aucun plafond), accents, syntaxe websearch (expression, exclusion) ;
extraits en segments surlignés jamais en HTML ; filtrage par droits à la source (TIERS,
SANS_DROIT) ; cloisonnement (aucun champ d'index rempli, aperçu d'indexation sans contenu du
scan) ; réindexation incrémentale à la nouvelle version ; supervision réservée ; délai mesuré
d'un scan de 20 pages contre D6 (24 h) et l'objectif V3 (5 min).
