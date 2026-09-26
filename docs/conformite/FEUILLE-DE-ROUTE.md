GED MARCHICA MED
Feuille de route — conformité à 100 % au dossier technique et d'intégration V3
Point de départ : la matrice de conformité technique compte 86 exigences « Proche » ou « Non ». Chacune est rattachée ci-dessous à une seule étape ; la couverture est vérifiée automatiquement à la génération du document.

# 1. Principes de séquencement
- Le socle d'abord. PostgreSQL, Liquibase et UUID passent avant toute nouvelle table : chaque table écrite sur l'ancien socle serait à migrer une seconde fois.
- Les droits avant les fonctions. Recherche, arbre, téléchargement, export et API appellent tous le même point d'autorisation : il doit exister avant eux.
- L'audit avant les nouveautés. Chaque fonction livrée après l'étape E4 produit ses événements d'audit dès sa première version.
- Une étape n'est close que sur son critère de sortie, vérifié en UAT et démontré en fin de sprint, conformément au rythme de deux semaines fixé par le CPS.

# 2. Vue d'ensemble

| Étape | Contenu | Durée | Semaines | Dépend de | Exigences |
|---|---|---|---|---|---|
| E0 | Cadrage et outillage | 1 sem. | S1 à S1 | — | 5 |
| E1 | Socle de données | 2 sem. | S2 à S3 | E0 | 12 |
| E2 | Identité et sessions | 2 sem. | S4 à S5 | E1 | 8 |
| E3 | Autorisation et confidentialité | 4 sem. | S6 à S9 | E2 | 4 |
| E4 | Journalisation et audit | 2 sem. | S10 à S11 | E3 | 5 |
| E5 | Stockage sécurisé des fichiers | 3 sem. | S12 à S14 | E4 | 7 |
| E6 | OCR asynchrone et recherche plein texte | 3 sem. | S15 à S17 | E5 | 12 |
| E7 | Modèle documentaire et cycle de vie | 4 sem. | S18 à S21 | E6 | 11 |
| E8 | Workflow, conservation et notifications | 3 sem. | S22 à S24 | E7 | 5 |
| E9 | API d'intégration | 3 sem. | S25 à S27 | E8 | 10 |
| E10 | Exploitation et infrastructure | 2 sem. | S28 à S29 | E0 (peut démarrer en parallèle dès E2) | 7 |
| E11 | Recette de conformité | 2 sem. | S30 à S31 | E1 à E10 | 0 |

Durée totale en enchaînement strict : 31 semaines, soit environ 7 mois. Hypothèse : deux développeurs back-end et un développeur front-end. L'étape E10 peut être menée en parallèle par un profil infrastructure dès E2, ce qui ramène le total à environ 29 semaines. Ces durées sont indicatives et à recaler après E0.

# 3. Détail des étapes

## E0 — Cadrage et outillage
Durée indicative : 1 semaine. Dépend de : —.
Objectif : Poser les conditions pour livrer vite sans régression : forge, intégration continue, environnements, contrôle des dépendances.
Tâches
- Protéger la branche principale : fusion uniquement par demande de fusion relue.
- Ouvrir un accès en lecture seule aux relecteurs désignés par MMED.
- Mettre en place l'intégration continue : compilation, tests, construction du JAR et du paquet Angular à chaque fusion.
- Ajouter OWASP Dependency-Check et la génération d'un SBOM CycloneDX au build Maven et npm.
- Créer le profil UAT à côté de dev et prod.
- Lancer auprès de MMED la collecte des informations d'infrastructure (voir la dernière section).
Exigences de la matrice clôturées (5)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 6.2.3 | A06 : OWASP Dependency-Check à chaque construction | Non |
| 8.3 | Registre des dépendances avec version et licence (SBOM) | Non |
| 9.2 | Tests de non-régression | Proche |
| 9.3 | Environnement UAT et déploiement par module | Non |
| 9.4 | Dépôt Git, branche protégée, accès en lecture pour MMED | Proche |

