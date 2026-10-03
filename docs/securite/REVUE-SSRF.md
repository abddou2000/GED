# Revue SSRF — appels sortants de la GED (P-12, OWASP A10)

Objet : vérifier qu'aucune entrée d'un utilisateur ou d'une application cliente
ne peut faire émettre à la GED une requête vers une destination de son choix
(Server-Side Request Forgery), et recenser tous les appels sortants.

Date : 27/09/2026, complétée le 28/09/2026 (ANO-E11-001, ANO-E11-002) — branche `ct/dev2`. Revue reconduite à chaque ajout d'un appel
sortant : le test `securite.AppelsSortantsTest` échoue tant que l'inventaire
ci-dessous n'a pas été complété.

## 1. Inventaire des appels sortants

| Appel | Classe | Destination | Origine de la destination | Entrée utilisateur transmise |
|---|---|---|---|---|
| PostgreSQL (JDBC) | pool Hikari (Spring) | `DB_HOST:DB_PORT` | configuration (`application.yml`, variables d'environnement) | requêtes paramétrées uniquement |
| Annuaire LDAPS | `identite.annuaire.AnnuaireLdap`, `ConfigurationAnnuaire`, `ControleursAnnuaire` (une source par contrôleur, bascule D4 y compris sur un contrôleur muet), `SondeAnnuaire`, `FabriqueSocketsLdaps` (sockets TLS, hôte et port transmis par JNDI) ; `EtatCompteAnnuaireLdap` (D15 : `userAccountControl` seul, à la délégation, même source) ; `EcheanceSecretAnnuaire` (P-02 : entrée du compte de service lui-même, par son DN configuré, `msDS-UserPasswordExpiryTimeComputed` et `accountExpires`, une lecture par heure au plus) | `GED_LDAP_URLS` (un ou plusieurs contrôleurs, D4) | configuration | identifiant de connexion, encodé par `LdapEncoder.filterEncode` (injection LDAP) ; objectGUID (UUID déjà résolu) encodé octet par octet (`GuidAnnuaire.pourFiltre`) ; jamais une URL |
| Relais SMTP | `notification.ExpediteurCourriels` (Spring Mail) | `GED_SMTP_HOTE:GED_SMTP_PORT` | configuration | destinataire = courriel du cache d'annuaire, jamais saisi ; lien = `GED_URL_APPLICATION` + chemin interne |
| clamd (INSTREAM) | `fichier.controle.ClientClamd`, `supervision.SondeAntivirus` | `ged.fichiers.antivirus.hote/port` | configuration | contenu du fichier, transmis comme données |
| Tesseract (processus) | `ocr.moteur.MoteurTesseract` | exécutable `GED_TESSERACT` | configuration | image rendue par la GED, sur l'entrée standard |
| combine_tessdata (processus, P-14) | `ocr.moteur.ModelesEntiers` (au démarrage, `ged.ocr.modeles=entiers`) | exécutable `GED_OCR_COMBINE_TESSDATA`, sinon `combine_tessdata` à côté de `GED_TESSERACT` | configuration | aucune : copie d'un modèle de `GED_TESSDATA` dans un répertoire de travail de la GED |
| LibreOffice (processus) | `fichier.previsualisation.ConvertisseurLibreOffice` (aperçu, copie PDF/A) | exécutable `GED_LIBREOFFICE` | configuration | fichier copié sous un nom fixe (`document.<ext>`, `source.<ext>`) dans un répertoire temporaire créé par la GED |
| Flux local vers LibreOffice | `ConvertisseurLibreOffice` lance `soffice` sur le même serveur | processus enfant (aucune connexion réseau de la GED vers LibreOffice : ligne de commande et fichiers du répertoire de travail) | configuration | document déposé, converti tel quel : LibreOffice lit donc un contenu choisi par le déposant (§3) |
| Annuaire simulé | `identite.annuaire.SimulateurAnnuaire` | aucune : écoute sur la boucle locale, profils dev et test seulement | configuration de test | — |
| Proxys de confiance | `journalisation.ConfigurationProxysDeConfiance` | aucune : `InetAddress` sur une adresse IP littérale, refus d'un nom d'hôte (pas de résolution DNS) | configuration | — |
| Coffre de clés (KEK) | `fichier.cles.KeystoreKeyProvider` | fichier PKCS#12 local | configuration | aucune |
| veraPDF (validation PDF/A) | `cycledevie.conservation.ValidateurVeraPdf` | aucune (bibliothèque en processus) | — | copie produite par la GED |

Aucun client HTTP (RestTemplate, WebClient, `java.net.http.HttpClient`,
`URL.openConnection`) n'existe dans le code : la GED n'appelle aucune URL, fournie
ou non par un utilisateur. Les en-têtes `Link`, `Location` et le lien des e-mails
sont construits depuis la configuration, jamais depuis l'en-tête `Host` de la
requête.

## 2. Vérifications

1. **Aucune destination construite depuis une entrée** : les classes de
   l'inventaire ne reçoivent ni `HttpServletRequest`, ni fichier envoyé, ni
   paramètre de contrôleur (vérifié par `AppelsSortantsTest`) ; hôtes, ports et
   commandes viennent de `@ConfigurationProperties` lues au démarrage.
2. **Inventaire fermé** : toute nouvelle ouverture de socket (y compris par une
   fabrique `SocketFactory` / `createSocket`), écoute, résolution de nom, contexte
   ou client LDAP (`LdapContextSource`, `InitialLdapContext`, `LDAPConnection`),
   conversion d'URI en URL (`toURL()`), courriel, connexion JDBC directe ou
   processus hors de l'inventaire fait échouer `AppelsSortantsTest`. Réciproquement,
   chaque entrée de l'inventaire doit être reconnue par un motif du test et
   figurer dans ce document (`inventaireJustifie`) : un motif manquant se voit.
3. **Arguments de processus** : chemins absolus créés par la GED (pas de nom
   fourni par l'utilisateur, donc pas d'argument commençant par `-`), liste
   d'arguments sans interpréteur de commandes (`ProcessBuilder`, jamais `sh -c`).
4. **Échec fermé** : destination injoignable = refus (antivirus, annuaire) ou
   reprise différée (SMTP), jamais un repli vers une autre destination.

## 3. Risques résiduels et mesures

| Risque | Mesure |
|---|---|
| Un document Office déposé référence une ressource externe (image liée, lien OLE) que LibreOffice tente de charger pendant la conversion | (1) `deploiement/libreoffice/ged-securite.xcd`, couche de configuration imposée : ressources liées d'un document non approuvé bloquées, macros désactivées ; (2) filtrage des sorties du service par systemd (`sorties.conf.exemple`, `IPAddressDeny=any` + destinations d'exploitation), qui s'applique aux processus enfants ; profil LibreOffice jetable par conversion |
| Le filtre n'accepte que des adresses, la configuration désigne des noms d'hôtes | Résolveurs DNS de MMED autorisés (ou noms figés dans `/etc/hosts`) ; une ligne par contrôleur de domaine ; `deploiement/scripts/verifier-sorties.sh` résout chaque nom de `ged.env` comme la JVM et échoue si une adresse n'est pas autorisée (à lancer à l'installation et à chaque changement d'adresse) |
| **Risque résiduel déclaré** (acceptation par MMED) : la boucle locale reste autorisée (clamd local) ; LibreOffice, processus enfant, peut donc joindre ce qui y écoute | L'API (`SERVER_ADDRESS`) et Actuator (`GED_MANAGEMENT_ADRESSE`) écoutent sur l'adresse du serveur, non autorisée par le filtre : sur la boucle ne reste que clamd, dont le protocole (`zINSTREAM`, `zPING`) ignore une requête HTTP ; rien d'autre ne doit écouter sur la boucle locale de `ged-app`. Parade complète si MMED l'exige : clamd sur un serveur distinct et retrait de `localhost` du filtre |
| Entités externes XML (XXE) dans les métadonnées XMP d'un PDF analysé par PDFBox / veraPDF | Analyse sur la copie produite par la GED ; même filtrage réseau ; bibliothèques tenues à jour (OWASP Dependency-Check en CI) |
| Destination de configuration modifiée par un tiers | Fichier d'environnement 0400 propriété de `ged` (`deploiement/systemd`), `/etc/ged` en lecture seule pour le service |

Vérifié par test automatisé : points 2.1 et 2.2 ; `verifier-sorties.sh` éprouvé
avec un résolveur simulé (noms résolus, contrôleur ajouté sans ligne de filtre,
résolveur non autorisé). Vérifié sur le papier : le filtrage systemd et la
configuration LibreOffice (ni systemd ni LibreOffice sur le poste de
développement) ; contrôle à l'installation dans `EXPLOITATION.md` §12.
