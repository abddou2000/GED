# Résultats de recette — vague 2 (E2 identité, E3 autorisation, correctifs E1)

Exécutés par qa les 26-27/09/2026 sur `ct/qa` après `git merge conformite-technique` (4f41279).
Bases `ged_qa` et `ged_qa_test` préparées par `creer-roles.sql` puis `preparer-base.sql -v tests=oui`,
extensions `unaccent` et `pg_trgm` créées à la main. Application intégrée démarrée par qa
(profil dev, ports 18084 / management 18094) avec l'annuaire **simulé** UnboundID (LDIF de
démonstration + `recette/donnees/annuaire-recette.ldif`). Aucun code applicatif modifié.

## 1. Suite automatisée

| Mesure | Vague 1 | Vague 2 |
|---|---|---|
| Tests | 257 | **360** (0 échec, 0 erreur, 0 ignoré), 2 min 56 s, PostgreSQL `ged_qa_test` |
| Classes | 39 | 54 |

Seule baisse par classe : `security.ServiceJetonTest` 8 → 5, justifiée (jeton HMAC local remplacé par
RS256 ; les cas de l'annuaire et des sessions sont dans `identite.AuthentificationApiTest` (13) et
`identite.annuaire.AnnuaireLdapTest` (14)).

## 2. Revérification des anomalies E1

