# Registre des risques et questions pour Marchica Med

Tenu par **pm**. Revu à chaque fin de vague. Probabilité et impact : Faible, Moyen, Élevé.
Intègre la revue technique client (`DECISIONS-REVUE-TECHNIQUE.md`) : risques R26 et R27,
questions QR1 à QR9, réponses reportées sur Q02, Q10 et Q20.

## 1. Constat préalable sur le dossier V3

Le §1.2 du PDF annonce trois chapitres qui **n'existent pas** dans le document livré (36 pages,
la table des matières s'arrête à 12.11) :

- chapitre 13 : cartographie des livrables techniques par phase ;
- chapitre 14 : matrices de traçabilité ;
- **chapitre 15 : points restant à confirmer avec Marchica Med**, « chacun assorti de la valeur
  par défaut retenue ».

Faute de chapitre 15, la liste de la section 3 ci-dessous a été reconstituée par pm à partir de
toutes les mentions « à valider », « à confirmer » et « à recueillir » du PDF, et de la section
4 de la feuille de route. En attendant les réponses, **l'équipe applique la valeur par défaut
écrite dans le PDF**, paramétrable, sans bloquer le développement.

Autre incohérence du PDF à signaler : le §1.1 cite le « CPS-07/AO/MM/25 » du marché
« n°07/AO/MM/26 ».

## 2. Risques techniques

