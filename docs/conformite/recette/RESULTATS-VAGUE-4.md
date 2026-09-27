# Résultats de recette — vague 4 (lot de dev2 : E4 audit, E9 API, notifications)

Exécutés par qa le 27/09/2026 sur `ct/qa` après `git merge conformite-technique` (30e73b3, qui
contient e8a75d9). Aucun code applicatif modifié. Les chemins du contrat §5.3.1 (T-042, P-06),
livrés par dev2 sur sa branche mais non intégrés, restent « à faire ».

## 1. Environnement

- Règle d'équipe 6 bis appliquée : annuaire simulé sur **33394**, `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=3`
  (suite de tests et instance qa).
- Instance qa (profil dev) : API 18084, management 18094. Simulateurs : annuaire UnboundID,
  **clamd** (`FauxClamd`), **relais SMTP** (`recette/lib/SmtpSimule.java`, GreenMail, qui écrit chaque
  message reçu dans un dossier), LibreOffice (`FauxSoffice`). Proxy de confiance par défaut
  (127.0.0.1, ::1). Base `ged_qa` conservée (jeu E3), changesets E4/E9 appliqués au démarrage.

## 2. Suite automatisée

**534 tests, 0 échec**, 8 min 12 s, en une seule exécution (pool Hikari 3) : ANO-E0-001 vérifiée.
Aucune classe n'a perdu de test par rapport à la vague 3.

## 3. E4 — journal d'audit

### Inaltérabilité (`recette/e4/verifier-journal.sh`, 12/12)

| Id | Contrôle | Résultat |
|---|---|---|
| J01 | Colonnes du §7.4.1 ; table partitionnée par mois (14 partitions, dont les mois à venir) | OK |
| J02 | Déclencheurs actifs BEFORE UPDATE, DELETE, TRUNCATE sur la table mère **et chaque partition** | OK |
| J03, J04 | **Connecté en `ged_app`** (vraie connexion) : INSERT et SELECT permis (transaction annulée) | OK |
| J05–J07 | **Connecté en `ged_app` : UPDATE, DELETE, TRUNCATE refusés, SQLSTATE 42501** | OK |
| J08–J10 | Connecté en `ged_owner` (propriétaire) : UPDATE, DELETE, TRUNCATE refusés par le déclencheur | OK |
| J11, J12 | Scellements : `ged_app` en ajout et lecture seulement ; `ged_readonly` lecture seule | OK |

`verifier-socle.sh` confirme au catalogue et par sondes (C36, P20–P23 OK). Il relève en revanche
deux écarts de convention sur les nouvelles tables (ANO-E1-005).

### Scellement (`recette/e4/verifier-scellement.sh`, 5/5, après le scellement horaire de 19 h 05)

| Id | Contrôle | Résultat |
|---|---|---|
| S01 | Vérification à la demande : 1 période, 272 enregistrements, chaîne intègre | OK |
| S02 | **`ged_owner` désactive les déclencheurs et modifie une ligne scellée → `EMPREINTE_DIFFERENTE`** (critère de sortie E4) | OK |
| S03 | Ligne restaurée → chaîne de nouveau intègre | OK |
| S04 | Empreinte d'un scellement réécrite en base → écart détecté | OK |
| S05 | Chaque vérification tracée (`AUDIT_VERIFIE`) | OK |

Contrôle complémentaire : la modification d'une ligne numérotée **hors** de l'intervalle
[premier, dernier] annoncé par le scellement (ligne 260) est elle aussi détectée — la chaîne couvre
toute la période — mais l'intervalle annoncé est faux (ANO-E4-004).

**Critère de sortie E4 : atteint pour l'inaltérabilité ; non atteint pour « chaque action produit
un événement »** tant que ANO-E4-001 (consultation) n'est pas corrigée.

### Un événement par action (`recette/e4/RecetteAudit.java`, 26/29)

