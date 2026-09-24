# 01 — Connexion

**Rôle dans le processus :** entrer dans l'application. C'est la seule porte ;
toute autre adresse saisie sans session valide ramène ici.

---

## 1. Ce que l'écran a à faire

Trois choses, et une seule est évidente :

1. Vérifier une identité.
2. **Se souvenir de l'endroit où l'utilisateur voulait aller.** Quelqu'un qui
   clique sur un lien vers un document et se retrouve dérouté vers la connexion
   doit atterrir sur **ce document** après s'être identifié, pas sur l'accueil.
   La destination est donc mise de côté et rejouée.
3. **Laisser l'utilisateur choisir la durée de sa session.** Ce n'est pas un
   confort, c'est une décision de sécurité qu'on ne peut pas prendre à sa place :
   seul lui sait s'il est sur son poste ou sur un poste partagé.

## 2. Le processus

```
        arrivée sur l'écran
                │
                ├── venu d'une page protégée ? ─► la destination est mémorisée
                │
                ▼
        saisie e-mail + mot de passe
                │
                ├── un champ vide ─────────────► le bouton reste inactif
                │                                (rien n'est envoyé)
                ▼
        vérification de l'identité
                │
    ┌───────────┼────────────┬──────────────┬─────────────┐
    ▼           ▼            ▼              ▼             ▼
 identité    identité    compte        trop de       le serveur
 correcte    fausse      désactivé     tentatives    ne répond pas
    │           │            │              │             │
    │           ▼            ▼              ▼             ▼
    │      « E-mail ou  « Ce compte   « Trop de     « Une erreur
    │       mot de passe  est          tentatives.   est survenue. »
    │       incorrect. »  désactivé. » Réessayez… »
    │
    ▼
 session ouverte ──► redirection vers la destination mémorisée
                     (ou l'accueil, à défaut)
```

## 3. Les informations demandées

| Information | Obligatoire | Ce qu'elle décide |
|---|---|---|
| Adresse e-mail | oui | l'identité |
| Mot de passe | oui | la preuve |
| « Se souvenir de moi » | non | **la durée de vie de la session** |

## 4. La règle de la case « Se souvenir de moi »

C'est le seul choix véritablement structurant de l'écran.

| Case | Ce que ça change |
|---|---|
| **Décochée** | la session est **cloisonnée à l'onglet** et disparaît à sa fermeture |
| **Cochée** | la session **survit sur le poste**, indéfiniment |

Le raisonnement : sur un poste personnel, se reconnecter dix fois par jour est
une nuisance ; sur un poste partagé, une session qui survit est une faille. Le
produit ne peut pas deviner dans quel cas on est — il demande.

## 5. Les règles de refus

| Situation | Message affiché |
|---|---|
| Identifiants incorrects | « E-mail ou mot de passe incorrect. » |
| Compte désactivé | « Ce compte est désactivé. » |
| Trop de tentatives rapprochées | « Trop de tentatives. Réessayez dans quelques minutes. » |
| Session expirée pendant l'usage | « Votre session a expiré. Reconnectez-vous. » |

**Pourquoi le message d'échec est volontairement vague.** « E-mail ou mot de
passe incorrect » ne dit pas lequel des deux est faux. Distinguer les deux cas
révélerait quelles adresses existent dans le système, ce qui aide qui cherche à
forcer l'entrée.

## 6. La protection contre le forçage

Les tentatives sont comptées. Au-delà d'un seuil, l'application refuse
temporairement, avec le message dédié. C'est ce qui distingue une erreur de
frappe d'un essai systématique.

## 7. Un piège de diagnostic à connaître

Lors d'une **première mise en service**, si aucun compte administrateur n'a été
créé au démarrage du serveur, l'écran affiche quand même « E-mail ou mot de
passe incorrect. » — il n'a aucun moyen de distinguer « ce mot de passe est
faux » de « aucun compte n'existe ».

**Conclusion pratique :** si la toute première connexion échoue de façon
inexplicable, la réponse est dans le **journal du serveur**, pas à l'écran.

## 8. En aval

Une fois la session ouverte, l'utilisateur arrive sur l'[accueil](02-accueil.md),
ou sur la page qu'il visait. Toutes les autres interfaces supposent cette étape
franchie.
