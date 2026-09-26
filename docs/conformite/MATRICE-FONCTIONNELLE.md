GED MARCHICA MED
Matrice de conformité — Dossier d'analyse des besoins fonctionnels V3
Objet : comparer, exigence par exigence, le dossier fonctionnel V3 (réf. IPTECH — AO 07/AO/MM/26, septembre 2026) avec la GED telle qu'elle est développée aujourd'hui (backend Spring Boot et frontend Angular).
Méthode : chaque constat a été vérifié dans le code source de l'application, et non sur les maquettes. Trois statuts sont utilisés :

| Identique | La GED répond à l'exigence telle qu'elle est écrite. |
|---|---|
| Proche | La GED couvre une partie de l'exigence, ou la couvre autrement : un complément est nécessaire. |
| Non | La GED ne couvre pas l'exigence, ou fait l'inverse de ce qui est demandé. |


# 1. Synthèse chef de projet

| Identique | Proche | Non |
|---|---|---|
| 20 exigences — 26 % | 20 exigences — 26 % | 37 exigences — 48 % |

Total : 77 exigences analysées.

## Ce qui est solide
- Le cœur documentaire : types de document, index typés, plans d'indexation et charte de nommage suivent exactement le modèle du dossier.
- Le moteur OCR retenu par le dossier, Tesseract, est déjà intégré et opérationnel.
- La corbeille réversible sur tous les référentiels, le versionnement, la version courante et le verrouillage.
- Le principe « règle de workflow » et « circuit figé par document », avec décisions tracées et motif au refus.
- Une API REST complète et documentée, base de l'intégration avec le bureau d'ordre.

## Les trois écarts bloquants
- Habilitations. La GED est mono-utilisateur. Le dossier exige LDAP, quatre rôles, neuf permissions, des périmètres à trois niveaux et un filtrage à la source. C'est le socle dont dépendent recherche, arborescence, confidentialité et menus.
- Journal d'audit. Aucune journalisation métier des consultations, modifications et suppressions. C'est une exigence contractuelle (Art. 49.11) et une condition de valeur probante.
- Recherche plein texte. L'OCR fonctionne, mais le texte n'est pas conservé, donc pas interrogeable. C'est pourtant la seule finalité de l'OCR selon le dossier.

## Deux écarts de conception à arbitrer avec le client
- Workflow séquentiel. Le dossier retient des validateurs indépendants sans ordre. La GED impose un ordre et un rejet rouvre l'étape précédente. Il faut refondre ou obtenir l'accord du client sur le séquentiel.
- Pré-remplissage OCR. Le dossier précise que l'OCR n'alimente aucune métadonnée. La GED pré-remplit les index depuis l'OCR, avec confirmation humaine. C'est un plus fonctionnel, mais il faut le présenter comme une option désactivable.

## Fonctions présentes dans la GED mais non demandées
- Étiquettes colorées sur les documents.
- Tableau de bord et statistiques par type et par période.
- Pré-remplissage des index par lecture positionnelle de l'OCR.
- Index de groupage des résultats de recherche.

## Plan de rattrapage proposé, par ordre de priorité
- Lot 1 — Habilitations : LDAP, rôles, permissions, périmètres, filtrage à la source, menus par profil.
- Lot 2 — Journal d'audit inaltérable et format de logs de l'Article 50.
- Lot 3 — Recherche plein texte sur le texte OCR, arabe, suppression de la limite de pages.
- Lot 4 — Métadonnées manquantes : objet, date du document, confidentialité, durée de conservation, type booléen.
- Lot 5 — Cycle de vie : archivage par statut, empreinte et PDF/A, purge, alertes d'échéance, export ZIP, prévisualisation.
- Lot 6 — Workflow : mode sans ordre, rattachement par type, désignation par rôle, notifications, diffusion.
- Lot 7 — Intégration et sécurité : clés API, rattachement multi-espaces, chiffrement au repos.

