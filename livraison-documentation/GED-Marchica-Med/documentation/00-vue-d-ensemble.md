# 00 — Le processus global

À lire en premier. Cette fiche pose le raisonnement d'ensemble et les notions
communes ; les fiches d'écran s'y réfèrent sans les redire.

---

## 1. Le problème que l'application résout

Une organisation reçoit et produit des documents. Trois difficultés reviennent
toujours :

1. **Les retrouver.** Un fichier nommé `scan0012.pdf` dans un dossier partagé est
   perdu dès le lendemain.
2. **Les valider.** Une facture doit être visée par plusieurs personnes, dans un
   ordre précis, et il faut pouvoir prouver qui a visé quoi.
3. **Les classer sans y penser.** Si le classement dépend de la discipline de
   chacun, il n'a pas lieu.

La GED répond aux trois par un même principe : **le document ne décide de rien,
c'est son type qui décide.** Au dépôt, on ne demande pas à l'opérateur où ranger
le fichier, comment le nommer ni qui doit le signer. On lui demande **de quel
type de document il s'agit**. Tout le reste en découle.

## 2. La chaîne de paramétrage

C'est le raisonnement central du produit. Il se lit de bas en haut : chaque
niveau existe pour servir le suivant.

```
  INDEX                  « Numéro de facture », « Date d'émission », « Fournisseur »
    │                    les informations élémentaires qui décrivent un document
    ▼
  PLAN D'INDEXATION      assemble des index et définit la CHARTE DE NOMMAGE
    │                    → 2026003_2026-02-02_acme
    ▼
  TYPE DE DOCUMENT       « Facture » : quel plan, quel espace de destination,
    │                    quels formats de fichier, quelle taille maximale
    ▼
  ESPACE DE TRAVAIL      le dossier de classement — et le CIRCUIT DE VALIDATION
    ▲                    qui s'appliquera aux documents déposés dedans
    │
  RÈGLE DE WORKFLOW      la suite ordonnée d'étapes et d'approbateurs
```

**Conséquence pratique :** on ne peut pas déposer un document utile dans une
application vide. Il faut d'abord créer au moins un index, un plan, un type, une
règle et un espace. C'est pour cela que l'ordre de lecture des fiches suit cette
chaîne, et non l'ordre du menu.

## 3. Le cycle de vie d'un document

```
   DÉPÔT ──► INDEXATION ──► CIRCUIT DE VALIDATION ──► FONDS DOCUMENTAIRE
     │           │                    │                        │
     │           │                    │                        ├─► consultation
     │           │                    │                        ├─► téléchargement
     │           │                    │                        ├─► nouvelle version
     │           │                    │                        └─► corbeille
     │           │                    │
     │           │                    ├── signée ──► étape suivante
     │           │                    └── refusée ─► circuit ARRÊTÉ
     │           │                                        │
     │           │                                   relance possible
     │           │
     │           └── les valeurs indexées composent le NOM du document
     │
     └── le TYPE choisi détermine espace, plan, formats, taille, circuit
```

Trois moments comptent, et ils sont traités par trois écrans différents :

| Moment | Écran | Fiche |
|---|---|---|
| Faire entrer le document | Dépôt | [11](11-depot-et-indexation.md) |
| Le faire valider | Mes workflow | [12](12-mes-workflow.md) |
| Le faire vivre | Documents | [10](10-documents.md) |

## 4. Les acteurs — et pourquoi il n'y en a qu'un

**Le cahier des charges impose un utilisateur unique : l'administrateur.**

C'est une décision, pas une simplification technique. Elle a une conséquence qu'il
faut avoir en tête sur tous les écrans :

| | L'administrateur | Les employés |
|---|---|---|
| Se connecte | oui | **non** |
| Nombre | un seul | autant que voulu |
| Nature | un compte | **une donnée** |
| Apparaît comme | l'opérateur | propriétaire d'un espace, approbateur d'une étape, membre d'un groupe |

Un employé n'est donc pas un utilisateur en attente d'un mot de passe : c'est une
information, au même titre qu'un nom de dossier. Quand un circuit de validation
désigne « Sara Bennani » comme approbatrice de l'étape 2, cela veut dire que
l'étape porte ce nom-là — pas que Sara se connectera pour la signer.

