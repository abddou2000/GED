# Résultats de recette — vague 10 (tour 3 : anomalies corrigées au tour 2, réserves des lignes « Vérifié »)

Exécutés par qa le 01/10/2026 sur `ct/qa-r3`, créée depuis `claude/inspiring-lovelace-10bg1c` (**ff20f21** :
tour 2 intégré — `97c2bbb` dev1, `83c1b25` dev3, `daef8d2` dev2, `d13216b`/`2ceacce` dev4, `53a633d` dev5,
`11a86d1` recette de la vague 9, `cc7d5a2`/`ff20f21` suivi de pm). Les corrections du tour 3 (dev2, dev3, dev4 au
travail en même temps) ne sont **pas** intégrées. Aucun code applicatif modifié ; quatre scripts de recette
adaptés ou ajoutés (§8).

## 1. Environnement

| Élément | Ce poste | Réel / simulé |
|---|---|---|
| Application | JAR construit sur `ct/qa-r3` par `mvn package` **en ligne** (SBOM complété, §2) ; profil dev, API 18084, management 18094 ; base `ged_qa` (108 changesets, aucun nouveau au tour 2) ; `ged_qa_test` pour `mvn test` | réel |
| Instances | **A** nominale (intégrité toutes les 3 min, reprises OCR 10 s / 20 s / 30 s) ; **B** modules workflow, export, intégration, OCR inactifs ; **C** trois contrôleurs d'annuaire autonomes dont un muet ; **T** sur une **copie peuplée** de `ged_qa` dans l'instance PostgreSQL jetable (100 000 documents clonés, reprise de 80 versions en clair au démarrage) | réel |
| PostgreSQL | instance partagée (bases `ged_qa*` ; `ged_qa` lue par `pg_dump`) ; **instance PostgreSQL 16 privée et jetable** (127.0.0.1:55494) pour T-073/P-13, T-104, T-035 et les essais de dev2 | réel |
| NGINX | **1.24.0**, `deploiement/nginx/ged.conf` livré (écoutes IPv6 facultatives), poste **sans IPv6** (`/proc/net/if_inet6` absent) | réel ; certificat auto-signé |
| Prometheus | **2.45.3**, `alertes.yml` livré (14 règles) non modifié, cible = management de l'instance | réel |
| ClamAV, LibreOffice, Tesseract, veraPDF | clamd 1.5.4 (signature EICAR seule), soffice 24.2, Tesseract 5.3.4, veraPDF embarqué | réels |
| Annuaire | UnboundID : embarqué (A, B, T) ; trois contrôleurs autonomes (C), compte de service portant `msDS-UserPasswordExpiryTimeComputed` à J+10 | simulé |
| Front | Node 24.21.0 de l'équipe : `ng build` vert (avertissement de budget CSS de `profil.scss`), `ng test` **148/148** | réel |

Toutes les instances, NGINX, Prometheus, clamd, les contrôleurs simulés et l'instance PostgreSQL jetable ont été
arrêtés en fin de vague (§9). Le PostgreSQL partagé n'a jamais été arrêté.

## 2. Revérification des anomalies « Corrigée »

