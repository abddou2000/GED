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

## 3. Preuve sur le poste de l'équipe (30/09/2026)

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

**Front-end, analyse réelle** — `npm audit --omit=dev` (registre npm joignable), rapport brut :
[`rapports/npm-audit-2026-09-30.json`](rapports/npm-audit-2026-09-30.json). 580 paquets dans l'arbre,
20 livrés ; **0 critique, 0 haute**, 7 paquets en sévérité **moyenne** issus de deux avis :

| Avis | Paquets | CVSS | Corrigé en | Exposition de la GED |
|---|---|---|---|---|
| [GHSA-p297-fm68-3q8c](https://github.com/advisories/GHSA-p297-fm68-3q8c) — fuite d'information par contournement de `HttpTransferCache` avec `withRequestsMadeViaParent` | `@angular/common` 22.0.8 (et forms, router, platform-browser qui en dépendent) | 4,0 | 22.1.1 | **aucune** : pas de rendu serveur, ni `HttpTransferCache`, ni `provideClientHydration` dans `frontend/src` |
| [GHSA-hh8m-fm6v-7cvg](https://github.com/advisories/GHSA-hh8m-fm6v-7cvg) — contournement de l'assainissement par les liaisons d'hôte des directives | `@angular/core`, `@angular/compiler` 22.0.8 (et animations) | non noté | 22.1.0 | **aucune constatée** : aucune liaison `host:` ni `@HostBinding` dans `frontend/src` |

Seuil de la CI non atteint (le job front est vert sur GitHub). Correction : montée d'Angular en
22.1.1 ou plus (22.2.0 publiée) à la prochaine livraison du front (dev4), `package-lock.json`
régénéré ; aucune urgence au sens du §1.

## 4. Intégration continue sur GitHub (`abddou2000/GED`)

Exécution n° 4 du 30/09/2026 (commit `71bdc1d`, branche `claude/inspiring-lovelace-10bg1c`) :
back-end (tests sur PostgreSQL 16, JAR, SBOM) **vert**, front-end (tests, paquet, SBOM, audit npm)
**vert**, registre des dépendances et licences **vert** ; OWASP Dependency-Check **en échec
volontaire** : « Secret NVD_API_KEY absent » (aucune analyse, aucun rapport).

Pour lever la réserve, l'administrateur du dépôt crée le secret `NVD_API_KEY` (*Settings → Secrets
and variables → Actions*) ou la variable `NVD_DATAFEED_URL` vers un miroir ; le premier passage
télécharge toute la NVD (de 10 à 30 minutes avec la clé), les suivants repartent du cache, sauvegardé
même quand l'analyse échoue sur une vulnérabilité.
