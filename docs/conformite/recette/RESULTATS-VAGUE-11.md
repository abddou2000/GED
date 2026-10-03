# Résultats de recette — vague 11 (tour 4 : vérification finale, levée des dernières réserves)

Exécutés par qa le 03/10/2026 sur `ct/qa-r4`, créée depuis `claude/inspiring-lovelace-10bg1c` (**a7343b8** : tour 3
intégré, puis tour 4 — `7af504d` fusion de ct/dev1-r4 (`e16eb01`, ANO-E7-007), `d7c3742` fusion de ct/dev2-r4
(`1068003`, ANO-E10-009 ; `86a1bb2`, construction hors ligne), `a7343b8` suivi de pm). Aucun code applicatif
modifié ; scripts de recette ajoutés ou complétés (§9).

## 1. Environnement

| Élément | Ce poste | Réel / simulé |
|---|---|---|
| Application | JAR construit sur `ct/qa-r4` par `mvn -o package` (hors ligne : **réussit** désormais, avec l'avertissement « SBOM INCOMPLET … à ne pas livrer tel quel », O1 de la vague 10 levée) ; profil dev, API **18084**, management **18094** ; base `ged_qa` (changeset `202610051000` appliqué au démarrage) ; `ged_qa_test` pour `mvn test` | réel |
| Instances | **A** nominale sur `ged_qa` (clamd réel, dépréciation de démonstration `/api/v1/etiquettes` pour P-08) ; **T** sur une **copie peuplée** de `ged_qa` (100 000 documents) dans une instance PostgreSQL 16 privée et jetable (127.0.0.1:55494), base réglée pour **forcer les plans parallèles** | réel |
| PostgreSQL | instance partagée (bases `ged_qa*` seulement ; `ged_qa` lue par `pg_dump`) ; instance privée jetable pour T-104 ; instance jetable créée et supprimée par `demontrer-deploiement.sh` (T-088) | réel |
| NGINX | 1.24.0, `deploiement/nginx/ged.conf` livré, poste **sans IPv6** | réel ; certificat auto-signé |
| ClamAV, Tesseract | clamd 1.5.4 (signature EICAR seule), Tesseract 5.3.4 | réels |
| Annuaire | UnboundID embarqué (`annuaire-dev.ldif` + `annuaire-recette.ldif`) | simulé |
| Écran | aucun navigateur sur le poste (téléchargement de Chrome refusé par le mandataire, 403) : l'application Angular **complète** est rendue dans jsdom par le lanceur de tests du front, contre l'instance réelle (§4, P-04) | DOM et routage réels, sans rendu graphique |
| Front | Node 24.21.0 de l'équipe : `ng build` vert (avertissements de budget connus), `ng test` **196/196** (44 fichiers) | réel |
| CI GitHub | lue en lecture seule par `gh api` (exécutions, jobs, étapes) ; journaux et artefacts non lisibles (servis par un hôte que le client `gh` du poste refuse) | réel |

Tout ce que qa a lancé a été arrêté en fin de vague (§10). Le PostgreSQL partagé n'a jamais été arrêté.

## 2. Revérification des anomalies « Corrigée »

| Anomalie | Correctif | Recette rejouée | Constat | Verdict |
|---|---|---|---|---|
| ANO-E7-007 (T-104) | e16eb01 (dev1) | `e10/verifier-index-expression.sh` (complété : **I09**, **I10**) sur la copie peuplée, **avant** puis **après** la migration ; API sur l'instance T avec plans parallèles forcés et `auto_explain` | **Avant** (corps d'origine, 108 changesets) : I02 et I08 ÉCHEC « cannot start subtransactions during a parallel operation » — le contrôle sait échouer. **Après** (`202610051000` appliqué par l'application) : **9 OK / 0 ÉCHEC / 1 AVERT** (I06, connu). I02 : les deux index de `DEPLOIEMENT.md` §8 se construisent **en parallèle** (5 s pour 100 000 documents) ; I08 : critère date en plan parallèle → compte 54 568 ; **I09** : `parallel_setup_cost = 0`, `parallel_tuple_cost = 0`, `min_parallel_table_scan_size = 0`, `min_parallel_index_scan_size = 0`, `max_parallel_workers_per_gather = 2`, avec et sans index : date large (99 800), étroite (389), renseignée (99 800), nombre (52), nombre renseigné — 10 requêtes, toutes en plan parallèle, mêmes comptes qu'en série, avec une ligne « 0000-01-01 » / « 1e1000000 » présente ; **I10** : sémantique identique aux corps d'origine sur 5 091 dates (11 années limites dont 0000, 1582, 1900, 2000, 2100, 9999, mois 00 à 13, jours 00 à 32, 3 653 valides) et 34 nombres limites (8 débordements de `numeric` → NULL au lieu d'une erreur). Corps d'origine remis le temps d'un essai : plan parallèle forcé → « cannot start subtransactions … » ; dépôt d'une métadonnée « 1e1000000 » avec l'index de montant → « value overflows numeric format » (**l'ancien `meta_nombre` faisait échouer l'écriture**, pas seulement la recherche) ; corps livrés rétablis (empreinte identique) : 99 800, dépôt accepté. **Bout en bout** (`ALTER DATABASE … SET` des cinq réglages, vus par `ged_app`) : `POST /api/v1/documents/recherche` **14 / 14 en 200** — `{de, a}` étroit (389), `de` seul (99 800), `a` seul (99 800), « renseignée » (99 800), nombre `{de, a}` (52), nombre `de` (99 899), date + nombre (99 699) — avec puis sans index d'expression ; `auto_explain` : `Parallel Seq Scan on document d … Filter: meta_date(…) >= … AND meta_nombre(…) >= …`, **Workers Launched: 2** ; 0 erreur au journal PostgreSQL et de l'application | **Vérifiée (e16eb01)** |
| ANO-E10-009 (T-006) | 1068003 (dev2) | `deploiement/nginx/tests/test-nginx-ipv6.sh; echo $?` sans IPv6, `TMPDIR` dédié ; sous `nobody` ; autotest ; interruption | Toutes les lignes `[OK]`, `[N/A] démarrage et HTTP [::1] → 301 : hôte sans IPv6`, « RÉSULTAT : RÉUSSI — NON APPLICABLE sur cet hôte … », **code 0**, `TMPDIR` vide, aucun NGINX laissé. Compte non root : code 0, rien laissé. Copie avec `ged.conf` altéré : 4 `[ÉCHEC]`, « 4 contrôle(s) en échec », **code 1**, répertoire supprimé. SIGTERM pendant l'essai : code 143, répertoire supprimé, aucun NGINX. Les trois `ged-nginx.*` du 30/09 (ancien script) restent dans `/tmp` | **Vérifiée (1068003)** |