Critère de sortie : Chaque fusion déclenche build, tests, rapport de vulnérabilités et SBOM ; un environnement UAT existe.

## E1 — Socle de données
Durée indicative : 2 semaines. Dépend de : E0.
Objectif : Aligner la persistance sur la stack imposée avant d'écrire la moindre nouvelle table.
Tâches
- Passer de MySQL à PostgreSQL 16 ou plus ; vérifier la disponibilité de la configuration de recherche arabe.
- Remplacer Flyway par Liquibase 4 : changelog maître, un changeset par évolution nommé AAAAMMJJHHmm_objet.xml.
- Appliquer les conventions : snake_case, clé id, idx_, uk_, fk_, ck_ ; rollback explicite sur chaque changeset.
- Passer toutes les clés primaires et identifiants d'API en UUID.
- Créer les trois rôles PostgreSQL : ged_owner, ged_app, ged_readonly.
- Déplacer l'amorçage initial (rôles système, niveaux, valeurs par défaut) en changesets data-initial.
- Ajouter supprime_par et supprime_le à la suppression douce ; préparer la colonne metadonnees en JSONB avec index GIN.
- Écrire le script de reprise des données MySQL existantes vers PostgreSQL.
- Passer les tests d'intégration sur PostgreSQL réel (Testcontainers).
Exigences de la matrice clôturées (12)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 2.2 | SGBD PostgreSQL 16 ou plus, configuration de recherche arabe vérifiée | Non |
| 2.2 | Outil de migration Liquibase 4 | Non |
| 4.2.1 | Aucune modification de schéma hors migration versionnée | Proche |
| 4.2.1 | Amorçage initial par migration, référentiels métier uniquement via l'interface | Proche |
| 4.2.2 | Conventions de nommage des changesets et des objets (snake_case, idx_, uk_, fk_) | Proche |
| 4.2.2 | Retour arrière explicite par changeset, schéma expand et contract | Non |
| 4.2.3 | Trois rôles PostgreSQL : propriétaire, application, lecture seule | Non |
| 12.1 | Clés primaires UUID | Non |
| 12.1 | Modèle logique en sept groupes de tables | Proche |
| 5.3.2 | JSON UTF-8, dates ISO 8601 en UTC, identifiants opaques UUID | Proche |
| 12.5 | Suppression douce avec auteur et date | Proche |
| 12.7 | Métadonnées en JSONB avec index GIN | Non |

Critère de sortie : Base vierge créée uniquement par Liquibase, rollback testé en UAT, tous les tests verts sur PostgreSQL.

## E2 — Identité et sessions
Durée indicative : 2 semaines. Dépend de : E1.
Objectif : Supprimer tout mot de passe local et authentifier par l'annuaire de MMED.
Tâches
- Authentification LDAPS search-then-bind avec Spring Security LDAP et compte de service en lecture seule.
- Provisionnement automatique à la première connexion, sans rôle, clé objectGUID ; suppression de la table des mots de passe.
- Table cache_annuaire (15 minutes) ; relecture de userAccountControl, désactivation prise en compte en 5 minutes.
- Jeton d'accès RS256 de 15 minutes, clé privée en coffre, conservé en mémoire côté Angular.
- Jeton de renouvellement opaque en cookie httpOnly, Secure, SameSite=Strict ; table session ; rotation et révocation de famille.
- Limitation de débit sur la connexion : 5 essais par minute par IP et par identifiant.
- Côté Angular : intercepteur de renouvellement silencieux, page d'accueil vide pour un compte sans rôle.
Exigences de la matrice clôturées (8)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 3.2 | Aucun référentiel local de mots de passe | Non |
| 3.3 | Authentification LDAPS search-then-bind avec compte de service, Spring Security LDAP | Non |
| 3.3 | Provisionnement automatique sans rôle, clé objectGUID | Non |
| 3.3 | Jeton d'accès court, sans permissions embarquées | Proche |
| 3.4.1 | JWT signé RS256 avec clé privée en coffre, conservé en mémoire côté Angular | Non |
| 3.4.1 | Jeton de renouvellement en cookie httpOnly, 8 h max, table de sessions, révocation | Non |
| 3.4.1 | Anti-force brute : 5 essais par minute par IP et identifiant, échecs journalisés | Proche |
| 3.4.2 | Cache annuaire de 15 minutes, désactivation AD prise en compte en 5 minutes | Non |

