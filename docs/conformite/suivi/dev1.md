# Suivi — dev1

## Contrat d'API du workflow (E8-API, D8) — publié pour dev2

Mêmes points d'entrée pour l'interface et pour l'intranet (§2.3). Base
`/api/v1/workflow`. Chaque action est attribuée à une personne nommée :
l'utilisateur du jeton, ou l'identité déléguée (`X-On-Behalf-Of`) d'une
application habilitée. Branchement de dev2 : déclarer en `@Primary` une
implémentation de `workflow.api.AccesApiWorkflow` :

- `acteur(auth, requete)` → `ActeurWorkflow(utilisateurId, employeId, applicationId, libelle)` :
  pour une `ApplicationAuthentifiee`, l'identité de `FiltreCleApi.ATTRIBUT_DELEGATION`
  (refus 403 sans délégation) ; pour un utilisateur, déléguer à `AccesApiWorkflowUtilisateurs` ;
- `verifierPortee(auth, operation, noeudId)` → `ControlePorteeApplication` avec
  `PILOTAGE` = `OperationApi.WORKFLOW_PILOTAGE`, `DECISION` = `OperationApi.WORKFLOW_DECISION`
  (nœud = emplacement principal du document, ou nœud visé).

Idempotency-Key : appliqué par le filtre de dev2 aux écritures, sans rien
demander au workflow. Les droits restent ceux d'`AccessPredicate` (la personne
déléguée doit elle-même détenir les permissions).

| Méthode et chemin | Opération | Corps | Réponse | Refus |
|---|---|---|---|---|
| `GET /regles` (alias `/api/v1/workflowgeds`) | — | page `?page&size&search` | `RegleResponse` | — |
| `GET /regles/{id}` | — | — | `RegleResponse` | 404 |
| `POST /regles` | PILOTAGE | `{name, steps:[{employeId \| roleId+perimetreNoeudId?, label, stepOrder?}]}` | 201 `RegleResponse` | 400 ; 403 sans `GERER_REFERENTIELS` |
| `PUT /regles/{id}` | PILOTAGE | idem | `RegleResponse` (effet sur les seuls dépôts futurs) | 400, 403, 404 |
| `DELETE /regles/{id}` | PILOTAGE | — | 204 (corbeille) | 403, 404 |
| `PUT /noeuds/{noeudId}/regle` | PILOTAGE | `{regleId \| null}` | 204 | 403, 404 |
| `PUT /types/{typeId}/regle` | PILOTAGE | `{regleId \| null}` | 204 | 403, 404 |
| `GET /documents/{documentId}/regle` | — | — | `{regleId, name, origine: TYPE\|NOEUD, origineId}` ou 204 | 404 hors périmètre |
| `GET /documents/{documentId}/circuits` | — | — | `[CircuitResponse]`, le plus récent d'abord | 404 hors périmètre |
| `GET /circuits/{circuitId}` | — | — | `CircuitResponse` | 404 |
| `POST /documents/{documentId}/circuits` | PILOTAGE | `{}` | 201 `CircuitResponse` | 409 `CIRCUIT_DEJA_OUVERT`, 409 `AUCUNE_REGLE`, 403 |
| `POST /circuits/{circuitId}/decisions` | DECISION | `{decision: VALIDE\|REFUSE\|ANNULEE, motif, validateurId?}` | 201 `CircuitResponse` | 400 `MOTIF_OBLIGATOIRE` (refus) ; 403 `PAS_VALIDATEUR` ; 409 `CIRCUIT_CLOS` ; 409 `DECISION_INCOHERENTE` (annuler sans décision) |
| `POST /circuits/{circuitId}/annulation` | PILOTAGE | `{motif}` | `CircuitResponse` (statut `ANNULE`) | 400 `MOTIF_OBLIGATOIRE` ; 403 (ni initiateur ni Administrateur) ; 409 `CIRCUIT_CLOS` |
| `PUT /circuits/{circuitId}/validateurs/{validateurId}` | PILOTAGE | `{employeId, motif}` | `CircuitResponse` | 403 (Administrateur seul) ; 409 `VALIDATEUR_DEJA_DECIDE` ; 409 `CIRCUIT_CLOS` |
| `GET /a-traiter` | — | `?page&size` | page `ATraiterResponse` | — |
| `GET /historique` | — | — | `[DecisionResponse]` de l'acteur | — |
| `GET /anomalies` | — | — | `[AnomalieResponse]` (validateurs nommés sans identité, sans droit Valider, ou inactifs) | 403 (Administrateur) |
| `POST /documents/{documentId}/diffusion` | PILOTAGE | `{utilisateurIds:[], groupeIds:[]}` | `{habilitationsPosees}` | 403 sans Diffuser ; 409 `DOCUMENT_NON_VALIDE` |

`CircuitResponse` : `id, documentId, document, statut (EN_COURS|VALIDE|REFUSE|ANNULE),
regleId, regle, initiateur, ouvertLe, closLe, annulePar, annuleLe, motifAnnulation,
versionCouranteId, versionCouranteNumero, validateurs[{id, type (NOMME|ROLE),
employeId, employe, roleCode, perimetreNoeudId, libelle, etat (EN_ATTENTE|VALIDE|REFUSE),
derniereDecision, reaffecteDe, reaffectePar, reaffecteLe}], decisions[{id, validateurId,
versionId, versionNumero, decision, motif, auteur, applicationId, le}], peutDecider, peutAnnuler`.

Règles de calcul (§12.8, D7) : validateurs parallèles, aucun ordre, aucun
facultatif. Statut recalculé dans la transaction de chaque décision ou
versement : `VALIDE` si, pour chaque validateur, la dernière décision non
annulée sur la version courante est `VALIDE` ; `REFUSE` si au moins un refus
sur la version courante ; sinon `EN_COURS`. Un versement rend caduques les
décisions antérieures. Validateur par rôle : résolu au moment de la décision
(rôle détenu sur le périmètre, et permission Valider sur le document).

## Contrat d'archivage pour dev3 (E7, livré en premier)

Le lot cycle de vie (dev3) archive un dossier entier et chaque document ; le
nœud, le document et leurs colonnes sont au lot modèle (dev1). Contrats
(commit « Poser le contrat d'archivage… ») :

- `common.StatutConservation` : `ACTIF` / `ARCHIVE`.
- Colonnes (changeset 202609301000) : `noeud` et `document` ←
  `statut_conservation`, `archive_le`, `archive_par` (identité GED) ;
  `document.echeance_conservation` (calculée par la base, §12.9).
- `workspace.archivage.ArchivageNoeuds` (implémentation `ArchivageNoeudsJdbc`) :
  `statut(noeud)`, `marquerArchive(noeud, auteur)` (nœud et sous-arborescence,
  même horodatage, idempotent), `marquerActif(noeud)`,
  `documentsAArchiver(noeud, apres, taille)` : documents vivants non archivés
  dont l'emplacement **principal** est dans la sous-arborescence, id croissants,
  pagination par clé (tranches de 100 du `job_archivage`).
- `document.archivage.ArchivageDocuments` (implémentation `ArchivageDocumentsJdbc`,
  `Propagation.MANDATORY` : dans la transaction de la tranche) :
  `archiver(document, auteur)` (idempotent, 409 `DOCUMENT_VERROUILLE` si
  verrouillé), `desarchiver(document)`.
- `document.GardeEcriture.exigerModifiable(document)` : 409
  `DOCUMENT_VERROUILLE` ou `DOCUMENT_ARCHIVE` ; à appeler par tout service
  d'écriture du lot cycle de vie (purge exceptée : elle porte sur un document
  en corbeille).
