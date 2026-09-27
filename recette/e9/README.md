# Recette E9 — API d'intégration

`bash recette/lib/lancer-java.sh recette/e9/RecetteApi.java RecetteApi` (prérequis : jeu de la
recette E3). Le script crée ses applications et clés par l'API d'administration, puis vérifie :
format de clé et secret montré une seule fois ; dépôt, recherche et téléchargement par clé dans sa
portée, hors portée 404/403, opération non accordée 403, routes d'administration fermées ;
Idempotency-Key (rejeu sans doublon avec `Idempotency-Replayed`, 422 `IDEMPOTENCE_CONFLIT`, 400
sans clé) ; audit attribué à l'application ; quota 429 + `Retry-After` ; adresse non autorisée ;
autre environnement ; régénération avec chevauchement et révocation immédiate ; délégation
`X-On-Behalf-Of` (lecture en intersection, écriture au nom du délégué avec double identité au
journal, 422 `IDENTITE_DELEGUEE_INVALIDE`, 403 sans attribut) ; problem+json ; OpenAPI.
Les chemins du contrat §5.3.1 (T-042, P-06) sont signalés « à faire » tant qu'ils ne sont pas intégrés.
