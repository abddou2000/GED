# Suivi — qa

## Lot en cours

Vague 1 : ligne de base, plan de recette, outillage de recette E1 / E5 / fumée, jeux de
données, registre des anomalies ; puis recette réelle de E1 (intégré) et de E5 (composants)
après fusion de `conformite-technique` (`fbb951c`) dans `ct/qa`.

## Livrables

| Livrable | Emplacement | État |
|---|---|---|
| Ligne de base | `docs/conformite/recette/LIGNE-DE-BASE.md` | 143 tests, 0 échec (H2, avant vague) |
| Plan de recette E0–E11 | `docs/conformite/recette/PLAN-DE-RECETTE.md` | Rédigé ; décisions D1–D14 intégrées (§3) |
| Résultats vague 1 | `docs/conformite/recette/RESULTATS-VAGUE-1.md` | 257 tests verts sur PostgreSQL, E1 et E5 (composants) recettés |
| Registre des anomalies | `docs/conformite/recette/ANOMALIES.md` | 4 ouvertes : ANO-E1-001, ANO-E1-002 (majeures), ANO-E1-003, ANO-E5-001 (mineures) |
| Scripts E1 | `recette/e1/` | Base vierge + démarrage sans DDL, rollback aller-retour, 38 contrôles de catalogue, 17 sondes de privilèges, analyse des changelogs (Java) ; autotests 8/8 et 2/2 |
| Scripts E5 | `recette/e5/` | Scanner « aucun clair » (autotest 7/7), altération, antivirus, type réel, taille (HTTP), banc des composants (19/19) |
| Fumée | `recette/fumee/fumee.sh` | 7/7 sur l'application intégrée (PostgreSQL, UUID) |
| Jeux de données | `recette/donnees/` | Générateur Java reproductible, scans FR/AR, marqueurs de page |

## Exigences recettées (Réf. de la matrice)

Vérifié : 2.2 PostgreSQL, 2.2 Liquibase, 4.2.1 aucune DDL hors migration, 4.2.2 rollback (sur
poste), 4.2.3 trois rôles, 12.1 UUID, 5.3.2 JSON/ISO/UUID, 12.7 JSONB + GIN (structure).
Vérifié en composants : 6.1.1, 6.1.2, 6.1.5 (Tika) ; par simulateur : 6.1.5 (ClamAV).
Non conforme : 4.2.2 conventions (ANO-E1-001), 12.5 (ANO-E1-002). Reste « Proche » : 4.2.1
amorçage (ANO-E1-003 sur le statut affiché). Partiel : 6.1.4, 2.3.2. Non vérifié : 6.1.6, 12.1
sept groupes.

## Ce qui reste

- Vague 2 : rejouer `verifier-alteration.sh`, `verifier-antivirus.sh`, `verifier-type-reel.sh`,
  `verifier-taille.sh` et `verifier-aucun-clair.sh` par l'API dès que le stockage chiffré est
  branché sur le dépôt ; vérifier les corrections des 4 anomalies.
- Écrire les scripts de recette E2 à E4 (LDAP simulé, jetons, audit) sur le même modèle.
- Rollback « en UAT » : dès que l'environnement UAT existe (E0).

## Points bloquants / à trancher

- D1 × §5.5 : la délégation doit rejeter un compte désactivé, D1 supprime la lecture de l'état
  AD (plan de recette §5).
- `ara.traineddata` absent : l'OCR arabe (E6) n'est pas testable sur ce poste.

## Vérifié uniquement par simulateur ou non vérifiable sur ce poste

- Antivirus : clamd factice des tests de dev3 (reconnaît EICAR seulement).
- Clés : keystore PKCS#12 de test ; table `cle_fichier` simulée en mémoire (changesets en
  `a-integrer/`).
- Non vérifiable ici : NGINX (413 de plateforme), TLS, ClamAV réel, LibreOffice, veraPDF,
  Prometheus, AD, exécution en UAT, reprise depuis MySQL (pas de serveur MySQL).

## Notes d'outillage

- Aucun Python (D5) : bash/psql/curl pour les serveurs, Java 17 en mode fichier source pour les
  outils (`java -Dfile.encoding=UTF-8`, indispensable sous Windows avec le JDK 17).
- Liquibase exécuté hors ligne par `recette/lib/LiquibaseRecette.java` avec le classpath du
  backend : le greffon Maven Liquibase exige des artefacts absents du dépôt local.
- Artefacts temporaires de qa préfixés `qa` (un fichier au nom générique a été écrasé par une
  exécution concurrente de dev3 lors de la première mesure de la ligne de base).