- Aucun de ces contrats ne vérifie les droits : l'appelant exige Archiver
  (`ControleAcces.exigerSurNoeud` / `exigerSurDocument`) avant.

## Lot en cours

**T-112 — alerte d'échéance de conservation** (§12.9 p. 35 ; dossier
fonctionnel §4.6.3, §4.6.6) : terminé, sur `ct/dev1` après fusion de
`conformite-technique` (b7274bc). Fusions précédentes intégrées (b7274bc).

| Réf. | Exigence | Réalisation |
|---|---|---|
| 12.9 (T-112) | Tâche planifiée quotidienne avec verrou de tâche (pas de double exécution à plusieurs instances) | `TacheAlertesEcheance` (`@Scheduled`, fuseau Africa/Casablanca) → `AlertesEcheanceConservation.executer` sous `common.tache.VerrouTache` : table `verrou_tache` (bail : `UPDATE` conditionnel validé dans sa propre transaction, repris à expiration si l'instance tombe ; libération par le seul détenteur). Paramétrable : `ged.conservation.alertes.actif` / `GED_ALERTE_ECHEANCE_ACTIVE`, `…cron` / `GED_ALERTE_ECHEANCE_CRON` (6 h par défaut), `tranche`, `bail`. |
| 12.9 | Sélection des documents échus non encore signalés, marquage | `document.echeance_signalee_le` (index partiel `idx_document_echeance_a_signaler`) ; tranches `FOR UPDATE SKIP LOCKED`, une transaction par tranche ; échéance <= jour de MMED ; corbeille exclue, archivés inclus. Échéance repoussée dans le futur (type, date, métadonnée) : signalement levé par déclencheur (`trg_document_echeance_resignaler`), nouvelle alerte à la nouvelle échéance. |
| 12.9 / 4.6.3 / 4.6.6 | Notification aux Agents d'archive (famille « fin de conservation ») | `EcheanceConservationAtteinte` implémente `EvenementNotifiable` (`TypeNotification.ECHEANCE_CONSERVATION`, moteur de dev2, boîte d'envoi dans la transaction du marquage). Destinataires : porteurs du rôle Agent d'archive (direct ou par groupe, global ou sur un nœud) qui détiennent **Archiver sur le document** (`AccessPredicate`, confidentialité comprise : un agent non désigné n'apprend pas l'existence d'un document confidentiel). |
| 7.4.1 | Événement d'audit | `ECHEANCE_CONSERVATION_ATTEINTE` au catalogue `ActionAudit`, une trace par document signalé (échéance, nombre d'agents notifiés). |
| 12.9 | Filtre « échéance dépassée », mise en évidence | `echeanceDepassee` : `GET /documents`, `POST /documents/recherche`, `GET /recherche/plein-texte` ; `DocumentResponse.echeanceDepassee` et `Resultat.echeanceDepassee` ; échéance triable. Écrans : bouton « Échéance dépassée » et pastille dans « Documents déposés » (ligne teintée, colonne « Échéance de conservation »), bandeau sur la fiche, case à cocher et pastille dans la recherche plein texte. |
| P4 | Aucune suppression automatique | Seul `echeance_signalee_le` change ; vérifié par test. |

Tests : `AlertesEcheanceConservationTest` (5 : signalement unique et
destinataires, verrou tenu ailleurs, deux exécutions simultanées sans double
signalement ni double notification, échéance repoussée puis de nouveau
atteinte, filtres), `VerrouTacheTest` (bail, reprise après panne, libération
par le seul détenteur), `SchemaLiquibaseTest` (table `verrou_tache`, retour
arrière au jalon `workflow-e8`) ; `mvn test` **585 verts**, `ng build` vert. Non fait : filtre dans l'API contrat §5.3.1
(`POST /recherches`, lot de dev2) ; écrans vérifiés par `ng build` seulement.

**Fusion de `conformite-technique` (e8a75d9 : dev3 E5-E7, dev2 vagues 2 à 4)
dans `ct/dev1`, réconciliation et correctifs de recette** : faite (commit de
fusion ci-dessous). E8 workflow : terminé (0a6a33e). E7 modèle : accepté
(b739ed3). E2/E3 : fusionnés (4f41279). E1 : fusionné.

### Réconciliation (fusion de e8a75d9)

- **Dépôt et fichier** : base dev3 (`DepotController` → `DepotService` en deux
  temps, `ControleFichiers`, stockage chiffré, OCR) + contrôles E3 (Déposer sur
  le nœud du type, dossier archivé refusé). `objet` et `dateDocument` ajoutés au
  dépôt. `ServiceModeleDocument.appliquerAuDepot` au temps 1 (version du plan en
  vigueur, objet, date) ; `ServiceVersions.verser` au dépôt et au versement
  (numéro, auteur, courante unique, empreinte de dev3).
- **Validation des métadonnées, une seule fois** : `ValidationPlan` (dev3) au
  dépôt (`MetadonneesDepot`) et à l'indexation ; le miroir JSONB
  `document.metadonnees` est tenu par `ServiceModeleDocument.synchroniser`
  (normalisation par nature de `ValidateurMetadonnees`, sans revalidation) ;
  la modification de fiche et le changement de type passent par
  `ValidateurMetadonnees` (version de plan du document). Nature BOOLEEN ajoutée
  à `ValidationPlan`. Refus unique : `MetadonneesInvalidesException` (400
  `METADONNEES_INVALIDES` + `erreurs`), y compris au dépôt (`ErreurDepot.invalides`
  la rend : le gestionnaire des erreurs de fichier ne portait pas `erreurs`).
- **Une publication par action** : événements `document.evenement` de dev3 pour
  dépôt, versions (`VersionAjoutee`, `VersionRestauree`), verrou (`VerrouModifie`,
  avec motif), fiche (`MetadonneesModifiees`) ; `EvenementModeleDocument` pour
  le déplacement, la re-typologisation et le renommage seul (`DOCUMENT_RENOMME`).
  Les circuits écoutent `VersionAjoutee` / `VersionRestauree`.
- **Changesets** : ordre de l'intégration conservé, lots E7/E8 de dev1 ajoutés
  après (`202609301020_conservation…` à `202610021200_jalon_workflow`) ;
  `202609301045` (empreinte) MARK_RAN après `202609271205`, retour arrière vide
  (la colonne appartient à dev3). Jalons et retour arrière complet verts.
