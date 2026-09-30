# Résultats de recette — vague 8 (lignes « Livré » et parties non exercées des lignes « Vérifié »)

Exécutés par qa le 30/09/2026 sur `ct/qa-r1`, créée depuis `claude/inspiring-lovelace-10bg1c` (**71bdc1d**).
Les corrections en cours chez les développeurs ne sont **pas** intégrées : leur revérification
(anomalies « Corrigée ») est reportée au tour suivant. Aucun code applicatif modifié.

Nouveauté de cette vague : le poste est un Linux (conteneur) sur lequel qa a **installé les
composants réels** qui n'étaient jusqu'ici vérifiés que sur papier ou par simulateur. Chaque
résultat dit ce qui a été éprouvé **en réel sur ce poste** et ce qui reste propre à
l'infrastructure de MMED (§8).

## 1. Environnement

| Élément | Ce poste | Réel / simulé |
|---|---|---|
| Application | JAR construit sur `ct/qa-r1`, profil dev, API 18084, management 18094, bases `ged_qa` / `ged_qa_test` | réel |
| ClamAV | **clamd 1.5.4** (paquet Ubuntu), configuration d'`EXPLOITATION.md` §2 (`StreamMaxLength 200M`…), port 13394 | **réel** ; signatures : base locale d'une seule signature EICAR (`freshclam` échoue à travers le mandataire du poste) |
| LibreOffice | `soffice` 24.x du poste (aperçu DOCX, conversion PDF/A) | **réel** |
| veraPDF | bibliothèque embarquée | réel |
| Tesseract | 5.x, modèles `fra`, `ara` de `backend/tessdata` | réel |
| NGINX | **1.24.0**, `deploiement/nginx/ged.conf` livré (seules les valeurs « À ADAPTER » et les ports changent, diff archivé) | **réel** ; certificat auto-signé au lieu de celui de MMED |
| Prometheus / Alertmanager | **Prometheus 2.45.3**, **Alertmanager 0.26** ; `alertes.yml`, `prometheus.exemple.yml`, `alertmanager.exemple.yml` livrés (cible et relais adaptés) | **réels** ; relais SMTP simulé (GreenMail, `recette/lib/SmtpSimule.java`) |
| Annuaire | simulateur UnboundID : embarqué (instances A et B) ; **trois contrôleurs autonomes** arrêtables (`recette/e10/AnnuaireAutonome.java`, instance C) | simulé |
| PostgreSQL | instance partagée (bases de qa) ; **instance PostgreSQL 16 privée et jetable** (127.0.0.1:55494, `/tmp/qa-pg-prive`) pour les recettes qui créent et détruisent des bases (T-073, P-13, T-092) : rien n'a touché l'instance partagée hors `ged_qa*` | réel |
| systemd | `systemd-analyze verify` 255 sur les unités livrées (racine jetable) | réel (pas de démarrage de service) |
| Node | 22.23.3 téléchargé (SHA-256 vérifié) : Angular CLI 22 refuse le Node du poste (22.22.2 < 22.22.3) | — |

