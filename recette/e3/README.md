# Recette E3 — autorisation et confidentialité

`verifier-autorisation.sh [--reinitialiser]` lance `RecetteAutorisation.java` (Java 17 + Jackson du
classpath du backend). Le script construit **son propre jeu** par l'API d'administration (trois
comptes, quatre habilitations, six témoins PUBLIC / PRIVE / CONFIDENTIEL marqués par exécution),
puis vérifie : liste, recherche, totaux, tableau de bord, recherche par index, arbre (nœuds de
passage), 404 indiscernable sur 14 routes (statut, type, corps et temps de réponse),
403 sur objet visible sans permission, désignation et retrait, rupture d'héritage, rattachement,
droits effectifs avec origine, administration réservée.

Variables : `GED_URL`, `GED_RECETTE_MOT_DE_PASSE`, `GED_E3_ADMIN`, `GED_E3_DEPOSANT`, `GED_E3_TIERS`,
`GED_E3_SANS_DROIT`, noms de nœuds `GED_E3_NOEUD_A`, `_A_ENFANT`, `_B`, `_B_ENFANT`, `_C` (défauts :
jeu de démonstration du profil dev). Le script refuse de conclure si les comptes de test portent
des habilitations hors jeu (`--reinitialiser` les retire). Il est rejouable : chaque exécution
dépose de nouveaux témoins (conservés, marqueur `QAE3…`).
