# Suivi dev4 — écrans d'administration et d'exploitation

Branche `ct/dev4-r1`, partie de `claude/inspiring-lovelace-10bg1c` (`71bdc1d`). Front seul :
aucun changement du back ni de Liquibase dans ce tour.

**Tests front** : `cd frontend && npx ng test --watch=false` (Vitest + jsdom, constructeur
`@angular/build:unit-test`). La CLI Angular 22 exige Node ≥ 22.22.3 ; le poste a 22.22.2 : les
commandes ont été lancées avec le Node 24.21.0 déposé par dev5 dans le répertoire de travail de
l'équipe (`dev5-node/`), placé en tête du `PATH`.

## Tour 1

| Id | Cause | Correctif | Preuve (test qui échoue sans le correctif) | État |
|---|---|---|---|---|
| ANO-F-003 | Routes d'administration déclarées sans `canActivate` (« garde à brancher avec E3 ») | `permissionGuard` (mécanisme de `traitements-ocr`) : index, plans (liste, création, édition), types (liste, fiche), étiquettes, règles de workflow → `GERER_REFERENTIELS` ; groupes (liste, fiche) → `GERER_ROLES_HABILITATIONS` ; journal d'audit → `CONSULTER_AUDIT` ; clés d'API → `GERER_CLES_API`. Retour à l'accueil. Le profil n'offre plus le lien vers la fiche d'un groupe à qui ne peut pas l'ouvrir. | `app.routes.spec.ts` : 12 écrans × (refus sans la permission, ouverture avec) + écrans utilisateur toujours ouverts ; 12 échecs avant correctif | Corrigée (`33fdc4e`) |
| ANO-F-007 | `index.model.ts` ne connaissait que 4 natures ; le libellé retombait sur la valeur brute | Nature « Booléen » proposée ; libellé, glyphe et teinte dans la liste ; valeur par défaut choisie (Aucune / Oui / Non), écritures admises par le serveur ramenées à `oui`/`non` ; `TypeIndex` (indexation) accepte `BOOLEEN` | `index.model.spec.ts` (ne compile pas avant correctif : `'BOOLEEN'` hors du type) | Corrigée (`a0f0c76`) |
| ANO-F-008 | Libellés repris de l'application d'origine | « Nom de l'espace de travail » ; dans le formulaire de groupe : « Nom du groupe », « Utilisateurs et espaces de travail », « Espaces de travail ». Relevé scripté de tous les gabarits (texte, `placeholder`, `aria-label`, `matTooltip`, `title`) : aucun autre libellé anglais visible. | `features/workspace/libelles-francais.spec.ts` : rend les deux formulaires ; 2 échecs avant correctif | Corrigée (`2ec6155`) |
| T-088 (2) | Le front ignorait `GET /api/v1/modules` | `ModulesService` (chargé une fois ; inconnu, en panne ou mode démonstration = actif) ; `moduleGuard` sur `mes-workflow`, `regles-de-workflow` (workflow), `recherche`, `traitements-ocr` (ocr), `mes-exports` (export), `notifications` (notifications), `cles-api` (integration) ; menus et cloche masqués ; plus de compteur « à traiter » ni de relevé des notifications vers un module inactif ; raccourcis de l'accueil filtrés | `core/modules.service.spec.ts`, `app.routes.spec.ts` (7 routes × actif/inactif + socle), `layout/shell/shell.spec.ts` (menus rendus) ; 9 échecs avant correctif | Livré (`feb4e6e`), à recetter |

Résultat : `ng test` 70 tests verts (18 au départ) ; `ng build` vert (avertissements de budget
préexistants). Suite back (`mvn -B -q test`, base `ged_dev4_test`, aucun fichier du back
modifié) : 597 tests, 5 échecs — les 4 de la référence (`ArchivageApiTest.conversionEnEchec`,
`ApercuTelechargementApiTest.apercuBureautiqueSansLibreOffice`,
`WorkflowApiTest.employesWithAccount`, `WorkSpaceApiTest.moveIntoDescendant`) et
`SupervisionIntegrationTest.portDeManagement`, qui attend 8081 alors que `equipe-env.sh dev4`
exporte `GED_MANAGEMENT_PORT=18096` : échec dû à l'environnement du poste, pas au code.