Trois instances successives : **A** (nominale ; intégrité toutes les 3 min, vérification du
scellement toutes les 4 min, scellement chaque minute, reprises OCR 10 s / 20 s / 30 s, langue OCR
`fra` pour le type `QAV8FRA` et `ara` pour `QAV8ARA`, dépréciation de démonstration sur
`/api/v1/etiquettes`) ; **B** (modules OCR, workflow, export, intégration inactifs) ; **C**
(trois contrôleurs d'annuaire dont un muet, délai OCR de 2 s par page). Toutes arrêtées en fin de
vague.

## 2. Suite automatisée

597 tests, 4 échecs de référence + 1 échec d'environnement (détail au §7).

## 3. Lignes « Livré »

| Ligne | Recette | Résultat | Verdict |
|---|---|---|---|
| T-006 NGINX front | `e10/verifier-front-nginx.sh` 23/23 ; **`e10/verifier-nginx-reel.sh`** N01, N05 | Paquet Angular construit, servi par un **vrai NGINX** ; `index.html` sans cache, fichiers compilés 30 j + gzip ; `config.json` d'environnement servi à la place de celui du paquet ; chemin inconnu, `.git`, `.env`, `/actuator` : 404 | **Vérifié (réel)** ; réserve : `listen [::]` empêche NGINX de démarrer sur un hôte sans IPv6 (observation O1) |
| T-066 NGINX durci | `verifier-nginx-reel.sh` N02–N10 | 301 vers HTTPS, `Server: nginx` sans version ; **TLS 1.0/1.1 refusés, 1.2/1.3 acceptés** (openssl) ; HSTS, nosniff, XFO, CSP, Referrer-Policy une seule fois sur page, API et erreurs ; `limit_req` : 1 + 5 puis 429 ; **211 Mo : 413 NGINX** ; **150 Mo déposés sans aucun fichier dans `client_body_temp`** (`proxy_request_buffering off`) ; adresse du client (127.0.0.2) enregistrée par le back-end, `X-Forwarded-For` forgé écrasé (ANO-E2-001 confirmée en réel) ; `traceparent` NGINX = `trace_id` de l'audit | **Vérifié (réel)** sauf N08b : fichier de 205 Mo → **500** du back-end → **ANO-E5-004** |
| T-009 briques | E5 antivirus, E7 cycle de vie, T-075 | **Tika** (type réel), **clamd réel** (EICAR 422 `FICHIER_INFECTE` détecté par clamd, EICAR sous `.pdf` 422, clamd arrêté → 503 `ANTIVIRUS_INDISPONIBLE`), **LibreOffice réel** (aperçu DOCX 200 puis cache ; archivage DOCX → PDF/A-2B méthode `LIBREOFFICE` validé **VALIDE par veraPDF**), keystore PKCS#12, **Prometheus réel** | **Vérifié (réel)** ; reste MMED : signatures ClamAV officielles et `freshclam`, KMS éventuel |
| T-028 protocole OCR | `e10/banc-ocr-reduit.sh` (2 pages par cellule, `ara+fra`) | CER fr 1,41 %, ar 2,63 %, ar dégradé 7,25 % ; débit 10,4 pages/min/cœur (seuil ≥ 6) | **Vérifié** sur corpus généré ; l'échantillon MMED (Q09) et QR8 restent ouverts : pas « Identique » |
| T-034 OCR, délai et reprises | instance C (2 s par page) + `RecetteComplementsV8` T034-01 | Scan de 2 pages : tentative 1 `DELAI_DEPASSE : page non reconnue en 2 s`, reprise à +10 s, tentative 2 idem, reprise à +20 s, tentative 3 réussie. PDF tronqué : `OCR_ECHEC` **immédiat** (1 tentative, motif `FICHIER_CORROMPU…`), document téléchargeable, « non interrogeable » | **Vérifié** ; AVERT : l'échec définitif sans reprise d'un fichier corrompu est un choix (O6) ; le passage en `OCR_ECHEC` après la 3e reprise d'un échec transitoire n'a pas été observé (3e tentative réussie) |
| T-050 pagination | `RecetteExploitation` T050-01 + E3-05 | 50 par défaut, plafond 200, tri en liste blanche, total au périmètre | **Vérifié** |
| T-069 Actuator | T069-01, N05 | Absent du port de l'API (401/404), management n'expose que `health` et `prometheus`, NGINX renvoie 404 | **Vérifié (réel)** |
| T-070 Dependency-Check | `e10/dependency-check-hors-ligne.sh` | seuil CVSS 7 lié à `verify` ; analyse non exécutée (base NVD : `NVD_API_KEY` absente) | **Reste « Livré »** : exécution en CI avec la clé NVD |
| T-073 sauvegarde et restauration | **`e10-sauvegarde/recette-t073-p13.sh`** sur PostgreSQL privé : 33 OK, 3 ÉCHEC | Ordre base → fichiers → clés imposé, clés GPG sur support distinct, destruction puis **restauration complète** : 62 tables identiques (md5), keystore et secrets identiques, 204 fichiers conformes, RTO 2 s pour 207 fichiers. ÉCHEC : droits de niveau base perdus (T073-39 → **ANO-E10-006**) ; mais surtout **les scripts livrés ne sont pas exécutables** (mode 100644) : la recette n'a pu tourner qu'après `chmod +x` local → **ANO-E10-003** | **Non conforme** (ANO-E10-003, ANO-E10-006) |
| T-074 sondes | T074-01, arrêt réel de clamd, instance C | Sondes `db`, `annuaire`, `referentielFichiers`, `antivirus`, `filesTraitement` ; clamd arrêté → `antivirus` DOWN, readiness DOWN ; annuaire : DEGRADE avec un contrôleur muet, DOWN tous arrêtés, hors readiness | **Vérifié (réel)** ; O2 : `/actuator/health` global DOWN quand le relais SMTP manque |
| T-075 métriques et alertes | T075-01/02 ; **Prometheus + Alertmanager réels** | `promtool check rules` : 12 règles ; alertes **réellement déclenchées** : `GedSondeIndisponible{antivirus}` (clamd arrêté, 2 min), `GedReferentielFichiers80` (disque du poste à 89 %), `GedTauxErreurs5xx` ; **courriels reçus** par le relais simulé (« [GED dev] GedTauxErreurs5xx (firing) », « (resolved) », « GedReferentielFichiers80 (firing) »). Manquent : appels par clé d'API, échéance du secret du compte de service | **Non conforme** : **ANO-E10-002** |
| T-077 journaux | `e10/RecetteRotationJournaux.java` 11/11 | `logback-spring.xml` livré : INFO en prod, DEBUG par variable (même artefact), rotation à la taille (gzip valides), rotation quotidienne, purge à 90 jours | **Vérifié** |
| T-085 SBOM | `e10/sbom-et-licences-hors-ligne.sh` (SBOM produits **en ligne** puis contrôlés) | Maven : 139 composants = résolution runtime, version et licence partout ; npm : 18, idem ; **Tesseract et modèles absents du SBOM** | **Non conforme** : **ANO-E0-002** |
| T-087 non-régression | §7 | suite complète ; CI jamais exécutée (aucune forge) | voir §7 |
| T-088 modules | **`e10/verifier-modules.sh`** (instance B) 5/6 | État publié, routes 404 `MODULE_INACTIF` (même sans authentification, clé d'API refusée), socle intact, aucun job OCR, métrique `ged_module_actif` ; **dépôt sous règle de workflow avec le module inactif : circuit ouvert, document inactif, plus aucune route pour le valider** | **Non conforme** : **ANO-E10-008** |
| T-092 déploiement scripté | **`e10-sauvegarde/recette-t092.sh`** 8 OK / 3 ÉCHEC ; `test-fumee.sh` livré contre l'instance réelle | Garde-fous, `validate` avant `update`, migration par `ged_owner`, bascule, témoin v3 + retour arrière OK. **`test-fumee.sh` envoie `email` au lieu d'`identifiant` : 400 contre la vraie application** → chaque déploiement repart en arrière, `--verifier` échoue toujours ; `--retour-arriere --base` après un retour automatique ne défait pas le changeset | **Non conforme** : **ANO-E10-004** (bloquante), **ANO-E10-005** |
| T-093 service systemd | `systemd-analyze verify` (racine jetable) | `ged-backend.service` accepté ; `ged-sauvegarde.service` : « **is not executable: Permission denied** » avec le script tel que le dépôt le livre, accepté après `chmod +x` | **Non conforme** : ANO-E10-003 ; exécution réelle en UAT |
| T-115 dépôt en deux temps | T115-01/02 | `SANS_PLAN`, `INDEXE`, `A_INDEXER` puis reprise → `INDEXE` ; 202 ; rejeu Idempotency-Key sans doublon | **Vérifié** (réserve de la vague 3 levée) |
| P-02 annuaire | **`e10/verifier-annuaire-bascule.sh`** (instance C, 3 contrôleurs) | Premier contrôleur arrêté (port fermé) : bascule en 50–80 ms ; deuxième arrêté : bascule vers le troisième ; tous arrêtés : 503 sans mode dégradé, **session ouverte conservée** (jeton et renouvellement 200), sonde DOWN hors readiness ; contrôleur relancé : connexions rétablies sans redémarrage ; métrique par contrôleur. **Contrôleur muet (accepte TCP, ne répond pas) en tête : 1 connexion sur 8 réussit, les autres 503 au bout de 5 s** alors que la sonde voit deux contrôleurs UP | **Non conforme** : **ANO-E2-002** ; « expiration du secret surveillée » absente (ANO-E10-002) |
| P-04 cycle de vie de l'identité | P04-01 ; P04-02 | Compte jamais connecté : identité sans rôle, tout sauf `/auth/me` en 403 ; compte désactivé dans l'annuaire : 401 ; réactivé : 200 avec les mêmes rôles | **Vérifié** (annuaire simulé) |
| P-07 limites | P07-01 | 64 Ko : 413 `METADONNEES_TROP_VOLUMINEUSES` (multipart et JSON), 60 Ko acceptés ; 200/201/202/204 | **Vérifié** ; le filtre de P-07 est la cause d'ANO-E5-004 |
| P-08 versionnement | P08-01/02 | `Deprecation: @…`, `Sunset`, `Link rel="successor-version"` sur le seul préfixe annoncé ; champs inconnus ignorés ; politique dans l'OpenAPI | **Vérifié** |
| P-13 rapprochement | `recette-t073-p13.sh` P13-01…12 | Orphelins en quarantaine, `A_REIMPORTER` au rapport, idempotent, âge de 7 jours. ÉCHEC : purge sans recontrôle d'un fichier redevenu référencé ; faux positif sur le cache d'aperçus ; fichier en cours de dépôt mis en quarantaine | **Non conforme** : **ANO-E10-007** |
| P-14 dimensionnement | relecture d'`ESSAIS-DE-CHARGE.md` + banc réduit | Débit mesuré ici (≈ 6 s/page/cœur) cohérent avec le rapport ; écarts de 3 à 6 fois sur §4.3.4/§6.6 et risques R30–R32 déjà écrits | **Vérifié sur le papier** ; décision MMED attendue |
| P-19 licences | P-19.* | Contrôle `--verifier` : GPL-3.0 et AGPL-3.0 non arbitrées refusées ; **accepte `mysql-connector-j`** (arbitrage « ne pas livrer ») et **tout composant du groupe `org.verapdf`** même déclaré GPL-3.0-only | **Non conforme** : **ANO-E0-003** |

## 4. Parties non exercées des lignes « Vérifié »

| Ligne | Partie non exercée | Recette | Résultat |
|---|---|---|---|
| T-018 | Expiration du cache annuaire à 15 min | T018-01 | `expire_le − lu_le` = 900 s ; attribut `department` changé dans l'annuaire : valeur gardée tant que le cache est valide, relue à expiration, remise ensuite. **Exercé** (horloge simulée par la base) |
| T-031 | Langue réglée par type | T031-01/02 | Même scan arabe : `fra` (QAV8FRA), `ara` (QAV8ARA), `ara+fra` (défaut) ; témoin `زركولين` trouvé sous `ara` et `ara+fra`. **Exercé.** O5 : réglage en configuration (redémarrage), pas à l'écran |
| T-039 | Réindexation complète | T039-01 | Tiers 403 ; Administrateur 202, `TERMINEE` 30/30 en 73 s ; 214 recherches pendant, toutes 200 ; résultats identiques (22 → 22). **Exercé.** O4 : progression à 0 jusqu'à la fin du lot |
| T-059 | Tâche mensuelle ; vérification à la demande | cron raccourci à 3 min, octet du chiffré modifié | Passe suivante : `{CONFORME=22, ALTERE=1}`, audit `INTEGRITE_ANOMALIE` ; fichier restauré. **Aucune alerte de supervision, aucune commande à la demande** → **ANO-E5-005** |
| T-064 | Bureautique réelle ; audit de l'aperçu | E7-21 ; audit | DOCX converti par **LibreOffice réel**, cache chiffré (`verifier-aucun-clair.sh --racine-cache` : 27 fichiers, 8/8) ; événement distinct `APERCU_CONSULTE`. **Exercé** |
| T-079 | Vérification planifiée du scellement | cron raccourci | Passe planifiée : 1 période, 303 enregistrements, 0 anomalie, `AUDIT_VERIFIE` audité, `ged_audit_anomalies` = 0. **Nominal exercé** ; le cas « ligne modifiée » par la tâche planifiée n'a pas été rejoué (la modification directe d'une ligne scellée a été refusée par l'outil de sécurité du poste) : reste couvert par `verifier-scellement.sh` (vague 4, S01–S06) |
| T-097 | Export d'un document rattaché | T097-01 | Présent **une fois** dans le ZIP ; manifeste : `Comptabilité / Comptabilité/2026`. **Exercé** |
| P-09 | Relance manuelle | P09-01 | Liste des échecs et relance réservées (tiers 403) ; relance 202 → `EN_ATTENTE_OCR`, tentatives remises à 0. **Exercé** |
| P-22 | Audit de la modification des droits | P22-01 | `HABILITATION_MODIFIEE` : pose (après seul), retrait (avant seul), acteur Administrateur. **Exercé** |
| E2 réel | Adresse du client derrière NGINX (ANO-E2-001) | N06 | 127.0.0.2 enregistrée, `X-Forwarded-For` forgé écrasé. **Exercé en réel** |

Non repris dans cette vague : P-05 (`SEQUENCES.md`), P-10 et P-16 (UAT), T-035 (priorité du
flux courant, développement attendu), T-101 / P-21 / R-03 / T-022 / T-110 (anomalies ouvertes,
revérification au tour suivant).

## 5. Régressions et autres constats sur les recettes existantes

- E3 autorisation : **28/28** ; E7 cycle de vie : **23/23** (LibreOffice réel) ; E5 : antivirus
  5/5 avec clamd réel (V01–V05), aucun fichier en clair 8/8 ; fumée qa 6/6.
- **E5-T01 régresse** : 200 Mio + 1 octet → 500 (vert en vague 2) → ANO-E5-004.
- **Recherche plein texte** : un document texte de 150 Mo (déposé par N09) rend **toute recherche
  qui le touche** impossible (500, `invalid memory alloc request size 1610612736`) → **ANO-E6-001**.
  Document mis en corbeille pour la suite de la recette.

## 6. Anomalies nouvelles

| Id | Membre | Gravité | Résumé |
|---|---|---|---|
| ANO-E10-004 | dev2 | Bloquante | `test-fumee.sh` se connecte avec `email` au lieu d'`identifiant` (D2) : 400 ; tout déploiement par `deployer.sh` repart en arrière, `--verifier` échoue toujours |
| ANO-E2-002 | dev1 | Majeure | Pas de bascule quand le premier contrôleur accepte TCP sans répondre : 7 connexions sur 8 en 503 alors que deux contrôleurs sont UP (D4) |
| ANO-E5-004 | dev2 | Majeure | Fichier de 200 à 210 Mo : 500 au lieu de 413 `FICHIER_TROP_VOLUMINEUX` (filtre P-07 hors gestionnaire d'erreurs) ; régression d'E5-T01 |
| ANO-E6-001 | dev3 | Majeure | Un texte extrait volumineux (150 Mo) fait échouer en 500 toute recherche plein texte qui le rencontre (`ts_headline` sur le texte entier) |
| ANO-E10-003 | dev2 | Majeure | Scripts de `deploiement/` non exécutables dans le dépôt : sauvegarde planifiée, `archive_command` (WAL) et appels internes échouent (« Permission denied ») |
| ANO-E10-005 | dev2 | Majeure | `deployer.sh --retour-arriere --base` après un retour automatique : réussite annoncée, changeset de la version en échec non défait |
| ANO-E10-007 | dev2 | Majeure | `rapprocher-orphelins.sh` : purge sans recontrôle (perte d'un fichier redevenu référencé), aucune garde « application arrêtée », faux positif sur le cache d'aperçus |
| ANO-E10-008 | dev2 | Majeure | Module workflow inactif : un dépôt sous règle ouvre un circuit impossible à traiter, document bloqué |
| ANO-E0-002 | dev2 | Mineure | SBOM sans Tesseract ni modèles `tessdata` (versions non consignées, `ara` absent du registre) |
| ANO-E0-003 | dev2 | Mineure | Le contrôle des licences accepte un composant arbitré « ne pas livrer » et tout composant d'un groupe arbitré quelle que soit sa licence |
| ANO-E5-005 | dev3 | Mineure | Divergence d'intégrité détectée par la tâche périodique : audit seul, ni alerte de supervision ni commande à la demande |
| ANO-E10-002 | dev2 | Mineure | Indicateurs du §6.7 manquants : appels par clé d'API, échéance du secret du compte de service |
| ANO-E10-006 | dev2 | Mineure | Restauration logique : droits de niveau base et réglages de rôle perdus (CONNECT/TEMP rendus à PUBLIC) |

Détail et reproduction : `ANOMALIES.md`.

## 7. Non-régression (`mvn test`)

`mvn -B -o test` sur `ged_qa_test` (instances arrêtées) : **597 tests, 5 échecs**, 183 s.

- Les 4 échecs de référence connus : `ArchivageApiTest.conversionEnEchec` et
  `ApercuTelechargementApiTest.apercuBureautiqueSansLibreOffice` (LibreOffice réellement présent
  sur le poste), `WorkflowApiTest.employesWithAccount`, `WorkSpaceApiTest.moveIntoDescendant`.
- 5e : `SupervisionIntegrationTest.portDeManagement` attend le port par défaut 8081 et lit
  `GED_MANAGEMENT_PORT` (18094, exporté par `equipe-env.sh`). Rejouée sans cette variable :
  6/6. Échec d'environnement, pas une régression ; le test dépend de l'environnement du poste.
- Aucune régression introduite (la branche ne contient aucun code applicatif).

T-087 : la suite tourne ; la CI (forge) n'a jamais été exécutée : reste « Livré ».

## 8. Ce qui a été éprouvé en réel, ce qui reste à MMED

**Éprouvé en réel sur ce poste** : NGINX 1.24 avec la configuration livrée (TLS, en-têtes, débit,
tailles, tampon disque, adresse du client, traçabilité) ; clamd 1.5.4 (INSTREAM, échec fermé,
sonde, alerte) ; LibreOffice (aperçu et conversion PDF/A validée par veraPDF) ; Prometheus et
Alertmanager (règles chargées, alertes déclenchées et courriels émis) ; `systemd-analyze verify`
des unités ; sauvegarde, destruction et restauration complètes avec les scripts livrés sur une
instance PostgreSQL 16 ; `deployer.sh` de bout en bout (sans systemd) ; construction du paquet
Angular ; SBOM Maven et npm ; banc OCR Java.

**Reste propre à l'infrastructure de MMED (UAT)** : certificat et chaîne de MMED, OCSP ; AD réel
(LDAPS 636, truststore, délai de connexion de 3 s — un hôte injoignable n'a pas pu être simulé ici,
seul un hôte muet l'a été) ; signatures ClamAV officielles et `freshclam` ; relais SMTP de MMED ;
node_exporter et blackbox_exporter (alertes disque serveur et certificat) ; systemd réel
(démarrage, redémarrage forcé, filtre de sorties, tmpfs) ; LUKS, pgaudit ; archivage WAL et PITR
sur les serveurs ; forge et CI (Dependency-Check avec clé NVD) ; échantillon OCR de MMED.

## 9. Observations (sans anomalie)

- O1 : `ged.conf` écoute aussi en `[::]` ; sur un hôte sans IPv6, NGINX refuse de démarrer (errno 97). À signaler dans `EXPLOITATION.md` §3.
- O2 : `/actuator/health` global est DOWN (503) dès que le relais SMTP manque (sonde `mail` de Spring) ; `readiness` et `ged_sante` ne sont pas touchés. Un répartiteur doit viser `readiness` (c'est ce que dit `EXPLOITATION.md`).
- O3 : un type documentaire accepte `tailleMaxMo` = 210, au-dessus du plafond de plateforme (200).
- O4 : progression de la réindexation complète à 0 jusqu'à la fin d'un lot de 500.
- O5 : la langue OCR par type est un réglage de configuration indexé par code de type (redémarrage), pas un champ du type.
- O6 : un PDF corrompu passe en `OCR_ECHEC` sans reprise (définitif) ; les reprises ne jouent que pour les échecs transitoires. À confirmer par la revue (le V3 dit « 3 tentatives »).
- O7 : la copie PDF/A d'un PDF texte (`pdf_texte_fr_convention.pdf`) est produite par **rastérisation** (normalisation refusée par veraPDF) : la copie de conservation n'a plus de couche texte (question Q7).
- O8 : aucune règle n'alerte si node_exporter ou blackbox_exporter cessent de répondre ; l'alerte d'expiration du certificat devient alors muette.
- O9 : outillage qa réparé pour Linux (`verifier-modules.sh` : apostrophe dans `${…:?…}` qui avalait le script, colonne `noeud_id`, `DONNEES` ; `recette-t092.sh` et bouchons : `cygpath` facultatif ; ports PostgreSQL paramétrables ; bits d'exécution des bouchons).

Traces locales (hors dépôt) : `qa/v8/*.txt`, journaux des instances A, B, C, de clamd, NGINX,
Prometheus, Alertmanager, courriels reçus (`qa/smtp/*.eml`).
