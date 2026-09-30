# Suivi — qa

## Lot en cours

Vague 8 (tour 1 de la recette technique, `ct/qa-r1`) : recette des 25 lignes « Livré » et des parties
non exercées des lignes « Vérifié », avec les **composants réels installés sur le poste Linux**
(NGINX, clamd, LibreOffice, Prometheus, Alertmanager, PostgreSQL jetable, systemd-analyze).
13 nouvelles anomalies (1 bloquante, 7 majeures, 5 mineures) : `RESULTATS-VAGUE-8.md`.
Revérification des corrections : tour suivant.

Vague 7 : revérification des correctifs (ab6b392 : ANO-E8-001, ANO-E1-006, ANO-E8-002, ANO-E7-003,
ANO-E8-003, ANO-E5-003, toutes vérifiées) et compléments T-105, P-21, R-03, T-101. Vague 6 : tout le code
intégré, critères de sortie E4, E7 et E8 atteints. Vagues précédentes : E1 à E7, E9, notifications,
contrat §5.3.1, E10/E11 sur papier.

## Livrables

| Livrable | Emplacement | État |
|---|---|---|
| Ligne de base | `docs/conformite/recette/LIGNE-DE-BASE.md` | 143 tests (H2, avant les vagues) |
| Plan de recette E0–E11 | `docs/conformite/recette/PLAN-DE-RECETTE.md` | D1–D14 intégrées |
| Résultats | `RESULTATS-VAGUE-1.md` à `RESULTATS-VAGUE-8.md` | 257 → 360 → 443 → 534 → 552 → 586 → 594 tests verts ; vague 8 : 597 tests, 4 échecs de référence + 1 d'environnement |
| Registre des anomalies | `docs/conformite/recette/ANOMALIES.md` | 25 vérifiées ; ouvertes : ANO-E7-004, ANO-E7-005, ANO-E8-004 et, vague 8, ANO-E10-004 (bloquante), ANO-E2-002, ANO-E5-004, ANO-E6-001, ANO-E10-003, ANO-E10-005, ANO-E10-007, ANO-E10-008 (majeures), ANO-E0-002, ANO-E0-003, ANO-E5-005, ANO-E10-002, ANO-E10-006 (mineures) |
| E10 exploitation (vague 8) | `recette/e10/` : `verifier-nginx-reel.sh` (NGINX réel, 10/11), `verifier-front-nginx.sh` (23/23), `verifier-annuaire-bascule.sh` + `AnnuaireAutonome.java` (P-02, P-04), `verifier-modules.sh` (T-088, 5/6), `RecetteExploitation.java` (10/11), `RecetteComplementsV8.java` (7 OK, 1 AVERT), `RecetteRotationJournaux.java` (11/11), `sbom-et-licences-hors-ligne.sh`, `banc-ocr-reduit.sh` | voir `RESULTATS-VAGUE-8.md` |
| E10 sauvegarde et déploiement | `recette/e10-sauvegarde/` : `recette-t073-p13.sh` (33 OK / 3 ÉCHEC), `recette-t092.sh` (8 OK / 3 ÉCHEC), sur PostgreSQL jetable | portables Linux et Git Bash |
| E4 | `recette/e4/` : `verifier-journal.sh` (12/12), `verifier-scellement.sh` (6/6), `RecetteAudit.java` (29/29) | critère de sortie atteint |
| E7 modèle | `recette/e7/RecetteModele.java` | 17/17 |
| E7 compléments | `recette/e7/RecetteComplements.java` (T-105, P-21, R-03, T-101 annulation et reprise) | 8/10 (P21-02 = ANO-E7-004, R03-03 = ANO-E7-005) |
| E8 workflow, E8-API | `recette/e8/RecetteWorkflow.java` | 30/30 ; critère de sortie E8 atteint |
| T-112 échéance | `recette/e8/RecetteEcheance.java` (deux instances) | 6/6 |
| E9 | `recette/e9/RecetteApi.java` | 23 OK, 2 AVERT (compte désactivé délégué ; chemins du contrat : voir ligne suivante) |
| Contrat §5.3.1 et réception | `recette/e9/RecetteContrat.java`, `RecetteReception.java` | contrat 15/15 ; T-040 5/5 hors règle de workflow (ANO-E9-001 close), 500 sous règle (ANO-E8-002) |
| Notifications | `recette/e8/RecetteNotifications.java` (+ `lib/SmtpSimule.java`) | 8/8, circuits et échéance NA (E8) |
| E1 | `recette/e1/` | base vierge, rollback, catalogue, changelogs (Java), autotests |
| E2 | `recette/e2/verifier-identite.sh` (+ `controles-identite.sql`) | 33/33 (ANO-E2-001 corrigée) |
| E3 | `recette/e3/` (`RecetteAutorisation.java`) | 28/28, rejoué en vague 3 |
| E5 | `recette/e5/` (bash HTTP + banc des composants) | tous verts par HTTP ; altération 9 OK, 1 ÉCHEC (A11 export, ANO-E5-003) |
| E6 | `recette/e6/RecetteOcrRecherche.java` | 24/24 |
| E7 | `recette/e7/RecetteCycleDeVie.java` | 23/23 ; critère de sortie E7 atteint |
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
Vague 6 : 12.7 méta-modèle, type, plan versionné, re-typologisation ; 12.8 versions (D9), verrou
(409 partout), workflow parallèle (D7, Q1), diffusion ; 12.5 déplacement et renommage ; R-03 / D12 ;
D8 décision déléguée et pilotage (hors création de règle) ; 12.9 T-112 ; 7.4.1 (ANO-E4-001 à 003) ;
12.6 (ANO-E7-002) ; 5.1 source du dépôt (ANO-E9-001) ; 6.2.3 A10 (ANO-E11-001 ; ANO-E11-002 sur papier) ;
10.4 (ANO-E10-001, papier) ; critères de sortie E4, E7, E8.
Non conforme : 4.2.2 montée sur base peuplée (ANO-E1-006), D8 création de règle par API
(ANO-E8-001), 5.1 dépôt par application sous règle (ANO-E8-002), 12.10 export d'un fichier altéré
(ANO-E5-003), 12.7 valeur par défaut (ANO-E7-003), retour arrière de la reprise (ANO-E8-003).

