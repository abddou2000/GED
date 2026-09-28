# Résultats de recette — vague 7 (revérification des correctifs, compléments T-105, P-21, R-03, T-101)

Exécutés par qa le 28/09/2026 sur `ct/qa`, par avance rapide jusqu'à **ab6b392**, qui intègre :

- 52cb0cf : ANO-E8-001 ;
- 0e1608b : ANO-E1-006, ANO-E8-002, ANO-E7-003, ANO-E8-003 ;
- e91edec : ANO-E5-003.

Aucun code applicatif modifié.

## 1. Environnement

- `DB_NAME=ged_qa`, `DB_NAME_TEST=ged_qa_test`, annuaire 33394, SMTP de test 3034, pool Hikari 3.
- Instance qa en profil dev (18084 / 18094) sur `ged_qa6`. Réglages :
  - tranches d'archivage de 2 ;
  - bail du travailleur 20 s, relève toutes les 2 s ;
  - scellement et alerte d'échéance chaque minute.
- Simulateurs : clamd, LibreOffice, SMTP.
- Montée de `ged_qa` (base peuplée, 13 documents archivés) pour ANO-E1-006.

## 2. Suite automatisée

**594 tests, 0 échec** (7 min 48 s, `ged_qa_test`).

## 3. Verdict par anomalie

| Anomalie | Recette | Verdict |
|---|---|---|
| ANO-E8-001 (R-01) | `RecetteWorkflow` A-02, A-08 (nouveau) | **Vérifiée**. Création, modification et suppression d'une règle par API pour un Administrateur délégué. Refus 403 : personne sans gestion des référentiels, clé sans PILOTAGE, absence de délégation. Double identité au journal. E8 : 30/30. |
| ANO-E1-006 (T-106, T-022) | Montée de `ged_qa` (13 archivés, montée précédente interrompue à 202609301050) | **Vérifiée**. Montée réussie, gel rétabli (`tgenabled` O). Écriture de `ged_app` sur une version archivée : toujours refusée. 70 versions numérotées, une courante par document. Somme de contrôle de 202610021120 acceptée sur une base qui l'avait appliqué. |
| ANO-E8-002 (T-108, T-040) | `RecetteReception` dans Comptabilité (sous règle) | **Vérifiée**. 5/5. Circuit ouvert pour les dépôts API et BUREAU_ORDRE, application tracée à l'audit et dans la notification. |
| ANO-E7-003 (T-102) | `RecetteModele` M-02 | **Vérifiée**. E7 modèle 17/17. |
| ANO-E8-003 (T-110) | Liquibase sur `ged_qa6` et `ged_qa` | **Vérifiée**, voir le détail ci-dessous. Changesets postérieurs non protégés : **ANO-E8-004**. |
| ANO-E5-003 | `verifier-alteration.sh` A11, A12 et A13 (nouveaux) | **Vérifiée**. Export d'un dossier dont un fichier est altéré au premier segment (A11) ou au dernier des trois (A12) : 500 `INTEGRITE_COMPROMISE`, problem+json, sans Content-Disposition. Aucun `DOCUMENT_EXPORTE` pour ces exports ; l'export témoin valide qui suit en produit 35. Altération : 12/12. |

Détail de la vérification d'ANO-E8-003 :

- Sur `ged_qa6`, retour arrière au-delà de 202610021120 **refusé** avec le décompte : 4 circuits annulés, 2 validateurs par rôle, 4 réaffectations, 2 décisions d'historique. Circuits et décisions intacts.
- Avec la décision explicite (`options=-c ged.retour_arriere_avec_perte=oui`) : exécuté, puis remontée.
- Sur `ged_qa`, où les circuits viennent de la seule reprise : retour arrière **accepté sans décision et fidèle** (1 SIGNED, 2 REJECTED avec leurs motifs, 65 PENDING), remontée identique.
- Reprise des anciennes signatures (T-110) sur `ged_qa`, avec 3 signatures préparées (signée, rejetée avec motif, rejetée sans motif) : 1 circuit VALIDE, 2 REFUSE (motif conservé ou « Refus repris sans motif »), 65 EN_COURS, décisions rattachées à la version courante.

## 4. Compléments (`recette/e7/RecetteComplements.java`, nouveau)

| Id | Exigence | Résultat |
|---|---|---|
| T105-01 | T-105 : confidentialité par défaut du type, surchargeable ; durée portée par le type | OK (PRIVE par défaut, PUBLIC si choisi, échéance à 60 mois) |
| P21-01 | P-21 : date du document, clé de tri prioritaire de la recherche par métadonnées | OK (`POST /documents/recherche`) |
| P21-02 | P-21 : tri par défaut de la liste et de `POST /recherches` | **ÉCHEC** : dépôt décroissant ; `sortBy=dateDocument` ignoré → **ANO-E7-004** |
| R03-01 | R-03 : espace d'échange sous habilitation de **groupe** : déposer, télécharger, modifier localement, verser | OK (la version versée devient courante) |
| R03-02 | R-03 : aucune édition ni co-édition en ligne | OK (aucune route d'édition dans l'OpenAPI ; `PUT …/contenu` → 405) |
| R03-03 | R-03 : aucune écriture dans un dossier archivé | Dépôt 409 et versement 409, mais **création de sous-dossier 201**, puis dépôt possible dessous → **ANO-E7-005** |
| T101-01 | T-101 : annulation avant la première tranche | OK (ANNULE, 0 archivé) |
| T101-02 | T-101 : annulation en cours | OK (prise en compte entre deux tranches ; déjà archivés conservés ; 8 actifs restants ; dossier non marqué ; réannulation 409 ; relance 202) |
| T101-03 | T-101 : reprise après interruption | OK. Instance tuée pendant le job (EN_COURS, 4/60), redémarrée ; job repris à l'expiration du bail, TERMINE 60/60, aucun document archivé deux fois, dossier marqué. Un premier essai à 12 documents était invalide (job fini avant l'arrêt) ; le contrôle exige désormais l'état EN_COURS relevé au moment de l'arrêt. |

## 5. Anomalies

- Vérifiées : ANO-E8-001, ANO-E1-006, ANO-E8-002, ANO-E7-003, ANO-E8-003, ANO-E5-003. Le registre compte 25 anomalies vérifiées et 3 ouvertes.
- Nouvelles, toutes Mineures :
  - **ANO-E7-004** : date du document pas en tri par défaut (liste, `POST /recherches`) ;
  - **ANO-E7-005** : dépôt en dossier archivé contourné par un sous-dossier ;
  - **ANO-E8-004** : un retour arrière au-delà de `workflow-e8` supprime les diffusions (LECTEUR) et les marques d'échéance avant d'être refusé, et laisse la base à mi-chemin.
- Observation : la création d'une règle par API est journalisée avec `acteurNom` = `application:qa-workflow`, alors que l'acteur utilisateur est bien l'Administrateur délégué (double identité correcte dans les identifiants).

Traces locales : `v7-*.log`, `lb-*.log`, `etat-reprise.txt` et le journal de l'instance.