## 3. Lignes recettées (tour 4)

| Ligne | Recette | Résultat | Verdict proposé |
|---|---|---|---|
| T-104 (§12.7) | §2 ANO-E7-007 | colonne JSONB et GIN (vague 1), validation contre le plan (vague 6), index d'expression éprouvés sur 100 000 documents (0,8 ms avec index contre 307 ms sans, résultats identiques), sûrs en plan parallèle, sémantique inchangée ; reste I06 (plan générique forcé, sans effet en mode `auto`, observation O3 de la vague 10) | **Identique** |
| T-006 (§2.2) | §2 ANO-E10-009 ; **`e10/verifier-nginx-reel.sh` 13/13** (N00 à N10) | configuration livrée telle quelle sous NGINX 1.24 réel ; variante IPv6 acceptée en syntaxe et refusée à l'ouverture sans IPv6, comme `EXPLOITATION.md` §3 le prévoit ; outil de test cohérent | **Identique (réserve UAT)** : certificat et hôte de MMED, démarrage en `[::1]` à éprouver sur un hôte IPv6 |
| T-088 (§9.3) | **`deploiement/uat/demontrer-deploiement.sh`** (dev2 `00d487e`), `DEMO_INSTANCE_JETABLE=oui`, ports de qa (18084, 18094, 33394, HTTPS 18584, HTTP 18684, PostgreSQL 18784), mots de passe tirés pour l'essai ; `ng test` 196/196 | **43 contrôles, tous verts** (E0 à E8) : première installation, déploiement v2 (sauvegarde, tag, migration, bascule, fumée), retour arrière avec la base, back seul, front seul et son retour arrière sans redémarrage du service (même PID), v3 défectueuse → **retour automatique** à v2, défaire la migration de v3 avec le JAR qui l'a appliquée, module `workflow` désactivé (404 `MODULE_INACTIF` à travers NGINX) puis réactivé ; instance jetable supprimée, instance partagée jamais touchée. Critère 2 : `workflow-masque.spec.ts`, `profil.spec.ts`, `type-document-detail.spec.ts`, `workspace-detail.spec.ts` verts (front 196/196). Seul systemd est simulé | **Identique (réserve UAT)** : rejouer sous systemd réel sur le serveur UAT (`DEPLOIEMENT.md` §10.5 : durcissement de l'unité, `Restart=on-failure`, `RequiresMountsFor`) |
| T-050 (§5.3.2) | **`e10/RecetteTour4`** T050-R4-01 à 05 ; `RecetteExploitation` T050-01 | **50 par défaut, 200 au plus, `size` et alias `taille`** sur les quatre recherches : `GET /recherche/plein-texte` (défaut 50 et non plus 20 ; `size=2` → 2, `taille=2` → 2, `size=100000` → 200), `POST /recherches` sans et avec texte (`size=1` + `taille=2` → 1 : `size` l'emporte), `POST /documents/recherche` (alias `taille`), `POST /indexation/recherche` (`size` en plus de `taille`) ; `GET /workflow/a-traiter` : 50 par défaut (et non 20), plafond 200 (et non 100), `taille=3` → 3, `page=-1` → 400 ; `GET /documents` : tri en liste blanche, total au seul périmètre | **Identique** |
| P-08 (§5.3.2) | **`RecetteTour4`** P08-R4-01 à 06 ; `RecetteExploitation` P08-01, P08-02 (instance avec dépréciation de démonstration) | Champs inconnus **ignorés et signalés** au lieu du 400 : `POST /documents/recherche {"confidentialit":…}` → 200, `GED-Champs-Ignores: confidentialit`, total inchangé (125, aucun filtre appliqué) ; `POST /recherches` → `deposant, confidentialit%C3%A9` (nom accentué encodé) et `criteres[0].valeurr` (chemin imbriqué) ; `GET /recherche/plein-texte?…&dateDu=` → `dateDu`, total inchangé ; `POST /indexation/recherche {"taile":10}` → `taile` ; requête toute connue : aucun en-tête ; valeur hors liste, bornes inversées : toujours 400. **CORS** : `Access-Control-Expose-Headers: GED-Champs-Ignores` pour l'origine admise, pré-vol 200, origine étrangère 403. OpenAPI : en-tête décrit, `PARAMETRE_INCONNU` plus annoncé (constante gardée au catalogue, jamais émise). Versionnement : `Deprecation: @1790812800`, `Sunset`, `Link rel="successor-version"` sur le seul préfixe annoncé ; corps de création avec champs inconnus → 201 | **Identique** (le volet « paramètre inconnu → 400 » d'ANO-F-011 est à rejouer par qa2 avec le nouveau comportement) |
| P-04 (§3.4.2, D1) | **`e10/ecran/recette-p04-ecran.spec.ts`** : application Angular complète (`appConfig` : intercepteurs, routes, gardes, initialiseurs) rendue dans jsdom, écran de connexion rempli et soumis, appels vers l'instance A réelle ; `RecetteExploitation` P04-01 | Compte **réel** de l'annuaire, provisionné **sans rôle** (`qanouveau1`) : après connexion → `/accueil`, « Bienvenue QA — Votre accès à la GED est ouvert, mais aucun rôle ne vous est encore attribué. L'Administrateur de la GED vous donnera accès… » ; menu = **« Accueil » seul** ; ni cloche, ni « Mes exports » ; carte du compte : « Se déconnecter » sans « Mon profil » ; `/espaces-de-travail` → ramené à `/accueil` ; **un seul appel au serveur** (`/auth/login`) : ni `/modules`, ni `/notifications/compteur`. Journal d'audit : `CONNEXION_REUSSIE qanouveau1`, `DECONNEXION`, `CONNEXION_REUSSIE sbennani`. Puis compte avec rôles (`sbennani`) : accueil habituel, **20 menus**, cloche, appels `/modules`, `/workflow/a-traiter`, `/notifications/compteur`, `/stats/overview`. API : identité sans rôle, `/auth/me` seul accessible, tout le reste 403. Rôles conservés à la réactivation : P04-02 (vague 8) | **Identique (réserve UAT)** : compte AD réel de MMED (provisionnement par l'annuaire réel), rendu dans un vrai navigateur |
| P-05 (§2.3.1, §4.5) | relecture de `docs/modelisation/SEQUENCES.md` (dev2 `3adbf0b`) contre `a7343b8` ; `SequencesDocumenteesTest` dans la suite | Six flux conformes au code : note du flux 4 corrigée (D15 livrée), 503 `ANNUAIRE_INDISPONIBLE` représenté, recherche par index ajoutée (tri en liste blanche, `Index inconnu` → 400, `meta_date` / `meta_nombre`), P-08 (`ChampsInconnusSignales`, en-tête) ; classes et codes cités présents. Forme : le corps listé au flux 3 omet `size` et celui du flux 4 omet l'alias `taille` (T-050) — sans effet sur la lecture | **Identique** |
| P-14 (§6.6) | banc OCR réduit (§3, T-028) ; ANO-E6-001 vérifiée (vague 10) ; R32 remesuré par dev3 (vague 9, sur le papier) | Recherche : bornée et tenue (150 Mio de texte, 100 000 documents en plan parallèle). OCR : **5,1 s de CPU par page et par cœur** (11,7 pages/min/cœur) sur ce poste, contre 1 à 3 s au §4.3.4 : l'écart **R30** demeure | **Reste « Vérifié »** : R30 attend la décision de MMED (cibles des §4.3.4 / §6.6, ou worker OCR à 16 vCPU) — c'est un écart à arbitrer, pas une réserve d'infrastructure ; une fois tranché, réserve UAT seulement (mesure sur l'échantillon de la Phase 7 et le matériel de MMED) |

### Lignes encore « Livré » dans `SUIVI.md`

| Ligne | Recette | Résultat | Verdict proposé |
|---|---|---|---|
| T-028 (§4.3.2) | **`e10/banc-ocr-reduit.sh 2 ara+fra`** (Tesseract 5.3.4 réel, 4 processeurs) | CER français 1,41 % (≤ 5 %), arabe 2,63 % (≤ 10 %), arabe dégradé 7,25 % (≤ 10 %) ; bilingue 1 bit 6,76 % (NA) ; **débit 11,7 pages/min/cœur** (seuil ≥ 6) ; corpus généré à vérité connue | **Vérifié** sur corpus généré ; pas « Identique » : échantillon de MMED (Q09) et comparaison sans Python (QR8) |
| T-070 (§6.2.3 A06) | `npm audit` réel (registre npm) ; CI GitHub lue par `gh api` | Contrôle npm **effectif et bloquant** : 1 haute (`@angular/router`, GHSA-ff3f-86qr-9cv3) + 6 moyennes → code 1 ; job front de la CI **rouge depuis le 01/10** → **ANO-E0-004** ; OWASP Dependency-Check : job « Analyser les dépendances » toujours en échec (secret `NVD_API_KEY` absent) ; chaîne éprouvée sur miroir synthétique (vague 9, inchangée) | **Reste « Livré »** : secret NVD à créer, ANO-E0-004 ouverte |
| T-089 (§9.4) | `gh api repos/abddou2000/GED` et `/branches` | dépôt **public** d'un compte personnel (`owner.type = User`), **aucune branche protégée** (`main`, `conformite-technique`, `claude/inspiring-lovelace-10bg1c`, `ct/qa`, `ct/qa2`) : état conforme à ce que `FORGE.md` décrit, réglages non appliqués | **Reste « Livré (procédure) »** : réglages à appliquer par le propriétaire (Q19) |

## 4. P-04 à l'écran : mode opératoire

`recette/e10/ecran/recette-p04-ecran.spec.ts` n'est pas un test du dépôt : il est copié dans `frontend/src/app/`
le temps d'une exécution (`ng test --include`), puis retiré. Il rend la racine `App` avec les fournisseurs
d'`appConfig` (aucun bouchon : intercepteurs `probleme`, `idempotence`, `auth`, `demo`, routes et gardes réels), remplit
le formulaire de connexion et le soumet ; le `fetch` de Node achemine les chemins relatifs (`/api/v1/…`) vers
l'instance (`GED_URL`), comme NGINX derrière le paquet, et `assets/config.json` répond `{"demo":false}`. Les
appels sont relevés pour vérifier qu'aucune requête refusée (403) n'est émise par l'écran d'un compte sans rôle.

## 5. Anomalies nouvelles

| Id | Membre | Gravité | Résumé |
|---|---|---|---|
| ANO-E6-002 | dev3 | Mineure | Plan d'indexation **manuel**, valeur d'index de plus de 255 caractères : le temps 2 du dépôt échoue sur une erreur SQL (`document.reference` en `varchar(255)`, non contrôlée en mode manuel) et `motifIndexation` renvoie au client le **texte brut de l'exception JDBC** (requête `update document set …` complète). Le dépôt reste conforme au §12.11 (202 `A_INDEXER`, fichier acquis) ; la saisie par `PUT /indexation/documents/{id}` répond proprement 400 `DONNEE_REFUSEE` |
| ANO-E0-004 | dev4 (dev2) | Majeure | `npm audit --omit=dev --audit-level=high` : 1 vulnérabilité **haute** dans le front livré (`@angular/router` 22.0.8, GHSA-ff3f-86qr-9cv3, corrigée en 22.2.0) ; le job front de la CI échoue depuis le 01/10 et n'archive plus ni le paquet ni son SBOM ; `VULNERABILITES-DEPENDANCES.md` §3 annonce encore « 0 haute, job vert ». Exposition nulle (pas de rendu serveur), mais correction due « à la livraison corrective suivante » selon la politique du projet |

Preuve d'ANO-E6-002 (`qa/r4/ref-longue.sh`, instance A) :

```
== plan manuel=true, valeur de 300 caractères
dépôt : HTTP 202 {"statutIndexation":"A_INDEXER","motifIndexation":"Métadonnées non enregistrées : could not execute statement
        [ERROR: value too long for type character varying(255)] [update document set active=?,application_id=?, … ]; SQL [update document set …]"}
saisie PUT /indexation/documents : HTTP 400 {"code":"DONNEE_REFUSEE","detail":"Donnée refusée par la base : une valeur dépasse la longueur autorisée pour sa colonne (255 caractères pour un champ texte)."}
saisie de 200 caractères : HTTP 200 ; statut INDEXE
== plan manuel=false, valeur de 300 caractères
dépôt : HTTP 202 {"statutIndexation":"A_INDEXER","motifIndexation":"Métadonnées non enregistrées : Le champ « nom composé du document » ne peut pas dépasser 255 caractères (reçu : 300)."}
```

Observations (sans anomalie) :

- **O1** — CI GitHub, job **back-end** : vert jusqu'à `66af618` (01/10), **rouge** sur `a7343b8` (03/10, exécution
  37124893548, étape « Compiler, tester, construire le JAR et le SBOM » en échec après 3 min 19 s ; l'exécution verte
  précédente durait 3 min 48 s). Le journal et l'artefact `rapports-tests-backend` ne sont pas lisibles depuis ce poste.
  Commits nouveaux depuis la dernière exécution verte : `1068003` (dont le test qui lance `test-nginx-ipv6.sh` : sur un
  exécuteur GitHub, NGINX est présent et IPv6 actif, la branche `[::1]` y est exercée pour la première fois), `86a1bb2`
  (SBOM obligatoire en CI), `e16eb01`. Localement, la suite complète est verte (§6) et le script passe sous un compte non
  root ; la branche IPv6 n'est pas exerçable ici. **À lire sur GitHub par pm ou dev2** (journal du job) ; à requalifier
  en anomalie si la cause est dans le livrable.
