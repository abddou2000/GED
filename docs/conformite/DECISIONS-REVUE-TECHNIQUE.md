# Décisions de la revue technique approfondie (réunion client)

Source : `C:\Users\abdou\Downloads\Revue technique approfondie Validation des spécifications, a-notes.docx`
(compte rendu de réunion de validation des spécifications avec Marchica Med).

**Règle de priorité** : une décision **actée** ci-dessous prime sur le dossier technique V3.
Une décision **tentative** ou une **question ouverte** ne change rien tant qu'elle n'est pas
confirmée : on garde le mécanisme du dossier V3.

## 1. Décisions actées qui modifient le dossier V3

| # | Sujet | Dossier V3 | Décision de la revue | Impact | Lot |
|---|---|---|---|---|---|
| D1 | Désactivation d'un compte AD | Relire `userAccountControl` à chaque renouvellement et toutes les 5 min | **Ne pas lire l'attribut d'activation.** Un compte désactivé échoue à l'authentification, cela suffit. Départ ou mutation : l'AD gère, la GED ne gère que ses habilitations. | Supprimer la relecture de `userAccountControl` et la tâche des 5 minutes. Voir le risque R1. | E2 (dev1) |
| D2 | Identifiant de connexion | `sAMAccountName` ou `userPrincipalName` | **UID de l'AD obligatoire, jamais l'adresse e-mail.** L'identifiant unique AD (objectGUID) reste nécessaire. | Connexion par `sAMAccountName` uniquement ; clé technique objectGUID. | E2 (dev1) |
| D3 | Attributs lus dans l'AD | Liste du §3.3 | Retirer deux attributs (non nommés clairement au compte rendu), voire tous ceux de ce type, à réintroduire seulement sur besoin. | Lire le **strict minimum** (identifiant, objectGUID, nom, prénom, courriel pour les notifications). Voir Q3. | E2 (dev1) |
| D4 | Résilience AD | Au moins deux contrôleurs, bascule | Un seul contrôleur existe aujourd'hui, un second est prévu. | Configuration à N contrôleurs, fonctionne avec un seul. | E2 (dev1) |
| D5 | OCR | Tesseract via Tess4J ou binaire | **Aucun Python**, solution Java uniquement ; tests « TC RAC / Syntec » en Java seul, sans service supplémentaire. | Conserver l'appel du binaire Tesseract depuis Java (déjà le cas). Aucun script Python. | E6 (dev3) |
| D6 | Délai dépôt → disponibilité en recherche | 5 min (20 pages, file vide) | **24 heures maximum.** Cas des attachements de 400 à 800 pages. | Objectif de supervision et seuil d'alerte à 24 h ; la file OCR reste asynchrone. | E6 (dev3), E10 (dev2) |
| D7 | Workflow | Sans ordre | **Parallèle confirmé** : tous les validateurs reçoivent la demande en même temps, aucun validateur optionnel. Logique entièrement côté back, le front ne fait qu'afficher. | Confirme le lot E8. | E8 |
| D8 | Workflow via API | Non prévu | **Nouvelle exigence** : le workflow doit être pilotable nominativement par API (intranet) : désigner les validateurs, gérer les circuits, et permettre de valider depuis une application tierce. | Nouveaux points d'entrée d'API workflow, soumis aux clés API et à la délégation d'identité. | E8 + E9 |
| D9 | Versions | Version courante désignable | Une nouvelle version **archive automatiquement l'ancienne** ; l'utilisateur lie explicitement la nouvelle version à l'ancienne. | À l'ajout d'une version, l'ancienne passe au statut archivé (lecture seule). Voir Q4 pour le cas « désigner une ancienne version comme courante ». | E7 |
| D10 | Archivage | Statut par document, job par lot | **Action manuelle**, jamais automatique ; applicable à **un dossier entier** en une fois ; **drapeau** sur documents **ou dossiers** ; les fichiers Word restent archivables ; conversion PDF obligatoire à l'archivage. | Drapeau d'archivage aussi sur les nœuds (dossiers) ; archivage d'un dossier = traitement de fond sur ses documents ; copie PDF/A maintenue. | E7 (dev3) |
| D11 | Journaux | INSERT seul, déclencheurs, scellement chaîné exporté | Protéger contre toute modification ou suppression **y compris par l'administrateur via l'interface** ; l'accès direct à la base n'est pas couvert. Actions auditées du §4.9.4 validées. | Minimum exigé : aucune fonction de modification ou suppression du journal dans l'application, droits INSERT/SELECT seuls. Le scellement reste au dossier V3 : on le conserve (coût modéré, preuve renforcée). | E4 (dev2) |
| D12 | Collaboration « marchés » (CPS) | — | Pas de co-édition. **Espace de partage simple** : déposer, télécharger, modifier en local, téléverser une nouvelle version. | Correspond aux espaces d'échange du dossier fonctionnel ; aucune édition en ligne. | E7 / E3 |
| D13 | Validateur restreint à un type de document | — | Jugé **trop détaillé**, hors périmètre du marché. | Ne pas implémenter. | — |
| D14 | Habilitations | Proposition « à valider » | Héritage, rupture d'héritage, document isolé et liste des permissions **validés**. Corbeille pour toute suppression confirmée. | Confirme E3. | E3 (dev1) |

