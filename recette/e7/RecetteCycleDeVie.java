import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette E7 — cycle de vie, de bout en bout par l'API (DAT V3 §6.1.4, §12.5, §12.6, §12.10 ;
 * décisions D9 et D10).
 *
 * <p>Critère de sortie E7 : un document archivé est intouchable, possède sa copie PDF/A validée,
 * et un export ZIP ne contient que ce que l'utilisateur peut voir. S'y ajoute la purge, qui doit
 * rendre le fichier définitivement illisible (destruction de la clé de données).
 *
 * <p>Prérequis : jeu d'habilitations de la recette E3. Contrôles sur disque et en base
 * facultatifs : GED_STOCKAGE_RACINE (racine des .enc) et GED_E7_JDBC (URL JDBC de lecture,
 * par exemple jdbc:postgresql://localhost:5432/ged_qa?user=postgres&currentSchema=ged).
 */
public class RecetteCycleDeVie extends ClientGed {

    RecetteCycleDeVie(String url) {
        super(url);
    }

    public static void main(String[] args) throws Exception {
        RecetteCycleDeVie g = new RecetteCycleDeVie(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path donnees = Path.of(env("GED_DONNEES", "recette/donnees"));
        String racine = env("GED_STOCKAGE_RACINE", null);
        String jdbc = env("GED_E7_JDBC", null);
        String tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String tTiers = g.connecter(env("GED_E3_TIERS", "yalaoui"), mdp);
        String tSans = g.connecter(env("GED_E3_SANS_DROIT", "nidrissi"), mdp);

        Map<String, JsonNode> noeuds = new LinkedHashMap<>();
        arbre(g.get("/api/v1/workspaces/tree", tAdmin).json(), noeuds);
        String A = noeuds.get(env("GED_E3_NOEUD_A", "Comptabilité")).path("id").asText();
        String Ae = noeuds.get(env("GED_E3_NOEUD_A_ENFANT", "2026")).path("id").asText();
        String typeA = null, typeAe = null;
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content")) {
            if (t.path("code").asText().equals(env("GED_E7_TYPE_A", "TD-FACT"))) typeA = t.path("id").asText();
            if (t.path("workspace").path("id").asText().equals(Ae) && typeAe == null) typeAe = t.path("id").asText();
        }
        String marque = "QAE7" + Long.toString(System.currentTimeMillis(), 36);
        Path pdf = donnees.resolve("pdf_texte_fr_convention.pdf");
        Path docx = donnees.resolve("document_fr.docx");

        // ================= 1. Archivage d'un document PDF (§12.6, §6.1.4)
        String dPdf = id(g.deposer(tDep, pdf, marque + "-archive-pdf", typeA, null));
        Rep arch = g.appel("POST", "/api/v1/documents/" + dPdf + "/archivage", tDep, null, null);
        JsonNode cons = g.get("/api/v1/documents/" + dPdf + "/conservation", tDep).json();
        JsonNode copie = cons.path("copie");
        verif("E7-01", arch.code() == 200 && arch.json().path("issue").asText().equals("ARCHIVE") && cons.path("statutConservation").asText().equals("ARCHIVE"),
                "Archivage manuel d'un document : statut ARCHIVE [12.6, D10]", "HTTP " + arch.code() + " " + arch.json().path("issue").asText() + ", statut " + cons.path("statutConservation").asText());
        verif("E7-02", copie.path("format").asText().toUpperCase().contains("PDF/A-2") || copie.path("format").asText().toUpperCase().contains("PDFA-2")
                        || copie.path("format").asText().toUpperCase().contains("PDF/A"),
                "Copie de conservation PDF/A-2 produite et validée par veraPDF, empreinte enregistrée [6.1.4]", copie.toString());
        res("E7-02b", copie.path("statut").asText().matches("(?i)VALIDE|VALIDEE|CONFORME|OK") ? "OK" : "ECHEC",
                "Validation veraPDF de la copie de conservation : conforme [6.1.4]", "statut " + copie.path("statut").asText() + ", méthode " + copie.path("methode").asText());

        // Intouchable : toute écriture refusée, pour tous les rôles (DG et Administrateur compris)
        List<String> acceptees = new ArrayList<>();
        for (String[] qui : new String[][]{{"DG", tDep}, {"ADMIN", tAdmin}}) {
            Rep put = g.json("PUT", "/api/v1/documents/" + dPdf, qui[1], Map.of("name", "renomme-" + marque));
            Rep ver = versement(g, qui[1], dPdf, Files.readAllBytes(pdf));
            Rep verrou = g.appel("PATCH", "/api/v1/documents/" + dPdf + "/verrou?verrouille=true", qui[1], null, null);
            Rep del = g.appel("DELETE", "/api/v1/documents/" + dPdf, qui[1], null, null);
            Rep rat = g.json("POST", "/api/v1/documents/" + dPdf + "/rattachements", qui[1], Map.of("noeudId", Ae));
            for (Object[] x : new Object[][]{{"PUT fiche", put}, {"versement", ver}, {"verrou", verrou}, {"suppression", del}, {"rattachement", rat}}) {
                if (((Rep) x[1]).code() / 100 == 2) acceptees.add(qui[0] + " " + x[0] + " → " + ((Rep) x[1]).code());
            }
        }
        JsonNode apres = g.get("/api/v1/documents/" + dPdf, tDep).json();
        verif("E7-03", acceptees.isEmpty() && !apres.path("name").asText().startsWith("renomme") && apres.path("versions").size() == 1,
                "Document archivé intouchable : fiche, versement, verrou, suppression, rattachement refusés, y compris pour la DG et l'Administrateur [12.6, critère E7]",
                acceptees.isEmpty() ? "5 écritures × 2 rôles refusées" : "acceptées : " + acceptees);
        // §6.1.4 : la copie de conservation est servie par défaut, l'original reste disponible.
        Rep dl = g.get("/api/v1/documents/" + dPdf + "/download", tDep);
        Rep dlOrig = g.get("/api/v1/documents/" + dPdf + "/download?original=true", tDep);
        verif("E7-04", dl.code() == 200 && sha256(dl.octets()).equals(copie.path("empreinte").asText())
                        && dlOrig.code() == 200 && sha256(dlOrig.octets()).equals(sha256(Files.readAllBytes(pdf))),
                "Document archivé : copie PDF/A servie par défaut (empreinte enregistrée exacte), original conservé et identique [6.1.4, 12.6]",
                "défaut " + dl.code() + " (empreinte copie " + sha256(dl.octets()).equals(copie.path("empreinte").asText()) + "), original " + dlOrig.code());
        Rep desT = g.appel("DELETE", "/api/v1/documents/" + dPdf + "/archivage", tTiers, null, null);
        verif("E7-05", desT.code() == 403 || desT.code() == 404, "Désarchivage refusé sans la permission Archiver (Utilisateur standard) [12.6]", "HTTP " + desT.code());
        Rep des = g.appel("DELETE", "/api/v1/documents/" + dPdf + "/archivage", tAdmin, null, null);
        Rep put2 = g.json("PUT", "/api/v1/documents/" + dPdf, tDep, Map.of("name", marque + "-apres-desarchivage"));
        verif("E7-06", des.code() / 100 == 2 && g.get("/api/v1/documents/" + dPdf + "/conservation", tDep).json().path("statutConservation").asText().equals("ACTIF"),
                "Désarchivage par l'Administrateur : document de nouveau actif [12.6]", "désarchivage " + des.code() + ", modification ensuite " + put2.code());

        // DOCX : conversion obligatoire en PDF/A (D10), LibreOffice SIMULÉ sur ce poste
        String dDocx = id(g.deposer(tDep, docx, marque + "-archive-docx", typeA, null));
        Rep archD = g.appel("POST", "/api/v1/documents/" + dDocx + "/archivage", tDep, null, null);
        JsonNode copieD = g.get("/api/v1/documents/" + dDocx + "/conservation", tDep).json().path("copie");
        verif("E7-07", archD.code() == 200 && archD.json().path("issue").asText().equals("ARCHIVE") && copieD.path("format").asText().toUpperCase().contains("PDF"),
                "Fichier Word archivable, converti en PDF/A-2 à l'archivage [D10, 6.1.4] (LibreOffice simulé)", "HTTP " + archD.code() + " " + archD.json() + " ; copie " + copieD);

        // ================= 2. Archivage d'un dossier entier (D10)
        String dDos1 = id(g.deposer(tDep, pdf, marque + "-dossier-1", typeAe, null));
        String dDos2 = id(g.deposer(tDep, pdf, marque + "-dossier-2", typeAe, null));
        Rep job = g.appel("POST", "/api/v1/archivage/dossiers/" + Ae, tDep, null, null);
        String jobId = job.json().path("id").asText();
        JsonNode etatJob = job.json();
        for (int i = 0; i < 60 && !etatJob.path("etat").asText().matches("(?i)TERMINE|ECHEC|ANNULE"); i++) {
            Thread.sleep(2000);
            etatJob = g.get("/api/v1/archivage/jobs/" + jobId, tDep).json();
        }
        String s1 = g.get("/api/v1/documents/" + dDos1 + "/conservation", tDep).json().path("statutConservation").asText();
        String s2 = g.get("/api/v1/documents/" + dDos2 + "/conservation", tDep).json().path("statutConservation").asText();
        String drapeau = g.get("/api/v1/archivage/dossiers/" + Ae, tDep).corps();
        verif("E7-08", job.code() / 100 == 2 && etatJob.path("etat").asText().equalsIgnoreCase("TERMINE") && s1.equals("ARCHIVE") && s2.equals("ARCHIVE"),
                "Archivage manuel d'un dossier entier : traitement de fond, tous ses documents archivés, drapeau sur le dossier [D10, 12.6]",
                "job " + job.code() + " → " + etatJob.path("etat").asText() + " (" + etatJob.path("archives").asText() + " archivés, "
                        + etatJob.path("anomalies").asText() + " anomalies), documents " + s1 + "/" + s2 + ", dossier " + drapeau);
        Rep depotDansArchive = g.deposer(tDep, pdf, marque + "-apres-drapeau", typeAe, null);
        res("E7-09", depotDansArchive.code() / 100 == 2 ? "AVERT" : "OK",
                "Dépôt dans un dossier marqué archivé", "HTTP " + depotDansArchive.code() + " " + depotDansArchive.codeMetier()
                        + (depotDansArchive.code() / 100 == 2 ? " (accepté : comportement à confirmer au regard de D10)" : ""));
        g.appel("DELETE", "/api/v1/archivage/dossiers/" + Ae, tAdmin, null, null);
        for (String d : List.of(dDos1, dDos2)) g.appel("DELETE", "/api/v1/documents/" + d + "/archivage", tAdmin, null, null);

        // ================= 3. Export ZIP (§12.10) : ne contient que ce que l'utilisateur voit
        String dPub = id(g.deposer(tDep, pdf, marque + "-export-public", typeA, "PUBLIC"));
        String dPriv = id(g.deposer(tDep, pdf, marque + "-export-prive", typeA, "PRIVE"));
        String dConf = id(g.deposer(tDep, pdf, marque + "-export-confidentiel", typeA, "CONFIDENTIEL"));
        String dEnfant = id(g.deposer(tDep, pdf, marque + "-export-enfant", typeAe, "PUBLIC"));
        Rep zipT = g.appel("POST", "/api/v1/exports/dossiers/" + A, tTiers, null, null);
        Map<String, byte[]> entrees = new LinkedHashMap<>();
        if (zipT.code() == 200) {
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zipT.octets()), StandardCharsets.UTF_8)) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) entrees.put(e.getName(), z.readAllBytes());
            }
        }
        // Le manifeste est rangé dans le dossier racine de l'archive (<dossier>/manifeste.csv).
        String manifeste = entrees.entrySet().stream().filter(e -> e.getKey().endsWith("manifeste.csv"))
                .map(e -> new String(e.getValue(), StandardCharsets.UTF_8)).findFirst().orElse("");
        String noms = String.join(" ", entrees.keySet());
        boolean aPub = manifeste.contains(dPub), aEnf = manifeste.contains(dEnfant);
        boolean fuite = manifeste.contains(dPriv) || manifeste.contains(dConf) || noms.contains("export-prive") || noms.contains("export-confidentiel")
                || manifeste.contains("export-prive") || manifeste.contains("export-confidentiel");
        verif("E7-10", zipT.code() == 200 && aPub && aEnf && !fuite,
                "Export ZIP du TIERS : documents visibles (sous-dossiers compris), privé et confidentiel d'autrui omis sans trace [12.10, 12.3, critère E7]",
                "HTTP " + zipT.code() + ", " + entrees.size() + " entrées, public " + aPub + ", sous-dossier " + aEnf + ", fuite " + fuite);
        boolean omissionAnnoncee = manifeste.toLowerCase().contains("omis") || zipT.entetes().map().keySet().stream().anyMatch(h -> h.toLowerCase().contains("omis"));
        verif("E7-11", !omissionAnnoncee, "Aucun compteur de documents omis dans l'archive ni dans la réponse [12.3]", omissionAnnoncee ? "omission signalée" : "");
        String entete = manifeste.lines().findFirst().orElse("");
        List<String> colonnes = List.of("identifiant", "chemin", "nom", "type", "confidentialit", "statut", "version", "empreinte");
        List<String> manquantes = colonnes.stream().filter(c -> !entete.toLowerCase().contains(c)).toList();
        boolean empreinteJuste = true;
        for (String ligne : manifeste.lines().skip(1).toList()) {
            if (ligne.contains(dPub)) empreinteJuste = ligne.contains(sha256(Files.readAllBytes(pdf)));
        }
        verif("E7-12", manifeste.startsWith("﻿") && manquantes.isEmpty() && empreinteJuste,
                "manifeste.csv UTF-8 : identifiant, chemins, nom, type, confidentialité, statut, version, empreinte SHA-256 exacte [12.10]",
                "colonnes manquantes " + manquantes + ", empreinte du public exacte " + empreinteJuste + " ; en-tête « " + entete + " »");
        byte[] contenuPub = null;
        for (var e : entrees.entrySet()) if (e.getKey().contains("export-public")) contenuPub = e.getValue();
        verif("E7-13", contenuPub != null && sha256(contenuPub).equals(sha256(Files.readAllBytes(pdf))),
                "Fichiers de l'archive déchiffrés au fil de l'eau, identiques aux originaux [12.10]", contenuPub == null ? "entrée absente" : "");
        Rep zipS = g.appel("POST", "/api/v1/exports/dossiers/" + A, tSans, null, null);
        verif("E7-14", zipS.code() == 404, "Export d'un dossier hors périmètre : 404, ni nom ni existence révélés [6.2.3 A01, P5]",
                "HTTP " + zipS.code() + " " + zipS.entetes().firstValue("Content-Disposition").orElse("") + ", " + zipS.octets().length + " octets");

        // ================= 4. Purge (§12.5) : destruction cryptographique
        Set<String> avant = encs(racine);
        String dPurge = id(g.deposer(tDep, pdf, marque + "-purge", typeA, null));
        Set<String> nouveaux = encs(racine);
        nouveaux.removeAll(avant);
        Rep purgeActif = g.appel("POST", "/api/v1/documents/" + dPurge + "/purge", tAdmin, null, null);
        verif("E7-15", purgeActif.code() == 409 || purgeActif.code() == 422 || purgeActif.code() == 400,
                "Purge d'un document hors corbeille refusée [12.5]", "HTTP " + purgeActif.code() + " " + purgeActif.codeMetier());
        g.appel("DELETE", "/api/v1/documents/" + dPurge, tDep, null, null);
        Rep purgeDg = g.appel("POST", "/api/v1/documents/" + dPurge + "/purge", tDep, null, null);
        verif("E7-16", purgeDg.code() == 403 || purgeDg.code() == 404, "Purge réservée à la permission Purger (la DG ne l'a pas) [12.5]", "HTTP " + purgeDg.code());
        Rep purge = g.appel("POST", "/api/v1/documents/" + dPurge + "/purge", tAdmin, null, null);
        Rep apresPurge = g.get("/api/v1/documents/" + dPurge, tAdmin);
        Rep dlPurge = g.get("/api/v1/documents/" + dPurge + "/download", tAdmin);
        Rep corbeille = g.get("/api/v1/documents/trashed?size=200&search=" + enc(marque + "-purge"), tAdmin);
        verif("E7-17", purge.code() / 100 == 2 && apresPurge.code() == 404 && dlPurge.code() == 404 && !corbeille.corps().contains(dPurge),
                "Purge depuis la corbeille : document introuvable partout (fiche, téléchargement, corbeille) [12.5]",
                "purge " + purge.code() + ", fiche " + apresPurge.code() + ", téléchargement " + dlPurge.code());
        if (racine != null) {
            Set<String> restants = encs(racine);
            boolean fichierDetruit = !nouveaux.isEmpty() && nouveaux.stream().noneMatch(restants::contains);
            verif("E7-18", fichierDetruit, "Purge : fichier chiffré supprimé du stockage [12.5]", "fichiers du dépôt " + nouveaux + ", encore présents : "
                    + nouveaux.stream().filter(restants::contains).toList());
        } else {
            res("E7-18", "NA", "Purge : fichier chiffré supprimé du stockage", "GED_STOCKAGE_RACINE non fourni");
        }
        if (jdbc != null && !nouveaux.isEmpty()) {
            try (Connection c = DriverManager.getConnection(jdbc)) {
                int cles = 0, lignes = 0;
                for (String f : nouveaux) {
                    try (PreparedStatement p = c.prepareStatement("SELECT count(*) FROM cle_fichier WHERE id = ?::uuid")) {
                        p.setString(1, f);
                        try (ResultSet r = p.executeQuery()) { r.next(); cles += r.getInt(1); }
                    }
                }
                try (PreparedStatement p = c.prepareStatement("SELECT (SELECT count(*) FROM document WHERE id = ?::uuid) + (SELECT count(*) FROM version_document WHERE document_id = ?::uuid) + (SELECT count(*) FROM document_texte WHERE document_id = ?::uuid)")) {
                    for (int i = 1; i <= 3; i++) p.setString(i, dPurge);
                    try (ResultSet r = p.executeQuery()) { r.next(); lignes = r.getInt(1); }
                }
                verif("E7-19", cles == 0 && lignes == 0, "Purge : clé de données (cle_fichier) détruite, document, versions et texte supprimés [12.5, 6.1.2]",
                        "clés restantes " + cles + ", lignes métier restantes " + lignes);
            }
        } else {
            res("E7-19", "NA", "Purge : destruction de la clé de données en base", "GED_E7_JDBC ou GED_STOCKAGE_RACINE non fourni");
        }

        // ================= 5. Prévisualisation (§6.1.6) : déchiffrée en flux, droits appliqués
        String dApPdf = id(g.deposer(tDep, pdf, marque + "-apercu-pdf", typeA, "PUBLIC"));
        String vApPdf = g.get("/api/v1/documents/" + dApPdf, tDep).json().path("versions").path(0).path("id").asText();
        Rep apPdf = g.get("/api/v1/versions/" + vApPdf + "/apercu", tTiers);
        verif("E7-20", apPdf.code() == 200 && apPdf.entetes().firstValue("Content-Type").orElse("").startsWith("application/pdf")
                        && sha256(apPdf.octets()).equals(sha256(Files.readAllBytes(pdf)))
                        && !apPdf.entetes().firstValue("Content-Disposition").orElse("").startsWith("attachment"),
                "Aperçu d'un PDF : servi déchiffré, en ligne, identique à l'original, au TIERS habilité [6.1.6]",
                "HTTP " + apPdf.code() + " " + apPdf.entetes().firstValue("Content-Type").orElse("") + " " + apPdf.entetes().firstValue("Content-Disposition").orElse(""));
        String dApDocx = id(g.deposer(tDep, docx, marque + "-apercu-docx", typeA, "PUBLIC"));
        String vApDocx = g.get("/api/v1/documents/" + dApDocx, tDep).json().path("versions").path(0).path("id").asText();
        Rep apDocx = g.get("/api/v1/versions/" + vApDocx + "/apercu", tTiers);
        Rep apDocx2 = g.get("/api/v1/versions/" + vApDocx + "/apercu", tTiers);
        verif("E7-21", apDocx.code() == 200 && new String(apDocx.octets(), 0, Math.min(5, apDocx.octets().length), StandardCharsets.ISO_8859_1).equals("%PDF-")
                        && apDocx2.code() == 200 && sha256(apDocx2.octets()).equals(sha256(apDocx.octets())),
                "Aperçu d'un DOCX : converti en PDF (LibreOffice simulé), servi depuis le cache au second appel [6.1.6]",
                "HTTP " + apDocx.code() + " puis " + apDocx2.code() + ", " + apDocx.octets().length + " octets");
        Rep apSans = g.get("/api/v1/versions/" + vApPdf + "/apercu", tSans);
        verif("E7-22", apSans.code() == 404, "Aperçu hors périmètre : 404 [6.1.6, 6.2.3]", "HTTP " + apSans.code());

        info("documents conservés (marqueur " + marque + ")");
        System.exit(bilan("E7 cycle de vie " + g.url));
    }

    static Rep versement(ClientGed g, String jeton, String doc, byte[] pdf) throws Exception {
        String f = "----qa" + UUID.randomUUID();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.writeBytes(("--" + f + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"v2.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        buf.writeBytes(pdf);
        buf.writeBytes(("\r\n--" + f + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return g.appel("POST", "/api/v1/documents/" + doc + "/versions", jeton, "multipart/form-data; boundary=" + f, buf.toByteArray());
    }

    static String id(Rep r) {
        if (r.code() / 100 != 2) throw new IllegalStateException("dépôt refusé : HTTP " + r.code() + " " + r.corps());
        return r.json().path("id").asText();
    }

    static Set<String> encs(String racine) throws Exception {
        Set<String> r = new HashSet<>();
        if (racine == null) return r;
        try (Stream<Path> s = Files.walk(Path.of(racine))) {
            s.filter(p -> p.toString().endsWith(".enc")).forEach(p -> r.add(p.getFileName().toString().replace(".enc", "")));
        }
        return r;
    }

    static void arbre(JsonNode n, Map<String, JsonNode> acc) {
        for (JsonNode x : n) {
            acc.put(x.path("name").asText(), x);
            arbre(x.path("children"), acc);
        }
    }
}
