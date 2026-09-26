# Recette de conformité technique — scripts indépendants du code applicatif

Plan : `docs/conformite/recette/PLAN-DE-RECETTE.md`. Anomalies : `docs/conformite/recette/ANOMALIES.md`.

| Dossier | Contenu |
|---|---|
| `lib/` | `commun.sh` (format de sortie, garde-fous), `api.sh` (client HTTP sans jq), `liquibase.sh` + `LiquibaseRecette.java` (Liquibase hors ligne) |
| `e1/` | Socle PostgreSQL / Liquibase / rôles — voir `e1/README.md` |
| `e5/` | Stockage chiffré, altération, antivirus, type réel, taille — voir `e5/README.md` |
| `fumee/` | Test de fumée HTTP réutilisable par le script de déploiement — voir `fumee/README.md` |
| `donnees/` | Jeux de données fictifs et leur générateur Java — voir `donnees/README.md` |

Conventions communes :

- Sortie `RESULTAT|identifiant|OK/ECHEC/AVERT/NA|libellé|détail`, puis `BILAN|…` ; code de sortie
  0 si aucun `ECHEC`, 1 sinon, 2 si le script n'a pas pu s'exécuter.
- **Aucun Python** (décision D5) : bash, coreutils, curl, psql sur les serveurs ; Java 17 en mode
  fichier source (`java -Dfile.encoding=UTF-8 X.java`) pour les outils de poste et d'intégration.
- Aucun secret dans les scripts : variables libpq (`PGPASSWORD`, `~/.pgpass`) et variables
  `GED_RECETTE_*` alimentées depuis le coffre.
- Les scripts destructifs refusent la production (`GED_ENV=prod`) et les bases qui ne sont pas
  jetables (`ged_qa*`, `*recette*`).
- Chaque contrôle a son **autotest** (`*/autotest/`) : un contrôle qui ne sait pas échouer ne
  prouve rien.
- Fins de ligne LF imposées par `.gitattributes` (les scripts tournent sous Linux en UAT).
