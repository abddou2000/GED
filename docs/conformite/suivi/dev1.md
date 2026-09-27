# Suivi — dev1

## Lot en cours

**E3 — Autorisation et confidentialité (vague 3)** : terminé côté développement,
branche `ct/dev1`, en attente d'intégration (fusions suspendues). E2 : accepté
(ea2f936), non encore fusionné. E1 : fusionné par pm (fbb951c).

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
