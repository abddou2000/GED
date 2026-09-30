# Résultats de la recette fonctionnelle — Dossier d'analyse des besoins fonctionnels V3

Exécutée par qa2 : F-01 à F-20 le 28/09/2026 sur `ct/qa2` (partie de `conformite-technique` à
**bc371ad**) ; **F-21 à F-77 le 30/09/2026 (tour 1)** sur `ct/qa2-r1`, partie de
`claude/inspiring-lovelace-10bg1c` à **71bdc1d**. Aucun code applicatif modifié. **État : les 77
lignes sont prononcées.** Les corrections des ANO-F-001 à ANO-F-009, faites en parallèle par les
développeurs, ne sont pas intégrées à cette base : leur revérification est prévue au tour suivant.

## 1. Méthode

- Référence : le PDF (24 pages) fait foi ; les décisions actées de `DECISIONS-REVUE-TECHNIQUE.md`
  priment. Chaque ligne de `MATRICE-FONCTIONNELLE.md` est numérotée F-01 à F-77 dans l'ordre du
  document.
- Scénarios exécutés **par l'API** avec les personas du §3.2 (`recette/fonctionnel/`, Java 17,
  aucun Python : D5), puis contrôle des **écrans** : route Angular, composant, appels, et front
  servi (`ng serve` 4388) parcouru avec le compte concerné.
- Verdicts : **Conforme** ; **API conforme, écran absent ou partiel** ; **Non conforme** ;
  **Non testable ici** (raison, et résultat au simulateur).

## 2. Environnement

- Back : JAR construit sur `ct/qa2`, profil dev, API **18088**, management **18098**, base
  **`ged_qa2`** (préparée par `preparer-base.sql`, schéma par Liquibase), stockage, clés et
  répertoires de travail sous `%USERPROFILE%\.ged-dev\qa2`.
- Front : `ng serve` sur **4388**, mandataire vers 18088 (fichier hors dépôt), jonction
  `node_modules` non versionnée.
- Réels : PostgreSQL 16, Tesseract 5 (`fra`, `ara`), chiffrement, veraPDF (bibliothèque).
- **Simulés** : annuaire AD (UnboundID embarqué, port **33399**, LDIF de démonstration +
  `recette/fonctionnel/annuaire-fonctionnel.ldif`), relais SMTP (GreenMail, port **3039**),
  LibreOffice (`FauxSoffice`). ClamAV non activé (hors périmètre fonctionnel).
- Incident d'environnement : l'instance s'est arrêtée trois fois sans trace d'arrêt dans son
  journal (codes de sortie 127 puis 0) ; elle tourne désormais sous un relanceur
  (`veiller-back.sh`, hors dépôt) qui note chaque arrêt. Aucun effet sur les verdicts (scénarios
  rejoués après relance).

**Tour 1 (30/09/2026, F-21 à F-77)** — poste Linux partagé (conteneur, 4 processeurs) :

- Back : JAR construit sur `ct/qa2-r1` (`mvn package`, tests non rejoués : aucune modification de
  code), profil dev, API **18088**, management **18098**, base **`ged_qa2`** (schéma créé par
  Liquibase au démarrage), stockage, clés et répertoires de travail dans le répertoire de travail
  de qa2 (hors dépôt). Alerte d'échéance planifiée chaque minute pour F-58.
- Réels : PostgreSQL 16, **Tesseract 5.3.4** (`fra`, `ara`, installé pour la recette),
  **LibreOffice** (aperçu et conversion PDF/A des fichiers Word), chiffrement, veraPDF.
- Simulés : annuaire (port **33399**, LDIF de démonstration + `annuaire-fonctionnel.ldif`), relais
  SMTP (GreenMail, port **3039**). ClamAV non activé (hors périmètre fonctionnel).
- Front : `ng serve` sur **4388** (mandataire vers 18088, fichier hors dépôt ; le poste a Node
  22.22.2 et le CLI exige 22.22.3 : version annoncée par un préchargement hors dépôt, sans toucher
  à `node_modules`), parcouru avec Chromium (Playwright) sous les comptes des personas ; captures
  hors dépôt.
- Rejeu : `recette/fonctionnel/lancer.sh A B` (42 OK, 1 ÉCHEC F-09b, 2 AVERT : état de F-01 à F-20
  inchangé), puis `lancer.sh C D` (37 OK, 1 ÉCHEC F-40, 1 NA F-74). Marqueurs d'exécution
  `mmuo179x1` (A, B) et `mmuo1wtim` (C, D).

