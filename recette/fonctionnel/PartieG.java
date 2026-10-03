import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Partie G de la recette fonctionnelle (tour 6) : groupes GED et droits par groupe après T-025
 * (écart 2, `f3ee44d`) — le membre d'un groupe est une identité GED ; une fiche employé sans
 * identité est membre « en attente » (champ `pendingUserIds`) jusqu'à sa première connexion.
 *
 * <p>Rejoue, sur un espace propre (« QA2 Groupes T-025 ») et des comptes neufs de l'annuaire
 * simulé (un par exécution), les exigences qui passent par un groupe : F-06 (rattachement par
 * groupe, ANO-F-009), F-14 et F-15 (effet immédiat d'un ajout, d'un retrait, de la corbeille),
 * F-11 (identité à la 1re connexion : appartenance préparée appliquée sans action, notifiée et
 * tracée depuis le tour 7, ANO-F-030 ; aucun avis pour un groupe en corbeille), F-65
 * (accès attribué notifié aux membres), F-54 et F-51 (validateur par rôle porté par un groupe,
 * notification), F-52 (diffusion à un groupe), F-58 (alerte d'échéance à un Agent d'archive par
 * groupe).
 *
 * <p>La fiche « reprise » sans identité est insérée en base (comme le fait la reprise des données)
 * si GED_RECETTE_JDBC_URL, GED_RECETTE_JDBC_UTILISATEUR et GED_RECETTE_JDBC_MDP sont fournis ;
 * sinon la ligne F-11g est NA.
 */
public class PartieG extends RecetteFonctionnelle {

    PartieG() {
        super("");
    }

    static void etape(String id, PartieC.Etape e) {
        PartieC.etape(id, e);
    }

    static String EG, TG, TGV, GA, GP, GL, GAR, A1, P1, P1EMP, DOC_EG;

