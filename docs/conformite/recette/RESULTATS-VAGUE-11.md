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

## 14. Tour 7 : P-14 / R30, réglage du débit OCR (03/10/2026)

Exécuté par qa sur `ct/qa-r7`, créée depuis `claude/inspiring-lovelace-10bg1c` (**5ceb08c** : fusion de ct/dev3-r6 —
`ModelesEntiers`, `ged.ocr.modeles=entiers`, `ged.ocr.chaine.dpi=200`, `BancTesseractIT#reglagesDebit`,
`ESSAIS-DE-CHARGE.md` § 2.4). Aucun code applicatif modifié ; scripts de recette adaptés ou ajoutés (§14.5).

Poste : conteneur Linux de l'équipe (Xeon 2,1 GHz, 4 cœurs sans hyperthreading, partagé ; charge de fond 1 à 4 pendant
les mesures, d'autres membres faisant tourner leurs suites), Tesseract **5.3.4** et `combine_tessdata` du paquet Ubuntu
`tesseract-ocr`, OpenJDK 17. Le débit se juge sur le **temps CPU** (coût d'une page pour un cœur), peu sensible à la
charge des autres : le temps écoulé, relevé aussi, l'égale à 0,01-0,02 s près dans toutes les mesures ci-dessous.

### 14.1 Mesure indépendante : banc réduit de qa, avant / après

`recette/e10/banc-ocr-reduit.sh 6 ara+fra <dpi>` avec `GED_BANC_MODELES=precis|entiers` : chaîne réelle de l'application
(`ExtracteurDocumentOcr` + `MoteurTesseract`, `OMP_THREAD_LIMIT=1`, `--oem 1 --psm 3`), un fil, pages l'une après
l'autre ; corpus `CorpusOcr` à vérité connue, **graine de qa (1), différente de celle de dev3** : 6 pages par cellule,
5 cellules, 30 pages par réglage. Copie compactée faite **par le script** (`combine_tessdata -c` sur une copie hors
dépôt), pas par la classe de l'application : empreintes `ara` `e7d6494e2ef2…`, `fra` `bf83833fa957…`, **identiques**
à celles de la copie faite par l'application (§14.3, P14.A.modeles) et par `BancTesseractIT` (conversion
déterministe). Deux passes entrelacées (15:52-16:11) ; les CER sont identiques d'une passe à l'autre (Tesseract est
déterministe), les temps CPU à 2 % près.

| Réglage | CPU Tesseract (s/page/cœur) | CPU Java | **Total (s/page/cœur)** | **p/min/cœur** | CER FR propre | CER FR dégradé | CER AR propre | CER AR dégradé | CER bilingue 1 bit |
|---|---|---|---|---|---|---|---|---|---|
| **Avant** : modèles livrés (`precis`), 300 dpi | 4,90 / 4,94 | 0,27 | **5,17 / 5,22** | **11,6 / 11,5** | 0,83 % | 2,28 % | 2,78 % | 6,94 % | 7,31 % |
| `precis`, 200 dpi | 4,40 / 4,39 | 0,35 | 4,75 / 4,74 | 12,6 / 12,7 | 0,79 % | 2,33 % | 3,26 % | 6,11 % | 7,94 % |
| `entiers`, 300 dpi | 2,89 / 2,96 | 0,27 | 3,16 / 3,23 | 19,0 / 18,6 | 0,83 % | 3,38 % | 3,02 % | 8,38 % | 7,43 % |
| **Après (livré)** : `entiers`, **200 dpi** | **2,51 / 2,53** | 0,34 | **2,85 / 2,87** | **21,0 / 20,9** | **0,74 %** | 2,74 % | **3,02 %** | **8,17 %** | 7,31 % |

