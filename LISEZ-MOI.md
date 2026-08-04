# GED — archive du projet

Backend **Spring Boot 3.4.1 / Java 17** · Frontend **Angular 22**.

## Ce que l'archive ne contient pas

`node_modules`, `frontend/dist`, `frontend/.angular` et `backend/target` ont été retirés :
ce sont des dépendances téléchargées et des artefacts de compilation, reconstruits par
les commandes ci-dessous. Sans eux l'archive pèse moins d'1 Mo au lieu de 350.

Le code source est intégralement présent : 106 fichiers `.java`, 60 `.ts`, 30 `.scss`,
28 `.html`, plus l'historique git.

La base de démonstration H2 (`backend/data`) **est incluse**, refermée proprement avant
la copie. Pour repartir de zéro, supprimez ce dossier : les seeders reconstruisent tout
au démarrage suivant.

## Démarrer

Prérequis : JDK 17 et Node. Développé avec
`C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot` et Maven 3.9.9.

**Backend** — port 8080 :

```bash
cd backend
mvn spring-boot:run
```

**Frontend** — port 4301 :

```bash
cd frontend
npm install
npm start -- --port 4301
```

## Vérifier

```bash
cd backend && mvn test
```

88 tests, tous verts au moment de l'archivage.

## Points d'entrée

| Quoi | Où |
|---|---|
| Indexation automatique (analyse puis confirmation) | `backend/src/main/java/com/ipt/ged/indexation/` |
| Chaîne d'OCRisation (port + adaptateurs) | `backend/src/main/java/com/ipt/ged/ocr/` |
| Écran correspondant | `frontend/src/app/features/indexation/` |
| Charte de nommage (séparateur, ordre, casse) | `backend/.../planindexation/PlanIndexation.java` |
| Jetons de design et style des dialogues | `frontend/src/styles.scss` |
| Scénario de test pas à pas | `SCENARIO-TEST-INDEXATION.md` |

## L'OCRisation

L'architecture ne s'engage sur aucun moteur : `ExtracteurTexte` est un port, et les
extracteurs sont essayés par ordre de priorité.

| Étage | Moteur | État |
|---|---|---|
| 1 | PDFBox — couche texte des PDF natifs | actif, aucune installation requise |
| 2 | Tesseract — scans et images | **à valider** : le binaire doit être installé sur le serveur |

`GET /api/v1/ocr/diagnostic` indique quels extracteurs répondent.
L'étage 2 a été écrit et compilé mais n'a **pas pu être exécuté** sur le poste de
développement, Tesseract n'y étant pas installé.

Réglages dans `application.yml`, section `ged.ocr` : `enabled`, `commande`, `langue`,
`dpi`, `pages-max`.

## Point ouvert à connaître

**Sécurité — aucune.** `backend/src/main/java/com/ipt/ged/config/SecurityConfig.java`
est en `anyRequest().permitAll()` : toutes les routes de l'API sont ouvertes.
L'authentification et le contrôle d'accès fin restent à faire.
