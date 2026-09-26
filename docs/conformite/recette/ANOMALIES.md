# Registre des anomalies de recette

Tenu par : qa. Une anomalie = un écart constaté entre le comportement livré et le dossier
technique V3 (le PDF fait foi) ou le critère de sortie d'une étape, **reproductible** par
les étapes indiquées. Les écarts déjà inventoriés dans `MATRICE-TECHNIQUE.md` avant
livraison ne sont pas recopiés ici : on n'enregistre que ce qu'une livraison n'a pas
tenu.

## Gravité

| Niveau | Définition |
|---|---|
| Bloquante | Le critère de sortie de l'étape ne peut pas être déclaré atteint (ex. fichier en clair sur le disque, objet hors périmètre visible). |
| Majeure | Une exigence de la matrice n'est pas tenue, mais le critère de sortie reste démontrable par ailleurs. |
| Mineure | Écart de forme (nommage, message, code d'erreur métier) sans effet sur la sécurité ni l'intégrité. |

## Statuts

`Ouverte` → `Corrigée` (le développeur indique le commit) → `Vérifiée` (qa a rejoué les
étapes et le script de recette concerné) ou `Rouverte`. `Rejetée` uniquement avec la
référence du PDF qui justifie le comportement.

## Registre

| Identifiant | Étape | Réf. matrice | Gravité | Description | Étapes de reproduction | Constaté sur (branche @ commit) | Statut |
|---|---|---|---|---|---|---|---|
| — | — | — | — | *Aucune anomalie enregistrée à ce jour.* | — | — | — |

Format de l'identifiant : `ANO-<étape>-<numéro à 3 chiffres>`, par exemple `ANO-E1-001`.
