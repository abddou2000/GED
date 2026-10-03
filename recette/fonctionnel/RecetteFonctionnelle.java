import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette FONCTIONNELLE de la GED Marchica Med (qa2) : chaque exigence du dossier d'analyse des
 * besoins fonctionnels V3 (77 lignes de MATRICE-FONCTIONNELLE.md, numérotées F-01 à F-77) est
 * rejouée par l'API, du point de vue d'un utilisateur, avec les personas du §3.2 :
 * Administrateur (sbennani), Agent d'archive (qa2agent), Direction Générale (qa2dg), deux
 * utilisateurs standard validateurs (qa2val1, qa2val2), un « Responsable RH » composé (qa2rh),
 * un utilisateur standard cantonné à un sous-dossier (yalaoui), un utilisateur d'un autre
 * périmètre (nidrissi), des identités jamais connectées (qa2neuf1, qa2neuf2) et un agent qui
 * quitte MMED (qa2parti).
 *
 * <p>Le jeu (espaces « QA2 … », index, plan, types, rôle, groupe, habilitations) est construit
 * par l'API d'administration, retrouvé par son code s'il existe déjà : le script est rejouable.
 * Les documents déposés portent un marqueur par exécution et sont conservés (aucune suppression
 * de données de recette).
 *
 * <p>Usage : {@code bash recette/lib/lancer-java.sh recette/fonctionnel/RecetteFonctionnelle.java
 * RecetteFonctionnelle [A] [B] [C] [D] [G]} (défaut : A à D ; G, groupes par identité, sur demande). Variables : GED_URL
 * (défaut http://localhost:18088), GED_RECETTE_MOT_DE_PASSE (défaut : celui de l'annuaire
 * simulé du profil dev), GED_LDAP_PORT (annuaire simulé, pour F-12).
 * Java 17 + Jackson du classpath du backend (décision D5 : aucun Python).
 */
public class RecetteFonctionnelle extends ClientGed {

    RecetteFonctionnelle(String url) {
        super(url);
    }

    static RecetteFonctionnelle g;
    static String mdp;
    static final Map<String, String> jetons = new HashMap<>();
    static final Map<String, JsonNode> moi = new HashMap<>();
    static String M; // marqueur d'exécution
    static Path donnees;

    // ------------------------------------------------------------------ appels par persona

    static String jeton(String compte) throws Exception {
        String t = jetons.get(compte);
        if (t == null) {
            t = g.connecter(compte, mdp);
            jetons.put(compte, t);
        }
        return t;
    }

    /** Appel JSON en tant que {@code compte} ; une reconnexion si le jeton a expiré. */
    static Rep J(String compte, String methode, String chemin, Object corps) throws Exception {
        Rep r = compte == null ? g.json(methode, chemin, null, corps) : g.json(methode, chemin, jeton(compte), corps);
        if (r.code() == 401 && compte != null) {
            jetons.remove(compte);
            r = g.json(methode, chemin, jeton(compte), corps);
        }
        return r;
    }

    static Rep G(String compte, String chemin) throws Exception {
        Rep r = g.get(chemin, compte == null ? null : jeton(compte));
        if (r.code() == 401 && compte != null) {
            jetons.remove(compte);
            r = g.get(chemin, jeton(compte));
        }
        return r;
    }

    static Rep X(String compte, String methode, String chemin) throws Exception {
        Rep r = g.appel(methode, chemin, jeton(compte), null, null);
        if (r.code() == 401) {
            jetons.remove(compte);
            r = g.appel(methode, chemin, jeton(compte), null, null);
        }
        return r;
    }

    static JsonNode me(String compte) throws Exception {
        JsonNode j = moi.get(compte);
        if (j == null) {
            j = G(compte, "/api/v1/auth/me").json();
            moi.put(compte, j);
        }
        return j;
    }

    static String uid(String compte) throws Exception {
        return me(compte).path("id").asText();
    }

    static String emp(String compte) throws Exception {
        return me(compte).path("employeId").asText();
    }

    /** Dépôt multipart en tant que {@code compte}. */
    static Rep deposer(String compte, String fichier, String nom, String typeId, Map<String, String> champs, String meta) throws Exception {
        byte[] octets = Files.readAllBytes(donnees.resolve(fichier));
        Map<String, String> c = new LinkedHashMap<>();
        if (nom != null) c.put("name", nom);
        if (typeId != null) c.put("typeDocumentId", typeId);
        if (champs != null) c.putAll(champs);
        String ext = fichier.substring(fichier.lastIndexOf('.'));
        String mime = switch (ext) {
            case ".pdf" -> "application/pdf";
            case ".png" -> "image/png";
            case ".txt" -> "text/plain";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".csv" -> "text/csv";
            default -> "application/octet-stream";
        };
        Rep r = g.deposer(jeton(compte), octets, (nom == null ? "sans-nom" : nom) + ext, mime, c, meta);
        if (r.code() == 401) {
            jetons.remove(compte);
            r = g.deposer(jeton(compte), octets, (nom == null ? "sans-nom" : nom) + ext, mime, c, meta);
        }
        return r;
    }