Critère de sortie : Connexion avec un compte AD de test en UAT, aucun mot de passe en base, révocation effective immédiatement.

## E3 — Autorisation et confidentialité
Durée indicative : 4 semaines. Dépend de : E2.
Objectif : Construire le point d'application unique des droits, dont dépendent recherche, arbre, téléchargement et API.
Tâches
- Tables role, permission, role_permission, groupe_ged, groupe_membre, habilitation ; quatre rôles système livrés.
- Nœuds (espaces et dossiers) avec chemin matérialisé ; habilitation sur nœud ou document, rupture d'héritage.
- Service AccessPredicate : nœuds accessibles par permission et prédicat SQL de confidentialité, cache invalidé par compteur de version.
- Direction Générale traitée par l'indicateur acces_global, sans ligne d'habilitation.
- Confidentialité PUBLIC, PRIVE, CONFIDENTIEL ; table des personnes désignées ; permissions VOIR_PRIVE et VOIR_CONFIDENTIEL.
- Réponse 404 pour tout objet hors périmètre ; arbre, compteurs et totaux limités au périmètre.
- Écrans d'administration : rôles, groupes, habilitations, consultation des droits effectifs.
- Côté Angular : menus et actions masqués selon les permissions reçues.
- Tests automatisés de chaque chemin d'accès.
Exigences de la matrice clôturées (4)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 6.2.3 | A01 : refus par défaut, 404 pour un objet hors périmètre | Proche |
| 6.4 | Contrôle d'accès côté back par un point d'application unique | Non |
| 12.2 | Permissions, rôles, habilitations sur nœud ou document, héritage et rupture | Non |
| 12.3 | Confidentialité PUBLIC, PRIVE, CONFIDENTIEL et personnes désignées | Non |

Critère de sortie : Un utilisateur sans droit ne voit ni ne compte aucun document hors de son périmètre, sur tous les chemins testés.

## E4 — Journalisation et audit
Durée indicative : 2 semaines. Dépend de : E3.
Objectif : Tracer chaque action avant de livrer les fonctions métier suivantes, pour qu'elles soient auditées dès leur naissance.
Tâches
- Filtre MDC (username, ip, traceId, spanId) et pattern de log de l'Article 50.
- Logback : niveau INFO en production, rotation quotidienne et à 100 Mo, compression, rétention 90 jours.
- Table journal_audit partitionnée par mois : acteur utilisateur, acteur application, IP, action, objet, avant, après, résultat, trace_id.
- Droits INSERT et SELECT seulement pour ged_app ; déclencheur de refus UPDATE, DELETE, TRUNCATE.
- Scellement horaire SHA-256 chaîné, exporté hors base ; commande et tâche mensuelle de vérification.
- Écran de consultation filtrable, export CSV et JSON avec empreintes ; consultation elle-même auditée.
- Journaliser les connexions réussies et refusées, ainsi que tous les événements des modules existants.
Exigences de la matrice clôturées (5)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 7.1 | Pattern de log imposé avec username, ip, traceId, spanId (MDC) | Non |
| 7.3.1 | Niveaux de log, rotation quotidienne et par taille, rétention 90 jours | Non |
| 7.4.1 | Table journal_audit : acteur, application, IP, action, objet, avant et après, trace_id | Non |
| 7.4.2 | INSERT seul, déclencheurs de refus, scellement SHA-256 chaîné et exporté | Non |
| 7.4.3 | Écran de consultation, export CSV et JSON, rétention 10 ans | Non |

