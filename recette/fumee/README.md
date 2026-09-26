# Test de fumée HTTP

`fumee.sh` vérifie une instance déployée en 7 étapes : santé (`/actuator/health` UP,
attente jusqu'à `--delai-sante`), connexion, appel authentifié, dépôt d'un PDF sous un nom
unique, recherche de ce nom, téléchargement **identique à l'octet** (SHA-256 : prouve
que chiffrement et déchiffrement sont transparents), suppression douce (`--nettoyer`).
Arrêt à la première étape en échec, code de sortie 1 : le script de déploiement de dev2
enchaîne alors son retour arrière (DAT §10.1).

Dépendances : bash 4, curl, sha256sum. **Ni Python ni jq** (décision D5 de la revue
technique : aucun Python), pour tourner tel quel sur les serveurs Linux de MMED.

```
export GED_RECETTE_IDENTIFIANT=… GED_RECETTE_MOT_DE_PASSE=…    # compte de test dédié, depuis le coffre
recette/fumee/fumee.sh --url https://ged-uat.marchicamed.ma --nettoyer --junit fumee.xml
```

Appel depuis le script de déploiement : `recette/fumee/fumee.sh --url "$URL" --nettoyer || retour_arriere`.
Sortie ligne à ligne `RESULTAT|F0x|OK/ECHEC|…` puis `BILAN|…` ; `--junit` produit un
rapport lisible par l'intégration continue.

## Contrat d'API

Les chemins et champs sont ceux de l'API actuelle et se changent par variables
d'environnement sans toucher au script (liste dans `recette/lib/api.sh`). À mettre à jour
à la livraison de :

| Lot | Variable | Nouvelle valeur attendue |
|---|---|---|
| E2 (D2 : connexion par UID AD, jamais l'e-mail) | `GED_CHAMP_IDENTIFIANT` | champ de l'UID `sAMAccountName` (nom exact fixé par dev1) |
| E9 | `GED_API_TELECHARGEMENT` | `/api/v1/documents/{id}/contenu` |
| E9 | `GED_API_RECHERCHE`, `GED_API_RECHERCHE_CORPS` | `/api/v1/recherches` en POST |
| E9 | `GED_RECETTE_CLE_API`, `GED_IDEMPOTENCE=1` | fumée par clé API, en-tête `Idempotency-Key` |

## Vérifié sur ce poste

2026-09-26 : application de `ct/qa` (profil dev, H2 en mémoire) démarrée sur le port 18084 :
7/7 OK ; mot de passe faux → F02 ECHEC (401) ; port fermé → F01 ECHEC (connexion
refusée) ; chemin de contrat E9 `/contenu` → F06 ECHEC (404, attendu tant que E9 n'est
pas livré). Rien n'a été vérifié derrière NGINX ni en TLS (absents du poste).