- **dev2** : `ErreurIdentite` → `ExceptionMetier` (`TropDeTentativesException`
  dérive de `TropDeRequetesException` : `Retry-After` par le gestionnaire
  commun) ; `GestionErreursIdentite`, `GestionErreursAutorisation`,
  `GestionErreursWorkflow`, `GestionErreursMetamodele` supprimés ;
  `ConflitAutorisationException` → `ConflitException` ;
  `ReponsesSecuriteProblem` branché dans `SecurityConfig` ; `AccesApiWorkflowCles`
  (`@Primary`) : délégation obligatoire pour une application (403
  `DELEGATION_REQUISE`), portée `WORKFLOW_PILOTAGE` / `WORKFLOW_DECISION` ;
  `EvenementWorkflow` implémente les vraies interfaces `EvenementAudit` et
  `EvenementNotifiable` (audit et notifications) ; codes E8 au catalogue
  `ActionAudit`. Dictionnaire OpenAPI (`champs.yml`) complété pour E7/E8.
- **Espace métier et clés d'API** : créer un dossier dans un espace métier
  reste réservé à la gestion des espaces (D12), sauf pour une application dont
  la clé porte `CREATION_DOSSIER` sur le parent (décision de l'Administrateur,
  §5.4) ; droits de la personne déléguée toujours exigés.
- Tests adaptés à la fusion : dépôt de métadonnées en partie JSON, `detail`
  au lieu de `message` (problem+json), versement 201, déclencheur de lecture
  seule des versions (falsification simulée en le désactivant), numéro de
  version dans les insertions SQL de dev3.

### Seconde fusion (4d28528 : dev3 T-040, ANO-E7-001, ANO-E5-002 ; dev2 contrat §5.3.1, ANO-E4-004, ANO-E1-005)

- Changesets du workflow renommés (demande du coordinateur, aucun n'était
  intégré) : `202610021000/1010/1020/1030` → `202610021100/1110/1120/1130`
  (fichiers, `logicalFilePath`, identifiants), exécutés après
  `202610021000_alignement_cles_uuid` de dev2 ; jalon `202610021200` inchangé.
  Références mises à jour (`ServiceCircuits`, `02_reprise.sql`).
  À signaler : `202610011000_reprise_liens_groupe_espace` (point 9) partage son
  horodatage avec `202610011000_portee_cles_api_noeud` (dev2) et
  `202610011000_ajout_source_depot_document` (dev3) — pas de collision
  Liquibase, renommable de la même façon si le coordinateur le souhaite.
- `DocumentResponse` : socle commun E7 + source du dépôt T-040 ; dépôt : origine
  (canal, application, déposant délégué) au temps 1 avec le modèle E7.
- Téléchargement d'une version : méthode de l'intégration conservée (contrat
  §5.3.1), passée par `LectureControlee` comme la version courante.
- `ServiceContratApi.creerDossier` : plus de règle copiée du parent (E8 : le
  dossier suit la règle du nœud le plus proche). `ContratApiTest.creationDeDossier`
  (dev2) adapté : aucune règle propre au dossier, et `ReglesApplicables` y
  résout la règle de l'espace (origine NOEUD).

### Correctifs de recette inclus

| Anomalie | Correction | Test |
|---|---|---|
| ANO-E7-002 (majeure) | `GardeEcriture.exigerModifiable` (409 `DOCUMENT_VERROUILLE` / `DOCUMENT_ARCHIVE`) sur rattacher, détacher, désigner, retirer une désignation | `AnomaliesAuditRecetteTest.archiveEnLectureSeule`, `.verrouille` |
| ANO-E4-001 (majeure) | `DocumentConsulte` (`DOCUMENT_CONSULTE`) publié à la lecture de la fiche (`GET /documents/{id}`), pas pour une relecture interne | `.consultationTracee` |
| ANO-E4-002 | `ControleAcces.horsPerimetre` : 404 inchangé pour le client, `ACCES_HORS_PERIMETRE` (REFUS, transaction propre) au journal si l'objet existe ; identifiant inexistant non tracé | `.horsPerimetreTrace` |
| ANO-E4-003 | Déconnexion tracée `DECONNEXION` ; `DESIGNATION_AJOUTEE`, `DESIGNATION_RETIREE`, `CONFIDENTIALITE_MODIFIEE`, `ACCES_HORS_PERIMETRE`, `DOCUMENT_RETYPE` et codes E8 au catalogue | `.designationAuCatalogue`, `AuthentificationApiTest.deconnexion` |

Tests : première fusion 561 verts ; seconde fusion **579 verts, 0 échec**, `ng build` vert. Point d'attention : la base de
développement `ged_dev1` a exécuté `202609301045` (empreinte) avant l'existence
de `202609271205` ; ce dernier y échouera (colonne déjà présente). La recréer
depuis la reprise, ou y marquer `202609271205-1` exécuté après avoir ajouté à
la main les colonnes manquantes (`cle_fichier_id`, `type_mime`, `taille_octets`).
Aucune autre base n'est concernée (les lots E7/E8 de dev1 n'étaient pas intégrés).

## E8 — exigences traitées (§12.8 ; MATRICE-FONCTIONNELLE §4.5 ; MATRICE-TECHNIQUE 12.8)