## 2. Décisions tentatives (ne rien changer pour l'instant)

- **T1** — Durées de conservation du §4.7.4 : en attente de validation par le maître d'ouvrage.
  On implémente le mécanisme paramétrable ; les valeurs restent des paramètres.
- **T2** — Données d'annuaire : cache ou base synchronisée, au choix de l'équipe. **Choix de
  l'équipe : on garde le cache `cache_annuaire` du dossier V3** (100 à 150 utilisateurs, aucune
  synchronisation à maintenir, pas de désynchronisation possible avec la logique applicative).

## 3. Questions ouvertes à faire trancher par Marchica Med

- **Q1 — Circuit figé ou non.** Le dossier V3 (p. 17 et §12.8) fige le circuit au dépôt. La
  réunion décrit l'inverse pour un changement d'organisation : « le nouveau validateur remplace
  l'ancien pour les étapes restantes ». Proposition de l'équipe : circuit figé (valeur probante),
  plus une **réaffectation explicite** par l'Administrateur d'un validateur en attente, tracée
  dans l'audit. À valider.
- **Q2 — Stockage des jetons.** Le compte rendu cite « jeton dans le storage local, refresh token
  en mémoire ou cookie ». Le dossier V3 retient l'inverse, plus sûr : jeton d'accès en mémoire,
  jeton de renouvellement en cookie httpOnly dont l'empreinte est vérifiée en base. L'équipe garde
  le mécanisme du dossier V3, qui satisfait l'exigence « jeton + refresh token haché ».
- **Q3 — Attributs AD retirés.** Quels sont exactement les deux attributs à retirer ?
- **Q4 — Verrouillage et versions.** La réunion définit le verrou comme l'immuabilité du fichier
  source, la modification ne portant que sur les métadonnées. Le dossier V3 fait du verrou un gel
  complet (fiche, versions, réindexation). L'équipe garde le gel complet du dossier V3 (tout
  fichier est de toute façon immuable, une nouvelle version crée un nouveau fichier). À confirmer.
- **Q5 — Mention Python.** L'action « retirer Python de la documentation » concerne le dossier
  fonctionnel (p. 11, « s'intègre en trois lignes de code en Python ») : à corriger par l'auteur
  du dossier.

## 4. Risques introduits

- **R1 (D1)** — Sans relecture de l'état AD, un utilisateur désactivé garde l'accès **jusqu'à
  l'expiration de son jeton de renouvellement** (8 h maximum au dossier V3), car le
  renouvellement ne repasse pas par l'annuaire. Mitigations proposées : durée absolue de session
  réduite (par exemple 4 h), et révocation manuelle des sessions d'un utilisateur par
  l'Administrateur (déjà prévue par la table `session`). À présenter au client.
