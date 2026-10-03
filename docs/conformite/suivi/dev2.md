# Suivi — dev2 (qualité, exploitation, traçabilité, API)

Branche `ct/dev2`. Mise à jour : 03/10/2026.

## Tour 5 de mise en conformité (03/10/2026) — branche `ct/dev2-r5`

Base : `claude/inspiring-lovelace-10bg1c` (`4090da3`). Référence : 687 tests back, 0 échec (vague 11).

| Tâche / constat | Commit | Cause | Correctif | Preuve (échoue sans le correctif) |
|---|---|---|---|---|
| CI back rouge (T-087), 1/2 : premier test en échec | `c0c51f9` | Journaux des exécutions 37124893548 (`a7343b8`), 37126190497 (`65b1002`) et 37126645378 (`4090da3`) : **687 tests, 1 échec, toujours le même**, `MetadonneesPlanParalleleTest.indexDeDeploiementSeCree:154` (« Gather » absent de l'`EXPLAIN`), test ajouté par `e16eb01` (ANO-E7-007, tour 4) ; le job était vert jusqu'à `66af618`. Les coûts de parallélisme à zéro ne suffisent pas : dès quelques pages dans `document`, l'accès par l'index d'expression ne coûte presque que des lectures de pages (non divisées par le parallélisme) ; à coûts voisins (< 1 %), le planificateur garde le plan série `Index Scan`, sans `Gather`. La taille de `document` au moment du test dépend de l'ordre des classes (chaque nouveau contexte vide le schéma) : vert sur nos postes, rouge en CI. Mesuré sur PostgreSQL 16 jetable : 44 documents (2 pages) → `Gather` ; 144 documents (4 pages) et au-delà → `Index Scan` seul | Le test ajoute un fonds de 1 000 documents sans métadonnée (annulé avec sa transaction) pour être toujours dans le cas de la CI, puis `SET LOCAL debug_parallel_query = on` (plan exécuté par un worker parallèle sous un `Gather`) et `enable_seqscan = off` (lecture par les index d'expression) ; il vérifie `Gather` **et** l'index dans les plans de date et de nombre, puis les trois comptes (5, 1, 3). Ce qu'il prouve est inchangé : index §8 construits en parallèle, `meta_date`/`meta_nombre` évaluées en mode parallèle (l'ancien `meta_date` à bloc `EXCEPTION` y échoue). Aucun test désactivé ni sauté | Avec le fonds et sans les deux réglages (état d'avant) : `indexDeDeploiementSeCree` **en échec localement comme en CI** (« plan parallèle attendu … Index Scan … ») ; avec : vert. Plans relevés à la main sur 44, 144, 1 044, 5 044 documents : `Gather` présent dans tous les cas avec les réglages |
| CI back rouge (T-087), 2/2 : connexions épuisées (latent) | `fc97877` | « remaining connection slots are reserved for roles with the SUPERUSER attribute » : **déjà présent dans les exécutions vertes** (`66af618`, 01/10), ce n'est pas ce qui fait échouer le job. Chaque contexte de test en cache garde son pool Hikari de 10 connexions (minimum-idle = 10 par défaut) ; la suite crée 10 contextes (`HikariPool-1` à `-10`) ; le service `postgres:16` accorde 97 places à `ged_app` (100 − 3 réservées) : le 10ᵉ pool ne se remplit pas (FATAL avec le recul exponentiel de Hikari), un 11ᵉ contexte n'aurait plus eu de connexion. Invisible sur nos postes : `equipe-env.sh` impose `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3` | `application-test.yml` : `maximum-pool-size: 5`, `minimum-idle: 1`, `idle-timeout: 10000` ; `src/test/resources/spring.properties` : `spring.test.context.cache.maxSize=12` (au-delà, le contexte le moins récent est fermé avec son pool). `ci.yml` inchangé : `max_connections` reste à la valeur par défaut de l'image, la suite tient dedans avec de la marge | `BudgetConnexionsSuiteTest` (2) : contextes en cache × taille de pool + 15 ≤ connexions accordées par le serveur (lues par `SHOW`), minimum-idle ≤ 1 et idle-timeout ≤ 60 s — **les deux en échec sans le correctif** (« 32 contextes en cache × 10 connexions par pool + 15 = 335 connexions possibles, le serveur n'en accorde que 97 », « minimum-idle = 10 »). Reproduction de la contrainte de la CI : instance PostgreSQL 16 **jetable** (`initdb`, port 55492, `max_connections` 100, 3 réservées, trust, préparée par `creer-roles.sql` et `preparer-base.sql -v tests=oui` comme `ci.yml`), suite sans variable de pool : **avant** pic de 97 connexions `ged_app`, 10 FATAL identiques à la CI ; **après** pic de 26, aucun FATAL, 689 tests verts (2 min 35 au lieu de 4 min 27) |
| Job « OWASP Dependency-Check (CVSS >= 7) » rouge (T-070) | — | Étape « Contrôler la chaîne d'analyse (miroir synthétique) » verte ; étape « Analyser les dépendances » : « Ni variable NVD_DATAFEED_URL ni secret NVD_API_KEY » (`NVD_API_KEY:` vide dans le journal). Échec volontaire, aucune vulnérabilité en cause, aucune analyse faite | Rien à corriger dans le dépôt, rien d'affaibli : **bloqué**, secret `NVD_API_KEY` (ou variable `NVD_DATAFEED_URL`) attendu de l'administrateur du dépôt / MMED. Vérification de remplacement impossible sur ce poste : NVD et `api.osv.dev` refusés par le mandataire (03/10) | — |
| ANO-E0-004 (volet document, T-070) | `b52ec13` | `VULNERABILITES-DEPENDANCES.md` laissait lire « 0 haute » comme un état général et décrivait la CI du 30/09 | Côté back seulement : état des dépendances Java déclaré **inconnu** (aucune analyse réelle à ce jour) ; §4 : état des trois jobs au 03/10 et leurs causes. Le §3 front et la montée d'Angular restent à dev4 (non touchés) | Relecture contre les journaux de la CI (`gh api`, `get_job_logs`) |

**Tests** : suite back complète sur `ged_dev2_test` (serveur partagé, environnement de l'équipe) : **689 tests, 0 échec** (687 + 2 `BudgetConnexionsSuiteTest`) ; même suite sur l'instance jetable aux réglages de la CI, sans variable de pool : **689, 0 échec**. Front non touché.

**Reste** : confirmer le job back vert sur GitHub à la prochaine exécution après intégration (je ne pousse pas). Job OWASP : bloqué tant que le secret manque. ANO-E0-004 : laissée « Ouverte » (correction du front par dev4).

**Points pour pm** :
- `SUIVI.md` (en-tête du tour 4) attribue le rouge du job back aux connexions épuisées : la cause du rouge est le test `indexDeDeploiementSeCree` ; les connexions épuisées sont un défaut latent, corrigé aussi.
- Spring garde au plus 12 contextes de test : un nouveau `@SpringBootTest(properties = …)` ou `@MockitoBean` crée un contexte de plus ; au-delà de 12, des fermetures et redémarrages (schéma recréé par Liquibase) ralentiraient la suite. `BudgetConnexionsSuiteTest` signale tout relèvement qui ne tiendrait plus dans les 97 connexions.
- Instance PostgreSQL jetable de dev2 (`/tmp/ged-dev2-pg-r5`, port 55492) : arrêtée et supprimée en fin de tour.

**Note de coordination pour dev4 (ANO-E0-004)** : je n'ai pas touché au front ni au §3 de `docs/securite/VULNERABILITES-DEPENDANCES.md`. Après la montée d'Angular : remplacer au §3 « 0 critique, 0 haute » et « le job front est vert sur GitHub » par le résultat du nouvel `npm audit` (rapport daté dans `docs/securite/rapports/`), et la ligne « Front-end » du tableau du §4.

## Tour 4 de mise en conformité (01/10/2026) — branche `ct/dev2-r4`

Base : `ct/qa-r3` (`8e38249`, part de `ff20f21`). Référence : 661 tests back, 0 échec.

| Anomalie / constat | Commit | Cause | Correctif | Preuve (échoue sans le correctif) |
|---|---|---|---|---|
| ANO-E10-009 (T-006) | `1068003` | sans IPv6, le `nginx -t` de V2 laisse un `nginx.pid` vide ; le piège `EXIT` faisait `kill ''` sous `set -e` → code 1 après « RÉUSSI », `rm -rf` jamais atteint (répertoire `ged-nginx.*` laissé) | `arreter` ignore un PID vide ou périmé et attend la fin du processus ; piège `nettoyer` : `set +e`, arrêt, suppression du répertoire, code du script conservé ; `rm -f nginx.pid` après le `nginx -t` de V2 ; sans IPv6, démarrage en `[::1]` signalé `[N/A]` et « RÉSULTAT : RÉUSSI — NON APPLICABLE sur cet hôte : … », code 0 ; codes documentés (0/1/2) dans l'en-tête et `EXPLOITATION.md` §3 | `ScriptsExploitationTest.testNginxIpv6CodeDeSortieEtNettoyage` lance le vrai script (NGINX 1.24, ports libres, `TMPDIR` dédié) : code 0, aucun `[ÉCHEC]`, « NON APPLICABLE » sans IPv6, `TMPDIR` vide — **en échec sur l'ancien script** (code 1) ; ignoré si NGINX/openssl/curl absents. Chemin d'échec vérifié à la main (port occupé → code 1, répertoire supprimé) |
| O1 recette vague 10 (T-085) | `86a1bb2` | hors ligne, `cyclonedx-maven-plugin` « requires online mode » et ne produit rien ; `completer-sbom.mjs` sortait en 2 → `mvn -o package` en échec depuis `f2fba2f` | le pom passe `--maven-hors-ligne=${settings.offline}` ; SBOM absent + hors ligne + hors CI : « [AVERTISSEMENT] SBOM INCOMPLET … JAR construit SANS SBOM, à ne pas livrer tel quel », code 0 ; **en CI (variable `CI`) ou en ligne : erreur, code 2 (inchangé)** ; hors ligne avec un `bom.json` ancien : avertissement « non régénéré » ; `DEPLOIEMENT.md` §3.1 (sous-section distincte) | `outils/tests/sbom-et-licences.test.mjs` : +3 tests (hors ligne hors CI → 0 + avertissement, **code 2 sur l'ancien outil** ; CI et en ligne → 2 ; argument présent dans le pom), 9/9. À la main : `mvn -o package` → BUILD SUCCESS avec l'avertissement ; `CI=true mvn -o package` → échec ; `mvn package` en ligne → SBOM complété (5 composants) |

**Tests** : suite back complète sur PostgreSQL (`ged_dev2_test`) : **662 tests, 0 échec** (661 + 1 `ScriptsExploitationTest`). `node --test outils/tests/*.test.mjs` : 9/9. `test-nginx-ipv6.sh` : code 0, répertoire supprimé. Front non touché.

**Reste** : variante IPv6 de `test-nginx-ipv6.sh` (démarrage en `[::1]`) à éprouver sur un hôte IPv6 (réserve UAT, inchangée).

**Points pour pm** :
- `DEPLOIEMENT.md` : ma note est la sous-section **§3.1 « Construction hors ligne et SBOM »**, en fin de §3, avant `## 4. Frontend` ; la note pm du même tour est à placer ailleurs dans §3 (ou à fusionner à la main si elle tombe au même endroit).
- Ce journal : la section « Tour 3 » est sur `ct/dev2-r3`, cette branche part de `ct/qa-r3` ; les deux sections s'insèrent au même endroit (en tête) : garder les deux, Tour 4 au-dessus.
- En CI, GitHub Actions positionne `CI=true` : le contrôle T-085 reste bloquant ; aucun changement de `ci.yml`.

## Tour 3 de mise en conformité (01/10/2026) — branche `ct/dev2-r3`

Base : `claude/inspiring-lovelace-10bg1c` (`ff20f21`). Référence : 661 tests back, 0 échec.

| Ligne / observation | Commit | Cause | Correctif | Preuve (échoue sans le correctif) |
|---|---|---|---|---|
| P-05 (réserve qa vague 9) | `3adbf0b` | `SEQUENCES.md` relu au tour 1 sur `71bdc1d` ; note du flux de délégation restée « D15 à livrer par dev1 » ; le code du tour 1 et du tour 2 avait changé quatre flux | document relu contre `ff20f21`, **six flux** : délégation (D15 livrée, `EtatCompteEnCache`, bascule `ControleursAnnuaire` avec mise à l'écart, **503 `ANNUAIRE_INDISPONIBLE`** représenté, refus de la clé détaillés, métrique par clé) ; dépôt (emplacement dans un espace d'échange, 422 `EMPLACEMENT_HORS_ESPACE_ECHANGE`, désignation du déposant d'un Confidentiel, aucun circuit si le module workflow est inactif) ; OCR (toute erreur reprise, file par priorité, clôture d'un bail expiré) ; recherche du contrat (fragments SQL R32, plus de passage par `IndexationService`, plafonds, critères §4.4.3, `PARAMETRE_INCONNU`) ; **flux nouveau** : recherche par index (`POST /documents/recherche`) ; archivage (empreinte recalculée, restauration sous dossier archivé) | `SequencesDocumenteesTest` étendu : tout code cité (MAJUSCULES_SOULIGNÉES) existe dans le code, aucune mention « à livrer » / « à venir » — **en échec sur l'ancien document** |
| O1 (recette vague 9) | `b18c4bd` | fil d'écoute de l'annuaire embarqué (UnboundID, fil **non démon**), gardé dans une table statique de `AnnuaireEmbarque`, jamais arrêté à la fermeture du contexte | `AnnuaireEmbarque` rend l'annuaire à la fermeture du contexte (compte des contextes qui le partagent, le dernier l'arrête) ; `GedApplication.main` sort avec le code 1 sur tout démarrage refusé, quel que soit le fil survivant ; `EXPLOITATION.md` §2 | `AnnuaireEmbarqueTest` (contexte refusé : port fermé, fil arrêté ; partage entre contextes) ; `DemarrageRefuseTest` (vraie application, JVM fille, profil dev, cache D15 à 6 min : arrêt et code non nul) — **les deux en échec sans le correctif** (JVM vivante après 240 s) |
| O3 (recette vague 9) | `00d487e` | hôte, port et superutilisateur PostgreSQL écrits en dur dans `demontrer-deploiement.sh` (`localhost:5432`, `runuser -u postgres`) | variables `DEMO_PGHOST`, `DEMO_PGPORT`, `DEMO_PGSUPER`, `DEMO_PGSUPER_COMPTE`, `DEMO_PGSUPER_HOTE`, `DEMO_CREER_ROLES` ; mode `DEMO_INSTANCE_JETABLE=oui` (instance créée, utilisée et supprimée par le script) ; défauts inchangés ; `DEPLOIEMENT.md` §10.5 | `ScriptsExploitationTest.demonstrationSansServeurPostgresEnDur` (**en échec sur l'ancien script**) ; démonstration rejouée sur instance jetable (ci-dessous) |

**O1 sous les profils uat et prod** (vérifié, pas supposé) : même refus (cache à 6 min) en
lançant `GedApplication` avec la configuration d'exploitation minimale (base en
`verify-full` sur le certificat du serveur local, LDAPS, keystore JWT et KEK jetables) :
la JVM s'arrêtait déjà avec le code 1 (uat 19 s, prod 42 s). Seul le profil dev
(annuaire embarqué) restait vivant ; le profil test n'est concerné qu'en théorie
(surefire termine sa JVM). Après correctif, profil dev : arrêt en 14 s, code 1.

**O3 rejoué** : `DEMO_INSTANCE_JETABLE=oui deploiement/uat/demontrer-deploiement.sh`
(JAR et paquet front construits depuis la branche, instance PostgreSQL 16 jetable
sur 127.0.0.1:18782, rôles créés par `creer-roles.sql`) : **43 contrôles verts**
(E0 à E8), instance arrêtée en fin de script ; l'instance partagée n'a pas été touchée.
Second passage avec la version commitée (`00d487e`) : 43 contrôles verts, code de
sortie 0, instance arrêtée et son répertoire supprimé ; répertoire de travail
`/tmp/ged-demo-dev2r3` supprimé après examen des journaux.

**Tests** : suite back complète sur PostgreSQL (`ged_dev2_test`) : **667 tests, 0 échec, 0 erreur** (référence 661 : +2 `SequencesDocumenteesTest`, +2 `AnnuaireEmbarqueTest`, +1 `DemarrageRefuseTest`, +1 `ScriptsExploitationTest`). Front non modifié (paquet construit pour la démonstration O3 : `ng build` vert).

**Reste** :
- O3 : rejeu par qa (commande dans `DEPLOIEMENT.md` §10.5) ; sous systemd réel en UAT,
  inchangé.
- O1 : `DemarrageRefuseTest` lance une JVM fille (environ 20 à 30 s ajoutés à la suite) ;
  il lit `DB_NAME_TEST` (ou `DB_NAME` + `_test`) comme `application-test.yml`.
- Reliquats du tour 2 inchangés (échéance du secret de l'annuaire : livrée par dev1
  `d7efa55` ; IPv6 de NGINX à constater sur un hôte IPv6).

**Points pour pm** :
- `GedApplication.main` appelle désormais `System.exit(1)` sur un démarrage refusé :
  aucun effet sur les tests (le `main` n'y est pas appelé, sauf par la JVM fille de
  `DemarrageRefuseTest`) ; sous systemd, `Restart=on-failure` relance (5 essais en
  10 min) — `EXPLOITATION.md` §2.
- SUIVI.md, P-05 : la réserve de forme de qa (note D15 du flux 4, 503 non représenté)
  est levée sur `3adbf0b` ; six flux au lieu de cinq (recherche par index ajoutée).
- **qa** : O3 se rejoue sans l'instance partagée (`DEMO_INSTANCE_JETABLE=oui`, ports
  propres : `SERVER_PORT`, `GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT`) ; O1 : relancer avec
  `GED_DELEGATION_CACHE_ETAT_COMPTE=6m` en profil dev, la JVM doit s'arrêter (code 1).
- Poste : mon lancement en profil dev a créé `~/.ged-dev/cles/ged-kek.p12` (chemin par
  défaut du profil dev, hors dépôt) ; laissé en place car d'autres membres peuvent
  s'en servir.
 (30/09/2026) — branche `ct/dev2-r2`

Base : `claude/inspiring-lovelace-10bg1c` (`08c710c`). Référence : 630 tests back, 0 échec.

| Anomalie / ligne | Commit | Cause | Correctif | Preuve (échoue sans le correctif) |
|---|---|---|---|---|
| ANO-E5-004 (T-066, P-07) | `fb4cf60` | `FiltreConventionsApi` lit les parties multipart (contrôle des 64 Ko) : la `FileSizeLimitExceededException` / `SizeLimitExceededException` de Tomcat, enveloppée dans une `IllegalStateException`, sortait du filtre hors du gestionnaire commun → 500 | le filtre reconnaît un dépassement de plafond (critère de Spring, indépendant du conteneur) et répond 413 `FICHIER_TROP_VOLUMINEUX` en problem+json ; autre échec d'analyse : inchangé | `FiltreConventionsApiPlafondTest` : **vrai Tomcat embarqué**, plafonds réduits (1 Kio / 4 Kio) : fichier et requête trop gros → 413 problem+json, fichier admis → 201 (500 sans le correctif) |
| ANO-E10-007 (P-13) | `a0c7da1` | purge des lots de quarantaine sans recontrôle en base ; aucune garde « application arrêtée » ; manquants calculés sur toute la table `cle_fichier` (clés du cache d'aperçus comprises) ; `mv` recalculé vers `aa/bb/<id>.enc` | refus de `--appliquer` et de la purge si `ged-backend` est actif ou si `ged_app` a une session sur la base ; fichiers modifiés depuis moins de 60 min laissés en place (`RECENT`, `--age-minimal-minutes`) ; purge : recontrôle de `cle_fichier` juste avant, fichier redevenu référencé **remis en place** ; manquants = colonnes qui référencent `cle_fichier` par clé étrangère (découvertes dans le catalogue) ; `MAL_RANGE` signalé, déplacé par son vrai chemin | `deploiement/sauvegarde/tests/test-rapprocher-orphelins.sh` (instance PostgreSQL jetable) : 19 contrôles verts, **11 en échec sur l'ancien script** ; `test-restauration-a-blanc.sh` toujours réussi |
| ANO-E10-006 (T-073) | `bbbdf4f` | `pg_dump` sans `--create` ne porte ni `pg_database.datacl` ni les `ALTER ROLE … IN DATABASE` | `sauvegarder-base.sh` exporte `droits-base.sql` (propriétaire, ACL dans leur ordre, réglages ; listes reprises comme pg_dump) ; `restaurer.sh base-logique` le rejoue sur la cible (sauf `--sans-proprietaires`) ; ancienne sauvegarde : message qui renvoie à `preparer-base.sql` | `deploiement/sauvegarde/tests/test-restaurer-droits.sh` (restauration à blanc, instance jetable, vrais scripts, base préparée par `preparer-base.sql`) : datacl, propriétaire, réglages identiques, `search_path` actif, CONNECT refusé à un rôle tiers — **5 contrôles en échec avec l'ancien `restaurer.sh`** |
| Réserve T-006 (O1 vague 8) | `4caa7a0` | `listen [::]` : NGINX refuse de démarrer sans IPv6 (errno 97) | écoutes IPv6 déplacées dans `ecoute-ipv6-http.conf` / `ecoute-ipv6-https.conf`, inclus par motif (`[.]conf`) depuis `/etc/nginx/ged/` : absents, rien n'est lu ; `EXPLOITATION.md` §3 | `deploiement/nginx/tests/test-nginx-ipv6.sh` (NGINX 1.24 réel) : sans les fichiers, `nginx -t`, démarrage, 301, 200 ; avec, syntaxe acceptée et refus errno 97 constaté (poste sans IPv6) ; l'ancien `ged.conf` échoue |
| ANO-E10-008 (T-088) | `b133653` | `ServiceCircuits.ouvrirAuDepot` ignorait l'état du module | **décision** : module `workflow` inactif → dépôt traité comme sans règle (document utilisable, trace WARN au journal technique) ; le dépôt (socle) n'est pas refusé ; après réactivation, circuit à ouvrir à la main si besoin ; `DEPLOIEMENT.md` §10.4 (limites : circuits ouverts avant la désactivation inchangés) | `ModulesInactifsApiTest.depotSousRegleSansCircuit` : 0 circuit, `active=true` (1 circuit sans le correctif) |
| ANO-E0-002 (T-085) | `f2fba2f` | `690d3d2` ne traçait les modèles que dans `docs/DEPENDANCES.md`, pas dans le SBOM | `outils/completer-sbom.mjs`, lancé par `mvn package` après CycloneDX (exec-maven-plugin), ajoute à `bom.json` et `bom.xml` Tesseract (5, Apache-2.0) et `tessdata-ara/eng/fra/osd` (4.1.0, SHA-256, `ged:origine`) ; liste partagée avec le registre (`outils/composants-hors-gestionnaire.mjs`) | `mvn package` : 5 composants `tesseract-ocr` ; `bom.json` et `bom.xml` **valides au schéma CycloneDX 1.6** (cyclonedx-core-java) ; `node --test outils/tests/*.test.mjs` ; contrôle ajouté en CI |
| ANO-E0-003 (P-19) | `f2fba2f` | un arbitrage valait acceptation ; l'option MPL-2.0 du groupe `org.verapdf:` écrasait toute déclaration | table `REFUSES` (mysql-connector-j) : présence = échec de `--verifier` ; option de groupe retenue seulement si le composant la déclare, arbitrage de groupe inapplicable sinon (`verapdf-xmp-core-jakarta` redevient BSD-3-Clause) ; registre régénéré | `outils/tests/sbom-et-licences.test.mjs` : 6 tests, **2 en échec sur l'ancien outil** (mysql accepté, GPL-3.0-only seul accepté) |
| ANO-E10-002 (1er point, T-075) | `bc2a920` | aucune métrique par application ou clé | `FiltreCleApi` : `ged_api_appels_total{application,cle,resultat,statut}` (identifiant public ; clé inconnue sous `inconnue`) ; `EXPLOITATION.md` | `ClesApiTest.metriqueAppelsParCle` sur la sortie Prometheus (en échec sans le correctif) |

**Tests** : suite back complète sur PostgreSQL (`ged_dev2_test`) : **636 tests, 0 échec** (référence 630 : +4 `FiltreConventionsApiPlafondTest`, +1 `ModulesInactifsApiTest`, +1 `ClesApiTest`). Scripts : `test-rapprocher-orphelins.sh`, `test-restaurer-droits.sh`, `test-restauration-a-blanc.sh` (instance jetable, 50 documents), `test-nginx-ipv6.sh` réussis ; `node --test outils/tests/*.test.mjs` : 6/6. Front non touché.

**Reste** :
- ANO-E10-002, second point : échéance du secret du compte de service de l'annuaire (P-02, avec dev1) —
  lecture de `pwdLastSet`/`accountExpires` non faite ce tour.
- NGINX avec IPv6 : variante « avec » vérifiée seulement jusqu'à la syntaxe (poste sans IPv6) ; démarrage
  et 301 en `[::1]` à constater sur un hôte IPv6 (le test le fait tout seul).
- Versement sur un document dont le circuit a été ouvert avant la désactivation du workflow : le statut
  est encore recalculé (document inactif jusqu'à la réactivation). Documenté, non corrigé.

**Points pour pm** :
- `backend/pom.xml` : propriété `ged.sbom.completer.skip` et plugin `exec-maven-plugin` 3.5.0 (phase
  `package`, après CycloneDX) : `mvn package` exige désormais `node` (déjà requis pour le front) ;
  `-Dged.sbom.completer.skip=true` pour s'en passer (SBOM alors incomplet). `mvn test` n'est pas touché.
- `.github/workflows/ci.yml` (job `registre`) : deux étapes ajoutées (tests `node --test`, présence de
  Tesseract dans le SBOM).
- **qa** : la recette `recette/e10-sauvegarde/recette-t073-p13.sh` crée ses « orphelins » quelques secondes
  avant le rapprochement : ils sont désormais `RECENT` et laissés en place ; ajouter
  `--age-minimal-minutes 0` (ou vieillir les fichiers par `touch -d`) pour P13-02, P13-08 et P13-10. P13-11
  (fichier en cours de dépôt) est couvert par l'âge minimal et par la garde `ged_app` (la recette ouvre sa
  transaction avec `postgres`, pas `ged_app`). `recette/e10/verifier-nginx-reel.sh` : ses `sed` sur
  `listen [::]` deviennent sans objet (le `ged.conf` livré démarre sans IPv6).
- `restaurer.sh base-logique` rejoue `droits-base.sql` : rôles d'origine requis sur le serveur cible (sinon
  `--sans-proprietaires`, droits non rétablis, message au journal).

## Tour 1 de mise en conformité (30/09/2026) — branche `ct/dev2-r1`

Base : `claude/inspiring-lovelace-10bg1c` (`71bdc1d`). Référence des tests du poste avant le tour :
597 tests, 5 échecs (les 4 connus + `SupervisionIntegrationTest.portDeManagement`, qui dépendait de
`GED_MANAGEMENT_PORT` : corrigé dans `47edd6a`).

| Ligne | Commit | Fait | Preuve | Reste |
|---|---|---|---|---|
| T-074 | `47edd6a` | test : toute métrique GED citée par `alertes.yml` est publiée (dont `ged_annuaire_controleur`, D4) ; test du port de management indépendant du poste | `SupervisionIntegrationTest.alertesSurDesMetriquesPubliees` | Prometheus réel en UAT |
| P-11 | `c92e4d8` | `MODELE-DE-MENACES.md` : compromission de la KEK (§3 bis), port de management (§10 bis), clamd local partout (§0), chaque parade d'échec fermé renvoyée à son test (§12) ; rotation immédiate de la KEK outillée (`LanceurRotationKek`) | `RotationImmediateKekTest` | candidate « Identique » après relecture qa |
| P-10 | `eb757d5` | `crypttab` en deux variantes (TPM `tpm2-device=auto`, Tang `_netdev`), PostgreSQL lié au montage, sauvegarde de l'en-tête LUKS | `deploiement/luks/essai-crypttab.sh` (générateurs systemd 255 réels), `essai-entete-luks.sh` (image LUKS2 : sauvegarde, destruction, restauration) | ouverture TPM ou Tang : UAT |
| P-16 | `9a1e400` | `shared_preload_libraries` complété sans écraser (`activer-pgaudit.sh`), tout compte tracé sauf `ged_app`, DBA nominatifs (`ged_dba`), `ged_sauvegarde` et `SET ROLE` tracés | `deploiement/postgresql/essai-pgaudit.sh` : instance PostgreSQL 16 jetable avec pgaudit réel, 17 contrôles verts | limite déclarée : un superutilisateur peut couper pgaudit (visible par `log_statement`) |
| P-17 | `5191bac` | `GARANTIE.md` : point de départ des 24 h, RPO base 15 min / fichiers 24 h, §4 bis fournisseurs tiers | relecture | ANO-E8-003 (dev1) reste à corriger côté changeset |
| P-05 | `1fcd132` | `SEQUENCES.md` relu contre le code (filtres, dépôt, OCR, recherche, délégation D8/D15) | `SequencesDocumenteesTest` (toute classe citée existe) | — |
| T-070 | `80c333a` | Dependency-Check éprouvé sans la NVD : miroir local au format NVD 2.0 ; CI : miroir (`NVD_DATAFEED_URL`) ou clé, cache NVD gardé même en échec, rapport `npm audit` archivé ; `docs/securite/VULNERABILITES-DEPENDANCES.md` (règle, modes API / miroir / hors ligne) | `outils/essai-dependency-check.sh` : 11 contrôles verts (base alimentée, 148 dépendances, échec à CVSS ≥ 7, CVSS 5,3 rapporté, suppression datée) ; `npm audit` réel : 0 haute ni critique, 2 avis moyens Angular sans exposition (`docs/securite/rapports/npm-audit-2026-09-30.json`) | **bloqué ici** : NVD et miroirs publics refusés par le mandataire ; sur GitHub, créer le secret `NVD_API_KEY` (le job échoue volontairement, exécution n° 4) |
| T-085 | `690d3d2` | SBOM régénérés (140 back, 18 front, licences toutes renseignées) ; modèles Tesseract identifiés par SHA-256 (eng, fra, ara : `tessdata_best` 4.1.0 ; osd : `tessdata` 4.1.0), modèle d'origine inconnue bloquant | `registre-dependances.mjs --verifier` : échoue avec un modèle inconnu, vert sinon | SBOM front sans empreintes (outil CycloneDX npm) |
| T-089 | `ad5d8ce` | `FORGE.md` adapté à `abddou2000/GED` : état relevé en lecture seule (dépôt **public** d'un compte **personnel**, `main` au socle d'origine sans CI, aucune protection), commandes `gh` réelles, lecture seule de MMED | relevé API GitHub du 30/09 | **décision pm/MMED** : dépôt public et personnel (pas de rôle « Read » possible) → organisation + dépôt privé (offre Team) recommandés ; réglages à appliquer par le propriétaire |
| T-088 (1), T-092 | `b778186`, `0f2509a` | démonstration outillée de `deployer.sh` et de ses retours arrière ; **4 défauts corrigés** (ci-dessous) | `deploiement/uat/demontrer-deploiement.sh` : **43 contrôles verts** (E1 à E8) ; `ScriptsExploitationTest` | rejouer sous systemd en UAT (`DEPLOIEMENT.md` §10.5) ; critère (2) : dev4 |

**Défauts de `deployer.sh` trouvés par la démonstration** (`b778186`) :

1. scripts d'exploitation livrés sans droit d'exécution (mode git 100644) : `deployer.sh` échouait dès la
   sauvegarde préalable (`Permission denied`), `ged-sauvegarde.service` aussi ;
2. `test-fumee.sh` envoyait `{email, motDePasse}` : 400, le contrat est `{identifiant, motDePasse}` (D2) —
   déjà constaté par qa (recette T092-05 sur `ct/qa`, pas encore au registre) ;
3. `test-fumee.sh` déposait sans `Idempotency-Key` : 400 `IDEMPOTENCE_CLE_ABSENTE` (T-049) ;
4. `--retour-arriere --base` après un retour arrière automatique : rollback lancé avec le JAR redevenu actif,
   qui ignore les changesets de la version défaite. Rejoué avec l'ancien script : « 0 changesets rolled back »,
   succès affiché, colonne de la v3 restée en base. Corrigé : rollback avec le JAR qui a migré.

Les défauts 1 à 3 rendaient tout déploiement réel impossible (retour arrière systématique) : T-092 était
« Livré » sur le papier seulement.

**Autres preuves obtenues ce tour, pour pm** :

- **T-087** : la CI tourne sur GitHub (exécution n° 4 du 30/09, `71bdc1d`) : back-end (tests sur PostgreSQL 16,
  JAR, SBOM), front-end (tests, paquet, SBOM, audit) et registre **verts** ; seul OWASP échoue (secret absent).
  La réserve « CI jamais exécutée » peut être levée.
- **T-006, T-066** : NGINX est présent sur le poste ; `nginx -t` passe sur `deploiement/nginx/ged.conf` (ports,
  certificat et chemins réécrits) et le paquet Angular est servi en HTTPS avec CSP, `nosniff`, HSTS, sans
  version du serveur, `config.json` hors paquet et proxy de l'API (test de fumée de la démonstration).
- **T-093** : l'unité `ged-backend.service` a été exécutée (par le systemctl simulé) : `EnvironmentFile` dans
  l'ordre, `User=ged`, `ExecStartPre`, arrêt progressif ; le durcissement systemd reste à vérifier en UAT.

**Tests** (fin du tour, `ct/dev2-r1`) : suite back complète **605 tests, 4 échecs**, exactement les 4
connus de la référence (`ArchivageApiTest.conversionEnEchec`, `ApercuTelechargementApiTest.apercuBureautiqueSansLibreOffice`,
`WorkflowApiTest.employesWithAccount`, `WorkSpaceApiTest.moveIntoDescendant`) ; aucun nouvel échec, le
5e échec du poste (`portDeManagement`) corrigé. Front non modifié (paquet construit pour la démonstration :
`ng build` vert sous Node 24 ; le Node 22.22.2 du poste est refusé par Angular CLI, qui exige 22.22.3).

**Points pour pm** : décision sur la visibilité et le propriétaire du dépôt GitHub (T-089) ; secret
`NVD_API_KEY` ; montée d'Angular en 22.1.1 ou plus (dev4) ; le registre qa ne porte pas encore l'anomalie
T092-05 (corrigée ici par `b778186`).

## T-088 (§9.3) : UAT et déploiement incrémental par module — **livré sur ct/dev2**

| Livré | Détail |
|---|---|
| Modules | Socle toujours actif (identité, habilitations, arborescence, dépôt, consultation, typologie, audit) + six modules métier du dossier fonctionnel : `ocr` (§4.2, §4.4), `workflow` (§4.5), `cycledevie` (§4.6, §4.7), `export`, `notifications`, `integration` (§4.10, DAT §5) — catalogue `com.ipt.ged.modules.ModuleMetier` |
| Drapeaux | `ged.modules.<code>.actif` / `GED_MODULES_<CODE>_ACTIF` (vrai par défaut, code inconnu = démarrage refusé) ; module inactif : routes en 404 `MODULE_INACTIF` avant l'authentification (intégration : toute requête `X-API-Key`), traitements de fond arrêtés par propriétés imposées (`ModulesEnvironnement` : chaîne OCR, alertes d'échéance, écriture et expédition des notifications) ; état : `GET /api/v1/modules`, métrique `ged_module_actif{module}` |
| Outillage | `deployer.sh <env> --activer-module <code>` / `--desactiver-module <code>` / `--modules` : `/etc/ged/modules.env`, redémarrage, sonde, contrôle de l'état publié, test de fumée, retour à l'état précédent en cas d'échec ; `EnvironmentFile=-/etc/ged/modules.env` dans le service ; `modules.env.exemple`, `.env.example` |
| Procédure | `DEPLOIEMENT.md` § 10 (modules, configuration, procédure UAT pas à pas, limites) |
| Tests | Suite complète : **605 verts** (0 échec). `ModulesTest` (5 : défauts, arrêt imposé contre un réglage contraire, routes fermées et ouvertes, code inconnu, catalogue), `ModulesInactifsApiTest` (3, contexte avec quatre modules inactifs : 404 `MODULE_INACTIF`, clé d'API fermée, socle et module actif servis, API et métrique, tâche d'alertes absente, aucune notification écrite) |