| Réf. | Exigence | Réalisation |
|---|---|---|
| 12.8 / 4.5.3 | Règle rattachable à un espace, un dossier ou un type ; la plus spécifique s'applique | `regle_workflow` sur `noeud` et `type_document` ; `ReglesApplicables` : type, puis nœud le plus proche en remontant (règle en corbeille ou sans validateur ignorée) ; `PUT /workflow/noeuds/{id}/regle`, `PUT /workflow/types/{id}/regle`, `GET /workflow/documents/{id}/regle` |
| 12.8 / 4.5.4 | Validateur nommé ou par rôle sur un périmètre | `regle_validateur.employe_id` XOR `role_id` (+ `perimetre_noeud_id`, contrainte en base) ; rôle résolu au moment de la décision (global, périmètre ou ancêtre, document) et permission Valider exigée |
| 12.8 / 4.5.4 | Règle modifiable, circuit figé au dépôt | `circuit` + `circuit_validateur` copiés dans la transaction du dépôt (`ServiceCircuits.ouvrirAuDepot`) ; test « circuit figé » |
| 12.8 / 4.5.3 (D7) | Décisions VALIDE / REFUSE (motif obligatoire) / ANNULEE, sans ordre, aucun facultatif | table `decision (circuit_validateur_id, version_id, decision, motif, cree_le, auteur_id, application_id)`, jamais modifiée ; `POST /workflow/circuits/{id}/decisions` |
| 12.8 / 4.5.3 | Statut recalculé sur la version courante ; versement = décisions caduques | recalcul dans la transaction de chaque décision et de chaque versement / désignation de version (écoute de `VERSION_AJOUTEE` / `VERSION_RESTAUREE`) ; `document.active` = circuit VALIDE |
| 12.8 (Q1/R27, D1) | Validateur défaillant signalé, réaffectation manuelle tracée | `GET /workflow/anomalies` (SANS_IDENTITE, SANS_DROIT, INACTIF > `ged.workflow.inactivite-jours`, AUCUN_PORTEUR) ; `PUT /workflow/circuits/{id}/validateurs/{v}` (Administrateur, motif, ancien validateur / auteur / date conservés) |
| 12.8 | Annulation par l'initiateur ou l'Administrateur, décisions conservées, nouveau circuit possible | `POST /workflow/circuits/{id}/annulation` ; `POST /workflow/documents/{id}/circuits` (409 `CIRCUIT_DEJA_OUVERT` / `AUCUNE_REGLE`) |
| 12.8 / 4.5.3 | Diffusion du document validé, sans copie | `POST /workflow/documents/{id}/diffusion` : habilitations de document au rôle `LECTEUR` (nouveau, Consulter) pour personnes et groupes ; Diffuser exigée ; 409 `DOCUMENT_NON_VALIDE` |
| 4.5.3 / 4.6.6 | Notification des validateurs et du déposant ; audit | `EvenementWorkflow` implémente `EvenementAudit` et `EvenementNotifiable` (copies conformes de dev2) : CIRCUIT_OUVERT, VALIDATION_RELANCEE, VALIDATION_APPROUVEE / REJETEE, DECISION_ANNULEE, CIRCUIT_ANNULE, VALIDATEUR_REAFFECTE, DOCUMENT_DIFFUSE, REGLE_WORKFLOW_RATTACHEE ; une publication par action |
| D8 (E8-API) | Pilotage par API, décision pour le compte d'un validateur | contrat publié ci-dessus ; `AccesApiWorkflow` (acteur délégué, portée PILOTAGE / DECISION) ; double identité (auteur + `application_id`) tracée |
| Reprise | Anciennes tables et signatures séquentielles | changesets `202610021100` (renommages), `202610021110` (circuit, décision), `202610021120` (signatures → circuits, retour arrière), `202610021130` (rôle LECTEUR), jalon `workflow-e8` ; scripts `02_reprise.sql` / `03_controles.sql` ; paquet `signature` supprimé |
| Écrans | Validation sans étapes, statut sur la fiche, règles, réaffectation | « Mes validations » (à traiter, historique, validateurs défaillants) ; carte « Validation » de la fiche document ; formulaire de règle nommé / par rôle ; règle du type sur sa fiche ; règle facultative du nœud |

Tests : `mvn test` → **344 tests, 0 échec** (dont `CircuitApiTest`, 12 cas,
et la migration des signatures dans `SchemaLiquibaseTest`) ; `ng build` vert.
Vérifié en exécution sur `ged_dev1` (backend 18081, interface 14301, arrêtés
ensuite) : migration appliquée (le circuit repris du document d'essai en
corbeille reste hors des listes), écrans « Mes validations », carte du
circuit, formulaire de règle par rôle.

Non fait / reste : fusion de `conformite-technique` et réconciliation dev3
(voir « Lot en cours ») ; un validateur nommé est un employé (les anciens
approbateurs n'avaient pas tous d'identité GED) ; la liste des personnes
proposée à la diffusion vient de l'écran d'administration (un non-administrateur
ne voit que les groupes).

## E7 (modèle) — exigences traitées

| Réf. | Exigence | Statut proposé | Preuve |
|---|---|---|---|
| 12.7 (T-102) | Méta-modèle : nature booléenne, obligatoire, défaut, liste, recherche | Identique | `IndexFieldType.BOOLEEN` (ck en base, OCR : jamais déduit), `ValidateurMetadonnees` (nature, obligatoire, liste, défaut, appartenance au plan). |
| 12.7 (T-104) | Métadonnées JSONB validées, GIN, index d'expression, recherche | Identique | `document.metadonnees` normalisé par code d'index (nombre JSON, booléen JSON, date ISO) ; validé au dépôt et à la modification (400 `METADONNEES_INVALIDES` + dictionnaire `erreurs`) ; dépôt sans métadonnées = deux temps (§12.11), obligatoires exigés à l'indexation. Fonctions IMMUTABLE `meta_texte` / `meta_nombre` / `meta_date` : support des index d'expression (un changeset par champ fréquent, procédure `DEPLOIEMENT.md` §8). `POST /documents/recherche` : containment `@>` (GIN) pour liste et booléen, bornes pour date et nombre, texte contient ; **date du document en tri prioritaire** ; périmètre par le prédicat SQL d'`AccessPredicate`. |
| 12.7 (T-105) | Type : conservation, confidentialité par défaut, plan versionné, `RESTRICT`, re-typologisation | Identique | `duree_conservation_mois`, `point_depart` (DATE_DOCUMENT / DATE_DEPOT / METADONNEE + index date du plan, contrôlé) ; `plan_indexation_version` (définition figée JSONB ; version créée à chaque modification d'un plan, d'un index ou d'un type ; le document référence la version de son dépôt et est validé contre elle) ; FK `document → type_document` RESTRICT + 409 `TYPE_UTILISE`, `actif` (désactivé = plus de dépôt) ; `job_retypage` (correspondance ancien → nouveau champ, un document par transaction, verrouillés / archivés / invalides en échec isolé, déplacement vers le dossier du type cible, rapport, événement `DOCUMENT_RETYPE` par document, prise atomique). |
| 12.7 (P-21) | Socle commun : objet, date du document, confidentialité, conservation déduite du type | Identique | Colonnes `objet`, `date_document` (reprise = date de dépôt), `echeance_conservation` calculée **par la base** (déclencheurs) au dépôt, au changement de type, de date ou de métadonnée, et pour tous les documents du type quand sa durée ou son point de départ change. |
| 12.8 (T-106) | Versions : numéro, empreinte, auteur, une seule courante ; D9 ; Q6 | Identique | `numero`, `auteur_id`, `empreinte` (SHA-256 calculé au versement ; colonne de dev3 réutilisée : changeset gardé `MARK_RAN` si elle existe) ; `is_default` → `courante` avec **index unique partiel** `uk_version_document_courante` ; `ServiceVersions` (démission écrite avant promotion) ; D9 : la nouvelle version devient courante, l'ancienne reste en lecture seule (déclencheur `trg_version_document_lecture_seule`) ; désignation d'une ancienne version avec Modifier (Q6 : V3) ; téléchargement de toute version. |
| 12.8 (T-107) | Verrou : auteur, date, motif ; 409 partout ; Administrateur ; audit | Identique | `verrou_par`, `verrou_le`, `verrou_motif` (ck) ; pose / levée par l'Administrateur (rôle global), 403 sinon ; `GardeEcriture` : 409 `DOCUMENT_VERROUILLE` (motif dans le message) sur fiche, métadonnées, versement, version courante, déplacement, rattachement, suppression, réindexation (routes indexation par l'intercepteur) et archivage (contrat dev3) ; 409 `DOCUMENT_ARCHIVE` pour un document archivé. Événements `DOCUMENT_VERROUILLE` / `DOCUMENT_DEVERROUILLE`. |
| 12.5 (T-098) | Déplacement de document et de dossier, droits, 409 verrouillé, audit | Identique | Dossier : chemin de la sous-arborescence recalculé par la base (E3) ; document : `PATCH /documents/{id}/emplacement` et changement de type — Déplacer sur l'origine, Déposer sur la destination, 409 si verrouillé ou archivé, rattachement doublon retiré, événement `DOCUMENT_DEPLACE` avec origine et destination. L'audit des déplacements de dossiers est celui de dev2 (`ESPACE_DEPLACE`, vague 2). |
| 12.5 (P-20) | Renommage : Modifier, unicité dans le dossier (409), audit | Identique | Documents (même emplacement principal) et nœuds (même parent) : 409 `NOM_DEJA_UTILISE` ; `DOCUMENT_RENOMME` avant / après. |
| 12.6 (T-101, D10) | Drapeau d'archivage sur documents et nœuds | Identique pour le modèle | `statut_conservation`, `archive_le`, `archive_par` sur `noeud` et `document` ; contrats `ArchivageNoeuds` / `ArchivageDocuments` livrés en premier (voir plus haut) ; le traitement (PDF/A, job) est au lot de dev3. |
| R-03 (D12) | Espace de partage simple | Identique | `noeud.usage_espace` METIER / ECHANGE, hérité par les dossiers (déclencheurs) ; en espace d'échange, Déposer sur le parent suffit pour créer dossiers et sous-dossiers ; en espace métier, gestion des espaces requise ; aucune édition en ligne (télécharger, modifier localement, verser). |

