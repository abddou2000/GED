import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Partie C de la recette fonctionnelle : recherche et consultation (§4.4), confidentialité
 * (§4.4.5), circuits de validation et diffusion (§4.5) — F-39 à F-54.
 *
 * <p>Chaque scénario est isolé ({@link #etape}) : une exception ne fait échouer que sa ligne.
 * Les validateurs qa2val1 et qa2val2 (Utilisateurs standard de QA2 Finance) valident des
 * documents déposés par l'Agent d'archive ; les règles de workflow sont créées pour
 * l'exécution et rattachées à des types ou des dossiers propres à l'exécution.
 */
public class PartieC extends RecetteFonctionnelle {

    PartieC() {
        super("");
    }

    interface Etape {
        void run() throws Exception;
    }

    /** Exécute un scénario ; une exception devient un ECHEC de la ligne, sans interrompre la suite. */
    static void etape(String id, Etape e) {
        try {
            e.run();
        } catch (Exception | Error x) {
            res(id, "ECHEC", "Scénario interrompu par une erreur", x.getClass().getSimpleName() + " : " + x.getMessage());
        }
    }

    static Map<String, String> champs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static Set<String> ids(JsonNode page) {
        Set<String> s = new LinkedHashSet<>();
        for (JsonNode d : contenu(page)) s.add(d.path("id").asText(d.path("documentId").asText()));
        return s;
    }

    /** Recherche sur métadonnées (POST /documents/recherche). */
    static JsonNode rechercher(String compte, Map<String, Object> requete) throws Exception {
        Rep r = J(compte, "POST", "/api/v1/documents/recherche", requete);
        if (r.code() != 200) throw new IllegalStateException("recherche " + court(r) + " " + r.corps());
        return r.json();
    }

    static Map<String, Object> critere(String code, String valeur, String de, String a) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", code);
        c.put("valeur", valeur);
        c.put("de", de);
        c.put("a", a);
        return c;
    }

    static JsonNode pleinTexteJ(String compte, String requete) throws Exception {
        return G(compte, "/api/v1/recherche/plein-texte?" + requete).json();
    }

    static String regle(String nom, List<Map<String, Object>> etapes) throws Exception {
        return idDe(J(ADM, "POST", "/api/v1/workflow/regles", Map.of("name", nom, "steps", etapes)), "règle " + nom);
    }

    static Map<String, Object> nomme(String libelle, String compte, int ordre) throws Exception {
        return Map.of("label", libelle, "employeId", emp(compte), "stepOrder", ordre);
    }

    static JsonNode circuit(String compte, String doc) throws Exception {
        return G(compte, "/api/v1/workflow/documents/" + doc + "/circuits").json().path(0);
    }

    static Rep decider(String compte, String circuitId, String decision, String motif) throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("decision", decision);
        c.put("motif", motif);
        return J(compte, "POST", "/api/v1/workflow/circuits/" + circuitId + "/decisions", c);
    }

    static boolean aTraiter(String compte, String doc) throws Exception {
        for (JsonNode a : contenu(G(compte, "/api/v1/workflow/a-traiter?size=100").json()))
            if (doc.equals(a.path("documentId").asText())) return true;
        return false;
    }

    static List<JsonNode> notifications(String compte) throws Exception {
        return contenu(G(compte, "/api/v1/notifications?size=100").json());
    }

    static boolean notifie(String compte, String type, String objetId) throws Exception {
        for (JsonNode n : notifications(compte))
            if (type.equals(n.path("type").asText()) && (objetId == null || objetId.equals(n.path("objetId").asText()))) return true;
        return false;
    }

    // Partagés entre scénarios
    static String c1, c2, c3, tWf, tWfN, dossierWf, rType, rNoeud, dVal, dRef, cVal, cRef;

    static void executer() throws Exception {
        info("---- Partie C : recherche, confidentialité, circuits de validation, diffusion (F-39 à F-54)");
        String m1 = M.toUpperCase();

        // Jeu de la recherche : trois factures indexées, objet et date du document renseignés.
        etape("F-39", () -> {
            c1 = idDe(deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-c-fact1-" + M, T_FACT,
                    champs("objet", "Maintenance ascenseurs " + M, "dateDocument", "2026-01-10"),
                    "{\"QA2_NUM\":\"C1-" + M + "\",\"QA2_MONTANT\":100,\"QA2_DATE_ECH\":\"2026-01-15\",\"QA2_PAYEE\":true,\"QA2_STATUT\":\"Définitif\"}"), "c1");
            c2 = idDe(deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-c-fact2-" + M, T_FACT,
                    champs("objet", "Travaux de voirie " + M, "dateDocument", "2026-05-10"),
                    "{\"QA2_NUM\":\"C2-" + M + "\",\"QA2_MONTANT\":500,\"QA2_DATE_ECH\":\"2026-06-15\",\"QA2_PAYEE\":false,\"QA2_STATUT\":\"Brouillon\"}"), "c2");
            c3 = idDe(deposer(V2, "pdf_texte_fr_facture.pdf", "qa2-c-fact3-" + M, T_FACT,
                    champs("objet", "Travaux de voirie phase 2 " + M, "dateDocument", "2026-09-01"),
                    "{\"QA2_NUM\":\"C3-" + M + "\",\"QA2_MONTANT\":900,\"QA2_DATE_ECH\":\"2026-11-15\",\"QA2_PAYEE\":true,\"QA2_STATUT\":\"Brouillon\"}"), "c3");
            // F-39 (§4.4.3) : critères issus des index, plages de nombres et de dates.
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("typeDocumentId", T_FACT);
            q.put("texte", M);
            q.put("criteres", List.of(critere("QA2_MONTANT", null, "200", "1000")));
            Set<String> montant = ids(rechercher(V1, q));
            q.put("criteres", List.of(critere("QA2_DATE_ECH", null, "2026-06-01", "2026-12-31")));
            Set<String> dates = ids(rechercher(V1, q));
            q.put("criteres", List.of(critere("QA2_STATUT", "Brouillon", null, null)));
            Set<String> liste = ids(rechercher(V1, q));
            q.put("criteres", List.of(critere("QA2_PAYEE", "true", null, null)));
            Set<String> bool = ids(rechercher(V1, q));
            q.put("criteres", List.of(critere("QA2_NUM", "c2-" + M, null, null)));
            Set<String> texte = ids(rechercher(V1, q));
            Set<String> attendu23 = Set.of(c2, c3);
            verif("F-39", montant.equals(attendu23) && dates.equals(attendu23) && liste.equals(attendu23) && bool.equals(Set.of(c1, c3))
                            && texte.equals(Set.of(c2)),
                    "Recherche multicritère sur les index : plage de nombres, plage de dates, liste, booléen, texte",
                    "montant 200-1000 → " + montant.size() + " (attendu c2, c3 : " + montant.equals(attendu23) + "), échéance juin-déc. → "
                            + dates.equals(attendu23) + ", statut Brouillon → " + liste.equals(attendu23) + ", payée → " + bool.equals(Set.of(c1, c3))
                            + ", numéro (casse ignorée) → " + texte.equals(Set.of(c2)));
        });

        // F-40 (§4.4.3) : critères imposés.
        etape("F-40", () -> {
            List<String> ok = new ArrayList<>(), ko = new ArrayList<>();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("typeDocumentId", T_FACT);
            q.put("texte", "qa2-c-fact");
            Set<String> type = ids(rechercher(V1, q));
            (type.containsAll(List.of(c1, c2, c3)) ? ok : ko).add("type");
            q.put("texte", "qa2-c-fact1-" + M);
            (ids(rechercher(V1, q)).equals(Set.of(c1)) ? ok : ko).add("nom");
            q.put("texte", "voirie phase 2 " + M);
            (ids(rechercher(V1, q)).equals(Set.of(c3)) ? ok : ko).add("objet");
            Map<String, Object> qe = new LinkedHashMap<>();
            qe.put("noeudId", FIN26F);
            qe.put("texte", M);
            Set<String> esp = ids(rechercher(V1, qe));
            (esp.containsAll(List.of(c1, c2, c3)) ? ok : ko).add("espace");
            String auj = java.time.LocalDate.now().toString();
            // Date de dépôt : critère de la recherche plein texte (et de son contrat d'API /recherches).
            Rep dep = J(V1, "POST", "/api/v1/recherches", Map.of("texte", "zarkolinet", "deposeDu", auj, "deposeAu", auj, "taille", 100));
            Rep depVide = J(V1, "POST", "/api/v1/recherches", Map.of("texte", "zarkolinet", "deposeDu", "2020-01-01", "deposeAu", "2020-12-31", "taille", 100));
            boolean depOk = dep.code() == 200 && dep.json().path("total").asLong() > 0 && depVide.code() == 200 && depVide.json().path("total").asLong() == 0;
            (depOk ? ok : ko).add("date de dépôt");
            // ANO-F-028 (tour 4) : la date de dépôt est aussi un critère de la recherche sur
            // métadonnées (dateDepotDu / dateDepotAu), sans terme, combinable avec la date du document.
            Map<String, Object> qdd = new LinkedHashMap<>();
            qdd.put("typeDocumentId", T_FACT);
            qdd.put("dateDepotDu", auj);
            qdd.put("dateDepotAu", auj);
            qdd.put("size", 200);
            Rep ddJour = J(V1, "POST", "/api/v1/documents/recherche", qdd);
            Map<String, Object> qddDoc = new LinkedHashMap<>(qdd);
            qddDoc.put("dateDocumentDu", "2026-09-01");
            qddDoc.put("dateDocumentAu", "2026-09-30");
            qddDoc.put("texte", M);
            Rep ddEtDoc = J(V1, "POST", "/api/v1/documents/recherche", qddDoc);
            Map<String, Object> qddVide = new LinkedHashMap<>(qdd);
            qddVide.put("dateDepotDu", "2020-01-01");
            qddVide.put("dateDepotAu", "2020-12-31");
            Rep ddVide = J(V1, "POST", "/api/v1/documents/recherche", qddVide);
            Map<String, Object> qddInv = new LinkedHashMap<>(qdd);
            qddInv.put("dateDepotDu", auj);
            qddInv.put("dateDepotAu", "2020-12-31");
            Rep ddInv = J(V1, "POST", "/api/v1/documents/recherche", qddInv);
            boolean ddOk = ddJour.code() == 200 && ids(ddJour.json()).containsAll(List.of(c1, c2, c3))
                    && ddEtDoc.code() == 200 && ids(ddEtDoc.json()).equals(Set.of(c3))
                    && ddVide.code() == 200 && ids(ddVide.json()).isEmpty() && ddInv.code() == 400
                    && ddJour.entetes().firstValue("GED-Champs-Ignores").isEmpty();
            (ddOk ? ok : ko).add("date de dépôt (recherche par index)");
            // P-08 (tour 4, ANO-F-011) : un champ ou paramètre inconnu est ignoré (même résultat)
            // et signalé par l'en-tête GED-Champs-Ignores, sur les trois API de recherche.
            Map<String, Object> qi = new LinkedHashMap<>();
            qi.put("typeDocumentId", T_FACT);
            qi.put("texte", M);
            Set<String> sansInconnu = ids(rechercher(V1, qi));
            qi.put("champInconnuQa2", "x");
            Rep riMeta = J(V1, "POST", "/api/v1/documents/recherche", qi);
            Rep riRech = J(V1, "POST", "/api/v1/recherches", Map.of("texte", "zarkolinet", "taille", 5, "filtreInconnuQa2", "x"));
            Rep riRechSans = J(V1, "POST", "/api/v1/recherches", Map.of("texte", "zarkolinet", "taille", 5));
            Rep riPt = G(V1, "/api/v1/recherche/plein-texte?q=zarkolinet&taille=5&parametreInconnuQa2=1");
            Rep riPtSans = G(V1, "/api/v1/recherche/plein-texte?q=zarkolinet&taille=5");
            String hMeta = riMeta.entetes().firstValue("GED-Champs-Ignores").orElse("");
            String hRech = riRech.entetes().firstValue("GED-Champs-Ignores").orElse("");
            String hPt = riPt.entetes().firstValue("GED-Champs-Ignores").orElse("");
            boolean inconnuOk = riMeta.code() == 200 && ids(riMeta.json()).equals(sansInconnu) && hMeta.contains("champInconnuQa2")
                    && riRech.code() == 200 && hRech.contains("filtreInconnuQa2")
                    && riRech.json().path("total").asLong() == riRechSans.json().path("total").asLong()
                    && riPt.code() == 200 && hPt.contains("parametreInconnuQa2")
                    && riPt.json().path("total").asLong() == riPtSans.json().path("total").asLong();
            info("F-40 date de dépôt (recherche par index) : jour " + court(ddJour) + " " + (ddJour.code() == 200 ? ids(ddJour.json()).size() : -1)
                    + ", ET date du document " + (ddEtDoc.code() == 200 ? ids(ddEtDoc.json()).size() : -1) + ", 2020 "
                    + (ddVide.code() == 200 ? ids(ddVide.json()).size() : -1) + ", plage inversée " + court(ddInv)
                    + " ; champs inconnus : métadonnées " + court(riMeta) + " [" + hMeta + "], /recherches " + court(riRech) + " [" + hRech
                    + "], plein texte " + court(riPt) + " [" + hPt + "]");
            (inconnuOk ? ok : ko).add("champ inconnu ignoré et signalé (P-08)");
            // Critères sans paramètre documenté : on vérifie qu'ils filtrent (sinon ils sont ignorés).
            StringBuilder ignores = new StringBuilder();
            Object[][] essais = {
                    {"date du document (plage)", Map.of("dateDocumentDu", "2026-09-01", "dateDocumentAu", "2026-09-30"), Set.of(c3)},
                    {"confidentialité", Map.of("confidentialite", "PRIVE"), Set.of()},
                    {"déposant", Map.of("deposantUtilisateurId", uid(V2)), Set.of(c3)}};
            for (Object[] e : essais) {
                Map<String, Object> qd = new LinkedHashMap<>();
                qd.put("texte", M);
                qd.put("typeDocumentId", T_FACT);
                @SuppressWarnings("unchecked") Map<String, Object> extra = (Map<String, Object>) e[1];
                qd.putAll(extra);
                Rep r = J(V1, "POST", "/api/v1/documents/recherche", qd);
                Set<String> apres = r.code() == 200 ? ids(r.json()) : Set.of("erreur " + r.code());
                (apres.equals(e[2]) ? ok : ko).add((String) e[0]);
                ignores.append(e[0]).append(" → ").append(r.code()).append(" ").append(apres.size()).append(" résultat(s) ; ");
            }
            verif("F-40", ko.isEmpty(),
                    "Critères imposés : type, date du document, nom, objet, confidentialité, espace, déposant, date de dépôt",
                    "disponibles " + ok + " ; absents des API de recherche " + ko + " (" + ignores + "attendu 1, 0, 1 sur 3 factures)");
        });

        // F-41 (§4.4.3) : plein texte, extraits mis en évidence.
        etape("F-41", () -> {
            JsonNode p = pleinTexteJ(V1, "q=" + enc("zarkolinet") + "&taille=5");
            boolean surligne = false;
            String extrait = "";
            for (JsonNode r : p.path("resultats")) {
                for (JsonNode s : r.path("extrait")) {
                    if (s.path("surligne").asBoolean() && s.path("texte").asText().toLowerCase().contains("zarkolinet")) {
                        surligne = true;
                        StringBuilder b = new StringBuilder();
                        for (JsonNode s2 : r.path("extrait")) b.append(s2.path("surligne").asBoolean() ? "[" + s2.path("texte").asText() + "]" : s2.path("texte").asText());
                        extrait = b.toString();
                    }
                }
                if (surligne) break;
            }
            verif("F-41", p.path("total").asLong() > 0 && surligne,
                    "Recherche plein texte avec extraits où le terme cherché est mis en évidence",
                    "total " + p.path("total") + ", extrait « " + (extrait.length() > 160 ? extrait.substring(0, 160) + "…" : extrait) + " »");
        });

        // F-42 (§4.4.3) : critères combinés en ET.
        etape("F-42", () -> {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("typeDocumentId", T_FACT);
            q.put("texte", M);
            q.put("criteres", List.of(critere("QA2_PAYEE", "true", null, null), critere("QA2_MONTANT", null, "200", "1000")));
            Set<String> et = ids(rechercher(V1, q));
            q.put("criteres", List.of(critere("QA2_PAYEE", "false", null, null), critere("QA2_MONTANT", null, "800", "1000")));
            Set<String> vide = ids(rechercher(V1, q));
            q.put("texte", "voirie phase 2 " + M);
            q.put("criteres", List.of(critere("QA2_STATUT", "Brouillon", null, null)));
            Set<String> texteEtListe = ids(rechercher(V1, q));
            JsonNode ptType = pleinTexteJ(V1, "q=zarkolinet&taille=100&typeDocumentId=" + T_FACT);
            JsonNode ptTous = pleinTexteJ(V1, "q=zarkolinet&taille=100");
            boolean ptEt = true;
            for (JsonNode r : ptType.path("resultats")) ptEt &= "QA2 Facture fournisseur".equals(r.path("typeDocument").asText());
            verif("F-42", et.equals(Set.of(c3)) && vide.isEmpty() && texteEtListe.equals(Set.of(c3)) && ptEt
                            && ptType.path("total").asLong() < ptTous.path("total").asLong(),
                    "Critères combinés en ET logique (métadonnées, et plein texte + filtres)",
                    "type ET payée ET 200-1000 → " + et.size() + " (c3 " + et.contains(c3) + "), non payée ET 800-1000 → " + vide.size()
                            + ", objet « voirie phase 2 » ET Brouillon → " + texteEtListe.size()
                            + " ; plein texte « zarkolinet » " + ptTous.path("total") + " → avec type Facture " + ptType.path("total"));
        });

        // F-43 (§4.4.3) : filtrage par habilitation à la source, compteurs compris.
        etape("F-43", () -> {
            // Quatre documents de l'exécution, à des emplacements différents :
            // Finance (racine), Factures 2026, Réservé direction, Projets.
            String n = "qa2-c-perim-" + M;
            doc(ADM, "pdf_texte_fr_facture.pdf", n + "-fin", T_COURRIER);
            doc(ADM, "pdf_texte_fr_facture.pdf", n + "-fact", T_FACT);
            doc(ADM, "note_texte_brut.txt", n + "-res", T_FACT_RES);
            doc(ADM, "pdf_texte_fr_facture.pdf", n + "-prj", type("QA2-PRJ-NOTE", "QA2 Note de projet", PRJ, null, List.of("pdf"), 10, null, "PUBLIC"));
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("texte", n);
            q.put("size", 2); // total indépendant de la page
            Map<String, Long> totaux = new LinkedHashMap<>();
            for (String c : List.of(ADM, V1, SOUS, AUTRE)) totaux.put(c, rechercher(c, q).path("total").asLong());
            // Plein texte : pour chacun, total = nombre de résultats, tous consultables.
            StringBuilder pt = new StringBuilder();
            boolean ptOk = true;
            for (String c : List.of(ADM, V2, SOUS, AUTRE)) {
                // Toutes les pages : le jeu cumulé des exécutions dépasse 100 résultats.
                JsonNode p = pleinTexteJ(c, "q=zarkolinet&taille=100");
                List<JsonNode> tous = new ArrayList<>();
                for (int page = 0; page < 20; page++) {
                    JsonNode pg = page == 0 ? p : pleinTexteJ(c, "q=zarkolinet&taille=100&page=" + page);
                    pg.path("resultats").forEach(tous::add);
                    if (pg.path("resultats").size() < 100) break;
                }
                int n2 = tous.size(), lisibles = 0;
                for (JsonNode r : tous) if (G(c, "/api/v1/documents/" + r.path("documentId").asText()).code() == 200) lisibles++;
                ptOk &= p.path("total").asLong() == n2 && lisibles == n2;
                pt.append(c).append(" ").append(p.path("total")).append(" (consultables ").append(lisibles).append(") ; ");
            }
            verif("F-43", totaux.get(ADM) == 4 && totaux.get(V1) == 3 && totaux.get(SOUS) == 1 && totaux.get(AUTRE) == 1 && ptOk,
                    "Filtrage par habilitation à la source : résultats ET compteurs limités au périmètre de chacun",
                    "4 documents placés en Finance, Factures 2026, Réservé direction, Projets → totaux " + totaux + " (attendu 4, 3, 1, 1) ; plein texte « zarkolinet » : " + pt);
        });

        // F-44 (§4.4.3) : pagination, tri, documents non OCRisés signalés.
        etape("F-44", () -> {
            JsonNode p0 = pleinTexteJ(V1, "q=zarkolinet&taille=1&page=0&tri=NOM"), p1 = pleinTexteJ(V1, "q=zarkolinet&taille=1&page=1&tri=NOM");
            String n0 = p0.path("resultats").path(0).path("nom").asText(), n1 = p1.path("resultats").path(0).path("nom").asText();
            boolean pagine = p0.path("total").asLong() >= 2 && !n0.equals(n1) && n0.compareToIgnoreCase(n1) <= 0;
            JsonNode l0 = G(V1, "/api/v1/documents?size=2&page=0&search=" + enc("qa2-c-fact") + "&sortBy=name&sortDir=asc").json();
            JsonNode l1 = G(V1, "/api/v1/documents?size=2&page=0&search=" + enc("qa2-c-fact") + "&sortBy=name&sortDir=desc").json();
            boolean liste = contenu(l0).size() == 2 && !contenu(l0).get(0).path("id").asText().equals(contenu(l1).get(0).path("id").asText());
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("texte", M);
            q.put("size", 2);
            q.put("page", 1);
            JsonNode rm = rechercher(V1, q);
            boolean metaPagine = rm.path("page").asInt(rm.path("number").asInt(-1)) == 1 && contenu(rm).size() <= 2;
            // Signalement des documents non OCRisés : statut OCR dans la liste et les résultats.
            boolean signale = contenu(G(V1, "/api/v1/documents?size=5&search=" + enc("qa2-c-fact")).json()).get(0).has("statutOcr");
            boolean signalePt = p0.path("resultats").path(0).has("statutOcr");
            verif("F-44", pagine && liste && metaPagine && signale,
                    "Résultats paginés et triables ; documents non OCRisés signalés",
                    "plein texte page 0 « " + n0 + " », page 1 « " + n1 + " » (tri NOM, total " + p0.path("total") + ") ; liste triée nom asc/desc " + liste
                            + " ; recherche sur métadonnées paginée " + metaPagine + " (ordre imposé : date du document décroissante) ; statut OCR dans la liste "
                            + signale + ", dans les résultats plein texte " + signalePt);
        });

        // F-45 (§4.4.5) : trois niveaux de confidentialité.
        etape("F-45", () -> {
            String pub = idDe(deposer(ADM, "pdf_texte_fr_facture.pdf", "qa2-c-conf-pub-" + M, T_NOTE, champs("confidentialite", "PUBLIC"), null), "public");
            String pri = idDe(deposer(ADM, "pdf_texte_fr_facture.pdf", "qa2-c-conf-pri-" + M, T_NOTE, champs("confidentialite", "PRIVE"), null), "privé");
            String con = idDe(deposer(ADM, "pdf_texte_fr_facture.pdf", "qa2-c-conf-con-" + M, T_NOTE, champs("confidentialite", "CONFIDENTIEL"), null), "confidentiel");
            Set<String> vV1 = visibles(V1, "qa2-c-conf-"), vAg = visibles(AGENT, "qa2-c-conf-"), vDg = visibles(DG, "qa2-c-conf-");
            Rep des = J(ADM, "POST", "/api/v1/documents/" + con + "/designes", Map.of("utilisateurId", uid(V1)));
            Set<String> vV1b = visibles(V1, "qa2-c-conf-");
            Rep ficheCon = G(V2, "/api/v1/documents/" + con);
            // Le déposant d'un document confidentiel le voit-il ?
            Rep propre = deposer(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-conf-agent-" + M, T_NOTE, champs("confidentialite", "CONFIDENTIEL"), null);
            boolean deposantVoit = propre.code() / 100 == 2 && G(AGENT, "/api/v1/documents/" + propre.json().path("id").asText()).code() == 200;
            verif("F-45", vV1.contains(pub) && !vV1.contains(pri) && !vV1.contains(con) && vAg.contains(pri) && !vAg.contains(con)
                            && des.code() / 100 == 2 && vV1b.contains(con) && ficheCon.code() == 404,
                    "Trois niveaux Public, Privé, Confidentiel croisés avec les habilitations (Voir privé, désignation nominative)",
                    "standard : public " + vV1.contains(pub) + ", privé " + vV1.contains(pri) + ", confidentiel " + vV1.contains(con)
                            + " ; Agent d'archive (Voir privé) : privé " + vAg.contains(pri) + ", confidentiel " + vAg.contains(con)
                            + " ; DG : " + vDg.size() + "/3 ; désignation " + court(des) + " → confidentiel visible du désigné " + vV1b.contains(con)
                            + ", fiche pour un non-désigné " + court(ficheCon) + " ; déposant d'un confidentiel le consulte " + deposantVoit);
        });

        // ---- Circuits de validation (F-46 à F-54)
        etape("F-46", () -> {
            rType = regle("QA2 Circuit facture " + M, List.of(nomme("Contrôle de gestion", V1, 1), nomme("Comptabilité", V2, 2)));
            tWf = type("QA2-WF-" + m1, "QA2 Pièce à valider " + M, FIN26, null, List.of("pdf"), 10, null, "PUBLIC");
            Rep rt = J(ADM, "PUT", "/api/v1/workflow/types/" + tWf + "/regle", Map.of("regleId", rType));
            dossierWf = idDe(J(ADM, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 Dossier validé " + M)), "dossier");
            rNoeud = regle("QA2 Circuit dossier " + M, List.of(nomme("Visa", V2, 1)));
            Rep rn = J(ADM, "PUT", "/api/v1/workflow/noeuds/" + dossierWf + "/regle", Map.of("regleId", rNoeud));
            tWfN = type("QA2-WFN-" + m1, "QA2 Pièce du dossier validé " + M, dossierWf, null, List.of("pdf"), 10, null, "PUBLIC");
            dVal = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-wf-val-" + M, tWf);
            String dNoeud = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-wf-noeud-" + M, tWfN);
            // Type à règle déposé dans le dossier à règle : la règle du type prime.
            String tWfTN = type("QA2-WFTN-" + m1, "QA2 Pièce typée du dossier " + M, dossierWf, null, List.of("pdf"), 10, null, "PUBLIC");
            J(ADM, "PUT", "/api/v1/workflow/types/" + tWfTN + "/regle", Map.of("regleId", rType));
            String dPrime = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-wf-prime-" + M, tWfTN);
            JsonNode cv = circuit(AGENT, dVal), cn = circuit(AGENT, dNoeud), cp = circuit(AGENT, dPrime);
            JsonNode origine = G(AGENT, "/api/v1/workflow/documents/" + dNoeud + "/regle").json();
            cVal = cv.path("id").asText();
            verif("F-46", rt.code() / 100 == 2 && rn.code() / 100 == 2 && rType.equals(cv.path("regleId").asText()) && rNoeud.equals(cn.path("regleId").asText())
                            && rType.equals(cp.path("regleId").asText()) && "EN_COURS".equals(cv.path("statut").asText()),
                    "Circuit paramétrable par type de document, ou rattaché à un dossier ; ouvert au dépôt",
                    "rattachement au type " + court(rt) + ", au dossier " + court(rn) + " ; dépôt du type → circuit « " + cv.path("regle").asText() + " » "
                            + cv.path("statut").asText() + " ; dépôt dans le dossier → « " + cn.path("regle").asText() + " » (origine " + origine.path("origine").asText()
                            + ") ; type à règle dans dossier à règle → « " + cp.path("regle").asText() + " »");
        });

        etape("F-47", () -> {
            boolean v1Recoit = aTraiter(V1, dVal), v2Recoit = aTraiter(V2, dVal);
            // Le validateur affiché en second décide le premier.
            Rep d2 = decider(V2, cVal, "VALIDE", "Conforme au bon de commande");
            JsonNode apres2 = d2.json();
            Rep d1 = decider(V1, cVal, "VALIDE", null);
            JsonNode fin = d1.json();
            verif("F-47", v1Recoit && v2Recoit && d2.code() / 100 == 2 && "EN_COURS".equals(apres2.path("statut").asText()) && d1.code() / 100 == 2
                            && "VALIDE".equals(fin.path("statut").asText()),
                    "Validateurs indépendants, sans ordre imposé : tous sollicités au dépôt, décisions dans n'importe quel ordre (D7)",
                    "à traiter : qa2val1 " + v1Recoit + ", qa2val2 " + v2Recoit + " ; décision du 2e affiché d'abord " + court(d2) + " → "
                            + apres2.path("statut").asText() + " ; puis du 1er " + court(d1) + " → " + fin.path("statut").asText());
        });

        etape("F-48", () -> {
            dRef = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-wf-ref-" + M, tWf);
            cRef = circuit(AGENT, dRef).path("id").asText();
            Rep sansMotif = decider(V1, cRef, "REFUSE", null);
            Rep motifVide = decider(V1, cRef, "REFUSE", "   ");
            Rep refus = decider(V1, cRef, "REFUSE", "Montant incohérent avec le devis " + M);
            verif("F-48", sansMotif.code() == 400 && motifVide.code() == 400 && refus.code() / 100 == 2 && "REFUSE".equals(refus.json().path("statut").asText()),
                    "Valider ou refuser ; motif obligatoire au refus",
                    "refus sans motif " + court(sansMotif) + ", motif blanc " + court(motifVide) + ", refus motivé " + court(refus) + " → "
                            + refus.json().path("statut").asText());
        });

        etape("F-49", () -> {
            Rep v = PartieD.verser(AGENT, dVal, "pdf_texte_fr_convention.pdf", "Version corrigée " + M);
            JsonNode c = circuit(AGENT, dVal);
            int caduques = 0;
            for (JsonNode d : c.path("decisions")) if (d.path("caduque").asBoolean()) caduques++;
            boolean redemande = aTraiter(V1, dVal) && aTraiter(V2, dVal);
            // Annulation par un validateur de sa propre décision antérieure.
            Rep val1 = decider(V1, cVal, "VALIDE", null);
            Rep annule = decider(V1, cVal, "ANNULEE", "Décision rendue par erreur");
            String etatV1 = "";
            for (JsonNode x : annule.json().path("validateurs")) if (emp(V1).equals(x.path("employeId").asText())) etatV1 = x.path("etat").asText();
            verif("F-49", v.code() / 100 == 2 && "EN_COURS".equals(c.path("statut").asText()) && caduques >= 2 && redemande
                            && val1.code() / 100 == 2 && annule.code() / 100 == 2 && "EN_ATTENTE".equals(etatV1),
                    "Nouvelle version : les validations antérieures deviennent caduques et sont redemandées ; un validateur peut annuler sa décision",
                    "versement " + court(v) + " → circuit " + c.path("statut").asText() + " (version " + c.path("versionCouranteNumero") + "), décisions caduques "
                            + caduques + ", redemandé aux deux " + redemande + " ; validation puis annulation " + court(annule) + " → état " + etatV1);
        });

        etape("F-50", () -> {
            JsonNode c = G(ADM, "/api/v1/workflow/circuits/" + cRef).json();
            JsonNode d = null;
            for (JsonNode x : c.path("decisions")) if ("REFUSE".equals(x.path("decision").asText())) d = x;
            String u1 = uid(V1);
            boolean audite = auditContient("objetId=" + dRef, l -> l.path("action").asText().equals("VALIDATION_REJETEE")
                    && u1.equals(l.path("acteurUtilisateurId").asText()));
            boolean nominatif = d != null && !d.path("auteur").asText().isBlank() && !d.path("le").asText().isBlank()
                    && d.path("motif").asText().contains(M);
            verif("F-50", nominatif && audite,
                    "Décision tracée nominativement, horodatée et motivée (circuit et journal d'audit)",
                    d == null ? "aucune décision REFUSE" : "auteur « " + d.path("auteur").asText() + " », le " + d.path("le").asText() + ", motif « "
                            + d.path("motif").asText() + " », version " + d.path("versionNumero") + " ; audit VALIDATION_REJETEE par qa2val1 " + audite);
        });

        etape("F-51", () -> {
            attendre(3000);
            boolean v1 = notifie(V1, "CIRCUIT_OUVERT", null), v2 = notifie(V2, "CIRCUIT_OUVERT", null);
            boolean dep = notifie(AGENT, "CIRCUIT_DECISION", null);
            String dossier = env("GED_RECETTE_MAILS", "");
            int mailsV1 = 0, mailsAgent = 0;
            if (!dossier.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Path.of(dossier))) {
                try (var s = java.nio.file.Files.list(java.nio.file.Path.of(dossier))) {
                    for (java.nio.file.Path f : s.toList()) {
                        String t = java.nio.file.Files.readString(f);
                        if (!t.contains(M)) continue;
                        if (t.contains("qa2val1@")) mailsV1++;
                        if (t.contains("qa2agent@")) mailsAgent++;
                    }
                }
            }
            verif("F-51", v1 && v2 && dep && (dossier.isBlank() || (mailsV1 > 0 && mailsAgent > 0)),
                    "Notification des validateurs (ouverture) et du déposant (décision), dans l'application et par courriel [SMTP SIMULÉ]",
                    "validateurs notifiés " + v1 + "/" + v2 + ", déposant notifié de la décision " + dep + " ; courriels de l'exécution : qa2val1 " + mailsV1
                            + ", qa2agent " + mailsAgent + (dossier.isBlank() ? " (dossier du SMTP simulé non fourni)" : ""));
        });

        etape("F-52", () -> {
            // dVal redevient VALIDE (nouvelle version validée par les deux), puis diffusion.
            decider(V1, cVal, "VALIDE", null);
            decider(V2, cVal, "VALIDE", null);
            String statut = circuit(AGENT, dVal).path("statut").asText();
            boolean avant = G(AUTRE, "/api/v1/documents/" + dVal).code() == 200;
            Rep parAgent = J(AGENT, "POST", "/api/v1/workflow/documents/" + dVal + "/diffusion", Map.of("utilisateurIds", List.of(uid(AUTRE))));
            Rep diff = J(V1, "POST", "/api/v1/workflow/documents/" + dVal + "/diffusion", Map.of("utilisateurIds", List.of(uid(AUTRE)), "groupeIds", List.of(GROUPE)));
            boolean apres = G(AUTRE, "/api/v1/documents/" + dVal).code() == 200;
            Rep ecriture = J(AUTRE, "PUT", "/api/v1/documents/" + dVal, Map.of("objet", "tentative " + M));
            Rep refuse = J(V1, "POST", "/api/v1/workflow/documents/" + dRef + "/diffusion", Map.of("utilisateurIds", List.of(uid(AUTRE))));
            verif("F-52", "VALIDE".equals(statut) && !avant && parAgent.code() == 200 && diff.code() == 200 && apres && ecriture.code() / 100 == 4 && refuse.code() == 409,
                    "Diffusion d'un document validé à un périmètre (personnes, groupes) : lecture accordée sans copie ; document non validé non diffusable",
                    "circuit " + statut + ", nidrissi avant " + avant + ", diffusion par un standard " + court(diff) + " (" + diff.json().path("habilitationsPosees")
                            + " habilitations) → lecture " + apres + ", écriture " + court(ecriture) + " ; document refusé " + court(refuse)
                            + " ; diffusion par l'Agent d'archive " + court(parAgent) + " (Diffuser, ANO-F-001)");
        });

        etape("F-53", () -> {
            Rep maj = J(ADM, "PUT", "/api/v1/workflow/regles/" + rType, Map.of("name", "QA2 Circuit facture " + M,
                    "steps", List.of(nomme("Direction", DG, 1))));
            JsonNode enCours = circuit(AGENT, dRef);
            Set<String> avant = textes(enCours.path("validateurs"), "employeId");
            String nouveau = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-wf-apres-" + M, tWf);
            Set<String> apres = textes(circuit(AGENT, nouveau).path("validateurs"), "employeId");
            verif("F-53", maj.code() == 200 && avant.equals(Set.of(emp(V1), emp(V2))) && apres.equals(Set.of(emp(DG))),
                    "Règle modifiable ; circuit figé par document (les circuits ouverts gardent leurs validateurs)",
                    "modification de la règle " + court(maj) + " ; circuit ouvert avant : " + avant.size() + " validateurs inchangés "
                            + avant.equals(Set.of(emp(V1), emp(V2))) + " ; dépôt suivant : validateur DG seul " + apres.equals(Set.of(emp(DG))));
        });

        etape("F-54", () -> {
            Map<String, Object> parRole = new LinkedHashMap<>();
            parRole.put("label", "Un utilisateur standard de la Finance");
            parRole.put("roleId", roles.get("UTILISATEUR_STANDARD"));
            parRole.put("perimetreNoeudId", FIN);
            parRole.put("stepOrder", 1);
            String r = regle("QA2 Circuit par rôle " + M, List.of(parRole));
            String t = type("QA2-WFR-" + m1, "QA2 Pièce validée par rôle " + M, FIN26, null, List.of("pdf"), 10, null, "PUBLIC");
            J(ADM, "PUT", "/api/v1/workflow/types/" + t + "/regle", Map.of("regleId", r));
            String d = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-c-wf-role-" + M, t);
            JsonNode c = circuit(AGENT, d);
            String typeV = c.path("validateurs").path(0).path("type").asText();
            boolean v1 = aTraiter(V1, d), v2 = aTraiter(V2, d), autre = aTraiter(AUTRE, d);
            Rep dec = decider(V2, c.path("id").asText(), "VALIDE", null);
            verif("F-54", "ROLE".equals(typeV) && v1 && v2 && !autre && dec.code() / 100 == 2 && "VALIDE".equals(dec.json().path("statut").asText()),
                    "Validateur désigné nommément (F-46) ou par rôle sur un périmètre : tout porteur du rôle peut décider",
                    "validateur de type " + typeV + " (" + c.path("validateurs").path(0).path("roleCode").asText() + "), à traiter : qa2val1 " + v1 + ", qa2val2 " + v2
                            + ", hors périmètre " + autre + " ; décision de qa2val2 " + court(dec) + " → " + dec.json().path("statut").asText());
        });
    }
}
