# Suivi — qa

## Lot en cours

Vague 5 : correctifs de dev3 (a92c10d : T-040, ANO-E7-001, ANO-E5-002) et lot final de dev2
(4d28528 : contrat §5.3.1, T-065, P-05, P-10 à P-12, P-16, P-17, ANO-E1-005, ANO-E4-004), fusionnés
dans `ct/qa` (88af353). Vagues précédentes : E1 à E7, E4, E9, notifications. E8 attend la branche de dev1.

## Livrables

| Livrable | Emplacement | État |
|---|---|---|
| Ligne de base | `docs/conformite/recette/LIGNE-DE-BASE.md` | 143 tests (H2, avant les vagues) |
| Plan de recette E0–E11 | `docs/conformite/recette/PLAN-DE-RECETTE.md` | D1–D14 intégrées |
| Résultats | `RESULTATS-VAGUE-1.md` à `RESULTATS-VAGUE-5.md` | 257 → 360 → 443 → 534 → 552 tests verts |
| Registre des anomalies | `docs/conformite/recette/ANOMALIES.md` | 11 vérifiées, 9 ouvertes : ANO-E7-002, ANO-E4-001, ANO-E9-001 majeures ; ANO-E4-002, 003, ANO-E5-003, ANO-E11-001, 002, ANO-E10-001 mineures |
| E4 | `recette/e4/` : `verifier-journal.sh` (12/12), `verifier-scellement.sh` (6/6, S06 bornes numériques), `RecetteAudit.java` (26/29) | — |
| E9 | `recette/e9/RecetteApi.java` | 23 OK, 2 AVERT (compte désactivé délégué ; chemins du contrat : voir ligne suivante) |
| Contrat §5.3.1 et réception | `recette/e9/RecetteContrat.java`, `RecetteReception.java` | contrat 15/15 ; T-040 2 OK, 3 ÉCHEC (ANO-E9-001) |
| Notifications | `recette/e8/RecetteNotifications.java` (+ `lib/SmtpSimule.java`) | 8/8, circuits et échéance NA (E8) |
| E1 | `recette/e1/` | base vierge, rollback, catalogue, changelogs (Java), autotests |
| E2 | `recette/e2/verifier-identite.sh` (+ `controles-identite.sql`) | 33/33 (ANO-E2-001 corrigée) |
| E3 | `recette/e3/` (`RecetteAutorisation.java`) | 28/28, rejoué en vague 3 |
| E5 | `recette/e5/` (bash HTTP + banc des composants) | tous verts par HTTP ; altération 9 OK, 1 ÉCHEC (A11 export, ANO-E5-003) |
| E6 | `recette/e6/RecetteOcrRecherche.java` | 24/24 |
| E7 | `recette/e7/RecetteCycleDeVie.java` | 22/23 (E7-03 = ANO-E7-002) |
| Fumée | `recette/fumee/fumee.sh` (`GED_URL_SANTE` pour le port de management) | 7/7 |
| Outils | `recette/lib/` : `ClientGed.java`, `lancer-java.sh`, `ClamdSimule.java`, `LiquibaseRecette.java` | — |

## Exigences recettées (Réf. de la matrice)

Vérifié : 2.2 (×2), 4.2.1 (×2), 4.2.2 (×2), 4.2.3, 12.1 UUID, 5.3.2 formats, 12.5 suppression douce,
12.7 JSONB ; 3.2, 3.3 jeton, 3.4.1 RS256/mémoire Angular/renouvellement/CSRF, 3.4.2 ; 6.2.3 A01,
6.4, 12.2, 12.3, 12.4 ; 6.1.1, 6.1.2, 6.1.4, 6.1.5 Tika, 6.1.6 (PDF) ; 4.3.3, 4.3.4, 4.4, 4.4.1,
5.3.1 ; 12.5 purge.
Par simulateur : 3.3 LDAPS search-then-bind et provisionnement (UnboundID), 6.1.5 ClamAV,
6.1.6 bureautique et 12.6 DOCX (LibreOffice).
Vague 4 : 7.1, 7.4.2, 7.4.3, 3.4.1 (anti-force brute), 5.1, 5.2, 5.3 (dépôt avec métadonnées,
délégation, OpenAPI), 5.3.2, 5.4, 5.5 (hors compte désactivé).
Vague 5 : 5.3.1 chemins exacts (P-06, T-042, T-044), 6.2.1 liaisons chiffrées hors dev (T-065,
démarrage uat refusé), 4.5 schéma de base (P-05), 12.10 export hors périmètre (ANO-E7-001), 6.1.2
lecture d'un fichier altéré (téléchargement, aperçu), 7.4.2 bornes des scellements, 12.1 UUID ;
sur papier avec réserves : 6.1.3 LUKS (P-10), 6.2.3 A04 menaces (P-11), 7.4.2 pgaudit (P-16),
10.4 garantie (P-17).
Non conforme : 7.4.1 (ANO-E4-001), 12.6 archivage (ANO-E7-002), 5.1 source d'un dépôt par
application (ANO-E9-001), 12.10 export d'un fichier altéré (ANO-E5-003), 6.2.3 A10 preuve SSRF
(ANO-E11-001, 002), 10.4 retour arrière (ANO-E10-001).

## Ce qui reste

- Vérifier les corrections des anomalies ouvertes.
- E8 (circuits, échéance, notifications associées) et anomalies de dev1, dès l'intégration de sa branche.
- Rejouer P-10 et P-16 en UAT.
- 12.11 : issues `SANS_PLAN` / `A_INDEXER` ; réindexation complète ; vérification mensuelle d'intégrité.
- UAT : AD réel, ClamAV réel, LibreOffice, NGINX (413 de plateforme, IP client), rollback sur copie UAT.

## Vérifié uniquement par simulateur ou non vérifiable sur ce poste

Annuaire (UnboundID), ClamAV (`FauxClamd`), LibreOffice (`FauxSoffice`), relais SMTP (GreenMail). Non vérifiable : NGINX,
TLS, AD, ClamAV et LibreOffice réels, Prometheus, reprise depuis MySQL, exécution en UAT.

## Notes d'outillage

- Aucun Python (D5) : bash/psql/curl et Java 17 (`lib/lancer-java.sh` compile avec Jackson et le
  pilote PostgreSQL du backend, hors ligne).
- La limitation de débit de la connexion (5/min/IP) cadence les recettes : les scripts attendent le
  `Retry-After`.
- La base PostgreSQL du poste est partagée (100 connexions) : arrêter l'instance qa avant `mvn test`.