Tests : `mvn test` → **337 verts** (dont `ModeleDocumentApiTest` 11, `RetypageTest`,
`ContratArchivageTest` 3 ; `SchemaLiquibaseTest` : jalon `modele-e7` et retour
arrière complet ; reprise adaptée : numéros, une version courante, verrou daté).
Front : build et tests verts. Vérifié en exécution sur `ged_dev1` (données E3
migrées, backend 18081 arrêté ensuite) : dépôt avec objet et date, version 1 avec
empreinte, verrou avec motif, 409 sur écriture, recherche triée par date.

Pour dev3 à la fusion : `DocumentService` a été modifié des deux côtés (versement
→ appeler `ServiceVersions.verser`, métadonnées au dépôt →
`ServiceModeleDocument.appliquerAuDepot`) ; `ValidationPlan` (dev3) et
`ValidateurMetadonnees` (dev1) font la même validation : garder la mienne (versions
de plan, booléen, normalisation) et y brancher `MetadonneesDepot` ; les événements
de dev3 `VersionAjoutee`, `VersionRestauree`, `VerrouModifie` doublonnent ceux du
lot modèle (versions et verrou sont à dev1) : n'en garder qu'une publication par
action. Changeset `202609301045` (empreinte) : `MARK_RAN` une fois `202609271205`
présent — ne jamais réordonner ces deux fichiers.

Tests en parallèle : le simulateur d'annuaire des tests écoute sur 33390 par
défaut ; deux copies de travail qui testent en même temps prennent des ports
différents par `GED_IDENTITE_ANNUAIRE_EMBARQUE_PORT` et
`GED_IDENTITE_ANNUAIRE_URLS` (dev1 : 33391 ; l'ancienne variable
`GED_TEST_ANNUAIRE_PORT` est retirée à la fusion).

**Point 9 — TRANCHÉ par le coordinateur (recommandation adoptée), livré en
325716f** : changeset `202610011000` (rapport `reprise_lien_groupe_espace`,
retrait des habilitations de groupe issues de la reprise, retour arrière),
script de reprise, `DEPLOIEMENT.md` §8, rapport affiché dans l'écran
Habilitations (`GET /api/v1/admin/reprise/liens-groupes`), tests de migration
depuis l'état E2 et de non-régression (Administrateur global membre d'un groupe
repris : suppression 204). Appliqué à `ged_dev1` ; le document d'essai y est
passé en corbeille (la purge, définitive, relève du lot de dev3).

Constat d'origine — droits des administrateurs sur les espaces des anciens
groupes. Constaté sur `ged_dev1` : `sbennani`,
Administrateur de portée globale et membre du groupe repris AG-ADMIN, reçoit
**403 en supprimant un document** d'un espace que ce groupe « couvrait » — la
reprise a fait de chaque lien groupe / espace une habilitation « Utilisateur
standard », et la règle « le plus spécifique prévaut » (§12.2.2, D14) la fait
passer devant sa portée globale. Or dans l'ancienne application ces groupes
**n'autorisaient rien** (commentaire d'origine : « il ne conditionne aucune
autorisation ») : la reprise crée une restriction qui n'existait pas.
Recommandation : **ne pas convertir `access_group_workspace` en habilitations**
dans le changeset 202609281030-2 ; conserver groupes et membres, et consigner les
anciens liens groupe / espace dans un rapport (table ou export) pour que
l'Administrateur pose lui-même les habilitations voulues depuis l'écran. À
défaut, variante minimale : ne créer ces habilitations que pour les groupes
dont aucun membre n'a de rôle global. La correction est un changeset de plus
(le changeset appliqué ne se modifie pas) et une ligne du script de reprise.

## E3 — exigences traitées (Réf. de MATRICE-TECHNIQUE.md, lignes T/P de SUIVI.md)