- **O2** — `mvn -o package` réussit désormais hors ligne, avec un avertissement explicite « SBOM INCOMPLET … à ne pas
  livrer tel quel » (O1 de la vague 10 levée par `86a1bb2`).
- **O3** — l'ancien `meta_nombre` faisait aussi échouer l'**écriture** d'un document dès qu'un index d'expression de
  montant existait et qu'une valeur comme « 1e1000000 » arrivait (« value overflows numeric format ») ; `e16eb01` le
  corrige (I09, autotest du §2).

## 6. Suite automatisée

`mvn -B -q -o test` sur `ged_qa_test`, instances arrêtées, pool Hikari de 3 : **687 tests, 0 échec, 0 erreur, 0 ignoré
(114 classes, 2 min 43 s)**. Référence après le tour 3 : 681 tests verts ; les 6 nouveaux viennent du tour 4
(`MetadonneesPlanParalleleTest`, `SchemaLiquibaseTest`, `ScriptsExploitationTest.testNginxIpv6CodeDeSortieEtNettoyage`,
exécuté ici avec NGINX réel).

Front : `ng build` vert (avertissements de budget : bundle initial, `profil.scss`, police Fraunces), `ng test`
**196/196** (44 fichiers) ; référence du tour 3 : 196/196.

## 7. Non-régression

