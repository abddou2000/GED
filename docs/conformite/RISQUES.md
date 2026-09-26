# Registre des risques et questions pour Marchica Med

Tenu par **pm**. Revu à chaque fin de vague. Probabilité et impact : Faible, Moyen, Élevé.

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

## 3. Informations à obtenir de Marchica Med

Valeur par défaut = valeur écrite dans le PDF, appliquée tant que MMED n'a pas répondu.

| N° | Question | Réf. PDF | Valeur par défaut | Nécessaire pour | Bloquant ? |
|---|---|---|---|---|---|
| Q01 | Domaine et base de recherche de l'annuaire | 3.3 | `DC=marchicamed,DC=ma` | E2 (UAT) | Non en dev, oui en UAT |
| Q02 | Noms des contrôleurs de domaine (au moins deux), compte de service LDAP en lecture seule, chaîne de certificats, politique de rotation du mot de passe ; compte AD de test pour l'UAT | 3.3, 3.4 | `svc-ged-ldap`, LDAPS 636 | E2 | Oui en UAT |
| Q03 | Politique PKI : autorité interne ou publique, certificat TLS du serveur, renouvellement automatisé | 6.2.1 | TLS 1.2 minimum | E10 | Oui en UAT |
| Q04 | KMS ou HSM existant, sinon validation du keystore PKCS#12 et du coffre de secrets | 6.1.2, 10.1 | Keystore PKCS#12, rotation annuelle | E5 | Non |
| Q05 | Serveurs Linux (distribution), stockage des fichiers (volume ou S3), conteneurs imposés ou non | 6.1.1, 10.2 | JAR + systemd, volume monté | E10 | Oui en UAT |
| Q06 | Support de sauvegarde distinct, validation des RPO (15 min base, 24 h fichiers), du RTO (8 h) et des rétentions (30 jours, mensuelle 12 mois) | 6.5 | Valeurs du PDF | E10 | Non |
| Q07 | Outil de supervision existant, destinataires des alertes, seuils (sonde 2 min, 5xx 2 % sur 5 min, disque 80 %) | 6.7 | Prometheus + Alertmanager | E10 | Non |
| Q08 | Seuils d'acceptation OCR : CER ≤ 5 % français imprimé, ≤ 10 % arabe, ≥ 6 pages par minute et par cœur | 4.3.2 | Valeurs du PDF | E6 | Non |
| Q09 | Échantillon de 300 pages stratifié et vérité terrain de 100 pages transcrites par le bureau d'ordre ; échantillon de 20 000 pages de la Phase 7 | 4.3.2, 6.6 | — | E6, E11 | Oui pour T-028 et P-14 |
| Q10 | Délai maximal entre dépôt et recherche : 5 min (20 pages, file vide), 60 min au 95e centile en pointe | 4.3.4 | Valeurs du PDF | E6 | Non |
| Q11 | Hypothèses de volumétrie : 150 000 documents repris, 60 000 par an, 1,5 Mo en moyenne, 5 millions d'événements d'audit par an | 6.6 | Valeurs du PDF | E10, E11 | Non |
| Q12 | Rétention des journaux techniques (90 jours) et du journal d'audit (10 ans) ; qui décide de l'archivage hors ligne des partitions | 7.3.1, 7.4.3 | 90 jours, 10 ans | E4 | Non |
| Q13 | Cible de l'export du scellement : support en écriture seule, journal centralisé, syslog ; source NTP ; autorisation de l'extension `pgaudit` | 7.4.1, 7.4.2 | Fichier dédié | E4, E10 | Non en dev |
| Q14 | Chiffrement du volume de la base (LUKS ou équivalent) fourni par l'infrastructure | 6.1.3 | Exigé | E10 | Non |
| Q15 | Relais SMTP, adresse d'expédition | 12.9, 6.7 | — | E8 | Oui en UAT |
| Q16 | Durées légales de conservation par catégorie et point de départ de l'échéance | 12.9, 7.4.3 | Date du document | E7, E8 | Non (paramétrable) |
| Q17 | Existe-t-il une base MySQL de production avec des données réelles à reprendre, et où se trouvent les fichiers associés ? | 4.2.1 (Art. 24) | Reprise scriptée | E1 | Oui pour la reprise |
| Q18 | Applications clientes à ouvrir (bureau d'ordre, future application des marchés), plages d'adresses, quotas (600 par minute, 100 000 par jour), besoin de délégation ; contrat d'interface du bureau d'ordre | 5.2, 5.4, 5.5 | Valeurs du PDF | E9 | Non |
| Q19 | Forge Git (GitLab ou Gitea), relecteurs désignés en lecture seule, calendrier des revues de code | 9.4 | — | E0 | Non pour le code, oui pour T-089 |
| Q20 | Arbitrages : workflow sans ordre (le séquentiel actuel disparaît), suppression du pré-remplissage des index par l'OCR | 4.3.3, 12.8 | Conformément au PDF | E6, E8 | Non (le PDF tranche) |
| Q21 | Version complète du dossier V3 avec les chapitres 13, 14 et 15, ou confirmation que la présente liste les remplace | 1.2 | Présente liste | Toutes | Non |
| Q22 | Version d'Angular attendue (« LTS courante ») et de PostgreSQL au démarrage de la Phase 4 | 2.2.1 | Angular actuel, PostgreSQL 16 | E11 | Non |
| Q23 | Équipe projet minimale (Art. 29) : noms des titulaires des profils UI/UX et opérateurs de numérisation | 11.1 | — | P-18 | Hors code |

## 4. Suivi

- Les questions Q02, Q03, Q05, Q15 bloquent l'UAT, pas le développement : à envoyer dès cette
  semaine.
- Les réponses sont reportées ici avec leur date et leur source, puis dans la configuration de
  l'environnement concerné (jamais dans le dépôt pour les secrets).
