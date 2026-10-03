# Vulnérabilités des dépendances — analyse, modes d'exécution, rapports

Référence : DAT §6.2.3 A06 (composants vulnérables : OWASP Dependency-Check à chaque
construction), §8.3 (registre des dépendances, `docs/DEPENDANCES.md`), §10.4 (garantie,
`docs/exploitation/GARANTIE.md` §4 bis). Lignes de suivi : T-070, T-085.

## 1. Règle

| Point | Règle |
|---|---|
| Java (back-end) | OWASP Dependency-Check (`dependency-check-maven` 12.2.2, `backend/pom.xml`), lié à `mvn verify` ; **échec du build à CVSS ≥ 7** (`ged.cvss.seuil`) |
| npm (front-end livré) | `npm audit --omit=dev --audit-level=high` : échec sur sévérité haute ou critique (CVSS ≥ 7), même seuil que le back-end |
| Rapports | HTML et JSON de Dependency-Check, JSON de `npm audit` : artefacts de la CI conservés 365 jours (`rapport-dependency-check`, `rapport-npm-audit`) |
| Faux positif | suppression dans `backend/dependency-check-suppressions.xml` : CVE exacte, raison, date de réexamen (`until`, 6 mois au plus) ; jamais de motif large |
| Vulnérabilité réelle | corrigée par montée de version : **30 jours au plus si critique** ; haute : à la livraison corrective suivante ; moyenne et basse : à la montée de version planifiée suivante, sauf exposition avérée |

## 2. Source des données de vulnérabilités (NVD)

Dependency-Check construit sa base locale (H2) depuis la NVD. Trois modes, au choix de
l'environnement de construction :

| Mode | Réglage | Quand |
|---|---|---|
| **API NVD** (défaut) | secret `NVD_API_KEY` (clé gratuite du NIST) ; lu par `nvdApiKeyEnvironmentVariable` | construction avec accès Internet (CI GitHub) |
| **Miroir** | `-DnvdDatafeedUrl=https://<miroir>/nvdcve-{0}.json.gz` ; dans la CI : variable de dépôt `NVD_DATAFEED_URL` (prioritaire sur la clé) | réseau filtré : le miroir est la seule sortie à ouvrir |
| **Hors ligne** | `-DautoUpdate=false -DdataDirectory=<répertoire>` | poste ou serveur sans aucune sortie ; base copiée depuis une machine à jour |

