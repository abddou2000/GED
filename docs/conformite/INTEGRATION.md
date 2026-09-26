# Procédure d'intégration

Seul **pm** fusionne dans `conformite-technique`, depuis sa copie `C:\Users\abdou\ged-app`.
Les branches `ct/dev1`, `ct/dev2`, `ct/dev3`, `ct/qa` sont des branches locales du même dépôt
(copies de travail `git worktree`) : pm les fusionne directement, sans `fetch` ni `push`.
Aucun `git push`, aucune réécriture d'historique partagé, `main` n'est jamais touchée.

## 1. Quand fusionner

| Type | Moment | Contenu |
|---|---|---|
| Fusion d'amorce | J+2 ou J+3 de la vague, sur demande du propriétaire | Uniquement le contrat : interfaces, énumérations, changesets de tables, implémentation par défaut, tests du contrat. Voir `PLAN-VAGUES.md`. |
| Fusion de fin de vague | Quand le membre déclare son lot terminé dans son `docs/conformite/suivi/<nom>.md` et que `mvn -q test` est vert sur sa branche | Le lot complet. |
| Fusion de correction | En vague 6, au fil de la recette | Un correctif à la fois. |

pm ne fusionne pas une branche dont le dernier commit n'est pas propre (`git -C <copie> status`
doit être vide) : on fusionne ce qui est commité, rien d'autre.

## 2. Ordre de fusion par vague

| Vague | Amorce | Ordre de fin de vague | Raison |
|---|---|---|---|
| 1 | — | ct/dev2 → ct/dev1 → ct/dev3 → ct/qa | L'outillage (CI, OWASP, SBOM) valide les fusions suivantes ; le socle (UUID, PostgreSQL) passe avant les composants qui compilent contre lui. |
| 2 | ct/dev2 (J+2) : `ExceptionMetier`, problem+json, `AuditService`, codes d'événements | ct/dev1 → ct/dev2 → ct/dev3 → ct/qa | L'identité change le helper de test `Comptes` utilisé par tous les tests. |
| 3 | ct/dev1 (J+3) : `AccessPredicate`, `ControleAcces`, `Sujet`, `SourceHabilitations` | ct/dev1 → ct/dev3 → ct/dev2 → ct/qa | Les tests de filtrage de la recherche exigent le vrai moteur d'autorisation. |
| 4 | ct/dev1 (J+2) : colonnes de cycle de vie, `GardeEcriture` | ct/dev1 → ct/dev3 → ct/dev2 → ct/qa | Le cycle de vie s'appuie sur le modèle de versions et le verrou. |
| 5 | ct/dev3 (J+2) : `NotificationService` | ct/dev3 → ct/dev1 → ct/dev2 → ct/qa | Le workflow appelle les notifications. |

