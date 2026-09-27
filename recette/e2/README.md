# Recette E2 — identité et sessions

`verifier-identite.sh --url URL [--base BASE] [--front frontend/src]` : 33 contrôles de bout en
bout (bash + curl + psql + openssl ; aucun Python, D5). Comptes désignés par variables
(`GED_E2_ADMIN`, `GED_E2_STANDARD`, `GED_E2_NOUVEAU` jamais connecté, `GED_E2_SANS_ROLE`,
`GED_E2_DESACTIVE`, `GED_RECETTE_MOT_DE_PASSE`) : comptes de test de l'AD en UAT, comptes du
simulateur en DEV. Durée 4 à 5 min : la limitation de débit (5 connexions/min/IP) impose d'espacer
les connexions ; le script attend le `Retry-After`.

- `controles-identite.sql` : aucun mot de passe (colonnes, tables, **valeurs** au format
  BCrypt/Argon2/PBKDF2 dans toute la base), objectGUID unique, `session` sans jeton en clair,
  `cache_annuaire`, aucun attribut d'état ni d'appartenance AD (D1, D3).
- Annuaire simulé en DEV : le LDIF de démonstration du backend concaténé à
  `recette/donnees/annuaire-recette.ldif` (comptes `qanouveau1..3` jamais connectés ; `qanouveau1`
  porte `memberOf: GED-Administrateurs` pour prouver qu'aucun droit n'est déduit de l'AD), passé à
  l'application par `--ged.identite.annuaire.embarque.ldif=file:…`.
- E2-25/E2-26 se présentent comme des clients derrière NGINX (`X-Forwarded-For` depuis localhost,
  proxy de confiance par défaut) ; `GED_E2_TESTER_PROXY=0` pour les sauter.

Résultats : `docs/conformite/recette/RESULTATS-VAGUE-2.md`.
