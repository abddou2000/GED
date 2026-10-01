# Résultats de la recette fonctionnelle — Dossier d'analyse des besoins fonctionnels V3

Exécutée par qa2 : F-01 à F-20 le 28/09/2026 sur `ct/qa2` (partie de `conformite-technique` à
**bc371ad**) ; **F-21 à F-77 le 30/09/2026 (tour 1)** sur `ct/qa2-r1`, partie de
`claude/inspiring-lovelace-10bg1c` à **71bdc1d** ; **tour 2 le 30/09/2026** sur `ct/qa2-r2`, partie
de `claude/inspiring-lovelace-10bg1c` à **08c710c** (corrections du tour 1 intégrées) :
revérification d'ANO-F-001 et ANO-F-003 à ANO-F-009 (toutes vérifiées), rejeu complet A à D,
lignes concernées reprononcées ; **tour 3 le 01/10/2026** sur `ct/qa2-r3`, partie de
`claude/inspiring-lovelace-10bg1c` à **ff20f21** (corrections du tour 2 intégrées) : revérification
d'ANO-F-010 à ANO-F-024 (toutes vérifiées), rejeu complet A à D, lignes concernées reprononcées,
volet fonctionnel de P-21 et R-03 (§5). Aucun code applicatif modifié. **État : les 77 lignes sont
prononcées.**

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

**Tour 2 (30/09/2026, revérifications)** — même poste, même instance (JAR reconstruit sur
`ct/qa2-r2` à 08c710c ; au démarrage Liquibase applique les changesets du tour 1, dont
`202610041000_composition_agent_archive`), même base `ged_qa2` (jeu des exécutions précédentes
conservé), front servi sur 4388 et parcouru avec Chromium sous nidrissi, sbennani, qa2agent et
qa2val1 (captures hors dépôt).

- Premier rejeu (`mmuo5cttc` A, B ; `mmuo5g5f1` C, D) : F-22, F-23, F-26 et F-43 en échec par un
  **défaut de la recette**, pas de l'application : le jeu cumulé dépasse 100 documents contenant
  « zarkolinet » et la recette ne lisait que la 1re page de la recherche plein texte (le scan de
  l'exécution était en 2e page). Recette corrigée : lecture de toutes les pages (F-23, F-26, F-43),
  filtre par type (F-22) ; F-52 et F-61 exigent désormais la diffusion et la purge par l'Agent
  d'archive (ANO-F-001).
- Rejeu de référence : `lancer.sh A B` (**43 OK, 0 ÉCHEC**, 2 AVERT : F-09c à arbitrer, ANO-F-002 ;
  F-12b conforme à D1), puis `lancer.sh C D` (**37 OK, 1 ÉCHEC** F-40, ANO-F-011, 1 NA F-74).
  Marqueurs `mmuo5y5s5` (A, B) et `mmuo623h1` (C, D).
- Écrans : gardes des 11 routes d'administration et accueil (nidrissi) ; index booléen, formulaires
  d'espace et de groupe, écran Rôles (sbennani) ; purge depuis la corbeille et diffusion
  (qa2agent) ; reprise d'un document « à indexer », dépôt avec objet et date, correction sur la
  fiche (qa2val1), chaque action relue à l'API. Fenêtres 1440×1000 et 1366×768.

**Tour 3 (01/10/2026, revérifications)** — même poste, après un redémarrage du conteneur
(PostgreSQL partagé trouvé arrêté, relancé tel quel) ; JAR construit sur `ct/qa2-r3` à ff20f21
(`mvn package -DskipTests`, aucune modification de code), même base `ged_qa2` (jeu des exécutions
précédentes conservé, changesets du tour 2 appliqués au démarrage), front servi sur 4388 et
parcouru avec Chromium sous qa2val1, sbennani, qa2agent et nidrissi (captures hors dépôt).

- Recette : F-62 durcie (ANO-F-016 corrigée) : le membre doit ranger son document dans le
  sous-dossier qu'il a créé (Déposer suffit dans un même espace d'échange), y déposer directement
  (`noeudId`), et un emplacement hors de l'espace d'échange doit être refusé.
- Rejeu : `lancer.sh A B` (**43 OK, 0 ÉCHEC**, 2 AVERT : F-09c, ANO-F-002 à arbitrer ; F-12b
  conforme à D1), puis `lancer.sh C D` (**38 OK, 0 ÉCHEC**, 1 NA F-74). F-40 passe (critères
  imposés). Marqueurs `mmupd50jw` (A, B) et `mmupd9yc4` (C, D).
