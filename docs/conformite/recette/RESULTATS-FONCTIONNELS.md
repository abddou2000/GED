# Résultats de la recette fonctionnelle — Dossier d'analyse des besoins fonctionnels V3

Exécutée par qa2 le 28/09/2026 sur `ct/qa2` (partie de `conformite-technique` à **bc371ad**).
Aucun code applicatif modifié. **État : rapport intermédiaire, F-01 à F-20 traitées** ; les lignes
F-21 à F-77 sont en cours.

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
| F-21 à F-77 | — | — | En cours | — | ANO-F-006 déjà relevée (F-27) |

## 4. Taux intermédiaire (F-01 à F-20)

| Verdict | Lignes |
|---|---|
| Conforme | 14 (F-02, F-03, F-04, F-06, F-07, F-08, F-11, F-13 à F-19) |
| API conforme, écran absent ou partiel | 3 (F-01, F-05, F-20) |
| Non conforme | 1 (F-09) |
| Non testable ici (AD) — conforme au simulateur | 2 (F-10, F-12) |

Taux de conformité fonctionnelle sur les 20 premières lignes : **14/20 = 70 %** conformes de bout
en bout ; 17/20 = 85 % si l'on compte les lignes dont seul l'écran manque ; 2 lignes ne seront
définitivement prononcées qu'en UAT sur l'AD de MMED.
