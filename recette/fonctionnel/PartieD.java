import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Partie D de la recette fonctionnelle : cycle de vie (§4.6), archivage (§4.7), traçabilité
 * (§4.9), intégration (§4.10), confidentialité et sécurité (§4.13 à §4.15) — F-55 à F-77.
 *
 * <p>Variables facultatives : GED_RECETTE_MAILS (dossier du SMTP simulé), GED_RECETTE_COFFRE
 * (racine du stockage chiffré de l'instance), GED_RECETTE_JOURNAL (journal technique de
 * l'instance). Sans elles, les lignes correspondantes sont NA (contrôle fait hors script).
 */
public class PartieD extends PartieC {

    /** Nouvelle version d'un document (multipart : fichier + observation). */
    static Rep verser(String compte, String doc, String fichier, String observation) throws Exception {
        byte[] octets = Files.readAllBytes(donnees.resolve(fichier));
        String mime = fichier.endsWith(".pdf") ? "application/pdf" : fichier.endsWith(".png") ? "image/png"
                : fichier.endsWith(".txt") ? "text/plain" : "application/octet-stream";
        Map<String, String> c = new LinkedHashMap<>();
        if (observation != null) c.put("observation", observation);
        Object[] m = multipart(octets, fichier, mime, c, null);
        Rep r = g.appel("POST", "/api/v1/documents/" + doc + "/versions", jeton(compte), "multipart/form-data; boundary=" + m[0], (byte[]) m[1]);
        if (r.code() == 401) {
            jetons.remove(compte);
            r = g.appel("POST", "/api/v1/documents/" + doc + "/versions", jeton(compte), "multipart/form-data; boundary=" + m[0], (byte[]) m[1]);
        }
        return r;
    }

    static JsonNode fiche(String compte, String doc) throws Exception {
        return G(compte, "/api/v1/documents/" + doc).json();
    }

    static String versionCourante(JsonNode fiche) {
        for (JsonNode v : fiche.path("versions")) if (v.path("principale").asBoolean()) return v.path("id").asText();
        return fiche.path("versions").path(0).path("id").asText();
    }

    static Map<String, byte[]> dezipper(byte[] zip) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (!e.isDirectory()) m.put(e.getName(), z.readAllBytes());
        }
        return m;
    }

    /** PDF valide gonflé au-delà de {@code octets} (commentaires après l'en-tête). */
    static byte[] pdfGonfle(int octets) throws Exception {
        byte[] base = Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(base);
        byte[] ligne = ("%" + "x".repeat(98) + "\n").getBytes(StandardCharsets.US_ASCII);
        while (o.size() < octets) o.writeBytes(ligne);
        return o.toByteArray();
    }

    static List<String> actions(String objetId) throws Exception {
        List<String> a = new ArrayList<>();
        for (JsonNode l : audit("objetId=" + objetId)) a.add(l.path("action").asText());
        return a;
    }

    static String dF55, dArch, dConfRh, dossierExport;

    static void executer() throws Exception {
        info("---- Partie D : cycle de vie, archivage, audit, intégration, sécurité (F-55 à F-77)");
        String m1 = M.toUpperCase();
        String auditDebut = java.time.Instant.now().minusSeconds(5).toString();

        // F-55 (§4.6.3) : versionnement, auteur et date par version.
        etape("F-55", () -> {
            dF55 = doc(V1, "pdf_texte_fr_facture.pdf", "qa2-d-version-" + M, T_NOTE);
            Rep v2 = verser(V2, dF55, "pdf_texte_fr_convention.pdf", "Mise à jour par qa2val2 " + M);
            JsonNode f = fiche(V1, dF55);
            JsonNode vs = f.path("versions");
            Set<String> auteurs = new LinkedHashSet<>();
            boolean dates = true;
            for (JsonNode v : vs) {
                auteurs.add(v.path("auteurId").asText());
                dates &= !v.path("createdAt").asText().isBlank();
            }
            verif("F-55", v2.code() / 100 == 2 && vs.size() == 2 && auteurs.containsAll(List.of(uid(V1), uid(V2))) && dates,
                    "Versions antérieures conservées ; auteur et date de chaque version",
                    "versement " + court(v2) + ", versions " + vs.size() + ", auteurs distincts " + auteurs.size() + " (qa2val1 et qa2val2 : "
                            + auteurs.containsAll(List.of(uid(V1), uid(V2))) + "), dates renseignées " + dates + ", observation « "
                            + vs.path(vs.size() - 1).path("observation").asText() + " »");
        });

        // F-56 (§4.6.4, Q6) : version courante désignable.
        etape("F-56", () -> {
            JsonNode f = fiche(V1, dF55);
            String premiere = null, courante = versionCourante(f);
            for (JsonNode v : f.path("versions")) if (v.path("numero").asInt() == 1) premiere = v.path("id").asText();
            Rep r = J(V1, "PATCH", "/api/v1/documents/" + dF55 + "/versions/" + premiere + "/default", null);
            String apres = versionCourante(fiche(V1, dF55));
            Rep tel = g.get("/api/v1/documents/" + dF55 + "/download", jeton(V1));
            boolean contenuV1 = tel.code() == 200 && sha256(tel.octets()).equals(sha256(Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"))));
            verif("F-56", r.code() == 200 && premiere != null && premiere.equals(apres) && !premiere.equals(courante) && contenuV1,
                    "Version courante désignable (pas forcément la dernière), servie au téléchargement",
                    "courante avant : v" + (premiere != null && premiere.equals(courante) ? 1 : 2) + ", désignation de v1 " + court(r)
                            + " → courante v1 " + premiere.equals(apres) + ", téléchargement = contenu v1 " + contenuV1);
        });

        // F-57 (§4.6.4) : verrouillage.
        etape("F-57", () -> {
            String d = doc(V1, "pdf_texte_fr_facture.pdf", "qa2-d-verrou-" + M, T_NOTE);
            Rep parStd = J(V1, "PATCH", "/api/v1/documents/" + d + "/verrou?verrouille=true&motif=" + enc("essai"), null);
            Rep pose = J(ADM, "PATCH", "/api/v1/documents/" + d + "/verrou?verrouille=true&motif=" + enc("Contentieux " + M), null);
            Rep modif = J(V1, "PUT", "/api/v1/documents/" + d, Map.of("objet", "Modifié sous verrou"));
            Rep vers = verser(V1, d, "pdf_texte_fr_convention.pdf", "sous verrou");
            Rep depl = J(AGENT, "PATCH", "/api/v1/documents/" + d + "/emplacement", Map.of("noeudId", FIN26F));
            Rep supp = X(AGENT, "DELETE", "/api/v1/documents/" + d);
            Rep lec = G(V1, "/api/v1/documents/" + d);
            Rep leve = J(ADM, "PATCH", "/api/v1/documents/" + d + "/verrou?verrouille=false", null);
            Rep modif2 = J(V1, "PUT", "/api/v1/documents/" + d, Map.of("objet", "Modifié après levée " + M));
            verif("F-57", pose.code() == 200 && pose.json().path("verrouille").asBoolean() && modif.code() / 100 == 4 && vers.code() / 100 == 4
                            && depl.code() / 100 == 4 && supp.code() / 100 == 4 && lec.code() == 200 && leve.code() == 200 && modif2.code() == 200,
                    "Verrouillage d'un document : fiche, versions, déplacement et suppression gelés, lecture maintenue ; levée du verrou",
                    "pose par un standard " + court(parStd) + ", par l'Administrateur " + court(pose) + " (motif « " + pose.json().path("verrouMotif").asText()
                            + " ») ; sous verrou : fiche " + court(modif) + ", version " + court(vers) + ", déplacement " + court(depl) + ", suppression "
                            + court(supp) + ", lecture " + lec.code() + " ; levée " + court(leve) + " → modification " + court(modif2));
        });

        // F-58 (§4.6.3) : durée de conservation par type et alerte d'échéance à l'Agent d'archive.
        etape("F-58", () -> {
            String t = type("QA2-ECH-" + m1, "QA2 Pièce à courte conservation " + M, FIN26, null, List.of("pdf"), 10, 1, "PUBLIC");
            String d = idDe(deposer(V1, "pdf_texte_fr_facture.pdf", "qa2-d-echeance-" + M, t, champs("dateDocument", "2025-01-10"), null), "échéance");
            JsonNode f = fiche(V1, d);
            Set<String> depassees = new LinkedHashSet<>();
            for (JsonNode x : contenu(G(AGENT, "/api/v1/documents?size=100&echeanceDepassee=true&search=" + enc(M)).json())) depassees.add(x.path("id").asText());
            boolean alerte = false;
            for (int i = 0; i < 26 && !alerte; i++) {
                alerte = notifie(AGENT, "ECHEANCE_CONSERVATION", null) && notifications(AGENT).toString().contains("qa2-d-echeance-" + M);
                if (!alerte) attendre(5000);
            }
            boolean std = notifications(V1).toString().contains("ECHEANCE_CONSERVATION");
            boolean journal = auditContient("objetId=" + d, l -> l.path("action").asText().equals("ECHEANCE_CONSERVATION_ATTEINTE"));
            verif("F-58", "2025-02-10".equals(f.path("echeanceConservation").asText()) && f.path("echeanceDepassee").asBoolean() && depassees.contains(d)
                            && alerte && !std,
                    "Durée de conservation par type ; document échu mis en évidence et alerte d'échéance adressée à l'Agent d'archive",
                    "échéance " + f.path("echeanceConservation") + " (1 mois après le 10/01/2025), échue " + f.path("echeanceDepassee") + ", filtre « échéance dépassée » "
                            + depassees.contains(d) + " ; alerte reçue par l'Agent d'archive " + alerte + " (tâche planifiée), par le standard " + std
                            + ", audit ECHEANCE_CONSERVATION_ATTEINTE " + journal);
        });

        // F-59 (§4.6.5) : import et export unitaires.
        etape("F-59", () -> {
            byte[] src = Files.readAllBytes(donnees.resolve("document_fr.docx"));
            Rep dep = deposer(V1, "document_fr.docx", "qa2-d-unitaire-" + M, T_NOTE, null, null);
            String d = idDe(dep, "docx");
            Rep tel = g.get("/api/v1/documents/" + d + "/download", jeton(V1));
            String dispo = tel.entetes().firstValue("Content-Disposition").orElse("");
            verif("F-59", tel.code() == 200 && sha256(tel.octets()).equals(sha256(src)) && dispo.contains("docx"),
                    "Import (dépôt) et export (téléchargement) d'un fichier unitaire, à l'identique",
                    "dépôt " + court(dep) + ", téléchargement " + tel.code() + ", empreinte identique " + sha256(tel.octets()).equals(sha256(src))
                            + ", « " + dispo + " »");
        });

        // F-60 (§4.6.5) : export d'un dossier complet en ZIP avec manifeste.
        etape("F-60", () -> {
            dossierExport = idDe(J(ADM, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 Export " + M)), "dossier d'export");
            String sous = idDe(J(ADM, "POST", "/api/v1/noeuds/" + dossierExport + "/dossiers", Map.of("nom", "QA2 Sous-dossier " + M)), "sous-dossier");
            String t1 = type("QA2-EXP-" + m1, "QA2 Pièce exportée " + M, dossierExport, null, List.of("pdf", "docx"), 10, null, "PUBLIC");
            String t2 = type("QA2-EXPS-" + m1, "QA2 Pièce du sous-dossier " + M, sous, null, List.of("pdf"), 10, null, "PUBLIC");
            doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-d-exp-pub-" + M, t1);
            idDe(deposer(ADM, "pdf_texte_fr_facture.pdf", "qa2-d-exp-pri-" + M, t1, champs("confidentialite", "PRIVE"), null), "privé");
            doc(ADM, "pdf_texte_fr_convention.pdf", "qa2-d-exp-sous-" + M, t2);
            Rep zv = J(V1, "POST", "/api/v1/exports/dossiers/" + dossierExport, null);
            Rep za = J(ADM, "POST", "/api/v1/exports/dossiers/" + dossierExport, null);
            Map<String, byte[]> ev = zv.code() == 200 ? dezipper(zv.octets()) : Map.of(), ea = za.code() == 200 ? dezipper(za.octets()) : Map.of();
            String manifeste = "";
            for (var e : ea.entrySet()) if (e.getKey().endsWith("manifeste.csv")) manifeste = new String(e.getValue(), StandardCharsets.UTF_8);
            String entete = manifeste.lines().findFirst().orElse("");
            long lignesA = manifeste.lines().count() - 1;
            boolean sousDossier = ea.keySet().stream().anyMatch(k -> k.contains("Sous-dossier"));
            verif("F-60", za.code() == 200 && ea.size() == 4 && lignesA == 3 && ev.size() == 3 && sousDossier && entete.toLowerCase().contains("empreinte"),
                    "Export d'un dossier complet (sous-dossiers compris) en ZIP avec manifeste des métadonnées, filtré par les droits",
                    "Administrateur " + court(za) + " : " + ea.size() + " entrées " + ea.keySet() + " ; manifeste « " + entete + " » (" + lignesA
                            + " lignes) ; standard " + court(zv) + " : " + ev.size() + " entrées (document privé omis)");
        });

        // F-61 (§4.6.5) : suppression à deux niveaux, corbeille puis purge définitive.
        etape("F-61", () -> {
            String d = doc(V1, "pdf_texte_fr_facture.pdf", "qa2-d-purge-" + M, T_NOTE);
            Rep vivant = J(ADM, "POST", "/api/v1/documents/" + d + "/purge", null);
            Rep sup = X(AGENT, "DELETE", "/api/v1/documents/" + d);
            Rep purgeStd = J(V1, "POST", "/api/v1/documents/" + d + "/purge", null);
            Rep purgeAgent = J(AGENT, "POST", "/api/v1/documents/" + d + "/purge", null);
            Rep purgeAdm = purgeAgent.code() / 100 == 2 ? purgeAgent : J(ADM, "POST", "/api/v1/documents/" + d + "/purge", null);
            boolean corbeille = G(ADM, "/api/v1/documents/trashed?size=200&search=" + enc("qa2-d-purge-" + M)).corps().contains(d);
            Rep restaurer = X(ADM, "PATCH", "/api/v1/documents/" + d + "/restore");
            Rep lire = G(ADM, "/api/v1/documents/" + d);
            attendre(500);
            boolean trace = auditContient("objetId=" + d, l -> l.path("action").asText().equals("DOCUMENT_PURGE"));
            verif("F-61", vivant.code() == 409 && sup.code() / 100 == 2 && purgeStd.code() == 403 && purgeAgent.code() / 100 == 2 && purgeAdm.code() / 100 == 2 && !corbeille
                            && restaurer.code() / 100 == 4 && lire.code() == 404 && trace,
                    "Deux niveaux : corbeille réversible, puis purge définitive réservée aux habilités, tracée",
                    "purge d'un document vivant " + court(vivant) + " ; mise en corbeille " + court(sup) + " ; purge par un standard " + court(purgeStd)
                            + ", par l'Agent d'archive " + court(purgeAgent) + " (Purger, ANO-F-001)" + (purgeAgent == purgeAdm ? "" : ", par l'Administrateur " + court(purgeAdm)) + " → en corbeille "
                            + corbeille + ", restauration " + court(restaurer) + ", fiche " + court(lire) + ", audit DOCUMENT_PURGE " + trace);
        });

        // F-62 (§4.6.5) : dossiers partagés pour un groupe d'utilisateurs.
        etape("F-62", () -> {
            String grp = idDe(J(ADM, "POST", "/api/v1/access-groups", Map.of("code", "QA2-PART-" + m1, "name", "QA2 Partage " + M,
                    "workspaceIds", List.of(), "userIds", List.of(emp(GRP), emp(AUTRE)))), "groupe de partage");
            String dossier = idDe(J(ADM, "POST", "/api/v1/noeuds/" + ECH + "/dossiers", Map.of("nom", "QA2 Partage " + M)), "dossier partagé");
            String t = type("QA2-PART-" + m1, "QA2 Pièce partagée " + M, dossier, null, List.of("pdf"), 10, null, "PUBLIC");
            boolean avant = idsArbre(AUTRE).contains(dossier);
            habiliter("GROUPE", grp, "UTILISATEUR_STANDARD", dossier, null, false);
            Rep depK = deposer(GRP, "pdf_texte_fr_facture.pdf", "qa2-d-partage-k-" + M, t, null, null);
            Rep depN = deposer(AUTRE, "pdf_texte_fr_facture.pdf", "qa2-d-partage-n-" + M, t, null, null);
            Set<String> deux = new LinkedHashSet<>();
            if (depK.code() / 100 == 2) deux.add(depK.json().path("id").asText());
            if (depN.code() / 100 == 2) deux.add(depN.json().path("id").asText());
            Set<String> vK = new LinkedHashSet<>(visibles(GRP, "qa2-d-partage-")), vN = new LinkedHashSet<>(visibles(AUTRE, "qa2-d-partage-"));
            vK.retainAll(deux);
            vN.retainAll(deux);
            boolean arbre = idsArbre(AUTRE).contains(dossier);
            Set<String> vS = new LinkedHashSet<>(visibles(SOUS, "qa2-d-partage-"));
            vS.retainAll(deux);
            boolean horsGroupe = vS.isEmpty();
            // Dans l'espace d'échange, le membre crée un sous-dossier : peut-il y ranger un document ?
            Rep sd = J(AUTRE, "POST", "/api/v1/noeuds/" + dossier + "/dossiers", Map.of("nom", "QA2 Lot " + M));
            Rep range = depN.code() / 100 == 2 && sd.code() / 100 == 2
                    ? J(AUTRE, "PATCH", "/api/v1/documents/" + depN.json().path("id").asText() + "/emplacement", Map.of("noeudId", sd.json().path("id").asText()))
                    : null;
            verif("F-62", !avant && depK.code() / 100 == 2 && depN.code() / 100 == 2 && vK.size() == 2 && vN.size() == 2 && arbre && horsGroupe,
                    "Dossier partagé pour un groupe : ses membres y déposent et voient les documents des autres ; hors groupe, rien",
                    "dossier visible de nidrissi avant " + avant + ", après habilitation du groupe " + arbre + " ; dépôts kelfassi " + court(depK) + ", nidrissi "
                            + court(depN) + " ; chacun voit " + vK.size() + "/" + vN.size() + " ; yalaoui (hors groupe) ne voit rien " + horsGroupe
                            + " ; sous-dossier créé par un membre " + court(sd) + ", rangement d'un document dedans " + (range == null ? "-" : court(range)));
        });

        // F-63 (§4.6.5) : prévisualisation en ligne des formats courants.
        etape("F-63", () -> {
            Map<String, String> types = new LinkedHashMap<>();
            StringBuilder det = new StringBuilder();
            boolean ok = true;
            for (String f : List.of("pdf_texte_fr_facture.pdf", "image_scan_fr.png", "document_fr.docx", "note_texte_brut.txt")) {
                String d = idDe(deposer(V1, f, "qa2-d-apercu-" + f.substring(0, f.indexOf('.')) + "-" + M, T_NOTE, null, null), f);
                String v = versionCourante(fiche(V1, d));
                Rep a = g.get("/api/v1/versions/" + v + "/apercu", jeton(V1));
                String ct = a.entetes().firstValue("Content-Type").orElse("");
                String disp = a.entetes().firstValue("Content-Disposition").orElse("");
                boolean bon = a.code() == 200 && (ct.startsWith("application/pdf") || ct.startsWith("image/") || ct.startsWith("text/")) && !disp.startsWith("attachment");
                ok &= bon;
                det.append(f.substring(f.lastIndexOf('.') + 1)).append(" ").append(a.code()).append(" ").append(ct).append(disp.isBlank() ? "" : " (" + disp.split(";")[0] + ")").append(" ; ");
            }
            Rep horsDroit = g.get("/api/v1/versions/" + versionCourante(fiche(V1, dF55)) + "/apercu", jeton(AUTRE));
            verif("F-63", ok && horsDroit.code() / 100 == 4,
                    "Prévisualisation en ligne des formats courants (PDF, image, bureautique convertie, texte), sous contrôle des droits",
                    det + "hors droits " + court(horsDroit));
        });

        // F-64 (§4.6.5) : formats et taille maximale par type.
        etape("F-64", () -> {
            Rep png = deposer(RH, "image_scan_fr.png", "qa2-d-format-" + M, T_CONTRAT, null, null);
            Rep deguise = deposer(V1, "faux_pdf_executable.pdf", "qa2-d-deguise-" + M, T_NOTE, null, null);
            // Taille minimale paramétrable : 5 Mo (contrôle de l'API d'administration).
            Rep typeUnMo = J(ADM, "POST", "/api/v1/type-documents", Map.of("code", "QA2-UNMO-" + m1, "typeDeDocument", "QA2 Un Mo " + M,
                    "description", "x", "workspaceId", FIN26, "typeAutorise", List.of("pdf"), "tailleMaxMo", 1));
            String tPetit = type("QA2-PETIT-" + m1, "QA2 Pièce de cinq Mo " + M, FIN26, null, List.of("pdf"), 5, null, "PUBLIC");
            Rep gros = g.deposer(jeton(V1), pdfGonfle(5_600_000), "qa2-d-gros-" + M + ".pdf", "application/pdf",
                    Map.of("name", "qa2-d-gros-" + M, "typeDocumentId", tPetit), null);
            Rep petit = g.deposer(jeton(V1), pdfGonfle(4_000_000), "qa2-d-petit-" + M + ".pdf", "application/pdf",
                    Map.of("name", "qa2-d-petit-" + M, "typeDocumentId", tPetit), null);
            Rep versionPng = petit.code() / 100 != 2 ? petit : verser(V1, petit.json().path("id").asText(), "image_scan_fr.png", "format interdit");
            verif("F-64", png.code() / 100 == 4 && deguise.code() / 100 == 4 && gros.code() / 100 == 4 && petit.code() / 100 == 2 && versionPng.code() / 100 == 4,
                    "Formats autorisés et taille maximale paramétrés par type, contrôlés au dépôt et au versement",
                    "PNG sur un type « pdf » " + court(png) + ", exécutable déguisé en PDF " + court(deguise) + ", 5,6 Mo sur un type limité à 5 Mo "
                            + court(gros) + ", 4 Mo " + court(petit) + ", version PNG d'un type sans PNG " + court(versionPng)
                            + " ; type limité à 1 Mo " + court(typeUnMo) + " (plancher de 5 Mo)");
        });

        // F-65 (§4.6.6) : notifications limitées à trois cas.
        etape("F-65", () -> {
            Set<String> familles = new LinkedHashSet<>(), types = new LinkedHashSet<>();
            for (String c : List.of(V1, V2, AGENT, AUTRE, GRP, DG))
                for (JsonNode n : notifications(c)) {
                    familles.add(n.path("famille").asText());
                    types.add(n.path("type").asText());
                }
            boolean acces = notifications(AUTRE).toString().contains("ACCES_ESPACE_ATTRIBUE");
            JsonNode pref = G(V1, "/api/v1/notifications/preferences").json();
            verif("F-65", Set.of("CIRCUIT_VALIDATION", "ACCES_ESPACE", "FIN_CONSERVATION").containsAll(familles) && acces,
                    "Notifications limitées aux circuits de validation, à l'attribution d'un accès et à la fin de conservation",
                    "familles observées " + familles + ", types " + types + " ; attribution d'accès notifiée à nidrissi " + acces
                            + " ; préférence courriel " + pref.path("courrielActif"));
        });

        // F-66 (§4.7.4, D10) : archivage par statut, réversible, lecture seule, inclus en recherche.
        etape("F-66", () -> {
            dArch = idDe(deposer(V1, "document_fr.docx", "qa2-d-archive-" + M, T_NOTE, champs("objet", "Rapport annuel " + M), null), "à archiver");
            Rep parStd = J(V1, "POST", "/api/v1/documents/" + dArch + "/archivage", null);
            Rep arch = J(AGENT, "POST", "/api/v1/documents/" + dArch + "/archivage", null);
            JsonNode f = fiche(V1, dArch);
            Rep modif = J(V1, "PUT", "/api/v1/documents/" + dArch, Map.of("objet", "Modifié archivé"));
            Rep vers = verser(V1, dArch, "pdf_texte_fr_convention.pdf", "archivé");
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("texte", "qa2-d-archive-" + M);
            boolean trouve = rechercher(V1, q).toString().contains(dArch);
            q.put("statutConservation", "ARCHIVE");
            boolean filtre = rechercher(V1, q).toString().contains(dArch);
            // Dossier entier (D10)
            String dos = idDe(J(ADM, "POST", "/api/v1/noeuds/" + FIN + "/dossiers", Map.of("nom", "QA2 Dossier à archiver " + M)), "dossier");
            String t = type("QA2-ARCHD-" + m1, "QA2 Pièce du dossier archivé " + M, dos, null, List.of("pdf"), 10, null, "PUBLIC");
            String a1 = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-d-archd1-" + M, t), a2 = doc(ADM, "pdf_texte_fr_convention.pdf", "qa2-d-archd2-" + M, t);
            Rep job = J(AGENT, "POST", "/api/v1/archivage/dossiers/" + dos, null);
            JsonNode jj = job.json();
            for (int i = 0; i < 60 && job.code() / 100 == 2 && !List.of("TERMINE", "ECHEC", "ANNULE").contains(jj.path("etat").asText()); i++) {
                attendre(2000);
                jj = G(AGENT, "/api/v1/archivage/jobs/" + jj.path("id").asText()).json();
            }
            boolean tousArch = "ARCHIVE".equals(fiche(ADM, a1).path("statutConservation").asText()) && "ARCHIVE".equals(fiche(ADM, a2).path("statutConservation").asText());
            Rep desarch = X(AGENT, "DELETE", "/api/v1/documents/" + dArch + "/archivage");
            String apres = fiche(V1, dArch).path("statutConservation").asText();
            Rep reArch = J(AGENT, "POST", "/api/v1/documents/" + dArch + "/archivage", null); // reste archivé pour F-67 et F-68
            verif("F-66", arch.code() == 200 && "ARCHIVE".equals(f.path("statutConservation").asText()) && modif.code() / 100 == 4 && vers.code() / 100 == 4
                            && trouve && filtre && tousArch && desarch.code() / 100 == 2 && "ACTIF".equals(apres),
                    "Archivage par changement de statut (document ou dossier entier), manuel, réversible, lecture seule, inclus dans la recherche",
                    "archivage par un standard " + court(parStd) + ", par l'Agent d'archive " + court(arch) + " → " + f.path("statutConservation") + " ; fiche "
                            + court(modif) + ", version " + court(vers) + " ; trouvé en recherche " + trouve + ", filtre « archivés » " + filtre
                            + " ; dossier entier " + court(job) + " → " + jj.path("etat").asText() + " " + jj.path("archives") + "/" + jj.path("total")
                            + ", documents archivés " + tousArch + " ; désarchivage " + court(desarch) + " → " + apres + " ; réarchivage " + court(reArch));
        });

        // F-67 (§4.7.4) : empreinte numérique et format pérenne PDF/A.
        etape("F-67", () -> {
            JsonNode c = G(AGENT, "/api/v1/documents/" + dArch + "/conservation").json();
            JsonNode copie = c.path("copie");
            String emp = "";
            for (JsonNode v : fiche(V1, dArch).path("versions")) if (v.path("principale").asBoolean()) emp = v.path("empreinte").asText();
            String attendu = sha256(Files.readAllBytes(donnees.resolve("document_fr.docx")));
            verif("F-67", emp.equals(attendu) && copie.path("format").asText().toUpperCase().contains("PDF/A") && !copie.path("empreinte").asText().isBlank()
                            && "ARCHIVE".equals(c.path("statutConservation").asText()),
                    "Intégrité à l'archivage : empreinte SHA-256 de l'original et copie de conservation PDF/A (document Word converti)",
                    "empreinte de la version = SHA-256 du fichier déposé " + emp.equals(attendu) + " ; copie : statut " + copie.path("statut").asText() + ", méthode "
                            + copie.path("methode").asText() + ", format " + copie.path("format").asText() + ", empreinte " + (copie.path("empreinte").asText().length() > 12
                            ? copie.path("empreinte").asText().substring(0, 12) + "…" : copie.path("empreinte").asText()) + ", motif " + copie.path("motif").asText());
        });

        // F-68 (§4.7.3) : traçabilité des consultations d'archives.
        etape("F-68", () -> {
            G(V1, "/api/v1/documents/" + dArch);
            g.get("/api/v1/documents/" + dArch + "/download", jeton(V1));
            g.get("/api/v1/versions/" + versionCourante(fiche(V1, dArch)) + "/apercu", jeton(V1));
            attendre(800);
            String u = uid(V1);
            Set<String> a = new LinkedHashSet<>();
            for (JsonNode l : audit("objetId=" + dArch)) if (u.equals(l.path("acteurUtilisateurId").asText())) a.add(l.path("action").asText());
            verif("F-68", a.containsAll(List.of("DOCUMENT_CONSULTE", "DOCUMENT_TELECHARGE")),
                    "Consultations d'un document archivé tracées nominativement (fiche, téléchargement, aperçu)",
                    "actions de qa2val1 sur l'archive : " + a);
        });

        // F-69 (§4.9.3, §4.9.4) : journal d'audit.
        etape("F-69", () -> {
            Set<String> vues = new LinkedHashSet<>();
            for (JsonNode l : contenu(G(ADM, "/api/v1/audit/evenements?taille=200&du=" + enc(auditDebut)).json())) vues.add(l.path("action").asText());
            for (String d : List.of(dF55, dArch)) vues.addAll(actions(d));
            List<String> attendues = List.of("CONNEXION_REUSSIE", "DOCUMENT_CONSULTE", "DOCUMENT_TELECHARGE", "DOCUMENT_DEPOSE", "VERSION_AJOUTEE",
                    "VERSION_RESTAUREE", "METADONNEES_MODIFIEES", "DOCUMENT_VERROUILLE", "DOCUMENT_ARCHIVE", "DOCUMENT_DESARCHIVE", "DOCUMENT_SUPPRIME",
                    "DOCUMENT_PURGE", "DOCUMENT_EXPORTE", "HABILITATION_MODIFIEE", "VALIDATION_APPROUVEE", "VALIDATION_REJETEE", "DOCUMENT_DIFFUSE",
                    "ACCES_REFUSE");
            // Actions antérieures à la partie D (circuits de la partie C, connexions) : recherche par action.
            List<String> manquantes = new ArrayList<>();
            for (String a : attendues)
                if (!vues.contains(a) && contenu(G(ADM, "/api/v1/audit/evenements?taille=1&action=" + a).json()).isEmpty()) manquantes.add(a);
            JsonNode l0 = contenu(G(ADM, "/api/v1/audit/evenements?taille=1&action=DOCUMENT_CONSULTE").json()).get(0);
            boolean complet = !l0.path("horodatage").asText().isBlank() && !l0.path("acteurNom").asText().isBlank() && !l0.path("adresseIp").asText().isBlank()
                    && !l0.path("objetId").asText().isBlank();
            Rep export = g.get("/api/v1/audit/export?format=csv&action=DOCUMENT_CONSULTE&du=" + enc(auditDebut), jeton(ADM));
            verif("F-69", manquantes.isEmpty() && complet && export.code() == 200,
                    "Journal d'audit : consultations, modifications, validations, suppressions et actions du §4.9.4 (qui, quoi, quand, d'où), exportable",
                    "actions attendues absentes " + manquantes + " ; ligne type : " + l0.path("action").asText() + " par « " + l0.path("acteurNom").asText() + " » depuis "
                            + l0.path("adresseIp").asText() + " le " + l0.path("horodatage").asText() + " ; export CSV " + export.code() + " ("
                            + export.octets().length + " octets)");
        });

        // F-70 (§4.9.3, D11) : journaux inaltérables, y compris par l'Administrateur.
        etape("F-70", () -> {
            JsonNode l0 = contenu(G(ADM, "/api/v1/audit/evenements?taille=1").json()).get(0);
            String id = l0.path("id").asText();
            Rep put = J(ADM, "PUT", "/api/v1/audit/evenements/" + id, Map.of("action", "FALSIFIE"));
            Rep del = X(ADM, "DELETE", "/api/v1/audit/evenements/" + id);
            Rep delTout = X(ADM, "DELETE", "/api/v1/audit/evenements");
            Rep verif = J(ADM, "POST", "/api/v1/audit/verifications", null);
            JsonNode rap = verif.json();
            verif("F-70", put.code() / 100 == 4 && del.code() / 100 == 4 && delTout.code() / 100 == 4 && verif.code() == 200 && rap.path("anomalies").size() == 0,
                    "Journal inaltérable par l'application, Administrateur compris ; scellement vérifiable",
                    "modification d'une ligne " + court(put) + ", suppression " + court(del) + ", purge " + court(delTout) + " ; vérification du scellement "
                            + court(verif) + " : " + rap.path("periodesVerifiees") + " période(s), " + rap.path("enregistrementsVerifies") + " enregistrements, anomalies "
                            + rap.path("anomalies").size() + " (droits SQL : voir le constat hors script)");
        });

        // F-71 (§4.9.3) : pattern de journalisation technique de l'Article 50.
        etape("F-71", () -> {
            String chemin = env("GED_RECETTE_JOURNAL", "");
            if (chemin.isBlank()) {
                res("F-71", "NA", "Pattern de l'Article 50", "GED_RECETTE_JOURNAL non fourni");
                return;
            }
            Pattern p = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[[^\\]]*\\] \\[[^\\]]*\\] \\[[^\\]]+\\] - [0-9a-f]*/[0-9a-f]* (TRACE|DEBUG|INFO |WARN |ERROR) - \\S+ - .*");
            List<String> l = Files.readAllLines(Path.of(chemin), StandardCharsets.UTF_8);
            int entetes = 0, conformes = 0, avecUtilisateur = 0;
            String exemple = "";
            for (String s : l) {
                if (!s.matches("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*")) continue; // suites de lignes (piles d'appel)
                entetes++;
                if (p.matcher(s).matches()) conformes++;
                if (s.contains("[" + V1 + "] [")) {
                    avecUtilisateur++;
                    if (exemple.isBlank()) exemple = s.length() > 180 ? s.substring(0, 180) + "…" : s;
                }
            }
            verif("F-71", entetes > 0 && conformes == entetes && avecUtilisateur > 0,
                    "Journal technique au pattern de l'Article 50 (horodatage, username, ip, thread, traceId/spanId, niveau, logger, message)",
                    conformes + "/" + entetes + " lignes conformes, " + avecUtilisateur + " portant l'utilisateur qa2val1 ; exemple « " + exemple + " »");
        });

        // F-72 (§4.10.3) : intégration via API REST documentée.
        etape("F-72", () -> {
            Rep doc = g.get("/v3/api-docs", null);
            if (doc.code() != 200) doc = g.get("/v3/api-docs", jeton(ADM));
            int chemins = doc.code() == 200 ? doc.json().path("paths").size() : 0;
            boolean contrat = doc.code() == 200 && doc.corps().contains("/api/v1/recherches") && doc.corps().contains("/api/v1/documents");
            verif("F-72", chemins >= 80 && contrat,
                    "Intégration via une API REST documentée (OpenAPI)",
                    "OpenAPI " + court(doc) + ", " + chemins + " chemins, contrat d'intégration (recherches, documents, contenu) présent " + contrat);
        });

        // F-73 (§4.10.3) : clé d'API dédiée par application, périmètre configurable.
        etape("F-73", () -> {
            JsonNode app = null;
            for (JsonNode a : G(ADM, "/api/v1/applications").json()) if ("qa2-intranet".equals(a.path("code").asText())) app = a;
            if (app == null) app = J(ADM, "POST", "/api/v1/applications", Map.of("code", "qa2-intranet", "nom", "Intranet (recette qa2)",
                    "description", "Seconde application consommatrice", "adressesAutorisees", List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"),
                    "quotaMinute", 600, "quotaJour", 100000)).json();
            String appId = app.path("id").asText();
            JsonNode k = J(ADM, "POST", "/api/v1/applications/" + appId + "/cles", Map.of("delegation", false, "validiteJours", 30)).json();
            Rep portee = J(ADM, "PUT", "/api/v1/cles-api/" + k.path("details").path("id").asText() + "/portee", Map.of("portee", List.of(
                    Map.of("noeudId", PRJ, "operations", List.of("CONSULTATION", "RECHERCHE")))));
            Map<String, String> h = new LinkedHashMap<>(Map.of("X-API-Key", k.path("cle").asText()));
            // La règle de F-12 sur QA2 Projets (validateur désactivé) est détachée : document utilisable d'emblée.
            Map<String, Object> aucune = new LinkedHashMap<>();
            aucune.put("regleId", null);
            J(ADM, "PUT", "/api/v1/workflow/noeuds/" + PRJ + "/regle", aucune);
            String dPrj = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-d-cle-prj-" + M, type("QA2-PRJ-NOTE", "QA2 Note de projet", PRJ, null, List.of("pdf"), 10, null, "PUBLIC"));
            Rep lPrj = g.appel("GET", "/api/v1/documents/" + dPrj, h, null, null);
            Rep lFin = g.appel("GET", "/api/v1/documents/" + dF55, h, null, null);
            Map<String, String> hd = new LinkedHashMap<>(h);
            hd.put("Idempotency-Key", java.util.UUID.randomUUID().toString());
            byte[] pdf = Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"));
            Rep depot = g.deposer(hd, pdf, "x.pdf", "application/pdf", Map.of("name", "qa2-d-cle-depot-" + M, "typeDocumentId",
                    type("QA2-PRJ-NOTE", "QA2 Note de projet", PRJ, null, List.of("pdf"), 10, null, "PUBLIC")));
            int nbApps = G(ADM, "/api/v1/applications").json().size();
            verif("F-73", portee.code() == 200 && lPrj.code() == 200 && lFin.code() / 100 == 4 && depot.code() / 100 == 4 && nbApps >= 2,
                    "Une clé d'API par application consommatrice, avec son périmètre (nœuds, opérations)",
                    nbApps + " applications ; clé de l'intranet sur QA2 Projets (consultation, recherche) : lecture Projets " + lPrj.code() + ", lecture Finance "
                            + court(lFin) + ", dépôt (opération non accordée) " + court(depot));
        });

        // F-74 (§4.10.3) : contrat d'interface avec le bureau d'ordre — constat documentaire hors script.
        res("F-74", "NA", "Contrat d'interface détaillé avec le bureau d'ordre", "constat documentaire (voir RESULTATS-FONCTIONNELS.md)");

        // F-75 (§4.13) : échelle de confidentialité, par défaut du type, croisée avec les habilitations.
        etape("F-75", () -> {
            String t = type("QA2-CONF-" + m1, "QA2 Pièce confidentielle par défaut " + M, FIN26, null, List.of("pdf"), 10, null, "CONFIDENTIEL");
            String d = doc(ADM, "pdf_texte_fr_facture.pdf", "qa2-d-confdef-" + M, t);
            String niveau = fiche(ADM, d).path("confidentialite").asText();
            boolean v1Avant = G(V1, "/api/v1/documents/" + d).code() == 200;
            Rep abaisse = J(ADM, "PUT", "/api/v1/documents/" + d, Map.of("confidentialite", "PUBLIC"));
            boolean v1Apres = G(V1, "/api/v1/documents/" + d).code() == 200;
            Rep parStd = J(V1, "PUT", "/api/v1/documents/" + d, Map.of("confidentialite", "CONFIDENTIEL"));
            attendre(500);
            boolean trace = auditContient("objetId=" + d, l -> l.path("action").asText().equals("CONFIDENTIALITE_MODIFIEE"));
            Rep inconnu = deposer(ADM, "pdf_texte_fr_facture.pdf", "qa2-d-conf-x-" + M, T_NOTE, champs("confidentialite", "SECRET"), null);
            verif("F-75", "CONFIDENTIEL".equals(niveau) && !v1Avant && abaisse.code() == 200 && v1Apres && trace,
                    "Échelle de confidentialité (Public, Privé, Confidentiel) : niveau par défaut du type, modifiable par document, croisée avec les habilitations, tracée",
                    "niveau hérité du type " + niveau + ", standard avant " + v1Avant + ", abaissement " + court(abaisse) + " → standard " + v1Apres
                            + ", relèvement par un standard " + court(parStd) + ", audit CONFIDENTIALITE_MODIFIEE " + trace + " ; niveau hors échelle (SECRET) " + court(inconnu));
        });

        // F-76 (§4.14) : loi 09-08, accès restreint et traçabilité des données personnelles.
        etape("F-76", () -> {
            dConfRh = doc(RH, "pdf_texte_fr_facture.pdf", "qa2-d-dossier-agent-" + M, T_CONTRAT);
            String niveau = fiche(RH, dConfRh).path("confidentialite").asText();
            Rep lecteurRh = G(V1, "/api/v1/documents/" + dConfRh); // LECTEUR sur QA2 RH, sans Voir privé
            Rep dg = G(DG, "/api/v1/documents/" + dConfRh);
            Rep rh = G(RH, "/api/v1/documents/" + dConfRh);
            Rep pt = G(V1, "/api/v1/recherche/plein-texte?taille=50&q=" + enc("qa2-d-dossier-agent-" + M));
            attendre(800);
            List<JsonNode> l = audit("objetId=" + dConfRh);
            boolean consult = false, refus = false;
            for (JsonNode x : l) {
                if (x.path("action").asText().equals("DOCUMENT_CONSULTE") && uid(RH).equals(x.path("acteurUtilisateurId").asText())) consult = true;
                if (uid(V1).equals(x.path("acteurUtilisateurId").asText()) && !"SUCCES".equals(x.path("resultat").asText())) refus = true;
            }
            verif("F-76", "PRIVE".equals(niveau) && lecteurRh.code() == 404 && rh.code() == 200 && consult && !pt.corps().contains(dConfRh),
                    "Données personnelles (contrat RH, Privé par défaut) : accès restreint aux habilités, consultations et refus tracés",
                    "niveau " + niveau + " ; lecteur de l'espace RH sans Voir privé " + court(lecteurRh) + ", DG " + court(dg) + ", Responsable RH " + rh.code()
                            + " ; absent de la recherche du lecteur " + !pt.corps().contains(dConfRh) + " ; consultation du Responsable RH tracée " + consult
                            + ", refus du lecteur tracé " + refus);
        });

        // F-77 (§4.15) : chiffrement de tous les documents.
        etape("F-77", () -> {
            String racine = env("GED_RECETTE_COFFRE", "");
            if (racine.isBlank()) {
                res("F-77", "NA", "Chiffrement de tous les documents", "GED_RECETTE_COFFRE non fourni");
                return;
            }
            byte[] clair = Files.readAllBytes(donnees.resolve("pdf_texte_fr_facture.pdf"));
            byte[] motif = java.util.Arrays.copyOfRange(clair, 0, Math.min(64, clair.length));
            int fichiers = 0, enc = 0, enClair = 0;
            try (var s = Files.walk(Path.of(racine))) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    fichiers++;
                    if (f.getFileName().toString().endsWith(".enc")) enc++;
                    byte[] b = Files.readAllBytes(f);
                    String debut = new String(b, 0, Math.min(8, b.length), StandardCharsets.ISO_8859_1);
                    if (debut.startsWith("%PDF") || debut.startsWith("PK") || debut.startsWith("\u0089PNG") || indexOf(b, motif) >= 0) enClair++;
                }
            }
            Rep tel = g.get("/api/v1/documents/" + dF55 + "/download", jeton(V1));
            verif("F-77", fichiers > 0 && enc == fichiers && enClair == 0 && tel.code() == 200,
                    "Tous les fichiers chiffrés au repos ; déchiffrement transparent pour l'utilisateur habilité",
                    fichiers + " fichiers dans le référentiel, " + enc + " en .enc, " + enClair + " lisible(s) en clair ; téléchargement déchiffré " + tel.code());
        });
    }

    static int indexOf(byte[] b, byte[] m) {
        outer:
        for (int i = 0; i + m.length <= b.length; i++) {
            for (int j = 0; j < m.length; j++) if (b[i + j] != m[j]) continue outer;
            return i;
        }
        return -1;
    }
}