Lecture : **5,20 → 2,86 s par page et par cœur (−45 %, ×1,82)** ; les modèles en entiers font l'essentiel (−39 %), le
200 dpi le reste (−10 %, rendu Java un peu plus cher : 0,34 contre 0,27 s). Seuils du §4.3.2 tenus partout (français
propre ≤ 5 %, arabe ≤ 10 % propre et dégradé) ; débit ≥ 6 p/min/cœur tenu (21). **Cible du §4.3.4 « 1 à 3 s par page
et par cœur » atteinte, par sa borne haute** (2,86 s, 5 % de marge) sur un cœur à 2,1 GHz. Coût qualité du compactage :
arabe dégradé **+1,2 point** (6,94 → 8,17 %), arabe propre +0,2 point ; reste 1,8 point de marge sous le seuil de 10 %
sur un corpus dont les CER sont des planchers (polices nettes, pas de tampon ni de manuscrit).

### 14.2 Contre-mesure par l'outil de dev3 (`BancTesseractIT#reglagesDebit`)

`GED_TESSERACT=/usr/bin/tesseract GED_TESSDATA_FAST=/usr/share/tesseract-ocr/5/tessdata GED_BANC_PAGES=8
GED_BANC_REGLAGES=best,best_int,best_int_200,best_200 mvn -o test -Dtest='BancTesseractIT#reglagesDebit'` (corpus et
graine de dev3, 40 pages par réglage ; test vert, 691 s) :

| Réglage | CPU Tesseract | Total (s/page/cœur) | p/min/cœur | CER FR p. / AR p. / AR d. / FR d. / bil. NB | dev3 (§ 2.4) |
|---|---|---|---|---|---|
| `best` (avant) | 4,89 | **5,16** | 11,6 | 0,86 / 2,65 / 7,11 / 2,29 / 7,44 % | 5,35 s |
| `best_int` (300 dpi) | 2,93 | 3,19 | 18,8 | 0,87 / 2,93 / 9,01 / 3,29 / 7,58 % | 3,18 s |
| **`best_int_200` (livré)** | **2,55** | **2,88** | **20,8** | 0,77 / 2,90 / 8,38 / 2,60 / 6,94 % | 2,90 s |
| `best_200` | 4,38 | 4,71 | 12,7 | 0,82 / 3,09 / 6,18 / 2,35 / 7,41 % | — |

CER **identiques au centième** à ceux du § 2.4 (mêmes pages, moteur déterministe) ; temps à 1 % (après) et 4 % (avant)
de ceux de dev3. Le tableau du § 2.4 est confirmé. À noter : sur le corpus de dev3, `entiers` à 300 dpi porte l'arabe
dégradé à 9,01 % (1 point de marge) ; le 200 dpi le ramène à 8,38 %. Le réglage livré est donc le couple, pas les
modèles seuls : revenir à 300 dpi en gardant `entiers` réduirait la marge en arabe dégradé.

Projection (calcul, comme au § 2.4) : 20 000 pages × 2,87 s / 4 cœurs ≈ **4 h** sur 4 cœurs de ce poste (borne haute
du §6.6 « 2 à 4 h »), contre ≈ 7 h 15 avant ; à transposer au serveur de MMED en proportion de la fréquence soutenue.

### 14.3 Application démarrée : conversion, repli, retour arrière, dépôt réel

`recette/e10/recette-p14-ocr.sh` sur le JAR construit à `5ceb08c` (`mvn -o package`), profil dev, base `ged_qa`,
API 18084 / management 18094, annuaire simulé (33394), antivirus désactivé (profil dev). Une instance par phase.
Tesseract est remplacé par un **espion** (script) qui note, à chaque appel de l'application, `--tessdata-dir`,
`OMP_THREAD_LIMIT` et les dimensions de l'image reçue sur l'entrée standard, puis appelle `/usr/bin/tesseract` ; un lien
`combine_tessdata` est posé à côté de l'espion (recherche par défaut « à côté du binaire »). Dépôts : PDF d'une page
**sans couche texte** (image JPEG en niveaux de gris à 300 dpi, `ScanTemoin.java`) portant un mot témoin français
inédit par phase et un témoin arabe.