| Réf. | Exigence | Statut proposé | Preuve |
|---|---|---|---|
| 12.2 (T-095) | Permissions, rôles, groupes GED, habilitations sur nœud ou document, héritage, rupture, cumul, document isolé, `acces_global` (D14) | Identique | Tables `permission` (18, data-initial), `role_permission` (composition des 4 rôles système, D14), `groupe_ged` / `groupe_membre` (reprise d'`access_group*`), `habilitation` (sujet utilisateur / groupe / application, cible nœud / document / globale, rupture ; contraintes `ck_habilitation_*`, unicité). `ResolveurDroits` : plus spécifique prévaut, rupture sans rôle, union au même niveau, document isolé additif, Direction Générale par code sans ligne par nœud, administration en portée globale seulement. `ResolveurDroitsTest` (11), `PermissionsLivreesTest`. Rôles composables par API et écran ; Administrateur non recomposable (anti-verrouillage). |
| 12.2.3 (T-072) | Point d'application unique `AccessPredicate` | Identique | `AccessPredicate` : nœuds accessibles par permission, `peut(sujet, permission, document)` (union des emplacements ∧ confidentialité), spécification JPA et fragment SQL « à la source », cache par sujet borné (`GED_AUTORISATION_CACHE_TAILLE`) invalidé par `version_habilitations` (compteur tenu par **déclencheurs** sur nœuds, habilitations, groupes, rôles ; séquence : une transaction annulée ne peut pas faire resservir des droits). Appliqué aux listes, totaux, fiche, téléchargement, versions, verrou, corbeille, arbre, compteurs du tableau de bord, recherche par index, circuits, profil, OCR, indexation, aperçu, recherche plein texte (dev3). `ArchitectureDroitsTest` : aucune lecture de documents en masse hors `AccessPredicate`, aucun contrôleur sur les dépôts de documents. |
| 6.2.3 (T-067) | A01 : refus par défaut, 404 hors périmètre | Identique | `ControleAcces` : 404 (`HorsPerimetreException`, même message qu'un identifiant inexistant) hors périmètre, 403 sur objet visible sans la permission. `CheminsAccesApiTest.horsPerimetre404` sur 15 routes. `/api/v1/admin/**` : rôle Administrateur **de portée globale** (autorités `ROLE_` des seules habilitations globales) + `GERER_ROLES_HABILITATIONS`. Écritures de référentiels : `GERER_REFERENTIELS` ; groupes : `GERER_ROLES_HABILITATIONS` ; espaces : `GERER_ESPACES` ou permission sur le nœud. |
| 12.2.3 / P5 | Arborescence : nœud couvert ou ancêtre (libellé de passage) ; compteurs au périmètre | Identique | `GET /workspaces/tree` : `passage=true`, statut masqué, aucun document ni action ; listes, sélecteurs, `childrenCount`, tuiles et répartitions au seul périmètre. `CheminsAccesApiTest.arbreDePassage`, `compteurs`. |
| 12.3 (T-096) | Confidentialité PUBLIC / PRIVE / CONFIDENTIEL, personnes désignées | Identique | `document.confidentialite` NOT NULL (ck), `type_document.confidentialite_defaut`, `document_confidentiel_designe` ; intersection avec les droits d'emplacement ; `VOIR_PRIVE` (Agent d'archive, Administrateur, DG), `VOIR_CONFIDENTIEL` (Administrateur, DG) ; déposant désigné par défaut au dépôt et au passage en CONFIDENTIEL ; désignation et retrait à effet immédiat, audités. `CheminsAccesApiTest.prive`, `confidentiel`, `niveauParDefautEtChangement`. |
| 12.4 (T-097) | Rattachement multiple | Identique pour E3 (table, droits, suppression) | `document.noeud_principal_id`, `document_rattachement` (uk document / nœud) ; droits en union ; emplacement affiché = principal s'il est accessible, sinon premier accessible ; suppression depuis le principal = corbeille (invisible partout), depuis un rattachement = retrait de la ligne ; ajout / retrait (`POST` / `DELETE /documents/{id}/rattachements`) avec écriture sur l'origine et la destination, refus du principal et des doublons (409), événements `RATTACHEMENT_AJOUTE` / `RATTACHEMENT_RETIRE`. Export ZIP : lot E7 (dev3). |
| P-22 | Droits effectifs avec origine ; modification immédiate et auditée | Identique | `GET /api/v1/admin/droits-effectifs` + écran : permissions, origines (attribution directe, héritage, portée globale, accès global, rupture, document isolé, groupe porteur), emplacements, verdict de confidentialité. Événement `HabilitationModifiee` (avant / après) pour habilitations, rôles, membres et corbeille des groupes. `CheminsAccesApiTest.attributionImmediate`, `droitsEffectifs`, `groupe`, `compositionDeRole`. |
| 5.3.2 (T-050) | Pagination : 50 par défaut, plafond 200, total au périmètre | Identique | Taille par défaut 50 sur toutes les listes (`Tri.TAILLE_DEFAUT`), plafond 200, total calculé sur le périmètre. `CheminsAccesApiTest.paginationAuPerimetre`. |
| 12.5 (T-099) | Cascade de la corbeille sur la sous-arborescence | Identique pour les nœuds | Suppression d'un nœud = nœud et descendants, même auteur et même horodatage ; restauration de ce seul lot. |
| 7.4.3 | Consultation du journal réservée (Administrateur, DG) | Identique | Bean `GardeConsultationAudit` (contrat de dev2) fondé sur `CONSULTER_AUDIT`, portée par Administrateur et Direction Générale. |

**Points d'extension livrés** (tous `@Primary` dans
`autorisation/extension/ConfigurationAutorisation` : les implémentations
provisoires des propriétaires restent en place et cèdent le pas, sans
modification de leurs fichiers) :

- `recherche.PredicatDroits` (dev3) : `predicat(col, utilisateur)` renvoie
  `EXISTS (SELECT 1 FROM document droits_d WHERE droits_d.id = <col> AND
  droits_d.supprime = false AND <emplacements> AND <confidentialité>)`. Paramètres
  préfixés `droits_` ; les ensembles de nœuds et de documents passent en **un
  seul paramètre tableau** (`CAST(:droits_noeuds AS uuid[])`), quelle que soit
  leur taille ; `FragmentSql.FAUX` sans appelant reconnu. Interface et
  `FragmentSql` copiées à l'identique de `ct/dev3`.
- `fichier.previsualisation.ControleAccesPrevisualisation` (dev3) : même
  décision que le téléchargement ; hors périmètre, `Refus.introuvable("version …")`
  — réponse identique à une version inconnue.
- `autorisation.SourceHabilitations` (dev2, clés d'API, vague 4) : une
  application est un sujet (`TypeSujet.APPLICATION`). Déclarer un bean qui
  reconnaît l'`Authentication` de la clé (`sujet(...)`) et fournit ses
  attributions (`attributions(sujet)`, portée par espace traduite en liste de
  permissions) — ou poser des lignes `habilitation` de sujet APPLICATION, déjà
  servies. Toute modification de portée hors des tables surveillées appelle
  `VersionHabilitations.incrementer()`. La clé étrangère `application_id` sera
  posée avec la table `application`.
- `audit.EvenementAudit` / `audit.GardeConsultationAudit` (dev2) : copiés à
  l'identique de `ct/dev2` ; `ConnexionReussie`, `ConnexionEchouee`,
  `SessionsRevoquees`, `HabilitationModifiee` (action `HABILITATION_MODIFIEE`,
  avant / après) et `AccesDocumentModifie` (rattachements, confidentialité,
  désignations) les implémentent. Les composantes `motif` des événements
  d'identité sont renommées `motifEchec` / `motifRevocation` (le contrat
  réserve `motif()` au texte).
- Routes des modules OCR et indexation (dev3) protégées sans modifier leurs
  contrôleurs : `GardeDroitsRequetes` (intercepteur MVC hors `SecurityConfig`).

À faire à la reprise des fusions (merge de `conformite-technique`) :
brancher `ReponsesSecuriteProblem` dans `SecurityConfig` (entry point et access
denied handler) ; convertir `HorsPerimetreException` → `RessourceIntrouvableException`,
`PermissionRefuseeException` → `AccesRefuseException`, `ConflitAutorisationException`
→ `ConflitException`, supprimer `GestionErreursAutorisation` et
`GestionErreursIdentite` ; rejouer `preparer-base.sql` (unaccent) ; retirer du
test `PrevisualisationApiTest` de dev3 les documents fictifs s'il a changé (il
pointe désormais sur des documents réels, `support/JeuDroits`).

