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
| D5 | OCR | Tesseract via Tess4J ou binaire, sans Python (§4.3.1) | **Aucun Python**, solution Java uniquement ; tests « TC RAC / Syntec » (« TC RAC » : transcription probable de « Tesseract ») en Java seul, sans service supplémentaire. | **Confirme le V3** : conserver l'appel du binaire Tesseract depuis Java (déjà le cas), aucun script Python. Conséquence : la comparaison du §4.3.2 avec PaddleOCR et EasyOCR (Python) n'est plus exécutable ; on mesure Tesseract seul contre les seuils (question Q8). | E6 (dev3) |
| D6 | Délai dépôt → disponibilité en recherche | 5 min (20 pages, file vide), 60 min au 95e centile en pointe | **24 heures maximum.** Cas des attachements de 400 à 800 pages. | Objectif de supervision et seuil d'alerte à 24 h ; la file OCR reste asynchrone. | E6 (dev3), E10 (dev2) |
| D7 | Workflow | Sans ordre | **Parallèle confirmé** : tous les validateurs reçoivent la demande en même temps, aucun validateur optionnel. Logique entièrement côté back, le front ne fait qu'afficher. | Confirme le lot E8. | E8 |
| D8 | Workflow via API | Non prévu | **Nouvelle exigence** : le workflow doit être pilotable nominativement par API (intranet) : désigner les validateurs, gérer les circuits, et permettre de valider depuis une application tierce. | Nouveaux points d'entrée d'API workflow, soumis aux clés API et à la délégation d'identité. Les mêmes points d'entrée servent le front et l'intranet (API unique, §2.3). | E8-API, entre E8 et E9 (vague 5 : dev1 contrat et points d'entrée, dev2 ouverture aux tiers) |
| D9 | Versions | Version courante désignable | Une nouvelle version **archive automatiquement l'ancienne** ; l'utilisateur lie explicitement la nouvelle version à l'ancienne. | *Corrigé par pm.* Au versement, la nouvelle version devient courante et l'ancienne est conservée **en lecture seule dans l'historique**. Ce n'est **pas** le statut d'archivage du §12.6 (drapeau, PDF/A) : D10 impose que l'archivage soit manuel et jamais automatique, ce qui exclut de l'appliquer aux versions à chaque versement. Désigner une ancienne version comme courante : question Q6. | E7 (dev1) |
| D10 | Archivage | Statut par document, job par lot | **Action manuelle**, jamais automatique ; applicable à **un dossier entier** en une fois ; **drapeau** sur documents **ou dossiers** ; les fichiers Word restent archivables ; conversion PDF obligatoire à l'archivage. | Drapeau d'archivage aussi sur les nœuds (dossiers) ; archivage d'un dossier = traitement de fond sur ses documents ; copie PDF/A maintenue. Restent ouverts : dépôt dans un dossier archivé, et la remarque « le PDF résultant est essentiellement une image » (question Q7). | E7 (dev3) |
| D11 | Journaux | INSERT seul, déclencheurs, scellement chaîné exporté | Protéger contre toute modification ou suppression **y compris par l'administrateur via l'interface** ; l'accès direct à la base n'est pas couvert. Actions auditées du §4.9.4 validées. | Minimum exigé : aucune fonction de modification ou suppression du journal dans l'application, droits INSERT/SELECT seuls. Le scellement reste au dossier V3 : on le conserve (coût modéré, preuve renforcée). | E4 (dev2) |
| D12 | Collaboration « marchés » (CPS) | — | Pas de co-édition. **Espace de partage simple** : déposer, télécharger, modifier en local, téléverser une nouvelle version. | Correspond aux espaces d'échange du dossier fonctionnel ; aucune édition en ligne. | E7 (dev1, vague 4) |
| D13 | Validateur restreint à un type de document | — | Jugé « **probablement** trop détaillé » pour le périmètre actuel (absent de la liste des décisions clés du compte rendu). | Ne pas implémenter ; confirmation écrite à obtenir. Ne pas confondre avec le rattachement d'une **règle** de workflow à un type (§12.8), qui reste dû. | — |
| D14 | Habilitations | Proposition « à valider » | Héritage, rupture d'héritage, document isolé et liste des permissions **validés**. Corbeille pour toute suppression confirmée. | Confirme E3. | E3 (dev1) |
| D15 | Délégation vers un compte désactivé (réponse à QR9, reportée le 30/09/2026) | §5.5 : un `X-On-Behalf-Of` désignant un compte désactivé est rejeté (422) | **MMED autorise la lecture de `userAccountControl`** dans l'AD, en lecture seule, **limitée à ce contrôle** et sécurisée (option « oui » de la question QR9). Si le compte est désactivé, la GED **refuse** qu'une application tierce agisse au nom de cet utilisateur. Les comptes techniques des applications tierces restent gérés à part, avec leurs propres droits. | **Exception bornée à D1** : D1 reste valable pour la connexion interactive, les renouvellements et les sessions (aucune relecture périodique). Lecture ponctuelle au moment de la délégation (cache court de quelques minutes au plus), compte désactivé = 422 `IDENTITE_DELEGUEE_INVALIDE`, tracé. Lève l'écart de T-055 et le risque R28 une fois livré et recetté. **Livrée le 30/09/2026** par dev1 (`69625e7`, `cdd250f`, fusion `1056f9c`) : lecture du seul `userAccountControl` par `objectGUID` avec le compte de service, cache `GED_DELEGATION_CACHE_ETAT_COMPTE` (2 min par défaut, 5 min au plus), 422 `IDENTITE_DELEGUEE_INVALIDE` tracé ; en attente de recette qa. | E9 (dev1, identité) |