# 2. Matrice détaillée

## Principes, périmètre et ergonomie (§2 et §5)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 2.2 | Solution modulaire et paramétrable sans code : types, métadonnées, circuits, durées de conservation, habilitations | Proche | Types, index, plans d'indexation et règles de workflow sont administrables depuis l'interface. Durées de conservation et habilitations n'existent pas. | Ajouter le paramétrage des durées et des droits. |
| 2.4 | Typologie documentaire administrable (administratif, RH, financier, juridique, courriers, technique) | Identique | Référentiel des types de document entièrement administrable, rattaché à un espace et à un plan d'indexation. | Saisir la typologie MMED en atelier. |
| 5 | Interface unique pour tous les modules | Identique | Une seule application, un menu commun à tous les écrans. | — |
| 5 | Interface exclusivement en français | Identique | Interface, messages et code en français. | — |
| 5 | Menus et fonctions adaptés au profil connecté | Non | Tous les menus sont visibles : l'application ne gère qu'un profil administrateur. | Dépend du module Habilitations. |

Sous-total : 3 identique(s), 1 proche(s), 1 non couverte(s).

## Acteurs, profils et habilitations (§3, §4.8, §4.12)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 3.1 | Modèle à trois dimensions : profil, rôle organisationnel, périmètre documentaire | Non | Application mono-utilisateur. Les groupes d'accès existent et sont liés aux espaces, mais aucune autorisation n'est appliquée. | Chantier prioritaire : moteur d'autorisation. |
| 3.1.1 | Périmètre positionnable sur espace, dossier ou fichier, avec héritage et règle du plus spécifique | Non | Les groupes ne se rattachent qu'aux espaces, sans héritage ni résolution de conflit. | À développer avec le moteur d'autorisation. |
| 3.2 | Quatre rôles composables (Utilisateur standard, Agent d'archive, Administrateur, Direction Générale) | Non | Aucun rôle : un seul compte administrateur. | Créer le référentiel des rôles. |
| 3.2.1 | Neuf permissions élémentaires (consulter, déposer, modifier, valider, diffuser, déplacer, archiver, supprimer, purger) | Non | Huit colonnes de droits existent en base (lecture, modifier, déposer, supprimer, déplacer, version, verrou) mais sont inertes. Valider, diffuser, archiver et purger manquent. | Réactiver et compléter ces droits. |
| 3.3 | Authentification Active Directory via LDAP | Non | Authentification locale : e-mail, mot de passe chiffré BCrypt, jeton JWT. | Brancher Spring Security LDAP. |
| 3.3 | Aucun écran de création de compte, identité créée à la première connexion, rôles attribués manuellement | Non | Un seul compte amorcé au premier démarrage. | À traiter avec LDAP. |
| 3.3.1 | Désactivation d'un compte AD : rôles inactifs, validations en cours signalées | Non | Non géré. | À traiter avec LDAP. |
| 3.4 | Clés API : générer, révoquer, régénérer, périmètre par clé, délégation d'identité | Non | Aucune clé API. L'API n'accepte que le jeton de l'utilisateur connecté. | Nouveau module d'administration. |
| 3.5 | Modification des droits sans intervention sur le code | Non | Pas de droits à modifier aujourd'hui. | Découle du moteur d'autorisation. |
| 3.6 | Aucune suppression définitive : corbeille réversible dans tous les modules | Identique | Suppression logique et restauration, unitaire ou en masse, sur documents, espaces, types, index, plans, workflows, étiquettes et groupes. | — |

Sous-total : 1 identique(s), 0 proche(s), 9 non couverte(s).

## Réception et intégration des documents (§4.1)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.1.3 | Enregistrement unique et horodatage automatique | Identique | Identifiant unique et date de création posés automatiquement. | — |
| 4.1.3 | Identification de la source et du déposant | Proche | Le déposant est enregistré. La source ou le canal (application, bureau d'ordre) ne l'est pas. | Ajouter le champ source. |
| 4.1.4 | Rattachement obligatoire à une catégorie existante | Identique | Le type de document est obligatoire au dépôt ; un type en corbeille est refusé. | — |
| 4.1.4 | Intégration du bureau d'ordre digital via API REST sécurisée par clé API | Proche | L'API REST de dépôt existe, mais sans clé API applicative. | Dépend des clés API. |
| 4.1.7 | Trois issues du dépôt : indexé, sans plan, à indexer | Proche | Le cas « sans plan » est géré. Il n'y a pas de statut « à indexer » pour reprendre une indexation échouée. | Ajouter un statut d'indexation. |

Sous-total : 2 identique(s), 3 proche(s), 0 non couverte(s).

## OCRisation et indexation (§4.2)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.2.1 | Moteur OCR open source Tesseract | Identique | Tesseract 5, moteur LSTM, modèles de langue livrés avec l'application. | — |
| 4.2.1 | OCR disponible pour l'interface et pour les applications consommatrices | Identique | Point d'API dédié au texte extrait et au diagnostic du moteur. | — |
| 4.2.1 | L'OCR sert uniquement à la recherche plein texte | Non | Le texte extrait n'est pas conservé, donc aucune recherche plein texte. | Stocker le texte et l'indexer. |
| 4.2.4 | L'OCR n'alimente aucun champ de métadonnée | Non | Écart inverse : la GED pré-remplit les index depuis l'OCR, avec confirmation humaine. | Arbitrage client : conserver en option ou désactiver. |
| 4.2.4 | Documents multi-pages traités comme une seule unité | Proche | Multi-pages géré, mais limité par défaut aux 5 premières pages (paramétrable). | Relever la limite pour la recherche plein texte. |
| 4.2.4 | Langues : français et arabe | Proche | Français et anglais installés. L'arabe demande d'ajouter son modèle de langue. | Ajouter le modèle arabe et tester. |
| 4.2.3 | Socle de métadonnées : identifiant, nom, objet, type, date du document, date de dépôt, déposant, confidentialité, durée de conservation | Proche | Présents : identifiant, nom, type, date de dépôt, déposant. Absents : objet, date du document, confidentialité, durée de conservation. | Ajouter les 4 champs manquants. |
| 4.2.3 | Métadonnées additionnelles paramétrables : texte, date, liste, nombre, booléen, obligatoire, défaut, recherche | Proche | Tout est géré sauf le type booléen. | Ajouter le type booléen. |
| 4.2.5 | Référentiel d'index, plan d'indexation et charte de nommage (jetons d'index et jetons système) | Identique | Modèle identique : index typés, plans, charte avec séparateur, majuscules, jetons date et heure. | — |
| 4.2.4 | Gouvernance des types : protection d'un type utilisé, re-typologie en lot | Proche | Création, modification et corbeille des types. Pas de re-typologie en lot. | Ajouter le changement de type en masse. |

Sous-total : 3 identique(s), 5 proche(s), 2 non couverte(s).

## Classement et arborescence (§4.3)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.3.2 | Espaces, puis arborescence de dossiers de profondeur libre | Identique | Espaces avec parent et enfants, arbre de navigation. | — |
| 4.3.2 | Deux natures d'espaces : métier et échange | Non | Une seule nature d'espace. | Ajouter la nature d'espace. |
| 4.3.4 | Création d'espace réservée à l'Administrateur | Proche | Vrai de fait, puisque le seul compte est administrateur. Rien ne l'impose une fois plusieurs rôles créés. | Dépend des rôles. |
| 4.3.4 | Espaces non autorisés invisibles dans l'arbre | Non | Tout l'arbre est visible. | Dépend du moteur d'autorisation. |
| 4.3.4 | Modification non autorisée bloquée et journalisée | Non | Ni contrôle ni journal. | Dépend des habilitations et de l'audit. |
| 4.3.5 | Déplacement d'un fichier ou d'un dossier, journalisé | Proche | Un espace peut changer de parent. Un document ne peut pas changer d'espace. Aucun journal. | Ajouter le déplacement de document. |
| 4.3.6 | Rattachement d'un document à plusieurs espaces sans duplication | Non | Un document appartient à un seul espace. | Nouvelle fonction. |
| 4.3.4 | Durée de conservation et statut associés à chaque type | Non | Non géré. | Voir cycle de vie. |

Sous-total : 1 identique(s), 2 proche(s), 5 non couverte(s).

## Recherche et consultation (§4.4)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.4.3 | Recherche multicritère sur index, avec plages de dates et de nombres | Identique | Critères générés depuis les index cochés « recherche », plages de dates et de nombres, regroupement par index. | — |
| 4.4.3 | Critères imposés : type, date du document, nom, objet, confidentialité, espace, déposant, date de dépôt | Proche | Type, espace et nom disponibles. Objet, confidentialité, déposant et dates de dépôt absents des critères. | Compléter les filtres. |
| 4.4.3 | Recherche plein texte avec extraits mis en évidence | Non | Non disponible. | Dépend du stockage du texte OCR. |
| 4.4.3 | Critères combinés en ET logique | Identique | Tous les critères doivent être satisfaits. | — |
| 4.4.3 | Filtrage par habilitation appliqué à la source, compteurs compris | Non | Aucun filtrage : tout est visible. | Dépend du moteur d'autorisation. |
| 4.4.3 | Résultats paginés et triables, documents non OCRisés signalés | Proche | Listes paginées et triables. La recherche par index n'est pas paginée et ne signale pas l'absence d'OCR. | Paginer la recherche. |
| 4.4.5 | Niveaux de confidentialité Public, Privé, Confidentiel | Non | Pas de niveau de confidentialité. | Nouveau champ et règles d'accès. |

Sous-total : 2 identique(s), 2 proche(s), 3 non couverte(s).

## Circuits de validation et diffusion (§4.5)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.5.3 | Circuit paramétrable par type de document, rattachable à un espace ou un dossier | Proche | Circuit rattaché à l'espace uniquement, pas au type. | Permettre le rattachement au type. |
| 4.5.3 | Validateurs indépendants, sans ordre imposé | Non | Écart de conception : la GED impose un ordre séquentiel, et un rejet rouvre l'étape précédente. | Refondre en mode parallèle, ou faire valider le séquentiel par le client. |
| 4.5.3 | Valider ou refuser, motif obligatoire au refus | Identique | Approbation et rejet, motif exigé au rejet. | — |
| 4.5.3 | Nouvelle version : revalider ou annuler une validation antérieure | Non | Non géré. | Nouvelle règle. |
| 4.5.3 | Décision tracée nominativement, horodatée et motivée | Identique | Chaque signature garde le validateur, la date et le motif. | — |
| 4.5.3 | Notification des validateurs et du déposant | Non | Aucune notification. Seuls l'écran « Mes workflow » et une relance manuelle existent. | Ajouter les notifications. |
| 4.5.3 | Diffusion du document validé à un périmètre | Non | Non géré. | Nouvelle fonction. |
| 4.5.4 | Règle modifiable, circuit figé par document | Identique | Les signatures sont copiées au dépôt : modifier la règle ne touche pas les circuits en cours. | — |
| 4.5.4 | Validateur désigné nommément ou par rôle | Proche | Désignation nommée uniquement. | Dépend des rôles. |

Sous-total : 3 identique(s), 2 proche(s), 4 non couverte(s).

## Cycle de vie documentaire (§4.6)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.6.3 | Versionnement : versions antérieures conservées, auteur et date par version | Proche | Versions conservées avec date et observation. L'auteur de chaque version n'est pas enregistré. | Ajouter l'auteur par version. |
| 4.6.4 | Version courante désignable, pas forcément la dernière | Identique | Version principale désignable parmi l'historique. | — |
| 4.6.4 | Verrouillage d'un document | Identique | Verrou qui bloque fiche, versions et réindexation. | — |
| 4.6.3 | Durée de conservation par type et alerte d'échéance à l'Agent d'archive | Proche | Une date d'expiration existe par document, sans durée par type ni alerte. | Ajouter durée par type et alerte. |
| 4.6.5 | Import et export d'un fichier unitaire | Identique | Dépôt et téléchargement unitaires. | — |
| 4.6.5 | Export d'un dossier complet en ZIP avec manifeste des métadonnées | Non | Non disponible. | Nouvelle fonction. |
| 4.6.5 | Suppression à deux niveaux : corbeille puis purge définitive | Proche | Corbeille disponible. Purge définitive absente. | Ajouter la purge réservée aux habilités. |
| 4.6.5 | Dossiers partagés pour un groupe d'utilisateurs | Non | Non géré. | Dépend des habilitations. |
| 4.6.5 | Prévisualisation en ligne des formats courants | Non | Téléchargement uniquement. | Ajouter une visionneuse PDF et images. |
| 4.6.5 | Formats et taille maximale paramétrables par type | Identique | Contrôlés à l'aperçu, au dépôt et à l'ajout de version. | — |
| 4.6.6 | Notifications limitées à 3 cas : circuits, attribution d'accès, fin de conservation | Non | Aucune notification. | Voir circuits. |

Sous-total : 4 identique(s), 3 proche(s), 4 non couverte(s).

## Archivage, traçabilité, intégration et sécurité (§4.7 à §4.15)

| Réf. | Exigence du dossier fonctionnel | Statut | Constat dans la GED actuelle | Action proposée |
|---|---|---|---|---|
| 4.7.4 | Archivage par changement de statut, réversible, lecture seule, inclus en recherche | Proche | Un statut « archivé » existe pour les espaces, pas pour les documents. Pas de lecture seule. | Ajouter le statut archivé au document. |
| 4.7.4 | Intégrité : empreinte numérique et format pérenne PDF/A | Non | Non géré. | Empreinte à l'archivage, conversion PDF/A. |
| 4.7.3 | Traçabilité des consultations d'archives | Non | Non géré. | Dépend du journal d'audit. |
| 4.9.3 | Journal d'audit : consultation, modification, validation, suppression, et liste détaillée du §4.9.4 | Non | Seules les dates de création et de modification, et l'historique des signatures, sont conservés. | Chantier prioritaire : journal d'audit. |
| 4.9.3 | Journaux inaltérables, y compris par l'Administrateur | Non | Non géré. | À concevoir avec le journal. |
| 4.9.3 | Pattern de journalisation technique de l'Article 50 | Non | Journalisation Spring par défaut. | Configurer le format imposé. |
| 4.10.3 | Intégration via API REST | Identique | Une centaine d'opérations REST documentées en OpenAPI. | — |
| 4.10.3 | Clé API dédiée par application, périmètre configurable | Non | Non géré. | Voir clés API. |
| 4.10.3 | Contrat d'interface détaillé avec le bureau d'ordre | Proche | Documentation OpenAPI générée. Contrat bureau d'ordre à spécifier. | Rédiger en atelier. |
| 4.13 | Échelle de confidentialité paramétrable, croisée avec les habilitations | Non | Non géré. | Voir confidentialité. |
| 4.14 | Conformité loi 09-08 : accès restreint et traçabilité des données personnelles | Non | Aucun mécanisme spécifique. | Découle des habilitations et de l'audit. |
| 4.15 | Chiffrement de tous les documents | Non | Fichiers stockés en clair sur le disque. | Chiffrement au repos à mettre en place. |

Sous-total : 1 identique(s), 2 proche(s), 9 non couverte(s).