Choix à connaître :
- `workspace` → `noeud` et `access_group*` → `groupe_ged` / `groupe_membre` au
  niveau des **tables** ; les classes Java et chemins d'API (`WorkSpace`,
  `/workspaces`, `AccessGroup`, `/access-groups`) gardent leur nom pour ne pas
  imposer un renommage à toute l'interface pendant la vague.
- Reprise E2 → E3 : `utilisateur_role` → habilitations globales ;
  `access_group_workspace` → habilitations de groupe sur le nœud, rôle
  Utilisateur standard. **Conséquence** (règle « plus spécifique ») : un
  Administrateur membre d'un tel groupe n'a, sur ces espaces, que les droits
  standard ; à revoir dans l'écran Habilitations après la montée (signalé dans
  `DEPLOIEMENT.md` §8). Le jeu de démonstration `dev` donne désormais le rôle
  Administrateur au groupe AG-ADMIN.
- Chemin matérialisé tenu par déclencheurs (`trg_noeud_chemin*`), anti-cycle
  compris ; la reprise MySQL insère les nœuds niveau par niveau.

Tests : `mvn test` → **322 tests, 0 échec** (dont 40 nouveaux : résolution,
chemins d'accès, architecture, données initiales). Front : build et tests verts.
Vérifié en exécution sur `ged_dev1` (données E2 migrées : 1 rôle global et 7
rattachements de groupes repris, chemins calculés) avec le backend sur 18081 et
l'interface : attribution d'un premier rôle à une identité sans rôle depuis
l'écran Habilitations, arbre de passage, 403 sur l'administration, retrait
immédiat. Simulateur seulement : annuaire (UnboundID).

Non fait / hors lot : export ZIP et API REST complète des rattachements (E7,
dev3/dev1) ; renommage des classes `WorkSpace` / `AccessGroup` ; filtrage des
types de document proposés au dépôt selon Déposer (le serveur refuse, l'écran
propose encore tous les types).

## E2 — exigences traitées (Réf. de MATRICE-TECHNIQUE.md, lignes T/P de SUIVI.md)

| Réf. | Exigence | Statut proposé | Preuve |
|---|---|---|---|
| 3.2 (T-010) | Aucun référentiel local de mots de passe | Identique | Table `compte_utilisateur`, BCrypt, `CompteSeeder`, mot de passe d'amorçage supprimés (changeset 202609271025 avec rollback) ; `SchemaLiquibaseTest.verifierAucunMotDePasse` ; reprise : comptes ni exportés ni repris. |
| 3.3 (T-011) | LDAPS search-then-bind, compte de service, Spring Security LDAP ; D2 : `sAMAccountName` seul | Identique (simulateur) | `identite/annuaire/AnnuaireLdap` (BindAuthenticator + FilterBasedLdapUserSearch, filtre figé dans le code) ; e-mail/UPN refusés (400 et refus annuaire) ; mot de passe jamais journalisé (test par capture des journaux). |
| 3.3 (T-012) | Provisionnement automatique sans rôle, clé objectGUID | Identique (simulateur) | `ServiceIdentites` ; table `utilisateur` (uk objectGUID) ; renommage sans doublon ; rattachement aux fiches employé reprises par courriel dérivé. |
| 3.3 (T-013) | Jeton d'accès court sans permission | Identique | 15 min, `sub`/`uid`/`sid`, aucun rôle (`ServiceJetonTest`). |
| 3.4.1 (T-014) | JWT RS256, clé privée en coffre, mémoire côté Angular | Identique | Magasin PKCS#12 hors dépôt (refus de démarrer sans) ; `alg none`, HS256, autre clé, durée excessive refusés ; Angular : plus aucun `localStorage`/`sessionStorage` pour le jeton. |
| 3.4.1 (T-015) | Renouvellement en cookie httpOnly, 8 h, table session, révocation | Identique | Cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` ; empreinte SHA-256 en table `session` ; rotation ; réutilisation = famille révoquée ; inactivité 30 min ; durée absolue paramétrable (`GED_SESSION_DUREE_ABSOLUE`, 4 h recommandées, R26) ; déconnexion et révocation Administrateur immédiates (filtre qui vérifie la session à chaque requête) ; écran Angular « Sessions ». |
| 3.4.1 (T-016, P-03) | CSRF : jeton dans `Authorization` seul ; en-tête personnalisé sur le renouvellement | Identique | Jeton en paramètre ou cookie refusé ; `X-GED-Renouvellement` exigé sur `/refresh` et `/logout` par cookie (403 sinon). |
| 3.4.1 (T-017) | 5 essais/min par IP et par identifiant, échecs journalisés | Identique | `LimiteurConnexions` (fenêtre glissante, mémoire bornée) ; 429 + `Retry-After` ; événements `ConnexionEchouee` / `ConnexionReussie` / `SessionsRevoquees` publiés pour le journal d'audit (dev2). |
| 3.4.2 (T-018) | Cache annuaire 15 min ; D1 : aucune lecture de `userAccountControl` | Identique (simulateur) | Table `cache_annuaire` ; `userAccountControl` exclu même s'il est configuré ; compte désactivé = liaison refusée par l'annuaire simulé (code 49/533). |
| P-01 | Attributs minimaux, jamais d'appartenance | Identique | Liste configurable expurgée de `memberOf`, groupes, UAC, UPN ; LDIF de test portant `memberOf` pour prouver qu'il n'est pas lu. |
| P-02 | LDAPS, TLS 1.2+, truststore, 3 s / 5 s, pool, secret à chaud, N contrôleurs (D4), annuaire indisponible | Identique (simulateur) | `ldap://` refusé hors dev/test ; `FabriqueSocketsLdaps` (TLS 1.2/1.3, magasin propre à l'annuaire, nom d'hôte vérifié — testé : certificat inconnu ou nom faux refusés) ; bascule testée ; 503 `ANNUAIRE_INDISPONIBLE` ; sonde `annuaire`. |
| P-04 | Accueil vide sans rôle ; D1 | Identique | `/auth/me` seul accessible sans rôle (403 ailleurs) ; page d'accueil vide Angular ; rôles relus à chaque requête. |
| 4.2.1 (T-020) | Amorçage par migration | Identique | Les quatre rôles système en changeset `labels="data-initial"`. |

Correctifs ajoutés à la vague : ANO-E1-001 (clé `id` UUID + `uk_` sur les tables
d'association, fonction `uuid_v7()` en base), ANO-E1-002 (`deleted` renommé
`supprime` : colonnes, index, entités, requêtes, DTO, reprise), `sslmode`
(`verify-full` en uat/prod), Liquibase désactivé au démarrage en uat/prod,
contrôles « prod » étendus à `uat` (Swagger, CORS). Migrations E2 appliquées avec
succès sur une base reprise peuplée.

Tests : `mvn test` → **282 tests, 0 échec**.

Vérifié uniquement par simulateur : tout ce qui touche l'annuaire (UnboundID en
mémoire ; LDAPS avec certificat autosigné) — aucun contrôleur de domaine réel.
Non fait : écrans d'attribution des rôles (lot E3) ; `server.forward-headers-strategy`
pour l'IP réelle derrière NGINX (configuration d'exploitation, dev2) ; le contrôle
« antivirus obligatoire en prod » de dev3 (`fichier/ConfigurationFichiers`) ne
couvre pas encore `uat`.