Critère de sortie : Chaque action produit un événement ; une modification manuelle du journal fait échouer la vérification du scellement.

## E5 — Stockage sécurisé des fichiers
Durée indicative : 3 semaines. Dépend de : E4.
Objectif : Chiffrer tous les documents et contrôler chaque fichier à l'entrée.
Tâches
- Interface FileStore ; arborescence /racine/aa/bb/uuid.enc ; écriture atomique (temporaire, fsync, renommage).
- Chiffrement AES-256-GCM, clé de données par version, enveloppée par une clé maîtresse en keystore PKCS#12 via KeyProvider.
- Rotation de la clé maîtresse par réenveloppement, sans rechiffrer les fichiers.
- Empreinte SHA-256 du contenu en clair par version ; vérification mensuelle et à la demande.
- Détection du type réel avec Apache Tika ; antivirus ClamAV en échec fermé.
- Codes 413, 415 et 422 (FICHIER_INFECTE) ; plafond de plateforme à 200 Mo.
- Prévisualisation : PDF et images déchiffrés en flux, bureautique convertie par LibreOffice et mise en cache chiffrée.
- Reprise : chiffrer les fichiers existants et calculer leurs empreintes.
Exigences de la matrice clôturées (7)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 2.3.2 | Briques Apache Tika, ClamAV, LibreOffice, veraPDF, keystore ou KMS, Prometheus | Non |
| 6.1.1 | Identifiant opaque, arborescence aa/bb, écriture unique et atomique | Proche |
| 6.1.2 | Chiffrement AES-256-GCM, clé par version, clé maîtresse en keystore, rotation | Non |
| 6.1.4 | Empreinte SHA-256 par version, vérification périodique | Non |
| 6.1.5 | Type réel détecté par le contenu (Apache Tika) | Non |
| 6.1.5 | Antivirus ClamAV, refus si indisponible | Non |
| 6.1.6 | Prévisualisation déchiffrée à la volée, droits appliqués, audit | Non |

Critère de sortie : Aucun fichier en clair sur le disque ; un fichier altéré est détecté ; un fichier infecté est refusé.

## E6 — OCR asynchrone et recherche plein texte
Durée indicative : 3 semaines. Dépend de : E5.
Objectif : Rendre le contenu des documents interrogeable, ce qui est l'unique finalité de l'OCR selon le dossier.
Tâches
- Supprimer le pré-remplissage des index par l'OCR.
- Table ocr_job et worker SELECT ... FOR UPDATE SKIP LOCKED ; dépôt qui répond HTTP 202 avec l'état EN_ATTENTE_OCR.
- Déchiffrement en mémoire ou tmpfs ; suppression du plafond de pages ; traitement page par page.
- Modèles fra et ara, langue par défaut fra+ara, réglable par type de document.
- 60 secondes par page, 3 tentatives (1, 5 puis 30 minutes), statut OCR_ECHEC, document « non interrogeable ».
- Table document_texte, colonne tsv (french et arabic, unaccent), index GIN ; métrique ocr_delai_disponibilite.
- Recherche : websearch_to_tsquery, extraits ts_headline, tri ts_rank_cd, EXISTS pour les rattachements, filtrage par droits à la source, pagination.
- Réindexation incrémentale à chaque version et réindexation complète en tâche de fond.
- Écran de supervision des traitements OCR ; côté Angular, recherche plein texte avec extraits.
- Préparer le protocole comparatif sur 300 pages (CER, WER, débit), exécuté dès réception de l'échantillon.
Exigences de la matrice clôturées (12)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 4.3.2 | Protocole comparatif sur 300 pages (CER, WER, débit) | Non |
| 4.3.3 | Cloisonnement : l'OCR n'alimente aucun champ d'index | Non |
| 4.3.4 | Traitement asynchrone : table ocr_job, worker SKIP LOCKED, réponse HTTP 202 | Non |
| 4.3.4 | Langues fra et ara, langue fixable par type de document | Proche |
| 4.3.4 | Aucun plafond de pages, texte multi-pages agrégé | Proche |
| 4.3.4 | Aucune copie en clair sur disque persistant (tmpfs) | Non |
| 4.3.4 | Délai de 60 s par page, 3 tentatives, statut OCR_ECHEC, document « non interrogeable » | Proche |
| 4.3.4 | Texte stocké dans document_texte, délai de disponibilité mesuré | Non |
| 4.4 | Recherche PostgreSQL tsvector et index GIN, configurations french et arabic | Non |
| 4.4 | websearch_to_tsquery, extraits ts_headline, tri ts_rank_cd, dédoublonnage | Non |
| 4.4.1 | Réindexation incrémentale et complète | Non |
| 5.3.1 | Recherche multicritère et plein texte filtrée par droits, paginée | Proche |

