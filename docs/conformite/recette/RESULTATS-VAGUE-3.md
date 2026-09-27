# Résultats de recette — vague 3 (E5 branché, E6, E7 de bout en bout par HTTP)

Exécutés par qa le 27/09/2026 sur `ct/qa` après `git merge conformite-technique` (e81ecc2).
Aucun code applicatif modifié.

## 1. Environnement

- Base `ged_qa` **recréée** (`preparer-base.sql -v tests=oui`, qui crée désormais `unaccent`),
  schéma par Liquibase au démarrage.
- Application intégrée (JAR construit sur `ct/qa`), profil dev, API 18084, management 18094,
  annuaire **simulé** (LDIF de démonstration + `recette/donnees/annuaire-recette.ldif`), stockage,
  cache d'aperçu, keystore et répertoires de travail dans un dossier jetable hors dépôt.
- Réels : chiffrement AES-256-GCM, Tika, **veraPDF** (bibliothèque embarquée), **Tesseract 5 `fra`
  et `ara`**, PostgreSQL (tsvector french + arabic).
- **Simulés** : ClamAV (`recette/lib/ClamdSimule.java` lance le `FauxClamd` des tests de dev3 :
  protocole INSTREAM réel, reconnaît EICAR seulement), LibreOffice (`FauxSoffice` des tests de dev3
  derrière un `.cmd`).
- Jeu d'habilitations : `recette/e3/verifier-autorisation.sh` rejoué sur la nouvelle base
  (**28/28**, non-régression E3) ; types de recette créés par l'API d'administration
  (`TD-QA-TXT`, `TD-QA-PETIT` 5 Mo, `TD-QA-200` 200 Mo).

## 2. Suite automatisée

| Exécution | Résultat |
|---|---|
| 1re, pendant que l'instance qa (10 connexions) et d'autres membres utilisaient PostgreSQL | 427 tests, **6 erreurs** « remaining connection slots are reserved » |
| 2e, instance qa arrêtée | **443 tests, 0 échec**, 4 min 21 s ; pic mesuré : **72 connexions** de la suite sur `ged_qa_test`, 98/100 sur le serveur → **ANO-E0-001** |

Aucune baisse de nombre de tests par classe par rapport à la vague 2.

## 3. E5 — stockage sécurisé, par HTTP sur le dépôt réel

