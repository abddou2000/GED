# Revue SSRF — appels sortants de la GED (P-12, OWASP A10)

Objet : vérifier qu'aucune entrée d'un utilisateur ou d'une application cliente
ne peut faire émettre à la GED une requête vers une destination de son choix
(Server-Side Request Forgery), et recenser tous les appels sortants.

Date : 27/09/2026 — branche `ct/dev2`. Revue reconduite à chaque ajout d'un appel
sortant : le test `securite.AppelsSortantsTest` échoue tant que l'inventaire
ci-dessous n'a pas été complété.

## 1. Inventaire des appels sortants

| Appel | Classe | Destination | Origine de la destination | Entrée utilisateur transmise |
|---|---|---|---|---|
| PostgreSQL (JDBC) | pool Hikari (Spring) | `DB_HOST:DB_PORT` | configuration (`application.yml`, variables d'environnement) | requêtes paramétrées uniquement |
| Annuaire LDAPS | `identite.annuaire.AnnuaireLdap`, `ConfigurationAnnuaire`, `SondeAnnuaire` | `GED_LDAP_URLS` | configuration | identifiant de connexion, encodé par `LdapEncoder.filterEncode` (injection LDAP) ; jamais une URL |
| Relais SMTP | `notification.ExpediteurCourriels` (Spring Mail) | `GED_SMTP_HOTE:GED_SMTP_PORT` | configuration | destinataire = courriel du cache d'annuaire, jamais saisi ; lien = `GED_URL_APPLICATION` + chemin interne |
| clamd (INSTREAM) | `fichier.controle.ClientClamd`, `supervision.SondeAntivirus` | `ged.fichiers.antivirus.hote/port` | configuration | contenu du fichier, transmis comme données |
| Tesseract (processus) | `ocr.moteur.MoteurTesseract` | exécutable `GED_TESSERACT` | configuration | image rendue par la GED, sur l'entrée standard |
| LibreOffice (processus) | `fichier.previsualisation.ConvertisseurLibreOffice` (aperçu, copie PDF/A) | exécutable `GED_LIBREOFFICE` | configuration | fichier copié sous un nom fixe (`document.<ext>`, `source.<ext>`) dans un répertoire temporaire créé par la GED |
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
2. **Inventaire fermé** : toute nouvelle ouverture de socket, de processus ou de
   connexion LDAP hors de l'inventaire fait échouer `AppelsSortantsTest`.
3. **Arguments de processus** : chemins absolus créés par la GED (pas de nom
   fourni par l'utilisateur, donc pas d'argument commençant par `-`), liste
   d'arguments sans interpréteur de commandes (`ProcessBuilder`, jamais `sh -c`).
4. **Échec fermé** : destination injoignable = refus (antivirus, annuaire) ou
   reprise différée (SMTP), jamais un repli vers une autre destination.

## 3. Risques résiduels et mesures

| Risque | Mesure |
|---|---|
| Un document Office déposé référence une ressource externe (image liée, lien OLE) que LibreOffice tente de charger pendant la conversion | Filtrage des sorties du service par systemd (`deploiement/systemd/ged-backend.service.d/sorties.conf.exemple`, `IPAddressDeny=any` + destinations d'exploitation), qui s'applique aux processus enfants ; profil LibreOffice jetable par conversion |
| Entités externes XML (XXE) dans les métadonnées XMP d'un PDF analysé par PDFBox / veraPDF | Analyse sur la copie produite par la GED ; même filtrage réseau ; bibliothèques tenues à jour (OWASP Dependency-Check en CI) |
| Destination de configuration modifiée par un tiers | Fichier d'environnement 0400 propriété de `ged` (`deploiement/systemd`), `/etc/ged` en lecture seule pour le service |

Vérifié par test automatisé : points 2.1 et 2.2. Vérifié sur le papier : le
filtrage systemd (pas de systemd sur le poste de développement).