## Points pour pm

- **T-088** : critère (2) du P1 livré côté front. Le module « cycle de vie » n'a ni menu ni route
  propre : ses actions (archiver, purger, conservation) vivent dans la fiche document et la fiche
  d'espace (écrans utilisateur, composant `cycle-dossier`, qui porte aussi l'export ZIP du module
  « export ») et ne sont pas masquées ; le serveur répond 404 `MODULE_INACTIF`.
  Idem pour les encarts « À valider » et « Mon activité » de l'accueil et le circuit affiché dans
  la fiche document quand le workflow est inactif. À confier à dev5 si le masquage doit aller
  jusque-là.
- **Raccourci « Rechercher par index »** de l'accueil : il mène à `#/index`, le référentiel
  d'administration des index, pas à une recherche de documents. Désormais masqué sans
  `GERER_REFERENTIELS` (sinon il renverrait à l'accueil). Il n'existe pas d'écran de recherche
  par métadonnées (`POST /recherches`) dans le front : écart à signaler pour les écrans
  utilisateur.
- **Saisie d'un index booléen au dépôt** (`document-upload`, écran de dev5) : champ texte par
  défaut ; le serveur accepte oui/non, vrai/faux, 1/0. Une case à cocher serait plus juste.
- **Node** : mettre la CLI Angular du poste à niveau (Node ≥ 22.22.3) plutôt que de dépendre du
  Node de dev5.

## Tour 2

Branche `ct/dev4-r2`, partie de `claude/inspiring-lovelace-10bg1c` (`08c710c`). Front, plus un
champ ajouté à une réponse du back (ANO-F-018) ; aucun changement de schéma, donc aucun
changeset Liquibase.

| Id | Cause | Correctif | Preuve (test qui échoue sans le correctif) | État |
|---|---|---|---|---|
| ANO-F-015 | Aucun écran pour `POST /type-documents/retypages` ni pour `PATCH /type-documents/{id}/actif` | Écran `#/type-de-document/retypage` (`GERER_REFERENTIELS` + module `cycledevie`, qui porte les routes de retypage côté serveur) : type source, type cible (types actifs autres que la source), correspondance des champs proposée par code et modifiable, champs perdus signalés, portée « tous les documents du type » ou sélection (documents du type dans son dossier) ; confirmation, suivi du travail (relecture toutes les 2 s), rapport par document (réussi / échec, motif, champs perdus), derniers travaux. Fiche du type : cartouche Statut, Désactiver (confirmé) / Réactiver, lien « Re-typer ses documents » ; colonne Statut dans la liste ; bouton « Re-typologie en lot » | `type-document-detail.spec.ts` (5 échecs sans le correctif), `retypage-lot.spec.ts` (composant absent), `app.routes.spec.ts` (garde de permission et de module) | Corrigée (`2225fc7`) |
| ANO-F-018 | La fiche d'un dossier ignorait les droits de l'appelant sur le nœud, que le serveur ne lui donnait pas | `GET /workspaces/{id}` porte `permissions` (droits effectifs de l'appelant sur le nœud, `DroitsResolus.surNoeud` ; nul dans les listes), documenté dans `champs.yml`. Fiche : Modifier ← MODIFIER ; Supprimer un document ← SUPPRIMER sur le nœud ; Archiver le dossier, Retirer le drapeau, Annuler ← ARCHIVER (entrée `peutArchiver` de `cycle-dossier`) ; Sous-dossier ← `GERER_ESPACES`, ou DEPOSER dans un espace d'échange (règle de `WorkSpaceService.create`) | Back : `CheminsAccesApiTest.permissionsSurLaFicheDuNoeud` (champ absent sans le correctif). Front : `workspace-detail.spec.ts` (3 échecs sans le correctif), `cycle-dossier.spec.ts` | Corrigée (`157ec9c`) |
| ANO-F-020 | La fiche affichait la valeur brute du serveur (`true`) ; le dépôt saisissait un booléen dans une zone de texte | `lireBooleen` / `afficherValeurIndex` (`indexation.model.ts`, mêmes écritures que `ValeursMetadonnees.booleen`) ; fiche : « Champs indexés » en Oui / Non, la saisie des index reprend la valeur enregistrée en oui / non ; dépôt : case à cocher (oui / non), indéterminée « Non renseigné » tant que rien n'est choisi, pour qu'un index obligatoire reste à renseigner explicitement. Ajouts seulement dans les fichiers de dev5, tests dans des fichiers à part | `indexation.model.spec.ts`, `document-detail-booleen.spec.ts`, `document-upload-booleen.spec.ts` (échoue sans le correctif) | Corrigée (`b0af397`) |
| T-088 (suite) | Écrans fermés au tour 1, mais actions des modules désactivés toujours proposées ailleurs (404 `MODULE_INACTIF`) | Fiche document : Archiver, Désarchiver et lecture de la copie de conservation (cycle de vie), circuit de validation (workflow) masqués. Fiche d'espace (`cycle-dossier`) : archivage du dossier (cycle de vie) et export ZIP (export) masqués séparément, aucun appel aux routes d'archivage, encart absent si les deux modules sont inactifs. Accueil : encarts « À valider » et « Activité récente » (champ `module` du catalogue) ni rendus ni proposés dans « Personnaliser » ; tuile « En attente de signature » masquée | `document-detail-modules.spec.ts`, `cycle-dossier.spec.ts`, `dashboard-modules.spec.ts` : 8 échecs sans le correctif | Livré (`f185107`), à recetter |
| ANO-F-024 | `MatDatepickerIntl` non traduit ; de plus `MatDatepickerModule` déclare lui-même `MatDatepickerIntl` dans ses fournisseurs : importé par un composant autonome, il masque tout fournisseur global | `CalendrierFr` (libellés français) fourni dans `app.config` ; la fiche document et le dépôt importent les directives autonomes du calendrier (`CHAMP_DATE`, `core/calendrier-fr.ts`) au lieu du module. Adaptateur de dates non touché (ANO-F-022, dev5) | `calendrier-fr.spec.ts` : fournisseur déclaré, bouton « Ouvrir le calendrier » sur un champ et sur le formulaire de dépôt, et démonstration que le module réintroduit « Open calendar » ; 3 échecs sans le correctif | Corrigée (`44bfc97`) ; ligne absente du registre de cette branche (ouverte par qa2 après `08c710c`) : statut à reporter à la fusion |

Résultats : `ng test` 121 tests verts (84 au départ) ; `ng build` vert (avertissements de budget
préexistants). Suite back complète (`mvn -B -q test`, base `ged_dev4_test`, `GED_MANAGEMENT_PORT` et
`SERVER_PORT` retirés) : 631 tests, 0 échec, 0 erreur (référence : 630, 0 échec ; +1 test
ANO-F-018).

### Points pour pm (tour 2)

- **Contrat d'API** (ANO-F-018) : champ `permissions` ajouté à `WorkSpaceResponse`, rempli
  seulement par `GET /workspaces/{id}` (comme `DocumentResponse`). Ajout rétrocompatible ; à
  signaler au suivi du contrat.
- **ANO-F-016 reste à arbitrer** : « Sous-dossier » n'apparaît plus qu'à qui le serveur l'accepte
  (gestion des espaces, ou Déposer dans un espace d'échange), mais le formulaire ouvert reste
  celui d'administration (code, propriétaire, statut) ; le dépôt dans un sous-dossier dépend de
  l'arbitrage attendu.
- **Suppression d'un document depuis la fiche d'espace** : jugée sur SUPPRIMER au nœud (la liste ne
  porte pas les droits par document). Un document supprimable par une habilitation posée sur lui
  seul ne propose donc la suppression que depuis sa propre fiche.
- **Re-typologie** : la sélection de documents se fait parmi ceux du type rangés dans le dossier du
  type (200 au plus) ; pour un lot plus large, la portée « tous les documents du type ». Le retypage
  est classé par le serveur dans le module « cycle de vie » : l'écran suit ce classement.
- **Case à cocher booléenne** (ANO-F-020) : une fois choisie, une valeur ne revient pas à « non
  renseigné » au dépôt ; la fiche garde la liste Oui / Non / —, qui permet de la vider.
- **Imports du calendrier** (ANO-F-024) : tout nouvel écran avec un champ date doit importer
  `CHAMP_DATE` et non `MatDatepickerModule`, sinon les libellés repassent en anglais (le test le
  montre). À relayer à dev5.
- **T-088, restes hors de mon périmètre** : le profil (`profil.ts`) lit encore l'historique du
  workflow, et la fiche d'un type montre sa règle de workflow, même module désactivé (réponses
  404 absorbées, rien de cassé). À décider si le masquage doit aller jusque-là.

## Tour 3

Branche `ct/dev4-r3`, partie de `claude/inspiring-lovelace-10bg1c` (`ff20f21`). Front seul :
aucun changement du back ni du schéma, donc aucun changeset Liquibase. Aucun champ date touché.

| Id | Cause | Correctif | Preuve (test qui échoue sans le correctif) | État |
|---|---|---|---|---|
| Corbeille (suite d'ANO-F-023) | ANO-F-023 (dev5) n'avait renommé que la liste des documents ; les listes des index, plans d'indexation, types de document, étiquettes, règles de workflow, groupes et espaces de travail appelaient encore « Archive » la vue des éléments supprimés (`?trashed=1`), comme l'archivage (D10) | Bouton « Corbeille » (icône corbeille), retour par le nom de la liste (« Index », « Types de document »…) au lieu de « Actifs » ; état vide « La corbeille est vide » (ajouté à la liste des règles, qui affichait « Aucune règle de workflow ») ; identifiants du code alignés (`corbeilleView`, `basculerCorbeille`). L'action « Archiver / Désarchiver » de la liste des espaces (vrai archivage) est inchangée | `features/corbeille-referentiels.spec.ts` : 7 listes × (vue normale, vue corbeille) ; 14 échecs avant correctif | Fait (`07ef0d7`) |
| T-088 (restes) | Profil et fiche d'un type ignoraient l'état du module workflow : lecture de `/workflow/historique` et `/workflow/regles` (404 `MODULE_INACTIF`), compteurs « À valider » / « Traitées », carte « Mes dernières décisions » et liens vers « Mes validations » (écran fermé), règle de workflow du type | Profil : compteurs, carte et liens masqués, historique non demandé, grille ramenée à une rangée ; fiche d'un type : règle masquée, règles non lues. Même reste trouvé sur les dossiers : formulaire d'espace (champ « Règle de workflow », lecture des règles), fiche d'un dossier (« Circuit de validation »), liste des espaces (colonne « Circuit », retirée aussi du sélecteur de colonnes). Le formulaire renvoie la règle déjà portée par le dossier | `profil/profil.spec.ts` (2), `type-document-detail.spec.ts` (+1), `workspace/workflow-masque.spec.ts` (5), `workspace-detail.spec.ts` (+1) ; 7 échecs avant correctif (les cas « module inactif », et ceux qui ciblent les nouvelles classes) | Fait (`f2fdfec`, `c3fbb52`) |
| P-04 | Écran existant (accueil vide `accueil-vide` avec message, `roleGuard` sur toutes les routes sauf l'accueil, menu principal masqué), jamais testé. Partiel : « Mes exports » était rangé hors du bloc « avec rôle » du menu, la cloche et « Mon profil » restaient proposés (trois liens qui ramenaient à l'accueil), et la coque appelait `/modules` et `/notifications/compteur`, refusés sans rôle (403) | Ces trois entrées réservées aux comptes avec rôle ; pas d'appel à `/modules` ni au compteur des notifications sans rôle | `compte-sans-role.spec.ts` : accueil vide (message, `role="status"`, aucun appel), accueil habituel dès qu'un rôle existe, garde vers l'accueil, coque (seul lien : l'accueil ; aucun appel ; carte de compte sans « Mon profil ») ; le test de la coque échoue avant correctif (`/notifications`, `/mes-exports`) | Fait (`33ed47c`) |

Résultats : `ng test` 175 tests verts (148 au départ, +27) ; `ng build` vert (avertissements de
budget préexistants, dont `profil.scss`). Suite back complète (`mvn -B -q test`, base
`ged_dev4_test`, `GED_MANAGEMENT_PORT` et `SERVER_PORT` retirés, aucun fichier du back modifié) :
661 tests, 0 échec, 0 erreur (référence : 661, 0 échec).

### Points pour pm (tour 3)
- **T-088** : le masquage du module workflow couvre maintenant aussi les dossiers (formulaire,
  fiche, liste), au-delà des deux restes signalés au tour 2 ; même cause, même traitement.
- **P-04** : l'écran du compte sans rôle est désormais exercé par un test automatisé (rendu et
  coque) ; la recette à l'écran avec un compte réel du simulateur reste à faire par qa.
- **Corbeille** : aucune anomalie du registre ne porte ce reste (ANO-F-023 visait les documents) ;
  rien n'a été changé dans les registres.
- **Pastille des notifications et état des modules** : sans rôle, plus aucun appel ; si un rôle
  est attribué pendant la session, ils reviennent à la reconnexion (le message de l'accueil vide
  demande justement de se reconnecter).

## Tour 5

Branche `ct/dev4-r5`, partie de `claude/inspiring-lovelace-10bg1c` (`4090da3`). Front (dépendances)
et documentation ; aucun fichier du back ni de Liquibase modifié.

| Id | Cause | Correctif | Preuve (test qui échoue sans le correctif) | État |
|---|---|---|---|---|
| ANO-E0-004 (T-070, avec dev2) | `@angular/router` 22.0.8 visé par l'avis haut GHSA-ff3f-86qr-9cv3 (corrigé en 22.2.0) : `npm audit --omit=dev --audit-level=high` en code 1 (7 vulnérabilités, 6 moyennes, 1 haute), étape « Audit des dépendances livrées » du job front rouge depuis le 01/10 | Tous les paquets `@angular/*` en `^22.2.1` (animations, cdk, common, compiler, compiler-cli, core, forms, material, platform-browser, router, build, cli) ; `package-lock.json` régénéré par npm (`npm uninstall` puis `npm install` des paquets Angular : `npm install` et `npm update` refusaient la montée incrémentale à cause des pairs exacts d'Angular) ; aucun code applicatif modifié | `outils/tests/versions-angular.test.mjs` (lancé par `node --test outils/tests/*.test.mjs`, job « Registre des dépendances et licences ») : 2 échecs sur le verrouillage d'avant (« @angular/router 22.0.8 est visé par GHSA-ff3f-86qr-9cv3 », « package.json demande ^22.0.0 ») ; contrôle réel : `npm audit --omit=dev --audit-level=high` → code 0, « found 0 vulnerabilities » (rapports avant / après dans `docs/securite/rapports/`) | Corrigée (`620408b`, documentation `83e91e6`) |