| Id | Action | Résultat |
|---|---|---|
| A01, A02 | Connexion refusée (identifiant saisi, REFUS) ; connexion réussie | OK |
| A03 | Dépôt | OK |
| **A04** | **Consultation de la fiche** | **ECHEC → ANO-E4-001** |
| A05, A06 | Téléchargement ; aperçu (événement distinct) | OK |
| A07 | Modification : avant `{"nom":…}` → après | OK |
| A08–A10 | Rattachement ajouté et retiré ; verrouillage | OK |
| A11, A12 | Archivage ; désarchivage | OK |
| A13 | Export ZIP : un événement par document | OK |
| A14–A16 | Suppression, restauration, purge (l'audit survit au document) | OK |
| A17 | Refus 403 tracé (`ACCES_REFUSE` avec la route) | OK |
| **A17b** | **Accès hors périmètre (404) non tracé** | **ECHEC → ANO-E4-002** |
| A18 | Fichier infecté (EICAR, clamd simulé) : 422 et `FICHIER_INFECTE` | OK |
| A19 | Habilitation attribuée puis retirée : avant/après, acteur Administrateur | OK |
| **A20** | **Déconnexion non tracée** | **ECHEC → ANO-E4-003** |
| C01, C02 | Consultation réservée (Utilisateur standard 403, DG 200) et elle-même auditée | OK |
| C03 | Exports CSV et JSON avec en-tête `X-Empreinte-SHA256` **égal** au SHA-256 du corps | OK |
| C04 | Export audité | OK |
| C05 | **Aucune route d'écriture du journal**, même pour l'Administrateur (PUT, PATCH, DELETE, POST refusés) (D11) | OK |
| L01 | Journal technique : 145/145 lignes au pattern de l'Article 50 | OK |
| L02 | `username` renseigné ; `traceId` de l'audit identique à celui du journal technique | OK |

Chaque événement porte acteur, adresse IP, résultat et `trace_id`.

## 4. E9 — API d'intégration (`recette/e9/RecetteApi.java`, 23 OK, 2 AVERT)

| Id | Contrôle | Résultat |
|---|---|---|
| 01, 02 | Clé `ged_dev_<id>_<secret>` ; portée par nœud et opération ; secret jamais relu | OK |
| 03 | Dépôt par clé ; **rejeu de la même Idempotency-Key : même réponse, `Idempotency-Replayed`, un seul document** | OK |
| 04 | **Même clé, contenu différent : 422 `IDEMPOTENCE_CONFLIT`** (problem+json) | OK |
| 05 | Création sans Idempotency-Key : 400, par clé comme par un utilisateur | OK |
| 06, 07 | Recherche filtrée par la portée ; téléchargement identique | OK |
| 08 | **Hors portée : 404 indiscernable d'un inexistant** | OK |
| 09, 10 | Dépôt hors portée 404 ; opération non accordée (versement) **403** | OK |
| 11 | Routes d'administration fermées à une clé | OK |
| 12 | **Chaque appel audité et attribué à l'application** (`DOCUMENT_DEPOSE`, `APPEL_API`) | OK |
| 13 | **Quota : 4e appel → 429 + `Retry-After`**, `QUOTA_MINUTE_DEPASSE` | OK |
| 14, 15 | Adresse non autorisée 403 ; autre environnement ou secret faux 401 | OK |
| 16, 17 | Régénération (ancienne et nouvelle valides) ; révocation immédiate | OK |
| 18 | **Délégation, lecture : intersection** (clé A+B, utilisateur A → document de B en 404) | OK |
| 19 | **Délégation, écriture : déposant = délégué ; audit acteur utilisateur + acteur application** | OK |
| 20, 21 | Identité inconnue 422 `IDENTITE_DELEGUEE_INVALIDE` ; clé sans attribut 403 | OK |
| 22 | Délégation pour un compte **désactivé** : accepté (provisionné sans rôle, lecture 404) au lieu de 422 | AVERT — point ouvert D1 × §5.5 (QR9, `verifier-compte-annuaire` désactivé) |
| 23, 24 | problem+json (404, 400) ; OpenAPI : `X-API-Key`, `Idempotency-Key`, `X-On-Behalf-Of`, problem+json, `Retry-After` | OK |
| 25 | Chemins du contrat `/noeuds/{id}/dossiers`, `/recherches`, `/documents/{id}/contenu`, `/droits` | AVERT — à faire (T-042, P-06, non intégrés) |

**Critère de sortie E9 atteint** (hors chemins du contrat) : une application de test dépose,
recherche et télécharge par clé, dans sa seule portée, et chaque appel est audité.

## 5. Notifications (`recette/e8/RecetteNotifications.java`, 8/8 + 1 NA)

| Id | Contrôle | Résultat |
|---|---|---|
| N-01 | Dépôt, désignation, rattachement, archivage, export, purge : **aucune notification** | OK |
| N-02, N-03 | **Attribution d'un accès à un espace** : pastille (`ACCES_ESPACE_ATTRIBUE`) et e-mail au bénéficiaire (sujet « Accès attribué : Projets ») | OK (relais simulé) |
| N-04, N-05 | Marquage lu ; retrait d'accès non notifié | OK |
| N-06 | Préférence « e-mail désactivé » : pastille seule | OK |
| N-07, N-08 | Chacun ne voit que ses notifications ; expédition auditée sans adresse | OK |
| N-09 | Circuits de validation et fin de conservation | NA : déclencheurs livrés avec E8 |

## 6. Anomalies revérifiées

| Anomalie | Résultat |
|---|---|
| **ANO-E2-001** (IP derrière NGINX) | **Corrigée** : `recette/e2/verifier-identite.sh` **33/33** ; E2-25 : un client derrière le proxy de confiance n'épuise plus le quota d'un autre ; E2-26 : la session enregistre l'IP du client (`X-Forwarded-For`) |
| ANO-E0-001 (connexions) | Corrigée par la règle d'équipe (pool 3) |
| ANO-E7-001, ANO-E7-002, ANO-E5-002 | Hors lot de dev2 : non rejouées |

## 7. Lignes de la matrice

| Réf. | Exigence | Verdict qa |
|---|---|---|
| 7.1 | Pattern de log avec username, ip, traceId, spanId | **Vérifié** |
| 7.4.1 | Table journal_audit : acteurs, IP, action, objet, avant/après, résultat, trace | **Non conforme** (ANO-E4-001 consultation ; ANO-E4-002, 003 mineures) |
| 7.4.2 | INSERT seul, déclencheurs, scellement chaîné exporté | **Vérifié** (réserve mineure ANO-E4-004) |
| 7.4.3 | Consultation réservée, export CSV/JSON avec empreinte | **Vérifié** (rétention 10 ans : revue de configuration seulement) |
| 3.4.1 | Anti-force brute par IP et identifiant | **Vérifié** (ANO-E2-001 corrigée) |
| 5.1 | Réception : enregistrer, horodater, source et déposant | **Vérifié** (déposant et application au journal) |
| 5.2 | Applications soumises au même modèle d'habilitation et à la même journalisation | **Vérifié** |
| 5.3 | Dépôt avec métadonnées en une opération ; pour le compte d'un utilisateur | **Vérifié** ; rattachement et consultation des droits par les chemins du contrat : à faire (T-042, P-06) |
| 5.3.2 | problem+json ; codes 403, 404, 409, 413, 415, 422, 429 ; Idempotency-Key ; quotas | **Vérifié** |
| 5.4 | Clés API : cycle de vie, SHA-256, X-API-Key, portée, quotas, expiration, IP | **Vérifié** |
| 5.5 | Délégation X-On-Behalf-Of, double identité | **Vérifié**, sauf rejet d'un compte désactivé (point ouvert D1 × §5.5) |
| 5.3 | OpenAPI 3 complète | **Vérifié** (en-têtes et erreurs documentés) |
| 12.9 | Notifications : boîte d'envoi, e-mail, pastille, trois cas | **Vérifié** pour l'accès à un espace (relais **simulé**) ; circuits et échéance avec E8 |