Critère de sortie : Un scan arabe ou français de 20 pages est trouvable par son contenu en moins de 5 minutes, dans le périmètre de l'utilisateur.

## E7 — Modèle documentaire et cycle de vie
Durée indicative : 4 semaines. Dépend de : E6.
Objectif : Compléter le méta-modèle et les opérations sur les documents.
Tâches
- Index de nature booléenne ; métadonnées additionnelles en JSONB validées contre le plan ; index d'expression pour dates et nombres.
- Type de document : durée de conservation, point de départ de l'échéance, confidentialité par défaut, plan versionné.
- Suppression d'un type utilisé impossible (RESTRICT) ; re-typologisation d'un lot par job_retypage avec rapport.
- Versions : numéro, empreinte, auteur, index unique partiel sur la version courante.
- Verrou avec auteur, date et motif, refus 409 sur toute écriture.
- Dépôt en deux temps : statut INDEXE, SANS_PLAN ou A_INDEXER, reprise de l'indexation.
- Rattachement d'un document à plusieurs espaces, sans copie ; droits en union des emplacements.
- Déplacement de document et de dossier avec sous-arborescence, renommage avec unicité, audit origine et destination.
- Purge définitive depuis la corbeille, avec destruction de la clé de données.
- Archivage par statut : lecture seule, empreinte revérifiée, copie PDF/A-2 validée par veraPDF, job_archivage par tranches de 100.
- Export de dossier en ZIP en flux avec manifeste CSV ; traitement de fond au-delà de 500 documents ou 2 Go.
Exigences de la matrice clôturées (11)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 6.1.4 | Copie de conservation PDF/A-2 validée par veraPDF | Non |
| 12.4 | Rattachement d'un document à plusieurs espaces | Non |
| 12.5 | Déplacement transactionnel avec sous-arborescence et anti-cycle | Proche |
| 12.5 | Purge définitive avec destruction cryptographique | Non |
| 12.6 | Archivage : statut du document, lecture seule, empreinte, PDF/A, job par lot | Non |
| 12.7 | Méta-modèle d'index : nature dont booléen, obligatoire, défaut, liste, recherche | Proche |
| 12.7 | Type : durée de conservation, confidentialité par défaut, plan versionné, re-typologisation | Non |
| 12.8 | Versions : numéro, empreinte, auteur, une seule version courante | Proche |
| 12.8 | Verrou avec auteur, date et motif | Proche |
| 12.10 | Export de dossier en ZIP en flux avec manifeste CSV | Non |
| 12.11 | Dépôt en deux temps : fichier reçu, puis indexation INDEXE, SANS_PLAN ou A_INDEXER | Proche |

Critère de sortie : Un document archivé est intouchable, possède sa copie PDF/A validée, et un export ZIP ne contient que ce que l'utilisateur peut voir.