| N° | Risque | Proba. | Impact | Parade | Porteur |
|---|---|---|---|---|---|
| R01 | **Migration des clés en UUID** : toutes les entités, DTO, dépôts, URL, tests et le front (identifiants numériques partout dans Angular) changent en même temps. Régressions silencieuses (tri, comparaisons, routes), et conflit avec tout le travail parallèle de la vague 1. | Élevé | Élevé | dev3 et dev2 ne touchent à aucune entité en vague 1 ; fusion de dev1 juste après l'outillage ; qa rejoue la ligne de base complète après la fusion ; types Angular `string` générés depuis un seul fichier de modèles. | dev1 |
| R02 | **Reprise des données MySQL vers PostgreSQL** : pas d'instance MySQL de référence connue sur le poste ; conversion des identifiants en UUID avec préservation des liens et des fichiers. | Moyen | Élevé | Script de reprise testé sur un jeu de données extrait du schéma V1/V2 ; contrôle de comptage et d'intégrité ; question Q17 à MMED. | dev1 |
| R03 | **Rôles PostgreSQL partagés entre membres** : `ged_owner`, `ged_app`, `ged_readonly` sont des objets du cluster, communs à toutes les bases `ged_*` du poste. Un script de test qui les supprime ou les modifie casse les bases des autres. | Élevé | Moyen | Script de rôles idempotent (création si absent, jamais de suppression) ; ou noms paramétrés par propriété Liquibase (`ged_app_dev1` en local, noms du PDF en UAT et PROD). Interdiction de `DROP ROLE` dans les tests. | dev1 |
| R04 | **Chiffrement AES-256-GCM et performance, mémoire** : l'implémentation JCE de GCM met tout le chiffré en mémoire au déchiffrement pour vérifier l'étiquette ; un fichier de 200 Mo ou un worker OCR parallèle peut saturer le tas, et la prévisualisation par plages est impossible. | Élevé | Élevé | GCM par segments (par exemple 1 Mo, IV et étiquette par segment, ordre et fin authentifiés), qui reste de l'AES-256-GCM au sens du 6.1.2 ; test de charge sur 200 Mo avec un tas limité ; mesure du coût CPU (6.1.3). Décision à acter par pm avant la fin de la vague 1. | dev3 |
| R05 | **OCR arabe** : modèle `ara` à ajouter (`tessdata_best` ou `fast`), qualité variable sur scans dégradés, `fra+ara` plus lent ; `unaccent` ne traite pas les diacritiques arabes ni les formes de l'alef. | Élevé | Moyen | Mesure sur jeu interne dès la vague 3 ; dictionnaire ou fonction de normalisation arabe prévu au 4.4 ; seuils à valider par MMED (Q08) ; protocole sur 300 pages dès réception de l'échantillon. | dev3 |
| R06 | **Pas de tmpfs sous Windows** : l'exigence « aucune copie en clair sur disque persistant » ne se démontre pas avec un répertoire temporaire classique. | Moyen | Moyen | Rendu PDFBox en mémoire et appel de Tesseract par entrée et sortie standard (`tesseract stdin stdout`) ; répertoire tmpfs configurable pour Linux ; test qui vérifie l'absence de fichier en clair. | dev3 |
| R07 | **Pas de Docker** : Testcontainers impossible ; tests d'intégration sur la base locale de chaque membre ; la future CI de la forge MMED devra fournir un service PostgreSQL. | Certain | Moyen | Profil `test` avec URL surchargeable ; bases préfixées par membre ; la CI documente son service PostgreSQL. | dev2 |
| R08 | **ClamAV absent** : le refus en échec fermé et la détection réelle ne sont vérifiés que par un serveur clamd factice. | Certain | Moyen | Simulateur du protocole `INSTREAM` ; fichier EICAR en recette UAT ; signalé comme « vérifié par simulateur ». | dev3 |
| R09 | **LibreOffice et veraPDF absents** : prévisualisation bureautique et PDF/A non vérifiables de bout en bout. veraPDF existe en bibliothèque Java mais sous licence GPL-3.0 ou MPL-2.0. | Certain | Moyen | Interfaces et simulateurs ; recette UAT ; licence MPL-2.0 retenue et vérifiée contre la cession de propriété (P-19). | dev3 |
| R10 | **Annuaire AD absent** : `objectGUID` binaire, `userAccountControl`, bascule entre contrôleurs, LDAPS et rechargement du secret ne sont testés que sur UnboundID embarqué. | Certain | Élevé | Simulateur fidèle (attributs AD, certificat de test) ; compte AD de test en UAT (Q02) ; signalé comme « vérifié par simulateur ». | dev1 |
| R11 | **NGINX et Prometheus absents** : configurations livrées mais non exécutées (`nginx -t` impossible). | Certain | Moyen | Relecture croisée pm et qa ; validation au premier déploiement UAT. | dev2 |
| R12 | **Parallélisme contre l'ordre de la feuille de route** : E5 et E6 sont développés avant la fusion d'E3 ; risque qu'un chemin d'accès échappe au filtre de droits. | Moyen | Élevé | Amorce `AccessPredicate` en début de vague 3 ; test d'architecture ; recette qa de chaque chemin (recherche, arbre, compteurs, extraits, prévisualisation, téléchargement, export, API). | dev1, qa |
| R13 | **Conflits de fusion** sur les fichiers chauds (`pom.xml`, `application*.yml`, `GlobalExceptionHandler`, `SecurityConfig`, `DocumentService`, changelog maître, menus Angular). | Élevé | Moyen | Propriétaire unique par vague et par fichier, amorces, règles de `INTEGRATION.md`. | pm |
| R14 | **Jeton en mémoire côté Angular** : un rechargement de page perd le jeton ; sans renouvellement silencieux au démarrage, l'utilisateur est déconnecté à chaque F5. | Élevé | Moyen | Renouvellement par cookie à l'initialisation de l'application ; test de bout en bout. | dev1 |
| R15 | **Suppression du pré-remplissage OCR** : fonction appréciée aujourd'hui, interdite par le 4.3.3 (« en aucun cas »). | Moyen | Faible | Le PDF fait foi : suppression en vague 3. Confirmation écrite de MMED demandée (Q20) pour éviter une contestation en recette. | dev3, pm |
| R16 | **Refonte du workflow sans ordre** : reprise des circuits en cours et de leurs signatures. | Moyen | Moyen | Script de reprise des circuits en vague 5 ; arbitrage MMED (Q20). | dev1 |
| R17 | **Perte de la clé maîtresse** : sans le keystore, les fichiers chiffrés sont définitivement perdus, y compris sur les postes de développement. | Faible | Élevé | Keystore et phrase secrète hors dépôt, sauvegardés à part (6.5) ; procédure de rotation testée ; KEK distincte par environnement. | dev3 |
| R18 | **Performance du point d'application unique** : requêtes récursives sur le chemin matérialisé et cache par sujet ; risque sur les listes et la recherche. | Moyen | Moyen | Cache invalidé par `version_habilitations`, taille bornée ; mesure sur la volumétrie du 6.6 en vague 3. | dev1 |
| R19 | **Scellement exporté hors base** : pas de support en écriture seule ni de journal centralisé sur le poste. | Certain | Moyen | Export vers un fichier dédié en local ; cible réelle à fixer avec MMED (Q13). | dev2 |
| R20 | **Partitionnement mensuel du journal d'audit** : pas d'extension de gestion des partitions supposée ; une partition manquante bloque toute écriture d'audit, donc toute action. | Moyen | Élevé | Tâche planifiée qui crée les partitions à l'avance, partition par défaut, alerte ; test du passage de mois. | dev2 |
| R21 | **Tests exécutés en superutilisateur** (`postgres` en `trust`) : les droits INSERT seul de `ged_app` sur l'audit ne seraient jamais démontrés. | Élevé | Moyen | Les tests de l'audit se connectent sous `ged_app` ; test qui vérifie le refus d'UPDATE et de DELETE. | dev2 |
| R22 | **Changesets déjà fusionnés modifiés** : erreur de somme de contrôle Liquibase sur toutes les bases des membres. | Moyen | Moyen | Règle : un changeset fusionné n'est jamais modifié, on en ajoute un nouveau ; contrôle par pm à chaque fusion. | pm |
| R23 | **Échantillon OCR et volumétrie** dépendent de MMED : T-028 et P-14 ne peuvent pas passer à « Identique » sans lui. | Élevé | Moyen | Outillage prêt dès la vague 3 ; relance de MMED à chaque fin de vague. | pm |
| R24 | **Charge de dev1** : 47 lignes sur le chemin critique (E1, E2, E3, E7, E8). Tout retard décale la fin. | Moyen | Élevé | Aucune tâche transverse à dev1 ; dev2 absorbe les finitions ; point d'avancement hebdomadaire. | pm |
| R25 | **Coût des tests sur PostgreSQL** : plus de 120 tests, bientôt plusieurs centaines, sur base réelle ; temps de build en hausse. | Moyen | Faible | Nettoyage par transaction annulée, base recréée une fois par exécution. | dev2 |
| R26 | **Revue client, R1 de la synthèse (D1)** : sans relecture de l'état du compte AD, un utilisateur désactivé dans l'annuaire **garde l'accès jusqu'à l'expiration de son jeton de renouvellement** (durée absolue de 8 h au V3), car le renouvellement ne repasse pas par l'annuaire. L'exposition réelle est bornée par l'inactivité de 30 minutes : seule une session active en continu atteint 8 h. | Moyen | Élevé | À présenter au client : durée absolue de session réduite et paramétrable (proposition 4 h) ; révocation manuelle de toutes les sessions d'un utilisateur par l'Administrateur (table `session`) ; procédure de départ qui inclut cette révocation. | dev1, pm |
| R27 | **Validateurs partis (D1)** : la GED ne détecte plus un compte désactivé ; le signalement automatique des validations en attente d'un validateur désactivé (V3 §3.4.2 et §12.8) n'a plus de déclencheur, et un circuit parallèle peut rester bloqué. | Élevé | Moyen | Réaffectation explicite d'un validateur en attente par l'Administrateur, auditée (T-111) ; tableau de bord des circuits en attente depuis plus de N jours ; confirmation client (QR1). | dev1 |
| R28 | **Délégation vers un compte désactivé (QR9)** : D1 retire la lecture de l'état AD, alors que le §5.5 exige le rejet d'une identité déléguée désactivée ; une application habilitée à déléguer pourrait déposer ou valider au nom d'un agent parti. | Moyen | Élevé | Question QR9 à MMED ; en attendant, recherche annuaire obligatoire, restriction d'adresses des clés de délégation, audit à double identité. | dev2, pm |
| R29 | **Retour arrière de la règle de workflow (lot E8)** : il reste impossible sur une base qui ne contient aucune règle (limite relevée à l'intégration des correctifs de la vague 6), alors que le §4.2.2 exige un retour arrière explicite par changeset. S'y ajoute celui de la reprise des signatures (`202610021120`), refusé par défaut parce qu'il perdrait des données (ANO-E8-003) et poursuivi seulement sur décision explicite (`DEPLOIEMENT.md` §8) ; cette garde est vérifiée (recette de la vague 7). **ANO-E8-004** (ouverte, dev1) : un retour arrière au-delà du jalon `workflow-e8` défait d'abord sans garde `202610031000` (marques d'échéance) et `202610021130` (diffusions, rôle LECTEUR), puis s'arrête sur la garde de `202610021120` et laisse la base à mi-chemin, contrairement à `DEPLOIEMENT.md` §8. | Élevé | Moyen | Sauvegarde complète (base, fichiers, clés) avant toute montée en UAT ou en production : elle reste le moyen de retour arrière du lot E8 ; retour arrière exercé sur une copie de la base cible avant la bascule ; jusqu'à la correction d'ANO-E8-004, aucun retour arrière au-delà de `workflow-e8` sans restauration préparée ; correction par un nouveau changeset (garde en tête de séquence ou retour arrière des diffusions et échéances conservatoire). | dev1, pm |
| R30 | **Débit OCR sous la cible du DAT** : mesuré à 6 à 10 s par page et par cœur (modèles précis, fonds bilingue) contre 1 à 3 s au §4.3.4, soit ~13 h au lieu de 2 à 4 h pour 20 000 pages sur 4 vCPU (§6.6) ; écart de 3 à 6 fois (`docs/exploitation/ESSAIS-DE-CHARGE.md` §2.2, §6). Le seuil d'acceptation du §4.3.2 (≥ 6 pages/min/cœur) et D6 pour le flux courant restent tenus. | Certain | Moyen | Worker OCR dimensionné temporairement à 16 vCPU pour la Phase 7 et la reprise (~8 jours au lieu de ~33) ; demande à MMED de corriger les cibles des §4.3.4 et §6.6 ; `tessdata_fast` écarté (CER arabe doublé). | pm, dev3 |
| R31 | **Reprise et flux courant dans la même file `ocr_job`** : les ~150 000 documents de la reprise représentent ~33 jours de calcul sur 4 vCPU ; en file unique, chaque dépôt courant attendrait la fin de la reprise et D6 (24 h) ne serait plus tenu pendant cette période. | Élevé | Élevé | Priorité du flux courant sur la reprise (priorité de job ou file séparée) avant de lancer la reprise ; T-035 ne repasse « Identique » qu'après ce correctif. | dev3 |
| R32 | **Recherche à la volumétrie cible** : un terme fréquent prend 10 à 40 s à 50 000 documents (tri de tous les résultats, total exact, extraits sur le texte complet) ; `IndexationService.rechercher` (multicritère historique) charge tout le résultat en mémoire (6,8 s et 744 Mo de tas à 50 000 documents) ; inutilisable à 450 000 documents (5 ans). | Élevé | Élevé | Correctifs du §5.4 du rapport d'essais : total plafonné, rang calculé sur un ensemble borné avec droits appliqués avant, `ts_rank`, extraits sur un fragment, multicritère en SQL paginé. | dev3 |
| R33 | **Statistiques obsolètes après un chargement de masse** : sans `VACUUM ANALYZE`, le planificateur balaie toute la table de texte (5 s à 50 000 documents, même pour un terme rare). | Certain | Moyen | `VACUUM ANALYZE` obligatoire après chaque chargement de masse et en fin de reprise, inscrit dans la procédure d'exploitation et de reprise ; `autovacuum_analyze_scale_factor` abaissé sur `document_texte`. | dev2, dev3 |

## 3. Informations à obtenir de Marchica Med

Valeur par défaut = valeur écrite dans le PDF, appliquée tant que MMED n'a pas répondu.

| N° | Question | Réf. PDF | Valeur par défaut | Nécessaire pour | Bloquant ? |
|---|---|---|---|---|---|
| Q01 | Domaine et base de recherche de l'annuaire | 3.3 | `DC=marchicamed,DC=ma` | E2 (UAT) | Non en dev, oui en UAT |
| Q02 | Nom du contrôleur de domaine (**revue client D4 : un seul aujourd'hui, un second prévu**), compte de service LDAP en lecture seule, chaîne de certificats, politique de rotation du mot de passe ; compte AD de test pour l'UAT | 3.3, 3.4 | `svc-ged-ldap`, LDAPS 636, liste de contrôleurs | E2 | Oui en UAT |
| Q03 | Politique PKI : autorité interne ou publique, certificat TLS du serveur, renouvellement automatisé | 6.2.1 | TLS 1.2 minimum | E10 | Oui en UAT |
| Q04 | KMS ou HSM existant, sinon validation du keystore PKCS#12 et du coffre de secrets | 6.1.2, 10.1 | Keystore PKCS#12, rotation annuelle | E5 | Non |
| Q05 | Serveurs Linux (distribution), stockage des fichiers (volume ou S3), conteneurs imposés ou non | 6.1.1, 10.2 | JAR + systemd, volume monté | E10 | Oui en UAT |
| Q06 | Support de sauvegarde distinct, validation des RPO (15 min base, 24 h fichiers), du RTO (8 h) et des rétentions (30 jours, mensuelle 12 mois) | 6.5 | Valeurs du PDF | E10 | Non |
| Q07 | Outil de supervision existant, destinataires des alertes, seuils (sonde 2 min, 5xx 2 % sur 5 min, disque 80 %) | 6.7 | Prometheus + Alertmanager | E10 | Non |
| Q08 | Seuils d'acceptation OCR : CER ≤ 5 % français imprimé, ≤ 10 % arabe, ≥ 6 pages par minute et par cœur | 4.3.2 | Valeurs du PDF | E6 | Non |
| Q09 | Échantillon de 300 pages stratifié et vérité terrain de 100 pages transcrites par le bureau d'ordre ; échantillon de 20 000 pages de la Phase 7 | 4.3.2, 6.6 | — | E6, E11 | Oui pour T-028 et P-14 |
| Q10 | Délai maximal entre dépôt et recherche | 4.3.4 | **Répondu (revue client D6) : 24 heures maximum**, au lieu de 5 min et 60 min au 95e centile | E6 | Clos |
| Q11 | Hypothèses de volumétrie : 150 000 documents repris, 60 000 par an, 1,5 Mo en moyenne, 5 millions d'événements d'audit par an | 6.6 | Valeurs du PDF | E10, E11 | Non |
| Q12 | Rétention des journaux techniques (90 jours) et du journal d'audit (10 ans) ; qui décide de l'archivage hors ligne des partitions | 7.3.1, 7.4.3 | 90 jours, 10 ans | E4 | Non |
| Q13 | Cible de l'export du scellement : support en écriture seule, journal centralisé, syslog ; source NTP ; autorisation de l'extension `pgaudit` | 7.4.1, 7.4.2 | Fichier dédié | E4, E10 | Non en dev |
| Q14 | Chiffrement du volume de la base (LUKS ou équivalent) fourni par l'infrastructure | 6.1.3 | Exigé | E10 | Non |
| Q15 | Relais SMTP, adresse d'expédition | 12.9, 6.7 | — | E8 | Oui en UAT |
| Q16 | Durées légales de conservation par catégorie et point de départ de l'échéance | 12.9, 7.4.3 | Date du document | E7, E8 | Non (paramétrable) |
| Q17 | Existe-t-il une base MySQL de production avec des données réelles à reprendre, et où se trouvent les fichiers associés ? | 4.2.1 (Art. 24) | Reprise scriptée | E1 | Oui pour la reprise |
| Q18 | Applications clientes à ouvrir (bureau d'ordre, future application des marchés), plages d'adresses, quotas (600 par minute, 100 000 par jour), besoin de délégation ; contrat d'interface du bureau d'ordre | 5.2, 5.4, 5.5 | Valeurs du PDF | E9 | Non |
| Q19 | Forge Git (GitLab ou Gitea), relecteurs désignés en lecture seule, calendrier des revues de code | 9.4 | — | E0 | Non pour le code, oui pour T-089 |
| Q20 | Arbitrages : workflow sans ordre, suppression du pré-remplissage des index par l'OCR | 4.3.3, 12.8 | **Workflow parallèle confirmé (revue client D7)** ; pré-remplissage : le PDF tranche (suppression), confirmation écrite toujours souhaitée | E6, E8 | Non |
| Q21 | Version complète du dossier V3 avec les chapitres 13, 14 et 15, ou confirmation que la présente liste les remplace | 1.2 | Présente liste | Toutes | Non |
| Q22 | Version d'Angular attendue (« LTS courante ») et de PostgreSQL au démarrage de la Phase 4 | 2.2.1 | Angular actuel, PostgreSQL 16 | E11 | Non |
| Q23 | Équipe projet minimale (Art. 29) : noms des titulaires des profils UI/UX et opérateurs de numérisation | 11.1 | — | P-18 | Hors code |

### Questions issues de la revue technique client

Numérotées QR1 à QR5 comme Q1 à Q5 de `DECISIONS-REVUE-TECHNIQUE.md`, plus trois questions
relevées par pm à la relecture du compte rendu brut. Tant qu'elles restent ouvertes, le
mécanisme du dossier V3 s'applique.

| N° | Question | Source | Position retenue en attendant | Lot | Bloquant ? |
|---|---|---|---|---|---|
| QR1 | **Circuit figé ou non.** Le compte rendu indique qu'un nouveau validateur « remplace l'ancien pour les étapes restantes » d'un circuit en cours ; le V3 (§12.8) fige le circuit au dépôt. La page 17 citée à la réunion est celle du **dossier fonctionnel** (§4.5), pas du dossier technique. La modification d'une règle qui ne s'applique qu'aux nouveaux dépôts est, elle, conforme au V3. | Revue §3, suivi | Circuit figé ; réaffectation explicite d'un validateur en attente par l'Administrateur, tracée (déjà prévue au V3 §3.4.2 comme « réattribution manuelle »). | E8 | Non |
| QR2 | **Stockage des jetons.** Le compte rendu cite « par exemple » un jeton dans le stockage local ; le V3 met le jeton d'accès en mémoire et le renouvellement en cookie httpOnly haché en base. | Revue §6 | Mécanisme du V3, plus sûr, qui satisfait « jeton + refresh token haché ». | E2 | Non |
| QR3 | **Attributs AD à retirer.** Le compte rendu parle de deux attributs « liés à Object sure ID » (transcription probable d'`objectGUID` ou `objectSID`), sans les nommer. L'identifiant unique AD reste explicitement nécessaire. Hypothèse de pm : `userAccountControl` et `userPrincipalName`, déjà rendus inutiles par D1 et D2. | Revue §10 | Lecture du strict minimum, liste configurable ; `objectGUID` conservé. | E2 | Non |
| QR4 | **Verrou.** Le compte rendu décrit l'immuabilité du fichier source et la modification limitée aux métadonnées ou à une nouvelle version : c'est le principe d'écriture unique du V3 (§6.1.1), pas une redéfinition de la fonction de verrouillage, sur laquelle aucune décision n'a été prise. | Revue §4 | Verrou du V3 : gel complet (fiche, versions, déplacement, réindexation, archivage). À confirmer. | E7 | Non |
| QR5 | **Mention de Python.** La phrase « trois lignes de code en Python » est dans le dossier fonctionnel (p. 11) ; le dossier technique exclut déjà Python (§4.3.1). « TC RAC » dans le compte rendu est vraisemblablement une transcription de « Tesseract ». | Revue §2, §10 | Correction du dossier fonctionnel par son auteur (action « Speaker 3 »). | Doc. | Non |
| QR6 | **Version courante désignable.** D9 archive automatiquement l'ancienne version au versement ; le V3 (§12.8) et le dossier fonctionnel (§4.6.4) permettent de redésigner une version antérieure comme courante. Cette possibilité est-elle maintenue ? | Revue §4 | Maintenue (V3) ; la bascule automatique au versement s'applique. | E7 | Non |
| QR7 | **Archivage de dossier (D10).** Un nouveau dépôt dans un dossier archivé est-il refusé ou crée-t-il un document actif ? La copie PDF « essentiellement une image » évoquée en réunion convient-elle, ou faut-il conserver une couche texte (PDF/A-2 depuis LibreOffice pour la bureautique, image pour les scans) ? | Revue §4, §5 | Dépôt refusé dans un dossier archivé (lecture seule) ; PDF/A-2 avec couche texte quand la source en a une. | E7 | Non |
| QR8 | **Protocole OCR sans Python (D5).** Le protocole du §4.3.2 compare Tesseract à PaddleOCR et EasyOCR, qui exigent Python : la comparaison est-elle abandonnée au profit d'une simple mesure de Tesseract contre les seuils ? | Revue §2, §10 | Mesure de Tesseract seul contre les seuils, outillage Java. | E6 | Non |
| QR9 | **Délégation d'identité et compte désactivé** (relevé par qa). Le §5.5 impose de rejeter (422 `IDENTITE_DELEGUEE_INVALIDE`) un en-tête `X-On-Behalf-Of` désignant un compte désactivé. Avec D1, la GED ne lit plus l'état du compte AD ; l'identité déléguée n'est vérifiée que par une recherche annuaire avec le compte de service, et un compte désactivé reste trouvable : le rejet n'est plus garanti. Faut-il lire `userAccountControl` pour ce seul cas (lecture ponctuelle, sans tâche périodique), ou MMED accepte-t-elle qu'une application habilitée à déléguer puisse agir pour un compte désactivé ? | Revue D1, §5.5 | Recherche annuaire obligatoire (identité inconnue = 422) ; le cas du compte désactivé reste ouvert et est signalé comme non conforme au §5.5 tant que MMED n'a pas tranché. | E9 (vague 4) | Non (bloque la conformité de T-055) |

## 4. Suivi

- Les questions Q02, Q03, Q05, Q15 bloquent l'UAT, pas le développement : à envoyer dès cette
  semaine, avec QR1 à QR9 et la présentation du risque R26.
- R-04 (validateur restreint à un type) : obtenir la confirmation écrite du hors périmètre, le
  compte rendu disant « probablement trop détaillé ».
- Les réponses sont reportées ici avec leur date et leur source, puis dans la configuration de
  l'environnement concerné (jamais dans le dépôt pour les secrets).