## Ce qui reste

- Tour suivant : revérifier les corrections des anomalies (ouvertes et vague 8) avec les scripts cités.
- Rejouer P-10, P-16 et ANO-E11-002 en UAT ; T-070 avec la clé NVD en CI ; P-05 (`SEQUENCES.md`).
- T-079 : cas « ligne scellée modifiée » détecté par la tâche planifiée (non rejoué en vague 8).
- UAT : AD réel (LDAPS, délai de connexion), signatures ClamAV officielles, certificat MMED, systemd
  réel, archivage WAL et PITR, rollback sur copie UAT.

## Vérifié uniquement par simulateur ou non vérifiable sur ce poste

Depuis la vague 8, **réels sur ce poste** : NGINX 1.24, clamd 1.5.4 (base de signatures réduite à
EICAR), LibreOffice, Prometheus 2.45 et Alertmanager 0.26, `systemd-analyze verify`, PostgreSQL 16
jetable pour les sauvegardes. Toujours simulés : annuaire (UnboundID), relais SMTP (GreenMail).
Non vérifiable : AD et certificat de MMED, signatures ClamAV officielles, forge et CI, reprise
depuis MySQL, exécution en UAT.

## Notes d'outillage

- Aucun Python (D5) : bash/psql/curl et Java 17 (`lib/lancer-java.sh` compile avec Jackson et le
  pilote PostgreSQL du backend, hors ligne).
- La limitation de débit de la connexion (5/min/IP) cadence les recettes : les scripts attendent le
  `Retry-After`.
- La base PostgreSQL du poste est partagée (100 connexions) : arrêter l'instance qa avant `mvn test`.