Statut proposé : **Identique** (script vérifié sur le papier : pas de systemd sur le poste). Limites
(DEPLOIEMENT.md § 10.4) : schéma commun à tous les modules ; `workflow` inactif n'empêche pas
l'ouverture d'un circuit au dépôt si une règle est déjà rattachée (code de dev1, non modifié) ;
redémarrage nécessaire pour changer un module. Front (masquage des menus par `GET /api/v1/modules`) :
à faire par dev4 / dev5.

## T-025 (§12.1) : aide à dev1 — tables du modèle de référence

Comparaison du tableau 12.1 du PDF (34 tables principales en sept groupes, plus
`document.metadonnees` en JSONB) avec une base créée par Liquibase (`ged_dev2_test`,
`conformite-technique` bc371ad) : **aucune table absente, aucune sous un autre nom**.
`ModeleDeReferenceTest` le fige désormais (tables des sept groupes, `noeud.parent_id` et
`chemin`, `document.metadonnees` JSONB, `utilisateur` sans mot de passe, clés UUID sauf
`journal_audit`).

- **Mon périmètre** (Identités et accès côté API : `application`, `cle_api`, `cle_api_portee` ;
  Traçabilité et exploitation : `journal_audit`, `journal_audit_scellement`, `notification`,
  `idempotence_cle`, `job_archivage`) : conforme, **aucun changeset nécessaire**.
