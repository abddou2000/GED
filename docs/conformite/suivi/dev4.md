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
préexistants) ; suite back : voir fin de tour.

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