## E8 — Workflow, conservation et notifications
Durée indicative : 3 semaines. Dépend de : E7.
Objectif : Aligner le circuit de validation sur le modèle sans ordre et livrer les trois cas de notification.
Tâches
- Règle de workflow rattachable à un espace, un dossier ou un type ; validateur nommé ou par rôle.
- Circuit figé au dépôt (circuit, circuit_validateur) ; décisions VALIDE, REFUSE, ANNULEE sans ordre imposé.
- Statut recalculé sur la version courante ; un nouveau versement rend caduques les décisions antérieures.
- Annulation d'un circuit avec motif ; validateur désactivé signalé à l'Administrateur ; diffusion par habilitation de lecture.
- Tâche quotidienne d'échéance de conservation avec verrou de tâche ; filtre « échéance dépassée ».
- Table notification (boîte d'envoi), e-mail par le relais SMTP de MMED et pastille dans l'application, 3 reprises.
- Préférence utilisateur pour désactiver l'e-mail.
- Côté Angular : écran de validation sans étapes, centre de notifications.
Exigences de la matrice clôturées (5)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 12.8 | Règle de workflow rattachable à un espace, un dossier ou un type ; validateur nommé ou par rôle | Proche |
| 12.8 | Décisions VALIDE, REFUSE, ANNULEE sans ordre, statut recalculé sur la version courante | Non |
| 12.8 | Annulation de circuit et diffusion | Non |
| 12.9 | Échéance de conservation et tâche planifiée d'alerte | Non |
| 12.9 | Notifications : boîte d'envoi, e-mail SMTP et pastille in-app | Non |

Critère de sortie : Deux validateurs décident dans n'importe quel ordre ; chaque ouverture, décision et annulation notifie les bonnes personnes.

## E9 — API d'intégration
Durée indicative : 3 semaines. Dépend de : E8.
Objectif : Ouvrir la GED au bureau d'ordre et aux autres applications de MMED, avec les mêmes règles que pour les utilisateurs.
Tâches
- Clés API ged_env_id_secret : génération, affichage unique, empreinte SHA-256, expiration 12 mois, chevauchement de 7 jours.
- Portée par nœud et par opération (cle_api_portee), liste d'adresses autorisées, quotas 600 par minute et 100 000 par jour, 429 avec Retry-After.
- Délégation X-On-Behalf-Of réservée aux clés autorisées ; double identité dans l'audit ; erreur IDENTITE_DELEGUEE_INVALIDE.
- Idempotency-Key obligatoire sur les créations, mémorisée 24 heures.
- Erreurs au format problem+json (RFC 7807) avec code métier stable ; codes 422 et 429.
- Points d'entrée du contrat : /noeuds/{id}/dossiers, /documents avec métadonnées, /recherches, /documents/{id}/contenu, versions, rattachements, droits.
- Enregistrement de la source ou du canal de chaque dépôt.
- Spécification OpenAPI 3 complète, avec exemples de payload.
- Écran d'administration des clés et des applications.
Exigences de la matrice clôturées (10)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 5.1 | Point d'entrée de réception : enregistrer, horodater, identifier source et déposant | Proche |
| 5.2 | Applications tierces soumises au même modèle d'habilitation et à la même journalisation | Non |
| 5.3 | Opérations : dépôt avec métadonnées en une seule opération | Proche |
| 5.3 | Opérations : dépôt pour le compte d'un utilisateur, rattachement, consultation des droits | Non |
| 5.3.2 | Erreurs au format problem+json (RFC 7807) avec code métier stable | Proche |
| 5.3.2 | Codes 403, 404 hors périmètre, 409, 413, 415, 422, 429 | Proche |
| 5.3.2 | En-tête Idempotency-Key obligatoire sur les créations | Non |
| 5.3.2 | Taille de fichier paramétrable par type, quotas de requêtes par clé | Proche |
| 5.4 | Clés API : cycle de vie, secret haché SHA-256, X-API-Key, portée, quotas, expiration, IP | Non |
| 5.5 | Délégation d'identité par X-On-Behalf-Of, double identité dans l'audit | Non |

Critère de sortie : Une application de test dépose, recherche et télécharge par clé API, dans sa seule portée, et chaque appel est audité.

## E10 — Exploitation et infrastructure
Durée indicative : 2 semaines. Dépend de : E0 (peut démarrer en parallèle dès E2).
Objectif : Livrer une plateforme exploitable et sécurisée de bout en bout.
Tâches
- Configuration NGINX : TLS 1.2 minimum, certificat MMED, redirection HTTPS, server_tokens off, HSTS, CSP, X-Frame-Options, limitation de débit, 200 Mo.
- Service systemd du JAR sur un serveur Linux distinct de la base ; LDAPS et connexion PostgreSQL chiffrées.
- Script de déploiement identique partout : sauvegarde, liquibase validate puis update, déploiement, sondes, test de fumée, retour arrière.
- Micrometer et Prometheus ; sondes PostgreSQL, LDAP, fichiers, ClamAV, file OCR ; Actuator limité au réseau interne ; alertes.
- Sauvegarde : base avec archivage WAL, fichiers chiffrés après la base, clés séparées ; RPO et RTO documentés.
- Test de restauration à blanc, avec compte rendu.
Exigences de la matrice clôturées (7)

| Réf. | Exigence | Statut actuel |
|---|---|---|
| 2.2 | Hébergement du front sur un serveur NGINX | Proche |
| 6.2.1 | TLS 1.2 minimum, certificat MMED, LDAPS et base chiffrés | Non |
| 6.2.2 | NGINX durci : server_tokens off, HSTS, CSP, limitation de débit, HTTPS forcé | Non |
| 6.5 | Sauvegarde base, fichiers et clés, RPO et RTO, restauration testée | Proche |
| 6.7 | Métriques Micrometer et Prometheus, alertes | Non |
| 10.1 | Procédure scriptée : CI, sauvegarde, migration, test de fumée, retour arrière | Proche |
| 10.2 | JAR exécutable en service système derrière NGINX, serveur Linux | Proche |

Critère de sortie : Déploiement DEV, UAT et PROD par le même script, restauration testée dans le RTO, alertes reçues.

## E11 — Recette de conformité
Durée indicative : 2 semaines. Dépend de : E1 à E10.
Objectif : Prouver que la GED est conforme à 100 % au dossier technique.
Tâches
- Rejouer la matrice de conformité technique : les 115 exigences doivent être « Identique ».
- Traiter les finitions des lignes déjà conformes (chemins du contrat, plafond 200 Mo, Actuator interne, sondes supplémentaires).
- Revue de sécurité OWASP Top 10 et test d'intrusion.
- Campagne de non-régression complète en UAT ; démonstration à MMED.
- Mise à jour de la documentation d'installation et préparation du procès-verbal de mise en production.
Critère de sortie : Matrice à 100 % « Identique », validée par MMED.

# 4. Informations à obtenir de Marchica Med
Ces éléments conditionnent certaines étapes ; ils doivent être demandés dès E0 pour ne pas bloquer le planning.

| Information | Nécessaire pour |
|---|---|
| Contrôleurs de domaine, base de recherche, compte de service LDAP, chaîne de certificats | E2 |
| Politique PKI et certificat TLS du serveur | E10 |
| Relais SMTP et adresse d'expédition | E8 |
| KMS ou HSM existant, sinon validation du keystore PKCS#12 | E5 |
| Serveurs Linux, stockage des fichiers, support de sauvegarde, outil de supervision existant | E10 |
| Échantillon de documents pour le protocole OCR (300 pages) et la Phase 7 | E6 |
| Durées légales de conservation par catégorie, rétention des journaux | E4, E7 |
| Arbitrages : workflow sans ordre, suppression du pré-remplissage OCR | E6, E8 |


# 5. Suivi
- À la fin de chaque étape, rejouer les lignes concernées de la matrice de conformité et passer leur statut à « Identique ».
- Tenir à jour le SBOM et le rapport OWASP Dependency-Check à chaque livraison.
- Présenter à MMED, à chaque démonstration de sprint, le taux de conformité de la matrice.