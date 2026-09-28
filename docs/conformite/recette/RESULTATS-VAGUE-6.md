# Résultats de recette — vague 6 (tout le code intégré : E7 modèle, E8, E8-API, T-112)

Exécutés par qa le 28/09/2026 sur `ct/qa`, par avance rapide jusqu'à **68a1f90**. Trois intégrations se sont succédé :

- b7274bc : dev1, E7 modèle, E8, E8-API, correctifs ANO-E7-002 et ANO-E4-001 à 003 ;
- 74a60da : correctifs de dev2 ANO-E9-001, ANO-E11-001, ANO-E11-002, ANO-E10-001 ;
- 68a1f90 : T-112.

Aucun code applicatif modifié.

## 1. Environnement

- Règle 6 bis : annuaire simulé sur 33394, SMTP de test 3034, pool 3. `DB_NAME=ged_qa` et `DB_NAME_TEST=ged_qa_test` sont explicites.
- Instance qa en profil dev (API 18084, management 18094) sur une base neuve **`ged_qa6`** (`preparer-base.sql`, jeu E3 reconstruit : 28/28).
- Seconde instance sur la même base (18085 / 18095, annuaire de l'instance A) pour la non-double exécution de T-112.
- Simulateurs : clamd (`FauxClamd`), LibreOffice (`FauxSoffice`), relais SMTP (GreenMail).
- Scellement et alerte d'échéance chaque minute (`--ged.audit.scellement.cron`, `GED_ALERTE_ECHEANCE_CRON`).
- Pourquoi une nouvelle base : `ged_qa` et `ged_qa5` ne montent pas de version, car elles contiennent des documents archivés (**ANO-E1-006**). `ged_qa` est restée en l'état.

## 2. Suite automatisée

| Commit | Tests | Échecs |
|---|---|---|
| b7274bc | 579 | 0 |
| 74a60da | 580 | 0 |
| 68a1f90 | **586** | 0 (7 min 47 s, `ged_qa_test`) |

## 3. Recettes

| Recette | Script | Résultat (commit) |
|---|---|---|
| E7 modèle | `recette/e7/RecetteModele.java` (nouveau) | **16/17** (68a1f90) ; M-02 → ANO-E7-003 |
| E7 cycle de vie | `recette/e7/RecetteCycleDeVie.java` | **23/23** (74a60da) ; E7-03 OK : ANO-E7-002 close |
| E8 workflow et E8-API | `recette/e8/RecetteWorkflow.java` (nouveau) | **28/29** (68a1f90) ; A-02 → ANO-E8-001 |
| T-112 échéance | `recette/e8/RecetteEcheance.java` (nouveau), deux instances | **6/6** (68a1f90) |
| Réception T-040 | `recette/e9/RecetteReception.java` | **5/5** dans un espace sans règle (74a60da) ; sous règle, R-02 et R-03 en 500 → ANO-E8-002 |
| E4 audit | `recette/e4/RecetteAudit.java` | **29/29** (74a60da) |
| E4 journal | `verifier-journal.sh` | **12/12** |
| E4 scellement | `verifier-scellement.sh` | **6/6** (période 11 h–12 h : 1 → 1610, 1609 lignes) |
| Reprise des signatures | Liquibase `rollbackCount 5` puis `update` sur `ged_qa6` | migration correcte (statut identique sur 58 documents) ; retour arrière avec perte → ANO-E8-003 |

Détail du workflow et de l'E8-API :

- Workflow :
  - règle sur espace, dossier ou type : la plus spécifique s'applique ; sans règle, pas de circuit ;
  - validateurs nommés et par rôle, rôle résolu à la décision ;
  - circuit figé ; décisions sans ordre ; motif obligatoire au refus ;
  - un versement rend les décisions caduques (EN_COURS sur la version 2) ;
  - réaffectation par l'Administrateur, tracée ; annulation ; nouveau circuit ;
  - 409 `CIRCUIT_DEJA_OUVERT`, `AUCUNE_REGLE`, `CIRCUIT_CLOS` ;
  - diffusion sans copie ;
  - « Mes validations », historique, anomalies réservées ;
  - audit de chaque action.
- Notifications (en application et par e-mail) : aux deux validateurs à l'ouverture, à l'initiateur à chaque décision, aux validateurs à l'annulation.
- E8-API :
  - 403 `DELEGATION_REQUISE` sans `X-On-Behalf-Of` ;
  - décision pour le compte d'un validateur, avec double identité au journal ;
  - clé sans portée, clé sans délégation ou personne non validatrice : 403 ;
  - rattacher une règle existante, ouvrir, réaffecter et annuler par API : OK ;
  - **créer une règle par API : 403 (ANO-E8-001)**.

## 4. Lignes « Vérifié »

**12.7 méta-modèle et type :**
- métadonnées validées et normalisées (booléen, nombre, liste, date) ; 400 `METADONNEES_INVALIDES` avec une erreur par champ ;
- objet et date du document ;
- recherche sur un booléen ;
- échéance déduite du type et recalculée (durée, point de départ, index date seulement) ;
- plan versionné ;
- type utilisé : 409 `TYPE_UTILISE`, désactivable ;
- re-typologisation par lot : travail de fond, correspondance, rapport, audit par document, verrouillé en échec.

**12.8 versions et verrou :**
- versions numérotées, avec auteur et empreinte ; une seule courante ;
- ancienne version en lecture seule en base (D9, sonde `ged_app`) ;
- verrou Administrateur avec auteur, date et motif ;
- 409 sur fiche, métadonnées, versement, déplacement, rattachement, réindexation, archivage et suppression ;
- pose et levée auditées.

**12.5 déplacement et renommage :**
- déplacement de document (Déplacer exigé, audit origine → destination) ;
- déplacement de dossier avec sa sous-arborescence, anti-cycle, `ESPACE_DEPLACE` ;
- renommage 409 `NOM_DEJA_UTILISE` (document et dossier), audité.

**R-03 / D12 :** espace d'échange (dossiers et sous-dossiers par un membre habilité, pas en espace métier).

**12.8 workflow, D7, Q1 :** voir la section 3.

**D8 / E8-API :** décision et pilotage (sauf création de règle, ANO-E8-001).

**12.9 / T-112 :**
- signalement unique malgré deux instances (verrou de tâche : « une autre exécution est en cours » observé en alternance) ;
- notification aux seuls Agents d'archive ayant Archiver ; confidentiel non notifié ;
- audit `ECHEANCE_CONSERVATION_ATTEINTE` ;
- échéance repoussée (signalement levé) puis de nouveau atteinte (second signalement) ;
- aucune suppression ni aucun archivage ;
- filtre « échéance dépassée » sur la liste, la recherche et le plein texte (exclusion vérifiée ; le document échu n'était pas encore indexé).

**5.1 / T-040 :** ANO-E9-001.

**6.2.3 A10 (P-12) :** ANO-E11-001 ; ANO-E11-002 sur papier (`verifier-sorties.sh` exercé avec une résolution simulée : 0 / 1 / 1).

**10.4 (P-17) :** ANO-E10-001 sur papier.

**Critères de sortie :**
- **E4 atteint** : chaque action produit un événement (29/29) ; une modification manuelle du journal fait échouer la vérification (S02, S04).
- **E7 atteint** : document archivé intouchable (E7-03), copie PDF/A validée par veraPDF (E7-02), export limité au visible (E7-10, E7-14).
- **E8 atteint** : deux validateurs décident dans n'importe quel ordre ; ouverture, décision et annulation notifient les bonnes personnes.

## 5. Anomalies

- Closes (Vérifiées) : ANO-E7-002, ANO-E4-001, ANO-E4-002, ANO-E4-003, ANO-E9-001, ANO-E11-001, ANO-E11-002 (papier), ANO-E10-001 (papier).
- Nouvelles :
  - **ANO-E1-006**, Majeure : montée impossible sur une base contenant un document archivé ;
  - **ANO-E8-001**, Majeure : création de règle refusée par API ;
  - **ANO-E8-002**, Majeure : dépôt par application sans délégation sous une règle → 500 ;
  - **ANO-E7-003**, Mineure : valeur par défaut perdue au dépôt ;
  - **ANO-E8-003**, Mineure : retour arrière de la reprise des signatures avec perte.
- Toujours ouverte : ANO-E5-003.

Observations, sans anomalie :
- un compte sans aucun rôle reçoit 403 partout, y compris sur un objet hors périmètre (P-04) ;
- l'archivage d'un document verrouillé est refusé en 409 `DOCUMENT_VERROUILLE`, sans citer le motif.

## 6. Notes d'outillage

- `recette/lib/SmtpSimule.java` corrigé. Il identifiait les messages par leur rang dans `getReceivedMessages()`, que GreenMail regroupe par boîte : doublons et destinataires mêlés dès qu'il y a plusieurs destinataires. Il les identifie désormais par Message-ID et destinataires. Les e-mails de la vague 4 (un seul destinataire) ne sont pas concernés.
- `RecetteAudit` (E4-A17b) attend désormais le code `ACCES_HORS_PERIMETRE` retenu par le correctif d'ANO-E4-002.
- `RecetteReception` : option `GED_E9_ESPACE_LIBRE=1`, pour un emplacement sans règle de workflow.
- Copie hors base des scellements : un fichier par base. Le fichier de l'instance, partagé entre `ged_qa5` et `ged_qa6`, faisait signaler `SCELLEMENT_SUPPRIME` à tort. Il a été séparé, et l'original est conservé.
- Incident de manipulation corrigé : la fusion de b7274bc était une avance rapide, et un `--amend` de qa l'avait réécrite localement. `ct/qa` a été remis exactement sur b7274bc (arbre identique), rien n'a été poussé. Les fusions suivantes ont été vérifiées (`merge-base --is-ancestor`) avant tout commit.