- **Écarts dans les groupes de dev1** (structure, pas nom de table), à traiter par dev1 :
  1. `groupe_ged` porte encore les huit colonnes booléennes de l'ancien modèle
     (`droit_access`, `droit_lecture`, `droit_modifier`, `droit_uploader`, `droit_supprimer`,
     `droit_deplacer`, `droit_ajouter_version`, `droit_verrouiller_deverrouiller`, entité
     `accessgroup.GedRights`) : au §12.2.1 un groupe n'est qu'un sujet d'habilitation, les droits
     passent par `role`, `role_permission` et `habilitation`. À retirer (expand / contract) ou à
     justifier comme reliquat sans effet ;
  2. `groupe_membre` rattache un **employé** (`employe_id`) et non une identité GED
     (`utilisateur_id`), alors qu'`habilitation` et `document_confidentiel_designe` désignent un
     `utilisateur` : deux clés d'identité pour un même sujet (résolution faite dans
     `ServiceCircuits` par `employe_id`). À aligner ou à justifier ;
  3. (mineur, conventions §4.2.2) colonnes `name` en anglais sur `groupe_ged`, `noeud`,
     `regle_workflow`, héritées de l'application d'origine.

## T-055 (§5.5) : question à poser à Marchica Med pour lever l'écart (QR9, R28)