Après **chaque** fusion (y compris d'amorce), pm demande aux autres membres de se
resynchroniser (section 6) avant la fusion suivante de la même vague lorsque celle-ci en dépend.

## 3. Fusion pas à pas

Depuis `C:\Users\abdou\ged-app`, branche `conformite-technique` :

```bash
git status --short                               # doit être vide (hors fichiers non suivis connus)
git log --oneline conformite-technique..ct/dev1  # ce qui va entrer
git diff --stat conformite-technique...ct/dev1   # fichiers touchés : contrôler le périmètre du lot
git merge --no-ff --no-commit ct/dev1            # fusion SANS commit : on vérifie d'abord
```

1. Résoudre les conflits selon la section 4.
2. Lancer les vérifications de la section 5. Tant qu'une vérification échoue, **ne pas
   committer** : `git merge --abort`, et renvoyer le lot au membre avec le message d'erreur.
   Ainsi aucun commit défectueux n'entre dans l'historique partagé.
3. Committer la fusion :

```bash
git commit -m "Fusionner ct/dev1 : E1 socle de données (vague 1)" \
           -m "Exigences : T-004, T-005, T-019 à T-025, T-046, T-099, T-104." \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

4. Mettre à jour `SUIVI.md` (statut « Livré » des lignes fusionnées, tableau de bord), commit
   séparé.
5. En fin de vague, poser une étiquette locale : `git tag vague-1` (jamais poussée).

Si un défaut n'apparaît qu'après le commit de fusion et que des membres ont déjà fusionné
`conformite-technique` : on **ne réécrit pas** l'historique ; on corrige par un nouveau commit du
membre concerné, ou `git revert -m 1 <fusion>` en dernier recours.

## 4. Gestion des conflits

### Règles générales

- pm résout les conflits **mécaniques** (union de listes, de clés, d'imports). Un conflit de
  **logique** (deux implémentations d'un même comportement) n'est jamais tranché par pm seul :
  fusion annulée, le propriétaire du fichier pour la vague reprend en ayant fusionné
  `conformite-technique`.
- Propriétaire du fichier pour la vague = sa version fait foi ; on y réapplique les ajouts
  des autres. Les propriétaires sont listés dans `PLAN-VAGUES.md`.
- Après résolution : `git diff --check` et aucune marque de conflit restante
  (`git grep -n -E "^(<<<<<<<|>>>>>>>|=======)$"`).

### `backend/pom.xml`

- **Union** des dépendances et des plugins ; une seule déclaration par `groupId:artifactId`.
- Versions : garder la version gérée par le parent Spring Boot quand elle existe (ne pas
  déclarer de version) ; sinon la plus récente des deux, sauf commentaire justifiant un
  épinglage.
- Propriétés (`<properties>`) : union, ordre alphabétique.
- Dépendances retirées par un lot (MySQL, H2, Flyway par dev1 en vague 1) : ne pas les
  réintroduire par une résolution d'union.
- Contrôle : `mvn -q -DskipTests validate` puis `mvn -q dependency:tree` sans avertissement de
  doublon ; le SBOM et le rapport OWASP (après E0) ne doivent pas faire apparaître de licence
  refusée (P-19).

### `application.yml`, `application-{dev,test,uat,prod}.yml`, `backend/.env.example`

- Un sous-arbre de configuration par membre :

| Membre | Sous-arbres |
|---|---|
| dev1 | `spring.datasource`, `spring.jpa`, `spring.liquibase`, `ged.identite`, `ged.autorisation`, `ged.securite` |
| dev2 | `logging`, `management`, `ged.audit`, `ged.api`, `ged.exploitation` |
| dev3 | `spring.servlet.multipart`, `ged.stockage`, `ged.antivirus`, `ged.previsualisation`, `ged.ocr`, `ged.recherche`, `ged.conservation`, `ged.notification` |

- Résolution = union des sous-arbres ; **jamais deux fois la même clé** (SnakeYAML refuse les
  clés dupliquées et l'application ne démarre plus : `mvn test` le révèle).
- Valeurs : aucun secret ; toute nouvelle variable d'environnement est documentée dans
  `backend/.env.example` (union, regroupée par préfixe).
- Le plafond de téléversement doit rester cohérent entre `spring.servlet.multipart` (dev3) et
  `client_max_body_size` de NGINX (dev2) : 200 Mo.

### `common/GlobalExceptionHandler.java`

- Vague 1 : conflits possibles entre dev1 (types d'identifiants) et dev3 (exceptions de
  fichier) ; garder la structure la plus récente et réappliquer les gestionnaires de l'autre.
- **À partir de l'amorce de vague 2, dev2 est seul propriétaire.** dev1 et dev3 ne créent plus
  de gestionnaire : ils lèvent une sous-classe d'`ExceptionMetier` (statut HTTP + code métier
  stable) que le gestionnaire traduit en problem+json. Un conflit sur ce fichier après la
  vague 2 signale une entorse à la règle : fusion annulée.
- Contrôle : les tests de format d'erreur (dictionnaire par champ pour les 400, code métier,
  `application/problem+json`) restent verts.

### `config/SecurityConfig.java`

- Propriétaire : dev1 en vagues 1 et 2, dev2 en vague 3 (filtre `X-API-Key`), dev1 ensuite.
- Les autres membres n'y touchent pas : filtre MDC enregistré hors de la chaîne de sécurité,
  sécurité de méthode dans une configuration distincte.

### Changelog Liquibase

- Un fichier par évolution `db/changelog/changesets/AAAAMMJJHHmm_objet_metier.xml`, attribut
  `author` = nom du membre, identifiant de changeset unique.
- Changelog maître : conflit d'**union** des lignes `include`, remises dans l'ordre des
  horodatages des noms de fichier.
- **Un changeset déjà fusionné n'est jamais modifié** (somme de contrôle) : toute correction
  est un nouveau changeset. pm vérifie par `git diff --stat` qu'aucun fichier de changeset
  existant n'est modifié.
- Deux changesets de membres différents sur la même table dans la même vague : vérifier l'ordre
  d'application sur base vierge (section 5).

### Fichiers Angular partagés

- `app.routes.ts`, liste des menus dans `layout`, `core/api.ts` : union des entrées ; le
  propriétaire de la vague (dev1 pour `layout` et `core/auth*`, dev2 pour `core/api.ts`) fait
  foi en cas de logique divergente.
- `frontend/node_modules` ne doit jamais apparaître dans un commit (jonction locale des copies
  de travail) : contrôle `git diff --stat` avant commit.

### Fichiers de suivi

- `docs/conformite/suivi/<nom>.md` : un fichier par membre, pas de conflit attendu.
- `docs/conformite/SUIVI.md` : pm seul.

## 5. Vérifications obligatoires après chaque fusion (avant le commit)

Base de pm : `ged_pm_test` (tests), `ged_pm_rb` (rollback), sur `localhost:5432`, utilisateur
`postgres`. L'URL passe par la variable d'environnement du profil `test` (nom fixé par dev1 en
E1, documenté dans `backend/.env.example`).

| # | Vérification | Commande | Critère |
|---|---|---|---|
| V1 | Marques de conflit et espaces | `git diff --check` ; `git grep -n -E "^(<<<<<<<\|>>>>>>>)"` | Aucune sortie |
| V2 | Tests back-end | `cd backend && mvn -q test` | Vert. Nombre de tests (rapports `target/surefire-reports`) supérieur ou égal à la somme attendue ; aucun test désactivé ou supprimé sans justification dans le suivi du membre. |
| V3 | Base vierge par Liquibase | Supprimer puis recréer `ged_pm_test` avant V2 | Schéma créé uniquement par le changelog ; `ddl-auto: validate` passe. |
| V4 | Retour arrière | `updateTestingRollback` sur `ged_pm_rb` (commande fournie par dev1 en E1) | Aucun échec (à partir de la fin de vague 1). |
| V5 | Build Angular | `cd frontend && npx ng build` | Succès, sans nouvelle erreur de compilation. |
| V6 | Contrôles de dépendances | Profil ou commande fournis par dev2 en E0 (OWASP Dependency-Check, SBOM CycloneDX Maven et npm) | Aucune vulnérabilité au-dessus du seuil ; licences acceptées (à partir de la fin de vague 1). |
| V7 | Secrets | `git diff conformite-technique...ct/<membre>` relu ; recherche de motifs (`-----BEGIN`, `password:`, `secret`, `.p12`, `.jks`) | Aucun secret, aucun keystore, aucune clé privée. |
| V8 | Périmètre | `git diff --stat` | Seuls les fichiers attendus pour le lot ; pas de `target/`, `storage/`, `node_modules`, journaux de démarrage. |
| V9 | Démarrage (si le lot touche la configuration) | Démarrer sur le port **18085** (profil `dev`), appeler `/actuator/health`, arrêter le processus | UP. Jamais les ports 8080 ni 4301. |

Après le commit : `SUIVI.md` mis à jour, message aux membres (section 6), et à qa la liste des
lignes passées à « Livré » pour recette.

## 6. Resynchronisation des membres

Quand pm l'annonce (après chaque fusion dont le membre dépend, et au plus tard en début de
vague), chaque membre, **dans sa propre copie de travail et sur sa propre branche** :

```bash
git status --short                 # tout committer d'abord (petit commit de travail si besoin)
git merge conformite-technique     # jamais de rebase, jamais de pull, jamais de checkout d'une autre branche
# résoudre les conflits dans sa copie, avec les mêmes règles que la section 4
mvn -q test                        # dans backend/, sur ses propres bases (ged_<nom>_test)
cd ../frontend && npx ng build     # si le front a changé (jonction node_modules en place)
git commit                         # si la fusion a demandé une résolution ; message terminé par la ligne d'attribution
```

- Après une fusion qui modifie le schéma, supprimer et recréer ses bases de test (les
  changesets ont pu être réordonnés).
- Rôles PostgreSQL : ne jamais les supprimer ; le script de rôles est idempotent (voir
  `RISQUES.md`, R03).
- Le membre note la resynchronisation dans son `docs/conformite/suivi/<nom>.md`.
- Un membre qui ne peut pas résoudre un conflit s'arrête et le signale à pm, sans forcer.
