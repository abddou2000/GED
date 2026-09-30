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