**Objet** : délégation d'identité (`X-On-Behalf-Of`) vers un compte Active Directory désactivé.

**Ce que dit le dossier technique (§5.5, « Vérification »)** : « le back-end résout l'identifiant dans
l'annuaire, vérifie que le compte est actif […] ; un en-tête […] correspondant à un compte désactivé
est rejeté (HTTP 422, IDENTITE_DELEGUEE_INVALIDE) ».

**Ce que MMED a décidé ensuite (revue technique, D1)** : la GED ne lit pas l'attribut d'activation
AD (`userAccountControl`) ; un compte désactivé échoue à la connexion, cela suffit.

**Le conflit** : la délégation ne passe pas par une connexion de la personne. L'application (par
exemple le bureau d'ordre ou l'intranet) s'authentifie avec sa clé et désigne la personne par son
identifiant. Sans lecture de l'état du compte, la GED trouve encore dans l'annuaire un agent parti
ou suspendu, et accepte qu'une application dépose, pilote un circuit ou valide **en son nom**
(constaté en recette, vague 4, cas 22).

**Question** — MMED autorise-t-elle la GED, **pour la seule vérification d'une identité
déléguée**, à lire l'attribut `userAccountControl` (bit 2, « compte désactivé ») de la personne
désignée, avec le compte de service LDAP en lecture déjà prévu ?

| Réponse | Conséquence | Délai |
|---|---|---|
| **Oui** (recommandé) | Lecture ponctuelle au moment de la délégation, une requête LDAP par appel délégué (cache court de quelques minutes au plus), aucune tâche périodique : D1 reste vrai pour l'authentification et les sessions. Compte désactivé = 422 `IDENTITE_DELEGUEE_INVALIDE`, tracé. T-055 passe à « Identique ». | 1 jour de développement et de test (simulateur d'annuaire déjà prêt : il porte le bit ACCOUNTDISABLE) |
| **Non, mais** les comptes désactivés sont déplacés dans une OU dédiée (ou retirés d'un groupe) | La recherche de l'identité déléguée exclut cette OU (ou exige ce groupe) : même résultat sans lire l'attribut. MMED fournit le DN de l'OU ou du groupe. | 1 jour |
| **Non** | MMED accepte par écrit l'écart au §5.5 : une application habilitée à déléguer peut agir pour un compte désactivé. Mesures qui restent : attribut « délégation » accordé par l'Administrateur, adresses sources obligatoires, portée limitée par espace, double identité au journal, revue périodique des clés. T-055 reste « Proche » avec dérogation (risque R28 accepté). | — |

**Ce qu'il faut en retour** : le choix (oui / OU dédiée / non), le nom du signataire, et, pour
l'OU ou le groupe, son DN exact. Destinataires : DSI de MMED (annuaire) et responsable de la
sécurité ; question QR9 du registre des risques.

## ANO-E8-001 (majeure, D8) : règles de workflow par une application — **corrigée sur ct/dev2**

Une application peut créer, modifier, supprimer et restaurer une règle de workflow (désignation
des validateurs, D8) sur `/api/v1/workflow/regles` (et l'ancien `/workflowgeds`). Décision,
intersection explicite de trois conditions (`cleapi.GardeReglesWorkflowApplications`, appelée
par `GardeDroitsRequetes` pour une application ; utilisateurs inchangés) :
1. **délégation** §5.5 : `X-On-Behalf-Of` obligatoire (403 `DELEGATION_REQUISE`), la personne est
   l'auteur, double identité au journal (`WORKFLOW_CREE`, `WORKFLOW_MODIFIE`…) ;
2. **portée de la clé** : opération `WORKFLOW_PILOTAGE` lue explicitement dans `cle_api_portee`
   (une clé de versement ne pilote pas), sur chaque nœud où la règle s'applique (nœuds, types de
   document, périmètres de validateurs par rôle, sous-arborescence comprise) ; règle non rattachée :
   portée sur au moins un nœud ; périmètres du corps contrôlés par `PorteeReglesWorkflowCorps` ;
3. **droits de la personne** : `GERER_REFERENTIELS`, comme dans l'interface et comme le rattachement
   par API de dev1 (`RattachementRegles`) : la clé ne donne à personne un droit qu'il n'a pas.

Opérations de masse (`multiple-*`) réservées à l'interface. Aucun changement dans `workflow`
(`ServiceCircuits`, contrôleurs) ; seul ajout chez dev1 : une branche dans `GardeDroitsRequetes`.
Description OpenAPI (`WorkflowRequest`). Test de bout en bout `ReglesWorkflowApplicationsTest`
(clé avec et sans `WORKFLOW_PILOTAGE`, délégué Administrateur et non administrateur, sans
délégation, règle hors portée, périmètre de rôle hors portée, masse). `mvn test` **589 verts**.
**Pour dev1** : la ligne `POST /regles` du contrat E8-API (suivi dev1) peut indiquer
« application : délégué avec `GERER_REFERENTIELS` et portée `WORKFLOW_PILOTAGE` ».

## Contrat d'API : filtre « échéance dépassée » (T-112) sur `POST /recherches` — **livré sur ct/dev2** (bdff78c)

Après fusion de `conformite-technique` (68a1f90, T-112 de dev1) : critère `echeanceDepassee`
avec la même sémantique que chez dev1 (échéance ≤ jour de MMED, `Echeances`) — fragment de
`CriteresMetadonnees` avec plein texte, filtre des résultats de l'indexation sans plein texte ;
colonne `echeanceDepassee` des résultats renseignée dans les deux cas ; décrit dans `champs.yml`
(OpenAPI). Test `ContratApiTest.rechercheEcheanceDepassee` (échéance passée, du jour même,
future ; avec et sans plein texte). `mvn test` **587 verts**, `ng build` vert.

## Anomalies de la recette de la vague 5 : **corrigées sur ct/dev2**

| Anomalie | Correction | Commit |
|---|---|---|
| ANO-E9-001 (majeure, T-040) | `cleapi.SourceDepotApplications` (`@Primary`) : l'origine du dépôt est lue sur le jeton `ApplicationAuthentifiee` (et non sur le principal, qui sous délégation est la personne) : canal `API` (bureau d'ordre reconnu à son code), `applicationId`, déposant délégué, `depotDelegue` vrai. Test par l'API réelle : `ContratApiTest.depotParApplication` (dépôt par clé avec et sans `X-On-Behalf-Of` : fiche, base, recherche par canal) ; `SourceDepotApiTest` ne garde que les contraintes de base | b39fa60 |
| ANO-E11-001 (P-12) | `AppelsSortantsTest` : motifs complétés (`createSocket`, `SocketFactory`, `toURL()`, contextes et clients LDAP, écoute, résolution de nom, JDBC direct) ; `FabriqueSocketsLdaps`, `SimulateurAnnuaire`, `ConfigurationProxysDeConfiance` inventoriés ; nouveau test `inventaireJustifie` (chaque entrée reconnue par un motif et citée dans `REVUE-SSRF.md`) | 5d0a363 |
| ANO-E11-002 (P-12) | `sorties.conf.exemple` : résolveurs DNS de MMED (ou noms figés dans `/etc/hosts`), une ligne par contrôleur (D4), NGINX aligné ; `deploiement/scripts/verifier-sorties.sh` (chaque nom de `ged.env` résolu comme la JVM doit tomber sur une adresse autorisée ; éprouvé avec un résolveur simulé) ; flux local vers LibreOffice déclaré, `deploiement/libreoffice/ged-securite.xcd` (ressources liées et macros bloquées), `SERVER_ADDRESS` hors boucle locale, risque résiduel de la boucle (clamd) déclaré dans `REVUE-SSRF.md` §3 ; installation et contrôle `EXPLOITATION.md` §12 | 5d0a363 |
| ANO-E10-001 (P-17) | `GARANTIE.md` §2 : double écriture maintenue en N+1 (retour vers N sans perte), retour arrière du contract par reconstitution de l'ancienne colonne depuis la nouvelle, variante sans double écriture interdite sauf recopie livrée et éprouvée en UAT | 5b8e15b |