## E1 — exigences traitées (références « Réf. » de MATRICE-TECHNIQUE.md)

| Réf. | Exigence | Statut proposé | Preuve |
|---|---|---|---|
| 2.2 | SGBD PostgreSQL 16 ou plus, configuration arabe vérifiée | Identique | H2 et MySQL retirés ; PostgreSQL dans dev, test, prod. Prérequis du changelog maître (version ≥ 16, `pg_ts_config` `arabic`) bloquant ; `SocleDonneesTest.stackPostgresql` (dont `to_tsvector('arabic', …)`). |
| 2.2 | Outil de migration Liquibase 4 | Identique | Flyway retiré ; `liquibase-core` 4.29 ; `db/changelog/db.changelog-master.xml`. |
| 4.2.1 | Aucune modification de schéma hors migration versionnée | Identique | `ddl-auto: validate` dans tous les profils ; `ged_app` sans aucun droit DDL (`SocleDonneesTest.compteApplicatifSansDdl`, SQLSTATE 42501). |
| 4.2.1 | Amorçage initial par migration, référentiels métier via l'interface | Proche | Jeux de démonstration (espaces, types, index, plans, étiquettes, groupes, employés) limités au profil `dev` : plus rien d'amorcé en prod. Convention `labels="data-initial"` posée. Restent : `CompteSeeder` (Java, supprimé par E2) ; aucune donnée de référence technique n'existe encore (permissions, rôles système, niveaux de confidentialité arrivent en E3, en changesets `data-initial`). |
| 4.2.2 | Conventions de nommage (snake_case, idx_, uk_, fk_) | Identique | 1 fichier par évolution `AAAAMMJJHHmm_objet_metier.xml` ; tables renommées au singulier pour que chaque FK soit `<table>_id` (`document`, `version_document`, `type_document`, `index_def`, `plan_indexation`, `plan_index`…) ; `pk_ uk_ fk_ ck_ idx_` vérifiés par `SchemaLiquibaseTest`. |
| 4.2.2 | Retour arrière explicite par changeset, expand / contract | Identique (hors exécution UAT) | `<rollback>` explicite sur chaque changeset ; `SchemaLiquibaseTest` : montée complète sur schéma vierge, retour arrière de TOUS les changesets, remontée ; jalon `socle-e1`. Procédure expand/contract et retour arrière CLI dans `DEPLOIEMENT.md` §8. Pas d'UAT disponible pour l'exécution « en UAT » (E0). |
| 4.2.3 | Trois rôles PostgreSQL | Identique | `backend/scripts/db/creer-roles.sql` (idempotent, jamais de DROP ni de changement de mot de passe) + `preparer-base.sql` (par base : schémas `ged` / `ged_liquibase`, privilèges par défaut). Liquibase en `ged_owner`, application en `ged_app` (vérifié en test, en dev et en prod). |
| 12.1 | Clés primaires UUID | Identique | Toutes les entités en UUID v7 (`IdentifiantUuid`, `UuidV7`), DTO, contrôleurs, dépôts, tests, front. Vérifié en base par `SchemaLiquibaseTest.verifierClesUuid`. |
| 12.1 | Modèle logique en sept groupes de tables | Proche | Tables existantes alignées sur les noms du §12.1 quand la correspondance est directe. Les tables des autres groupes (utilisateur, session, habilitation, noeud, journal_audit…) relèvent des lots E2 à E9. |
| 5.3.2 | JSON UTF-8, dates ISO 8601 UTC, identifiants opaques UUID | Identique pour les identifiants | Identifiants d'API = UUID (chaînes) ; identifiant mal formé → 400 au format commun. Format des dates non modifié par ce lot. |
| 12.5 | Suppression douce avec auteur et date | Identique (suppression douce) | `supprime_par` / `supprime_le` sur les 8 tables à corbeille, renseignés à la suppression (simple et multiple), vidés à la restauration ; contrainte `ck_<table>_suppression`. `SocleDonneesTest.suppressionDouceAvecAuteurEtDate`. Purge : lot E7. |
| 12.7 | Métadonnées en JSONB avec index GIN | Proche | `document.metadonnees` JSONB NOT NULL `{}` + `idx_document_metadonnees` GIN + `ck_document_metadonnees` ; mappé dans l'entité. Pas encore alimenté (lot E7). |

Tâche E1 « tests d'intégration sur PostgreSQL réel » : faite sur PostgreSQL local
(`ged_dev1_test`), sans Testcontainers (pas de Docker sur le poste, cf. brief).

## Livrables annexes

- Reprise des données : `backend/scripts/reprise/` (export MySQL, transit, transfert en
  une transaction avec table de correspondance ancien id → UUID, contrôles),
  documentée dans `DEPLOIEMENT.md` §7 ; `RepriseDonneesTest` sur un export d'essai.
- Front Angular : identifiants en chaînes UUID, `ng build` OK ; jeu de démonstration
  converti.
- Documentation : `LISEZ-MOI.md`, `DEPLOIEMENT.md`, `backend/.env.example`.

## Tests

`mvn test` : 156 tests, 0 échec (143 existants adaptés + 13 nouveaux :
`SchemaLiquibaseTest` 2, `SocleDonneesTest` 7, `RepriseDonneesTest` 1, `UuidV7Test` 3).

## Ce qui reste / points d'attention pour l'intégration

- Tables renommées (`documents_file` → `document`, `document_versions` →
  `version_document`, `indices` → `index_def`, etc.) : tout travail parallèle
  qui écrit du SQL natif ou une migration sur les anciens noms doit être rebasé
  sur ce changelog. Flyway n'existe plus : toute nouvelle évolution est un
  changeset Liquibase.
- `StorageService.store(...)` : seule modification, le type du paramètre
  `workspaceId` (`Long` → `UUID`) ; rien d'autre n'a été touché au stockage.
- Chaque membre doit exécuter `creer-roles.sql` (déjà fait sur ce serveur, rôles
  partagés) puis `preparer-base.sql -v base=<sa base>` (et `-v tests=oui` pour sa
  base de test).
- Exécution du retour arrière « en UAT » : dépend de l'environnement UAT (E0).

## Vérifié uniquement hors cible

- `exporter-mysql.sh` n'a pas été exécuté : pas de serveur MySQL sur le poste. Le
  reste de la chaîne de reprise a été exécuté sur un export d'essai au même format.
- Mots de passe des rôles : poste en authentification `trust` ; le chemin « mot de
  passe fourni » de `creer-roles.sql` n'a pas été exercé.