| Anomalie | Correctif | Recette rejouée | Constat | Verdict |
|---|---|---|---|---|
| ANO-E2-002 (P-02, D4) | 3e94067 (dev1) | instance C ; `qa/r3/muet.sh` (8 essais espacés de 25 s) ; **`e10/verifier-annuaire-bascule.sh` 6/6** | Contrôleur muet en tête : **8 / 8 connexions réussies** (vague 8 : 1 / 8). Une sur deux prend 5,1 s (délai de lecture, puis le muet est mis à l'écart 30 s), les autres 70 à 550 ms. A01 bascule (200 en 3,1 s, métrique 0 sur le muet, sonde DEGRADE), A02, A03, A04 (503 sans mode dégradé, session conservée, sonde DOWN hors readiness), A05, P04-02 | **Vérifiée** |
| ANO-E5-004 (T-062, P-07) | fb4cf60 (dev2) | `e5/verifier-taille.sh` **4/4** ; 205 Mo en direct ; **NGINX réel N08b** | 200 Mio + 1 octet → 413 `FICHIER_TROP_VOLUMINEUX` ; limite du type 5 Mo + 1 octet → 413, exactement 5 Mo → 202 ; exactement 200 Mio → 202. Fichier de 205 Mo (requête < 210 Mo) → **413 `application/problem+json`** en 0,8 s (code `FICHIER_TROP_VOLUMINEUX` ; journal : WARN « Refus 413 FICHIER_TROP_VOLUMINEUX : …FileSizeLimitExceededException », plus d'`IllegalStateException`) ; par NGINX : 413 problem+json du back-end | **Vérifiée** |
| ANO-E6-001 (T-038, P-14) | 9ebe15d (dev3) | dépôt d'un texte de 150 Mio puis recherche ; `e6/RecetteOcrRecherche` **19/19** | Texte de 157 286 400 caractères en base ; `plein-texte?taille=5&q=recette` **200 en 0,03 s**, « zarkolinet » 200 en 0,28 s (le document est le premier résultat, extrait borné), « convention » 200. E6 rejouée avec ce document présent : extraits en segments, pertinence, droits | **Vérifiée** |
| ANO-E10-007 (P-13) | a0c7da1 (dev2) | **`e10-sauvegarde/recette-t073-p13.sh`** (adaptée, §8) 40 OK / 0 ÉCHEC / 2 AVERT ; `deploiement/sauvegarde/tests/test-rapprocher-orphelins.sh` 19/19 | P13-02/08 (quarantaine, idempotence) avec `--age-minimal-minutes 0` ; P13-04 : aperçu en cache non signalé ; **P13-10 : fichier redevenu référencé remis à sa place** par la purge (« référencé en base, remis en place ») ; P13-11 : fichier en cours de dépôt épargné ; P13-12 : `.enc` mal rangé signalé `MAL_RANGE` et mis en quarantaine, code 0 ; nouveaux P13-13 (orphelin de moins de 60 min laissé en place, `RECENT`) et P13-14 (session `ged_app` ouverte → `--appliquer` refusé, rien déplacé) | **Vérifiée** |
| ANO-E10-008 (T-088) | b133653 (dev2) | instance B ; **`e10/verifier-modules.sh` 6/6** | M05 : dépôt `TD-FACT` sous la règle de Comptabilité → **0 circuit, document actif** ; journal : « Dépôt … sans circuit : règle … applicable, module workflow inactif ». M01–M04, M06 conformes | **Vérifiée** |
| ANO-E0-002 (T-085) | f2fba2f (dev2) | `mvn package` en ligne ; **`e10/sbom-et-licences-hors-ligne.sh`** 10 OK / 1 NA | `bom.json` : 145 composants = 140 Maven (139 distincts, identiques à la résolution runtime) + **Tesseract 5 et `tessdata-ara`, `-eng`, `-fra`, `-osd` 4.1.0**, licence Apache-2.0, SHA-256 identiques aux fichiers de `backend/tessdata` ; `bom.xml` aussi. Réserve : la version de Tesseract est « 5 » (paquet du serveur, 5.3.4 ici). **O1** : `mvn -o package` échoue désormais (« SBOM JSON absent ») | **Vérifiée** |
| ANO-E0-003 (P-19) | f2fba2f (dev2) | même script ; `node --test outils/tests/*.test.mjs` 6/6 | `mysql-connector-j` injecté → `--verifier` code 1 « Composants refusés présents dans le livrable » ; GPL-3.0-only seul sous `org.verapdf` → refusé ; GPL et AGPL non arbitrées refusées ; SBOM actuels acceptés | **Vérifiée** |
| ANO-E5-005 (T-059) | 5006a3b (dev3) | instance A ; octet 100 du chiffré du témoin E5-V01 inversé à 10:10:04 ; **Prometheus réel** | Passe de 10:12 : `ged_integrite_anomalies_total{statut="ALTERE"}` 0 → **1** (compteurs publiés à 0 dès le démarrage), `ged_integrite_derniere_passe_anomalies` **1**, audit `INTEGRITE_ANOMALIE` (FICHIER c316d941…, « ALTERE (version …) ») ; alerte **`GedIntegriteFichiersAnomalie` firing** à 10:12:08 ; `promtool test rules alertes-integrite.test.yml` SUCCESS. Fichier restauré à 10:12:15. `RecetteTour2` T059-R2-01 à 03 OK | **Vérifiée** |
| ANO-E10-002 (T-075, P-02) | bc2a920 (dev2), d7efa55 (dev1) | `e10/RecetteExploitation` ; instance C ; Prometheus réel | `ged_api_appels_total{application="qa-v8",cle="3c81648811ccaf3a",resultat="accepte",statut="200"}` (identifiant public de la clé) ; trois clés inventées ou fausses → `application="inconnue",cle="inconnue",resultat="refuse",statut="401"`. `ged_annuaire_compte_service_echeance_jours` **9,997** avec l'attribut à J+10 (NaN sans attribut, cas de l'annuaire embarqué) ; `GedAnnuaireSecretCompteServiceEcheance` **pending** (`for: 1h`). T075-01 : seule manque l'échéance des certificats, hors application (blackbox_exporter, accepté par l'anomalie) | **Vérifiée** |
| ANO-E10-006 (T-073) | bbbdf4f (dev2) | `recette-t073-p13.sh` T073-39 ; `test-restaurer-droits.sh` 7/7 | `datacl` et réglages `ALTER ROLE … IN DATABASE` identiques après restauration logique ; rien pour PUBLIC ; CONNECT refusé à un rôle hors des trois | **Vérifiée** |
| ANO-E7-006 (T-101, R-03, D10) | 52f4242 (dev1) | **`e10/RecetteTour2`** e7004 e7005 t059 **11/11** | E7005-R2-05 : document mis à la corbeille avant l'archivage du dossier puis restauré → 204, document **ARCHIVE**, **copie de conservation PDF/A-2B VALIDE** créée (méthode RASTERISATION), versement et fiche **409 `DOCUMENT_ARCHIVE`** ; E7005-R2-01 à 04 et E7004-R2-01 à 03 toujours conformes | **Vérifiée** |

