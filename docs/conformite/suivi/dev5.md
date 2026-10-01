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

## Tour 2 — ANO-F-010 à 014, 016, 017, puis ANO-F-021 à 023 (ajoutées par pm)

Branche `ct/dev5-r2`, partie de `claude/inspiring-lovelace-10bg1c` à 08c710c ; `ct/dev4-r2`
fusionnée (d13216b) à la demande de pm, conflits résolus en ajoutant.

| Anomalie | Cause | Correctif | Preuve (échoue sans le correctif) | Commits |
|---|---|---|---|---|
| ANO-F-010 | Aucun écran n'appelait `POST /documents/recherche` ; l'API ne triait que par date du document ; raccourci d'accueil vers la recherche plein texte. | Écran `#/recherche-par-index` (menu « Recherche par index », raccourci « Rechercher par index ») : un critère par index coché « recherche » (`GET /indexation/criteres`), plages de dates et de nombres, liste et booléen en valeur exacte, texte en « contient » ; seuls les critères renseignés partent, en ET ; résultats paginés et triés côté serveur. Back : `sortBy` / `sortDir` sur `POST /documents/recherche` (dateDocument par défaut, name, createdAt ; hors liste blanche = 400), départage par identifiant. | `recherche-index.spec.ts` (4 tests), `w-raccourcis.spec.ts`, `app.routes.spec.ts`, `ModeleDocumentApiTest.rechercheTriable` | 0327d25, 40feb26 |
| ANO-F-011 (écran) | Critères imposés absents (API : dev3). | Plage de date du document, confidentialité, déposant (personnes dotées d'une identité GED) dans l'écran, envoyés sous `dateDocumentDu`, `dateDocumentAu`, `confidentialite`, `deposantUtilisateurId` : **noms identiques à ceux de dev3** (552fdf0 sur `ct/dev3-r2`, corps refusant tout champ inconnu). | `recherche-index.spec.ts` (corps envoyé) | 40feb26 |
| ANO-F-012 | La fiche n'affichait pas `versions[].auteurId`, et aucune API lisible par un utilisateur ne donnait le nom d'une identité GED. | Colonne « Auteur » au tableau des versions ; `GET /employes` expose `utilisateurId` (ajout compatible, dictionnaire OpenAPI complété) et l'écran traduit l'identité en nom. | `document-detail.spec.ts` (auteur), `EmployeApiTest.identiteGedExposee` | 1d334e1, 1145800 |
| ANO-F-013 | `GET/POST/DELETE /documents/{id}/designes` non appelés par le front. | Bloc « Personnes autorisées (Confidentiel) » sur la fiche d'un document Confidentiel : liste, désigner (parmi les personnes ayant une identité GED, hors déjà désignées), retirer ; lecture seule sur document verrouillé ou archivé. | `document-designes.spec.ts` (3 tests), `document-detail.spec.ts` | fe61d82, 1145800 |
| ANO-F-014 | Déplacement et rattachements servis par l'API sans écran. | Bloc « Emplacements » : déplacer vers un dossier choisi (avec Déplacer), rattacher à un dossier de plus et retirer un rattachement (avec Modifier) ; fiche rechargée après chaque opération. | `document-emplacements.spec.ts` (3 tests) | 00ed0d1, 1145800 |
| ANO-F-016 (écran) | « Sous-dossier » ouvrait le formulaire d'administration (`POST /workspaces`) ; le dépôt n'avait pas d'emplacement. | Espace d'échange : « Nouveau dossier » ouvre un formulaire simple (nom, description) sur `POST /noeuds/{id}/dossiers` ; « Déposer ici » (si Déposer sur le nœud) ouvre le dépôt avec les dossiers de l'espace, celui d'où l'on vient proposé ; le dossier part en `noeudId` (API de dev1, 35adde1 sur `ct/dev1-r2`). Espaces métier inchangés. | `workspace-detail-echange.spec.ts` (3), `dossier-simple-form.spec.ts` (2), `document-upload-dossier.spec.ts` (3) | 0149f4e |
| ANO-F-017 | `statutOcr` affiché sur la seule fiche. | Pastille « OCR en attente » / « OCR en échec » (info-bulle explicative) dans la liste des documents et dans les résultats de la recherche par index ; la recherche plein texte renvoie vers la recherche par index pour un document non OCRisé. Par construction, un document non OCRisé n'apparaît pas dans les résultats plein texte. | `document-list.spec.ts` (OCR), `recherche-index.spec.ts` | 397f828, 40feb26 |
| ANO-F-021 | `.page > .colonnes { flex: 1 1 auto; min-height: 0 }` et `.colonnes > * { max-height: 100%; overflow: auto }` comprimaient le bloc central dans la hauteur restante. | Blocs à hauteur naturelle, la fiche défile dans son cadre (`.page` défile déjà) ; seul le tableau des versions défile (420 px au plus). | `document-detail-mise-en-page.spec.ts` (styles calculés, échoue avec l'ancienne feuille : vérifié) ; **mesure réelle** Chromium sans tête sur le front construit, API simulée : 1366×768 → colonnes 799/799 et 819/819 px (avant, règles d'origine réinjectées : 40/799 et 40/819) ; 1440×1000 « à indexer » → 799/799 (avant : 120/799). Script : `dev5-mesure-f021.js` (bloc-notes de session). | 9426632 |
| ANO-F-022 | `provideNativeDateAdapter()` : `Date.parse` lit mm/jj/aaaa. | `DateAdapterFr` (core) fourni à toute l'application : jj/mm/aaaa (/, . ou -) ou AAAA-MM-JJ, date impossible invalide (31/02), affichage jj/mm/aaaa, semaine du lundi. | `date-adapter-fr.spec.ts` (3 tests, dont un champ Material réel : « 03/04/2026 » → 3 avril) | ed23b46 |
| ANO-F-023 | Libellé « Archive » sur la corbeille. | Bouton « Corbeille », état vide « La corbeille est vide ». | `document-list.spec.ts` | bf6d3df |

Aucun changeset Liquibase : pas d'évolution de schéma.

### Vérifications

- Front (après fusion de `ct/dev4-r2`) : `ng build` vert (avertissements de budget habituels) ;
  `ng test` **35 fichiers, 148 tests verts** (84 à la référence, 106 avant la fusion).
- Back : suite complète `mvn test` (environnement dev5, après fusion de `ct/dev4-r2`) :
  **633 tests, 0 échec, 0 erreur** (référence : 630, 0 échec).

### Points pour pm

1. **Statuts** : ANO-F-010, 012, 013, 014 et 017 passées « Corrigée » dans
   `ANOMALIES-FONCTIONNELLES.md`. ANO-F-011 et ANO-F-016 sont partagées (dev3, dev1) : statut
   laissé à leurs auteurs, écran prêt et aligné sur leurs contrats. ANO-F-021, 022 et 023 ne
   figurent que sur `ct/qa2-r2` : statut à reporter à la fusion (9426632, ed23b46, bf6d3df).
2. **Ordre de fusion** : l'écran de recherche envoie `dateDocumentDu`… que seul `ct/dev3-r2`
   accepte ; sans lui, ces critères partiraient vers une API qui les ignore (aujourd'hui) ou
   les refuse (après dev3). Fusionner `ct/dev3-r2` avec ou avant `ct/dev5-r2`. De même,
   « Déposer ici » suppose `noeudId` au dépôt (`ct/dev1-r2`). Mes changements de
   `RechercheMetadonnees` (tri) et ceux de dev3 (critères) touchent des blocs distincts.
3. **« Archive » ailleurs** : les corbeilles des référentiels (index, plans, étiquettes, types,
   groupes, règles de workflow) et de la liste des espaces s'intitulent encore « Archive ».
   Hors de mon périmètre ; même correction à prévoir (dev4), surtout pour les espaces, où
   l'archivage d'un dossier existe (D10).
4. **Champs date** : les nouveaux écrans n'importent pas `MatDatepickerModule` (consigne de
   dev4, ANO-F-024) ; les plages de la recherche par index sont des champs date natifs du
   navigateur (format local du poste), comme la recherche plein texte.
5. `GET /employes` expose désormais `utilisateurId` à tout utilisateur doté d'un rôle (la
   liste des noms l'était déjà) : nécessaire pour désigner une personne ou filtrer par déposant.

## Tour 3 — T-050 (écrans et « à traiter »), puis ANO-F-025, 027, 028 (ajoutées par pm)

Branche `ct/dev5-r3`, partie de `claude/inspiring-lovelace-10bg1c` à ff20f21 ; `ct/dev3-r3`
fusionnée en avance rapide (8ad50bd : page/size homogènes, en-tête `GED-Champs-Ignores`).

| Tâche | Cause | Correctif | Preuve (échoue sans le correctif) | Commit |
|---|---|---|---|---|
| T-050 — `GET /workflow/a-traiter` | Pagination propre au contrôleur : 20 par défaut, plafond 100, page négative ramenée à 0. | `Tri.pageable` comme les autres listes : 50 par défaut (et pour une taille < 1), 200 au plus, alias `taille` (filtre de conventions), page négative 400. | `CircuitApiTest.aTraiterPagination` (`size=150` rendait 100 ; `page=-1` rendait 200) | f8b8bfb |
| T-050 — écrans de recherche | Plein texte et index demandaient 20 par page (sélecteur 10/20/50) ; la liste « à traiter » s'arrêtait à 100. | `core/pagination.ts` : 50 par défaut, sélecteur 25/50/100/200 (jamais au-delà du plafond serveur) ; « à traiter » lue en une page de 200. | `recherche-plein-texte.spec.ts` (nouveau, `taille=50`, 200 au plus), `recherche-index.spec.ts` (`size` 50), `pagination.spec.ts` | db48ff2 |
| Champs ignorés (DAT §5.3.2, P-08) | Le serveur nomme les champs inconnus ignorés dans `GED-Champs-Ignores` (dev3), aucun écran ne le lisait : un critère mal nommé passait inaperçu. | Les services de recherche rendent le corps et les champs ignorés (`observe: 'response'`) ; avertissement discret « Critère non appliqué : … » (`role=status`, noms décodés) au-dessus du total, sur les deux écrans. En-tête exposé par CORS (frontend d'une autre origine). | `recherche-plein-texte.spec.ts`, `recherche-index.spec.ts` (avertissement présent, puis absent sans en-tête), `pagination.spec.ts` (décodage) | db48ff2 |
| ANO-F-025 | Fiche : cartouche « Échéance de conservation » en valeur brute ; `afficherValeurIndex` ne traitait que le booléen. | `formaterDate` pour la cartouche ; un index de type date lisible s'affiche jj/mm/aaaa (lecture composant par composant, sans décalage de fuseau) ; valeur illisible ou texte de même forme inchangés. | `document-detail-dates.spec.ts` (2 tests : « 01/10/2036 », « 15/11/2026 »), `indexation.model.spec.ts` | 71ee7ed |
| ANO-F-027 | Bloc « Emplacements » : « Déplacer » réservé à la permission Déplacer, alors que le serveur accepte Déposer entre dossiers d'un même espace d'échange (D12). | Sans Déplacer mais avec Déposer : lecture de l'usage de l'espace du document (`GET /workspaces/{id}`) ; espace d'échange → « Déplacer » vers les seuls dossiers de cet espace (hors dossier actuel et nœuds de passage) ; espace métier → rien. Parcours de l'arbre mis en commun (`dossiersDeLEspace`) avec « Déposer ici ». | `document-emplacements.spec.ts` (+2 : membre en espace d'échange, destinations et PATCH ; Déposer en espace métier) | a40d37e |
| ANO-F-028 | `POST /documents/recherche` n'avait pas de critère de date de dépôt ; l'écran index non plus. | Back : `dateDepotDu` / `dateDepotAu` (bornes incluses, au jour), même fragment SQL que `deposeDu` / `deposeAu` (`CriteresMetadonnees`), plage inversée 400, champs décrits dans `champs.yml`. Écran : « Déposé du … au … » dans le socle, plage inversée refusée sans appel. | `CriteresImposesApiTest.dateDeDepot` (champ ignoré et signalé avant : 3 résultats au lieu de 2), `recherche-index.spec.ts` (corps, refus) | 9916bdd |

Aucun changeset Liquibase : pas d'évolution de schéma.

### Vérifications

- Front : `ng build` vert ; `ng test` **38 fichiers, 161 tests verts** (référence 148).
- Back : ciblés verts (`CircuitApiTest` 14, `CriteresImposesApiTest` 8, `SpecificationOpenApiTest` 10) ;
  suite complète `mvn test` (environnement dev5, à 9916bdd) : **670 tests, 0 échec, 0 erreur**
  (référence après le tour 2 : 661).

### Points pour pm

1. **Statuts** : ANO-F-025 (71ee7ed), ANO-F-027 (a40d37e) et ANO-F-028 (9916bdd) n'existent que
   sur `ct/qa2-r3` : « Corrigée (commit) » à poser à la fusion. ANO-F-028 était attribuée à dev3
   pour la partie API : faite ici à la demande de pm (bloc distinct de ses changements T-050).
2. **Ordre de fusion** : `ct/dev5-r3` contient `ct/dev3-r3` (avance rapide) ; l'avertissement
   suppose l'en-tête `GED-Champs-Ignores` de dev3.
3. **Autres listes** : les référentiels (types, index, plans, étiquettes, groupes, règles), la
   liste des documents et « Mes workflows » gardent un sélecteur 10/25/50 et une taille propre
   à l'écran ; le serveur leur sert déjà 50 par défaut et 200 au plus. Harmonisation possible avec
   `core/pagination.ts`, hors de ma tâche de ce tour.
4. **Date de dépôt** : bornes au jour UTC, comme `deposeDu` / `deposeAu` existants ; un dépôt à
   0 h 30 heure du Maroc (UTC+1) tombe la veille. Écart commun aux trois recherches, à trancher
   (fuseau de MMED) si qa2 le relève.
5. **ANO-F-027** : le membre se voit proposer tous les dossiers non « de passage » de l'espace
   d'échange ; le serveur exige en plus Déposer sur la destination, qu'il revérifie.