- Écrans (jeu `r3a`, préparé par l'API) : recherche par index et critères imposés (qa2val1),
  comparés à l'API ; auteur des versions, booléen, état OCR, corbeille ; dates tapées au clavier
  et calendrier ; mise en page de la fiche en 1366×768 et 1440×1000 ; désignation, statut et
  re-typologie d'un type, charte (sbennani) ; déplacer, rattacher, retirer (qa2agent) ; actions
  d'un dossier, nouveau dossier, « Déposer ici », téléchargement et nouvelle version dans
  l'espace d'échange (nidrissi). Chaque action relue à l'API.

## 3. Tableau des 77 exigences

Colonnes : identifiant, référence et page du PDF, exigence, verdict, preuve (identifiant du
scénario et constat), anomalie.

| F | Réf. (page) | Exigence | Verdict | Preuve | Anomalie |
|---|---|---|---|---|---|
| F-01 | 2.2 (p. 4) | Paramétrable sans code : types, métadonnées, circuits, durées, habilitations | Conforme (tour 2) | F-01 : index booléen, type (durée 36 mois, confidentialité par défaut), règle rattachée au type, rôle créés par l'API d'administration (201/204). Écrans : types (durée, point de départ, confidentialité), règles, rôles, habilitations présents. Tour 2 : type **Booléen** proposé à l'écran des index, index booléen créé à l'écran, affiché « Booléen » | ANO-F-007 (vérifiée) |
| F-02 | 2.4 (p. 4-5) | Typologie documentaire administrable | Conforme | F-02 : six catégories créées puis modifiées par l'Administrateur ; utilisateur standard 403. Écrans `#/type-de-document` (liste, fiche, formulaire) | — |
| F-03 | 5 (p. 24) | Interface unique pour tous les modules | Conforme | Une seule application Angular, coque unique (`layout/shell`), tous les écrans enfants de la coque ; parcours du front servi | — |
| F-04 | 5 (p. 24) | Interface exclusivement en français | Conforme (réserve mineure) | `lang="fr"`, `LOCALE_ID fr-FR`, messages d'erreur de l'API en français (`Requête invalide`, `Données invalides`), pagination en français ; un libellé anglais « Workspace » (tour 1), disparu au tour 2. Tour 3 : calendrier en français (« Ouvrir le calendrier », « Mois précédent », « Choisir le mois et l'année », semaine du lundi). Reste : deux dates de la fiche au format aaaa-mm-jj (échéance de conservation, index de type date) | ANO-F-008, ANO-F-024 (vérifiées), ANO-F-025 |
| F-05 | 5 (p. 24) | Menus et fonctions adaptés au profil | Conforme (tour 3) | F-05a : standard sans permission d'administration, habilitations/audit/clés 403. Front (`nidrissi`) : menu réduit (Accueil, Mes validations, Espaces, Documents, Recherche par index, Recherche, Mes exports). Tour 2 : les 11 adresses d'administration ramènent à l'accueil ; accueil sans « Créer un espace » ni lien vers groupes ou index. Tour 3 : la fiche d'un dossier n'affiche plus que les actions que le serveur accepte (ni sous-dossier en espace métier, ni archivage, ni suppression) ; « Modifier » y reste, le serveur l'acceptant (voir F-35) | ANO-F-003, ANO-F-004, ANO-F-018 (vérifiées) |
| F-06 | 3.1 (p. 5) | Trois dimensions : profil, rôle organisationnel, périmètre | Conforme | F-06 : droits obtenus par un groupe GED (origine « groupe » aux droits effectifs), bornés au rôle du groupe (dépôt 403), autre périmètre sans accès, direction lue dans l'annuaire. Écrans groupes et habilitations (sujet Utilisateur ou Groupe). Tour 2 : membre ou espace inconnu refusé (422 `MEMBRES_INCONNUS`, `ESPACES_INCONNUS`) en création et en modification ; groupe créé à l'écran avec son membre | ANO-F-009 (vérifiée) |
| F-07 | 3.1.1 (p. 5-6) | Périmètre à trois niveaux, héritage, plus spécifique, union | Conforme | F-07a héritage vers le sous-dossier, pas vers le parent ; F-07b rupture en lecture seule qui prévaut (dépôt 403 sous rupture, 202 ailleurs) ; F-07c document isolé visible seul. Écran habilitations : nœud ou document, case « Rupture d'héritage » ; écran droits effectifs | — |
| F-08 | 3.2 (p. 6) | Quatre rôles composables, cumulables | Conforme | F-08 : quatre rôles système, rôle « Responsable RH » composé par l'API, cumul Standard + Lecteur sur qa2val1. Écran `#/administration/roles` (création, composition, suppression d'un rôle non attribué) | — |
| F-09 | 3.2.1 (p. 6-7) | Neuf permissions élémentaires ; composition des rôles | Conforme (tour 2, réserve ANO-F-002 à arbitrer) | F-09a : neuf permissions au catalogue (OK). F-09b : Agent d'archive sans Valider, Diffuser, Purger au tour 1 ; **tour 2 : OK**, les neuf permissions (API, base, écran Rôles), diffusion et purge effectives (F-52, F-61, purge à l'écran). F-09c : Direction Générale avec Déplacer, Archiver, Supprimer (à arbitrer). F-09d : Standard ⊇ Consulter, Déposer, Valider | ANO-F-001 (vérifiée), ANO-F-002 |
| F-10 | 3.3 (p. 7) | Authentification Active Directory via LDAP | Non testable ici (AD réel absent) — conforme au simulateur | F-10 : compte AD accepté (200), mauvais mot de passe 401 `IDENTIFIANTS_REFUSES`, connexion par adresse e-mail refusée (400, D2). Écran de connexion « Identifiant Windows ». À rejouer en UAT sur l'AD de MMED | — |
| F-11 | 3.3, 3.3.1 (p. 7-8) | Pas de création de compte ; identité à la 1re connexion ; rôles manuels | Conforme | F-11 : `POST /admin/utilisateurs` 405 ; qa2neuf1 inconnu avant sa 1re connexion, créé sans rôle ni permission (liste des documents 403), rôle attribué par l'Administrateur avec effet immédiat. Aucun écran de création de compte ; écran habilitations | — |
| F-12 | 3.3.1 (p. 8) ; D1 | Désactivation d'un compte AD | Non testable ici (AD réel absent) — conforme à D1 au simulateur | F-12a : compte désactivé dans l'annuaire simulé → connexion 401, ses documents restent consultables. F-12b : la validation en cours du validateur désactivé **n'est pas signalée** (`/workflow/anomalies` vide) et la session ouverte reste valide jusqu'à expiration : conforme à **D1** (la GED ne relit plus l'état AD), risques R26/R27 ; réaffectation manuelle par l'Administrateur disponible | — (D1) |
| F-13 | 3.4 (p. 8) | Clés d'API : générer, consulter, révoquer, régénérer, périmètre, délégation | Conforme | F-13a génération 201, portée par nœud et opérations, secret jamais réaffiché ; F-13b délégation `X-On-Behalf-Of` (déposant nominatif, double identité au journal), hors portée 404 ; F-13c régénération avec chevauchement, révocation effective (401 `CLE_API_REVOQUEE`). Écran `#/cles-api` complet (génération, portée, régénération, révocation motivée) | — |
| F-14 | 3.5 (p. 9) | Modification des droits sans intervention sur le code | Conforme | F-14 : attribution puis retrait d'une habilitation, recomposition d'un rôle, effet immédiat sur la visibilité. Écrans habilitations et rôles | — |
| F-15 | 3.6 (p. 9) | Aucune suppression définitive : corbeille réversible partout | Conforme | F-15 : suppression, présence en corbeille et restauration pour documents (par l'Agent d'archive), espaces, types, index, plans, règles, étiquettes, groupes. Écrans : vues « corbeille » et restauration dans chaque liste ; tour 3 : dans la liste des documents, bouton « Corbeille », « La corbeille est vide » (plus de « Archive ») | ANO-F-023 (vérifiée) |
| F-16 | 4.1.3 (p. 9) | Enregistrement unique et horodatage automatique | Conforme | F-16 : identifiant unique et horodatage à chaque réception, deux dépôts identiques → deux identifiants. Fiche : « Déposé le » | — |
| F-17 | 4.1.3, 4.1.6 (p. 10) | Identification de la source et du déposant | Conforme | F-17a canal `INTERFACE`, déposant = utilisateur authentifié ; F-17b `createdById` de la requête ignoré ; F-19 canal `BUREAU_ORDRE` et application ; F-13b déposant délégué. Fiche : « Canal de dépôt », « Créateur » | — |
| F-18 | 4.1.4 (p. 10) | Rattachement obligatoire à une catégorie existante | Conforme | F-18 : sans type 400, type inconnu 404, type en corbeille 400. Écran de dépôt : type obligatoire | — |
| F-19 | 4.1.4, 4.1.5 (p. 10) | Bureau d'ordre par API REST sécurisée par clé d'API | Conforme | F-19 : dépôt par clé 202, canal `BUREAU_ORDRE`, application identifiée, type « Courrier entrant », transmis à l'OCR (`EN_ATTENTE_OCR`) ; sans clé 401 | — |
| F-20 | 4.1.7 (p. 10) | Trois issues : indexé, sans plan, à indexer | Conforme (tour 2) | F-20 : `INDEXE`, `SANS_PLAN`, `A_INDEXER` puis reprise par `PUT /indexation/documents/{id}` → `INDEXE`. Tour 2 : pastille « À indexer » dans la liste, bandeau et saisie des index sur la fiche, enregistrement à l'écran → `INDEXE` (qa2val1). Tour 3 : bloc de saisie entièrement affiché en 1366×768 (plus de défilement interne, la page défile) | ANO-F-005, ANO-F-021 (vérifiées) |
| F-21 | 4.2.1 | Moteur OCR open source Tesseract | Conforme | F-21 : `/ocr/etat` actif, moteur disponible, langues `ara, eng, fra, osd`, défaut `ara+fra`. Écran `#/traitements-ocr` (Administrateur) : chaîne active, moteur, langues, compteurs | — |
| F-22 | 4.2.1 | OCR disponible pour l'interface et pour les applications | Conforme | F-22 : texte OCR d'un scan lu par l'utilisateur (200) et par une clé d'API (200) ; recherche plein texte par clé (200) qui trouve le scan. Fiche : bandeau d'état OCR ; écran `#/recherche`. Tour 2 : premier rejeu en échec (F-22, F-23, F-26) par un défaut **de la recette** — jeu cumulé de 141 documents « zarkolinet », scan de l'exécution en 2e page de 100 ; recette corrigée (toutes les pages, filtre par type), rejeu OK | — |
| F-23 | 4.2.1 | L'OCR sert à la recherche plein texte | Conforme | F-23 : scan `OCR_TERMINE`, trouvé par un mot du texte (qa2val2), scan RH hors périmètre exclu | — |
| F-24 | 4.2.4 | L'OCR n'alimente aucune métadonnée | Conforme | F-24 : après OCR, métadonnées vides, objet nul, indexation `A_INDEXER` ; seule proposition d'index tirée du **nom du fichier** (`QA2_NUM←NOM_FICHIER`), aucune du contenu | — |
| F-25 | 4.2.4 | Multipage traité comme une unité | Conforme | F-25 : PDF de 8 pages (scans français puis arabe) OCRisé en entier, trouvé par le témoin français et par le témoin arabe de la dernière page | — |
| F-26 | 4.2.4 | Langues : français et arabe | Conforme | F-26 : scan arabe OCRisé et PDF arabe natif trouvés par un mot arabe, scan français par un mot français (Tesseract réel `ara+fra`) | — |
| F-27 | 4.2.3 | Socle : identifiant, nom, objet, type, date du document, dépôt, déposant, confidentialité, conservation | Conforme (tour 3, réserve mineure) | F-27 : dépôt avec objet, date du document, confidentialité ; échéance déduite du type (12 mois) ; correction objet/date 200. Tour 2 : objet et date saisis au dépôt et corrigés sur la fiche à l'écran. Tour 3 : date **tapée** en jj/mm/aaaa lue correctement (« 03/04/2026 » → 2026-04-03 sur la fiche, « 15/03/2026 » → 2026-03-15 au dépôt, « 31/02/2026 » invalide) ; fiche lisible en 1366×768. Réserve : échéance de conservation affichée « 2036-10-01 » | ANO-F-006, ANO-F-021, ANO-F-022 (vérifiées), ANO-F-025 |
| F-28 | 4.2.3 | Métadonnées additionnelles paramétrables | Conforme (tour 3, réserve mineure) | F-28 : texte, nombre, date, liste, booléen ; obligatoire manquant, valeur hors liste, nombre invalide → 400 `METADONNEES_INVALIDES` ; valeur par défaut appliquée. Écran : type Booléen à l'écran des index (tour 2) ; tour 3 : booléen affiché « Oui » / « Non » sur la fiche. Réserve : un index de type date s'affiche « 2026-11-15 » | ANO-F-007, ANO-F-020 (vérifiées), ANO-F-025 |
| F-29 | 4.2.5 | Référentiel d'index, plan, charte de nommage | Conforme | F-29 : nom composé au dépôt « FAMMUPD50JW_DÉFINITIF_26 » (jetons d'index, jeton année, séparateur, majuscules). Tour 3 : aperçu de la charte « NUMÉRO DE PIÈCE_STATUT_ANNÉE » à l'API et à l'écran des plans (plus de « YEAR ») | ANO-F-019 (vérifiée) |
| F-30 | 4.2.4 | Gouvernance des types : type utilisé protégé, re-typologie en lot | Conforme (tour 3) | F-30 : suppression d'un type utilisé 409 `TYPE_UTILISE` ; retypage de 3 documents 202 → `TERMINE` 3/3 ; standard 403. Tour 3 : écran « Re-typologie en lot » (source, cible, correspondance des champs, tous ou une sélection, historique et rapport) : 2 documents re-typés à l'écran, `TERMINE` 2/0/2, relus au type cible | ANO-F-015 (vérifiée) |
| F-31 | 4.3.2 | Espaces puis dossiers de profondeur libre | Conforme | F-31 : six niveaux de dossiers sous un espace, document du 6e niveau visible d'un standard de l'espace ; Agent d'archive refusé en espace métier (403). Écran `#/espaces-de-travail` (arbre, sous-dossiers) | — |
| F-32 | 4.3.2, D12 | Deux natures d'espaces : métier et échange | Conforme (tour 3, renvoi ANO-F-026) | F-32 : espace `ECHANGE` ; un membre y crée dossier et sous-dossier par l'API (201), même geste refusé en espace métier (403). Tour 3 : dans un dossier partagé, « Nouveau dossier » ouvre un formulaire simple (nom, description) et crée le dossier ; « Déposer ici » range le document dans le dossier choisi. Mais un standard peut basculer son espace métier en échange (F-35, ANO-F-026) | ANO-F-016 (vérifiée), ANO-F-026 |
| F-33 | 4.3.4 | Création d'espace réservée à l'Administrateur | Conforme | F-33 : Agent d'archive, DG, standard 403 ; Administrateur 201, espace aussitôt visible de la DG. Écran : pas de bouton de création pour un standard | — |
| F-34 | 4.3.4 | Espaces non autorisés invisibles | Conforme | F-34 : arbre de yalaoui réduit à Exercice 2026 (Finance en simple passage), sans Réservé ni RH ; nidrissi ne voit pas la Finance ; fiche RH 404. Écran : arbre de qa2val1 (Finance, RH), de nidrissi (Échange, Projets) | — |
| F-35 | 4.3.4 | Modification non autorisée bloquée et journalisée | **Non conforme** (tour 3, à arbitrer pour le renommage) | F-35 : renommage d'un dossier en lecture seule (rupture), déplacement, suppression par un standard 403, refus au journal. Tour 3 : la fiche n'expose plus les actions refusées (ANO-F-018 vérifiée) ; mais `PUT /workspaces/{id}` n'exige que **Modifier**, que porte le standard : nidrissi modifie QA2 Projets (200), en change l'usage `METIER` → `ECHANGE` (200), y crée alors un dossier (201), puis rétablit l'usage. Nom, propriétaire, règle de workflow et nature d'un espace sont modifiables par un standard | ANO-F-018 (vérifiée), ANO-F-026 |
| F-36 | 4.3.5 | Déplacement d'un fichier ou d'un dossier, journalisé | Conforme (tour 3, réserve mineure) | F-36 : sans Déplacer 403, destination hors droits 404, déplacement 200 et audit ; dossier déplacé avec sa sous-arborescence, audit. Tour 3 : bloc « Emplacements » de la fiche, « Déplacer » vers un dossier choisi (qa2agent), relu à l'API. Réserve : le membre d'un espace d'échange, que le serveur autorise à ranger (Déposer), n'a pas « Déplacer » à l'écran | ANO-F-014 (vérifiée), ANO-F-027 |
| F-37 | 4.3.6 | Rattachement à plusieurs espaces sans duplication | Conforme (tour 3) | F-37 : rattachement 201, visible depuis RH, une occurrence en liste et en recherche, retrait 204 sans effet sur l'original. Tour 3 : « Rattacher » et retrait depuis la fiche (qa2agent), relus à l'API | ANO-F-014 (vérifiée) |
| F-38 | 4.3.4 | Durée de conservation et statut par type | Conforme (tour 3) | F-38 : durée 12 mois, point de départ, échéance déduite ; type désactivé → dépôt 400. Tour 3 : statut « Actif » / « Désactivé » sur la fiche du type, « Désactiver » puis « Réactiver » relus à l'API (`actif`) | ANO-F-015 (vérifiée) |
| F-39 | 4.4.3 | Recherche multicritère sur index, plages de dates et de nombres | Conforme (tour 3) | F-39 : `POST /documents/recherche` : montant 200-1000, échéance juin-déc., liste « Brouillon », booléen, texte insensible à la casse → résultats exacts. Tour 3 : écran « Recherche par index » (menu et accueil) : un critère par index de recherche (plages pour dates et nombres, liste, Oui/Non, texte) ; Montant 200-1000 ET Payée Oui → 1, Statut Brouillon → 2, identiques à l'API ; résultats paginés, triés, état OCR signalé | ANO-F-010 (vérifiée) |
| F-40 | 4.4.3 | Critères imposés : type, date du document, nom, objet, confidentialité, espace, déposant, date de dépôt | Conforme (tour 3, réserve mineure) | F-40 : les huit critères filtrent ; date du document (plage), confidentialité et déposant ajoutés à `POST /documents/recherche`, `POST /recherches` et au plein texte (1, 0, 1 sur 3 factures, attendus) ; paramètre inconnu 400 `PARAMETRE_INCONNU`. Écran « Recherche par index » : type, nom ou objet, emplacement, date du document, confidentialité, déposant (1, 0, 1, comme l'API). Réserve : la date de dépôt n'est un critère qu'à l'écran plein texte, qui exige des termes | ANO-F-011, ANO-F-010 (vérifiées), ANO-F-028 |
| F-41 | 4.4.3 | Plein texte avec extraits mis en évidence | Conforme | F-41 : extraits en segments, terme cherché marqué (« Référence de recette : [zarkolinet] »). Écran `#/recherche` : extraits sous chaque résultat, terme en `<mark>` | — |
| F-42 | 4.4.3 | Critères combinés en ET | Conforme | F-42 : type ET payée ET 200-1000 → 1 ; non payée ET 800-1000 → 0 ; objet ET statut → 1 ; plein texte + type : 79 → 11. Écran : critères du plein texte cumulés (les critères d'index manquent : ANO-F-010) | — |
| F-43 | 4.4.3 | Filtrage à la source, compteurs compris | Conforme | F-43 : quatre documents placés en Finance, Factures 2026, Réservé direction, Projets → totaux Administrateur 4, qa2val1 3, yalaoui 1, nidrissi 1 ; plein texte : total = nombre de résultats, tous consultables, pour chaque persona. Tour 2 : premier rejeu en échec par un défaut de la recette (comparaison limitée à la 1re page de 100 alors que le jeu cumulé en compte 172) ; recette corrigée (toutes les pages), rejeu OK : totaux 236, 193, 123, 26, tous consultables | — |
| F-44 | 4.4.3 | Résultats paginés et triables, non OCRisés signalés | Conforme (tour 3) | F-44 : plein texte paginé et trié (nom, date de dépôt, type, pertinence), liste triable, recherche sur métadonnées paginée ; état OCR dans les réponses. Tour 3 : pastille « OCR en attente » dans la liste et dans la recherche par index (document à l'OCR en attente) ; le plein texte renvoie vers la recherche par index pour les documents non OCRisés | ANO-F-017 (vérifiée) |
| F-45 | 4.4.5 | Niveaux Public, Privé, Confidentiel | Conforme (tour 3) | F-45 : standard voit Public seulement ; Agent d'archive (Voir privé) voit Privé, pas Confidentiel ; désignation 200 → le désigné voit le Confidentiel, un autre 404 ; le déposant voit son Confidentiel. Tour 3 : bloc « Personnes autorisées (Confidentiel) » sur la fiche ; désignation à l'écran → le désigné (yalaoui) lit le document (200) | ANO-F-013 (vérifiée) |
| F-46 | 4.5.3 | Circuit par type, rattachable à un espace ou dossier | Conforme | F-46 : règle rattachée au type (204) et au dossier (204) ; dépôt → circuit de la règle du type, du dossier (origine `NOEUD`) ; la règle du type prime. Écran : sélecteur « Règle de workflow » sur la fiche du type et dans le formulaire de l'espace | — |
| F-47 | 4.5.3, D7 | Validateurs indépendants, sans ordre | Conforme | F-47 : les deux validateurs ont la demande dès le dépôt ; le 2e affiché décide le premier (→ `EN_COURS`), puis le 1er (→ `VALIDE`). Écran `#/mes-workflow` : « À traiter », Valider / Refuser | — |
| F-48 | 4.5.3 | Valider ou refuser, motif obligatoire | Conforme | F-48 : refus sans motif ou motif blanc 400 `MOTIF_OBLIGATOIRE`, refus motivé → `REFUSE` | — |
| F-49 | 4.5.3 | Nouvelle version : revalider ou annuler | Conforme | F-49 : versement → circuit `EN_COURS` sur la v2, 2 décisions caduques, redemandé aux deux ; un validateur retire sa décision (`ANNULEE`, → `EN_ATTENTE`). Écran : « décisions sur la version 2 », « Retirer ma décision » dans l'historique | — |
| F-50 | 4.5.3 | Décision nominative, horodatée, motivée | Conforme | F-50 : auteur « Valideur Un », horodatage, motif, version ; `VALIDATION_REJETEE` au journal avec l'acteur. Écran : « Décisions (n) » sur la fiche | — |
| F-51 | 4.5.3 | Notification des validateurs et du déposant | Conforme (courriel au simulateur) | F-51 : `CIRCUIT_OUVERT` aux deux validateurs, `CIRCUIT_DECISION` au déposant ; courriels reçus par le SMTP simulé (qa2val1 3, qa2agent 5). Écran `#/notifications`. Courriel à rejouer en UAT sur le relais de MMED | — |
| F-52 | 4.5.3 | Diffusion du document validé | Conforme | F-52 : diffusion par un standard (Diffuser) à une personne et un groupe → 2 habilitations de lecture, écriture refusée (403) ; document refusé 409 `DOCUMENT_NON_VALIDE` ; au tour 1, l'Agent d'archive ne pouvait pas diffuser (403, ANO-F-001). Écran : bouton « Diffuser » sur la fiche. Tour 2 : diffusion par l'Agent d'archive 200, désormais exigée par la recette ; bouton « Diffuser » affiché sous qa2agent | ANO-F-001 (vérifiée) |
| F-53 | 4.5.4 | Règle modifiable, circuit figé | Conforme | F-53 : règle modifiée (200) ; le circuit ouvert garde ses deux validateurs, le dépôt suivant n'a que la DG | — |
| F-54 | 4.5.4 | Validateur nommé ou par rôle | Conforme | F-54 : validateur `ROLE` (Utilisateur standard sur QA2 Finance) : à traiter pour qa2val1 et qa2val2, pas pour nidrissi ; décision de qa2val2 → `VALIDE`. Écran des règles : « rôle UTILISATEUR_STANDARD » | — |
| F-55 | 4.6.3 | Versions conservées, auteur et date par version | Conforme (tour 3) | F-55 : 2 versions, auteurs distincts (qa2val1, qa2val2), dates, observation. Tour 3 : colonne « Auteur » du tableau des versions (« Valideuse Deux », « Valideur Un ») | ANO-F-012 (vérifiée) |
| F-56 | 4.6.4, Q6 | Version courante désignable | Conforme | F-56 : v1 redevenue courante (200), téléchargement = contenu de la v1. Écran : « Rendre courante » | — |
| F-57 | 4.6.4 | Verrouillage | Conforme | F-57 : pose par un standard 403, par l'Administrateur 200 avec motif ; sous verrou fiche, version, déplacement, suppression 409 `DOCUMENT_VERROUILLE`, lecture 200 ; levée puis modification 200. Écran : « Verrouiller » (Administrateur) | — |
| F-58 | 4.6.3 | Durée par type, alerte d'échéance à l'Agent d'archive | Conforme | F-58 : type de 1 mois, document du 10/01/2025 → échéance 10/02/2025, échu, filtre « échéance dépassée » ; notification `ECHEANCE_CONSERVATION` à l'Agent d'archive (tâche planifiée), pas au standard ; audit `ECHEANCE_CONSERVATION_ATTEINTE`. Écran : bandeau « Échéance dépassée », filtre de la liste, notification | — |
| F-59 | 4.6.5 | Import et export unitaires | Conforme | F-59 : dépôt d'un .docx puis téléchargement à l'identique (même SHA-256), nom d'origine. Écran : « Téléverser un document », « Télécharger » | — |
| F-60 | 4.6.5 | Export ZIP d'un dossier avec manifeste | Conforme | F-60 : ZIP avec sous-dossier et `manifeste.csv` (identifiant, chemins, nom, objet, type, dates, déposant, confidentialité, statut, version, empreinte, canal) ; export d'un standard sans le document Privé. Écran : « Exporter (ZIP) » sur la fiche du dossier, `#/mes-exports` | — |
| F-61 | 4.6.5 | Corbeille puis purge définitive | Conforme | F-61 : purge d'un document vivant 409 ; corbeille ; purge par un standard 403, par l'Agent d'archive 403 au tour 1 (ANO-F-001), par l'Administrateur 204 ; restauration puis fiche 404 ; audit `DOCUMENT_PURGE`. Écran : purge depuis la vue corbeille. Tour 2 : purge par l'Agent d'archive 204 (exigée par la recette), et à l'écran sous qa2agent (corbeille → « Purger définitivement » → document détruit). Tour 3 : la vue s'intitule « Corbeille » | ANO-F-001, ANO-F-023 (vérifiées) |
| F-62 | 4.6.5 | Dossiers partagés pour un groupe | Conforme (tour 3) | F-62 : groupe de deux membres habilité sur un dossier de l'espace d'échange : dossier visible des membres, dépôts 202, chacun voit les deux documents, hors groupe rien. Tour 3 (recette durcie) : le membre crée un sous-dossier (201), y range son document (200), y dépose directement (202, `noeudId`) ; emplacement hors de l'espace d'échange 422 `EMPLACEMENT_HORS_ESPACE_ECHANGE`. Écran : « Nouveau dossier », « Déposer ici » avec choix du dossier, téléchargement, nouvelle version | ANO-F-016 (vérifiée), ANO-F-027 |
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
| F-75 | 4.13 | Échelle de confidentialité, croisée avec les habilitations | Conforme (tour 3) | F-75 : niveau par défaut du type appliqué (Confidentiel), abaissé par document (200) → visible du standard, audit `CONFIDENTIALITE_MODIFIEE` ; niveau hors échelle 400. Échelle à trois niveaux fixes (§12.3 du dossier technique). Écran : niveau par défaut du type, niveau sur la fiche, désignation des personnes (tour 3) | ANO-F-013 (vérifiée) |
| F-76 | 4.14 | Loi 09-08 : accès restreint, traçabilité des données personnelles | Conforme | F-76 : contrat RH Privé par défaut ; lecteur de l'espace RH sans Voir privé 404 et absent de sa recherche ; Responsable RH et DG 200 ; consultation et refus tracés | — |
| F-77 | 4.15 | Chiffrement de tous les documents | Conforme | F-77 : 69 fichiers du référentiel, tous en `.enc`, aucun lisible en clair (ni en-tête PDF/ZIP/PNG ni contenu connu) ; téléchargement déchiffré 200 | — |

## 4. Taux (77 lignes)

### Tour 3 (base ff20f21, corrections des ANO-F-010 à 024 intégrées)

| Verdict | Nombre | Lignes |
|---|---|---|
| Conforme (dont réserve ou renvoi à une ANO-F d'une autre ligne) | 73 | F-01 à F-09, F-11, F-13 à F-34, F-36 à F-73, F-75 à F-77 |
| API conforme, écran absent ou partiel | 0 | — |
| Non conforme | 1 | F-35 |
| Non testable ici | 3 | F-10, F-12 (AD réel, conformes au simulateur), F-74 (contrat du bureau d'ordre, Q18) |

Taux de conformité fonctionnelle : **73/77 = 95 %** de bout en bout (81 % au tour 2, 75 % au tour
1) ; plus aucune ligne dont seul l'écran manque. Passent conformes : les onze lignes dont l'écran
manquait (F-27, F-28, F-30, F-32, F-36 à F-39, F-44, F-45, F-55) et F-40 (critères imposés). Seule
ligne non conforme : **F-35**, nouvellement : un Utilisateur standard modifie un espace par
`PUT /workspaces/{id}` (nom, propriétaire, règle, et usage métier → échange, ce qui lui ouvre la
création de dossiers refusée en espace métier ; ANO-F-026, Majeure, à arbitrer par pm pour la
portée de Modifier sur un nœud). Réserves mineures nouvelles : dates de la fiche en aaaa-mm-jj
(F-27, F-28, F-04 ; ANO-F-025), rangement après coup absent de l'écran pour le membre d'un espace
d'échange (F-36, F-62 ; ANO-F-027), date de dépôt seulement en plein texte (F-40 ; ANO-F-028).
Restent : ANO-F-002 (Direction Générale, à arbitrer) et les trois lignes non testables ici.

### Tour 2 (base 08c710c, corrections des ANO-F-001 et ANO-F-003 à 009 intégrées)

Ces chiffres portaient sur la base 08c710c ; ils sont recalculés ci-dessus (tour 3).

| Verdict | Nombre | Lignes |
|---|---|---|
| Conforme (dont réserve ou renvoi à une ANO-F d'une autre ligne) | 62 | F-01 à F-09, F-11, F-13 à F-26, F-29, F-31, F-33 à F-35, F-41 à F-43, F-46 à F-54, F-56 à F-73, F-75 à F-77 |
| API conforme, écran absent ou partiel | 11 | F-27, F-28, F-30, F-32, F-36 à F-39, F-44, F-45, F-55 |
| Non conforme | 1 | F-40 |
| Non testable ici | 3 | F-10, F-12 (AD réel, conformes au simulateur), F-74 (contrat du bureau d'ordre, Q18) |

Taux de conformité fonctionnelle : **62/77 = 81 %** de bout en bout (75 % au tour 1) ; **73/77 =
95 %** si l'on compte les lignes dont seul l'écran manque (94 %). Passent conformes : F-01 (type
Booléen à l'écran), F-05 (gardes de route et accueil), F-09 (Agent d'archive), F-20 (reprise « à
indexer » à l'écran). F-27 reste partiel malgré la vérification d'ANO-F-006 : la date du document
tapée au clavier est refusée ou inversée (ANO-F-022, nouvelle, Majeure) ; F-28 attend ANO-F-020.
Réserves nouvelles sur des lignes conformes : F-20 (bloc de saisie comprimé sur la fiche,
ANO-F-021, Majeure), F-04 (calendrier en anglais, ANO-F-024), F-15 et F-61 (corbeille intitulée
« Archive », ANO-F-023). Seule ligne non conforme : F-40 (trois critères de recherche imposés
absents de l'API, ANO-F-011, en correction au tour 2). Les écarts d'écran restants (F-30, F-32,
F-36 à F-39, F-44, F-45, F-55) correspondent à ANO-F-010 et ANO-F-012 à ANO-F-017, en correction
au tour 2 : revérification au tour 3.

### Volet fonctionnel de P-21 et R-03 (tour 3)

- **P-21** (socle : objet, date du document clé de tri prioritaire, confidentialité, échéance
  déduite du type) : conforme côté fonctionnel. Date du document saisie au dépôt et corrigée sur la
  fiche, au calendrier comme au clavier en jj/mm/aaaa (ANO-F-022 vérifiée) ; colonne et tri
  « Date du document » dans la liste ; tri par défaut décroissant sur la date du document dans la
  recherche par index (ordre 01/10, 01/09, 10/05, 10/01/2026) et dans `POST /documents/recherche`
  (F-44) ; filtre par plage de date du document à l'écran et dans les trois API de recherche
  (ANO-F-011 vérifiée) ; échéance de conservation déduite du type (F-27, F-38). Réserve mineure :
  échéance affichée aaaa-mm-jj sur la fiche (ANO-F-025).
- **R-03** (espace de partage simple : déposer, télécharger, modifier localement, verser une
  nouvelle version ; ni co-édition ni édition en ligne) : conforme côté fonctionnel. Sous nidrissi
  (membre par groupe d'un dossier de l'espace d'échange) : « Nouveau dossier », « Déposer ici »
  avec choix du dossier (document rangé dans le dossier créé), « Télécharger » (fichier reçu),
  « Déposer la version » (v2 « Version modifiée localement », auteur nidrissi) ; aucune fonction
  d'édition en ligne ; API : rangement et dépôt dans le sous-dossier, refus hors de l'espace
  d'échange (F-62). Réserves : ANO-F-027 (ranger un document existant n'est pas proposé à
  l'écran au membre) ; ANO-F-026 (un standard peut faire d'un espace métier un espace d'échange) ;
  choix de dev1 (tout membre habilité range tout document visible de l'espace) à confirmer par pm.

### Tour 1 (base 71bdc1d)

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

Ces chiffres portaient sur la base `71bdc1d`, sans les corrections des ANO-F-001 à 009 faites en
parallèle ; ils sont recalculés ci-dessus (tour 2).