Vérifications (Node 24.21.0) : `npm ci` sans erreur ; `npx ng test --watch=false` : 44 fichiers,
196 tests verts ; `npx ng build` vert (avertissements de budget préexistants, plus une dépréciation
Sass sur l'`@import` que la CLI 22.2 génère elle-même pour les styles globaux). `npm run sbom` et
`mvn package -DskipTests` puis `node outils/registre-dependances.mjs` : `docs/DEPENDANCES.md` mis à
jour (Angular 22.2.1, transitive `entities` 8.1.0 BSD-2-Clause ajoutée, `zod` 4.6.5) ; le SBOM
lui-même n'est pas versionné (artefact `sbom-frontend` de la CI). `docs/securite/VULNERABILITES-DEPENDANCES.md`
§3 et §4 mis à jour. Suite back complète (`mvn -B -q test`, base `ged_dev4_test`,
`GED_MANAGEMENT_PORT` et `SERVER_PORT` retirés, aucun fichier du back modifié) : 687 tests, 0 échec,
0 erreur. Outillage : `node --test outils/tests/*.test.mjs` 12 tests verts (9 + 3 nouveaux). Le `node_modules` du front a été installé dans la copie de travail (pas de lien
vers celui de `/home/user/GED/frontend`, resté en 22.0.8, pour ne pas le modifier).

### Points pour pm (tour 5)

- **CI GitHub** : rien n'a été poussé ; le retour au vert du job front (et la reprise de l'archivage
  de `frontend-paquet` et `sbom-frontend`) est à constater à la première exécution après fusion.
