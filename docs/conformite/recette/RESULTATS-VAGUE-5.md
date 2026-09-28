# Résultats de recette — vague 5 (correctifs de dev3, lot final de dev2)

Exécutés par qa le 28/09/2026 sur `ct/qa`. Aucun code applicatif n'a été modifié. Deux fusions :

- `conformite-technique` @ a92c10d : T-040, ANO-E7-001, ANO-E5-002. Scripts de recette commités en 64e0aee.
- `conformite-technique` @ 4d28528 : contrat §5.3.1, T-065, P-05, P-10 à P-12, P-16, P-17, ANO-E1-005, ANO-E4-004. Fusion 88af353.

E8 (workflow) et les anomalies de dev1 ne sont pas recettées dans cette vague, car la branche de dev1 n'est pas intégrée.

## 1. Environnement

- Règle 6 bis : annuaire simulé sur 33394, `GED_SMTP_PORT_TEST=3034`, pool Hikari 3.
- Instance qa en profil dev : API 18084, management 18094.
- Base neuve `ged_qa5`, préparée par `backend/scripts/db/preparer-base.sql`. Le jeu E3 y a été reconstruit (E3 : 28/28).
- Simulateurs : clamd (`FauxClamd`) et LibreOffice (`FauxSoffice`).
- L'instance déclare `ged.depot.applications-bureau-ordre=qa-bureau-ordre`.
- `ged_qa` n'est plus utilisée : son coffre mêlait deux clés maîtresses (v3 et v4), ce qui faussait l'export.

## 2. Suite automatisée

| Commit | Tests | Échecs |
|---|---|---|
| a92c10d | 540 | 0 |
| 88af353 | 552 | 0 (en 4 min 35 s, sur `ged_qa_test`) |

Nouvelles classes vertes : `ContratApiTest` (5), `AppelsSortantsTest` (2), `ControleTransportsChiffresTest` (3), `ScellementAuditTest` (5).

**Incident.** Un premier lancement de la suite a omis `DB_NAME=ged_qa`. Il a donc visé la base de test par défaut **`ged_dev1_test`**, pendant qu'une exécution de dev1 y tournait. Il est resté bloqué après avoir pris le verrou Liquibase de cette base (`DATABASECHANGELOGLOCK`, à 00 h 31), puis a été arrêté. qa n'a rien modifié d'autre dans cette base. **Le verrou est à vérifier et, si besoin, à libérer par dev1 ou pm.**

Désormais, qa passe `DB_NAME_TEST=ged_qa_test` explicitement.

## 3. Correctifs de dev3

