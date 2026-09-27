# Suivi — qa

## Lot en cours

Vagues 2 et 3 : recette E2 (identité), E3 (autorisation), revérification des anomalies E1, puis
E5 branché, E6 (OCR, recherche) et E7 (cycle de vie) de bout en bout par HTTP, sur
`conformite-technique` e81ecc2 fusionné dans `ct/qa`.

## Livrables

| Livrable | Emplacement | État |
|---|---|---|
| Ligne de base | `docs/conformite/recette/LIGNE-DE-BASE.md` | 143 tests (H2, avant les vagues) |
| Plan de recette E0–E11 | `docs/conformite/recette/PLAN-DE-RECETTE.md` | D1–D14 intégrées |
| Résultats | `RESULTATS-VAGUE-1.md`, `RESULTATS-VAGUE-2.md`, `RESULTATS-VAGUE-3.md` | 257 → 360 → 443 tests verts |
| Registre des anomalies | `docs/conformite/recette/ANOMALIES.md` | 5 vérifiées, 5 ouvertes (ANO-E2-001, ANO-E7-001, ANO-E7-002 majeures ; ANO-E5-002, ANO-E0-001 mineures) |
| E1 | `recette/e1/` | base vierge, rollback, catalogue, changelogs (Java), autotests |
| E2 | `recette/e2/verifier-identite.sh` (+ `controles-identite.sql`) | 33 contrôles, 31 OK |
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
Non conforme : 3.4.1 anti-force brute derrière NGINX (ANO-E2-001), 12.6 archivage (ANO-E7-002),
12.10 export (ANO-E7-001).

## Ce qui reste

- Vérifier les corrections des anomalies ouvertes.
- E4 (audit) : écrire `recette/e4/` dès la livraison (les contrôles d'audit d'E5, E7 sont NA).
- 12.11 : issues `SANS_PLAN` / `A_INDEXER` ; réindexation complète ; vérification mensuelle d'intégrité.
- UAT : AD réel, ClamAV réel, LibreOffice, NGINX (413 de plateforme, IP client), rollback sur copie UAT.

## Vérifié uniquement par simulateur ou non vérifiable sur ce poste

Annuaire (UnboundID), ClamAV (`FauxClamd`), LibreOffice (`FauxSoffice`). Non vérifiable : NGINX,
TLS, AD, ClamAV et LibreOffice réels, Prometheus, reprise depuis MySQL, exécution en UAT.

## Notes d'outillage

- Aucun Python (D5) : bash/psql/curl et Java 17 (`lib/lancer-java.sh` compile avec Jackson et le
  pilote PostgreSQL du backend, hors ligne).
- La limitation de débit de la connexion (5/min/IP) cadence les recettes : les scripts attendent le
  `Retry-After`.
- La base PostgreSQL du poste est partagée (100 connexions) : arrêter l'instance qa avant `mvn test`.