- **Outillage de test (non livré)** : `npm audit` sans `--omit=dev` signale toujours `undici` 7.28.0
  (haute, via `jsdom`) et `vitest` / `@vitest/mocker` 4.1.10 (moyenne). Inchangés par la montée, hors
  seuil de la CI (paquets livrés seulement) ; à traiter à la montée planifiée de l'outillage de test.
- **Postes de l'équipe** : le `node_modules` partagé de `/home/user/GED/frontend` est encore en
  Angular 22.0.8 ; après fusion, y relancer `npm ci` (Node 24) pour que les autres membres testent
  sur les mêmes versions.
- **dev2 (T-070)** : document de vulnérabilités mis à jour ici ; à relire par dev2, propriétaire de
  la ligne.

## Tour 7

Branche `ct/dev4-r7`, partie de `claude/inspiring-lovelace-10bg1c` (`dd89445`). Front seul : aucun
fichier du back ni de Liquibase modifié, donc aucun changeset.

| Id | Cause | Correctif | Preuve (test qui échoue sans le correctif) | État |
|---|---|---|---|---|
| ANO-F-029 (F-06, T-025) | Le formulaire des groupes lisait `/employes?has_user=1` : seules les personnes ayant une identité GED avaient une option. Le `mat-select` multiple ne renvoie que les valeurs qui ont une option : le membre en attente (fiche sans identité, `pendingUserIds`) chargé dans `userIds` était effacé dès que l'Administrateur cochait ou décochait quelqu'un (le renommage seul ne touchait pas au champ, d'où l'attente conservée). La fiche et la liste ignoraient `pendingUserIds` | Source de données : `GET /api/v1/employes` sans filtre (API existante, renvoie toutes les fiches avec `utilisateurId` nul sans identité) ; aucun point d'API nouveau n'a été nécessaire. Formulaire : options = toutes les fiches, celles sans identité suffixées « en attente de première connexion », plus une option pour chaque membre actuel absent de la liste (filet si la liste est incomplète ou indisponible) ; bloc « N membre(s) en attente de première connexion » sous le sélecteur, avec un bouton « Retirer » par personne ; aide « Une personne jamais connectée devient membre à sa première connexion ». Fiche : badge « en attente de première connexion » sur la ligne du membre, « Membres : 2 (dont 1 en attente de première connexion) ». Liste : avatar en pointillé et infobulle suffixée. Le corps envoyé reste `userIds` = identifiants de fiche (contrat T-025) | `features/access-group/access-group-membres-attente.spec.ts`, 7 cas : ajouter un membre conserve l'attente, retirer un membre réel la conserve, attente conservée même absente de la liste des fiches, personne jamais connectée proposée avec le libellé, création avec une personne jamais connectée, retrait explicite d'un membre en attente, fiche (badge et décompte). 7 échecs sur le code d'avant (sélecteur sur `?has_user=1`, fiche sans mention) ; 7 verts après | Corrigée (`4c58b57`) |