| Anomalie | Script | Résultat |
|---|---|---|
| ANO-E7-001 | `recette/e7/RecetteCycleDeVie.java` (E7-14 renforcé) | **Vérifiée**. Un dossier hors périmètre répond 404, avec un corps identique à celui d'un dossier absent, sans `Content-Disposition` et sans ligne `job_export`. L'export de fond (seuil 0) se comporte de même, alors qu'un export dans le périmètre crée bien son travail (202). E7 : 22/23, E7-03 étant ANO-E7-002 de dev1. |
| ANO-E5-002 | `recette/e5/verifier-alteration.sh` (A10, A09 et A11 ajoutés) | **Vérifiée pour le téléchargement et l'aperçu.** Octet inversé, fichier tronqué, fichier substitué et aperçu répondent 500 `INTEGRITE_COMPROMISE` en problem+json, sans `Content-Disposition`. L'altération est auditée (`INTEGRITE_ANOMALIE`, acteur et motif). **L'export synchrone d'un dossier qui contient le fichier altéré reste en 200 avec une archive tronquée : ANO-E5-003.** Bilan : 9 OK, 1 ÉCHEC (A11), 1 NA (A08, pas de point d'entrée de vérification). |
| T-040 | `recette/e9/RecetteReception.java` (nouveau) | **Partiel.** Voir le détail ci-dessous. |

Détail de la réception T-040 :

| Contrôle | Résultat |
|---|---|
| R-01 : dépôt par l'interface | OK. Canal INTERFACE, déposant et horodatage renseignés ; un champ de formulaire ne peut pas forcer le canal. |
| R-03 : bureau d'ordre | Canal BUREAU_ORDRE correct. |
| R-05 : journal d'audit | OK. |
| R-02 : dépôt par clé d'API | **Écart** : `applicationId` est null. |
| R-04 : dépôt délégué | **Écart** : enregistré comme INTERFACE, sans application ni indicateur de délégation. |

Ces écarts sont consignés sous **ANO-E9-001**.

## 4. Lot final de dev2

| Repère | Moyen | Verdict |
|---|---|---|
| P-06, T-042, T-044 (contrat §5.3.1) | `recette/e9/RecetteContrat.java` (nouveau), **15/15** | **Vérifié**, voir le détail ci-dessous. |
| T-040 : critère `canal` de `POST /recherches` | idem, C-07 et C-14 | Vérifié. La valeur stockée est fausse pour les dépôts délégués (ANO-E9-001). |
| T-065 (TLS hors dev) | Test unitaire, plus démarrage réel en profil `uat` avec `DB_SSLMODE=disable` et `GED_SMTP_STARTTLS=false` | **Vérifié**, voir le détail ci-dessous. |
| P-05 (schéma de base) | `node outils/schema-base.mjs --base ged_qa_test` sur une base migrée par Liquibase, puis comparaison avec la version commitée | **Vérifié**. Aucun écart hors le nom de la base source (42 tables, 82 changesets). `CLASSES.md` régénéré est identique. |
| P-12 (revue SSRF) | Lecture, `AppelsSortantsTest`, recherche des appels sortants dans `backend/src/main` | **Écart sur la preuve** (ANO-E11-001, ANO-E11-002). Le code est conforme : aucun client HTTP, toutes les destinations viennent de la configuration. |
| P-11 (modèle de menaces) | Lecture contre §6.2.3 A04 | **Vérifié avec réserve**, voir le détail ci-dessous. |
| P-17 (garantie) | Lecture contre §10.4 | **Vérifié avec réserve**, voir le détail ci-dessous. Écart : ANO-E10-001 (retour arrière de l'étape 2). |
| P-10 (LUKS), sur papier | Lecture contre §6.1.3 | **Vérifié avec réserve**, voir le détail ci-dessous. |
| P-16 (pgaudit), sur papier | Lecture contre §7.4.2 et D11 | **Vérifié avec réserve**, voir le détail ci-dessous. |
| ANO-E4-004 | `recette/e4/verifier-scellement.sh`, S06 ajouté ; scellement lancé par `ged.audit.scellement.cron` | **Vérifiée**. La période 00 h–01 h est scellée de 1 à 225 pour 225 lignes. S01 à S06 : 6/6. |
| ANO-E1-005 | `recette/e1/verifier-socle.sh` sur `ged_qa5` | **Vérifiée**. Clés UUID ; C04, C05 et C06 OK. Il reste deux avertissements déjà connus (C13, C25). |

Détail du contrat §5.3.1 (P-06, T-042, T-044) :

- `POST /noeuds/{id}/dossiers` :
  - 201 avec `Location` ;
  - rejeu sans doublon ; 422 `IDEMPOTENCE_CONFLIT` ; 400 `IDEMPOTENCE_CLE_ABSENTE` ;
  - 404 indiscernable ; 400 avec le champ en erreur.
- `GET /documents/{id}/contenu` et `?version=` : octets identiques au dépôt ; 404 indiscernable.
- `POST /recherches` :
  - critères de nœud et de canal ;
  - taille 50 par défaut, plafond 200 ;
  - canal inconnu → 400 ;
  - rien révélé hors périmètre.
- `GET /documents|noeuds/{id}/droits` : 403 pour un tiers sans permission d'administration ; 404 indiscernable.
- Dépôt par application et dépôt délégué acceptés (202, OCR en attente).
- Recherche par clé d'API.
- Chemins présents dans OpenAPI.

Détail de T-065. Démarrage refusé en `uat`, avec quatre écarts nommés :

- `sslmode` ;
- URL JDBC ;
- autorité de certification ;
- STARTTLS.

`ldap://` est déjà refusé par l'annuaire. Observation : le contrôle ne s'applique qu'aux profils `prod` et `uat` (liste blanche). Un profil inconnu ne démarre pas faute de `ged.base.nom`, le risque est donc faible.

Réserves sur les repères vérifiés avec réserve (à rejouer en UAT pour P-10 et P-16) :

- P-11 :
  - la compromission de la KEK n'est pas modélisée ;
  - les parades d'échec fermé ne renvoient pas à leurs tests ;
  - le port de management n'est pas traité ;
  - clamd est dit « réseau interne, TLS » en §0, mais « local » dans le schéma.
- P-17 :
  - fournisseurs tiers du §10.4 non traités ;
  - point de départ des 24 h ambigu ;
  - RPO de 15 min valable pour la base seulement.
- P-10 :
  - `crypttab` sans `tpm2-device` ni `_netdev` : la phrase de passe peut être demandée au redémarrage ;
  - pas de sauvegarde d'en-tête LUKS.
- P-16 :
  - `shared_preload_libraries` écrase une liste existante ;
  - les sessions DBA nominatives et `ged_sauvegarde` ne sont pas tracées (`pgaudit.log='none'` par défaut) ;
  - `SET ROLE` n'est pas couvert.

## 5. Anomalies

- Closes (Vérifiées) : ANO-E7-001, ANO-E5-002 (téléchargement et aperçu), ANO-E4-004, ANO-E1-005.
- Nouvelles :
  - ANO-E9-001, Majeure (T-040) ;
  - ANO-E5-003, Mineure (export d'un fichier altéré) ;
  - ANO-E11-001 et ANO-E11-002, Mineures (P-12) ;
  - ANO-E10-001, Mineure (P-17).
- Toujours ouvertes : ANO-E7-002 (dev1), ANO-E4-001, ANO-E4-002, ANO-E4-003.

Traces locales dans le répertoire de travail de qa :

- `e7-vague5.log`, `e5-alteration-vague5.log`, `reception-vague5b.log` ;
- `contrat-vague5.log`, `e4-scellement-vague5.log`, `e1-vague5.log` ;
- `mvn-test-vague5.log`, `mvn-test-vague5c.log`.