## 2. Décisions tentatives (ne rien changer pour l'instant)

- **T1** — Durées de conservation du §4.7.4 : en attente de validation par le maître d'ouvrage.
  On implémente le mécanisme paramétrable ; les valeurs restent des paramètres.
- **T2** — Données d'annuaire : cache ou base synchronisée, au choix de l'équipe. **Choix de
  l'équipe : on garde le cache `cache_annuaire` du dossier V3** (100 à 150 utilisateurs, aucune
  synchronisation à maintenir, pas de désynchronisation possible avec la logique applicative).

## 3. Questions ouvertes à faire trancher par Marchica Med

- **Q1 — Circuit figé ou non.** Le dossier V3 (§12.8) fige le circuit au dépôt. La réunion
  décrit l'inverse pour un changement d'organisation : « le nouveau validateur remplace
  l'ancien pour les étapes restantes ». *Corrigé par pm* : la « page 17 » citée en réunion est
  celle du **dossier fonctionnel** (§4.5), pas du dossier technique (dont la p. 17 traite des clés
  API et de la délégation) ; le compte rendu demande de vérifier ce point « par rapport au
  fonctionnement réel », pas de changer la règle. La partie « les nouveaux documents suivent le
  nouveau circuit » est déjà conforme au V3. Le vocabulaire « étapes restantes » est séquentiel,
  en contradiction avec D7. Proposition de l'équipe : circuit figé (valeur probante), plus une
  **réaffectation explicite** par l'Administrateur d'un validateur en attente, tracée dans
  l'audit — c'est la « réattribution manuelle » déjà prévue au V3 §3.4.2. Attention : avec D1, la
  GED ne détecte plus les comptes désactivés ; la réaffectation est donc à l'initiative de
  l'Administrateur (risque R27). À valider.
- **Q2 — Stockage des jetons.** Le compte rendu cite, à titre d'exemple seulement (« par exemple »), « jeton dans le storage local, refresh token
  en mémoire ou cookie ». Le dossier V3 retient l'inverse, plus sûr : jeton d'accès en mémoire,
  jeton de renouvellement en cookie httpOnly dont l'empreinte est vérifiée en base. L'équipe garde
  le mécanisme du dossier V3, qui satisfait l'exigence « jeton + refresh token haché ».
- **Q3 — Attributs AD retirés.** Quels sont exactement les deux attributs à retirer ? Le compte
  rendu les dit « liés à Object sure ID » (transcription probable d'`objectGUID` ou
  `objectSID`) ; l'identifiant unique AD reste explicitement nécessaire (compte rendu, partie 7). Hypothèse de pm :
  `userAccountControl` et `userPrincipalName`, déjà rendus inutiles par D1 et D2.