## 3. Tableau des 77 exigences

Colonnes : identifiant, référence et page du PDF, exigence, verdict, preuve (identifiant du
scénario et constat), anomalie.

| F | Réf. (page) | Exigence | Verdict | Preuve | Anomalie |
|---|---|---|---|---|---|
| F-01 | 2.2 (p. 4) | Paramétrable sans code : types, métadonnées, circuits, durées, habilitations | API conforme, écran partiel | F-01 : index booléen, type (durée 36 mois, confidentialité par défaut), règle rattachée au type, rôle créés par l'API d'administration (201/204). Écrans : types (durée, point de départ, confidentialité), règles, rôles, habilitations présents ; **type Booléen absent** de l'écran des index | ANO-F-007 |
| F-02 | 2.4 (p. 4-5) | Typologie documentaire administrable | Conforme | F-02 : six catégories créées puis modifiées par l'Administrateur ; utilisateur standard 403. Écrans `#/type-de-document` (liste, fiche, formulaire) | — |
| F-03 | 5 (p. 24) | Interface unique pour tous les modules | Conforme | Une seule application Angular, coque unique (`layout/shell`), tous les écrans enfants de la coque ; parcours du front servi | — |
| F-04 | 5 (p. 24) | Interface exclusivement en français | Conforme (réserve mineure) | `lang="fr"`, `LOCALE_ID fr-FR`, messages d'erreur de l'API en français (`Requête invalide`, `Données invalides`), pagination en français ; un libellé anglais « Workspace » | ANO-F-008 |
| F-05 | 5 (p. 24) | Menus et fonctions adaptés au profil | API conforme, écran partiel | F-05a : standard sans permission d'administration, habilitations/audit/clés 403. Front (`nidrissi`) : menu réduit (Accueil, Mes validations, Espaces, Documents, Recherche, Mes exports) ; mais routes d'administration non gardées et raccourcis du tableau de bord hors profil | ANO-F-003, ANO-F-004 |
| F-06 | 3.1 (p. 5) | Trois dimensions : profil, rôle organisationnel, périmètre | Conforme | F-06 : droits obtenus par un groupe GED (origine « groupe » aux droits effectifs), bornés au rôle du groupe (dépôt 403), autre périmètre sans accès, direction lue dans l'annuaire. Écrans groupes et habilitations (sujet Utilisateur ou Groupe) | ANO-F-009 (robustesse) |
| F-07 | 3.1.1 (p. 5-6) | Périmètre à trois niveaux, héritage, plus spécifique, union | Conforme | F-07a héritage vers le sous-dossier, pas vers le parent ; F-07b rupture en lecture seule qui prévaut (dépôt 403 sous rupture, 202 ailleurs) ; F-07c document isolé visible seul. Écran habilitations : nœud ou document, case « Rupture d'héritage » ; écran droits effectifs | — |
| F-08 | 3.2 (p. 6) | Quatre rôles composables, cumulables | Conforme | F-08 : quatre rôles système, rôle « Responsable RH » composé par l'API, cumul Standard + Lecteur sur qa2val1. Écran `#/administration/roles` (création, composition, suppression d'un rôle non attribué) | — |
| F-09 | 3.2.1 (p. 6-7) | Neuf permissions élémentaires ; composition des rôles | **Non conforme** | F-09a : neuf permissions au catalogue (OK). F-09b : **Agent d'archive sans Valider, Diffuser, Purger**. F-09c : Direction Générale avec Déplacer, Archiver, Supprimer (à arbitrer). F-09d : Standard ⊇ Consulter, Déposer, Valider | ANO-F-001, ANO-F-002 |
| F-10 | 3.3 (p. 7) | Authentification Active Directory via LDAP | Non testable ici (AD réel absent) — conforme au simulateur | F-10 : compte AD accepté (200), mauvais mot de passe 401 `IDENTIFIANTS_REFUSES`, connexion par adresse e-mail refusée (400, D2). Écran de connexion « Identifiant Windows ». À rejouer en UAT sur l'AD de MMED | — |
| F-11 | 3.3, 3.3.1 (p. 7-8) | Pas de création de compte ; identité à la 1re connexion ; rôles manuels | Conforme | F-11 : `POST /admin/utilisateurs` 405 ; qa2neuf1 inconnu avant sa 1re connexion, créé sans rôle ni permission (liste des documents 403), rôle attribué par l'Administrateur avec effet immédiat. Aucun écran de création de compte ; écran habilitations | — |
| F-12 | 3.3.1 (p. 8) ; D1 | Désactivation d'un compte AD | Non testable ici (AD réel absent) — conforme à D1 au simulateur | F-12a : compte désactivé dans l'annuaire simulé → connexion 401, ses documents restent consultables. F-12b : la validation en cours du validateur désactivé **n'est pas signalée** (`/workflow/anomalies` vide) et la session ouverte reste valide jusqu'à expiration : conforme à **D1** (la GED ne relit plus l'état AD), risques R26/R27 ; réaffectation manuelle par l'Administrateur disponible | — (D1) |
| F-13 | 3.4 (p. 8) | Clés d'API : générer, consulter, révoquer, régénérer, périmètre, délégation | Conforme | F-13a génération 201, portée par nœud et opérations, secret jamais réaffiché ; F-13b délégation `X-On-Behalf-Of` (déposant nominatif, double identité au journal), hors portée 404 ; F-13c régénération avec chevauchement, révocation effective (401 `CLE_API_REVOQUEE`). Écran `#/cles-api` complet (génération, portée, régénération, révocation motivée) | — |
| F-14 | 3.5 (p. 9) | Modification des droits sans intervention sur le code | Conforme | F-14 : attribution puis retrait d'une habilitation, recomposition d'un rôle, effet immédiat sur la visibilité. Écrans habilitations et rôles | — |
| F-15 | 3.6 (p. 9) | Aucune suppression définitive : corbeille réversible partout | Conforme | F-15 : suppression, présence en corbeille et restauration pour documents (par l'Agent d'archive), espaces, types, index, plans, règles, étiquettes, groupes. Écrans : vues « corbeille » et restauration dans chaque liste | — |
| F-16 | 4.1.3 (p. 9) | Enregistrement unique et horodatage automatique | Conforme | F-16 : identifiant unique et horodatage à chaque réception, deux dépôts identiques → deux identifiants. Fiche : « Déposé le » | — |
| F-17 | 4.1.3, 4.1.6 (p. 10) | Identification de la source et du déposant | Conforme | F-17a canal `INTERFACE`, déposant = utilisateur authentifié ; F-17b `createdById` de la requête ignoré ; F-19 canal `BUREAU_ORDRE` et application ; F-13b déposant délégué. Fiche : « Canal de dépôt », « Créateur » | — |
| F-18 | 4.1.4 (p. 10) | Rattachement obligatoire à une catégorie existante | Conforme | F-18 : sans type 400, type inconnu 404, type en corbeille 400. Écran de dépôt : type obligatoire | — |
| F-19 | 4.1.4, 4.1.5 (p. 10) | Bureau d'ordre par API REST sécurisée par clé d'API | Conforme | F-19 : dépôt par clé 202, canal `BUREAU_ORDRE`, application identifiée, type « Courrier entrant », transmis à l'OCR (`EN_ATTENTE_OCR`) ; sans clé 401 | — |
| F-20 | 4.1.7 (p. 10) | Trois issues : indexé, sans plan, à indexer | API conforme, écran partiel | F-20 : `INDEXE`, `SANS_PLAN`, `A_INDEXER` puis reprise par `PUT /indexation/documents/{id}` → `INDEXE`. **Aucun écran de reprise** d'un document « à indexer » | ANO-F-005 |
| F-21 | 4.2.1 | Moteur OCR open source Tesseract | Conforme | F-21 : `/ocr/etat` actif, moteur disponible, langues `ara, eng, fra, osd`, défaut `ara+fra`. Écran `#/traitements-ocr` (Administrateur) : chaîne active, moteur, langues, compteurs | — |
| F-22 | 4.2.1 | OCR disponible pour l'interface et pour les applications | Conforme | F-22 : texte OCR d'un scan lu par l'utilisateur (200) et par une clé d'API (200) ; recherche plein texte par clé (200) qui trouve le scan. Fiche : bandeau d'état OCR ; écran `#/recherche` | — |
| F-23 | 4.2.1 | L'OCR sert à la recherche plein texte | Conforme | F-23 : scan `OCR_TERMINE`, trouvé par un mot du texte (qa2val2), scan RH hors périmètre exclu | — |
| F-24 | 4.2.4 | L'OCR n'alimente aucune métadonnée | Conforme | F-24 : après OCR, métadonnées vides, objet nul, indexation `A_INDEXER` ; seule proposition d'index tirée du **nom du fichier** (`QA2_NUM←NOM_FICHIER`), aucune du contenu | — |
| F-25 | 4.2.4 | Multipage traité comme une unité | Conforme | F-25 : PDF de 8 pages (scans français puis arabe) OCRisé en entier, trouvé par le témoin français et par le témoin arabe de la dernière page | — |
| F-26 | 4.2.4 | Langues : français et arabe | Conforme | F-26 : scan arabe OCRisé et PDF arabe natif trouvés par un mot arabe, scan français par un mot français (Tesseract réel `ara+fra`) | — |
| F-27 | 4.2.3 | Socle : identifiant, nom, objet, type, date du document, dépôt, déposant, confidentialité, conservation | API conforme, écran partiel | F-27 : dépôt avec objet, date du document, confidentialité ; échéance déduite du type (12 mois) ; correction objet/date 200. Écran : formulaire de dépôt sans objet ni date du document (constaté au tour 1 sur la base, correction de dev5 non intégrée) | ANO-F-006 |
| F-28 | 4.2.3 | Métadonnées additionnelles paramétrables | API conforme, écran partiel | F-28 : texte, nombre, date, liste, booléen ; obligatoire manquant, valeur hors liste, nombre invalide → 400 `METADONNEES_INVALIDES` ; valeur par défaut appliquée. Écran : type Booléen absent de l'écran des index ; la fiche affiche un booléen en brut (« PAYÉE true ») | ANO-F-007, ANO-F-020 |
| F-29 | 4.2.5 | Référentiel d'index, plan, charte de nommage | Conforme (réserve mineure) | F-29 : nom composé au dépôt « FAMMUO179X1_DÉFINITIF_26 » (jetons d'index, jeton année, séparateur, majuscules). Écran plans d'indexation ; l'aperçu de la charte affiche le jeton système en anglais (« …_STATUT_YEAR ») | ANO-F-019 |
| F-30 | 4.2.4 | Gouvernance des types : type utilisé protégé, re-typologie en lot | API conforme, écran absent | F-30 : suppression d'un type utilisé 409 `TYPE_UTILISE` ; retypage de 3 documents 202 → `TERMINE` 3/3 ; standard 403. **Aucun écran de re-typologie** (`/type-documents/retypages` non appelé par le front) | ANO-F-015 |
| F-31 | 4.3.2 | Espaces puis dossiers de profondeur libre | Conforme | F-31 : six niveaux de dossiers sous un espace, document du 6e niveau visible d'un standard de l'espace ; Agent d'archive refusé en espace métier (403). Écran `#/espaces-de-travail` (arbre, sous-dossiers) | — |
| F-32 | 4.3.2, D12 | Deux natures d'espaces : métier et échange | API conforme, écran partiel | F-32 : espace `ECHANGE` ; un membre y crée dossier et sous-dossier par l'API (201), même geste refusé en espace métier (403). Écran : le bouton « Sous-dossier » ouvre le formulaire d'administration « Créer un espace de travail » (code, propriétaire…), qui appelle `POST /workspaces` réservé à l'Administrateur ; et un membre ne peut **ranger aucun document** dans le sous-dossier créé (voir F-62) | ANO-F-016 |
| F-33 | 4.3.4 | Création d'espace réservée à l'Administrateur | Conforme | F-33 : Agent d'archive, DG, standard 403 ; Administrateur 201, espace aussitôt visible de la DG. Écran : pas de bouton de création pour un standard | — |
| F-34 | 4.3.4 | Espaces non autorisés invisibles | Conforme | F-34 : arbre de yalaoui réduit à Exercice 2026 (Finance en simple passage), sans Réservé ni RH ; nidrissi ne voit pas la Finance ; fiche RH 404. Écran : arbre de qa2val1 (Finance, RH), de nidrissi (Échange, Projets) | — |
| F-35 | 4.3.4 | Modification non autorisée bloquée et journalisée | Conforme (réserve mineure) | F-35 : renommage, déplacement, suppression d'un dossier par un standard 403, refus au journal. Écran : la fiche d'un dossier propose « Modifier », « Sous-dossier », « Archiver le dossier », « Supprimer » à un standard qui n'en a pas le droit (refus du serveur) | ANO-F-018 |
| F-36 | 4.3.5 | Déplacement d'un fichier ou d'un dossier, journalisé | API conforme, écran partiel | F-36 : sans Déplacer 403, destination hors droits 404, déplacement 200 et audit ; dossier déplacé avec sa sous-arborescence, audit. Écran : dossier déplaçable ; un document ne se déplace qu'**indirectement**, en changeant son type (« Changer le type déplace le document dans le dossier associé ») ; pas de déplacement vers un dossier choisi | ANO-F-014 |
| F-37 | 4.3.6 | Rattachement à plusieurs espaces sans duplication | API conforme, écran partiel | F-37 : rattachement 201, visible depuis RH, une occurrence en liste et en recherche, retrait 204 sans effet sur l'original. Écran : la fiche **affiche** les rattachements mais n'offre ni ajout ni retrait | ANO-F-014 |
| F-38 | 4.3.4 | Durée de conservation et statut par type | API conforme, écran partiel | F-38 : durée 12 mois, point de départ, échéance déduite ; type désactivé → dépôt 400. Écran : durée et point de départ dans le formulaire du type ; **pas d'activation / désactivation** d'un type (`PATCH /type-documents/{id}/actif` non appelé) | ANO-F-015 |
| F-39 | 4.4.3 | Recherche multicritère sur index, plages de dates et de nombres | API conforme, écran absent | F-39 : `POST /documents/recherche` : montant 200-1000, échéance juin-déc., liste « Brouillon », booléen, texte insensible à la casse → résultats exacts. **Aucun écran** n'appelle cette recherche : `#/recherche` est la seule recherche (plein texte) et le raccourci « Rechercher par index » mène au référentiel des index | ANO-F-010 (et ANO-F-004) |
| F-40 | 4.4.3 | Critères imposés : type, date du document, nom, objet, confidentialité, espace, déposant, date de dépôt | **Non conforme** | F-40 : type, nom, objet, espace (recherche sur métadonnées) et date de dépôt (plein texte, `/recherches`) filtrent. **Date du document (plage), confidentialité et déposant n'existent dans aucune API** : ignorés en silence (3 résultats sur 3 au lieu de 1, 0, 1). Écran : type, espace, date de dépôt, canal seulement | ANO-F-011, ANO-F-010 |
| F-41 | 4.4.3 | Plein texte avec extraits mis en évidence | Conforme | F-41 : extraits en segments, terme cherché marqué (« Référence de recette : [zarkolinet] »). Écran `#/recherche` : extraits sous chaque résultat, terme en `<mark>` | — |
| F-42 | 4.4.3 | Critères combinés en ET | Conforme | F-42 : type ET payée ET 200-1000 → 1 ; non payée ET 800-1000 → 0 ; objet ET statut → 1 ; plein texte + type : 79 → 11. Écran : critères du plein texte cumulés (les critères d'index manquent : ANO-F-010) | — |
| F-43 | 4.4.3 | Filtrage à la source, compteurs compris | Conforme | F-43 : quatre documents placés en Finance, Factures 2026, Réservé direction, Projets → totaux Administrateur 4, qa2val1 3, yalaoui 1, nidrissi 1 ; plein texte : total = nombre de résultats, tous consultables, pour chaque persona | — |
| F-44 | 4.4.3 | Résultats paginés et triables, non OCRisés signalés | API conforme, écran partiel | F-44 : plein texte paginé et trié (nom, date de dépôt, type, pertinence), liste triable, recherche sur métadonnées paginée ; état OCR dans les réponses de liste et de fiche. Écran : pagination et tri présents ; l'état OCR n'est signalé **que sur la fiche** (bandeau), ni dans la liste ni dans les résultats | ANO-F-017 |
| F-45 | 4.4.5 | Niveaux Public, Privé, Confidentiel | API conforme, écran partiel | F-45 : standard voit Public seulement ; Agent d'archive (Voir privé) voit Privé, pas Confidentiel ; désignation 200 → le désigné voit le Confidentiel, un autre 404 ; le déposant voit son Confidentiel. Écran : niveau choisi au dépôt et sur la fiche (« Confidentiel (personnes désignées) »), mais **aucun écran de désignation** | ANO-F-013 |
| F-46 | 4.5.3 | Circuit par type, rattachable à un espace ou dossier | Conforme | F-46 : règle rattachée au type (204) et au dossier (204) ; dépôt → circuit de la règle du type, du dossier (origine `NOEUD`) ; la règle du type prime. Écran : sélecteur « Règle de workflow » sur la fiche du type et dans le formulaire de l'espace | — |
| F-47 | 4.5.3, D7 | Validateurs indépendants, sans ordre | Conforme | F-47 : les deux validateurs ont la demande dès le dépôt ; le 2e affiché décide le premier (→ `EN_COURS`), puis le 1er (→ `VALIDE`). Écran `#/mes-workflow` : « À traiter », Valider / Refuser | — |
| F-48 | 4.5.3 | Valider ou refuser, motif obligatoire | Conforme | F-48 : refus sans motif ou motif blanc 400 `MOTIF_OBLIGATOIRE`, refus motivé → `REFUSE` | — |
| F-49 | 4.5.3 | Nouvelle version : revalider ou annuler | Conforme | F-49 : versement → circuit `EN_COURS` sur la v2, 2 décisions caduques, redemandé aux deux ; un validateur retire sa décision (`ANNULEE`, → `EN_ATTENTE`). Écran : « décisions sur la version 2 », « Retirer ma décision » dans l'historique | — |
| F-50 | 4.5.3 | Décision nominative, horodatée, motivée | Conforme | F-50 : auteur « Valideur Un », horodatage, motif, version ; `VALIDATION_REJETEE` au journal avec l'acteur. Écran : « Décisions (n) » sur la fiche | — |
| F-51 | 4.5.3 | Notification des validateurs et du déposant | Conforme (courriel au simulateur) | F-51 : `CIRCUIT_OUVERT` aux deux validateurs, `CIRCUIT_DECISION` au déposant ; courriels reçus par le SMTP simulé (qa2val1 3, qa2agent 5). Écran `#/notifications`. Courriel à rejouer en UAT sur le relais de MMED | — |
| F-52 | 4.5.3 | Diffusion du document validé | Conforme (réserve ANO-F-001) | F-52 : diffusion par un standard (Diffuser) à une personne et un groupe → 2 habilitations de lecture, écriture refusée (403) ; document refusé 409 `DOCUMENT_NON_VALIDE` ; l'Agent d'archive ne peut pas diffuser (403, ANO-F-001). Écran : bouton « Diffuser » sur la fiche | ANO-F-001 |
| F-53 | 4.5.4 | Règle modifiable, circuit figé | Conforme | F-53 : règle modifiée (200) ; le circuit ouvert garde ses deux validateurs, le dépôt suivant n'a que la DG | — |
| F-54 | 4.5.4 | Validateur nommé ou par rôle | Conforme | F-54 : validateur `ROLE` (Utilisateur standard sur QA2 Finance) : à traiter pour qa2val1 et qa2val2, pas pour nidrissi ; décision de qa2val2 → `VALIDE`. Écran des règles : « rôle UTILISATEUR_STANDARD » | — |
| F-55 | 4.6.3 | Versions conservées, auteur et date par version | API conforme, écran partiel | F-55 : 2 versions, auteurs distincts (qa2val1, qa2val2), dates, observation. Écran : tableau des versions (fichier, observation, taille, date) **sans l'auteur** | ANO-F-012 |
| F-56 | 4.6.4, Q6 | Version courante désignable | Conforme | F-56 : v1 redevenue courante (200), téléchargement = contenu de la v1. Écran : « Rendre courante » | — |
| F-57 | 4.6.4 | Verrouillage | Conforme | F-57 : pose par un standard 403, par l'Administrateur 200 avec motif ; sous verrou fiche, version, déplacement, suppression 409 `DOCUMENT_VERROUILLE`, lecture 200 ; levée puis modification 200. Écran : « Verrouiller » (Administrateur) | — |
| F-58 | 4.6.3 | Durée par type, alerte d'échéance à l'Agent d'archive | Conforme | F-58 : type de 1 mois, document du 10/01/2025 → échéance 10/02/2025, échu, filtre « échéance dépassée » ; notification `ECHEANCE_CONSERVATION` à l'Agent d'archive (tâche planifiée), pas au standard ; audit `ECHEANCE_CONSERVATION_ATTEINTE`. Écran : bandeau « Échéance dépassée », filtre de la liste, notification | — |
| F-59 | 4.6.5 | Import et export unitaires | Conforme | F-59 : dépôt d'un .docx puis téléchargement à l'identique (même SHA-256), nom d'origine. Écran : « Téléverser un document », « Télécharger » | — |
| F-60 | 4.6.5 | Export ZIP d'un dossier avec manifeste | Conforme | F-60 : ZIP avec sous-dossier et `manifeste.csv` (identifiant, chemins, nom, objet, type, dates, déposant, confidentialité, statut, version, empreinte, canal) ; export d'un standard sans le document Privé. Écran : « Exporter (ZIP) » sur la fiche du dossier, `#/mes-exports` | — |
| F-61 | 4.6.5 | Corbeille puis purge définitive | Conforme (réserve ANO-F-001) | F-61 : purge d'un document vivant 409 ; corbeille ; purge par un standard 403, **par l'Agent d'archive 403** (ANO-F-001), par l'Administrateur 204 ; restauration puis fiche 404 ; audit `DOCUMENT_PURGE`. Écran : purge depuis la vue corbeille | ANO-F-001 |
| F-62 | 4.6.5 | Dossiers partagés pour un groupe | Conforme (réserve ANO-F-016) | F-62 : groupe de deux membres habilité sur un dossier de l'espace d'échange : dossier visible des membres, dépôts 202, chacun voit les deux documents, hors groupe rien. Le membre crée un sous-dossier (201) mais ne peut **pas y ranger** un document (déplacement 403, le dépôt n'a pas d'emplacement) | ANO-F-016 |
| F-63 | 4.6.5 | Prévisualisation des formats courants | Conforme | F-63 : aperçu `inline` PDF, PNG, Word converti en PDF (LibreOffice réel), texte ; hors droits 404. Écran : « Aperçu de la version » | — |
| F-64 | 4.6.5 | Formats et taille par type | Conforme | F-64 : PNG sur un type PDF 415, exécutable déguisé 415, 5,6 Mo sur un type à 5 Mo 413, 4 Mo 202, version PNG sur un type PDF 415. Observation : la taille maximale d'un type ne peut pas descendre sous 5 Mo (400). Écran : formats et taille dans le formulaire du type | — |
| F-65 | 4.6.6 | Notifications limitées à trois cas | Conforme | F-65 : familles observées `CIRCUIT_VALIDATION`, `ACCES_ESPACE`, `FIN_CONSERVATION` seulement ; attribution d'accès notifiée (nidrissi). Écran : « Circuits de validation, accès attribués, fins de conservation », préférence courriel | — |
| F-66 | 4.7.4, D10 | Archivage par statut, réversible, lecture seule, en recherche | Conforme | F-66 : standard 403, Agent d'archive 200 → `ARCHIVE` ; fiche et version 409 `DOCUMENT_ARCHIVE` ; trouvé en recherche, filtre « archivés » ; dossier entier (job `TERMINE` 2/2) ; désarchivage 204 → `ACTIF`. Écran : « Archiver » / « Désarchiver », « Archiver le dossier », badge « ARCHIVÉ » | — |
| F-67 | 4.7.4 | Empreinte et PDF/A | Conforme | F-67 : empreinte de la version = SHA-256 du fichier déposé ; copie de conservation `VALIDE`, format `PDF/A-2B` d'un .docx. Écran : « Copie de conservation PDF/A-2B servie au téléchargement ; l'original est conservé » | — |
| F-68 | 4.7.3 | Traçabilité des consultations d'archives | Conforme | F-68 : `DOCUMENT_CONSULTE`, `DOCUMENT_TELECHARGE`, `APERCU_CONSULTE` de qa2val1 sur le document archivé | — |
| F-69 | 4.9.3, 4.9.4 | Journal d'audit | Conforme | F-69 : 18 actions attendues présentes (connexion, consultation, téléchargement, dépôt, versions, métadonnées, verrou, archivage, suppression, purge, export, habilitation, validations, diffusion, refus) ; qui, quand, d'où, objet ; export CSV 200. Écran `#/journal-audit` : filtres, exports CSV et JSON | — |
| F-70 | 4.9.3, D11 | Journaux inaltérables, Administrateur compris | Conforme | F-70 : aucune modification ni suppression par l'API (404, 405) ; `ged_app` n'a que `INSERT, SELECT` sur `journal_audit`, déclencheur `trg_journal_audit_ajout_seul` (constat en lecture des droits) ; vérification du scellement : 1 période, 1 321 enregistrements, 0 anomalie. Écran : « lecture seule », « Vérifier le scellement » | — |
| F-71 | 4.9.3 | Pattern de l'Article 50 | Conforme | F-71 : 819/819 lignes du journal technique au pattern, `username` et `ip` renseignés (« 11:52:39.951 - [qa2val1] [127.0.0.1] [http-nio-18088-exec-1] - 91a3…/fdc1… WARN … ») | — |
| F-72 | 4.10.3 | Intégration via API REST | Conforme | F-72 : OpenAPI 200, 159 chemins, contrat d'intégration (`/recherches`, `/documents/{id}/contenu`, `/noeuds/{id}/dossiers`, droits). Désactivée en production par configuration (à livrer en fichier) | — |
| F-73 | 4.10.3 | Clé d'API par application, périmètre configurable | Conforme | F-73 : 2 applications ; clé de l'intranet bornée à QA2 Projets (consultation, recherche) : lecture Projets 200, Finance 404, dépôt 403. Écran `#/cles-api` : applications, clés, portée, régénération, révocation | — |
| F-74 | 4.10.3 | Contrat d'interface avec le bureau d'ordre | Non testable ici | Le contrat générique existe (OpenAPI, contrat E9 : dépôt par clé, canal `BUREAU_ORDRE`, F-19) ; le contrat **propre au bureau d'ordre** reste à rédiger en atelier avec MMED (question Q18 de `RISQUES.md`) : aucun document dans le dépôt | — (Q18) |
| F-75 | 4.13 | Échelle de confidentialité, croisée avec les habilitations | Conforme (réserve ANO-F-013) | F-75 : niveau par défaut du type appliqué (Confidentiel), abaissé par document (200) → visible du standard, audit `CONFIDENTIALITE_MODIFIEE` ; niveau hors échelle 400. Échelle à trois niveaux fixes (§12.3 du dossier technique). Écran : niveau par défaut du type, niveau sur la fiche ; désignation absente | ANO-F-013 |
| F-76 | 4.14 | Loi 09-08 : accès restreint, traçabilité des données personnelles | Conforme | F-76 : contrat RH Privé par défaut ; lecteur de l'espace RH sans Voir privé 404 et absent de sa recherche ; Responsable RH et DG 200 ; consultation et refus tracés | — |
| F-77 | 4.15 | Chiffrement de tous les documents | Conforme | F-77 : 69 fichiers du référentiel, tous en `.enc`, aucun lisible en clair (ni en-tête PDF/ZIP/PNG ni contenu connu) ; téléchargement déchiffré 200 | — |

## 4. Taux (77 lignes, tour 1)

| Verdict | Nombre | Lignes |
|---|---|---|
| Conforme (dont réserve mineure ou renvoi à une ANO-F d'une autre ligne) | 58 | F-02, F-03, F-04, F-06, F-07, F-08, F-11, F-13 à F-19, F-21 à F-26, F-29, F-31, F-33 à F-35, F-41 à F-43, F-46 à F-54, F-56 à F-73, F-75 à F-77 |
| API conforme, écran absent ou partiel | 14 | F-01, F-05, F-20, F-27, F-28, F-30, F-32, F-36 à F-39, F-44, F-45, F-55 |
| Non conforme | 2 | F-09, F-40 |
| Non testable ici | 3 | F-10, F-12 (AD réel, conformes au simulateur), F-74 (contrat du bureau d'ordre, Q18) |

Taux de conformité fonctionnelle : **58/77 = 75 %** de bout en bout ; **72/77 = 94 %** si l'on
compte les lignes dont seul l'écran manque ; 3 lignes ne se prononcent qu'en UAT ou après l'atelier
avec MMED. Les écarts d'écran se concentrent sur la **recherche** (aucun écran de recherche
multicritère sur les index, ANO-F-010) et sur des fonctions servies par l'API sans écran
(désignation des personnes d'un document confidentiel, rattachement et déplacement d'un document,
re-typologie, activation d'un type, auteur des versions). Les deux lignes non conformes tiennent à
la composition du rôle Agent d'archive (F-09, ANO-F-001, en correction) et à trois critères de
recherche imposés absents de l'API (F-40, ANO-F-011).

Ces chiffres portent sur la base `71bdc1d`, sans les corrections des ANO-F-001 à 009 faites en
parallèle ; ils seront recalculés au tour suivant.