**Corollaire important :** les « groupes d'accès » (écran 05) **n'accordent aucun
droit et n'en retirent aucun**. Ils rattachent des employés à des espaces, à
titre d'organisation. Le nom vient du cahier des charges et il est trompeur.

## 5. Deux notions à ne jamais confondre

### Le statut et la corbeille

Ces deux mécanismes coexistent, notamment sur les espaces de travail, et ils ne
disent pas la même chose :

| | **Statut** | **Corbeille** |
|---|---|---|
| Question posée | l'objet est-il en service ? | l'objet existe-t-il encore ? |
| Valeurs | Actif / Inactif / Archivé | présent / supprimé |
| Geste | modifier la fiche | « Supprimer » / « Restaurer » |

Un espace peut être **archivé sans être supprimé**, et **supprimé alors qu'il
était actif**. Les deux états sont indépendants.

### Les index et les étiquettes

| | **Index** | **Étiquette** |
|---|---|---|
| Porte | une **valeur** (« 2026003 ») | rien, elle est posée ou non |
| Vient de | le plan d'indexation du type | un choix libre au dépôt |
| Sert à | décrire, nommer, rechercher | classer transversalement |
| Structure | imposée par le paramétrage | libre |

## 6. Le principe de la corbeille

**Aucune suppression n'est définitive.** Sur les huit écrans de liste, le même
schéma s'applique :

```
   VUE « ACTIFS »                        VUE « ARCHIVE »
        │                                      │
   « Supprimer » ──────────────────────────►  la ligne apparaît ici
   (confirmation qui NOMME l'objet)            │
        ◄───────────────────────────────── « Restaurer »
                                          (aucune confirmation)
```

Deux asymétries voulues :

- **Supprimer se confirme**, et la confirmation nomme l'objet : « « Facture
  janvier » sera déplacé vers la corbeille. » Une confirmation anonyme
  (« Confirmez-vous ? ») ne protège de rien, puisqu'elle ne permet pas de voir
  qu'on s'est trompé de ligne.
- **Restaurer ne se confirme pas.** Le geste ne détruit rien ; demander une
  confirmation ajouterait une friction sans bénéfice.

**Ce que la corbeille ne fait pas :** le fichier physique n'est **jamais** effacé
du disque, même après suppression. C'est délibéré — la restauration doit pouvoir
rendre le document, pas une coquille vide. La contrepartie est que le stockage
ne fait que croître et demande une purge décidée à la main.

## 7. La règle des messages

Le produit s'astreint à trois principes :

1. **Nommer l'objet.** « Supprimer cette étiquette », pas « Supprimer
   l'élément ».
2. **Dire ce qui va se passer, en une phrase.** « « Urgent » sera déplacée vers
   la corbeille. » — pas un paragraphe d'avertissement.
3. **Ne jamais afficher un message technique.** Toute erreur non interprétée
   retombe sur « Une erreur est survenue. »

Les unités varient d'un écran à l'autre et c'est voulu : on supprime
« 2 étiquette(s) », « 2 index », « 2 plan(s) », « 2 élément(s) » pour les
workflows. Le mot suit l'objet.

## 8. Le mode démonstration

L'application sait fonctionner **sans serveur**, en répondant à sa propre place à
partir de captures des vraies réponses de l'API. C'est ce qui permet de la
montrer à un client sur un poste nu.

Deux choses à savoir :

- **Les écritures ne sont pas conservées** dans ce mode : le jeu de démonstration
  est intact au rechargement. C'est voulu — laisser croire à un enregistrement
  durable serait plus trompeur qu'un effet visiblement éphémère.
- **Le basculement peut être accidentel.** Si le fichier de configuration est
  absent ou illisible, l'application passe **silencieusement** en démonstration
  et n'appelle plus le serveur. Personne n'est averti. Après un déploiement, il
  faut ouvrir l'application et vérifier qu'un document **réel** s'affiche.

## 9. Ce que le produit ne fait pas

À connaître avant de promettre quoi que ce soit :

- La déconnexion **ne révoque pas** le jeton de session : il reste valide
  jusqu'à son expiration.
- Le compteur de tentatives de connexion **n'est jamais purgé**.
- La reconnaissance de caractères (OCR) **n'a jamais été validée de bout en
  bout** sur un poste réel.
- Le stockage **n'est jamais purgé** automatiquement.
