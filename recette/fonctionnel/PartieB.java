import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Partie B de la recette fonctionnelle : OCR, métadonnées, référentiel d'index, gouvernance des
 * types (§4.2), classement et arborescence (§4.3) — F-21 à F-38.
 */
public class PartieB extends RecetteFonctionnelle {

    PartieB() {
        super("");
    }

    /** Attend la fin de l'OCR d'un document (OCR_TERMINE ou OCR_ECHEC), 5 minutes au plus. */
    static JsonNode attendreOcr(String compte, String doc) throws Exception {
        JsonNode j = null;
        for (int i = 0; i < 150; i++) {
            j = G(compte, "/api/v1/documents/" + doc).json();
            String s = j.path("statutOcr").asText();
            if (s.equals("OCR_TERMINE") || s.equals("OCR_ECHEC") || j.path("statutOcr").isNull()) return j;
            attendre(2000);
        }
        return j;
    }

    static Set<String> pleinTexte(String compte, String q) throws Exception {
        // Toutes les pages : le jeu n'est jamais supprimé, les exécutions successives
        // dépassent une page de 100 résultats (tour 2 : 141 documents « zarkolinet »).
        Set<String> ids = new LinkedHashSet<>();
        for (int page = 0; page < 20; page++) {
            JsonNode rep = G(compte, "/api/v1/recherche/plein-texte?taille=100&page=" + page + "&q=" + enc(q)).json();
            for (JsonNode r : rep.path("resultats")) ids.add(r.path("documentId").asText());
            if (rep.path("resultats").size() < 100) break;
        }
        return ids;
    }

    /** PDF multipage : trois fois le scan français puis le scan arabe. */
    static byte[] scanMultipage() throws Exception {
        PDFMergerUtility fusion = new PDFMergerUtility();
        try (PDDocument cible = new PDDocument()) {
            for (String f : List.of("scan_fr_courrier.pdf", "scan_fr_courrier.pdf", "scan_fr_courrier.pdf", "scan_ar_courrier.pdf")) {
                try (PDDocument s = Loader.loadPDF(Files.readAllBytes(donnees.resolve(f)))) {
                    fusion.appendDocument(cible, s);
                }
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            cible.save(o);
            return o.toByteArray();
        }
    }

    static void executer() throws Exception {
        info("---- Partie B : OCR, métadonnées, classement (F-21 à F-38)");
        String m1 = M.toUpperCase();
        String cle = cleApplication();

        // F-21 (§4.2.1) : moteur Tesseract.
        JsonNode etat = G(ADM, "/api/v1/ocr/etat").json();
        verif("F-21", etat.path("actif").asBoolean() && etat.path("moteurDisponible").asBoolean()
                        && etat.path("languesInstallees").toString().contains("fra") && etat.path("languesInstallees").toString().contains("ara"),
                "Moteur OCR open source Tesseract intégré et opérationnel", "état " + etat);

        // Scans déposés d'abord : l'OCR tourne pendant les autres contrôles.
        String dScanFr = doc(V1, "scan_fr_courrier.pdf", "qa2-f23-scanfr-" + M, T_FACT);
        String dScanAr = doc(V1, "scan_ar_courrier.pdf", "qa2-f26-scanar-" + M, T_NOTE);
        String dPdfAr = doc(V1, "pdf_texte_ar_courrier.pdf", "qa2-f26-pdfar-" + M, T_NOTE);
        Rep rMulti = g.deposer(jeton(V1), scanMultipage(), "qa2-f25-multi-" + M + ".pdf", "application/pdf",
                Map.of("name", "qa2-f25-multi-" + M, "typeDocumentId", T_NOTE), null);
        String dMulti = idDe(rMulti, "dépôt multipage");
        String dScanRh = doc(RH, "scan_fr_courrier.pdf", "qa2-f23-scanrh-" + M, T_CONTRAT);

        // F-27 (§4.2.3) : socle commun de métadonnées.
        Rep r27 = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f27-" + M, T_NOTE,
                Map.of("objet", "Facture de maintenance " + M, "dateDocument", "2026-03-15", "confidentialite", "PRIVE"), null);
        JsonNode j27 = r27.json();
        Rep modif27 = null;
        if (r27.code() / 100 == 2) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", "qa2-f27-" + M);
            c.put("typeDocumentId", T_NOTE);
            c.put("objet", "Objet corrigé " + M);
            c.put("dateDocument", "2026-03-16");
            c.put("confidentialite", "PRIVE");
            modif27 = J(V1, "PUT", "/api/v1/documents/" + j27.path("id").asText(), c);
        }
        boolean socle = !j27.path("id").asText().isBlank() && j27.path("name").asText().startsWith("qa2-f27")
                && ("Facture de maintenance " + M).equals(j27.path("objet").asText()) && !j27.path("typeDocument").isNull()
                && "2026-03-15".equals(j27.path("dateDocument").asText()) && !j27.path("createdAt").asText().isBlank()
                && !j27.path("deposantUtilisateurId").asText().isBlank() && "PRIVE".equals(j27.path("confidentialite").asText())
                && "2027-03-15".equals(j27.path("echeanceConservation").asText());
        verif("F-27", socle && modif27 != null && modif27.code() == 200 && ("Objet corrigé " + M).equals(modif27.json().path("objet").asText()),
                "Socle : identifiant, nom, objet, type, date du document, date de dépôt, déposant, confidentialité, durée de conservation déduite du type",
                "objet " + j27.path("objet") + ", date du document " + j27.path("dateDocument") + ", dépôt " + j27.path("createdAt")
                        + ", confidentialité " + j27.path("confidentialite") + ", échéance " + j27.path("echeanceConservation")
                        + " (type : 12 mois) ; correction objet/date " + (modif27 == null ? "-" : court(modif27)));

