# Ligne de base de la suite de tests — avant la vague E0/E1/E5

Rédigé par : qa. Objet : figer l'état de la suite automatisée **avant** toute évolution de
conformité, pour qu'une régression introduite par un lot soit attribuable sans ambiguïté.

## Conditions d'exécution

| Élément | Valeur |
|---|---|
| Branche / commit | `ct/qa` @ `8a99bdc` (identique au point de départ commun des branches `ct/*`) |
| Commande | `cd backend && mvn clean test -B` |
| Date | 2026-09-26, 21:39:18 → 21:40:23 UTC |
| JDK / Maven | OpenJDK 17.0.19 (Microsoft) / Maven 3.9.9 |
| Base | H2 en mémoire (`jdbc:h2:mem:gedtest`, profil `test`, `ddl-auto: create-drop`, Flyway désactivé) |
| Stockage | `backend/target/test-storage` (fichiers **en clair**, un sous-dossier par espace) |
| OCR | Tesseract 5 présent (`C:/Program Files/Tesseract-OCR`), modèles `eng`, `fra`, `osd` ; **`ara` absent** |

## Résultat global

| Tests | Échecs | Erreurs | Ignorés | Durée Maven | Durée murale | Verdict |
|---|---|---|---|---|---|---|
| **143** | 0 | 0 | 0 | 1 min 02 s | 1 min 04,5 s | **BUILD SUCCESS** |

## Détail par classe (21 classes)

| Classe | Tests | Durée (s) |
|---|---|---|
| accessgroup.AccessGroupApiTest | 6 | 25,42 (inclut le démarrage du contexte Spring) |
| document.CorbeilleApiTest | 3 | 0,60 |
| document.DepotRobustesseApiTest | 6 | 0,30 |
| document.DocumentApiTest | 7 | 0,28 |
| document.VersionConcurrenceTest | 2 | 4,43 (second contexte Spring) |
| etiquette.EtiquetteApiTest | 6 | 0,30 |
| index.IndexApiTest | 6 | 0,40 |
| indexation.IndexationApiTest | 8 | 1,35 |
| indexation.IndexationAutomatiqueApiTest | 8 | 0,73 |
| indexation.IndexationControlesApiTest | 6 | 0,47 |
| indexation.NormalisationDateTest | 18 | 0,27 |
| ocr.DisponibiliteTesseractTest | 2 | 0,00 |
| ocr.ExtractionNombreTest | 4 | 0,02 |
| ocr.OcrApiTest | 8 | 1,79 |
| planindexation.PlanIndexationApiTest | 6 | 0,33 |
| security.SecuriteApiTest | 4 | 2,66 |
| security.ServiceJetonTest | 8 | 0,23 |
| signature.SignatureApiTest | 8 | 3,81 |
| typedocument.TypeDocumentApiTest | 7 | 0,52 |
| workflow.WorkflowApiTest | 11 | 0,64 |
| workspace.WorkSpaceApiTest | 9 | 0,66 |

## Avertissements relevés dans le journal (11 lignes WARN, aucune bloquante)

| Nb | Source | Lecture qa |
|---|---|---|
| 2 | `ServiceJeton` : « GED_JWT_CLE absente : une clé aléatoire a été tirée » | Attendu hors prod (un contexte par classe de config). |
| 2 | `JpaBaseConfiguration` : `spring.jpa.open-in-view` activé par défaut | Réglage à expliciter (hors périmètre de conformité, à signaler à dev1). |
| 3 + 3 | `ExtracteurPdfNatif` / `ExtracteurTesseract` : lecture impossible | Provoqués volontairement par les tests de robustesse (PDF corrompu). |
| 1 | `StorageService` : « Suppression refusée, chemin hors du stockage : ../../evade.txt » | Provoqué volontairement (test de traversée de chemin). |

## Ce que la ligne de base ne couvre pas (et que la recette devra couvrir)

- **Aucun test sur PostgreSQL** : toute la suite tourne sur H2 ; ni Liquibase, ni les rôles
  `ged_owner`/`ged_app`/`ged_readonly`, ni JSONB/GIN, ni la recherche `tsvector`.
- **Stockage en clair** : 11 fichiers écrits sous `target/test-storage/<espace>/<uuid>.pdf`,
  lisibles tels quels. Le script `recette/e5/verifier-aucun-clair.py` lancé sur ce dossier
  doit donc **échouer** aujourd'hui (constat servant d'autotest du script, voir
  `recette/README.md`).
- **OCR arabe** : le modèle `ara.traineddata` n'est pas présent (ni dans `backend/tessdata`,
  ni dans l'installation Tesseract) : aucun test ne peut exercer l'arabe sur ce poste tant
  qu'il n'est pas installé.

## Incident d'outillage pendant la mesure (sans effet sur le résultat retenu)

Une première exécution a écrit son journal dans un fichier temporaire au nom générique
(`baseline.log`) du répertoire de travail partagé entre agents ; ce fichier a été écrasé par
l'exécution concurrente de dev3 (le journal montrait `started by abdou in …\ged-wt\dev3\backend`).
La mesure a été **refaite** dans un chemin propre à qa (`scratchpad/qa/ligne-de-base.log`) ;
seuls les chiffres de cette seconde exécution, dont le journal montre
`started by abdou in C:\Users\abdou\ged-wt\qa\backend`, figurent ci-dessus.
Règle retenue : tout artefact temporaire de qa porte le préfixe `qa`.

## Utilisation

À chaque intégration d'un lot par `pm`, qa relance `mvn clean test` sur `ct/qa` après
`git merge conformite-technique` et compare : le nombre de tests ne doit jamais baisser
(sauf suppression justifiée d'une fonction, par exemple les mots de passe locaux en E2),
et tout échec est inscrit au registre `ANOMALIES.md`.