    static String idDe(Rep r, String quoi) {
        if (r.code() / 100 != 2) throw new IllegalStateException(quoi + " : HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static String doc(String compte, String fichier, String nom, String typeId) throws Exception {
        return idDe(deposer(compte, fichier, nom, typeId, null, null), "dépôt de " + nom);
    }

    // ------------------------------------------------------------------ outils JSON

    static List<JsonNode> contenu(JsonNode page) {
        List<JsonNode> l = new ArrayList<>();
        (page.has("content") ? page.path("content") : page).forEach(l::add);
        return l;
    }

    static JsonNode trouver(List<JsonNode> l, String champ, String valeur) {
        for (JsonNode n : l) if (valeur.equalsIgnoreCase(n.path(champ).asText())) return n;
        return null;
    }

    static void arbre(JsonNode noeuds, Map<String, JsonNode> acc) {
        for (JsonNode n : noeuds) {
            acc.put(n.path("id").asText(), n);
            arbre(n.path("children"), acc);
        }
    }

    static Set<String> idsArbre(String compte) throws Exception {
        Map<String, JsonNode> acc = new LinkedHashMap<>();
        arbre(G(compte, "/api/v1/workspaces/tree").json(), acc);
        return acc.keySet();
    }

    static Set<String> textes(JsonNode tableau, String champ) {
        Set<String> s = new LinkedHashSet<>();
        for (JsonNode n : tableau) s.add(n.path(champ).asText());
        return s;
    }

    /** Documents visibles d'un compte dont le nom contient {@code texte} (liste paginée complète). */
    static Set<String> visibles(String compte, String texte) throws Exception {
        Set<String> r = new LinkedHashSet<>();
        for (int p = 0; p < 50; p++) {
            JsonNode j = G(compte, "/api/v1/documents?size=100&page=" + p + "&search=" + enc(texte)).json();
            for (JsonNode d : j.path("content")) r.add(d.path("id").asText());
            if (p + 1 >= j.path("totalPages").asInt(1)) break;
        }
        return r;
    }

    static List<JsonNode> audit(String filtre) throws Exception {
        return contenu(G("sbennani", "/api/v1/audit/evenements?taille=200&" + filtre).json());
    }

    static boolean auditContient(String filtre, Predicate<JsonNode> p) throws Exception {
        for (JsonNode l : audit(filtre)) if (p.test(l)) return true;
        return false;
    }

    /** Tentative de connexion brute ; attend la fin de la limitation de débit (429) au lieu de la compter. */
    static Rep login(String identifiant, String secret) throws Exception {
        for (int i = 0; i < 4; i++) {
            Rep r = g.json("POST", "/api/v1/auth/login", null, Map.of("identifiant", identifiant, "motDePasse", secret));
            if (r.code() != 429) return r;
            long s = r.entetes().firstValueAsLong("Retry-After").orElse(60);
            info("limitation de débit : attente de " + (s + 1) + " s");
            attendre((s + 1) * 1000);
        }
        throw new IllegalStateException("429 persistant pour " + identifiant);
    }

    static String court(Rep r) {
        String c = r.codeMetier();
        return r.code() + (c.isBlank() ? "" : " " + c);
    }

    static void attendre(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ------------------------------------------------------------------ jeu de recette

    static String ADM = "sbennani", AGENT = "qa2agent", DG = "qa2dg", V1 = "qa2val1", V2 = "qa2val2", RH = "qa2rh",
            SOUS = "yalaoui", AUTRE = "nidrissi", NEUF1 = "qa2neuf1", NEUF2 = "qa2neuf2", PARTI = "qa2parti", GRP = "kelfassi";
    static Map<String, String> roles = new HashMap<>();
    static String FIN, FIN26, FIN26F, FINRES, RHE, RHC, ECH, PRJ; // nœuds
    static String IDX_NUM, IDX_MONTANT, IDX_DATE, IDX_PAYEE, IDX_STATUT, PLAN;
    static String T_FACT, T_NOTE, T_CONTRAT, T_COURRIER, T_FACT_RES;
    static String ROLE_RH, GROUPE;

    static String noeud(String nom, String code, String parent, String usage) throws Exception {
        for (JsonNode w : contenu(G(ADM, "/api/v1/workspaces?size=500&search=" + enc(nom)).json())) {
            if (code.equals(w.path("code").asText())) return w.path("id").asText();
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", nom);
        c.put("code", code);
        c.put("description", "Recette fonctionnelle qa2");
        c.put("employeId", emp(ADM));
        c.put("parentId", parent);
        if (usage != null) c.put("usageEspace", usage);
        return idDe(J(ADM, "POST", "/api/v1/workspaces", c), "création du nœud " + nom);
    }

    static String index(String code, String nom, String type, String valeurs, boolean obligatoire) throws Exception {
        JsonNode i = trouver(contenu(G(ADM, "/api/v1/indices?size=500").json()), "code", code);
        if (i != null) return i.path("id").asText();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("nomIndex", nom);
        c.put("fieldType", type);
        c.put("valeurs", valeurs);
        c.put("obligatoire", obligatoire);
        c.put("indexePourRecherche", true);
        c.put("indexDeGroupage", false);
        return idDe(J(ADM, "POST", "/api/v1/indices", c), "index " + code);
    }

    static String type(String code, String libelle, String noeud, String plan, List<String> formats, int tailleMo,
                       Integer dureeMois, String confidentialite) throws Exception {
        JsonNode t = trouver(contenu(G(ADM, "/api/v1/type-documents?size=500").json()), "code", code);
        if (t != null) return t.path("id").asText();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("typeDeDocument", libelle);
        c.put("description", "Type de recette fonctionnelle qa2");
        c.put("workspaceId", noeud);
        c.put("planIndexationId", plan);
        c.put("typeAutorise", formats);
        c.put("tailleMaxMo", tailleMo);
        c.put("dureeConservationMois", dureeMois);
        c.put("pointDepart", "DATE_DOCUMENT");
        c.put("confidentialiteDefaut", confidentialite);
        return idDe(J(ADM, "POST", "/api/v1/type-documents", c), "type " + code);
    }

    /** Pose une habilitation si elle n'existe pas encore (sujet, rôle, nœud ou document, rupture). */
    static void habiliter(String sujetType, String sujetId, String roleCode, String noeudId, String documentId, boolean rupture) throws Exception {
        String roleId = roleCode == null ? null : roles.get(roleCode);
        for (JsonNode h : G(ADM, "/api/v1/admin/habilitations?sujetType=" + sujetType + "&sujetId=" + sujetId).json()) {
            boolean memeRole = roleId == null ? h.path("roleId").isNull() : roleId.equals(h.path("roleId").asText());
            boolean memeNoeud = noeudId == null ? h.path("noeudId").isNull() : noeudId.equals(h.path("noeudId").asText());
            boolean memeDoc = documentId == null ? h.path("documentId").isNull() : documentId.equals(h.path("documentId").asText());
            if (memeRole && memeNoeud && memeDoc && h.path("ruptureHeritage").asBoolean() == rupture) return;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sujetType", sujetType);
        c.put("sujetId", sujetId);
        c.put("roleId", roleId);
        c.put("noeudId", noeudId);
        c.put("documentId", documentId);
        c.put("ruptureHeritage", rupture);
        Rep r = J(ADM, "POST", "/api/v1/admin/habilitations", c);
        if (r.code() / 100 != 2) throw new IllegalStateException("habilitation " + sujetId + " " + roleCode + " : " + r.code() + " " + r.corps());
    }

    static void preparerJeu() throws Exception {
        for (JsonNode r : G(ADM, "/api/v1/admin/roles").json()) roles.put(r.path("code").asText(), r.path("id").asText());
        // Provisionne les identités (première connexion) avant de leur donner des droits ;
        // qa2neuf1/2 restent jamais connectés pour F-11.
        for (String c : List.of(AGENT, DG, V1, V2, RH, SOUS, AUTRE, GRP)) me(c);

        FIN = noeud("QA2 Finance", "QA2-FIN", null, "METIER");
        FIN26 = noeud("QA2 Exercice 2026", "QA2-FIN-2026", FIN, null);
        FIN26F = noeud("QA2 Factures fournisseurs", "QA2-FIN-2026-FACT", FIN26, null);
        FINRES = noeud("QA2 Réservé direction", "QA2-FIN-RES", FIN, null);
        RHE = noeud("QA2 Ressources Humaines", "QA2-RH", null, "METIER");
        RHC = noeud("QA2 Contrats de travail", "QA2-RH-CTR", RHE, null);
        ECH = noeud("QA2 Échange marchés", "QA2-ECH", null, "ECHANGE");
        PRJ = noeud("QA2 Projets", "QA2-PRJ", null, "METIER");

        IDX_NUM = index("QA2_NUM", "Numéro de pièce", "TEXTE", null, true);
        IDX_MONTANT = index("QA2_MONTANT", "Montant TTC", "NOMBRE", null, false);
        IDX_DATE = index("QA2_DATE_ECH", "Date d'échéance", "DATE", null, false);
        IDX_PAYEE = index("QA2_PAYEE", "Payée", "BOOLEEN", null, false);
        IDX_STATUT = index("QA2_STATUT", "Statut", "LISTE", "Brouillon,Définitif,Annulé", false);
        JsonNode plan = trouver(contenu(G(ADM, "/api/v1/plan-indexations?size=500").json()), "code", "QA2-PLAN-FACT");
        if (plan == null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2-PLAN-FACT");
            c.put("nomDuPlan", "QA2 Fiche facture");
            c.put("modeIndexation", false);
            c.put("manuel", true);
            c.put("majuscule", true);
            c.put("separateur", "_");
            c.put("indexIds", List.of(IDX_NUM, IDX_MONTANT, IDX_DATE, IDX_PAYEE, IDX_STATUT));
            c.put("charteIds", List.of());
            PLAN = idDe(J(ADM, "POST", "/api/v1/plan-indexations", c), "plan");
        } else {
            PLAN = plan.path("id").asText();
        }
        T_FACT = type("QA2-FACT", "QA2 Facture fournisseur", FIN26F, PLAN, List.of("pdf", "png", "docx"), 20, 120, "PUBLIC");
        T_NOTE = type("QA2-NOTE", "QA2 Note interne", FIN26, null, List.of("pdf", "txt", "png", "docx"), 20, 12, "PUBLIC");
        T_CONTRAT = type("QA2-CTR", "QA2 Contrat de travail", RHC, null, List.of("pdf"), 20, 600, "PRIVE");
        T_COURRIER = type("QA2-COUR", "QA2 Courrier entrant", FIN, null, List.of("pdf", "png"), 20, 60, "PUBLIC");
        T_FACT_RES = type("QA2-RES", "QA2 Note réservée", FINRES, null, List.of("pdf", "txt"), 20, 12, "PUBLIC");

        // Rôle composé « Responsable RH » (§3.2 : composition sans développement).
        JsonNode rrh = null;
        for (JsonNode r : G(ADM, "/api/v1/admin/roles").json()) if ("QA2_RESP_RH".equals(r.path("code").asText())) rrh = r;
        if (rrh == null) {
            ROLE_RH = idDe(J(ADM, "POST", "/api/v1/admin/roles", Map.of("code", "QA2_RESP_RH", "libelle", "QA2 Responsable RH",
                    "permissions", List.of("CONSULTER", "DEPOSER", "VALIDER", "VOIR_PRIVE"))), "rôle Responsable RH");
        } else {
            ROLE_RH = rrh.path("id").asText();
        }
        roles.put("QA2_RESP_RH", ROLE_RH);

        // Groupe GED (dimension « rôle organisationnel ») : kelfassi y est membre, LECTEUR sur la Finance.
        JsonNode grp = trouver(contenu(G(ADM, "/api/v1/access-groups?size=500").json()), "code", "QA2-GRP-AUDIT");
        if (grp == null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2-GRP-AUDIT");
            c.put("name", "QA2 Auditeurs internes");
            c.put("workspaceIds", List.of());
            c.put("userIds", List.of(emp(GRP)));
            GROUPE = idDe(J(ADM, "POST", "/api/v1/access-groups", c), "groupe");
        } else {
            GROUPE = grp.path("id").asText();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2-GRP-AUDIT");
            c.put("name", "QA2 Auditeurs internes");
            c.put("workspaceIds", List.of());
            c.put("userIds", List.of(emp(GRP)));
            J(ADM, "PUT", "/api/v1/access-groups/" + GROUPE, c);
        }

        habiliter("UTILISATEUR", uid(AGENT), "AGENT_ARCHIVE", FIN, null, false);
        habiliter("UTILISATEUR", uid(AGENT), "AGENT_ARCHIVE", RHE, null, false);
        habiliter("UTILISATEUR", uid(AGENT), "AGENT_ARCHIVE", ECH, null, false);
        habiliter("UTILISATEUR", uid(DG), "DIRECTION_GENERALE", null, null, false);
        habiliter("UTILISATEUR", uid(V1), "UTILISATEUR_STANDARD", FIN, null, false);
        habiliter("UTILISATEUR", uid(V2), "UTILISATEUR_STANDARD", FIN, null, false);
        habiliter("UTILISATEUR", uid(V1), "LECTEUR", FINRES, null, true); // rupture : restreint à la lecture
        habiliter("UTILISATEUR", uid(V2), "UTILISATEUR_STANDARD", ECH, null, false);
        habiliter("UTILISATEUR", uid(RH), "QA2_RESP_RH", RHE, null, false);
        habiliter("UTILISATEUR", uid(SOUS), "UTILISATEUR_STANDARD", FIN26, null, false);
        habiliter("UTILISATEUR", uid(AUTRE), "UTILISATEUR_STANDARD", PRJ, null, false);
        habiliter("GROUPE", GROUPE, "LECTEUR", FIN, null, false);
    }

    // ================================================================== PARTIE A : F-01 à F-20

    static void partieA() throws Exception {
        info("---- Partie A : principes, habilitations, réception (F-01 à F-20)");

        // F-01 (§2.2) : paramétrage sans code des types, métadonnées, circuits, durées, habilitations.
        String m1 = M.toUpperCase();
        Rep i1 = J(ADM, "POST", "/api/v1/indices", Map.of("code", "QA2_P_" + m1, "nomIndex", "Param " + M, "fieldType", "BOOLEEN",
                "obligatoire", false, "indexePourRecherche", true, "indexDeGroupage", false));
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("code", "QA2-P-" + m1);
        ct.put("typeDeDocument", "Param " + M);
        ct.put("description", "Type créé par l'API d'administration");
        ct.put("workspaceId", FIN);
        ct.put("typeAutorise", List.of("pdf"));
        ct.put("tailleMaxMo", 5);
        ct.put("dureeConservationMois", 36);
        ct.put("pointDepart", "DATE_DEPOT");
        ct.put("confidentialiteDefaut", "PRIVE");
        Rep t1 = J(ADM, "POST", "/api/v1/type-documents", ct);
        Rep w1 = J(ADM, "POST", "/api/v1/workflow/regles", Map.of("name", "Param " + M,
                "steps", List.of(Map.of("label", "Contrôle", "employeId", emp(V1), "stepOrder", 1))));
        Rep rt = t1.code() / 100 == 2 && w1.code() / 100 == 2
                ? J(ADM, "PUT", "/api/v1/workflow/types/" + t1.json().path("id").asText() + "/regle", Map.of("regleId", w1.json().path("id").asText()))
                : null;
        Rep r1 = J(ADM, "POST", "/api/v1/admin/roles", Map.of("code", "QA2_P_" + m1, "libelle", "Param " + M, "permissions", List.of("CONSULTER")));
        verif("F-01", i1.code() / 100 == 2 && t1.code() / 100 == 2 && t1.json().path("dureeConservationMois").asInt() == 36
                        && w1.code() / 100 == 2 && rt != null && rt.code() / 100 == 2 && r1.code() / 100 == 2,
                "Paramétrage sans code : index booléen, type avec durée de conservation et confidentialité par défaut, règle de workflow rattachée au type, rôle",
                "index " + court(i1) + ", type " + court(t1) + " (durée " + t1.json().path("dureeConservationMois") + " mois), règle " + court(w1)
                        + ", rattachement au type " + (rt == null ? "-" : court(rt)) + ", rôle " + court(r1));

        // F-02 (§2.4) : typologie administrable (six catégories du CPS).
        List<String> cats = List.of("Administratif", "RH", "Financier", "Juridique", "Courrier", "Technique");
        int crees = 0;
        StringBuilder det = new StringBuilder();
        for (String c : cats) {
            String code = "QA2-CAT-" + c.toUpperCase();
            String id = type(code, "QA2 " + c, FIN, null, List.of("pdf"), 10, null, "PUBLIC");
            if (id != null && !id.isBlank()) crees++;
        }
        JsonNode maj = null;
        JsonNode tadm = trouver(contenu(G(ADM, "/api/v1/type-documents?size=500").json()), "code", "QA2-CAT-ADMINISTRATIF");
        if (tadm != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2-CAT-ADMINISTRATIF");
            c.put("typeDeDocument", "QA2 Administratif (note, décision)");
            c.put("description", "Modifié " + M);
            c.put("workspaceId", FIN);
            c.put("typeAutorise", List.of("pdf", "docx"));
            c.put("tailleMaxMo", 10);
            c.put("pointDepart", "DATE_DOCUMENT");
            c.put("confidentialiteDefaut", "PUBLIC");
            maj = J(ADM, "PUT", "/api/v1/type-documents/" + tadm.path("id").asText(), c).json();
        }
        Rep refus = J(V1, "POST", "/api/v1/type-documents", Map.of("code", "QA2-X-" + m1, "typeDeDocument", "x", "description", "x",
                "workspaceId", FIN, "typeAutorise", List.of("pdf")));
        verif("F-02", crees == 6 && maj != null && maj.path("description").asText().contains(M) && refus.code() == 403,
                "Typologie des six catégories créée et modifiée par l'Administrateur ; refusée à un utilisateur standard",
                crees + "/6 types, modification " + (maj != null) + ", standard " + court(refus));

        // F-05 (§5) côté API : les permissions exposées au front suivent le profil.
        Set<String> pV1 = new LinkedHashSet<>();
        me(V1).path("permissions").forEach(p -> pV1.add(p.asText()));
        Set<String> pA = new LinkedHashSet<>();
        me(ADM).path("permissions").forEach(p -> pA.add(p.asText()));
        Rep hab403 = G(V1, "/api/v1/admin/habilitations?sujetType=UTILISATEUR&sujetId=" + uid(V1));
        Rep audit403 = G(V1, "/api/v1/audit/evenements");
        Rep cles403 = G(V1, "/api/v1/applications");
        verif("F-05a", pA.contains("GERER_ROLES_HABILITATIONS") && !pV1.contains("GERER_ROLES_HABILITATIONS") && !pV1.contains("CONSULTER_AUDIT")
                        && hab403.code() == 403 && audit403.code() == 403 && cles403.code() == 403,
                "Profil connecté : permissions d'administration absentes du profil standard, fonctions d'administration refusées (403)",
                "standard : " + pV1 + " ; habilitations " + hab403.code() + ", audit " + audit403.code() + ", clés " + cles403.code());

        // F-06 (§3.1) : trois dimensions — profil (rôle), rattachement organisationnel (groupe, direction), périmètre.
        String dFin = doc(V1, "pdf_texte_fr_facture.pdf", "qa2-f06-" + M, T_NOTE);
        boolean groupeVoit = visibles(GRP, "qa2-f06-" + M).contains(dFin);
        boolean autreVoitPas = !visibles(AUTRE, "qa2-f06-" + M).contains(dFin);
        Rep grpDepot = deposer(GRP, "pdf_texte_fr_facture.pdf", "qa2-f06g-" + M, T_NOTE, null, null);
        JsonNode de = G(ADM, "/api/v1/admin/droits-effectifs?utilisateurId=" + uid(GRP) + "&noeudId=" + FIN26).json();
        boolean viaGroupe = de.path("origines").toString().contains("QA2 Auditeurs");
        verif("F-06", groupeVoit && autreVoitPas && grpDepot.code() == 403 && viaGroupe && !me(V1).path("direction").asText().isBlank(),
                "Profil × rattachement (groupe GED, direction lue dans l'annuaire) × périmètre : droits obtenus par le groupe, limités au rôle du groupe",
                "membre du groupe voit " + groupeVoit + " et dépôt " + court(grpDepot) + " (LECTEUR), autre périmètre ne voit pas " + autreVoitPas
                        + ", origine « groupe » " + viaGroupe + ", direction « " + me(V1).path("direction").asText() + " »");

        // F-07 (§3.1.1) : trois niveaux, héritage, rupture (le plus spécifique), document isolé, union.
        String dRacine = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-f07-racine-" + M, T_COURRIER);    // QA2 Finance
        String dEnfant = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-f07-enfant-" + M, T_FACT);       // Finance/2026/Factures
        String dRes = doc(ADM, "note_texte_brut.txt", "qa2-f07-res-" + M, T_FACT_RES);             // Finance/Réservé (rupture pour V1)
        String dIsole = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-f07-isole-" + M, T_COURRIER);
        habiliter("UTILISATEUR", uid(AUTRE), "LECTEUR", null, dIsole, false);
        Set<String> vSous = visibles(SOUS, "qa2-f07-"), vAutre = visibles(AUTRE, "qa2-f07-");
        Rep depRes = deposer(V1, "note_texte_brut.txt", "qa2-f07-v1res-" + M, T_FACT_RES, null, null);
        Rep depFin = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f07-v1fin-" + M, T_COURRIER, null, null);
        boolean v1LitRes = visibles(V1, "qa2-f07-res-" + M).contains(dRes);
        verif("F-07a", vSous.contains(dEnfant) && !vSous.contains(dRacine),
                "Héritage : une habilitation sur un dossier couvre sa sous-arborescence, pas le niveau supérieur",
                "sous-dossier visible " + vSous.contains(dEnfant) + ", racine de l'espace visible " + vSous.contains(dRacine));
        verif("F-07b", v1LitRes && depRes.code() == 403 && depFin.code() / 100 == 2,
                "Rupture d'héritage : le périmètre le plus spécifique (lecture seule) prévaut sur l'héritage (standard)",
                "lecture " + v1LitRes + ", dépôt sous rupture " + court(depRes) + ", dépôt hors rupture " + court(depFin));
        verif("F-07c", vAutre.contains(dIsole) && !vAutre.contains(dRacine),
                "Document isolé : une habilitation sur un seul fichier le rend visible, et lui seul",
                "isolé visible " + vAutre.contains(dIsole) + ", voisin visible " + vAutre.contains(dRacine));

        // F-08 (§3.2) : quatre rôles, composables, cumulables.
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode r : G(ADM, "/api/v1/admin/roles").json()) codes.add(r.path("code").asText());
        String dRh = doc(RH, "pdf_texte_fr_facture.pdf", "qa2-f08-rh-" + M, T_CONTRAT);
        habiliter("UTILISATEUR", uid(V1), "LECTEUR", RHE, null, false);
        Set<String> vV1 = visibles(V1, "qa2-f0");
        boolean cumul = vV1.contains(dFin);
        Rep v1rh = G(V1, "/api/v1/documents/" + dRh);
        Set<String> rolesV1 = new LinkedHashSet<>();
        for (JsonNode h : G(ADM, "/api/v1/admin/habilitations?sujetType=UTILISATEUR&sujetId=" + uid(V1)).json()) rolesV1.add(h.path("roleCode").asText());
        verif("F-08", codes.containsAll(List.of("UTILISATEUR_STANDARD", "AGENT_ARCHIVE", "ADMINISTRATEUR", "DIRECTION_GENERALE", "QA2_RESP_RH"))
                        && rolesV1.size() >= 2 && cumul,
                "Quatre rôles système, un rôle « Responsable RH » composé sans développement, cumul de rôles sur un même utilisateur",
                "rôles " + codes + " ; rôles cumulés de qa2val1 " + rolesV1 + " ; contrat PRIVE déposé par le Responsable RH vu par un lecteur : " + v1rh.code()
                        + " (règle de confidentialité)");

        // F-09 (§3.2.1) : neuf permissions élémentaires et composition des rôles du §3.2.
        Set<String> elem = new LinkedHashSet<>();
        for (JsonNode p : G(ADM, "/api/v1/admin/permissions").json())
            if ("ELEMENTAIRE".equals(p.path("categorie").asText())) elem.add(p.path("code").asText());
        List<String> neuf = List.of("CONSULTER", "DEPOSER", "MODIFIER", "VALIDER", "DIFFUSER", "DEPLACER", "ARCHIVER", "SUPPRIMER", "PURGER");
        verif("F-09a", elem.containsAll(neuf) && elem.size() == 9, "Neuf permissions élémentaires au catalogue", elem.toString());
        Map<String, Set<String>> compo = new HashMap<>();
        Map<String, Boolean> global = new HashMap<>();
        for (JsonNode r : G(ADM, "/api/v1/admin/roles").json()) {
            Set<String> s = new LinkedHashSet<>();
            r.path("permissions").forEach(p -> s.add(p.asText()));
            compo.put(r.path("code").asText(), s);
            global.put(r.path("code").asText(), r.path("accesGlobal").asBoolean());
        }
        Set<String> manqueAgent = new LinkedHashSet<>(neuf);
        manqueAgent.removeAll(compo.getOrDefault("AGENT_ARCHIVE", Set.of()));
        verif("F-09b", manqueAgent.isEmpty(), "Agent d'archive : les neuf permissions (§3.2 : « consulter, déposer, modifier, valider, diffuser, déplacer, archiver, supprimer et purger »)",
                "composition livrée " + compo.get("AGENT_ARCHIVE") + " ; manquent " + manqueAgent);
        Set<String> dgStruct = new LinkedHashSet<>(compo.getOrDefault("DIRECTION_GENERALE", Set.of()));
        dgStruct.retainAll(List.of("DEPLACER", "ARCHIVER", "SUPPRIMER", "PURGER"));
        boolean dgAdmin = compo.getOrDefault("DIRECTION_GENERALE", Set.of()).stream().anyMatch(p -> p.startsWith("GERER_") || p.equals("ADMINISTRER_INDEX") || p.equals("SUPERVISER_TRAITEMENTS"));
        res("F-09c", dgStruct.isEmpty() && !dgAdmin && global.getOrDefault("DIRECTION_GENERALE", false) ? "OK" : "AVERT",
                "Direction Générale : accès global, sans administration technique ni « permissions de structuration » (§3.2)",
                "accès global " + global.get("DIRECTION_GENERALE") + ", administration " + dgAdmin + ", structuration portée " + dgStruct);
        Set<String> std = compo.getOrDefault("UTILISATEUR_STANDARD", Set.of());
        verif("F-09d", std.containsAll(List.of("CONSULTER", "DEPOSER", "VALIDER")),
                "Utilisateur standard : Consulter, Déposer, Valider sur son périmètre", std.toString());

        // F-10 (§3.3) : authentification par l'annuaire (simulé ici), refus d'un mauvais secret, identifiant AD (D2).
        Rep okLogin = login(V2, mdp);
        Rep koLogin = login(V2, "mauvais-" + M);
        Rep mailLogin = login(V2 + "@marchica.ma", mdp);
        verif("F-10", okLogin.code() == 200 && koLogin.code() == 401 && mailLogin.code() / 100 == 4,
                "Authentification LDAP : compte AD accepté, mauvais mot de passe refusé, connexion par e-mail refusée (D2) [annuaire SIMULÉ]",
                "valide " + okLogin.code() + ", mauvais secret " + court(koLogin) + ", adresse e-mail " + court(mailLogin));

        // F-11 (§3.3, §3.3.1) : pas de création manuelle ; identité créée sans rôle à la 1re connexion ; rôle attribué ensuite.
        Rep creation = J(ADM, "POST", "/api/v1/admin/utilisateurs", Map.of("identifiant", "fantome", "fullName", "Fantôme"));
        // Tour 4 : qa2neuf1 est connu (et habilité) depuis la première exécution ; chaque exécution
        // ajoute à l'annuaire simulé un compte neuf (qa2n<marqueur>) pour rejouer la 1re connexion.
        String cptNeuf = NEUF1;
        try {
            LdapSimule.ajouter("qa2n" + M, "Nouveau", "Compte " + M);
            cptNeuf = "qa2n" + M;
        } catch (Exception e) {
            info("F-11 : compte neuf non ajouté à l'annuaire simulé (" + e.getMessage() + "), repli sur " + NEUF1);
        }
        boolean dejaConnu = false;
        for (JsonNode u : G(ADM, "/api/v1/admin/utilisateurs").json()) dejaConnu |= cptNeuf.equalsIgnoreCase(u.path("identifiant").asText());
        JsonNode m0 = me(cptNeuf);
        boolean connuApres = false;
        for (JsonNode u : G(ADM, "/api/v1/admin/utilisateurs").json()) connuApres |= cptNeuf.equalsIgnoreCase(u.path("identifiant").asText());
        boolean sansRole = m0.path("roles").size() == 0 && m0.path("permissions").size() == 0;
        Rep listeSansRole = G(cptNeuf, "/api/v1/documents?size=5");
        habiliter("UTILISATEUR", m0.path("id").asText(), "UTILISATEUR_STANDARD", PRJ, null, false);
        moi.remove(cptNeuf);
        JsonNode m1b = me(cptNeuf);
        Rep listeApres = G(cptNeuf, "/api/v1/documents?size=5");
        boolean cptNeufVrai = !cptNeuf.equals(NEUF1);
        verif("F-11", creation.code() / 100 != 2 && connuApres && (dejaConnu || sansRole)
                        && (!cptNeufVrai || (!dejaConnu && sansRole && listeSansRole.code() == 403 && listeApres.code() == 200))
                        && m1b.path("roles").toString().contains("UTILISATEUR_STANDARD"),
                "Aucun écran ni API de création de compte ; identité créée à la première connexion, sans rôle ; rôle attribué manuellement, effet immédiat",
                "POST /admin/utilisateurs " + creation.code() + ", compte " + cptNeuf + " connu avant 1re connexion " + dejaConnu + ", après " + connuApres
                        + ", sans rôle à la création " + sansRole + " (liste des documents " + court(listeSansRole) + "), rôles après attribution "
                        + m1b.path("roles") + " (liste des documents " + listeApres.code() + ")");

        // F-12 (§3.3.1, D1) : désactivation d'un compte AD.
        f12();

        // F-13 (§3.4) : clés d'API — générer, consulter, révoquer, régénérer, périmètre, délégation, double identité.
        f13();

        // F-14 (§3.5) : modification des droits sans intervention sur le code, effet immédiat.
        String dF14 = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-f14-" + M, T_COURRIER);
        boolean avant = visibles(NEUF2 + "", "qa2-f14-" + M).contains(dF14);
        Map<String, Object> hb = new LinkedHashMap<>();
        hb.put("sujetType", "UTILISATEUR");
        hb.put("sujetId", uid(NEUF2));
        hb.put("roleId", roles.get("LECTEUR"));
        hb.put("noeudId", FIN);
        hb.put("ruptureHeritage", false);
        Rep pose = J(ADM, "POST", "/api/v1/admin/habilitations", hb);
        boolean pendant = visibles(NEUF2, "qa2-f14-" + M).contains(dF14);
        Rep retrait = pose.code() / 100 == 2 ? X(ADM, "DELETE", "/api/v1/admin/habilitations/" + pose.json().path("id").asText()) : null;
        boolean apres = visibles(NEUF2, "qa2-f14-" + M).contains(dF14);
        Rep compo2 = J(ADM, "PUT", "/api/v1/admin/roles/" + ROLE_RH, Map.of("code", "QA2_RESP_RH", "libelle", "QA2 Responsable RH",
                "permissions", List.of("CONSULTER", "DEPOSER", "VALIDER", "VOIR_PRIVE", "MODIFIER")));
        moi.remove(RH);
        verif("F-14", !avant && pose.code() / 100 == 2 && pendant && retrait != null && retrait.code() / 100 == 2 && !apres && compo2.code() == 200,
                "Droits modifiés par l'Administrateur sans code : attribution et retrait d'une habilitation, recomposition d'un rôle, effet immédiat",
                "avant " + avant + ", attribution " + court(pose) + " → visible " + pendant + ", retrait " + (retrait == null ? "-" : court(retrait))
                        + " → visible " + apres + ", recomposition du rôle " + court(compo2));

        // F-15 (§3.6) : corbeille réversible dans tous les modules.
        f15();

        // F-16 (§4.1.3) : enregistrement unique et horodatage automatique.
        Rep d16a = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f16-" + M, T_NOTE, null, null);
        Rep d16b = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f16-" + M, T_NOTE, null, null);
        JsonNode j16 = d16a.json();
        verif("F-16", d16a.code() / 100 == 2 && d16b.code() / 100 == 2 && !j16.path("id").asText().equals(d16b.json().path("id").asText())
                        && !j16.path("createdAt").asText().isBlank(),
                "Chaque réception reçoit un identifiant unique et un horodatage automatique",
                "id " + j16.path("id").asText() + ", horodatage " + j16.path("createdAt").asText() + ", second dépôt identique → autre id");

        // F-17 (§4.1.3, §4.1.6) : source et déposant (dépôt manuel).
        verif("F-17a", "INTERFACE".equals(j16.path("canalDepot").asText()) && uid(V1).equals(j16.path("deposantUtilisateurId").asText())
                        && j16.path("applicationId").isNull(),
                "Dépôt manuel : canal INTERFACE, déposant = utilisateur authentifié",
                "canal " + j16.path("canalDepot") + ", déposant " + j16.path("deposantUtilisateurId") + " (" + j16.path("createdBy").asText() + ")");
        Rep d17 = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f17-" + M, T_NOTE, Map.of("createdById", uid(ADM)), null);
        verif("F-17b", uid(V1).equals(d17.json().path("deposantUtilisateurId").asText()),
                "Le déposant ne peut pas être usurpé par un paramètre de la requête", "déposant " + d17.json().path("deposantUtilisateurId"));

        // F-18 (§4.1.4) : rattachement obligatoire à une catégorie existante.
        Rep sansType = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f18-" + M, null, null, null);
        Rep typeInconnu = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f18-" + M, "00000000-0000-7000-8000-000000000000", null, null);
        String tJet = type("QA2-JET-" + m1, "QA2 Jetable " + M, FIN, null, List.of("pdf"), 5, null, "PUBLIC");
        X(ADM, "DELETE", "/api/v1/type-documents/" + tJet);
        Rep typeCorbeille = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f18-" + M, tJet, null, null);
        verif("F-18", sansType.code() == 400 && typeInconnu.code() / 100 == 4 && typeCorbeille.code() / 100 == 4,
                "Aucun document « hors catégorie » : type absent, inconnu ou en corbeille refusé",
                "sans type " + court(sansType) + ", inconnu " + court(typeInconnu) + ", en corbeille " + court(typeCorbeille));

        // F-19 (§4.1.4, §4.1.5) : bureau d'ordre par API REST sécurisée par clé d'API — voir f13 (F-19).

        // F-20 (§4.1.7) : trois issues du dépôt.
        String meta = "{\"QA2_NUM\":\"F-" + M + "\",\"QA2_MONTANT\":1250.5,\"QA2_DATE_ECH\":\"2026-10-31\",\"QA2_PAYEE\":false,\"QA2_STATUT\":\"Définitif\"}";
        Rep indexe = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f20-indexe-" + M, T_FACT, null, meta);
        Rep sansPlan = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f20-sansplan-" + M, T_NOTE, null, null);
        Rep aIndexer = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f20-aindexer-" + M, T_FACT, null, null);
        Rep reprise = null;
        if (aIndexer.code() / 100 == 2) {
            reprise = J(V1, "PUT", "/api/v1/indexation/documents/" + aIndexer.json().path("id").asText(),
                    Map.of("valeurs", List.of(Map.of("indexFieldId", IDX_NUM, "valeur", "R-" + M))));
        }
        JsonNode apresReprise = aIndexer.code() / 100 == 2 ? G(V1, "/api/v1/documents/" + aIndexer.json().path("id").asText()).json() : null;
        verif("F-20", "INDEXE".equals(indexe.json().path("statutIndexation").asText())
                        && "SANS_PLAN".equals(sansPlan.json().path("statutIndexation").asText())
                        && "A_INDEXER".equals(aIndexer.json().path("statutIndexation").asText())
                        && reprise != null && reprise.code() == 200 && apresReprise != null
                        && "INDEXE".equals(apresReprise.path("statutIndexation").asText()),
                "Trois issues du dépôt (indexé, sans plan, à indexer) ; le fichier « à indexer » est conservé et l'indexation reprise",
                "indexé " + indexe.json().path("statutIndexation") + ", sans plan " + sansPlan.json().path("statutIndexation")
                        + ", à indexer " + aIndexer.json().path("statutIndexation") + " → reprise " + (reprise == null ? "-" : court(reprise))
                        + " → " + (apresReprise == null ? "-" : apresReprise.path("statutIndexation")));
    }

    static void f12() throws Exception {
        // Le compte qa2parti est réactivé en début de scénario (rejouable), habilité, désigné nommément
        // validateur d'un circuit en cours, puis désactivé dans l'annuaire simulé.
        LdapSimule.uac(PARTI, 512);
        me(PARTI);
        habiliter("UTILISATEUR", uid(PARTI), "UTILISATEUR_STANDARD", PRJ, null, false);
        String tPrj = type("QA2-PRJ-NOTE", "QA2 Note de projet", PRJ, null, List.of("pdf"), 10, null, "PUBLIC");
        Rep regle = J(ADM, "POST", "/api/v1/workflow/regles", Map.of("name", "QA2 validation projet " + M,
                "steps", List.of(Map.of("label", "Chef de projet", "employeId", emp(PARTI), "stepOrder", 1))));
        J(ADM, "PUT", "/api/v1/workflow/noeuds/" + PRJ + "/regle", Map.of("regleId", regle.json().path("id").asText()));
        String d = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-f12-" + M, tPrj);
        JsonNode c = G(ADM, "/api/v1/workflow/documents/" + d + "/circuits").json().path(0);
        LdapSimule.uac(PARTI, 514);
        Rep login = login(PARTI, mdp);
        Rep ancien = G(PARTI, "/api/v1/documents?size=1"); // jeton obtenu avant la désactivation
        JsonNode anomalies = G(ADM, "/api/v1/workflow/anomalies").json();
        boolean signale = anomalies.toString().contains(d);
        Rep docVisible = G(ADM, "/api/v1/documents/" + d);
        verif("F-12a", login.code() == 401 && docVisible.code() == 200,
                "Compte désactivé dans l'AD : connexion refusée ; ses documents restent consultables selon les habilitations [annuaire SIMULÉ]",
                "connexion " + court(login) + ", document encore consultable " + docVisible.code());
        res("F-12b", signale ? "OK" : "AVERT",
                "Validation en cours d'un validateur désactivé signalée à l'Administrateur pour réattribution (§3.3.1-3) ; D1 : la GED ne relit plus l'état AD",
                "circuit " + c.path("statut").asText() + ", listé dans /workflow/anomalies : " + signale + " ; session déjà ouverte : "
                        + ancien.code() + " (R26 : valable jusqu'à expiration)");
        LdapSimule.uac(PARTI, 512);
    }

    static void f13() throws Exception {
        String codeApp = "bo"; // reconnu comme bureau d'ordre (GED_DEPOT_APPLICATIONS_BUREAU_ORDRE)
        JsonNode app = null;
        for (JsonNode a : G(ADM, "/api/v1/applications").json()) if (codeApp.equals(a.path("code").asText())) app = a;
        if (app == null) {
            // La délégation exige une liste d'adresses autorisées (poste de recette : boucle locale).
            app = J(ADM, "POST", "/api/v1/applications", Map.of("code", codeApp, "nom", "Bureau d'ordre digital (recette qa2)",
                    "description", "Application tierce MMED", "adressesAutorisees", List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"),
                    "quotaMinute", 600, "quotaJour", 100000)).json();
            if (app.path("id").isMissingNode()) throw new IllegalStateException("application : " + app);
        }
        String appId = app.path("id").asText();
        Rep gen = J(ADM, "POST", "/api/v1/applications/" + appId + "/cles", Map.of("delegation", true, "validiteJours", 30));
        String cle = gen.json().path("cle").asText();
        String cleId = gen.json().path("details").path("id").asText();
        Rep portee = J(ADM, "PUT", "/api/v1/cles-api/" + cleId + "/portee", Map.of("portee", List.of(
                Map.of("noeudId", FIN, "operations", List.of("DEPOT", "CONSULTATION", "RECHERCHE")))));
        JsonNode consult = G(ADM, "/api/v1/applications/" + appId).json();
        boolean secretMasque = !consult.toString().contains(cle);
        verif("F-13a", gen.code() / 100 == 2 && !cle.isBlank() && portee.code() == 200 && secretMasque,
                "Générer une clé, lui associer un périmètre fonctionnel et documentaire, la consulter (secret jamais réaffiché)",
                "génération " + court(gen) + ", portée " + court(portee) + ", consultation sans secret " + secretMasque);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-API-Key", cle);
        h.put("Idempotency-Key", java.util.UUID.randomUUID().toString());
        byte[] pdf = Files.readAllBytes(donnees.resolve("scan_fr_courrier.pdf"));
        Rep bo = g.deposer(h, pdf, "qa2-f19-courrier-" + M + ".pdf", "application/pdf", Map.of("name", "qa2-f19-courrier-" + M, "typeDocumentId", T_COURRIER));
        h.put("Idempotency-Key", java.util.UUID.randomUUID().toString());
        h.put("X-On-Behalf-Of", V1);
        Rep deleg = g.deposer(h, pdf, "qa2-f19-deleg-" + M + ".pdf", "application/pdf", Map.of("name", "qa2-f19-deleg-" + M, "typeDocumentId", T_COURRIER));
        h.remove("X-On-Behalf-Of");
        h.put("Idempotency-Key", java.util.UUID.randomUUID().toString());
        Rep horsPortee = g.deposer(h, pdf, "qa2-f19-hors-" + M + ".pdf", "application/pdf", Map.of("name", "qa2-f19-hors-" + M, "typeDocumentId", T_CONTRAT));
        Map<String, String> sansCle = new LinkedHashMap<>();
        sansCle.put("Idempotency-Key", java.util.UUID.randomUUID().toString());
        Rep anonyme = g.deposer(sansCle, pdf, "x.pdf", "application/pdf", Map.of("name", "x", "typeDocumentId", T_COURRIER));
        JsonNode jbo = bo.json(), jd = deleg.json();
        verif("F-19", bo.code() / 100 == 2 && "BUREAU_ORDRE".equals(jbo.path("canalDepot").asText()) && appId.equals(jbo.path("applicationId").asText())
                        && anonyme.code() == 401,
                "Bureau d'ordre digital : dépôt par API REST sécurisée par clé, source identifiée, rattaché à la catégorie « Courrier entrant »",
                "dépôt " + court(bo) + ", canal " + jbo.path("canalDepot") + ", application " + jbo.path("applicationId") + ", type "
                        + jbo.path("typeDocument").path("label") + ", statut OCR " + jbo.path("statutOcr") + " ; sans clé " + court(anonyme));
        String uV1 = uid(V1);
        boolean doubleId = deleg.code() / 100 == 2 && auditContient("objetId=" + jd.path("id").asText(),
                l -> !l.path("acteurApplicationId").isNull() && uV1.equals(l.path("acteurUtilisateurId").asText()));
        verif("F-13b", deleg.code() / 100 == 2 && jd.path("depotDelegue").asBoolean() && uid(V1).equals(jd.path("deposantUtilisateurId").asText())
                        && doubleId && horsPortee.code() / 100 == 4,
                "Délégation d'identité (déposant nominatif) et double identité au journal ; hors périmètre de la clé refusé",
                "délégué " + court(deleg) + " déposant " + jd.path("deposantUtilisateurId") + ", audit application + personne " + doubleId
                        + ", hors portée " + court(horsPortee));

        Rep regen = J(ADM, "POST", "/api/v1/cles-api/" + cleId + "/regeneration", Map.of());
        String cle2 = regen.json().path("cle").asText();
        String cle2Id = regen.json().path("details").path("id").asText();
        Map<String, String> h1 = new LinkedHashMap<>(Map.of("X-API-Key", cle));
        Map<String, String> h2 = new LinkedHashMap<>(Map.of("X-API-Key", cle2));
        Rep ancienne = g.appel("GET", "/api/v1/documents/" + jbo.path("id").asText(), h1, null, null);
        Rep nouvelle = g.appel("GET", "/api/v1/documents/" + jbo.path("id").asText(), h2, null, null);
        // Régénération avec chevauchement (DAT §5.4) : l'ancienne clé reste valide le temps de la bascule ;
        // on la révoque ensuite explicitement, puis la nouvelle.
        Rep revocAncienne = J(ADM, "POST", "/api/v1/cles-api/" + cleId + "/revocation", Map.of("motif", "Bascule terminée " + M));
        Rep ancienneApres = g.appel("GET", "/api/v1/documents/" + jbo.path("id").asText(), h1, null, null);
        Rep revoc = J(ADM, "POST", "/api/v1/cles-api/" + cle2Id + "/revocation", Map.of("motif", "Recette qa2 " + M));
        Rep apresRevoc = g.appel("GET", "/api/v1/documents/" + jbo.path("id").asText(), h2, null, null);
        verif("F-13c", regen.code() / 100 == 2 && nouvelle.code() == 200 && revocAncienne.code() / 100 == 2 && ancienneApres.code() == 401
                        && revoc.code() / 100 == 2 && apresRevoc.code() == 401,
                "Régénérer (nouvelle clé active, chevauchement de l'ancienne) puis révoquer : une clé révoquée cesse immédiatement de fonctionner",
                "régénération " + court(regen) + ", nouvelle clé " + nouvelle.code() + ", ancienne pendant le chevauchement " + ancienne.code()
                        + ", révocation de l'ancienne " + court(revocAncienne) + " → " + court(ancienneApres) + ", révocation de la nouvelle "
                        + court(revoc) + " → " + court(apresRevoc));
        // Clé active pour la suite (parties B à D).
        cleActive = J(ADM, "POST", "/api/v1/applications/" + appId + "/cles", Map.of("delegation", true, "validiteJours", 30)).json();
        J(ADM, "PUT", "/api/v1/cles-api/" + cleActive.path("details").path("id").asText() + "/portee", Map.of("portee", List.of(
                Map.of("noeudId", FIN, "operations", List.of("DEPOT", "CONSULTATION", "RECHERCHE", "VERSEMENT")))));
    }

    static JsonNode cleActive;

    /**
     * Clé d'API active de l'application « bo » (portée : QA2 Finance, dépôt, consultation,
     * recherche, versement), générée au besoin quand la partie A n'a pas tourné.
     */
    static String cleApplication() throws Exception {
        if (cleActive != null) return cleActive.path("cle").asText();
        JsonNode app = null;
        for (JsonNode a : G(ADM, "/api/v1/applications").json()) if ("bo".equals(a.path("code").asText())) app = a;
        if (app == null) {
            app = J(ADM, "POST", "/api/v1/applications", Map.of("code", "bo", "nom", "Bureau d'ordre digital (recette qa2)",
                    "description", "Application tierce MMED", "adressesAutorisees", List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"),
                    "quotaMinute", 600, "quotaJour", 100000)).json();
        }
        cleActive = J(ADM, "POST", "/api/v1/applications/" + app.path("id").asText() + "/cles", Map.of("delegation", true, "validiteJours", 30)).json();
        J(ADM, "PUT", "/api/v1/cles-api/" + cleActive.path("details").path("id").asText() + "/portee", Map.of("portee", List.of(
                Map.of("noeudId", FIN, "operations", List.of("DEPOT", "CONSULTATION", "RECHERCHE", "VERSEMENT")))));
        return cleActive.path("cle").asText();
    }

    static void f15() throws Exception {
        List<String> ko = new ArrayList<>();
        StringBuilder det = new StringBuilder();
        // document
        String d = doc(V1, "pdf_texte_fr_facture.pdf", "qa2-f15-" + M, T_NOTE);
        Rep sup = X(AGENT, "DELETE", "/api/v1/documents/" + d); // Supprimer : Agent d'archive (l'utilisateur standard ne l'a pas)
        boolean enCorbeille = G(ADM, "/api/v1/documents/trashed?size=200&search=" + enc("qa2-f15-" + M)).corps().contains(d);
        Rep rest = X(AGENT, "PATCH", "/api/v1/documents/" + d + "/restore");
        boolean revenu = visibles(V1, "qa2-f15-" + M).contains(d);
        det.append("document ").append(sup.code()).append("/corbeille ").append(enCorbeille).append("/restauré ").append(revenu);
        if (!(sup.code() / 100 == 2 && enCorbeille && rest.code() / 100 == 2 && revenu)) ko.add("document");
        // référentiels
        String m1 = M.toUpperCase();
        Map<String, String> objets = new LinkedHashMap<>();
        objets.put("workspaces", noeud("QA2 Corbeille " + M, "QA2-CORB-" + m1, FIN, null));
        objets.put("type-documents", type("QA2-CORB-" + m1, "QA2 Corbeille " + M, FIN, null, List.of("pdf"), 5, null, "PUBLIC"));
        objets.put("indices", index("QA2_CORB_" + m1, "Corbeille " + M, "TEXTE", null, false));
        Map<String, Object> pl = new LinkedHashMap<>();
        pl.put("code", "QA2-CORB-" + m1);
        pl.put("nomDuPlan", "Corbeille " + M);
        pl.put("indexIds", List.of());
        pl.put("charteIds", List.of());
        objets.put("plan-indexations", idDe(J(ADM, "POST", "/api/v1/plan-indexations", pl), "plan jetable"));
        objets.put("workflow/regles", idDe(J(ADM, "POST", "/api/v1/workflow/regles", Map.of("name", "Corbeille " + M,
                "steps", List.of(Map.of("label", "x", "employeId", emp(V1), "stepOrder", 1)))), "règle jetable"));
        objets.put("etiquettes", idDe(J(ADM, "POST", "/api/v1/etiquettes", Map.of("code", "QA2-CORB-" + m1, "tag", "qa2-" + M, "couleur", "#1565c0")), "étiquette"));
        objets.put("access-groups", idDe(J(ADM, "POST", "/api/v1/access-groups", Map.of("code", "QA2-CORB-" + m1, "name", "Corbeille " + M,
                "workspaceIds", List.of(), "userIds", List.of())), "groupe jetable"));
        for (var e : objets.entrySet()) {
            Rep s = X(ADM, "DELETE", "/api/v1/" + e.getKey() + "/" + e.getValue());
            Rep lue = G(ADM, "/api/v1/" + e.getKey() + "/trashed?size=500");
            boolean dans = lue.corps().contains(e.getValue());
            Rep r = X(ADM, "PATCH", "/api/v1/" + e.getKey() + "/" + e.getValue() + "/restore");
            det.append(" ; ").append(e.getKey()).append(" ").append(s.code()).append("/").append(dans).append("/").append(r.code());
            if (!(s.code() / 100 == 2 && dans && r.code() / 100 == 2)) ko.add(e.getKey());
        }
        verif("F-15", ko.isEmpty(), "Corbeille réversible dans tous les modules : documents, espaces et dossiers, types, index, plans, règles de workflow, étiquettes, groupes",
                (ko.isEmpty() ? "" : "ÉCHEC sur " + ko + " — ") + det);
    }

    // ================================================================== programme

    public static void main(String[] args) throws Exception {
        g = new RecetteFonctionnelle(env("GED_URL", "http://localhost:18088"));
        mdp = env("GED_RECETTE_MOT_DE_PASSE", "dev-local-only");
        donnees = Path.of(env("GED_RECETTE_DONNEES", "recette/donnees"));
        M = "m" + Long.toString(System.currentTimeMillis(), 36);
        Set<String> parties = new LinkedHashSet<>(Arrays.asList(args));
        if (parties.isEmpty()) parties.addAll(List.of("A", "B", "C", "D"));
        info("cible " + g.url + " ; marqueur " + M + " ; parties " + parties);
        preparerJeu();
        info("jeu prêt : Finance " + FIN + ", RH " + RHE + ", échange " + ECH + ", types FACT " + T_FACT + " NOTE " + T_NOTE);
        if (parties.contains("A")) partieA();
        if (parties.contains("B")) PartieB.executer();
        if (parties.contains("C")) PartieC.executer();
        if (parties.contains("D")) PartieD.executer();
        if (parties.contains("G")) PartieG.executer(); // tour 6 : groupes par identité (T-025), sur demande
        System.exit(bilan("recette fonctionnelle " + parties));
    }
}