## 3. Lignes et réserves recettées

| Ligne | Recette | Résultat | Verdict proposé |
|---|---|---|---|
| T-034 (§4.3.4) | `qa/r3/t034.sh` (instance A, reprises 10 s / 20 s / 30 s) | PDF corrompu (scan tronqué à 4000 octets) : 10:07:41 dépôt 202 ; tentative 1 en échec « FICHIER_CORROMPU : PDF illisible : Page tree root must be a dictionary », reprise à +10 s ; tentative 2, reprise à +20 s ; tentative 3, reprise à +30 s ; **tentative 4 → `OCR_ECHEC`** à 10:08:41, motif conservé ; texte « interrogeable: false », téléchargement 200, fiche `OCR_ECHEC`. Valeur livrée `delais-reprise: 1m,5m,30m` (≈ 36 min en production). L'observation O6 de la vague 8 est levée | **Identique** |
| T-006 (§2.2) réserve IPv6 | **`e10/verifier-nginx-reel.sh` 13/13** (adapté, §8) ; `deploiement/nginx/tests/test-nginx-ipv6.sh` | `ged.conf` livré : **aucun `listen [::]` inconditionnel** (N00) ; plus aucune ligne retirée par la recette : `nginx -t` OK et NGINX démarre tel quel (N01, N02–N10) ; variante avec `ecoute-ipv6-http.conf` et `-https.conf` installés : **syntaxe acceptée**, ouverture refusée (errno 97) sur ce poste sans IPv6, comme prévu par `EXPLOITATION.md` §3 (N01b). Le test de dev2 donne les mêmes constats mais **sort en code 1** après « RÉUSSI » → **ANO-E10-009** (outil de test, pas la configuration) | Réserve **levée** ; **Identique (réserve UAT)** : certificat et hôte de MMED, variante IPv6 à éprouver sur un hôte IPv6 |
| T-066 (§6.1.5, §6.2) | même script | N02 301 sans version, N03 TLS 1.2/1.3 seuls, N04 en-têtes, N05 paquet et `config.json`, N06 adresse du client, N07 429, **N08a 211 Mo → 413 NGINX, N08b 205 Mo → 413 problem+json**, N09 aucun tampon disque pour 150 Mo, N10 `traceparent` | **Identique (réserve UAT)** (certificat de MMED) |
| T-062 / P-07 | §2 ANO-E5-004 ; `RecetteExploitation` P07-01 | Tailles 4/4 ; métadonnées de 70 Ko → 413 `METADONNEES_TROP_VOLUMINEUSES` (multipart et JSON), 60 Ko acceptées | **Identique** (les deux) |
| T-038 (§4.4) | §2 ANO-E6-001 ; E6 19/19 | websearch, exclusion, extraits en segments jamais en HTML, pertinence, une ligne par document, périmètre ; 5 NA : scans de 20 pages non générés (`generer-donnees.sh --pages-scan 20`), exercés en vague 3 | **Identique** |
| T-104 (§12.7) index d'expression | **`e10/verifier-index-expression.sh`** (nouveau, §8) sur la copie peuplée (100 000 documents) ; API sur l'instance T | Fonctions immuables (I01) ; index employés avec constantes (I03), avec paramètres liés comme l'application (I04), après 8 exécutions préparées (I05) ; résultats identiques avec et sans index, valeurs mal formées jamais retenues, **0,9 ms avec l'index contre 498 ms sans** (I07). **Mais** l'index de `DEPLOIEMENT.md` §8 **ne se crée pas** (I02) et toute lecture parallèle d'un critère date échoue (I08) : `meta_date` est `PARALLEL SAFE` avec un bloc `EXCEPTION`. Bout en bout : `POST /documents/recherche` critère `QA_T104_DATE` → **500** ; critère nombre → 200 ; avec l'index : plage étroite 200 (63 ms), plage large ou « renseignée » 500 → **ANO-E7-007**. I06 (AVERT) : en plan générique forcé, l'index ne sert plus (code en paramètre) ; sans effet en mode `auto` | **Non conforme** (ANO-E7-007) : reste « Vérifié » |
| T-035 (R31, D6) | instance T : reprise à blanc simulée — 80 versions en clair (`file_path`, sans `cle_fichier`) sous une ancienne racine, `--ged.fichiers.reprise.source` | Reprise : « 80 reprise(s), 0 échec(s) », 80 jobs en priorité 1. Pendant la reprise (70 en attente, 2 en cours), un **dépôt courant** (scan de 2 pages) passe **`OCR_TERMINE` en 9 s**, alors que **67 jobs de reprise attendent encore** ; 3 jobs de reprise seulement se terminent après le dépôt (ceux déjà en cours). La reprise réelle (données MySQL de MMED, ~150 000 documents) reste à rejouer à la Phase 7 | **Identique (réserve UAT)** : volume réel de la reprise |
| T-059 (§6.1.4) | §2 ANO-E5-005 ; `RecetteTour2` T059 | divergence = métrique, alerte réelle et audit ; vérification à la demande (document, fonds) | **Identique** |
| T-073 / P-13 (§6.5) | §2 ANO-E10-006, ANO-E10-007 | 40 OK, 0 ÉCHEC ; restent 2 AVERT de forme déjà connus (P13-05 : le rapport donne l'identifiant de fichier et non le document ; P13-06 : aucun statut « à ré-importer » en base) ; RTO de 1 s pour 207 fichiers, non représentatif | **Identique (réserve UAT)** : volume, supports et GPG de MMED |
| T-075 (§6.7) | §2 ANO-E10-002 ; Prometheus réel | 14 règles, `promtool check rules` et `test rules` verts ; alertes réellement évaluées (`GedIntegriteFichiersAnomalie` firing, `GedAnnuaireSecretCompteServiceEcheance` et `GedAnnuaireControleurIndisponible` pending) | **Identique (réserve UAT)** : blackbox_exporter et Alertmanager de MMED |
| P-02 (§3.3, D4) | §2 ANO-E2-002, ANO-E10-002 | bascule, mise à l'écart, échéance du secret | **Identique (réserve UAT)** : AD réel en LDAPS, lecture de `msDS-UserPasswordExpiryTimeComputed` / `accountExpires` par le compte de service (DSI de MMED) |
| T-085 / P-19 | §2 ANO-E0-002, ANO-E0-003 | SBOM complet et contrôle des licences durci | T-085 **Identique (réserve UAT)** (version exacte de Tesseract du serveur) ; P-19 **Identique** |
| T-088 (§9.3) | §2 ANO-E10-008 ; `ng test` 148/148 | critère 2 (menus et actions masqués) : tests verts ; critère 1 éprouvé par T-092 ; `deploiement/uat/demontrer-deploiement.sh` toujours non rejouable ici (hôte et port en dur, O3 de la vague 9) | **Identique (réserve UAT)** : démonstration de déploiement sur l'UAT |
| T-101 (§12.6) | §2 ANO-E7-006 | cycle archivage / corbeille / restauration fermé | **Identique** ; R-03 attend en plus ANO-F-016 (qa2) |
| P-14 (R32) | §2 ANO-E6-001 | recherche tenue avec un texte de 150 Mio | **Vérifié** ; reste l'écart de débit OCR (R30) à porter à MMED |
| P-05 (`SEQUENCES.md`) | relecture | note du flux 4 toujours « à livrer par dev1 (T-055) » à `ff20f21` | inchangé (« Vérifié ») |

## 4. Non-régression

- `e10/RecetteExploitation` 9 OK / 2 ÉCHEC : T075-01 (échéance des certificats, hors application, voir ANO-E10-002)
  et P08-01 (instance A lancée sans la dépréciation de démonstration de la vague 8 : pas d'en-têtes
  `Deprecation`/`Sunset` à constater) — réglage de l'environnement, pas une régression. T050-01, T069-01, T074-01,
  T075-02, T115-01/02, P07-01, P08-02, P04-01 conformes.
- `e6/RecetteOcrRecherche` 19/19 ; `e10/RecetteTour2` 11/11 ; `e5/verifier-taille.sh` 4/4 ;
  `e10/verifier-modules.sh` 6/6 ; `verifier-nginx-reel.sh` 13/13 ; `recette-t073-p13.sh` 40/40 hors AVERT.

## 5. Anomalies nouvelles

| Id | Membre | Gravité | Résumé |
|---|---|---|---|
| ANO-E7-007 | dev1 | Majeure | `meta_date` déclarée `PARALLEL SAFE` avec un bloc `EXCEPTION` : recherche sur une métadonnée date en 500 dès un plan parallèle (100 000 documents) ; l'index d'expression de `DEPLOIEMENT.md` §8 ne se crée pas |
| ANO-E10-009 | dev2 | Mineure | `test-nginx-ipv6.sh` imprime « RÉUSSI » mais sort en code 1 sur un hôte sans IPv6 (`kill ''` dans le piège sous `set -e`) et laisse son répertoire de travail (trois répertoires `ged-nginx.*` du 30/09 encore dans `/tmp`) |

Observations (sans anomalie) :
- **O1** : depuis f2fba2f, `mvn -o package` (hors ligne) **échoue** : le goal CycloneDX exige le mode en ligne, puis
  `completer-sbom.mjs` sort en 2 (« SBOM JSON absent »). Documenté (`-Dged.sbom.completer.skip=true`, SBOM alors
  incomplet), mais une construction hors ligne (poste isolé, forge interne de MMED sans accès) ne produit plus de
  JAR sans cette option. À écrire dans la procédure de construction, ou rendre l'étape non bloquante hors ligne.
- **O2** : l'alerte `GedIntegriteFichiersAnomalie` reste active une heure après une détection, même fichier
  restauré (première ligne de la règle, choix documenté) ; un redémarrage de l'application en cours de fenêtre
  (compteur remis à 0) a donné une valeur de 3 à `increase()`. Sans effet sur le déclenchement.
- **O3** : `meta_date` / `meta_nombre` reçoivent le code de la métadonnée en paramètre lié : en plan générique
  (`plan_cache_mode = force_generic_plan`), l'index d'expression ne sert plus (I06). Sans effet avec le réglage par
  défaut (`auto`, I05) ; à garder en tête si l'exploitation forçait les plans génériques.

## 6. Suite automatisée

`mvn -B -q -o test` sur `ged_qa_test`, instances arrêtées, pool Hikari de 3 : **661 tests, 0 échec, 0 erreur, 0 ignoré (109 classes, 4 min 30 s)**. Référence après le tour 2 : 661 tests, 0 échec.
Front : `ng build` vert, `ng test` **148/148** (35 fichiers).

## 7. Ce qui est éprouvé en réel sur ce poste, et ce qui reste propre à MMED

Réel ici : NGINX 1.24 (configuration livrée sans retouche des écoutes, variante IPv6 constatée refusée sans
IPv6), Prometheus 2.45 (règles livrées évaluées, alertes d'intégrité et d'échéance réellement levées), clamd,
LibreOffice, Tesseract (reprises d'un PDF corrompu), PostgreSQL 16 (sauvegarde, restauration et droits,
rapprochement, index d'expression et plans parallèles sur 100 000 documents, reprise à blanc de 80 versions en
clair avec priorité du flux courant), SBOM CycloneDX Maven produit par `mvn package`.
Reste à MMED / UAT : AD réel (bascule entre contrôleurs réels, attributs d'échéance lus par le compte de service),
certificat et hôte NGINX (IPv6 éventuel), blackbox_exporter et Alertmanager, volumes réels (reprise MySQL ~150 000
documents, RTO, débit OCR R30), version de Tesseract du serveur, démonstration de déploiement sur l'UAT.

## 8. Scripts de recette modifiés ou ajoutés

- `recette/e10/sbom-et-licences-hors-ligne.sh` : copie de **tous** les modules `outils/*.mjs` et du dossier
  `backend/tessdata` (le registre importe désormais `composants-hors-gestionnaire.mjs` ; sans cela, l'outil
  plantait et le plantage passait pour un refus) ; un refus doit **citer le composant injecté** ; Tesseract et les
  quatre modèles exigés avec version et SHA-256 ; composants `pkg:generic` exclus de la comparaison avec la
  résolution Maven.
- `recette/e10/verifier-nginx-reel.sh` : plus aucun `listen [::]` retiré ; N00 vérifie l'absence d'écoute IPv6
  inconditionnelle ; N01b éprouve la variante avec les fichiers `ecoute-ipv6-*.conf` (`/etc/nginx/ged/` adapté).
- `recette/e10/verifier-annuaire-bascule.sh` : l'adresse source de chaque connexion change vraiment (l'incrément
  se perdait dans une substitution `$(…)` : toutes les connexions partaient de 127.0.0.11 et A04 recevait 429).
- `recette/e10-sauvegarde/recette-t073-p13.sh` : `--age-minimal-minutes 0` pour les contrôles de quarantaine
  (P13-02/08/10/12), P13-10 exige la remise en place, P13-12 le signalement `MAL_RANGE`, nouveaux P13-13 (`RECENT`)
  et P13-14 (garde « application arrêtée »).
- `recette/e10/verifier-index-expression.sh` (nouveau, T-104) : I01 à I08 sur une copie peuplée jetable.

## 9. Fin de vague

Instances A, B, C et T, NGINX, Prometheus, clamd et les trois contrôleurs d'annuaire simulés arrêtés ; instance
PostgreSQL jetable (55494) arrêtée et supprimée, avec la copie `ged_qa_t104`. Les données de recette ajoutées à
`ged_qa` (documents `qar3-*`, texte de 150 Mio ; les index `QA_T104_*` n'ont existé que dans la copie) y
restent, comme aux vagues précédentes. Aucun processus d'un autre membre n'a été touché.

## 10. Synthèse pour le suivi

- **Peuvent passer « Identique »** : T-034, T-038, T-059, T-062, P-07, P-19, T-101.
- **Peuvent passer « Identique (réserve UAT) »** : T-006, T-066, T-035, T-073, P-13, T-075, P-02, T-085, T-088.
- **Restent « Vérifié »** : T-104 (ANO-E7-007), P-14 (écart de débit OCR R30), R-03 (ANO-F-016, qa2), P-05 (note du
  flux 4).
