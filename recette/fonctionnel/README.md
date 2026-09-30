# Recette fonctionnelle — Dossier d'analyse des besoins fonctionnels V3

Tenue par qa2. Résultats : `docs/conformite/recette/RESULTATS-FONCTIONNELS.md` ; anomalies :
`docs/conformite/recette/ANOMALIES-FONCTIONNELLES.md`.

`bash recette/fonctionnel/lancer.sh [A] [B] [C] [D]` compile `RecetteFonctionnelle.java` (et les
autres `.java` du dossier) avec `recette/lib/ClientGed.java` et le classpath du backend, puis
rejoue les scénarios. Sortie `RESULTAT|F-xx|OK/ECHEC/AVERT/NA|libellé|détail`, puis `BILAN|…`.

| Partie | Exigences |
|---|---|
| A | F-01 à F-20 : principes, habilitations, identités, clés d'API, réception |
| B | F-21 à F-38 : OCR, métadonnées, classement, arborescence |
| C | F-39 à F-54 : recherche, confidentialité, circuits de validation, diffusion |
| D | F-55 à F-77 : cycle de vie, archivage, audit, intégration, sécurité |

Personas (annuaire **simulé** du profil dev + `annuaire-fonctionnel.ldif`, à concaténer au LDIF
de démonstration et à désigner par `GED_IDENTITE_ANNUAIRE_EMBARQUE_LDIF=file:…`) :

| Compte | Persona (§3.2) | Habilitations posées par le script |
|---|---|---|
| sbennani | Administrateur | amorçage |
| qa2agent | Agent d'archive | QA2 Finance, QA2 RH, QA2 Échange |
| qa2dg | Direction Générale | portée globale |
| qa2val1, qa2val2 | Utilisateurs standard, validateurs | QA2 Finance (qa2val1 : lecteur, avec rupture, sur « Réservé direction ») |
| qa2rh | « Responsable RH », rôle composé | QA2 RH |
| yalaoui | Utilisateur standard cantonné à un dossier | QA2 Finance / Exercice 2026 |
| nidrissi | Utilisateur standard d'un autre périmètre | QA2 Projets |
| kelfassi | Membre du groupe « QA2 Auditeurs internes » | par le groupe : lecteur sur QA2 Finance |
| qa2neuf1, qa2neuf2 | Identités AD jamais connectées | F-11, F-14 |
| qa2parti | Agent désactivé dans l'AD en cours de circuit | F-12 (désactivation par `LdapSimule`) |

Instance de recette : profil dev, API 18088, management 18098, annuaire simulé 33399, SMTP simulé
3039 (`recette/lib/SmtpSimule.java`), LibreOffice simulé (`FauxSoffice`), bureau d'ordre reconnu
par `GED_DEPOT_APPLICATIONS_BUREAU_ORDRE=bo`. Le script est rejouable : le jeu est retrouvé par
ses codes (`QA2-…`), les documents portent un marqueur d'exécution et ne sont jamais supprimés.
Aucun Python (décision D5).
