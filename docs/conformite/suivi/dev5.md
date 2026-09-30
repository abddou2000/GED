# Suivi dev5 — écrans utilisateur

Branche `ct/dev5-r1` (partie de `claude/inspiring-lovelace-10bg1c` à 71bdc1d). Périmètre : accueil,
dépôt en deux temps, fiche du document et versions, recherche, mes validations, notifications,
espaces de partage, exports.

## Tour 1 — ANO-F-004, ANO-F-005, ANO-F-006

| Anomalie | Cause | Correctif | Preuve (échoue sans le correctif) | Commits |
|---|---|---|---|---|
| ANO-F-004 | Raccourcis de l'accueil figés (`w-raccourcis.ts`) ; tuile « Groupes d'accès » sans condition (`stat-tiles.ts`) ; « Rechercher par index » pointait vers `/index` (référentiel, administration). | Chaque raccourci porte sa permission (`DEPOSER`, `GERER_ESPACES`, aucune pour « Mes validations » et la recherche) ; la tuile des groupes suit `GERER_ROLES_HABILITATIONS`, comme le menu ; le raccourci de recherche mène à `/recherche` et s'intitule « Rechercher un document ». Grille des tuiles adaptée à leur nombre. | `w-raccourcis.spec.ts` (3 tests), `stat-tiles.spec.ts` (2 tests) | 5022147 |
| ANO-F-005 | La fiche lisait les index sans jamais proposer `PUT /indexation/documents/{id}` ; la liste ignorait `statutIndexation` ; les messages du dépôt renvoyaient à une reprise « depuis la fiche » qui n'existait pas. | Fiche : bandeau « À indexer » et saisie des index du plan (texte, nombre, date, liste, booléen ; obligatoires signalés et bloquants), enregistrement puis rechargement (issue « indexé ») ; « Saisir les index » permet aussi de corriger un document indexé ; sans `MODIFIER`, l'état est dit sans saisie. Liste : pastille « À indexer » vers la fiche. Dépôt : messages rendus exacts. Back : `GET /indexation/documents/{id}/champs` expose `obligatoire` (champ ajouté, compatible). | `document-detail.spec.ts` (3 tests F-005), `document-list.spec.ts`, `ModeleDocumentApiTest.champsObligatoiresExposes` | 205f693, 03d5dc2 |
| ANO-F-006 | Formulaire de dépôt sans contrôles `objet` / `dateDocument` ; fiche en lecture seule sur ces champs ; tri de la liste impossible sur la date du document (`sortBy=dateDocument` hors liste blanche, ignoré silencieusement par `Tri`). | Dépôt : champs Objet et Date du document (vide = date du dépôt), figés une fois le document créé. Fiche : objet et date corrigeables (`PUT /documents/{id}`). Liste : colonne « Date du document » triable. Back : `dateDocument` ajouté aux tris de `GET /documents` et `/documents/trashed`, avec départage par identifiant (ex-aequo stables d'une page à l'autre). | `document-upload.spec.ts`, `document.service.spec.ts`, `document-detail.spec.ts` (F-006), `document-list.spec.ts`, `ModeleDocumentApiTest.listeTrieeParDateDuDocument` | 205f693, 03d5dc2 |

Aucun changeset Liquibase : pas d'évolution de schéma.

### Vérifications

- Front : `ng build` vert (mêmes avertissements de budget qu'avant) ; `ng test` 12 fichiers,
  32 tests verts (18 avant ce tour). Les tests front tournent sous Vitest + jsdom.
- Back : suite complète `mvn test` (environnement dev5) : **599 tests** (597 + 2 ajoutés),
  5 échecs, aucun lié à ce tour : les 4 de la référence (`ArchivageApiTest.conversionEnEchec`,
  `ApercuTelechargementApiTest.apercuBureautiqueSansLibreOffice`,
  `WorkflowApiTest.employesWithAccount`, `WorkSpaceApiTest.moveIntoDescendant`) et
  `SupervisionIntegrationTest.portDeManagement`, qui attend le port de management 8081 alors que
  `equipe-env.sh` exporte `GED_MANAGEMENT_PORT=18097` pour dev5 (échec dû à l'environnement du
  poste, pas au code ; il passe sans cette variable).

### Points pour pm

1. **Node du poste refusé par Angular CLI** : `ng build` / `ng test` échouent avec le Node
   v22.22.2 installé (`@angular/cli` 22.0.7 exige ^22.22.3, ^24.15 ou ≥26). J'ai utilisé un Node
   24.21.0 officiel (somme SHA-256 vérifiée) décompressé dans le bloc-notes de la session
   (`dev5-node/`), sans rien changer au poste ni au dépôt. dev4 rencontrera la même chose.
2. **Recherche par index à l'écran** : le raccourci mène désormais à la recherche plein texte
   (contenu, type, dossier, dates), seul écran de recherche utilisateur ; aucun écran n'offre
   les critères d'index (`POST /indexation/recherche`, `POST /documents/recherche` existent
   côté API). Le libellé a été changé en « Rechercher un document » pour ne pas promettre ce
   que l'écran ne fait pas. Écran de recherche par critères d'index à prévoir (hors de ce tour).
3. **Ordre par défaut de la liste** : il reste celui du dépôt (le plus récent d'abord) ; la date
   du document est un tri proposé par la colonne. Si « les tris portent prioritairement » sur
   la date du document doit valoir tri par défaut, c'est une ligne à changer (`triChamp` initial) :
   décision à prendre (un dépôt récent d'un document ancien n'apparaîtrait plus en tête).
   La recherche plein texte trie par date de dépôt (`DATE_DEPOT`) : un tri par date du document y
   relève du back (dev3).
4. `CritereResponse` (module indexation, dev3) porte un champ `obligatoire` de plus : ajout
   compatible, déjà décrit dans `documentationapi/champs.yml`.