Tests après fusion de `conformite-technique` (b7274bc, E7 modèle, E8 et E8-API de dev1) : `mvn test` **580 verts** (0 échec), `ng test` 18 verts, `ng build` vert ; schéma et diagrammes de classes régénérés.
Vérifié sur le papier seulement : filtre systemd, couche de configuration LibreOffice
(ni systemd ni LibreOffice sur le poste).

## Dernière partie du périmètre — contrat d'API, finitions E10/E11, modélisation : **acceptée** (4d28528)

| Réf. | Exigence | Livré | Statut proposé |
|---|---|---|---|
| P-06, T-042 (§5.3.1) | chemins exacts du contrat | `contratapi` : `POST /noeuds/{id}/dossiers` (Déposer sur le parent, circuit hérité, code attribué, 201 + Location), `POST /recherches` (plein texte de `SearchIndexer` + critères d'index d'`IndexationService`, en ET, droits à la source, pagination 50 / 200, tri en liste blanche), `GET /documents/{id}/contenu?version=` (`DocumentService.telechargerVersion`, audité) ; `POST /documents`, `POST /documents/{id}/versions`, `POST /documents/{id}/rattachements` et `DELETE …/{noeudId}` vérifiés au chemin exact ; aucune règle réimplémentée ; Idempotency-Key, portée de clé, délégation et audit par les mécanismes communs ; anciens chemins conservés pour le front, leur description OpenAPI renvoie au chemin du contrat | Identique |
| T-044 (§5.3.1) | consultation des droits | `GET /documents/{id}/droits`, `GET /noeuds/{id}/droits`, `pourUtilisateur` (identifiant ou UUID) : objet visible exigé (404 sinon), soi-même ou l'utilisateur délégué sans condition, un tiers avec `GERER_ROLES_HABILITATIONS` ; droits d'une clé d'API (sa portée) ou de la délégation ; `ServiceDroitsEffectifs.calculerPourAppelant` (même fonction de décision) | Identique |
| T-065 (§6.2.1) | TLS | `securite.ControleTransportsChiffres` : démarrage refusé en uat/prod sans `sslmode=verify-full` et autorité lisible, avec une URL `ldap://` ou sans STARTTLS vers le SMTP ; tableau des liaisons dans `EXPLOITATION.md` §6 | Identique (contrôle éprouvé par test ; TLS réel vérifié sur le papier) |
| P-16 (§7.4.2) | pgaudit | `deploiement/postgresql/pgaudit.conf.exemple`, `pgaudit-roles.sql` (postgres : tout, ged_owner : DDL / droits / écritures, ged_readonly : lectures, ged_app : rien), procédure et vérification `EXPLOITATION.md` §10 | Vérifié sur le papier (pgaudit absent du poste) |
| P-10 | chiffrement du volume de la base | LUKS2 (aes-xts, argon2id) en prérequis d'installation, ouverture TPM ou Tang, séquestre des phrases, vérification : `EXPLOITATION.md` §11 | Vérifié sur le papier |
| P-12 (A10) | revue SSRF | `docs/securite/REVUE-SSRF.md` (inventaire des 8 appels sortants, aucun client HTTP) ; `securite.AppelsSortantsTest` fige l'inventaire et interdit toute destination issue d'une requête ; filtrage systemd des sorties (`ged-backend.service.d/sorties.conf.exemple`, s'applique à LibreOffice et Tesseract) | Identique |
| P-11 | modèle de menaces | `docs/securite/MODELE-DE-MENACES.md` : STRIDE par module (identité, autorisation, dépôt et stockage, recherche, cycle de vie, circuits, notifications, API, audit, exploitation, front) | Identique |
| P-17 (§10.4) | plan de garantie | `docs/exploitation/GARANTIE.md` : expand / contract type avec retour arrière par étape, règles de changeset de données, déroulé UAT puis production, contournement sous 24 h par situation | Identique |
| P-05 (§4.5, §12.1) | livrables de modélisation | `docs/modelisation/SCHEMA-BASE.md` généré depuis une base créée par Liquibase (42 tables, colonnes, contraintes, index, volumétrie à 5 ans §6.6, diagramme entité-association par groupe du §12.1) ; `CLASSES.md` (diagramme par module, généré depuis le code) ; `SEQUENCES.md` (dépôt en deux temps, OCR, recherche filtrée, délégation, archivage) ; scripts `outils/schema-base.mjs`, `outils/diagrammes-classes.mjs` | Identique |

**Anomalies de recette corrigées** :
- **ANO-E4-004** : `premier_numero` / `dernier_numero` du scellement calculés numériquement
  (min / max). L'ordre des lignes dans la chaîne (identifiant en texte, colonne de sortie
  `id::text`) est explicité (`ORDER BY 1`) et **conservé**, sinon les scellements déjà
  produits ne se vérifieraient plus. Test : période de plus de 150 lignes
  (`ScellementAuditTest.bornesNumeriques`).
- **ANO-E1-005** : `preference_notification` (clé `id` UUID + `uk_preference_notification_utilisateur_id`)
  et `journal_audit_scellement` (clé `id` UUID v7, la chaîne se suivant par période et non par
  identifiant) alignés par `202610021000_alignement_cles_uuid.xml` avec retour arrière ; seule
  exception déclarée dans `SchemaLiquibaseTest` : `journal_audit` (identifiant séquentiel exigé
  par le §7.4.1). Le scellement en INSERT seul est migré par `ADD COLUMN … DEFAULT` (réécriture
  sans UPDATE, aucun déclencheur contourné).

**Aussi** : port du SMTP simulé des tests surchargeable (`GED_SMTP_PORT_TEST`, 3025 par
défaut), documenté dans la règle 6 bis du brief :
SMTP simulé dev1 3031, dev2 3032, dev3 3033, qa 3034, pm 3035. Noms de schémas OpenAPI
homonymes désormais stables (tous préfixés quand le nom simple est ambigu), plus d'ordre
d'apparition.

**Fusion de `conformite-technique`** (a92c10d : T-040 et correctifs ANO-E7-001, ANO-E5-002
de dev3, recette qa de la vague 4) : `POST /recherches` du contrat suit le canal du dépôt
(critère `canal`, 400 hors des quatre valeurs ; colonne `canalDepot` avec ou sans plein
texte ; `ContratApiTest.recherche`). Schéma et diagrammes de classes régénérés après fusion.
Tests après fusion : `mvn test` **552 verts** (0 échec), `ng test` 18 verts, `ng build` vert.

**Limites** : pgaudit, LUKS, filtrage systemd et TLS réel non exécutables sur le poste
(vérifiés sur le papier) ; volumétrie estimée d'après les hypothèses du §6.6, à réévaluer sur
l'échantillon réel.

## Vague 4 — fusions E2/E3 (dev1) et E5-E7 (dev3), portée des clés et délégation, D4 : **acceptée** (439a08e)

Fusions de `conformite-technique` : 4f41279 (E2, E3 de dev1) dans cbae4e4, puis e81ecc2
(E5 à E7 de dev3). Références : matrice technique (MT) et fonctionnelle (MF), lignes
comptées à partir de 0.

| Réf. | Exigence | Livré | Statut proposé |
|---|---|---|---|
| MT 4.14 | §5.4 — portée des clés | `SourceHabilitationsApplications` : la clé est un sujet (`Sujet.id` = la clé, cache des droits par clé), `cle_api_portee` (nœud + opérations) traduite en attributions et décidée par le même `AccessPredicate` que les utilisateurs ; `OperationApi` → permissions (CONSULTATION, RECHERCHE → Consulter ; DEPOT, CREATION_DOSSIER → Déposer ; VERSEMENT → Consulter + Modifier ; RATTACHEMENT → Consulter + Modifier + Déposer ; WORKFLOW_PILOTAGE → Consulter + Modifier ; WORKFLOW_DECISION → Consulter + Valider, contrat E8-API de dev1) ; habilitations de sujet APPLICATION servies aussi (élémentaires seulement) ; API `GET/PUT /api/v1/cles-api/{id}/portee` (audit `CLE_API_PORTEE_MODIFIEE`, effet immédiat par `version_habilitations`), portée recopiée à la régénération ; `ControlePorteeApplication` branché sur `ControleAcces` ; FK `cle_api_portee → noeud` et `habilitation → application` ; éditeur de portée dans l'écran « Clés d'API » | Identique |
| MT 4.1 | §5.2 — applications au même modèle | sujet d'autorisation, audit, portée : oui ; routes réservées aux utilisateurs refusées aux clés (applications, clés, audit, auth, notifications, admin, groupes) | Identique |
| MT 4.15 | §5.5 — délégation `X-On-Behalf-Of` | `ResolveurDelegationAnnuaire` : attribut « délégation » de la clé (403 sinon), adresses sources obligatoires (403 `DELEGATION_SANS_ADRESSES`), identifiant (`sAMAccountName` ou objectGUID) résolu par les identités GED puis l'annuaire (provisionnement sans rôle), inconnu → 422 `IDENTITE_DELEGUEE_INVALIDE`, annuaire injoignable → 503 ; écriture : droits de la clé, l'utilisateur délégué est l'auteur (déposant) ; lecture (GET/HEAD) : **intersection** nœud par nœud des droits de la clé et de l'utilisateur ; double identité au journal (`acteur_utilisateur_id` = délégué, `acteur_application_id`) | **Proche** : rejet d'un compte **désactivé** en attente (QR9, D1) — option `ged.api.delegation.verifier-compte-annuaire` (existence dans l'annuaire), faux par défaut |
| P-02 / D4 | sonde annuaire par contrôleur | `identite.annuaire.SondeAnnuaire` (dev1) : un contrôleur = liaison par la source principale, comme avant ; N contrôleurs = liaison de chacun (mêmes réglages, `ConfigurationAnnuaire.construire`), `UP` / `DEGRADE` (un sur N) / `DOWN`, cache 30 s conservé ; jauge `ged_annuaire_controleur{controleur}`, alerte `GedAnnuaireControleurIndisponible` ; `DEGRADE` = 200 et compté disponible dans `ged_sante` | Identique (N contrôleurs éprouvés par test unitaire, un seul contrôleur réel) |
| MT 7.4 | §8.3 — registre des dépendances | veraPDF (double licence, **MPL-2.0 retenue** pour tout le groupe `org.verapdf`), xmpbox (Apache-2.0), Saxon-HE et rhino (MPL-2.0, transitives de veraPDF), jaxb-api (CDDL-1.1 retenue), stax-utils (BSD-4-Clause) arbitrés dans `outils/registre-dependances.mjs` ; usages de spring-boot-starter-mail, spring-security-ldap, unboundid, tika-core documentés ; `docs/DEPENDANCES.md` régénéré, aucune licence sans arbitrage | Identique |

