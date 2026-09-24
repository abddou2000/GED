# 01 — Connexion — instructions

## 1. Objectif

Faire entrer l'utilisateur dans l'application. C'est la seule porte : toute
adresse saisie sans session valide doit ramener ici.

## 2. Ce que l'écran doit afficher

1. Le logo et le nom de l'application.
2. Une accroche courte.
3. Le formulaire de connexion (voir §4).
4. Un lien « Mot de passe oublié ? ».
5. Une mention de sécurité en pied de page.

## 3. Actions et comportements attendus

| Geste | Comportement attendu |
|---|---|
| Un champ obligatoire est vide | le bouton « Se connecter » reste **inactif** ; rien n'est envoyé |
| Clic sur « Se connecter » | vérification de l'identité |
| Identité correcte | ouverture de la session, puis **redirection vers la page que l'utilisateur visait** |
| Identité incorrecte | message d'erreur, les champs restent remplis |
| Clic sur l'œil du mot de passe | bascule affiché / masqué |

**Instruction importante :** quand l'utilisateur a été dérouté vers la connexion
depuis une autre page, **mémorisez cette destination** et renvoyez-le dessus
après connexion. Ne le renvoyez pas systématiquement sur l'accueil.

## 4. Formulaire

| Champ | Type | Obligatoire | Valeur par défaut |
|---|---|---|---|
| Adresse e-mail | e-mail | **oui** | vide |
| Mot de passe | mot de passe masqué | **oui** | vide |
| Se souvenir de moi | case à cocher | non | décochée |

## 5. Règles à respecter

1. **La case « Se souvenir de moi » décide de la durée de la session.**
   - Décochée : la session est **cloisonnée à l'onglet** et disparaît à sa
     fermeture.
   - Cochée : la session **survit sur le poste**.
   Ne prenez pas cette décision à la place de l'utilisateur : lui seul sait s'il
   est sur un poste partagé.
2. **Ne dites jamais lequel des deux identifiants est faux.** Le message doit
   rester « E-mail ou mot de passe incorrect. ». Distinguer les deux cas
   révélerait quelles adresses existent dans le système.
3. **Comptez les tentatives.** Au-delà d'un seuil, refusez temporairement avec un
   message dédié.
4. Ne videz pas les champs après un échec : l'utilisateur doit pouvoir corriger
   une faute de frappe.

## 6. Messages à afficher

| Situation | Message exact |
|---|---|
| Identifiants incorrects | « E-mail ou mot de passe incorrect. » |
| Compte désactivé | « Ce compte est désactivé. » |
| Trop de tentatives | « Trop de tentatives. Réessayez dans quelques minutes. » |
| Session expirée pendant l'usage | « Votre session a expiré. Reconnectez-vous. » |
| Erreur non interprétée | « Une erreur est survenue. » |

## 7. Cas particuliers

**Première mise en service.** Si aucun compte administrateur n'a été créé, l'écran
affichera « E-mail ou mot de passe incorrect. » — il ne peut pas distinguer ce cas
d'un mot de passe erroné.

**Instruction :** documentez ce point pour l'exploitant. En cas d'échec
inexplicable à la première connexion, la réponse est dans le **journal du
serveur**, pas à l'écran.

## 8. Dépendances

- **En amont :** aucune.
- **En aval :** toutes les autres interfaces supposent cette étape franchie.
