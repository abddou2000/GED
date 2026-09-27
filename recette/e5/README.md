# Recette E5 — stockage sécurisé des fichiers

Bash + coreutils + curl uniquement (**aucun Python**, décision D5 de la revue technique) :
les scripts s'exécutent sur le serveur qui porte le stockage. Sortie
`RESULTAT|id|OK/ECHEC/AVERT/NA|…` puis `BILAN|…`, code 0 si aucun `ECHEC`. Identifiants
et contrat d'API : `recette/lib/api.sh` ; options communes `--url`, `--racine`,
`--nettoyer` : `commun-e5.sh`.

| Script | Critère / exigence | Preuve |
|---|---|---|
| `verifier-aucun-clair.sh --racine R [--racine-cache C]` | « Aucun fichier en clair sur le disque » — 6.1.1, 6.1.2 | Pour chaque fichier : arborescence `aa/bb/<uuid>.enc`, en-tête `GEDC` v1, aucune signature de format en tête, aucune chaîne en clair connue (y compris mots-témoins et EICAR), contenu incompressible (gzip) ; temporaires abandonnés ; racine vide refusée |
| `verifier-alteration.sh --racine R` | « Un fichier altéré est détecté » — 6.1.2, 6.1.4 | Octet inversé, troncature, substitution par le chiffré d'un autre document, grand fichier altéré en dernier segment : aucun contenu altéré n'est servi ; restauration puis lecture identique ; vérification à la demande (`GED_API_VERIF_INTEGRITE`) et audit (`GED_API_AUDIT`) dès leur livraison |
| `verifier-antivirus.sh [--antivirus-arrete]` | « Un fichier infecté est refusé » — 6.1.5 | Témoin sain accepté, EICAR → 422 `FICHIER_INFECTE` sans rien écrire, EICAR déguisé en `.pdf` refusé ; ClamAV arrêté → 503 `ANTIVIRUS_INDISPONIBLE` (échec fermé) |
| `verifier-type-reel.sh` | Type réel par le contenu (Tika), 415 — 6.1.5, 5.3.2 | Texte, exécutable, DOCX, PNG sous `.pdf` → 415 `FORMAT_NON_AUTORISE` sans rien écrire ; vrai PDF sous `.txt` accepté |
| `verifier-taille.sh` | 413, 100 Mo par type, 200 Mo plateforme — 6.1.5 | 200 Mio + 1 → 413 ; limite du type + 1 → 413 `FICHIER_TROP_VOLUMINEUX` ; limite exacte acceptée |
| `verifier-composants.sh [--clamd hote:port]` (lance `BancComposantsE5.java`) | E5 **au niveau des composants** tant que le dépôt HTTP n'est pas branché (vague 1) | Classes de production de dev3 exercées sur le jeu de recette : dépôt chiffré, empreintes = manifeste, relecture, stockage passé à `verifier-aucun-clair.sh`, altérations (octet, troncature, substitution, fin de grand fichier), type réel, tailles, EICAR et clamd arrêté (faux clamd des tests de dev3), rotation de KEK, destruction cryptographique — 19 contrôles |
| `autotest/lancer-autotest.sh` | Le scanner lui-même | 7 stockages simulés (conforme, clair déguisé, faux en-tête, arborescence, temporaire ancien, racine vide, cache en clair) |

Codes attendus alignés sur la livraison de dev3 (`ct/dev3`, classe `Refus`) et sur le DAT
§5.3.2 : 413 `FICHIER_TROP_VOLUMINEUX`, 415 `FORMAT_NON_AUTORISE`, 422 `FICHIER_INFECTE`,
503 `ANTIVIRUS_INDISPONIBLE`, 500 `INTEGRITE_COMPROMISE` (lecture d'un fichier altéré).

## Variables propres à E5

| Variable | Usage |
|---|---|
| `GED_TYPE_DOCUMENT_PDF_ID` | type n'acceptant que le PDF (type réel, altération) |
| `GED_TYPE_DOCUMENT_TEXTE_ID` | type acceptant le texte brut (antivirus : l'EICAR est un fichier texte) |
| `GED_TYPE_DOCUMENT_PETIT_ID`, `GED_TYPE_DOCUMENT_PETIT_MAX_OCTETS` | type à limite réduite (413 par type, borne incluse) |
| `GED_TYPE_DOCUMENT_200_ID` | type paramétré à 200 Mo |
| `GED_API_VERIF_INTEGRITE`, `GED_API_AUDIT` | vérification à la demande (gabarit `{id}`), consultation de l'audit (E4) |
| `GED_TEMOINS_SUPPLEMENTAIRES` | chaînes en clair supplémentaires à rechercher, séparées par des virgules |

## Résultats

Vague 1 (composants intégrés, `fbb951c`) : `verifier-composants.sh` 19/19 — voir
`docs/conformite/recette/RESULTATS-VAGUE-1.md`.

## État constaté avant intégration de E5 (application de `ct/qa`, 2026-09-26)

Exécution réelle contre l'application actuelle démarrée sur le port 18084 : le stockage
est en clair (S02 à S06 en ECHEC), un fichier altéré, tronqué ou substitué est servi tel
quel (A03 à A06 en ECHEC), un texte, un exécutable, un DOCX ou un PNG nommés `.pdf` sont
acceptés (R02 à R05), l'EICAR déguisé est accepté (V03), la limite par type répond 400 au
lieu de 413 (T02). Ces échecs sont ceux attendus tant que E5 n'est pas intégré ; ils
prouvent que les scripts savent détecter chaque défaut. Ce ne sont pas des anomalies.

## Limites sur ce poste

ClamAV réel, NGINX (413 de `client_max_body_size`) et LibreOffice (prévisualisation
bureautique) sont absents : V02/V04 ne sont exécutables qu'avec un clamd (réel en UAT, ou
le faux clamd des tests de dev3) ; T01 derrière NGINX n'est vérifiable qu'en UAT.