**Branchements sur E2/E3** : acteur du journal = identité GED (`ActeurCourant.utilisateurId()`) ;
destinataires des notifications depuis `cache_annuaire`, `groupe_membre`, habilitations
(`AnnuaireDestinatairesIdentite`) ; écoute directe de `HabilitationModifiee` ; journal sous
`CONSULTER_AUDIT` (garde de dev1) ; administration des clés sous `GERER_CLES_API`. Tests :
`EvenementsLotsAuditTest` (connexions, habilitations au journal),
`EvenementsDocumentsJournalTest` (les 15 événements documents de dev3 au journal, lectures
en transaction en lecture seule comprises), `NotificationsTest.chaineReelleAutorisation`.

**Arbitrages de fusion à connaître (dev1, dev3)** :
- `GestionErreursAutorisation` supprimé et `ConflitAutorisationException` devenue une
  `ConflitException` (problem+json), comme annoncé par dev1 ; `CheminsAccesApiTest` lit
  `code`/`detail` (404 au libellé fixe, P5).
- dev3 : `EvenementDocument.acteurUtilisateurId()` rend `null` — le journal prend l'identité
  GED de la requête (l'employé n'est pas l'identité) ; `EvenementsAuditTest` et
  `ArchivageApiTest` ajustés. Les tests de dev3 qui lisaient `$.message` lisent `$.detail`
  (contrat problem+json) ; statuts 413/415 de dev3 conservés.
- `AuditService` : un événement publié dans une transaction en **lecture seule**
  (téléchargement, aperçu) est écrit dans sa propre transaction (sinon 500).
- Spécification OpenAPI : `NomsSchemasDistincts` — deux DTO homonymes (les `Ref`, les
  `Resultat`) ne sont plus fusionnés en un seul schéma ; dictionnaire complété pour E2, E3,
  E5 à E7.
- Workflow API de dev1 (bd83262, pas encore dans `conformite-technique`) : `AccesApiWorkflow`
  sera branché (`@Primary`) quand il y sera ; la portée prévoit déjà WORKFLOW_PILOTAGE et
  WORKFLOW_DECISION.

**Exploitation des tests en parallèle (pour pm)** : les suites de plusieurs membres lancées en
même temps se gênent — même port d'annuaire simulé (33390) et saturation de PostgreSQL
(`max_connections` = 100, contextes Spring de test en cache). Mes exécutions :
`GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT=33392 GED_IDENTITE_ANNUAIRE_URLS=ldap://localhost:33392
SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3`.

## Après vague 3 — notifications (E8, partie reprise de dev3) et OpenAPI (T-053) : **acceptés** (93cbc7d)

Références : matrice technique (MT) et fonctionnelle (MF), lignes comptées à partir de 0.

| Réf. | Exigence | Livré | Statut proposé |
|---|---|---|---|
| MT 8.18 / T-113 | §12.9 — boîte d'envoi, e-mail SMTP, pastille in-app | paquet `notification` : table `notification` écrite dans la transaction du déclencheur (API interne `Notifications.envoyer`), expédition asynchrone par Spring Mail (dès la validation de la transaction + relève 30 s, `FOR UPDATE SKIP LOCKED`, 3 tentatives espacées 1 puis 2 min, puis `ECHEC`), modèles français figés à la création, préférence e-mail (`preference_notification`, l'in-app subsiste), API `GET /api/v1/notifications` (+ `/compteur`, `/{id}/lecture`, `/lecture`, `/preferences`), audit `NOTIFICATION_ENVOYEE` / `NOTIFICATION_ECHEC` / `PREFERENCE_NOTIFICATION_MODIFIEE` sans adresse ni texte ; Angular : pastille dans la barre supérieure (relève 1 min) et écran « Notifications » | Identique pour le moteur ; relais SMTP de MMED **simulé** (GreenMail) |
| MF 7.10 | §4.6.6 — trois cas exclusivement | énumération fermée `TypeNotification` + contrainte `ck_notification_type` | Identique |
| MF 7.10 (accès) | attribution d'un accès à un espace, jamais le retrait | écoute de `HABILITATION_MODIFIEE` (contrat `EvenementAudit`, sans compiler contre dev1) : habilitation avec rôle sur un nœud (utilisateur ou membres du groupe) et membres ajoutés à un groupe (un avis par espace du groupe) ; retrait, document, rupture seule, auteur : rien | Identique dès la fusion d'E3 (dev1) ; éprouvé avec des événements de même forme |
| MF 6.5 / MF 7.3 | §4.5.3 notification des validateurs et du déposant ; alerte d'échéance aux Agents d'archive | contrat `EvenementNotifiable` (une méthode `notification()`) pour les événements de workflow (dev1, E8) et d'échéance (dev3, E8), destinataires nommés ou par rôle | **Proche** : moteur et modèles prêts, les événements déclencheurs n'existent pas encore |
| MT 4.13 / T-053 | §5.3 — spécification OpenAPI 3 complète | `documentationapi` : `OpenApiCustomizer` + dictionnaire `champs.yml` (72 schémas, tous les champs décrits avec exemple, paramètres et corps sans schéma nommé), erreurs problem+json par opération (codes et exemple chacune : 400, 401, 403, 404, 409, 413, 415, 422, 429, 500, 503 selon l'opération), sécurité (Bearer ou `X-API-Key`, connexion publique, routes réservées aux utilisateurs), en-têtes `Idempotency-Key`, `X-On-Behalf-Of`, `Retry-After`, `Idempotency-Replayed`, `Deprecation`/`Sunset`/`Link`, pagination 50/200 ; springdoc désactivé en profil prod (en plus du refus de `SecurityConfig`) | Identique |

**Points d'extension (à brancher à la fusion, dev1)** : `AnnuaireDestinataires`
(courriel lu dans `cache_annuaire`, membres des groupes, espaces d'un groupe,
porteurs d'un rôle) — l'implémentation transitoire `AnnuaireDestinatairesLocal`
lit `compte_utilisateur` et les groupes d'accès de cette branche ; `IdentiteDestinataire`
(`ActeurCourant::employeId` ici, `ActeurCourant::utilisateurId` après E2). Les deux
sont `@ConditionalOnMissingBean` : déclarer son bean suffit.

**Pour dev1 et dev3** : un DTO ajouté ou modifié doit être décrit dans
`backend/src/main/resources/documentationapi/champs.yml` ; sinon
`SpecificationOpenApiTest` échoue et liste les entrées à ajouter. Aucune annotation
dans les contrôleurs. Sous-arbre de configuration `ged.notification` repris de dev3
(INTEGRATION.md § 3) ; `spring.mail` dans `notification.yml`.

**Limites** : relais SMTP réel non éprouvé (simulateur) ; pas de rôle « Agent
d'archive » avant E3 (une alerte d'échéance sans destinataire est journalisée, rien
n'est envoyé au hasard) ; liens des e-mails vers les écrans actuels
(`televerser/<id>`, `espaces-de-travail/<id>`), à ajuster si les routes changent ;
écran vérifié par tests et construction, pas en navigateur (pas de backend dev lancé).

**Tests** : `DB_NAME=ged_dev2 mvn -q test` vert, **369 tests**, 0 échec ; nouveaux : `NotificationsTest` 10,
`ModelesNotificationTest` 3, `SpecificationOpenApiTest` 7 ; `SchemaLiquibaseTest`
et `ClesApiTest` étendus. Front : `ng test` vert (17 tests, dont `NotificationsService` 4), `ng build` vert.
Commits : b55c1cb (moteur), 1b096b3 (Angular), 1c029ec (OpenAPI).

## Vague 3 — lot E9 socle API : **acceptée** (8731e55)

Références de la matrice technique, section « Intégration et API (§5) », lignes
comptées à partir de 0 (4.x), avec l'article du DAT.

| Réf. | Exigence (DAT) | Livré | Statut proposé |
|---|---|---|---|
| 4.9 | 5.3.2 — Idempotency-Key obligatoire sur les créations | `FiltreIdempotence` générique (routes configurables : dépôt, version, rattachement, espace, dossier), table `idempotence_cle`, réservation avant exécution, empreinte requête (multipart indépendant du boundary) et réponse 2xx mémorisées 24 h par appelant (application ou utilisateur) ; rejeu → réponse initiale + `Idempotency-Replayed`, contenu différent → 422 `IDEMPOTENCE_CONFLIT`, traitement concurrent → 409 `IDEMPOTENCE_EN_COURS`, clé absente/invalide → 400 ; purge horaire. Front : intercepteur qui pose une clé UUID neuve sur chaque POST | Identique |
| 4.10 | 5.3.2 — pagination, plafond 200, liste blanche | existant vérifié (plafond 200, tri en liste blanche) ; taille par défaut portée de 10 à 50, alias `taille` accepté | Identique |
| 4.11 | 5.3.2 — taille par type, quotas par clé | quotas 600/min et 100 000/jour par clé (réglables par application), 429 + `Retry-After`, compteurs en mémoire persistés périodiquement (`cle_api.quota_jour_*`) ; métadonnées plafonnées à 64 Ko (413 `METADONNEES_TROP_VOLUMINEUSES`) | Identique **sous réserve** : compteurs par instance (voir limites) |
| 4.12 | 5.3.2 — version majeure dans l'URL | `/api/v1` inchangé ; en-têtes `Deprecation`, `Sunset` (≥ 12 mois) et `Link rel="successor-version"` émis par préfixe déclaré (`ged.api.conventions.depreciations`), prêts pour `/api/v2` | Identique |
| 4.14 | 5.4 — clés API | tables `application`, `cle_api` ; format `ged_<env>_<identifiant>_<secret>`, secret 256 bits montré une fois, empreinte SHA-256 (comparaison à temps constant) ; `X-API-Key` ; génération, consultation, expiration 12 mois, révocation motivée, régénération avec chevauchement de 7 jours, clés expirant sous 30 jours signalées ; adresses autorisées (IP ou CIDR) ; refus d'une clé d'un autre environnement ; événements d'audit pour chaque opération et chaque appel (`APPEL_API`, `CLE_API_REFUSEE`, `QUOTA_DEPASSE`, …) sans secret ; écran Angular « Clés d'API » | **Proche** : la portée (`cle_api_portee`) est modélisée mais branchée en vague 4 |
| 4.1 | 5.2 — applications soumises au même modèle | l'application est un sujet authentifié (`ApplicationAuthentifiee`, `ROLE_APPLICATION`), journalisée comme un utilisateur ; point d'extension `ControlePorteeApplication` (permissif) | **Proche** : calcul des droits = E3 (dev1), vague 4 |
| 4.15 | 5.5 — délégation X-On-Behalf-Of | modèle prêt (`cle_api.delegation`, délégation refusée sans liste d'adresses) ; `ResolveurIdentiteDeleguee` fermé par défaut (422 `IDENTITE_DELEGUEE_INVALIDE`) | Non (préparé, vague 4) |

D8 (workflow pilotable par API) : l'énumération `OperationApi` de la portée prévoit
`WORKFLOW_PILOTAGE` et `WORKFLOW_DECISION` ; rien d'autre n'est implémenté.

**Branchement sécurité (pour dev1)** : aucune modification de `SecurityConfig`.
Une chaîne dédiée `ConfigurationSecuriteApplications`
(`@Order(HIGHEST_PRECEDENCE + 10)`) ne s'applique qu'aux requêtes `/api/**` portant
`X-API-Key` ; les autres restent sur la chaîne utilisateur. Seule contrainte :
`SecurityConfig` ne doit pas déclarer d'ordre plus prioritaire que
`HIGHEST_PRECEDENCE + 10`. Une application n'a jamais accès à
`/api/v1/applications/**`, `/api/v1/cles-api/**`, `/api/v1/audit/**`,
`/api/v1/auth/**`. Points d'extension à remplacer en E3/vague 4 (beans
`@ConditionalOnMissingBean`) : `ControlePorteeApplication`,
`ResolveurIdentiteDeleguee`, `GardeAdministrationCles` (aujourd'hui : tout
utilisateur authentifié administre les clés ; à restreindre par permission).

**Alignement stockage (dev3)** : plus aucune mention de `ged.storage.*` ni de
`GED_STORAGE_TEMP` ; `DEPLOIEMENT.md`, `ged.env.exemple` et
`SondeReferentielFichiers` suivent `ged.fichiers.racine` / `GED_STOCKAGE_RACINE`.

**Limites** : quotas comptés par instance (plusieurs instances derrière NGINX
= quota multiplié ; un compteur partagé en base serait à arbitrer) ; 64 Ko contrôlé
sur `Content-Length` pour le JSON et sur les parties non fichier en multipart ;
corps de réponse mémorisé plafonné à 1 Mo (au-delà, le rejeu rend le statut et
`Location` sans corps, toujours sans doublon) ; toute création via l'API exige désormais la clé
d'idempotence, y compris pour les scripts et clients existants.

**Tests** : `DB_NAME=ged_dev2 mvn -q test` vert, **348 tests**, 0 échec
(nouveaux : `IdempotenceApiTest` 6, `ClesApiTest` 11, `QuotasEtFormatCleApiTest` 4,
`ConventionsApiTest` 4 ; `SchemaLiquibaseTest` étendu). Les tests MockMvc reçoivent
une clé d'idempotence par défaut (`CleIdempotenceParDefautDesTests`). Front :
`ng test` vert (13 tests), `ng build` vert. Instable observé une fois, passé au
rejeu : `PrevisualisationApiTest.apercuPdf` (`ConcurrentModificationException`
dans `HeaderWriterFilter`, périmètre dev3).

## Vague 2 — acceptée (7f7c5ab)

### Amorce M1 — contrat d'erreurs problem+json : **PRÊT** (commit 6d1d09e)

À fusionner dès maintenant (dev1 et dev3 en dépendent). Contrat pour les lots :

- Lever `com.ipt.ged.common.erreur.ExceptionMetier` ou une sous-classe, avec un code
  du catalogue du domaine : `RequeteInvalideException` (400), `NonAuthentifieException`
  (401), `AccesRefuseException` (403), `RessourceIntrouvableException` (404, libellé
  fixe : absent et hors périmètre indiscernables), `ConflitException` (409),
  `RegleMetierException` (422), `TropDeRequetesException` (429, `Retry-After`
  calculé), `ServiceIndisponibleException` (503). Codes génériques :
  `CodesErreur` ; codes de domaine dans le paquet du domaine (majuscules et
  soulignés, jamais modifiés une fois publiés). `avec(nom, valeur)` ajoute un
  membre d'extension.
- Réponse : `application/problem+json` avec `type` (`urn:ged:erreur:<code>`),
  `title`, `status`, `detail`, `instance`, `code`, `traceId`, et `erreurs` (par
  champ) pour les 400 de validation. `GlobalExceptionHandler` : **dev2 seul** ;
  personne n'a à le modifier pour un nouveau refus.
- `ErreurFichierException` (dev3) reste traitée telle quelle, codes conservés.
  Suggestion à dev3 : la faire hériter d'`ExceptionMetier` (une ligne).
- **dev1** : brancher `ReponsesSecuriteProblem` (bean) dans `SecurityConfig` —
  `.exceptionHandling(e -> e.authenticationEntryPoint(r).accessDeniedHandler(r))`
  — pour que les 401/403 de la chaîne de sécurité soient au même format ; lever
  `TropDeRequetesException` pour l'anti-force brute au lieu de
  `ResponseStatusException` (celle-ci reste traduite, mais sans `Retry-After`).
- Front : `core/probleme.ts` (`messageErreur`, `codeErreur`, `erreursParChamp`),
  intercepteur en tête de chaîne ; les écrans existants lisent encore
  `error.message`/`error.errors`, alias posés par l'intercepteur.
- Tests : `ContratErreursTest` (tous les statuts, format, Retry-After, 404
  indiscernable, validation, exceptions Spring, 401/403 de la chaîne) ; tests
  existants passés de `$.message`/`$.errors` à `$.detail`/`$.erreurs`.
  `mvn test` : 303 verts.
- **Reprise de `GestionErreursIdentite` (dev1)** : une fois l'amorce fusionnée, les
  exceptions de `identite/erreur/` héritent d'`ExceptionMetier` avec leurs codes
  (`IDENTIFIANTS_REFUSES` 401, `TROP_DE_TENTATIVES` → `TropDeRequetesException` 429 avec
  `Retry-After`, `ANNUAIRE_INDISPONIBLE` 503, `ENTETE_CSRF_MANQUANT` 403,
  `RENOUVELLEMENT_REFUSE` 401) et l'advice `GestionErreursIdentite` est supprimé :
  plus aucun format d'erreur parallèle.

### Lot E4 — journal d'audit : **LIVRÉ sur ct/dev2** (commits 1925eb6 à 9c0916e)

| Réf. matrice | Exigence (DAT) | Livré | Statut proposé |
|---|---|---|---|
| 4.7 | 5.3.2 — problem+json, code métier stable | `common/erreur` (`ExceptionMetier` et sous-classes, `CodesErreur`, `Problemes`), `GlobalExceptionHandler` en RFC 7807, 401/403 de la chaîne (`ReponsesSecuriteProblem`), front `core/probleme.ts` | Identique dès que dev1 branche `ReponsesSecuriteProblem` dans `SecurityConfig` |
| 4.8 | 5.3.2 — codes 403, 404 hors périmètre, 409, 413, 415, 422, 429 | tous couverts, `Retry-After` sur 429, 404 à libellé fixe | Identique pour le format ; le « hors périmètre » réel vient d'E3 |
| 6.2 | 7.4.1 — `journal_audit` (acteur, application, IP, action, objet, avant/après, résultat, trace_id) | table partitionnée par mois, identifiant séquentiel, catalogue `ActionAudit`, `AuditService`, contrat `EvenementAudit` pour les lots | Identique pour le mécanisme ; exhaustivité après branchement dev1/dev3 (ci-dessous) |
| 6.3 | 7.4.2 — INSERT seul, déclencheurs, scellement SHA-256 chaîné exporté | privilèges `ged_app` INSERT/SELECT, déclencheur `BEFORE UPDATE OR DELETE OR TRUNCATE` (mère et partitions), scellement horaire en base et hors base (fichier ajout seul + journal technique), vérification mensuelle et à la demande tracée, métrique et alertes | Identique |
| 6.4 | 7.4.3 — écran, export CSV/JSON, rétention 10 ans | API `/api/v1/audit` (lecture seule), écran Angular « Journal d'audit », export avec scellements et SHA-256, consultation et export tracés, partitions jamais supprimées automatiquement | Proche : garde provisoire « authentifié » (compte unique aujourd'hui) ; permission `CONSULTER_AUDIT` avec E3 |

Événements tracés à ce jour : opérations d'administration des référentiels (espaces :
création, modification, déplacement, archivage, corbeille, restauration ; types, index,
plans, circuits, étiquettes, groupes : création, modification avec avant/après,
corbeille, restauration, une trace par objet pour les opérations de masse), décisions de
validation (approbation, rejet, relance), indexation enregistrée (valeurs et nom
recomposé), refus de droits (403), consultation, export et vérification du journal.