| Id | Contrôle | Résultat | Constat |
|---|---|---|---|
| P14.A.journal | démarrage par défaut (aucune variable `GED_OCR_*`) : conversion au journal | OK | « Modèles OCR compactés en entiers dans `<java.io.tmpdir>/ged-tessdata-entiers` : [ara, eng, fra] » (`osd`, sans réseau LSTM, copié tel quel) ; instance prête en **15 s, comme sans conversion** (14 s) |
| P14.A.modeles | copie compactée = conversion indépendante ; empreinte de la source notée | OK | `ara` 12 603 724 → 2 495 395 o, `fra` 3 972 885 → 1 248 107 o ; empreintes égales à celles du script ; marque `<sha256 du modèle livré> entiers` |
| P14.A.etat | `/api/v1/ocr/etat` | OK | moteur disponible, `[ara, eng, fra, osd]`, défaut `ara+fra` |
| P14.A.depot / ocr / recherche | dépôt réel d'un PDF scanné → OCR → recherche | OK | 202 `EN_ATTENTE_OCR` ; texte extrait en 6 s (`OCR_TERMINE`, provenance OCR, 1 page, `ara+fra`, 664 caractères), témoin lu ; document trouvé par `/recherche/plein-texte` sur le témoin français **et** sur le témoin arabe |
| P14.A.espion | modèles et résolution réellement employés | OK | `--tessdata-dir` = copie compactée, `OMP_THREAD_LIMIT=1`, image **1653 × 2338** (A4 à 200 dpi) |
| P14.A2.reutilisation | redémarrage | OK | copie réutilisée, non refaite (dates de modification inchangées) |
| P14.B.journal | `GED_OCR_COMBINE_TESSDATA=/inexistant/combine_tessdata` | OK | WARN « Aucun modèle OCR compacté en entiers (/inexistant/combine_tessdata indisponible ?) : modèles précis employés, débit réduit » ; démarrage poursuivi |
| P14.B.etat / depot / ocr / recherche | OCR fonctionnel en repli | OK | 202, texte extrait en 6 s, témoins français et arabe trouvés |
| P14.B.espion | repli effectif | OK | `--tessdata-dir` = modèles livrés (`backend/tessdata`), 200 dpi conservé |
| **P14.B3.reprise** | outil rétabli (même répertoire de travail que B, outil par défaut présent) : conversion au redémarrage | **ECHEC** | toujours « Aucun modèle OCR compacté en entiers (`…/bin/combine_tessdata` indisponible ? » alors que l'outil est là ; marques `ara`, `fra` restées à `copie` : **ANO-E6-003** |
| P14.B2.journal | répertoire de la copie impossible à créer (`/proc/…`) | OK | WARN « Répertoire des modèles compactés … inutilisable … : modèles précis employés » ; moteur disponible |
| P14.C.journal | `GED_OCR_DPI=300` et `GED_OCR_MODELES=precis` : aucune conversion | OK | ni ligne au journal, ni répertoire de travail créé |
| P14.C.etat / depot / ocr / recherche | OCR au réglage d'avant | OK | 202, texte extrait en 7 s, témoins trouvés |
| P14.C.espion | retour arrière effectif | OK | modèles livrés, image **2480 × 3507** (300 dpi) |
| P14.D.refus | `GED_OCR_MODELES=rapide` | OK | démarrage refusé en 12 s : « ged.ocr.modeles : « entiers » ou « precis » attendu, reçu « rapide » » |
| P14.livres | modèles livrés intacts après toutes les phases | OK | 4 empreintes inchangées (le registre et le SBOM restent justes) |

Bilan : **27 OK, 1 ÉCHEC** (B3, ANO-E6-003). Premier essai (conservé : `qa-r7/p14-app-essai1.txt`, sans la phase
B3) : mêmes constats sur la configuration, plus deux faux échecs de l'outil de recette, corrigés avant le second
essai — dimensions attendues arrondies au pixel supérieur (PDFBox tronque : 1653 et non 1654) et témoin en lettres
arbitraires (« septokarinabgcdcg » lu « septokarinabgcedcg » par les modèles en entiers : une erreur d'un caractère,
dans l'ordre du CER mesuré ; le témoin est désormais fait de syllabes). Rien à reprocher à l'application sur ces deux
points.

### 14.4 Suite automatisée

**Suite back complète** à `5ceb08c` (`mvn -o -B test` sur `ged_qa_test`, sans `GED_MANAGEMENT_PORT` ni `SERVER_PORT`,
sans `GED_TESSERACT`) : **705 tests, 0 échec, 0 erreur, 3 ignorés** (119 classes, 2 min 43 s ; 696 au tour 6). Les
3 ignorés sont les cas à Tesseract réel de `ReglageDebitOcrTest` et `ModelesEntiersTest`, rejoués à part avec
`GED_TESSERACT=/usr/bin/tesseract` : `ReglageDebitOcrTest` 5/5, `ModelesEntiersTest` 4/4, `MoteurTesseractTest` 8/8,
`ExtracteurDocumentOcrTest` 10/10 (**27/27**, comme annoncé par dev3). Aucun test ne couvre le cas d'ANO-E6-003
(outil rétabli après un premier démarrage sans lui). Front non touché par le lot (non rejoué).

### 14.5 Scripts de recette

- `recette/e10/banc-ocr-reduit.sh` + `BancOcrReduit.java` (adaptés) : 3e argument **DPI** (300 par défaut, valeur des
  vagues 8 à 11 ; 200 = réglage livré), `GED_BANC_MODELES=entiers` (compactage hors dépôt, indépendant de
  l'application, empreintes affichées), **temps CPU exact** de Tesseract (`cutime + cstime` de `/proc/self/stat`,
  relevé toutes les 50 ms en secours hors Linux) et temps CPU Java (`ThreadMXBean`), cellule « français dégradé »,
  ligne `P-14.debit` (cible du §4.3.4, information). Les valeurs des vagues précédentes restent comparables : à 300 dpi
  et 6 pages par cellule, 4,9 s de CPU Tesseract (5,1 s en vague 11 à 2 pages par cellule).
- `recette/e10/recette-p14-ocr.sh` + `ScanTemoin.java` (nouveaux) : §14.3 ; rejouable en UAT sur le serveur de MMED
  (variables `GED_JAR`, `GED_TESSERACT_REEL`, `GED_TESSDATA`, compte de dépôt, `GED_P14_ARGS`).

### 14.6 Anomalie nouvelle et observations

**ANO-E6-003** (mineure, dev3) — repli sans `combine_tessdata` définitif : l'outil rétabli, l'application reste sur
les modèles précis (débit d'avant R30) et répète un avertissement qui accuse l'outil présent. Détail et reproduction
dans `ANOMALIES.md`. Contournement d'après le code : vider le répertoire de travail et redémarrer.

- **O1** — Avec `precis`, rien au journal ne dit quels modèles sont employés (le choix `entiers` est, lui, journalisé).
  Une ligne « modèles OCR : livrés (precis) » au démarrage faciliterait le contrôle du retour arrière en exploitation ;
  aucune exigence en cause.
- **O2** — La résolution effective (`ged.ocr.chaine.dpi`) n'apparaît ni au journal ni dans `/api/v1/ocr/etat` : seul un
  espion comme celui du §14.3 la montre. Même remarque.
- **O3** — Le coût qualité du compactage porte sur l'arabe dégradé (+1,2 point ici, 9,01 % à 300 dpi sur le corpus de
  dev3) : c'est la cellule à surveiller sur l'échantillon de la Phase 7 (Q09), avec les corps de 8 pt à 200 dpi
  (réserve (1) du § 2.4).

### 14.7 Avis sur les lignes du suivi

- **P-14** — L'écart **R30 est levé sur ce poste et ce corpus** : mesure indépendante 5,20 → **2,86 s par page et par
  cœur** (dev3 : 5,35 → 2,90), cible du §4.3.4 atteinte par sa borne haute, §6.6 tenu par sa borne haute en projection
  (≈ 4 h pour 20 000 pages sur 4 cœurs), seuils du §4.3.2 tenus ; réglage effectif dans l'application démarrée
  (modèles compactés et 200 dpi constatés par l'espion), retour arrière par configuration éprouvé, OCR et recherche de
  bout en bout sur un dépôt réel. Les autres volets de P-14 (recherche R32, ANO-E6-001) sont vérifiés depuis la
  vague 10. **Mais ANO-E6-003, ouverte, porte sur ce réglage** : par la règle de passage, P-14 **reste « Vérifié »**.
  Dès ANO-E6-003 corrigée et vérifiée (rejeu de `recette-p14-ocr.sh`, phases B puis B3), **P-14 peut passer
  « Identique (réserve UAT) »**, réserve : échantillon de la Phase 7 (Q09 : CER réel, tampons, manuscrit, corps de 8 pt
  à 200 dpi) et débit remesuré sur le serveur de MMED (fréquence soutenue). La demande de correction des §4.3.4 / §6.6
  à MMED n'a plus d'objet sur ces mesures ; RISQUES.md (R30 : « worker à 16 vCPU », « écart de 3 à 6 fois ») est à
  mettre à jour par pm.
- **T-028** — **Reste « Vérifié », bloquée par Q09 et QR8** : le réglage ne change ni l'absence de l'échantillon de
  300 pages et de sa vérité terrain (Q09), ni la question de la comparaison avec PaddleOCR / EasyOCR sans Python (QR8).
  Sur le corpus généré, avec le réglage livré : CER français 0,74 %, arabe 3,02 %, arabe dégradé 8,17 %, débit
  20,9 p/min/cœur (seuil ≥ 6).

### 14.8 Fin du tour

Instances qa arrêtées (une par phase, chacune arrêtée en fin de phase) ; aucun processus d'un autre membre touché ;
PostgreSQL partagé jamais arrêté. `ged_qa` reçoit 6 documents de recette (`qa-p14-*`, dont 3 du premier essai).
Mesures brutes dans le bloc-notes de qa (`qa-r7/banc-reduit.txt`, `banc-it.log`, `p14-app.txt`, `p14/`).

**Synthèse** : réglage de débit OCR **vérifié** (indépendamment : 2,86 s par page et par cœur, −45 %, seuils de
qualité tenus ; dans l'application : conversion, repli, retour arrière, dépôt réel recherchable) ; **ANO-E6-003**
ouverte (mineure, dev3) ; P-14 reste « Vérifié » jusqu'à sa vérification, puis « Identique (réserve UAT) » ; T-028
reste « Vérifié » (Q09, QR8).

## 15. Tour 8 : vérification d'ANO-E6-003 et du réglage OCR exposé (03/10/2026)

Exécuté par qa sur `ct/qa-r8`, créée depuis `claude/inspiring-lovelace-10bg1c` (**18d2034** : fusion de ct/dev3-r7 —
`4fc5b95` conversion refaite après repli, `46171b8` réglage OCR journalisé et rendu par `/api/v1/ocr/etat`). Aucun code
applicatif modifié ; JAR reconstruit à `18d2034` (`mvn -o package`) ; même poste, même instance qa qu'au §14.3 (profil
dev, base `ged_qa`, API 18084 / management 18094, annuaire simulé 33394, antivirus désactivé), une instance par phase.

### 15.1 Script de recette étendu

`recette/e10/recette-p14-ocr.sh` (cf. §15.5) contrôle désormais, à chaque phase démarrée, la ligne de journal
« Réglage OCR : modèles … (…), pages PDF rendues à … dpi » (comparée **à l'identique**) et les champs `modeles` et `dpi`
de `GET /api/v1/ocr/etat`. Phases ajoutées ou complétées :

- **B** : avertissement « (… indisponible) » attendu, marques des copies `repli` ;
- **B3** (outil rétabli, **même** répertoire de travail que B) : conversion au démarrage sans avertissement, marques
  `entiers`, copies identiques octet pour octet à une conversion indépendante (`combine_tessdata -c`), dépôt réel → OCR →
  recherche, espion sur la copie refaite, **débit retrouvé** (CPU de Tesseract sur la page témoin, B / B3 ≥ 1,3) ;
- **B4** (troisième démarrage, même répertoire) : copies et marques aux dates inchangées, rien de refait ;
- **B5** : répertoire préparé avec les marques `copie` des versions précédentes (cas d'un répertoire persistant déjà
  touché par l'ancien JAR) : conversion refaite.

L'espion note le temps CPU exact de chaque appel (`times` du shell : utilisateur + système de Tesseract) et garde l'image
et les arguments reçus de l'application ; le contrôle de débit prend le **minimum de 5 rejeux** de ces appels (même
image, mêmes arguments, donc mêmes modèles) pour B, B3 et A, mesurés côte à côte en fin de phase B3.

### 15.2 Résultats

Trois essais complets (16:51-17:01, charge de fond ≈ 2) :

| Essai | Bilan | Constat |
|---|---|---|
| 1 | **48 OK, 0 ÉCHEC** | débit mesuré sur l'appel de l'application : B 0,91 s → B3 0,63 s (×1,44) ; A 0,62 s |
| 2 | 47 OK, **1 ÉCHEC** (outil de recette) | `P14.B3.debit` : B 0,91 s → B3 **0,71 s** (×1,28, seuil 1,3) sur un **appel unique** : la charge d'un autre membre suffit à décaler une mesure de 0,6 s ; toutes les autres lignes OK. Pas un défaut de l'application : `--tessdata-dir` était bien la copie refaite (espion OK), marques `entiers`. Contrôle durci (minimum de 5 rejeux) avant l'essai 3 |
| 3 | **48 OK, 0 ÉCHEC** | minimum de 5 rejeux : **B 0,89 s → B3 0,61 s (×1,46), A 0,60 s** ; appels de l'application : B 0,90, B3 0,64, A 0,63 s |

Détail de l'essai 3 (les essais 1 et 2 donnent les mêmes constats, hors la ligne de débit de l'essai 2) :

| Id | Contrôle | Résultat | Constat |
|---|---|---|---|
| P14.A.journal / reglage / modeles / etat | défaut | OK | « Modèles OCR compactés en entiers dans `<java.io.tmpdir>/ged-tessdata-entiers` : [ara, eng, fra] » puis « Réglage OCR : modèles entiers (`…/ged-tessdata-entiers`), pages PDF rendues à 200 dpi » ; copies identiques à la référence (`ara` `e7d6494e2ef2…`, `fra` `bf83833fa957…`) ; marques `ara`, `eng`, `fra` = `entiers`, `osd` = `non-convertible` ; état `{"modeles":"entiers","dpi":200}` |
| P14.A.depot / ocr / recherche / espion | dépôt réel | OK | 202 `EN_ATTENTE_OCR`, texte en 5 s, témoins français et arabe trouvés ; copie compactée, `OMP_THREAD_LIMIT=1`, 1653 × 2338 (200 dpi) |
| P14.A2.reutilisation / reglage | redémarrage | OK | copie non refaite ; réglage `entiers` |
| P14.B.journal | outil absent | OK | WARN « Aucun modèle OCR compacté en entiers (/inexistant/combine_tessdata indisponible) : modèles précis employés, débit réduit (…) ; conversion refaite au premier démarrage où l'outil sera présent » (le « ? » du tour 7 a disparu) |
| P14.B.reglage / marques / etat | repli | OK | « Réglage OCR : modèles repli (`…/backend/tessdata`), pages PDF rendues à 200 dpi » ; marques `ara`, `eng`, `fra`, `osd` = `repli` ; état `repli` / 200 |
| P14.B.depot / ocr / recherche / espion | OCR en repli | OK | texte extrait, témoins trouvés ; modèles livrés, 200 dpi |
| **P14.B3.reprise** | outil rétabli, même répertoire | **OK** | « Modèles OCR compactés en entiers dans `…/entiers-B` : [ara, eng, fra] », **aucun** avertissement (ÉCHEC au tour 7 : ANO-E6-003) |
| **P14.B3.modeles** | copies refaites | **OK** | marques `entiers` (`osd` : `non-convertible`), `ara` et `fra` identiques à la conversion indépendante |
| P14.B3.reglage / etat | réglage exposé | OK | « Réglage OCR : modèles entiers (`…/entiers-B`), pages PDF rendues à 200 dpi » ; état `entiers` / 200 |
| P14.B3.depot / ocr / recherche / espion | dépôt réel après reprise | OK | texte en 6 s, témoins français et arabe trouvés ; `--tessdata-dir` = `…/entiers-B` (copie refaite), 200 dpi |
| **P14.B3.debit** | débit retrouvé | **OK** | 0,89 → 0,61 s de CPU Tesseract pour la page (×1,46), égal au réglage par défaut (0,60 s). Rapport plus faible que les ×1,82 du banc (§14.1) : la page témoin est courte (664 caractères), le chargement des modèles y pèse davantage ; la mesure compare des réglages, ce n'est pas un débit par page |
| **P14.B4.reutilisation** | 3e démarrage, même répertoire | **OK** | 8 fichiers (copies et marques) aux dates inchangées, `osd` non réessayé ; réglage `entiers`, état `entiers` / 200 |
| **P14.B5.ancienne-marque** | marques `copie` d'avant le correctif | **OK** | conversion refaite (`ara`, `eng`, `fra` = `entiers`, `osd` = `non-convertible`), copies identiques à la référence ; réglage `entiers` |
| P14.B2.journal / reglage / etat | répertoire inutilisable (`/proc/…`) | OK | WARN « Répertoire des modèles compactés … inutilisable … : modèles précis employés » ; réglage `repli` (modèles livrés), état `repli` / 200 |
| P14.C.journal / reglage / etat | `precis`, 300 dpi | OK | aucune conversion ; « Réglage OCR : modèles precis (`…/backend/tessdata`), pages PDF rendues à 300 dpi » ; état `precis` / 300 |
| P14.C.depot / ocr / recherche / espion | retour arrière | OK | texte en 7 s, témoins trouvés ; modèles livrés, 2480 × 3507 (300 dpi) |
| P14.D.refus | `GED_OCR_MODELES=rapide` | OK | démarrage refusé en 12 s, motif explicite |
| P14.livres | modèles livrés | OK | 4 empreintes inchangées |

Démarrage : 14 à 15 s par phase, conversion comprise (B3, B5), comme sans conversion. `ged_qa` reçoit 12 documents de
recette (`qa-p14-*`, 4 par essai).

### 15.3 Suite automatisée

**Suite back complète** à `18d2034` (`mvn -o -B test` sur `ged_qa_test`, sans `GED_MANAGEMENT_PORT` ni `SERVER_PORT`,
sans `GED_TESSERACT`, après l'arrêt des instances de recette) : **711 tests, 0 échec, 0 erreur, 3 ignorés** (2 min 58 s ;
705 au tour 7, comme annoncé par dev3). Les 3 ignorés sont les cas à Tesseract réel de `ReglageDebitOcrTest` (1) et
`ModelesEntiersTest` (2), rejoués à part avec `GED_TESSERACT=/usr/bin/tesseract` : `ReglageDebitOcrTest` 7/7,
`ModelesEntiersTest` 7/7 (dont `outilRetabli`, `ancienneMarqueCopie`, `disponibilite`), `MoteurTesseractTest` 8/8,
`ExtracteurDocumentOcrTest` 10/10, `OcrApiTest` 8/8 (champs `modeles` et `dpi`) : **40/40**. Front non touché par le
lot (non rejoué).

### 15.4 Cas résiduel exercé à part (observation)

dev3 signale (suivi, point 2) qu'un `combine_tessdata` présent mais **défectueux** (il démarre, puis refuse tout) marque
les modèles `non-convertible`, sans nouvel essai. Exercé directement sur `ModelesEntiers.preparer` (classes de
`18d2034`, `qa-r8/OutilDefectueux.java`) : `/bin/false` → « Aucun modèle OCR convertible en entiers par /bin/false »,
marques `non-convertible` ; puis, même répertoire, le vrai `/usr/bin/combine_tessdata` (deux fois) → toujours `repli`,
« Aucun modèle OCR convertible en entiers par /usr/bin/combine_tessdata » alors que cet outil convertit ces modèles.
Une saturation du disque, autre cause plausible, ne mène **pas** à ce cas (essai sur un tmpfs de 14 Mo,
`qa-r8/disque-plein.sh`) : la copie d'un modèle échoue avant l'outil, l'application passe par « Répertoire des modèles
compactés … inutilisable » et emploie les modèles livrés ; aucune marque n'est écrite pour le modèle en cause.

- **O1** — Le cas « outil défectueux puis réparé » reste figé (contournement : vider le répertoire de travail), alors que
  `DEPLOIEMENT.md` § 3.2 dit « inutile de vider le répertoire de travail » (vrai pour l'outil absent, le seul cas
  courant). Une phrase dans `EXPLOITATION.md` § 2 (« si l'avertissement dit "Aucun modèle OCR convertible" après
  réparation de l'outil : vider le répertoire de travail et redémarrer ») suffirait. Pas d'anomalie : cas improbable
  (binaire du paquet `tesseract-ocr`), message qui nomme l'outil, OCR assuré au débit d'avant R30 ; déjà déclaré par
  dev3.
- **O2** — Un contrôle de débit sur une page est sensible à la charge du poste s'il porte sur un appel unique
  (essai 2) : en UAT, s'en tenir au minimum des rejeux, ou au banc réduit pour un débit par page.

### 15.5 Script de recette

`recette/e10/recette-p14-ocr.sh` (étendu) : contrôles « Réglage OCR » et `modeles` / `dpi` à chaque phase, marques des
copies, phases B4 et B5, phase B3 complétée (copies, dépôt, espion, débit), temps CPU et rejeux de l'espion. Toujours
rejouable en UAT (mêmes variables). Bilan attendu : 48 lignes OK.

### 15.6 Avis sur les lignes du suivi

- **ANO-E6-003** — **Vérifiée** (`4fc5b95`, `46171b8`) : l'outil rétabli, la conversion est refaite au redémarrage
  suivant dans le même répertoire, sans avertissement, et le débit des modèles compactés est retrouvé ; troisième
  démarrage sans reconversion ; anciennes marques `copie` reprises ; réglage employé lisible au journal et par l'API.
- **P-14** — Plus aucune anomalie ouverte sur ce réglage ; les mesures du §14 (2,86 s par page et par cœur, seuils du
  §4.3.2 tenus) et le bout en bout sur l'application démarrée restent acquis à `18d2034` (aucune régression sur trois
  essais). **P-14 peut passer « Identique (réserve UAT) »**, réserve : échantillon de la Phase 7 (Q09 : CER réel, arabe
  dégradé, tampons, manuscrit, corps de 8 pt à 200 dpi) et débit remesuré sur le serveur de MMED
  (`banc-ocr-reduit.sh 6 ara+fra 200` avec `GED_BANC_MODELES=entiers`, puis `recette-p14-ocr.sh`). O1 ne s'y oppose pas
  (observation de documentation).
- **T-028** — inchangée : « Vérifié », bloquée par Q09 et QR8.

### 15.7 Fin du tour

Instances qa arrêtées (une par phase, chacune arrêtée en fin de phase) ; aucune instance d'un autre membre touchée
(l'instance de démonstration, 18085 / 4385, n'a pas été approchée) ; PostgreSQL partagé jamais arrêté ; tmpfs d'essai
démonté. Mesures brutes dans le bloc-notes de qa (`qa-r8/p14-app-1.txt` à `-3.txt`, `p14-1/` à `p14-3/`,
`defectueux.txt`, `suite.log`, `cibles.log`).

**Synthèse** : ANO-E6-003 **vérifiée** ; réglage OCR exposé (journal, `/api/v1/ocr/etat`) conforme à l'annonce de dev3
dans toutes les phases ; aucune anomalie nouvelle, deux observations ; P-14 → « Identique (réserve UAT) » proposé.
