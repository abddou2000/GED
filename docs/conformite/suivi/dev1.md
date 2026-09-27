# Suivi — dev1

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

**E7 — partie modèle (vague 4)** : terminé côté développement, branche
`ct/dev1`, en attente d'intégration (fusions suspendues). E3 : accepté
(483eb40). E2 : accepté (ea2f936). Aucun des deux n'est encore fusionné.
E1 : fusionné par pm (fbb951c).

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

Tests en parallèle : le simulateur d'annuaire des tests écoute sur
`GED_TEST_ANNUAIRE_PORT` (défaut 33390) ; deux copies de travail qui testent en
même temps doivent prendre des ports différents (dev1 : 33391).

**Point à trancher (demandé par le coordinateur) — droits des administrateurs
sur les espaces des anciens groupes.** Constaté sur `ged_dev1` : `sbennani`,
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