**Constituer un miroir** (machine ayant accès à la NVD, une fois par jour) : l'outil
*Open Vulnerability CLI* (`vulnz`, projet de l'auteur de Dependency-Check) produit le
format attendu :

```bash
export NVD_API_KEY=...            # clé du NIST
vulnz cve --cache --directory /srv/miroir-nvd     # nvdcve-2002.json.gz … nvdcve-modified.json.gz, cache.properties
# servir /srv/miroir-nvd en HTTPS interne (NGINX), puis :
mvn -B verify -DnvdDatafeedUrl=https://miroir.interne/nvd/nvdcve-{0}.json.gz
```

Les autres sources en ligne de Dependency-Check se servent aussi depuis un miroir interne,
ou se coupent en le consignant : liste KEV de la CISA (`-DknownExploitedUrl=` ou
`-DknownExploitedEnabled=false`), suppressions hébergées (`-DhostedSuppressionsUrl=` ou
`-DhostedSuppressionsEnabled=false`), analyseur Maven Central (`-DcentralAnalyzerEnabled=false`).
OSS Index est déjà coupé dans le pom (compte obligatoire).

**Mode hors ligne** : sur une machine à jour, `mvn org.owasp:dependency-check-maven:update-only
-DdataDirectory=/srv/odc` ; copier `/srv/odc` (fichier `odc.mv.db`) sur la machine isolée ; y lancer
`mvn verify -DautoUpdate=false -DdataDirectory=/srv/odc`. Une base de plus de 7 jours ne doit pas
servir à une livraison.

## 3. Preuve sur le poste de l'équipe (30/09/2026 ; front-end mis à jour le 03/10/2026)

Le poste sort par un mandataire filtrant : `services.nvd.nist.gov`, `nvd.nist.gov`, `www.cisa.gov`
et `github.com` (miroirs publics de la NVD) y sont **refusés** ; Maven Central est ouvert.
L'analyse réelle des dépendances Java ne peut donc pas tourner sur ce poste. Ce qui y est prouvé :

**Chaîne d'analyse, en mode miroir** — `bash outils/essai-dependency-check.sh` sert en local un
miroir au format NVD 2.0 contenant deux vulnérabilités **synthétiques** sur `tika-core`
(CVE-2099-0001, CVSS 9,8 ; CVE-2099-0002, CVSS 5,3 : ces identifiants n'existent pas), puis lance
le plugin avec la configuration du pom. Résultat : 11 contrôles sur 11 verts :

- téléchargement depuis le miroir, base H2 alimentée, 148 dépendances analysées, rapports HTML et JSON ;
- identification `pkg:maven/org.apache.tika/tika-core@3.2.3` → `cpe:2.3:a:apache:tika:3.2.3` ;
- **build en échec** : « CVSS score greater than or equal to '7.0' … tika-core-3.2.3.jar : CVE-2099-0001(9.8) » ;
- vulnérabilité CVSS 5,3 rapportée sans bloquer ;
- suppression datée de CVE-2099-0001 : build réussi, vulnérabilité tracée comme supprimée dans le rapport.

Cet essai tourne aussi dans le job CI « OWASP Dependency-Check (CVSS >= 7) », avant l'analyse
réelle : une régression de la configuration (seuil, rapports, suppressions) y est donc détectée même
sans données NVD.

**Front-end, analyse réelle (03/10/2026, après montée d'Angular en 22.2.1)** — `npm audit
--omit=dev --audit-level=high` (registre npm joignable, Node 24.21.0, npm 11) : **code 0, « found 0
vulnerabilities »** ; rapport brut : [`rapports/npm-audit-2026-10-03.json`](rapports/npm-audit-2026-10-03.json)
(414 paquets dans l'arbre, 20 livrés ; 0 critique, 0 haute, 0 moyenne, 0 basse).

Historique de l'écart (ANO-E0-004) :

| Date | Angular livré | Résultat de l'audit des paquets livrés | Rapport |
|---|---|---|---|
| 30/09 | 22.0.8 (cdk, material 22.0.6) | 0 haute, 7 moyennes ; job front vert | [`npm-audit-2026-09-30.json`](rapports/npm-audit-2026-09-30.json) |
| 01/10 – 03/10 | 22.0.8 | **1 haute** + 6 moyennes, code 1 : l'avis GHSA-ff3f-86qr-9cv3 fait échouer depuis le 01/10 l'étape « Audit des dépendances livrées » du job front | [`npm-audit-2026-10-03-avant-montee.json`](rapports/npm-audit-2026-10-03-avant-montee.json) |
| 03/10 | **22.2.1** (tous les paquets `@angular/*`, y compris cdk, material, build, cli, compiler-cli) | **0 vulnérabilité**, code 0 | [`npm-audit-2026-10-03.json`](rapports/npm-audit-2026-10-03.json) |

Avis corrigés par la montée :

| Avis | Paquets | Sévérité | Corrigé en | Exposition de la GED avant correction |
|---|---|---|---|---|
| [GHSA-ff3f-86qr-9cv3](https://github.com/advisories/GHSA-ff3f-86qr-9cv3) — déni de service du rendu serveur | `@angular/router` 22.0.8 | **haute** | 22.2.0 | **aucune** : pas de rendu serveur (`@angular/ssr` absent) |
| [GHSA-p297-fm68-3q8c](https://github.com/advisories/GHSA-p297-fm68-3q8c) — fuite d'information par contournement de `HttpTransferCache` avec `withRequestsMadeViaParent` | `@angular/common` 22.0.8 (et forms, router, platform-browser qui en dépendent) | moyenne (CVSS 4,0) | 22.1.1 | **aucune** : ni `HttpTransferCache`, ni `provideClientHydration` dans `frontend/src` |
| [GHSA-hh8m-fm6v-7cvg](https://github.com/advisories/GHSA-hh8m-fm6v-7cvg) — contournement de l'assainissement par les liaisons d'hôte des directives | `@angular/core`, `@angular/compiler` 22.0.8 (et animations) | moyenne | 22.1.0 | **aucune constatée** : aucune liaison `host:` ni `@HostBinding` dans `frontend/src` |

Vérifications de la montée : `package-lock.json` régénéré par npm (`npm uninstall` puis `npm install`
des paquets Angular, la résolution incrémentale refusant les pairs exacts d'Angular), `npm ci` sans
erreur, `npx ng test --watch=false` (196 tests verts), `npx ng build` vert (avertissements de budget
préexistants ; nouvel avertissement de dépréciation de Sass sur l'`@import` que la CLI 22.2 génère
elle-même pour les styles globaux, sans effet sur le paquet). SBOM front régénéré (`npm run sbom`) et
registre `docs/DEPENDANCES.md` mis à jour (Angular 22.2.1 ; nouvelle transitive `entities` 8.1.0,
BSD-2-Clause, compatible). Garde hors ligne : `outils/tests/versions-angular.test.mjs` (lancé par le
job « Registre des dépendances et licences ») échoue si un paquet Angular revient sous la version qui
corrige un avis du tableau ci-dessus, ou si une famille `@angular/*` est montée partiellement ; il
échoue sur le verrouillage d'avant la montée (`@angular/router 22.0.8 est visé par GHSA-ff3f-86qr-9cv3`).

**Outillage de développement (non livré, hors seuil de la CI)** — `npm audit` sans `--omit=dev`
signale 3 paquets, inchangés par la montée : `undici` 7.28.0 (haute, dépendance de `jsdom`, 15 avis
dont GHSA-8xcm-r25x-g524), `vitest` et `@vitest/mocker` 4.1.10 (moyenne, GHSA-82fw-gwwq-j7x9). Ils ne
servent qu'aux tests unitaires sur le poste et en CI ; aucun n'entre dans le paquet livré. À monter à
la montée de version planifiée suivante de l'outillage de test.

**Back-end (dépendances Java) : état des vulnérabilités inconnu.** L'analyse réelle n'a encore
jamais tourné, ni sur ce poste (NVD refusée par le mandataire, de même que `api.osv.dev` le
03/10/2026), ni dans la CI (§4). Les « 0 critique, 0 haute » ci-dessus ne valent que pour le
front-end à la date du rapport ; aucune affirmation de ce document ne porte sur l'absence de
vulnérabilité haute ou critique dans les dépendances Java.

## 4. Intégration continue sur GitHub (`abddou2000/GED`)

Exécution n° 4 du 30/09/2026 (commit `71bdc1d`, branche `claude/inspiring-lovelace-10bg1c`) :
back-end (tests sur PostgreSQL 16, JAR, SBOM) **vert**, front-end (tests, paquet, SBOM, audit npm)
**vert**, registre des dépendances et licences **vert** ; OWASP Dependency-Check **en échec
volontaire** : « Secret NVD_API_KEY absent » (aucune analyse, aucun rapport).

**État au 03/10/2026** (exécutions 37124893548 sur `a7343b8`, 37126190497 sur `65b1002`,
37126645378 sur `4090da3`, lues par l'API GitHub) :

| Job | État | Cause |
|---|---|---|
| Back-end (tests, JAR, SBOM) | **rouge** depuis `a7343b8` (vert du 30/09 au 01/10, `66af618` compris) | un test, `MetadonneesPlanParalleleTest.indexDeDeploiementSeCree:154` (687 tests, 1 échec) : il attendait un plan parallèle que PostgreSQL ne choisit pas dès que la table `document` dépasse quelques pages ; sa taille au moment du test dépend de l'ordre des classes. Les « remaining connection slots are reserved for roles with the SUPERUSER attribute » du journal du service, déjà présents dans les exécutions vertes, viennent d'un autre défaut, latent : dix pools Hikari de dix connexions pour 97 places. Correctifs de dev2 (tour 5, `c0c51f9` et `fc97877`), à confirmer par la prochaine exécution après intégration : `docs/conformite/suivi/dev2.md` |
| OWASP Dependency-Check (CVSS >= 7) | **rouge, échec volontaire** | étape « Contrôler la chaîne d'analyse (miroir synthétique) » verte ; étape « Analyser les dépendances » : « Ni variable NVD_DATAFEED_URL ni secret NVD_API_KEY » (T-070 : secret attendu de l'administrateur du dépôt pour MMED). Aucune vulnérabilité Java n'est en cause : aucune analyse n'a eu lieu |
| Front-end (tests, paquet, SBOM, audit) | rouge du 01/10 au 03/10 à l'étape « Audit des dépendances livrées » (paquet et SBOM du front non archivés) | ANO-E0-004 (vulnérabilité haute dans `@angular/router` 22.0.8) : montée d'Angular en 22.2.1 par dev4 (tour 5, `620408b`), audit à 0 sur le poste (§3) ; retour au vert à constater à la première exécution après intégration |

Pour lever la réserve, l'administrateur du dépôt crée le secret `NVD_API_KEY` (*Settings → Secrets
and variables → Actions*) ou la variable `NVD_DATAFEED_URL` vers un miroir ; le premier passage
télécharge toute la NVD (de 10 à 30 minutes avec la clé), les suivants repartent du cache, sauvegardé
même quand l'analyse échoue sur une vulnérabilité.
