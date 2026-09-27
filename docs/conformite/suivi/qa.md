# Suivi — qa

## Lot en cours

Vague 4 : recette du lot de dev2 (E4 audit, E9 API d'intégration, notifications) et
revérification d'ANO-E2-001, sur `conformite-technique` 30e73b3 (⊇ e8a75d9) fusionné dans `ct/qa`.
Vagues précédentes : E1, E2, E3, E5, E6, E7 recettés.

## Livrables

| Livrable | Emplacement | État |
|---|---|---|
| Ligne de base | `docs/conformite/recette/LIGNE-DE-BASE.md` | 143 tests (H2, avant les vagues) |
| Plan de recette E0–E11 | `docs/conformite/recette/PLAN-DE-RECETTE.md` | D1–D14 intégrées |
| Résultats | `RESULTATS-VAGUE-1.md` à `RESULTATS-VAGUE-4.md` | 257 → 360 → 443 → 534 tests verts |
| Registre des anomalies | `docs/conformite/recette/ANOMALIES.md` | 7 vérifiées, 8 ouvertes : ANO-E7-001, ANO-E7-002, ANO-E4-001 majeures ; ANO-E5-002, ANO-E4-002, 003, 004, ANO-E1-005 mineures |
| E4 | `recette/e4/` : `verifier-journal.sh` (12/12), `verifier-scellement.sh` (5/5), `RecetteAudit.java` (26/29) | — |
| E9 | `recette/e9/RecetteApi.java` | 23 OK, 2 AVERT (compte désactivé délégué, chemins du contrat à faire) |
| Notifications | `recette/e8/RecetteNotifications.java` (+ `lib/SmtpSimule.java`) | 8/8, circuits et échéance NA (E8) |
| E1 | `recette/e1/` | base vierge, rollback, catalogue, changelogs (Java), autotests |
| E2 | `recette/e2/verifier-identite.sh` (+ `controles-identite.sql`) | 33/33 (ANO-E2-001 corrigée) |
| E3 | `recette/e3/` (`RecetteAutorisation.java`) | 28/28, rejoué en vague 3 |
| E5 | `recette/e5/` (bash HTTP + banc des composants) | tous verts par HTTP (antivirus simulé) |
| E6 | `recette/e6/RecetteOcrRecherche.java` | 24/24 |
| E7 | `recette/e7/RecetteCycleDeVie.java` | 21/23 |
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
Non conforme : 7.4.1 (ANO-E4-001), 12.6 archivage (ANO-E7-002), 12.10 export (ANO-E7-001).

## Ce qui reste

- Vérifier les corrections des anomalies ouvertes.
- Chemins du contrat §5.3.1 (T-042, P-06) dès leur intégration ; E8 (circuits, échéance, notifications associées).
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