Résultats : `ng test` 203 tests verts (45 fichiers) ; `ng build` vert (avertissements de budget
préexistants). Suite back complète (`mvn -B -q test`, base `ged_dev4_test`, `GED_MANAGEMENT_PORT` et
`SERVER_PORT` retirés, aucun fichier du back modifié) : 696 tests, 0 échec, 0 erreur, code 0
(référence annoncée : 661, 0 échec ; la suite a grandi depuis).


### Points pour pm (tour 7)

- **Recette à l'écran (qa2)** : rejouer le cas de reproduction d'ANO-F-029 (groupe préparé par
  l'API avec un membre réel et une fiche reprise sans identité) : la fiche doit montrer le badge,
  « Modifier » doit proposer la fiche reprise (cochée, suffixée), cocher un autre membre puis
  « Mettre à jour » doit garder `pendingUserIds` = [la fiche].
- **Volume (dev1, pour information)** : le sélecteur charge toutes les fiches employé en une fois
  (`GET /employes`, non paginé, qui parcourt aussi toutes les identités). Suffisant pour la base
  actuelle ; si la reprise amène plusieurs milliers de fiches, un point de recherche paginé
  (`/employes?search=…&size=…`) serait préférable. Aucun point d'API n'a été inventé.
- **ANO-F-030** : non traitée ici (pm pour arbitrage, dev1 ensuite).