    static Map<String, Object> groupe(String code, String nom, List<String> espaces, List<String> membres) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("name", nom);
        c.put("workspaceIds", espaces);
        c.put("userIds", membres);
        return c;
    }

    static boolean voit(String compte, String doc) throws Exception {
        return G(compte, "/api/v1/documents/" + doc).code() == 200;
    }

    static boolean groupeExiste(String code) throws Exception {
        return trouver(contenu(G(ADM, "/api/v1/access-groups?size=500&search=").json()), "code", code) != null;
    }

    static Set<String> ids(JsonNode tableau, String champ) {
        Set<String> s = new LinkedHashSet<>();
        for (JsonNode n : tableau) s.add(champ == null ? n.asText() : n.path(champ).asText());
        return s;
    }

    static String resume(JsonNode g) {
        return "usersCount " + g.path("usersCount") + ", users " + ids(g.path("users"), "id") + ", pendingUserIds " + g.path("pendingUserIds");
    }

    static Connection base() throws Exception {
        String url = env("GED_RECETTE_JDBC_URL", "");
        if (url.isBlank()) return null;
        return DriverManager.getConnection(url, env("GED_RECETTE_JDBC_UTILISATEUR", ""), env("GED_RECETTE_JDBC_MDP", ""));
    }

    static int compter(String sql, String... args) throws Exception {
        try (Connection c = base(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, UUID.fromString(args[i]));
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    static void executer() throws Exception {
        info("---- Partie G : groupes GED par identité (T-025), droits et destinataires par groupe");
        String m1 = M.toUpperCase();
        EG = noeud("QA2 Groupes T-025", "QA2-GRP-T025", null, "METIER");
        TG = type("QA2-GT025", "QA2 Pièce de groupe (1 mois)", EG, null, List.of("pdf"), 10, 1, "PUBLIC");
        DOC_EG = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-g-espace-" + M, TG);
        A1 = "qa2g" + M;
        LdapSimule.ajouter(A1, "Membre", "Groupe " + M);
        me(A1); // 1re connexion : identité sans rôle
        boolean sansDroit = !voit(A1, DOC_EG);

        // ---- F-06 / ANO-F-009 : userIds accepte l'identité GED, refuse l'inconnu
        etape("F-06g", () -> {
            Rep parIdentite = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GA-" + m1, "QA2 Groupe identité " + M, List.of(EG), List.of(uid(A1))));
            GA = idDe(parIdentite, "groupe par identité");
            JsonNode g = parIdentite.json();
            boolean traduit = g.path("usersCount").asInt() == 1 && emp(A1).equals(g.path("users").path(0).path("id").asText())
                    && g.path("pendingUserIds").isArray() && g.path("pendingUserIds").isEmpty();
            boolean voitApres = voit(A1, DOC_EG);
            JsonNode de = G(ADM, "/api/v1/admin/droits-effectifs?utilisateurId=" + uid(A1) + "&noeudId=" + EG).json();
            boolean origine = de.path("origines").toString().contains("QA2 Groupe identité");
            String inconnu = UUID.randomUUID().toString();
            Rep r1 = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GX-" + m1, "QA2 Groupe inconnu " + M, List.of(), List.of(inconnu)));
            Rep r2 = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GY-" + m1, "QA2 Groupe mixte " + M, List.of(), List.of(uid(A1), inconnu)));
            boolean aucunCree = !groupeExiste("QA2-GX-" + m1) && !groupeExiste("QA2-GY-" + m1);
            Rep r3 = J(ADM, "PUT", "/api/v1/access-groups/" + GA, groupe("QA2-GA-" + m1, "QA2 Groupe identité " + M, List.of(EG), List.of(uid(A1), inconnu)));
            JsonNode apresPut = G(ADM, "/api/v1/access-groups/" + GA).json();
            Rep doublon = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GD-" + m1, "QA2 Groupe doublon " + M, List.of(), List.of(emp(A1), uid(A1))));
            Rep espaceInconnu = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GE-" + m1, "QA2 Groupe espace " + M, List.of(inconnu), List.of(uid(A1))));
            boolean listeInconnus = r1.corps().contains(inconnu) && r2.corps().contains(inconnu) && !r2.corps().contains(uid(A1));
            verif("F-06g", sansDroit && parIdentite.code() == 201 && traduit && voitApres && origine
                            && r1.code() == 422 && "MEMBRES_INCONNUS".equals(r1.codeMetier()) && r2.code() == 422 && listeInconnus && aucunCree
                            && r3.code() == 422 && apresPut.path("usersCount").asInt() == 1
                            && doublon.code() == 201 && doublon.json().path("usersCount").asInt() == 1
                            && espaceInconnu.code() == 422 && "ESPACES_INCONNUS".equals(espaceInconnu.codeMetier()),
                    "Groupe GED par identité (T-025, ANO-F-009) : identité GED acceptée et traduite, droits du groupe effectifs ; identifiant inconnu refusé (422) sans rien écrire",
                    "compte neuf " + A1 + " sans droit avant " + sansDroit + " ; POST userIds=[identité] " + court(parIdentite) + " (" + resume(g) + ", fiche attendue "
                            + emp(A1) + ") → document de l'espace lu " + voitApres + ", origine « groupe » " + origine + " ; inconnu seul " + court(r1)
                            + ", mixte " + court(r2) + " (seul l'inconnu listé " + listeInconnus + "), aucun groupe créé " + aucunCree + " ; PUT avec un inconnu "
                            + court(r3) + " → membres " + apresPut.path("usersCount") + " ; même personne par fiche et par identité " + court(doublon) + " → "
                            + doublon.json().path("usersCount") + " membre ; espace inconnu " + court(espaceInconnu));
        });

        // ---- F-14 / F-15 : retrait, ajout, corbeille et restauration du groupe, effet immédiat
        etape("F-14g", () -> {
            Rep retrait = J(ADM, "PUT", "/api/v1/access-groups/" + GA, groupe("QA2-GA-" + m1, "QA2 Groupe identité " + M, List.of(EG), List.of()));
            boolean apresRetrait = voit(A1, DOC_EG);
            Rep ajout = J(ADM, "PUT", "/api/v1/access-groups/" + GA, groupe("QA2-GA-" + m1, "QA2 Groupe identité " + M, List.of(EG), List.of(emp(A1))));
            boolean apresAjout = voit(A1, DOC_EG);
            Rep corbeille = X(ADM, "DELETE", "/api/v1/access-groups/" + GA);
            boolean enCorbeille = voit(A1, DOC_EG);
            Rep restaure = X(ADM, "PATCH", "/api/v1/access-groups/" + GA + "/restore");
            boolean restaureVoit = voit(A1, DOC_EG);
            JsonNode g = G(ADM, "/api/v1/access-groups/" + GA).json();
            verif("F-14g", retrait.code() == 200 && !apresRetrait && ajout.code() == 200 && apresAjout && corbeille.code() / 100 == 2 && !enCorbeille
                            && restaure.code() / 100 == 2 && restaureVoit && g.path("usersCount").asInt() == 1,
                    "Droits par groupe modifiés sans code, effet immédiat : retrait et ajout d'un membre, groupe en corbeille puis restauré",
                    "retrait du membre " + court(retrait) + " → lecture " + apresRetrait + " ; ajout par sa fiche " + court(ajout) + " → lecture " + apresAjout
                            + " ; groupe en corbeille " + court(corbeille) + " → lecture " + enCorbeille + " ; restauré " + court(restaure) + " → lecture "
                            + restaureVoit + " (" + resume(g) + ")");
        });

        // ---- F-11 : appartenance préparée pour une fiche sans identité, appliquée à la 1re connexion
        etape("F-11g", () -> {
            if (base() == null) {
                res("F-11g", "NA", "Appartenance en attente (fiche reprise sans identité)", "GED_RECETTE_JDBC_URL non fourni : fiche reprise non insérée");
                return;
            }
            String nom = "T" + M;
            P1EMP = UUID.randomUUID().toString();
            try (Connection c = base(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO employe (id, first_name, last_name, has_user, created_at, updated_at) VALUES (?, 'Attente', ?, false, now(), now())")) {
                p.setObject(1, UUID.fromString(P1EMP));
                p.setString(2, nom);
                p.executeUpdate();
            }
            Rep cree = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GP-" + m1, "QA2 Groupe préparé " + M, List.of(EG), List.of(P1EMP)));
            GP = idDe(cree, "groupe préparé");
            JsonNode g = cree.json();
            boolean enAttente = g.path("usersCount").asInt() == 1 && P1EMP.equals(g.path("users").path(0).path("id").asText())
                    && ids(g.path("pendingUserIds"), null).equals(Set.of(P1EMP));
            int attenteBase = compter("SELECT count(*) FROM groupe_membre_attente WHERE groupe_ged_id = ? AND employe_id = ?", GP, P1EMP);
            int membresBase = compter("SELECT count(*) FROM groupe_membre WHERE groupe_ged_id = ?", GP);
            // L'écran renvoie les identifiants reçus (users[].id) : l'attente doit être conservée.
            Rep renvoi = J(ADM, "PUT", "/api/v1/access-groups/" + GP, groupe("QA2-GP-" + m1, "QA2 Groupe préparé (renommé) " + M, List.of(EG),
                    List.copyOf(ids(g.path("users"), "id"))));
            boolean conservee = renvoi.code() == 200 && ids(renvoi.json().path("pendingUserIds"), null).equals(Set.of(P1EMP));
            // Première connexion : courriel dérivé de la fiche (prénom.nom@marchica.ma).
            P1 = "qa2p" + M;
            LdapSimule.ajouter(P1, "Attente", nom, "attente." + nom.toLowerCase() + "@marchica.ma");
            JsonNode moiP1 = me(P1);
            boolean rattache = P1EMP.equals(moiP1.path("employeId").asText());
            JsonNode apres = G(ADM, "/api/v1/access-groups/" + GP).json();
            boolean converti = apres.path("pendingUserIds").isEmpty() && apres.path("usersCount").asInt() == 1
                    && P1EMP.equals(apres.path("users").path(0).path("id").asText());
            int attenteApres = compter("SELECT count(*) FROM groupe_membre_attente WHERE employe_id = ?", P1EMP);
            boolean voitP1 = voit(P1, DOC_EG);
            JsonNode stats = G(P1, "/api/v1/stats/overview").json();
            JsonNode profil = G(P1, "/api/v1/employes/profil").json();
            boolean profilGroupe = ids(profil.path("groupesAcces"), "id").contains(GP);
            attendre(1500);
            // ANO-F-030 (tour 7) : la conversion est notifiée (un avis par espace) et tracée (acteur « Système »).
            int avisP1 = 0;
            for (JsonNode n : PartieC.notifications(P1))
                if ("ACCES_ESPACE_ATTRIBUE".equals(n.path("type").asText()) && EG.equals(n.path("objetId").asText())) avisP1++;
            JsonNode trace = null;
            for (JsonNode l : audit("action=GROUPE_MEMBRE_ACTIVE&objetId=" + GP)) trace = l;
            String uidP1 = uid(P1);
            boolean journal = trace != null && "Système".equals(trace.path("acteurNom").asText()) && trace.path("acteurUtilisateurId").isNull()
                    && uidP1.equals(trace.path("apres").path("utilisateurId").asText()) && P1EMP.equals(trace.path("apres").path("employeId").asText())
                    && P1EMP.equals(trace.path("avant").path("membreEnAttente").asText());
            // Groupe en corbeille au moment de la 1re connexion : appartenance convertie, sans droit ni avis.
            String nomC = "C" + M, empC = UUID.randomUUID().toString();
            try (Connection c = base(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO employe (id, first_name, last_name, has_user, created_at, updated_at) VALUES (?, 'Corbeille', ?, false, now(), now())")) {
                p.setObject(1, UUID.fromString(empC));
                p.setString(2, nomC);
                p.executeUpdate();
            }
            Rep creeC = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GC-" + m1, "QA2 Groupe corbeille " + M, List.of(EG), List.of(empC)));
            String gc = idDe(creeC, "groupe en corbeille");
            Rep supprC = X(ADM, "DELETE", "/api/v1/access-groups/" + gc);
            String pc = "qa2c" + M;
            LdapSimule.ajouter(pc, "Corbeille", nomC, "corbeille." + nomC.toLowerCase() + "@marchica.ma");
            boolean rattacheC = empC.equals(me(pc).path("employeId").asText());
            attendre(1500);
            boolean avisC = PartieC.notifie(pc, "ACCES_ESPACE_ATTRIBUE", null);
            boolean voitC = voit(pc, DOC_EG);
            JsonNode traceC = null;
            for (JsonNode l : audit("action=GROUPE_MEMBRE_ACTIVE&objetId=" + gc)) traceC = l;
            res("F-11g", cree.code() == 201 && enAttente && attenteBase == 1 && membresBase == 0 && conservee && rattache && converti && attenteApres == 0 && voitP1
                            && stats.path("accessGroups").asInt() >= 1 && profilGroupe && avisP1 == 1 && journal
                            && creeC.code() == 201 && supprC.code() / 100 == 2 && rattacheC && !avisC && !voitC ? "OK" : "ECHEC",
                    "Membre préparé pour une personne jamais connectée : en attente sans droit, appartenance appliquée à la 1re connexion sans action de l'Administrateur, notifiée et journalisée (ANO-F-030) ; aucun avis pour un groupe en corbeille",
                    "fiche reprise " + P1EMP + " (sans identité) ; POST " + court(cree) + " (" + resume(g) + ") ; base : attente " + attenteBase + ", membres " + membresBase
                            + " ; PUT avec users[].id renvoyés " + court(renvoi) + " → attente conservée " + conservee + " ; 1re connexion de " + P1
                            + " : identité rattachée à la fiche " + rattache + ", groupe → " + resume(apres) + ", attente en base " + attenteApres
                            + ", document de l'espace lu " + voitP1 + ", tuile Groupes " + stats.path("accessGroups") + ", « mes groupes » du profil " + profilGroupe
                            + " ; avis ACCES_ESPACE_ATTRIBUE sur l'espace " + avisP1 + " ; journal GROUPE_MEMBRE_ACTIVE conforme " + journal
                            + (trace == null ? " (aucune ligne)" : " (acteur « " + trace.path("acteurNom").asText() + " », motif « " + trace.path("motif").asText()
                            + " », après " + trace.path("apres") + ")")
                            + " ; groupe en corbeille " + court(creeC) + "/" + court(supprC) + " puis 1re connexion de " + pc + " (rattaché " + rattacheC
                            + ") : avis ACCES_ESPACE_ATTRIBUE " + avisC + ", document lu " + voitC + ", [observation] trace GROUPE_MEMBRE_ACTIVE "
                            + (traceC != null));
        });

        // ---- F-65 : membre ajouté à un groupe couvrant un espace → avis d'accès
        etape("F-65g", () -> {
            attendre(1500);
            int avis = 0;
            for (JsonNode n : PartieC.notifications(A1))
                if ("ACCES_ESPACE_ATTRIBUE".equals(n.path("type").asText()) && EG.equals(n.path("objetId").asText())) avis++;
            verif("F-65g", avis >= 2,
                    "Attribution d'un accès par un groupe notifiée au membre (identité GED)",
                    "avis ACCES_ESPACE_ATTRIBUE sur « QA2 Groupes T-025 » reçus par " + A1 + " : " + avis + " (création du groupe, puis ré-ajout après retrait)");
        });

        // ---- F-54 / F-51 : validateur par rôle porté par un groupe
        etape("F-54g", () -> {
            Map<String, Object> parRole = new LinkedHashMap<>();
            parRole.put("label", "Un utilisateur standard du groupe");
            parRole.put("roleId", roles.get("UTILISATEUR_STANDARD"));
            parRole.put("perimetreNoeudId", EG);
            parRole.put("stepOrder", 1);
            String r = PartieC.regle("QA2 Circuit par groupe " + M, List.of(parRole));
            TGV = type("QA2-GV-" + m1, "QA2 Pièce validée par groupe " + M, EG, null, List.of("pdf"), 10, null, "PUBLIC");
            J(ADM, "PUT", "/api/v1/workflow/types/" + TGV + "/regle", Map.of("regleId", r));
            String d = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-g-wf-" + M, TGV);
            JsonNode c = PartieC.circuit(ADM, d);
            boolean a1 = PartieC.aTraiter(A1, d), p1 = P1 != null && PartieC.aTraiter(P1, d), autre = PartieC.aTraiter(AUTRE, d);
            attendre(2000);
            boolean avis = PartieC.notifie(A1, "CIRCUIT_OUVERT", null) && PartieC.notifications(A1).toString().contains(d);
            Rep dec = PartieC.decider(A1, c.path("id").asText(), "VALIDE", null);
            String statut = dec.json().path("statut").asText();
            // F-52 : diffusion du document validé à un groupe dont le membre est désigné par son identité.
            GL = idDe(J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GL-" + m1, "QA2 Groupe lecteurs " + M, List.of(), List.of(uid(AUTRE)))), "groupe lecteurs");
            boolean avant = voit(AUTRE, d);
            Rep diff = J(A1, "POST", "/api/v1/workflow/documents/" + d + "/diffusion", Map.of("groupeIds", List.of(GL)));
            boolean apres = voit(AUTRE, d);
            Rep ecriture = J(AUTRE, "PUT", "/api/v1/documents/" + d, Map.of("objet", "tentative " + M));
            verif("F-54g", "ROLE".equals(c.path("validateurs").path(0).path("type").asText()) && a1 && (P1 == null || p1) && !autre && avis
                            && dec.code() / 100 == 2 && "VALIDE".equals(statut),
                    "Validateur par rôle porté par un groupe GED : chaque membre (identité) reçoit la demande et l'avis, et peut décider",
                    "circuit " + c.path("statut").asText() + ", validateur " + c.path("validateurs").path(0).path("type").asText() + " ; à traiter : " + A1 + " " + a1
                            + ", " + P1 + " (membre converti) " + p1 + ", nidrissi (hors groupe) " + autre + " ; avis CIRCUIT_OUVERT à " + A1 + " " + avis
                            + " ; décision de " + A1 + " " + court(dec) + " → " + statut);
            verif("F-52g", !avant && diff.code() == 200 && apres && ecriture.code() / 100 == 4,
                    "Diffusion d'un document validé à un groupe GED dont le membre est désigné par son identité : lecture accordée, écriture refusée",
                    "groupe lecteurs (nidrissi par son identité) ; lecture avant " + avant + ", diffusion par " + A1 + " " + court(diff) + " ("
                            + diff.json().path("habilitationsPosees") + " habilitation) → lecture " + apres + ", écriture " + court(ecriture));
        });

        // ---- F-58 : alerte d'échéance à un Agent d'archive qui tient son rôle d'un groupe
        etape("F-58g", () -> {
            Rep cree = J(ADM, "POST", "/api/v1/access-groups", groupe("QA2-GAR-" + m1, "QA2 Groupe archivistes " + M, List.of(), List.of(uid(A1))));
            GAR = idDe(cree, "groupe archivistes");
            habiliter("GROUPE", GAR, "AGENT_ARCHIVE", EG, null, false);
            String d = idDe(deposer(ADM, "pdf_texte_fr_facture.pdf", "qa2-g-echeance-" + M, TG, PartieC.champs("dateDocument", "2025-01-10"), null), "échéance");
            boolean alerte = false;
            for (int i = 0; i < 30 && !alerte; i++) {
                alerte = PartieC.notifications(A1).toString().contains("qa2-g-echeance-" + M);
                if (!alerte) attendre(5000);
            }
            boolean std = PartieC.notifications(V1).toString().contains("qa2-g-echeance-" + M);
            verif("F-58g", alerte && !std,
                    "Alerte d'échéance adressée à l'Agent d'archive qui tient son rôle d'un groupe GED (membre par identité)",
                    "groupe archivistes " + court(cree) + " (AGENT_ARCHIVE sur l'espace) ; document échu " + d + " ; alerte reçue par " + A1 + " " + alerte
                            + " ; par un standard hors périmètre " + std);
        });
    }
}