**Branchement attendu des autres lots** (contrat `com.ipt.ged.audit.EvenementAudit`,
écouté par `EcouteurEvenementsAudit` ; aucune modification de leur service par dev2) :
- **dev3** — `EvenementDocument extends EvenementAudit` avec ces méthodes par défaut :
  `action()` = `type()`, `objetType()` = `"DOCUMENT"`, `objetId()` = `documentId()`,
  `acteurUtilisateurId()` = `acteur().employeId()`, `acteurApplicationId()` =
  `acteur().applicationId()` ; `MetadonneesModifiees` redéfinit `avant()`/`apres()`.
  Idem pour `FichierInfecte` (`FICHIER_INFECTE`, résultat `REFUS`) et
  `AnomalieIntegrite` (`INTEGRITE_ANOMALIE`, `ECHEC`). Brancher aussi
  `VerificationAntivirus` sur `AnalyseurAntivirus::disponible`.
- **dev1** — `ConnexionReussie` (`CONNEXION_REUSSIE`, objet `UTILISATEUR`,
  `acteurUtilisateurId` = `utilisateurId`, `adresseIp`), `ConnexionEchouee`
  (`CONNEXION_REFUSEE`, résultat `REFUS`, motif = motif d'échec, `acteurNom` =
  identifiant saisi), `SessionsRevoquees` (`SESSIONS_REVOQUEES`) implémentent
  `EvenementAudit`. Faire exposer par le principal E2 l'identifiant utilisateur
  utilisé par `ActeurCourant.employeId()` (aujourd'hui l'employé).
- **dev1 (E3)** — déclarer un bean `GardeConsultationAudit` fondé sur
  `CONSULTER_AUDIT` ; tracer `HABILITATION_MODIFIEE` et les 404 hors périmètre utiles.
- **dev1 (vague 2)** — `SchemaLiquibaseTest` modifié par dev2 (tables d'audit,
  partitions écartées, jalon `socle-e1` compté hors lots postérieurs) : union simple
  avec les tables d'identité.

Tests (tous sur PostgreSQL réel, `DB_NAME=ged_dev2`) : `JournalAuditInalterableTest`
(connecté en `ged_app` : UPDATE/DELETE/TRUNCATE refusés, SQLSTATE 42501 ; connecté en
`ged_owner` : déclencheur sur mère et partition ; succès annulé avec sa transaction,
refus conservé), `ScellementAuditTest` (chaîne, export, **altération par `ged_owner`
avec déclencheur désactivé → `EMPREINTE_DIFFERENTE`**, **scellement réécrit en base →
`EXPORT_DIFFERENT`**), `ConsultationAuditApiTest` (filtres, pagination, export CSV/JSON
et empreinte, injection CSV neutralisée, aucune écriture, consultation tracée),
`AuditOperationsApiTest`, `AuditDecisionsValidationTest`, `SchemaLiquibaseTest`
(conventions et retour arrière). **`mvn test` : 323 verts.** Front : 8 tests verts,
build vert ; écran vérifié dans le navigateur contre le back-end local.