| Script | Résultat |
|---|---|
| `fumee.sh` (`GED_EXIGER_UUID=1`, santé sur le port de management) | 7/7 : dépôt **202**, recherche, téléchargement identique à l'octet, suppression douce |
| `verifier-type-reel.sh` (type « PDF seul ») | 6/6 : texte, exécutable, DOCX, PNG sous `.pdf` → **415 `FORMAT_NON_AUTORISE`** sans rien écrire ; vrai PDF sous `.txt` accepté |
| `verifier-antivirus.sh` | 3/3 : témoin sain accepté ; **EICAR → 422 `FICHIER_INFECTE`**, rien d'écrit ; EICAR sous `.pdf` → 422 (simulateur) |
| `verifier-antivirus.sh --antivirus-arrete` (clamd simulé arrêté) | **Fichier sain refusé 503 `ANTIVIRUS_INDISPONIBLE`**, rien d'écrit ; sonde `antivirus` DOWN (échec fermé) |
| `verifier-taille.sh` | 4/4 : 200 Mio + 1 → **413 `FICHIER_TROP_VOLUMINEUX`** ; limite du type + 1 → 413 ; limite exacte et 200 Mio exacts acceptés |
| `verifier-alteration.sh` | 7/7 : octet inversé, troncature, substitution → lecture refusée (500) ; grand fichier altéré en fin → transfert interrompu, jamais complet ; restauration → identique. Réserve : **ANO-E5-002** (500 sans code métier) |
| `verifier-aucun-clair.sh --racine coffre --racine-cache cache-apercu` | 8/8 sur **76 fichiers** (originaux, versions, copies PDF/A, cache d'aperçu) : `aa/bb/<uuid>.enc`, en-tête `GEDC`, aucune signature ni chaîne en clair, incompressibles |
| Répertoires de travail (aperçu, conservation) après la recette | 0 fichier résiduel |

## 4. E6 — OCR et recherche plein texte (`recette/e6/`, 24/24)

| Id | Contrôle | Résultat |
|---|---|---|
| 01 | Chaîne active, Tesseract disponible, modèles `fra` et `ara`, défaut `fra+ara` (D5 : binaire appelé depuis Java) | OK |
| 02 | Dépôt de 6 scans : **202 `EN_ATTENTE_OCR`** immédiat | OK |
| 03 | Texte extrait et indexé : OCR page par page, couche texte native pour le PDF arabe | OK |
| 04, 05, 06 | Témoin trouvé dans le scan **français**, le scan **arabe** et le PDF arabe à formes de présentation | OK |
| 07, 08 | **`zarkopage20`** trouvé (20e page d'un scan de 20 pages : aucun plafond) ; scan arabe de 20 pages trouvé | OK |
| 09–11 | Insensible aux accents ; websearch : expression exacte, exclusion | OK |
| 12 | Extraits en segments `{texte, surligne}`, jamais en HTML | OK |
| 13, 14 | TIERS : trouve le scan de A, pas celui de B ; SANS_DROIT : 0 résultat, total 0 | OK |
| 15, 16 | Cloisonnement : aucun champ d'index rempli ; l'aperçu d'indexation d'un scan ne propose rien issu du contenu | OK |
| 17 | Réindexation incrémentale : nouvelle version trouvée, ancien contenu plus trouvé | OK |
| 18 | Supervision OCR : Administrateur 200, utilisateur 403 | OK |
| 19 | **Délai dépôt → disponibilité d'un scan de 20 pages : 130 s (fr), 163 s (ar)** — sous D6 (24 h) et sous l'objectif V3 (5 min), file quasi vide, 2 workers | OK |

**Critère de sortie E6 : atteint** (scan arabe et français de 20 pages trouvés par leur contenu, dans
le périmètre, en moins de 3 min).

## 5. E7 — cycle de vie (`recette/e7/`, 21/23)

| Id | Contrôle | Résultat |
|---|---|---|
| 01, 02 | Archivage manuel : statut ARCHIVE ; copie **PDF/A-2B validée par veraPDF**, empreinte enregistrée | OK |
| **03** | **Document archivé intouchable** : fiche, versement, verrou, suppression refusés pour DG et Administrateur ; **rattachement accepté (201)** | **ECHEC → ANO-E7-002** |
| 04 | Copie PDF/A servie par défaut (empreinte exacte), original conservé (`?original=true`) | OK |
| 05, 06 | Désarchivage refusé à l'Utilisateur standard (403), permis à l'Administrateur | OK |
| 07 | DOCX archivé avec conversion PDF/A-2 obligatoire (D10) | OK (LibreOffice simulé) |
| 08, 09 | Archivage d'un **dossier entier** : job de fond TERMINE, documents archivés, drapeau sur le dossier ; dépôt ensuite refusé 409 `DOSSIER_ARCHIVE` (D10) | OK |
| 10–13 | Export ZIP du TIERS : documents visibles et sous-dossiers, privé/confidentiel d'autrui **omis sans trace**, aucun compteur d'omis, `manifeste.csv` UTF-8 complet, empreintes exactes, fichiers identiques | OK |
| **14** | **Export d'un dossier hors périmètre : 200 + ZIP nommé « Comptabilité » (manifeste vide)** | **ECHEC → ANO-E7-001** |
| 15–19 | Purge : refusée hors corbeille (409) et à la DG (403) ; depuis la corbeille : fiche, téléchargement, corbeille → 404 ; **fichier `.enc` supprimé ; `cle_fichier` détruite** ; document, versions, texte supprimés | OK |
| 20–22 | Aperçu PDF déchiffré en ligne identique ; aperçu DOCX converti puis servi depuis le cache chiffré ; 404 hors périmètre | OK (conversion simulée) |

Critère de sortie E7 : **non atteint** tant que ANO-E7-001 (export hors périmètre) et ANO-E7-002
(archivé modifiable par rattachement) ne sont pas corrigées.

## 6. Anomalies antérieures revérifiées

| Anomalie | Constat | Statut |
|---|---|---|
| ANO-E5-001 (keystore dans le dépôt) | Keystore de dev par défaut sous `${user.home}/.ged-dev/cles` (revue de `application-dev.yml`) | Vérifiée |
| ANO-E1-004 (`unaccent`) | Créée par `preparer-base.sql` ; le changelog vérifie sa présence | Vérifiée |
| ANO-E2-001 (IP derrière NGINX) | Non rejouée dans cette vague | Ouverte |

## 7. Lignes de la matrice

| Réf. | Exigence | Verdict qa |
|---|---|---|
| 6.1.1 | Identifiant opaque, `aa/bb`, écriture atomique | **Vérifié** (dépôt réel) |
| 6.1.2 | AES-256-GCM, clé par version, keystore, rotation | **Vérifié** (rotation : banc des composants, vague 1) |
| 6.1.4 | Empreinte SHA-256 ; PDF/A-2 validé veraPDF | **Vérifié** (empreintes du manifeste et de la copie ; veraPDF réel) ; vérification mensuelle non exercée |
| 6.1.5 | Type réel (Tika) | **Vérifié** |
| 6.1.5 | ClamAV, refus si indisponible | **Vérifié par simulateur** (ClamAV réel en UAT) |
| 6.1.6 | Prévisualisation déchiffrée, droits appliqués | **Vérifié** pour PDF ; bureautique **par simulateur** ; audit non vérifiable (E4 absent) |
| 4.3.3 | Cloisonnement OCR | **Vérifié** |
| 4.3.4 | Asynchrone 202, fra/ara, sans plafond, délai mesuré | **Vérifié** |
| 4.4 | tsvector + GIN, french/arabic, websearch, extraits, pertinence | **Vérifié** |
| 4.4.1 | Réindexation incrémentale | **Vérifié** (complète : non exercée) |
| 5.3.1 | Recherche filtrée par droits, paginée | **Vérifié** |
| 12.5 | Purge avec destruction cryptographique | **Vérifié** |
| 12.6 | Archivage | **Non conforme** (ANO-E7-002) |
| 12.10 | Export ZIP avec manifeste | **Non conforme** (ANO-E7-001) |
| 12.11 | Dépôt en deux temps | Partiel (202 et statut vérifiés ; issues `SANS_PLAN`/`A_INDEXER` non exercées) |