        // F-28 (§4.2.3) : métadonnées additionnelles paramétrables.
        String idxPrio = index("QA2_PRIO", "Priorité QA2", "LISTE", "Basse,Normale,Haute", false);
        JsonNode ip = G(ADM, "/api/v1/indices/" + idxPrio).json();
        if (ip.path("valeurParDefaut").isNull() || ip.path("valeurParDefaut").asText().isBlank()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2_PRIO");
            c.put("nomIndex", "Priorité QA2");
            c.put("fieldType", "LISTE");
            c.put("valeurs", "Basse,Normale,Haute");
            c.put("valeurParDefaut", "Normale");
            c.put("obligatoire", false);
            c.put("indexePourRecherche", true);
            c.put("indexDeGroupage", false);
            J(ADM, "PUT", "/api/v1/indices/" + idxPrio, c);
        }
        String planF28;
        JsonNode p28 = trouver(contenu(G(ADM, "/api/v1/plan-indexations?size=500").json()), "code", "QA2-PLAN-F28");
        if (p28 == null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2-PLAN-F28");
            c.put("nomDuPlan", "QA2 Plan métadonnées");
            c.put("modeIndexation", false);
            c.put("manuel", true);
            c.put("majuscule", false);
            c.put("separateur", "_");
            c.put("indexIds", List.of(IDX_NUM, IDX_MONTANT, IDX_DATE, IDX_PAYEE, IDX_STATUT, idxPrio));
            c.put("charteIds", List.of());
            planF28 = idDe(J(ADM, "POST", "/api/v1/plan-indexations", c), "plan F-28");
        } else {
            planF28 = p28.path("id").asText();
        }
        String tF28 = type("QA2-F28", "QA2 Pièce indexée", FIN26, planF28, List.of("pdf"), 10, null, "PUBLIC");
        Rep manque = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f28a-" + M, tF28, null, "{\"QA2_MONTANT\":10}");
        Rep listeKo = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f28b-" + M, tF28, null, "{\"QA2_NUM\":\"X\",\"QA2_STATUT\":\"Inconnu\"}");
        Rep nombreKo = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f28c-" + M, tF28, null, "{\"QA2_NUM\":\"X\",\"QA2_MONTANT\":\"douze\"}");
        Rep bon = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f28d-" + M, tF28, null,
                "{\"QA2_NUM\":\"N-" + M + "\",\"QA2_MONTANT\":99.5,\"QA2_DATE_ECH\":\"2026-12-01\",\"QA2_PAYEE\":true,\"QA2_STATUT\":\"Brouillon\"}");
        JsonNode meta = bon.json().path("metadonnees");
        verif("F-28", manque.code() == 400 && listeKo.code() == 400 && nombreKo.code() == 400 && bon.code() / 100 == 2
                        && meta.path("QA2_PAYEE").asBoolean() && "Normale".equals(meta.path("QA2_PRIO").asText())
                        && meta.path("QA2_MONTANT").asDouble() == 99.5,
                "Métadonnées additionnelles par type : texte, nombre, date, liste, booléen ; obligatoire, valeur par défaut, contrôle de nature",
                "obligatoire manquant " + court(manque) + ", valeur hors liste " + court(listeKo) + ", nombre invalide " + court(nombreKo)
                        + ", dépôt complet " + court(bon) + " → " + meta);

        // F-29 (§4.2.5) : référentiel d'index, plan, charte de nommage.
        String planCh;
        JsonNode pch = trouver(contenu(G(ADM, "/api/v1/plan-indexations?size=500").json()), "code", "QA2-PLAN-CHARTE");
        if (pch == null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", "QA2-PLAN-CHARTE");
            c.put("nomDuPlan", "QA2 Charte facture");
            c.put("modeIndexation", false);
            c.put("manuel", true);
            c.put("majuscule", true);
            c.put("separateur", "_");
            c.put("indexIds", List.of(IDX_NUM, IDX_STATUT));
            c.put("charteIds", List.of(IDX_NUM, IDX_STATUT, "year"));
            planCh = idDe(J(ADM, "POST", "/api/v1/plan-indexations", c), "plan charte");
        } else {
            planCh = pch.path("id").asText();
        }
        // Charte automatique (manuel = false) : le nom du document est composé au dépôt.
        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("code", "QA2-PLAN-CHARTE");
        pc.put("nomDuPlan", "QA2 Charte facture");
        pc.put("modeIndexation", false);
        pc.put("manuel", false);
        pc.put("majuscule", true);
        pc.put("separateur", "_");
        pc.put("indexIds", List.of(IDX_NUM, IDX_STATUT));
        pc.put("charteIds", List.of(IDX_NUM, IDX_STATUT, "year"));
        J(ADM, "PUT", "/api/v1/plan-indexations/" + planCh, pc);
        String tCh = type("QA2-CHARTE", "QA2 Facture nommée", FIN26, planCh, List.of("pdf"), 10, null, "PUBLIC");
        Rep nomme = deposer(V1, "pdf_texte_fr_facture.pdf", null, tCh, null, "{\"QA2_NUM\":\"fa" + M + "\",\"QA2_STATUT\":\"Définitif\"}");
        String nom = nomme.json().path("name").asText();
        JsonNode planLu = G(ADM, "/api/v1/plan-indexations/" + planCh).json();
        verif("F-29", nomme.code() / 100 == 2 && nom.toUpperCase().startsWith(("FA" + M).toUpperCase()) && nom.contains("_")
                        && nom.endsWith("_" + String.format("%02d", LocalDate.now().getYear() % 100)),
                "Référentiel d'index typés, plan d'indexation, charte de nommage (jetons d'index et jeton système, séparateur, majuscules)",
                "dépôt " + court(nomme) + ", nom composé « " + nom + " », aperçu du plan « " + planLu.path("preview").asText() + " »");

        // F-30 (§4.2.4) : gouvernance des types.
        Rep supprUtilise = X(ADM, "DELETE", "/api/v1/type-documents/" + T_NOTE);
        String tSrc = type("QA2-RETYPE-SRC", "QA2 Retypage source", FIN26, null, List.of("pdf"), 10, null, "PUBLIC");
        g.appel("PATCH", "/api/v1/type-documents/" + tSrc + "/actif?actif=true", jeton(ADM), null, null);
        List<String> lot = new ArrayList<>();
        for (int i = 0; i < 3; i++) lot.add(doc(V1, "pdf_texte_fr_facture.pdf", "qa2-f30-" + i + "-" + M, tSrc));
        Rep job = J(ADM, "POST", "/api/v1/type-documents/retypages", Map.of("sourceTypeDocumentId", tSrc, "cibleTypeDocumentId", T_COURRIER,
                "correspondance", Map.of(), "documentIds", lot));
        JsonNode jj = job.json();
        for (int i = 0; i < 60 && job.code() / 100 == 2 && !List.of("TERMINE", "ECHEC").contains(jj.path("statut").asText()); i++) {
            attendre(1000);
            jj = G(ADM, "/api/v1/type-documents/retypages/" + jj.path("id").asText()).json();
        }
        int retypes = 0;
        for (String d : lot) if (T_COURRIER.equals(G(ADM, "/api/v1/documents/" + d).json().path("typeDocument").path("id").asText())) retypes++;
        Rep retypeStd = J(V1, "POST", "/api/v1/type-documents/retypages", Map.of("sourceTypeDocumentId", tSrc, "cibleTypeDocumentId", T_COURRIER,
                "correspondance", Map.of(), "documentIds", lot));
        verif("F-30", supprUtilise.code() == 409 && "TERMINE".equals(jj.path("statut").asText()) && retypes == 3 && retypeStd.code() == 403,
                "Gouvernance des types : suppression d'un type utilisé empêchée ; re-typologie d'un lot, réservée à l'Administrateur",
                "suppression d'un type utilisé " + court(supprUtilise) + " ; retypage " + court(job) + " → " + jj.path("statut").asText() + " "
                        + jj.path("reussis") + "/" + jj.path("total") + ", documents retypés " + retypes + "/3 ; standard " + court(retypeStd));

        // F-31 (§4.3.2) : espaces puis arborescence de profondeur libre.
        String parent = FIN;
        List<String> chaine = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            Rep r = J(ADM, "POST", "/api/v1/noeuds/" + parent + "/dossiers", Map.of("nom", "QA2 niveau " + i + " " + M));
            if (r.code() / 100 != 2) {
                chaine.add("échec niveau " + i + " : " + court(r));
                break;
            }
            parent = r.json().path("id").asText();
            chaine.add(parent);
        }
        // Structure d'un espace métier : Administrateur (GERER_ESPACES) ; constat pour l'Agent d'archive.
        Rep dossierAgent = J(AGENT, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 dossier agent " + M));
        boolean profondOk = chaine.size() == 6 && !chaine.get(chaine.size() - 1).startsWith("échec");
        boolean visibleProfond = false;
        if (profondOk) {
            String tP = type("QA2-PROFOND-" + m1, "QA2 Profond " + M, parent, null, List.of("pdf"), 5, null, "PUBLIC");
            String dProfond = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-f31-" + M, tP);
            visibleProfond = visibles(V1, "qa2-f31-" + M).contains(dProfond);
        }
        verif("F-31", profondOk && idsArbre(AGENT).contains(parent) && visibleProfond,
                "Espaces, puis dossiers de profondeur libre (six niveaux sous l'espace), droits hérités jusqu'au fond",
                "niveaux créés " + chaine.size() + (profondOk ? "" : " " + chaine) + ", document au 6e niveau visible d'un standard de l'espace " + visibleProfond
                        + " ; dossier en espace métier par l'Agent d'archive " + court(dossierAgent));

        // F-32 (§4.3.2, D12) : deux natures d'espaces.
        JsonNode ech = G(ADM, "/api/v1/workspaces/" + ECH).json();
        Rep dossierEch = J(V2, "POST", "/api/v1/noeuds/" + ECH + "/dossiers", Map.of("nom", "QA2 lot marché " + M));
        Rep sousDossierEch = dossierEch.code() / 100 == 2
                ? J(V2, "POST", "/api/v1/noeuds/" + dossierEch.json().path("id").asText() + "/dossiers", Map.of("nom", "QA2 pièces " + M)) : null;
        Rep dossierMetier = J(V1, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 interdit " + M));
        verif("F-32", "ECHANGE".equals(ech.path("usageEspace").asText()) && dossierEch.code() / 100 == 2 && sousDossierEch != null
                        && sousDossierEch.code() / 100 == 2 && dossierMetier.code() == 403,
                "Deux natures d'espaces : métier et échange ; dans un espace d'échange, un membre crée librement dossiers et sous-dossiers",
                "usage " + ech.path("usageEspace") + ", dossier par un membre de l'échange " + court(dossierEch) + ", sous-dossier "
                        + (sousDossierEch == null ? "-" : court(sousDossierEch)) + " ; même geste d'un standard en espace métier " + court(dossierMetier));

        // F-33 (§4.3.4) : création d'espace réservée à l'Administrateur.
        Map<String, Object> esp = new LinkedHashMap<>();
        esp.put("name", "QA2 Espace interdit " + M);
        esp.put("code", "QA2-INT-" + m1);
        esp.put("employeId", emp(AGENT));
        Rep eAgent = J(AGENT, "POST", "/api/v1/workspaces", esp);
        esp.put("employeId", emp(DG));
        Rep eDg = J(DG, "POST", "/api/v1/workspaces", esp);
        esp.put("employeId", emp(V1));
        Rep eStd = J(V1, "POST", "/api/v1/workspaces", esp);
        esp.put("name", "QA2 Espace admin " + M);
        esp.put("code", "QA2-ADM-" + m1);
        esp.put("employeId", emp(ADM));
        Rep eAdm = J(ADM, "POST", "/api/v1/workspaces", esp);
        boolean dgVoitNouvel = eAdm.code() / 100 == 2 && idsArbre(DG).contains(eAdm.json().path("id").asText());
        verif("F-33", eAgent.code() == 403 && eDg.code() == 403 && eStd.code() == 403 && eAdm.code() / 100 == 2 && dgVoitNouvel,
                "Création d'espace réservée à l'Administrateur ; la Direction Générale voit le nouvel espace sans attribution",
                "Agent d'archive " + court(eAgent) + ", DG " + court(eDg) + ", standard " + court(eStd) + ", Administrateur " + court(eAdm)
                        + ", visible de la DG " + dgVoitNouvel);

        // F-34 (§4.3.4, P5) : espaces non autorisés invisibles dans l'arbre.
        Set<String> arbreSous = idsArbre(SOUS), arbreAutre = idsArbre(AUTRE);
        Map<String, JsonNode> noeudsSous = new LinkedHashMap<>();
        arbre(G(SOUS, "/api/v1/workspaces/tree").json(), noeudsSous);
        boolean passage = noeudsSous.containsKey(FIN) && noeudsSous.get(FIN).path("passage").asBoolean();
        Rep ficheInvisible = G(SOUS, "/api/v1/workspaces/" + RHE);
        verif("F-34", arbreSous.contains(FIN26) && !arbreSous.contains(FINRES) && !arbreSous.contains(RHE) && passage
                        && !arbreAutre.contains(FIN) && ficheInvisible.code() == 404,
                "Arbre filtré : seuls les nœuds couverts (ancêtres en simple passage) sont présentés ; nœud non couvert introuvable",
                "yalaoui : Exercice 2026 " + arbreSous.contains(FIN26) + ", Finance en passage " + passage + ", Réservé " + arbreSous.contains(FINRES)
                        + ", RH " + arbreSous.contains(RHE) + " ; nidrissi voit Finance " + arbreAutre.contains(FIN) + " ; fiche RH " + court(ficheInvisible));

        // F-35 (§4.3.4) : modification non autorisée de la structure bloquée et journalisée.
        // Dossiers jetables de QA2 Finance (qa2val1 y est Utilisateur standard par héritage) :
        // un renommage accepté à tort ne touche pas le jeu de recette.
        String s1 = idDe(J(ADM, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 structure " + M)), "dossier jetable");
        String s2 = idDe(J(ADM, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 structure bis " + M)), "dossier jetable");
        Map<String, Object> ren = new LinkedHashMap<>();
        ren.put("name", "QA2 structure renommée par un standard " + M);
        ren.put("code", "QA2-STR-" + m1);
        ren.put("employeId", emp(V1));
        Rep renomme = J(V1, "PUT", "/api/v1/workspaces/" + s1, ren);
        Rep deplace = J(V1, "PATCH", "/api/v1/workspaces/" + s2 + "/parent", Map.of("parentId", FIN26));
        Rep supprime = X(V1, "DELETE", "/api/v1/workspaces/" + s2);
        attendre(500);
        String uV1 = uid(V1);
        // Refus au journal : objet renseigné, ou désigné dans le motif (refus de permission).
        StringBuilder refus = new StringBuilder();
        for (JsonNode l : audit("resultat=REFUS"))
            if (uV1.equals(l.path("acteurUtilisateurId").asText()))
                refus.append(l.path("objetId").asText()).append(' ').append(l.path("motif").asText()).append('\n');
        boolean refusS1 = refus.toString().contains(s1), refusS2 = refus.toString().contains(s2);
        verif("F-35", renomme.code() == 403 && deplace.code() == 403 && supprime.code() == 403 && refusS1 && refusS2,
                "Toute modification non autorisée de la structure est bloquée et journalisée (refus au journal d'audit)",
                "renommage (et changement de propriétaire) par un standard " + court(renomme) + ", déplacement " + court(deplace)
                        + ", suppression " + court(supprime) + ", refus tracés : dossier renommé " + refusS1 + ", dossier déplacé/supprimé " + refusS2);

        // F-36 (§4.3.5) : déplacement d'un fichier ou d'un dossier.
        String tDep = type("QA2-DEPL", "QA2 Pièce à déplacer", FIN26, null, List.of("pdf"), 10, null, "PUBLIC");
        String dDep = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-f36-" + M, tDep);
        Rep depStd = J(SOUS, "PATCH", "/api/v1/documents/" + dDep + "/emplacement", Map.of("noeudId", FIN26F));
        Rep depHors = J(AGENT, "PATCH", "/api/v1/documents/" + dDep + "/emplacement", Map.of("noeudId", PRJ));
        Rep depOk = J(AGENT, "PATCH", "/api/v1/documents/" + dDep + "/emplacement", Map.of("noeudId", FIN26F));
        attendre(500);
        boolean depTrace = auditContient("objetId=" + dDep, l -> l.path("action").asText().contains("DEPLAC"));
        String nA = idDe(J(ADM, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 à déplacer " + M)), "dossier à déplacer");
        String nB = idDe(J(ADM, "POST", "/api/v1/noeuds/" + nA + "/dossiers", Map.of("nom", "QA2 enfant " + M)), "sous-dossier");
        Rep mvAgent = J(AGENT, "PATCH", "/api/v1/workspaces/" + nA + "/parent", Map.of("parentId", FIN26));
        Rep mvDossier = mvAgent.code() == 200 ? mvAgent : J(ADM, "PATCH", "/api/v1/workspaces/" + nA + "/parent", Map.of("parentId", FIN26));
        JsonNode enfant = G(ADM, "/api/v1/workspaces/" + nB).json();
        boolean sousArbreSuivi = nA.equals(enfant.path("parent").path("id").asText());
        Map<String, JsonNode> arbreAdm = new LinkedHashMap<>();
        arbre(G(ADM, "/api/v1/workspaces/tree").json(), arbreAdm);
        boolean sousFin26 = arbreAdm.containsKey(nA) && FIN26.equals(arbreAdm.get(nA).path("parentId").asText());
        boolean mvTrace = auditContient("objetId=" + nA, l -> l.path("action").asText().contains("DEPLAC"));
        verif("F-36", depStd.code() == 403 && depHors.code() / 100 == 4 && depOk.code() == 200
                        && FIN26F.equals(depOk.json().path("workspace").path("id").asText()) && depTrace && mvDossier.code() == 200
                        && sousArbreSuivi && sousFin26 && mvTrace,
                "Déplacement d'un document (droits à l'origine et à la destination) et d'un dossier avec sa sous-arborescence, journalisé",
                "sans permission Déplacer " + court(depStd) + ", destination hors droits " + court(depHors) + ", déplacement " + court(depOk)
                        + " → " + depOk.json().path("workspace").path("label") + ", audit " + depTrace + " ; dossier par l'Agent d'archive "
                        + court(mvAgent) + (mvAgent == mvDossier ? "" : ", par l'Administrateur " + court(mvDossier))
                        + ", enfant suivi " + sousArbreSuivi + ", nouveau parent " + sousFin26 + ", audit " + mvTrace);

        // F-37 (§4.3.6) : rattachement à plusieurs espaces sans duplication.
        String dMulti2 = doc(AGENT, "pdf_texte_fr_facture.pdf", "qa2-f37-" + M, T_COURRIER);
        boolean rhAvant = visibles(RH, "qa2-f37-" + M).contains(dMulti2);
        Rep ratt = J(AGENT, "POST", "/api/v1/documents/" + dMulti2 + "/rattachements", Map.of("noeudId", RHC));
        boolean rhApres = visibles(RH, "qa2-f37-" + M).contains(dMulti2);
        Rep rattStd = J(V1, "POST", "/api/v1/documents/" + dMulti2 + "/rattachements", Map.of("noeudId", ECH));
        JsonNode liste = G(AGENT, "/api/v1/documents?size=50&search=" + enc("qa2-f37-" + M)).json();
        int occurrences = 0;
        for (JsonNode d : liste.path("content")) if (d.path("id").asText().equals(dMulti2)) occurrences++;
        Rep rech = J(AGENT, "POST", "/api/v1/documents/recherche", Map.of("texte", "qa2-f37-" + M));
        Rep detache = X(AGENT, "DELETE", "/api/v1/documents/" + dMulti2 + "/rattachements/" + RHC);
        boolean rhFin = visibles(RH, "qa2-f37-" + M).contains(dMulti2);
        boolean toujours = visibles(AGENT, "qa2-f37-" + M).contains(dMulti2);
        verif("F-37", !rhAvant && ratt.code() / 100 == 2 && rhApres && rattStd.code() / 100 == 4 && occurrences == 1
                        && rech.json().path("total").asInt() == 1 && detache.code() / 100 == 2 && !rhFin && toujours,
                "Rattachement sans duplication : visible depuis le second espace, une seule occurrence en liste et en recherche, retrait sans effet sur l'original",
                "RH avant " + rhAvant + ", rattachement " + court(ratt) + ", RH après " + rhApres + ", standard sans écriture " + court(rattStd)
                        + ", occurrences liste " + occurrences + ", recherche " + rech.json().path("total") + ", retrait " + court(detache)
                        + " → RH " + rhFin + ", original " + toujours);

        // F-38 (§4.3.4, §4.6.3) : durée de conservation et statut par type.
        JsonNode tn = G(ADM, "/api/v1/type-documents/" + T_NOTE).json();
        Rep inactif = g.appel("PATCH", "/api/v1/type-documents/" + tSrc + "/actif?actif=false", jeton(ADM), null, null);
        Rep depotInactif = deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-f38-" + M, tSrc, null, null);
        verif("F-38", tn.path("dureeConservationMois").asInt() == 12 && tn.has("actif") && "2027-03-15".equals(j27.path("echeanceConservation").asText())
                        && inactif.code() / 100 == 2 && depotInactif.code() / 100 == 4,
                "Durée de conservation et statut (actif / inactif) portés par chaque type ; l'échéance du document en découle",
                "durée " + tn.path("dureeConservationMois") + " mois, point de départ " + tn.path("pointDepart") + ", échéance "
                        + j27.path("echeanceConservation") + " ; désactivation du type " + court(inactif) + " → dépôt " + court(depotInactif));

        // ---- OCR (F-22 à F-26)
        JsonNode oFr = attendreOcr(V1, dScanFr), oAr = attendreOcr(V1, dScanAr), oPdfAr = attendreOcr(V1, dPdfAr), oMulti = attendreOcr(V1, dMulti);
        attendreOcr(RH, dScanRh);

        Rep texteUi = G(V1, "/api/v1/ocr/documents/" + dScanFr + "/texte");
        Map<String, String> hk = new LinkedHashMap<>(Map.of("X-API-Key", cle));
        Rep texteApi = g.appel("GET", "/api/v1/ocr/documents/" + dScanFr + "/texte", hk, null, null);
        Rep rechApi = g.appel("GET", "/api/v1/recherche/plein-texte?q=zarkolinet&taille=100&typeDocumentId=" + T_FACT + "&du=" + java.time.LocalDate.now(), hk, null, null);
        verif("F-22", texteUi.code() == 200 && texteUi.json().path("texte").asText().toLowerCase().contains("zarkolinet")
                        && texteApi.code() == 200 && rechApi.code() == 200 && rechApi.corps().contains(dScanFr),
                "Texte OCR et recherche disponibles pour l'interface (jeton) et pour une application consommatrice (clé d'API)",
                "texte (utilisateur) " + texteUi.code() + " " + texteUi.json().path("provenance") + ", texte (clé) " + court(texteApi)
                        + ", recherche (clé) " + court(rechApi) + " contient le scan " + rechApi.corps().contains(dScanFr));

        Set<String> trouves = pleinTexte(V2, "zarkolinet");
        verif("F-23", "OCR_TERMINE".equals(oFr.path("statutOcr").asText()) && trouves.contains(dScanFr) && !trouves.contains(dScanRh),
                "Le texte OCR d'un scan est conservé et interrogeable en plein texte, dans le périmètre de l'utilisateur",
                "statut " + oFr.path("statutOcr") + ", trouvé par qa2val2 " + trouves.contains(dScanFr) + ", scan RH exclu " + !trouves.contains(dScanRh));

        JsonNode apresOcr = G(V1, "/api/v1/documents/" + dScanFr).json();
        JsonNode analyse = G(V1, "/api/v1/indexation/documents/" + dScanFr + "/analyse").json();
        List<String> sources = new ArrayList<>();
        for (JsonNode p : analyse.path("propositions"))
            if (!p.path("valeurProposee").isNull() && !p.path("valeurProposee").asText().isBlank())
                sources.add(p.path("code").asText() + "←" + p.path("source").asText());
        boolean metaVides = apresOcr.path("metadonnees").isNull() || apresOcr.path("metadonnees").isEmpty();
        verif("F-24", metaVides && apresOcr.path("objet").isNull()
                        && sources.stream().noneMatch(s -> s.toUpperCase().matches(".*(OCR|CONTENU|TEXTE).*")),
                "L'OCR n'alimente aucun champ : après OCR, métadonnées vides et aucune proposition tirée du contenu",
                "métadonnées " + apresOcr.path("metadonnees") + ", objet " + apresOcr.path("objet") + ", indexation " + apresOcr.path("statutIndexation")
                        + ", propositions " + sources + " (provenance du texte : " + analyse.path("provenanceTexte").asText() + ")");

        JsonNode tm = G(V1, "/api/v1/ocr/documents/" + dMulti + "/texte").json();
        Set<String> fr = pleinTexte(V1, "zarkolinet"), ar = pleinTexte(V1, "زركولين");
        verif("F-25", "OCR_TERMINE".equals(oMulti.path("statutOcr").asText()) && tm.path("nbPages").asInt() >= 4 && fr.contains(dMulti) && ar.contains(dMulti),
                "Document multipage traité comme une unité : toutes les pages OCRisées, dernière page comprise, un seul document trouvé",
                "pages " + tm.path("nbPages") + ", langue " + tm.path("langue") + ", témoin français " + fr.contains(dMulti)
                        + ", témoin arabe (dernière page) " + ar.contains(dMulti));

        verif("F-26", ar.contains(dScanAr) && ar.contains(dPdfAr) && fr.contains(dScanFr),
                "Français et arabe : scan arabe OCRisé et PDF arabe natif trouvés par un mot arabe, scan français par un mot français",
                "scan arabe " + ar.contains(dScanAr) + " (" + oAr.path("statutOcr").asText() + "), PDF arabe " + ar.contains(dPdfAr)
                        + " (" + oPdfAr.path("statutOcr").asText() + "), scan français " + fr.contains(dScanFr));
    }
}