Autres ajustements de la vague :
- `server.forward-headers-strategy: native` ; la valve Tomcat ne croit
  `X-Forwarded-For` que des proxys de `ged.journalisation.proxys-de-confiance`
  (même règle que le filtre MDC), au lieu de tous les réseaux privés.
- Sonde `annuaire` : celle de dev1 fait foi (même nom de bean : la mienne est retirée) ;
  exclue du groupe `readiness`, alerte dédiée `GedAnnuaireIndisponible`.
- Exploitation mise à jour pour E2 (`GED_LDAP_*`, `GED_JWT_KEYSTORE*`,
  `GED_ADMINISTRATEURS`, `GED_SESSION_DUREE_ABSOLUE`), sauvegarde de la clé RS256.
- **Pour dev3** : le contrôle « antivirus obligatoire en prod » de
  `fichier/ConfigurationFichiers` ne couvre pas le profil `uat` (le profil `uat` importe
  `prod` mais le contrôle teste le nom de profil) ; à étendre, fichier non modifié par dev2.

Reste pour dev2 : vérification du scellement sur la volumétrie réelle (5 millions
d'enregistrements par an : durée de la vérification mensuelle à mesurer en UAT) ;
branchement des événements dev1/dev3 à contrôler après leurs fusions.

## Vague 1 — livrée (fusion 95b11e1)

E0 (outillage), E4 partie journalisation technique (7.1, 7.3.1), E10 (exploitation).
Décisions de la revue technique intégrées : **D4** (un seul contrôleur de domaine,
sonde à N contrôleurs), **D6** (objectif de disponibilité en recherche : 24 h,
paramétrable), **D5** (aucun Python dans les livrables). Alignement sur le lot
stockage de dev3 (210 Mo par requête, clamd `StreamMaxLength 200M`, tmpfs des
fichiers en clair, `GED_KEYSTORE_*`).

## Exigences traitées (référence de la matrice : section.ligne)

| Réf. | Exigence (DAT) | Livré | Statut proposé |
|---|---|---|---|
| 5.14 | 6.2.3 A06 — OWASP Dependency-Check à chaque construction | plugin `dependency-check-maven` 12.2.2 lié à `verify`, échec à CVSS ≥ `ged.cvss.seuil` (7), job CI dédié, fichier de suppressions encadré | Identique **sous réserve** du secret `NVD_API_KEY` (sans clé, la base NVD répond 429 : analyse non exécutée sur le poste) |
| 7.4 | 8.3 — registre des dépendances, version et licence | SBOM CycloneDX Maven (`target/bom.json`, `bom.xml`) et npm (`npm run sbom`), registre `docs/DEPENDANCES.md` généré par `outils/registre-dependances.mjs`, licences classées au regard de la cession (11.2) | Identique |
| 7.6 | 9.2 — non-régression | `.github/workflows/ci.yml` : tests back sur `postgres:16`, tests front, à chaque push et demande de fusion | Identique (CI non encore exécutée sur la forge : aucun push autorisé) |
| 7.7 | 9.3 — UAT et déploiement par module | profil `uat` (importe `prod`, test d'équivalence), `deployer.sh --module back|front` | **Proche** : le déploiement « par processus métier » (DAT 9.3) supposerait des modules activables séparément ; la granularité livrée est back / front |
| 7.8 | 9.4 — branche protégée, accès MMED | procédure exacte `docs/exploitation/FORGE.md` (GitHub, GitLab, Gitea), remise Article 45 | **Proche** tant que pm n'a pas appliqué les réglages sur la forge (action externe) |
| 6.0 | 7.1 — pattern de l'Article 50, MDC | `FiltreContexteRequete` (ip via XFF de confiance, traceId/spanId W3C), `FiltreUtilisateurJournalisation` (username), `DecorateurTacheMdc`, `logback-spring.xml` au pattern exact | Identique |
| 6.1 | 7.3.1 — niveaux, rotation, rétention | INFO par défaut, niveau externalisé, rotation quotidienne et 100 Mo, gzip, 90 jours | Identique |
| 0.5 | 2.2 — front hébergé sur NGINX | `deploiement/nginx/ged.conf`, config.json d'environnement hors paquet | Identique (vérifié sur le papier) |
| 5.9 | 6.2.1 — TLS 1.2 min, LDAPS, base chiffrée | TLS 1.2/1.3 NGINX ; `sslmode=verify-full` dans le déploiement et la sauvegarde ; LDAPS dans la sonde | **Proche** : l'URL JDBC de l'application (lot E1) doit porter `sslmode`/`sslrootcert` ; LDAPS applicatif = E2 |
| 5.10 | 6.2.2 — NGINX durci | server_tokens, HSTS, CSP Angular (sans script en ligne), X-Frame-Options, nosniff, limitation de débit, HTTPS forcé | Identique (vérifié sur le papier ; CSP éprouvée sous Chromium) |
| 5.17 | 6.5 — sauvegarde, RPO/RTO, restauration testée | scripts base (WAL, physique, logique), fichiers après la base, clés chiffrées à part, restauration pas à pas, rapprochement des orphelins, test à blanc exécuté | Identique (exercice UAT sur données réelles à planifier avant la mise en production) |
| 5.19 | 6.7 — Micrometer, Prometheus, alertes | `micrometer-registry-prometheus`, port de management interne, sondes base/référentiel/annuaire/antivirus/files, métriques GED, `alertes.yml` aux seuils du DAT | Identique (règles vérifiées sur le papier) |
| 7.11 | 10.1 — procédure scriptée | `deployer.sh` : sauvegarde, Liquibase validate/tag/update par ged_owner, arrêt progressif, sonde, test de fumée, retour arrière | Identique (non exécuté de bout en bout : pas de systemd sur le poste) |
| 7.12 | 10.2 — JAR en service système derrière NGINX | `ged-backend.service` durci, `EnvironmentFile` 0400, redémarrage automatique | Identique (vérifié sur le papier) |

Également couvert : 5.13 (6.2.3 A05, Actuator restreint au réseau interne) et 5.18
(6.7, sondes LDAP, ClamAV, file OCR) — finitions prévues en E11.

## Tests

`mvn -q test` : **vert**, 182 tests, 0 échec (ligne de base de la branche : 143 ; nouveaux :
`FiltreContexteRequeteTest` (MDC rempli et vidé, en-têtes forgés, XFF de confiance
ou non, dispatch d'erreur), `DecorateurTacheMdcTest`, `PatternJournalisationTest`
(pattern exact lu dans `logback-spring.xml`, format produit, rotation/rétention),
`SondesTest`, `SondeAnnuaireTest`, `SecuritePortManagementTest`,
`SupervisionIntegrationTest` (sondes, métriques Prometheus, port de management,
ordre des filtres, exécuteur asynchrone), `ProfilUatTest` — 39 tests).
Front : `ng test` vert (2 tests), `ng build` de production vert.

## Vérifié uniquement par simulateur ou sur le papier

- **ClamAV** : clamd simulé (protocole `zPING`/`PONG` réel).
- **Annuaire LDAP** : annuaire LDAP v3 simulé (BER, liaison anonyme, RootDSE), client
  JNDI réel ; un, deux, aucun contrôleur joignable.
- **NGINX** : non installé, configuration relue ; CSP servie par un serveur local
  aux mêmes en-têtes, paquet compilé chargé sous Chromium sans violation.
- **Prometheus / Alertmanager** : non installés, syntaxe YAML contrôlée, PromQL relu.
- **OWASP Dependency-Check** : configuration acceptée par le plugin, analyse
  bloquée par l'absence de clé NVD (HTTP 429).
- **systemd, deployer.sh** : syntaxe contrôlée ; appels du test de fumée rejoués à
  la main contre le back-end local (port 18082, management 18092 : santé et
  Prometheus servis sur le port de management, 401/404 sur le port de l'API,
  traceId propagé, username et ip dans les journaux).
- **Sauvegarde / restauration** : exécutées réellement (compte rendu dans
  `docs/exploitation/RESTAURATION.md`), sur jeu d'essai synthétique ; archivage WAL
  et restauration à un instant donné sur une instance PostgreSQL jetable.

## Données laissées en place (essais)

- Base `ged_dev2`, schéma `essai_restauration` (jeu d'essai du test à blanc) et base
  `ged_dev2_restauration` (cible de restauration, recréée à chaque exercice).
- Base H2 locale du profil dev (`backend/data`, non versionnée) : deux documents
  « FUMEE-test-… » déposés puis mis à la corbeille lors de l'essai du test de fumée.

## Points pour les autres membres (à arbitrer par pm)

- **pm — pom.xml** : en plus de `<build><plugins>` et de la dépendance Prometheus,
  une propriété `<ged.cvss.seuil>7</ged.cvss.seuil>` a été ajoutée dans
  `<properties>` : sans elle, le seuil ne peut pas être à la fois une valeur par
  défaut et surchargeable (`-Dged.cvss.seuil=`). Dépendance Prometheus placée après
  `spring-boot-starter-validation`, hors des zones modifiées par dev1.
- **pm — application.yml** : une ligne `spring.config.import: classpath:exploitation.yml`
  et remplacement du bloc `management` final par un renvoi vers `exploitation.yml`.
- **pm — forge** : créer le secret `NVD_API_KEY`, appliquer `FORGE.md`.
- **dev1 (E1)** : ajouter `sslmode`/`sslrootcert` à l'URL JDBC (6.2.1) ; en UAT/PROD
  `SPRING_LIQUIBASE_ENABLED=false` (la migration passe par `deployer.sh` avec
  `ged_owner`, l'application n'a plus besoin de ses identifiants) ; vérifier en UAT
  que `liquibase status` via le CLI ne voit aucun changeset en attente sur une base
  migrée par l'application (mêmes chemins de changelog).
- **dev1 (E2)** : les contrôles « fail-closed » propres au profil `prod`
  (`ServiceJeton` : clé obligatoire ; `SecurityConfig` : Swagger fermé) ne
  s'appliquent pas au profil `uat` ; à étendre à `uat` avec l'authentification
  RS256. `SecurityConfig` ouvre encore `/actuator/health` sur le port de l'API :
  sans effet (Actuator n'y est plus servi), à nettoyer. Sonde annuaire : lit
  `spring.ldap.urls` par défaut.
- **dev3 (E5/E6)** : brancher `VerificationAntivirus` sur `AnalyseurAntivirus::disponible` ;
  déclarer une `FileDeTraitement` pour `ocr_job` ; publier le délai OCR sous le
  Timer `ged.ocr.delai.disponibilite` (histogramme déjà configuré, objectif 24 h).
- **Registre des dépendances** : `mysql-connector-j` (GPL-2.0 avec exception FOSS)
  est signalé ; il disparaît avec E1. Après chaque fusion modifiant les
  dépendances, régénérer `docs/DEPENDANCES.md` (`mvn package`, `npm run sbom`,
  `node outils/registre-dependances.mjs`).

## Reste à faire

- Exécuter la CI sur la forge (après push par pm) et l'analyse OWASP avec la clé NVD.
- Premier passage réel de `deployer.sh` en DEV Linux, puis rollback Liquibase testé
  en UAT (DAT 10.1).
- `nginx -t`, contrôle TLS externe, `promtool check rules` à l'installation.
- Exercice de restauration en UAT sur données réelles (RTO chronométré).
- 7.7 : décider avec MMED de la granularité du « déploiement par processus métier ».
