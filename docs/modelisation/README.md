# Livrables de modélisation (P-05, DAT §4.5, §12.1)

| Livrable | Fichier | Source | Régénération |
|---|---|---|---|
| Schéma de base détaillé (tables, colonnes, types, contraintes, index, volumétrie à 5 ans d'après §6.6) | `SCHEMA-BASE.md` | base créée par Liquibase (catalogues PostgreSQL) | `node outils/schema-base.mjs --base <base migrée>` |
| Diagrammes entité-association par groupe du §12.1 (Mermaid) | `SCHEMA-BASE.md` | idem | idem |
| Diagrammes de classes par module (Mermaid) | `CLASSES.md` | `backend/src/main/java` | `node outils/diagrammes-classes.mjs` |
| Diagrammes de séquence des flux principaux | `SEQUENCES.md` | rédigés, revus à chaque changement de flux | — |

Régénérer le schéma : lancer d'abord `mvn test` (la base `<DB_NAME>_test` est
recréée par Liquibase, `drop-first`) ou démarrer l'application sur une base de
développement, puis :

```bash
node outils/schema-base.mjs --base ged_dev2_test
node outils/diagrammes-classes.mjs
```

Options du script de schéma : `--schema ged`, `--registre ged_liquibase`,
`--psql <chemin de psql>`, `--utilisateur postgres`, `--hote`, `--port`. Il
n'exécute que des lectures de catalogue. Les diagrammes Mermaid s'affichent
dans la forge (GitHub, GitLab, Gitea) et dans les éditeurs courants.