| Anomalie | Contrôle | Résultat |
|---|---|---|
| ANO-E1-001 clés composites | `verifier-socle.sh` E1-C04 | **Corrigée** : 0 clé composite (`id uuid` + `uk_` sur les tables d'association) |
| ANO-E1-002 `deleted` | E1-C15, E1-C16 | **Corrigée** : `supprime`, `supprime_par`, `supprime_le` sur toutes les tables à corbeille |
| ANO-E1-003 `data-initial` | E1-C22, `AnalyseurChangelogs` A07/A08/A23 | **Corrigée** : rôles système et permissions en changesets `data-initial` ; `CompteSeeder` supprimé ; restent 8 seeders de **démonstration** limités au profil `dev` (A23 avertissement, admis) |

Recette E1 complète rejouée sur le changelog de la vague (50 changesets, 26 tables) :
base vierge par Liquibase seul puis démarrage de l'application en `ged_app` sans aucune
modification de DDL (V01–V05 OK) ; retour arrière des 50 changesets puis remontée au DDL
identique (R01–R04 OK) ; catalogue 48 OK, 0 ECHEC, 2 AVERT (C13 index sur `*_par`, C25
extensions) ; analyse des changelogs 16 OK, 0 ECHEC.

Ajustements de la recette (pas de l'application) : les colonnes d'auteur `<action>_par`
(`cree_par`, `verrou_par`, `archive_par`) sont admises au même titre que `supprime_par`, le DAT
nommant lui-même `cree_par` (§12.4) ; seules les insertions de **valeurs fixes** exigent
l'étiquette `data-initial` (une reprise `INSERT … SELECT` ou un `UPDATE` dans un corps de
déclencheur n'est pas un amorçage).

## 3. Recette E2 — `recette/e2/verifier-identite.sh` (33 contrôles : 31 OK, 2 ECHEC)

| Id | Contrôle | Résultat |
|---|---|---|
| C01–C03 | Aucune colonne ni table de mot de passe ; **aucune valeur** au format BCrypt/Argon2/PBKDF2 dans toute colonne texte de la base | OK |
| C04–C07 | `utilisateur.object_guid` unique ; `session` sans jeton en clair (colonne `empreinte`) ; `cache_annuaire` ; aucun attribut `userAccountControl`/`memberOf` mémorisé | OK |
| 01 | Connexion par `sAMAccountName` (D2) | OK |
| 02 | Cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, Max-Age 28 799 s (≤ 8 h), `Cache-Control: no-store`, jeton de renouvellement jamais dans le corps | OK |
| 03–05 | JWT RS256, `exp − iat` = 900 s, revendications `exp iat iss jti sid sub uid` (aucun rôle ni permission) | OK |
| 07 | Jetons forgés refusés en 401 : `alg: none`, HS256, charge modifiée, signature tronquée ; jeton en paramètre d'URL ou en cookie refusé | OK |
| 08 | Adresse e-mail et UPN refusés en 400 **avec le bon mot de passe** (D2) | OK |
| 09 | Mauvais mot de passe et compte inconnu : 401 au message identique | OK |
| 10 | Compte désactivé dans l'annuaire : connexion refusée par l'annuaire (D1) | OK (simulateur) |
| 11, 12 | Première connexion : identité créée **sans rôle** (compte portant pourtant `memberOf: GED-Administrateurs` : aucun droit déduit de l'AD, P2), clé objectGUID ; `/documents` → 403 | OK |
| 13–16 | Renouvellement sans `X-GED-Renouvellement` → 403 ; rotation à chaque usage ; la table `session` ne contient que l'empreinte SHA-256 ; **rejeu d'un jeton consommé → toute la famille révoquée, jeton d'accès en cours refusé aussitôt** | OK |
| 17 | Déconnexion : jeton d'accès refusé immédiatement | OK |
| 18, 19 | **Révocation par l'Administrateur : accès et renouvellement refusés immédiatement** (critère de sortie) ; refusée en 403 à une identité sans rôle | OK |
| 20, 21 | Aucune lecture de `userAccountControl` ni tâche périodique dans le code client de la GED (D1 ; seul le simulateur d'annuaire le lit, comme l'AD) ; attributs lus = strict minimum (D3) | OK |
| 22 | Front Angular : aucun `localStorage`/`sessionStorage`/`document.cookie` pour le jeton (revue du code) | OK |
| 23, 24 | 6e tentative en une minute → 429 + `Retry-After` ; même IP, autre identifiant → 429 | OK |
| **25** | **Derrière NGINX : un client épuise le quota de tous** | **ECHEC → ANO-E2-001** |
| **26** | **Session : adresse du proxy enregistrée au lieu de celle du client** | **ECHEC → ANO-E2-001** |

**Inspection du navigateur** (Angular servi sur 4384, mandaté vers l'API qa ; compte `yalaoui`) :
après connexion puis rechargement complet de la page, `localStorage` et `sessionStorage` vides,
`document.cookie` vide (le cookie de renouvellement est `HttpOnly`), aucune base IndexedDB,
aucune chaîne au format JWT dans un stockage ; la session est rétablie au rechargement par
`POST /api/v1/auth/refresh` → 200 (renouvellement silencieux). Jeton en mémoire seulement : conforme.

## 4. Recette E3 — `recette/e3/verifier-autorisation.sh` (28 contrôles : 28 OK)

Jeu construit par le script via l'API d'administration : DÉPOSANT = Direction Générale (portée
globale) ; TIERS = Utilisateur standard sur « Comptabilité » (A) ; SANS_DROIT = Utilisateur
standard sur « Projets » (C, sans document) et « Contrats » (enfant de B). Témoins : D1 PUBLIC,
D2 PRIVE, D3 CONFIDENTIEL en A ; D4 PUBLIC en A/2026 ; D5 PUBLIC en B ; D6 PRIVE en A déposé par
le TIERS. Exécuté deux fois (idempotent).

| Id | Contrôle | Résultat |
|---|---|---|
| 02–06 | DG voit les 6 témoins ; TIERS voit **exactement** D1, D4, D6 ; SANS_DROIT : 0 résultat et **total 0** en recherche et en liste complète ; total du TIERS = son périmètre recalculé sur la vue DG | OK |
| 07, 08 | Tableau de bord (tuile, répartition par type, courbe des dépôts) au seul périmètre (TIERS 3, SANS_DROIT 0) | OK |
| 09, 10 | Recherche par index filtrée par droits et confidentialité à la source | OK |
| 11–13 | Arbre : TIERS voit A et sa descendance seulement ; SANS_DROIT voit B en **passage** (libellé seul), rien sous B hors « Contrats », 0 document sur B | OK |
| 14, 15 | **404 sur 14 routes** (fiche, téléchargement, aperçu, rattachements, désignés, PUT, verrou, DELETE, restauration, rattachement, texte OCR, indexation ×2, circuit) pour 6 couples utilisateur/témoin interdit : **84 réponses identiques à un identifiant inexistant** (statut, type, corps hors horodatage) | OK |
| 16–18 | Aucune écriture hors périmètre n'a pris effet ; temps de réponse 404 interdit/inexistant 17,3 / 17,2 ms ; objet visible sans la permission → 403 | OK |
| 19–22 | CONFIDENTIEL visible dès la désignation, 404 dès le retrait ; désigné sans droit sur l'emplacement → 404 (intersection) ; PRIVE invisible d'un tiers, visible de son déposant | OK |
| 23, 24 | Rupture d'héritage sur A/2026 : D4 et le nœud disparaissent aussitôt ; retrait : réapparaissent (D14) | OK |
| 25, 26 | Rattachement de D5 à A : visible du TIERS (union), une seule ligne ; retrait : 404 | OK |
| 27, 28 | Droits effectifs avec origine (ATTRIBUTION_DIRECTE, HERITAGE) cohérents avec les accès constatés ; administration refusée hors Administrateur (403) | OK |

## 5. Lignes de la matrice

| Réf. | Exigence | Verdict qa |
|---|---|---|
| 3.2 | Aucun référentiel local de mots de passe | **Vérifié** |
| 3.3 | LDAPS search-then-bind, compte de service | **Vérifié par simulateur** (search-then-bind par UID ; LDAPS réel et contrôleur MMED en UAT) |
| 3.3 | Provisionnement sans rôle, clé objectGUID | **Vérifié** (simulateur d'annuaire) |
| 3.3 | Jeton d'accès court sans permission | **Vérifié** |
| 3.4.1 | RS256, clé en coffre, jeton en mémoire côté Angular | **Vérifié** (code et navigateur) ; coffre de clé : revue en UAT |
| 3.4.1 | Renouvellement en cookie httpOnly, 8 h, table session, révocation | **Vérifié** |
| 3.4.1 | CSRF : jeton dans `Authorization` seul | **Vérifié** |
| 3.4.1 | Anti-force brute 5/min par IP et identifiant | **Non conforme en production** (ANO-E2-001) ; conforme en accès direct |
| 3.4.2 | Cache annuaire 15 min ; D1 | **Vérifié** (structure, absence de relecture de l'état) |
| 6.2.3 A01 | Refus par défaut, 404 hors périmètre | **Vérifié** |
| 6.4 | Point d'application unique | **Vérifié** sur 14 routes + listes, recherche, arbre, compteurs |
| 12.2 | Rôles, habilitations, héritage, rupture (D14) | **Vérifié** |
| 12.3 | Confidentialité et personnes désignées | **Vérifié** |
| 12.4 | Rattachement (droits, recherche, retrait) | **Vérifié** pour E3 (export ZIP en E7) |
| 4.2.2 | Conventions de nommage | **Vérifié** (ANO-E1-001 corrigée) |
| 12.5 | Suppression douce avec auteur et date | **Vérifié** (ANO-E1-002 corrigée) |
| 4.2.1 | Amorçage par migration | **Vérifié** (ANO-E1-003 corrigée) |

Critère de sortie E2 : atteint sur ce poste avec le simulateur (connexion, aucun mot de passe,
révocation immédiate) ; « compte AD de test en UAT » reste à faire. Critère de sortie E3 :
**atteint**. Réserve transverse : ANO-E2-001 à corriger avant toute mise derrière NGINX.