- `e10/RecetteExploitation` **10 OK / 1 ÉCHEC** : T075-01 (échéance des certificats, hors application, déjà acceptée
  par ANO-E10-002) ; P08-01 conforme cette fois (instance lancée avec la dépréciation de démonstration).
- `e10/verifier-nginx-reel.sh` 13/13 ; `deploiement/nginx/tests/test-nginx-ipv6.sh` code 0 ;
  `demontrer-deploiement.sh` 43/43 ; banc OCR réduit 4 OK / 2 NA.

## 8. Ce qui est éprouvé en réel sur ce poste, et ce qui reste propre à MMED

Réel ici : PostgreSQL 16 (plans parallèles forcés, index d'expression construits en parallèle, 100 000 documents),
NGINX 1.24, clamd, Tesseract, déploiement complet par `deployer.sh` (v1 → v2 → retour arrière → v3 défectueuse →
retour automatique → module désactivé/réactivé) avec NGINX et Liquibase CLI réels et systemd simulé, écran Angular
réel contre l'API réelle (sans navigateur graphique), CI GitHub (lecture).
Reste à MMED / UAT : systemd réel du serveur UAT (T-088), certificat et hôte NGINX, IPv6 éventuel (T-006), AD réel
(P-04), échantillon de la Phase 7 et décision sur R30 (P-14, T-028), secret NVD et réglages de forge (T-070, T-089).

## 9. Scripts de recette modifiés ou ajoutés

- `recette/e10/verifier-index-expression.sh` : **I09** (plan parallèle forcé, avec et sans index, cinq critères,
  ligne limite « 0000-01-01 » / « 1e1000000 », comptes comparés à la lecture en série) et **I10** (sémantique de
  `meta_date` / `meta_nombre` comparée en série aux corps d'origine sur 5 091 dates et 34 nombres limites).
- `recette/e10/RecetteTour4.java` (nouveau) : T-050 (T050-R4-01 à 05) et P-08 (P08-R4-01 à 06) contre une instance.
- `recette/e10/ecran/recette-p04-ecran.spec.ts` (nouveau) : P-04 à l'écran, application Angular complète contre
  l'instance réelle (§4).

## 10. Fin de vague

Instances A et T arrêtées ; clamd arrêté ; instance PostgreSQL privée (55494) arrêtée et supprimée avec la copie
`ged_qa_t104` ; démonstration T-088 : service, NGINX et instance jetable arrêtés par le script, répertoire
`/tmp/ged-demo-qa` supprimé (journal des déploiements archivé dans le bloc-notes de qa). Données de recette ajoutées à
`ged_qa` (documents `qav8-*`, type `QA-R4…` d'ANO-E6-002, index `QA_T104_*` seulement dans la copie) laissées en
place, comme aux vagues précédentes. Aucun processus d'un autre membre n'a été touché.

## 11. Synthèse pour le suivi

- **Anomalies vérifiées** : ANO-E7-007 (`e16eb01`), ANO-E10-009 (`1068003`). **Nouvelles** : ANO-E6-002 (Mineure,
  dev3), ANO-E0-004 (Majeure, dev4 / dev2).
- **Peuvent passer « Identique »** : T-104, T-050, P-08, P-05.
- **Peuvent passer « Identique (réserve UAT) »** : T-006 (certificat, hôte IPv6), T-088 (systemd réel de l'UAT),
  P-04 (AD réel, navigateur).
- **Restent « Vérifié »** : P-14 (R30, décision de MMED).
- **Lignes « Livré »** : T-028 peut passer « Vérifié » (corpus généré ; Q09, QR8 ouverts) ; T-070 reste « Livré »
  (secret NVD, ANO-E0-004) ; T-089 reste « Livré (procédure) » (Q19).
- **À lire sur GitHub** : cause de l'échec du job back-end de la CI à `a7343b8` (O1).

## 12. Tour 5 : vérification courte (03/10/2026)

Exécutée par qa sur `ct/qa-r5`, créée depuis `claude/inspiring-lovelace-10bg1c` (**af8ca2f** : tour 5 intégré —
`c1126de` fusion de ct/dev3-r5 (`cf1261f`, ANO-E6-002), `69cfe41` fusion de ct/dev4-r5 (`620408b`, ANO-E0-004),
`c48dbfb` fusion de ct/dev2-r5 (`c0c51f9`, `fc97877`, CI back), `af8ca2f` suivi de pm). Aucun code applicatif modifié.

Environnement : JAR construit par `mvn -o package` sur `af8ca2f` (avertissement « SBOM INCOMPLET » attendu hors
ligne) ; instance **A** sur `ged_qa` (profil dev, 18084/18094, aucun changeset à appliquer), clamd 1.5.4 réel
(13394), Tesseract réel, annuaire UnboundID embarqué (simulé) ; Node 24.21.0 / npm 11.19.0 de l'équipe pour le
front ; CI GitHub lue par `gh api` et par l'outil MCP GitHub (journal du job back).

### 12.1 Revérification des anomalies « Corrigée »

| Anomalie | Correctif | Recette rejouée | Constat | Verdict |
|---|---|---|---|---|
| ANO-E6-002 (T-115) | cf1261f (dev3) | `qa/r5/ref-longue.sh` (script du tour 4 rejoué tel quel sur l'instance A) ; `qa/r5/ref-limite.sh` (nouveau : bornes 255 / 256 sous plan manuel) ; journal de l'instance ; lecture de `document.reference` en base | **Plan manuel, 300 caractères** : dépôt **202** `A_INDEXER`, `motifIndexation` = « Métadonnées non enregistrées : Le champ « référence composée du document » ne peut pas dépasser 255 caractères (reçu : 300) : raccourcissez les valeurs d'index qui la composent. » — **plus aucun texte SQL** (au tour 4 : requête `update document set …` complète) ; `PUT /indexation/documents/{id}` même valeur → **400 `DONNEE_REFUSEE`**, même message ; 200 caractères → 200, `INDEXE`. **Plan automatique** inchangé : 202 `A_INDEXER`, motif « nom composé du document … (reçu : 300) », PUT → 400 `REQUETE_INVALIDE`, 200 caractères → `INDEXE`. **Bornes** (plan manuel) : 255 caractères → 202 `INDEXE`, `length(reference) = 255` en base ; 256 → 202 `A_INDEXER`, motif métier « (reçu : 256) ». Journal : **0** `SqlExceptionHelper`, **0** « value too long » ; un WARN `DepotService` par dépôt refusé, au motif métier (pile de l'exception métier jointe, aucun SQL) | **Vérifiée (cf1261f)** |
| ANO-E0-004 (T-070) | 620408b (dev4) | `npm audit --omit=dev --audit-level=high` (registre réel) ; `ng version`, `ng build`, `ng test --watch=false` ; `node --test outils/tests/versions-angular.test.mjs` ; CI GitHub (`gh api …/actions/runs?branch=claude/inspiring-lovelace-10bg1c`, jobs, étapes, artefacts) | Audit : **code 0, « found 0 vulnerabilities »** (au tour 4 : code 1, 1 haute + 6 moyennes). `ng version` : CLI, core et router **22.2.1**. `ng build` vert (avertissements de budget connus) ; `ng test` **196/196** (44 fichiers) ; garde de versions 3/3. Audit complet (outillage compris) : 1 haute, 2 moyennes, toutes hors paquet livré (`undici` via `jsdom`, `vitest`) — conforme à `VULNERABILITES-DEPENDANCES.md` §3. CI : exécution **37128015054** (`af8ca2f`), job « Front-end (tests, paquet, SBOM, audit) » **vert**, étape 9 « Audit des dépendances livrées » `success`, étapes d'archivage `success` ; artefacts `frontend-paquet`, `sbom-frontend`, `rapport-npm-audit` présents ; déjà vert sur 37127411511 (`69cfe41`) | **Vérifiée (620408b)** |

### 12.2 CI GitHub sur la tête (`af8ca2f`)

| Exécution | Commit | Back-end | Front-end | Registre et licences | OWASP |
|---|---|---|---|---|---|
| 37128015054 | `af8ca2f` (tête, contient `c0c51f9` et `fc97877`) | **vert** (étape « Compiler, tester, construire le JAR et le SBOM » `success` ; artefacts `backend-jar`, `sbom-backend`, `rapports-tests-backend`) | vert | **vert** | rouge, échec volontaire : annotation « Ni variable NVD_DATAFEED_URL ni secret NVD_API_KEY » ; étape « Contrôler la chaîne d'analyse (miroir synthétique) » `success` |
| 37127411511 | `69cfe41` (avant les correctifs de dev2) | rouge | vert | sauté | rouge (idem) |
| 37124893548 … 37126645378 | `a7343b8` … `4090da3` | rouge | rouge | sauté | rouge |

Le job back est **revenu au vert** à la première exécution qui contient les correctifs de dev2 : aucune anomalie
ouverte. Journal du job (outil MCP `get_job_logs`, fin du journal) : les seules `ERROR` sont celles du journal du
service PostgreSQL provoquées volontairement par les tests (gardes de retour arrière, journal d'audit en ajout seul,
version en lecture seule, contrainte `ck_notification_type`) ; aucune « remaining connection slots are reserved »
dans les 150 dernières lignes lues (pas de lecture intégrale possible). Le décompte des tests de la CI n'est pas lisible ici (journal complet et artefacts servis par un
hôte que le client `gh` du poste refuse).

### 12.3 Suite automatisée

`mvn -B -q -o test` sur `ged_qa_test`, instance A arrêtée, pool Hikari de 3 : **693 tests, 0 échec, 0 erreur, 0 ignoré (116 classes, 3 min 04 s)**. Référence du tour 4 :
687 tests verts ; les nouveaux viennent du tour 5 (`DepotDeuxTempsApiTest.planManuelValeurTropLongue`,
`DepotServiceMotifTest`).
Front : voir 12.1 (`ng build` vert, `ng test` 196/196).

### 12.4 Observations (sans anomalie)

- **O1** — `docs/securite/VULNERABILITES-DEPENDANCES.md` §4 décrit encore le job back « rouge depuis `a7343b8` …
  à confirmer par la prochaine exécution » et le job front « retour au vert à constater » : l'exécution 37128015054
  (`af8ca2f`) les confirme tous deux. À mettre à jour par dev2 (T-070, document).
- **O2** — Une même valeur trop longue est refusée en **400** sous les deux chartes, mais avec deux codes :
  `DONNEE_REFUSEE` (plan manuel, « référence composée ») et `REQUETE_INVALIDE` (plan automatique, « nom composé »).
  Message métier clair dans les deux cas ; à harmoniser éventuellement au contrat (pas d'exigence du V3 en cause).
- **O3** — Depuis `cf1261f`, chaque dépôt dont le temps 2 est refusé pour une raison **métier** écrit au journal un
  WARN avec la pile complète de l'exception (une vingtaine de lignes) : utile pour les pannes techniques, bruyant
  pour un refus de saisie attendu. Sans effet fonctionnel.

### 12.5 Avis sur les lignes du suivi

- **T-115 peut remonter « Identique »** : seule ANO-E6-002 l'avait fait redescendre (vague 11) ; elle est vérifiée,
  et la ligne avait été relue conforme au §12.11 par pm (temps 1 et 2 séparés, issues `INDEXE`, `SANS_PLAN`,
  `A_INDEXER` et reprise, 202, rejeu sans effet) avec des composants réels (clamd, Tesseract, PostgreSQL 16).
- **T-070 peut passer « Vérifié »** (pas au-delà) : ANO-E0-004 est vérifiée ; le contrôle npm tourne réellement à
  chaque construction et bloque à « haute » (prouvé en rouge du 01/10 au 03/10, en vert depuis `69cfe41`), son
  rapport est archivé (`rapport-npm-audit`) ; la chaîne Dependency-Check (seuil CVSS ≥ 7, rapports, suppression
  datée) est éprouvée sur miroir synthétique (vague 9) et rejouée à chaque exécution de la CI (étape verte) ; le
  délai de correction est documenté (`VULNERABILITES-DEPENDANCES.md`). **Partie non exercée** : l'analyse réelle des
  dépendances Java n'a jamais tourné (secret `NVD_API_KEY` absent : MMED / administrateur du dépôt) — elle laisse la
  ligne à « Vérifié » ; « Identique » après une exécution réelle du job OWASP en CI.

### 12.6 Scripts de recette (bloc-notes de qa, `qa/r5/`)

`ref-longue.sh` (tour 4, chemins mis à jour), `ref-limite.sh` (nouveau), `lancer-instance.sh`, `instance.sh`,
`arreter.sh`, `jeton.sh`, `front.sh` (`ng version`, `ng build`, `ng test`, audit complet), `mvn-test.sh` ; sorties
`ref-longue.txt`, `ref-limite.txt`, `npm-audit.txt`, `npm-audit-complet.json`, `ng-build.log`, `ng-test.log`,
`mvn-test.log`.

### 12.7 Fin du tour

Instance A et clamd arrêtés par qa ; aucun processus d'un autre membre touché ; PostgreSQL partagé jamais arrêté.
Données ajoutées à `ged_qa` (espaces, index, plans et types `QA-R5…` / `R5L…`, documents `qa-R5…`) laissées en place,
comme aux vagues précédentes.

**Synthèse** : ANO-E6-002 et ANO-E0-004 **vérifiées** ; aucune anomalie nouvelle ; CI GitHub verte sur `af8ca2f`
pour le back, le front et le registre (OWASP en échec volontaire, secret NVD) ; T-115 → « Identique » ; T-070 →
« Vérifié ».

## 13. Tour 6 : T-025, écart 2 (03/10/2026)

Exécuté par qa sur `ct/qa-r6`, créée depuis `claude/inspiring-lovelace-10bg1c` (**f3ee44d** : fusion de ct/dev1-r6 —
`173593b` changesets `202610061000` / `202610061010` et code, `28e2489` documentation, `736a47e` journal de dev1).
Aucun code applicatif modifié ; script de recette ajouté (§13.4). Le réglage de débit OCR de dev3 (`ct/dev3-r6`)
n'est **pas** fusionné dans la branche d'intégration à `f3ee44d` : non recetté (pm le confiera séparément).

Environnement : base **`ged_qa`** elle-même (base de qa, peuplée : 140 documents, 6 identités, 3 groupes), sauvegardée
par `pg_dump` avant l'essai (bloc-notes de qa, `qa-r6/ged_qa-avant-r6.dump`) ; montée et retours arrière par l'outil de
recette `recette/lib/LiquibaseRecette.java` (liquibase-core et pilote du backend livré, hors ligne) en `ged_owner` ;
JAR construit par `mvn -o package` sur `f3ee44d` (avertissement « SBOM INCOMPLET » attendu hors ligne), profil dev,
API 18084 / management 18094, base en `ged_app`, annuaire UnboundID embarqué (simulé, port 33394) avec
`annuaire-dev.ldif` + `recette/donnees/annuaire-recette.ldif` (compte `qanouveau3`, jamais connecté jusqu'ici).

### 13.1 Données préparées dans l'ancien schéma (`groupe_membre.employe_id`)

Phase `preparer` du script, avant la montée (`ged_qa` au changeset `202610051000-1`, 109 changesets) : fiche employé
« QA Nouveau3 » (sans identité ; son courriel dérivé `qa.nouveau3@marchica.ma` est celui de `qanouveau3` dans
l'annuaire simulé, donc rattachable à la première connexion), fiche « QAT025 Attente » (sans compte d'annuaire) ;
appartenances ajoutées : QA Nouveau3, QAT025 Attente et Omar Tazi (fiche reprise sans identité, compte désactivé)
dans « Lecteurs Comptabilité » (groupe habilité `UTILISATEUR_STANDARD` sur l'espace « Comptabilité »), QAT025 Attente
aussi dans « Administrateurs GED ». **`groupe_membre` avant : 7 lignes, dont 4 de fiches sans identité.**

### 13.2 Résultats

| Id | Contrôle | Résultat | Constat |
|---|---|---|---|
| T025-A1 | (a) montée **réelle** de `ged_qa` | OK | `status` avant : exactement `202610061000-1` et `202610061010-1` en attente ; `update` : 109 → 111 changesets |
| T025-A2 | (a) `groupe_membre` + `groupe_membre_attente` = lignes d'avant | OK | **3 + 4 = 7** ; l'attente contient exactement les 4 appartenances de fiches sans identité (c'est le contrôle après montée de `DEPLOIEMENT.md` §8) |
| T025-A3 | (a) chaque appartenance retrouvée | OK | projection (id, groupe, fiche employé) identique avant / après : identité ↔ fiche par `utilisateur.employe_id`, attente avec le **même identifiant de ligne** ; rien de perdu, déplacé ni dédoublé |
| T025-A4 | (a) schéma après montée | OK | `employe_id` absent de `groupe_membre`, `utilisateur_id` NOT NULL, `uk_groupe_membre_groupe_ged_id_utilisateur_id`, `fk_groupe_membre_utilisateur`, `idx_groupe_membre_utilisateur_id` ; table `groupe_membre_attente` avec `uk_…_groupe_ged_id_employe_id`, `fk_…_groupe_ged`, `fk_…_employe`, `idx_…_employe_id` ; insertion sans `utilisateur_id` refusée (not-null) ; droits de `ged_app` (DML) et `ged_readonly` (SELECT) posés sur la nouvelle table par les privilèges par défaut |
| T025-A5 | (a) aucune autre table touchée | OK | empreinte md5 du contenu des 62 autres tables identique ; seul `version_habilitations` avance (163 → 165 : déclencheur de `groupe_membre`, effet voulu) |
| T025-B1 | (b) retour arrière des deux changesets | OK | `rollbackCount 2` (contract puis expand) : 111 → 109 |
| T025-B2 | (b) contenu après retour arrière | OK | **contenu des 63 tables identique à l'état d'avant la montée** (md5 par table, `groupe_membre` comparé par colonnes nommées) : les 7 appartenances reviennent avec `employe_id` et leur identifiant d'origine ; table d'attente et `utilisateur_id` supprimés |
| T025-B3 | (b) DDL après retour arrière | AVERT | DDL identique **à l'ordre physique des colonnes près** : `employe_id`, recréé par le retour arrière, devient la dernière colonne de `groupe_membre` (avant : `groupe_ged_id, employe_id, id` ; après : `groupe_ged_id, id, employe_id`). Contraintes, index, clés étrangères et noms identiques ; sans effet (aucune requête ne dépend de la position). Observation O1 |
| T025-B4 | (b) remontée : contenu | OK | 109 → 111 ; **contenu des 64 tables identique** à l'état d'après la première montée (membres et attente, mêmes identifiants) |
| T025-B5 | (b) remontée : DDL | OK | `pg_dump -s` normalisé identique à celui d'après la première montée |
| T025-B6 | (b) la comparaison sait échouer | OK | même comparateur, état d'avant contre état d'après montée : 3 lignes d'empreinte différentes (`groupe_membre`, `groupe_membre_attente`) |
| T025-C1 | (c) avant la connexion | OK | fiche « QA Nouveau3 » sans identité ; son appartenance à « Lecteurs Comptabilité » en attente (ligne `01a10259-1a90-…`) ; vue de l'Administrateur (`GET /access-groups/{id}`) : la fiche figure dans `pendingUserIds` |
| T025-C2 | (c) première connexion par l'annuaire simulé | OK | `POST /auth/login` `qanouveau3` → **200** (search-then-bind, profil dev) |
| T025-C3 | (c) conversion | OK | identité créée **rattachée à la fiche préparée** (courriel dérivé) ; **la même ligne** (`01a10259-1a90-…`) est dans `groupe_membre` avec `utilisateur_id` = la nouvelle identité ; plus rien en attente pour cette fiche ; total membres + attente inchangé (7) ; les 3 autres appartenances attendent toujours ; `version_habilitations` 173 → 174 (cache des droits invalidé). Aucune action de l'Administrateur |
| T025-C4 | (c) droit du groupe appliqué : accès réel à un document | OK | `/auth/me` : `roles: ["UTILISATEUR_STANDARD"]` (rôle du groupe) ; `GET /documents/01a0f222-479d-…` (« QAE3…-D1-public », espace « Comptabilité ») → **200** |
| T025-C5 | (c) témoins | OK | même compte, document **PRIVÉ** d'autrui du même espace → 404 (périmètre §12.3 respecté) ; identité sans groupe (`nidrissi`) sur le document public → 404 (l'accès vient bien du groupe) |
| T025-C6 | (c) droits effectifs (P-22) | OK | `GET /admin/droits-effectifs?utilisateurId=…&documentId=…` → 200 : `UTILISATEUR_STANDARD`, `via: GROUPE`, `viaLibelle: "Lecteurs Comptabilité"`, attribution sur « Comptabilité » ; permissions CONSULTER, DEPOSER, DIFFUSER, MODIFIER, VALIDER |
| T025-C7 | (c) vue de l'Administrateur après connexion | OK | la fiche quitte `pendingUserIds` et reste dans `users` (identifiant de fiche inchangé : l'écran Angular, non modifié, renvoie ce qu'il a reçu) |
| T025-D1 | retour arrière **après mise en service** (application arrêtée, une appartenance convertie) | OK | `rollbackCount 2` : les 7 appartenances (1 convertie, 3 réelles d'origine, 3 en attente) reviennent dans `groupe_membre`, projection (id, groupe, fiche) identique ; la convertie désigne la fiche « QA Nouveau3 » |
| T025-D2 | remontée après mise en service | OK | contenu des 64 tables et DDL identiques à l'état d'avant ce retour arrière |

Bilan du script : monter **5/5**, aller-retour **5 OK, 1 AVERT** (B3), connexion **7/7**, retour-apres-service
**2/2**. L'application a démarré sur la base montée sans erreur (validation Hibernate du schéma, aucun changeset en
attente). Le contrôle C6 a été resserré après l'exécution (recherche exacte de `via: GROUPE` et du nom du groupe) et
validé sur la réponse réelle, conservée (`qa-r6/travail/c6.json`).

### 13.3 Suite automatisée

Tests du lot relus et rejoués sur `ged_qa_test` : `SchemaLiquibaseTest` (11, dont `groupeMembreParIdentite`),
`AppartenanceIdentiteApiTest` (2), `RepriseDonneesTest` (1), `AccessGroupApiTest` (7), `NotificationsTest` (11),
`CheminsAccesApiTest` (26) : 58/58. **Suite back complète** (`mvn -o -B test`) : **696 tests, 0 échec, 0 erreur,
0 ignoré** (117 classes, 2 min 54 s ; 693 au tour 5, + 3 tests du lot). Front non touché par le lot (non rejoué).

### 13.4 Script de recette ajouté

`recette/e1/recette-t025-ecart2.sh` (bash + psql + outil Liquibase de la recette ; aucun Python, D5) : phases
`preparer`, `monter`, `aller-retour`, `connexion`, `retour-apres-service` ; garde-fou sur le nom de base (préfixe
`ged_qa`) et refus de la production ; sorties `RESULTAT|…`. Rejouable en UAT sur une copie restaurée de la base
(`T025_BASE`, compte jamais connecté de l'AD de test dans `T025_COMPTE`, fiche correspondante dans `T025_PRENOM` /
`T025_NOM`).

### 13.5 Anomalies nouvelles et observations

**Aucune anomalie nouvelle.**

- **O1** (T025-B3) — Après retour arrière, `groupe_membre.employe_id` revient en dernière position physique. Écart de
  pure présentation du DDL (même schéma, mêmes noms, mêmes contraintes) ; l'aller-retour « update → rollback →
  update » donne un DDL identique (B5). Rien à corriger.
- **O2** — `GET /access-groups/{id}` compte dans `users` et `usersCount` les fiches **en attente** (distinguées par
  `pendingUserIds`) ; l'écran Angular, inchangé, ne les signale pas. Comportement antérieur conservé (avant le lot,
  ces fiches figuraient déjà comme membres sans pouvoir se connecter) et sans effet sur les droits (C5). Une mention
  « en attente de première connexion » à l'écran serait plus claire : à apprécier par qa2 / dev4 (aucune exigence du
  V3 en cause).
- **O3** — La conversion repose sur le rattachement de l'identité à la fiche reprise par courriel dérivé
  (`prénom.nom@domaine`, mécanisme antérieur au lot). Si l'AD de MMED donne une autre adresse, ou en cas
  d'homonymes, une nouvelle fiche est créée et l'appartenance de l'ancienne reste en attente (visible dans
  `pendingUserIds` ; l'Administrateur peut ajouter la personne à la main). À surveiller à la reprise à blanc (Phase 7).
- **O4** — Le retour arrière de `202610061000` réintègre l'attente avec `ON CONFLICT DO NOTHING` : une ligne en
  attente qui doublerait une appartenance réelle de la même fiche dans le même groupe serait écartée sans bruit.
  Situation non atteignable par l'application (une fiche qui a une identité n'est jamais mise en attente :
  traduction par `AccessGroupService` ; conversion dans `ServiceIdentites.creer`, unique chemin de création
  d'identité) et qui ne perdrait aucun droit. Signalée pour mémoire.

### 13.6 Avis sur la ligne du suivi

**T-025 peut passer « Identique ».** Les trois écarts de P2 sont levés : écarts 1 et 3 vérifiés en vague 9 ;
écart 2 levé par la décision du client du 03/10 et le correctif `173593b`, éprouvé ici sur une base peuplée réelle :
montée sans perte (membres + attente = lignes d'avant, identifiants conservés), retour arrière des deux changesets et
remontée sans différence de contenu (y compris après mise en service), conversion automatique à la première
connexion par l'annuaire simulé avec le droit du groupe réellement appliqué (document lu, témoins refusés, origine
« groupe » dans les droits effectifs). Schéma documenté régénéré (`SCHEMA-BASE.md` : `groupe_membre.utilisateur_id`,
`groupe_membre_attente`, contraintes conformes au constat A4). Réserve habituelle, non bloquante : première connexion
avec un compte de l'AD réel de MMED et rattachement par son courriel (O3), en UAT ou à la reprise à blanc.

### 13.7 Fin du tour

Instance qa arrêtée ; aucun processus d'un autre membre touché ; PostgreSQL partagé jamais arrêté. `ged_qa` laissée
**à 111 changesets** (lot appliqué), avec les données de recette (`QA Nouveau3`, `QAT025 Attente`, appartenances,
identité `qanouveau3` désormais consommée) ; sauvegarde d'avant l'essai conservée dans le bloc-notes de qa.

**Synthèse** : T-025 écart 2 vérifié (19 OK, 1 AVERT de présentation) ; suite back 696 tests, 0 échec ; aucune
anomalie nouvelle ; quatre observations ; avis T-025 → « Identique ».