- **Q4 — Verrouillage et versions.** *Lecture corrigée par pm* : la réunion ne redéfinit pas la
  fonction de verrouillage. Elle décrit l'immuabilité du fichier source (toute modification de
  contenu passe par une nouvelle version, la « modification » d'un document ne portant que sur
  ses métadonnées) : c'est le principe d'écriture unique du V3 (§6.1.1), déjà retenu. Aucune
  décision n'a été prise sur le verrou lui-même : le gel complet du V3 (fiche, versions,
  déplacement, réindexation, archivage) s'applique. Confirmation souhaitée, car le mot « verrouillage »
  employé en réunion peut prêter à confusion.
- **Q5 — Mention Python.** L'action « retirer Python de la documentation » concerne le dossier
  fonctionnel (p. 11, « s'intègre en trois lignes de code en Python ») : à corriger par l'auteur
  du dossier.
- **Q6 — Version courante désignable** (ajoutée par pm). D9 bascule automatiquement la version
  courante au versement ; le V3 (§12.8) et le dossier fonctionnel (§4.6.4) permettent de
  redésigner une version antérieure comme courante. On la conserve en attendant.
- **Q7 — Archivage de dossier** (ajoutée par pm). Dépôt dans un dossier archivé : refusé
  (lecture seule) en attendant ; nature de la copie PDF (« essentiellement une image » selon un
  participant) : PDF/A-2 avec couche texte quand la source en a une.
- **Q8 — Protocole OCR sans Python** (ajoutée par pm). La comparaison avec PaddleOCR et EasyOCR
  est-elle abandonnée ? En attendant : mesure de Tesseract seul contre les seuils du §4.3.2.
- **Q9 — Délégation vers un compte désactivé** (relevée par qa, QR9 du registre des risques).
  **Tranchée le 30/09/2026** : voir la décision D15.

## 4. Risques introduits

Risques reportés dans `RISQUES.md` sous les numéros R26 (ci-dessous R1) et R27 ; questions
sous les numéros QR1 à QR9 (QR9 tranchée : D15).

- **R1 (D1)** — Sans relecture de l'état AD, un utilisateur désactivé garde l'accès **jusqu'à
  l'expiration de son jeton de renouvellement** (8 h maximum au dossier V3), car le
  renouvellement ne repasse pas par l'annuaire. Mitigations proposées : durée absolue de session
  réduite (par exemple 4 h), et révocation manuelle des sessions d'un utilisateur par
  l'Administrateur (déjà prévue par la table `session`). L'inactivité de 30 minutes borne déjà
  l'exposition d'une session abandonnée. À présenter au client.
- **R2 (D1)** — Ajouté par pm. Plus de signalement automatique des validations en attente d'un
  validateur parti ; un circuit parallèle peut rester bloqué. Parade : réaffectation par
  l'Administrateur et tableau de bord des circuits en attente.

## 5. Décisions du chef de projet IPTECH (30/09/2026)

Décisions internes de conduite de projet, prises par le chef de projet côté IPTECH ; elles ne
modifient pas le dossier V3 mais fixent les conditions de clôture de deux lignes du suivi.

- **P1 — T-088 (déploiement UAT module par module, §9.3) : « Livré sous réserves ».** La
  validation finale suppose : (1) la démonstration de `deployer.sh`, y compris son retour
  arrière, sur un environnement systemd (kit UAT de dev2) ; (2) le masquage dans le front des
  menus des modules désactivés (dev4).
- **P2 — T-025 (modèle de référence du §12.1) : « à corriger ».** dev1 corrige les trois écarts
  relevés par dev2 : colonnes `droit_*` de `groupe_ged`, `groupe_membre.employe_id` à remplacer
  par `utilisateur_id`, colonnes `name` en anglais ; il fournit ensuite le schéma à jour
  (`docs/modelisation/SCHEMA-BASE.md` régénéré) et les résultats de tests. Tout écart conservé
  est justifié par écrit et soumis à validation.
