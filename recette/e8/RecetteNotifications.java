import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Recette des notifications (DAT V3 §12.9, lot livré par dev2 avant E8) : boîte d'envoi,
 * e-mail par le relais SMTP et pastille dans l'application, TROIS cas exclusivement (circuits de
 * validation, attribution d'un accès à un espace, fin de conservation), préférence e-mail.
 *
 * <p>Seul le cas « attribution d'un accès à un espace » a un déclencheur livré à ce jour (les
 * circuits et l'échéance arrivent avec E8) : il est exercé, et l'on vérifie qu'AUCUNE autre action
 * (dépôt, rattachement, désignation, archivage, export, purge, retrait d'accès) ne notifie.
 *
 * <p>Relais SIMULÉ sur le poste : recette/lib/SmtpSimule.java (GreenMail), qui écrit chaque
 * message reçu dans GED_E8_COURRIELS. Prérequis : jeu d'habilitations de la recette E3.
 */
public class RecetteNotifications extends ClientGed {

    RecetteNotifications(String url) {
        super(url);
    }

    static RecetteNotifications g;

    static List<String> courriels(Path dossier) throws Exception {
        List<String> r = new ArrayList<>();
        if (!Files.isDirectory(dossier)) return r;
        try (Stream<Path> s = Files.list(dossier)) {
            for (Path p : s.sorted().toList()) r.add(Files.readString(p, StandardCharsets.UTF_8));
        }
        return r;
    }

    static long nonLues(String jeton) throws Exception {
        return g.get("/api/v1/notifications/compteur", jeton).json().path("nonLues").asLong(-1);
    }

    public static void main(String[] args) throws Exception {
        g = new RecetteNotifications(env("GED_URL", "http://localhost:18084"));
        String mdp = env("GED_RECETTE_MOT_DE_PASSE", null);
        Path pdf = Path.of(env("GED_DONNEES", "recette/donnees")).resolve("pdf_texte_fr_convention.pdf");
        Path boite = Path.of(env("GED_E8_COURRIELS", "courriels"));
        String cTiers = env("GED_E3_TIERS", "yalaoui");
        String tAdmin = g.connecter(env("GED_E3_ADMIN", "sbennani"), mdp);
        String tDep = g.connecter(env("GED_E3_DEPOSANT", "kelfassi"), mdp);
        String tTiers = g.connecter(cTiers, mdp);
        JsonNode moi = g.get("/api/v1/auth/me", tTiers).json();
        String uTiers = moi.path("id").asText(), courriel = moi.path("email").asText();
        Map<String, String> noeuds = new LinkedHashMap<>();
        pile(g.get("/api/v1/workspaces/tree", tAdmin).json(), noeuds);
        String A = noeuds.get("Comptabilité"), C = noeuds.get("Projets"), Be = noeuds.get("Contrats");
        String typeA = null;
        for (JsonNode t : g.get("/api/v1/type-documents?size=200", tAdmin).json().path("content"))
            if (t.path("code").asText().equals("TD-FACT")) typeA = t.path("id").asText();
        g.json("PUT", "/api/v1/notifications/preferences", tTiers, Map.of("courrielActif", true));
        g.appel("POST", "/api/v1/notifications/lecture", tTiers, null, null);
        int mails0 = courriels(boite).size();
        String m = Long.toString(System.currentTimeMillis(), 36);

        // ---------- actions qui NE doivent PAS notifier
        String d = g.deposer(tDep, pdf, "qae8-" + m + "-conf", typeA, "CONFIDENTIEL").json().path("id").asText();
        g.json("POST", "/api/v1/documents/" + d + "/designes", tDep, Map.of("utilisateurId", uTiers));
        g.json("POST", "/api/v1/documents/" + d + "/rattachements", tDep, Map.of("noeudId", C));
        g.appel("POST", "/api/v1/documents/" + d + "/archivage", tDep, null, null);
        g.appel("DELETE", "/api/v1/documents/" + d + "/archivage", tAdmin, null, null);
        g.appel("POST", "/api/v1/exports/dossiers/" + A, tTiers, null, null);
        g.appel("DELETE", "/api/v1/documents/" + d, tDep, null, null);
        g.appel("POST", "/api/v1/documents/" + d + "/purge", tAdmin, null, null);
        Thread.sleep(40_000); // relève de la boîte d'envoi : 30 s
        long n1 = nonLues(tTiers);
        int mails1 = courriels(boite).size();
        verif("N-01", n1 == 0 && mails1 == mails0,
                "Dépôt, désignation, rattachement, archivage, export, purge : aucune notification (trois cas exclusivement) [12.9]",
                "non lues " + n1 + ", e-mails nouveaux " + (mails1 - mails0));

        // ---------- attribution d'un accès à un espace : notifiée (pastille et e-mail)
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("sujetType", "UTILISATEUR");
        h.put("sujetId", uTiers);
        h.put("roleId", "0192a000-0000-7000-8000-000000000004");
        h.put("noeudId", C);
        h.put("ruptureHeritage", false);
        Rep hab = g.json("POST", "/api/v1/admin/habilitations", tAdmin, h);
        String idHab = hab.json().path("id").asText();
        long n2 = 0;
        List<String> nouveaux = List.of();
        for (int i = 0; i < 30 && (n2 == 0 || nouveaux.isEmpty()); i++) {
            Thread.sleep(2000);
            n2 = nonLues(tTiers);
            List<String> tous = courriels(boite);
            nouveaux = tous.subList(mails1, tous.size());
        }
        JsonNode notif = g.get("/api/v1/notifications?nonLues=true", tTiers).json().path("content").path(0);
        verif("N-02", hab.code() == 201 && n2 == 1 && notif.path("type").asText().equals("ACCES_ESPACE_ATTRIBUE"),
                "Attribution d'un accès à un espace : notification dans l'application (pastille) [12.9, 12.2.3]",
                "habilitation " + hab.code() + ", non lues " + n2 + ", type " + notif.path("type").asText());
        String mail = nouveaux.isEmpty() ? "" : nouveaux.get(0);
        verif("N-03", nouveaux.size() == 1 && mail.contains(courriel),
                "Attribution d'un accès : e-mail expédié par le relais SMTP au bénéficiaire [12.9] (relais simulé)",
                nouveaux.size() + " e-mail(s)" + (mail.isEmpty() ? "" : " : " + mail.lines().filter(l -> l.startsWith("A:") || l.startsWith("Sujet:")).toList()));

        // Lecture
        Rep lu = g.appel("POST", "/api/v1/notifications/" + notif.path("id").asText() + "/lecture", tTiers, null, null);
        verif("N-04", lu.code() == 200 && nonLues(tTiers) == 0, "Notification marquée lue : pastille remise à zéro", "HTTP " + lu.code());

        // Retrait : aucune notification
        g.appel("DELETE", "/api/v1/admin/habilitations/" + idHab, tAdmin, null, null);
        Thread.sleep(35_000);
        int mails3 = courriels(boite).size();
        verif("N-05", nonLues(tTiers) == 0 && mails3 == mails1 + 1, "Retrait d'un accès : aucune notification (attribution seule) [12.9]",
                "non lues " + nonLues(tTiers) + ", e-mails " + (mails3 - mails1 - 1) + " de plus");

        // Préférence : e-mail désactivé, la pastille subsiste
        Rep pref = g.json("PUT", "/api/v1/notifications/preferences", tTiers, Map.of("courrielActif", false));
        h.put("noeudId", Be);
        Rep hab2 = g.json("POST", "/api/v1/admin/habilitations", tAdmin, h);
        Thread.sleep(40_000);
        int mails4 = courriels(boite).size();
        long n4 = nonLues(tTiers);
        verif("N-06", pref.code() == 200 && hab2.code() == 201 && n4 == 1 && mails4 == mails3,
                "Préférence « e-mail désactivé » : notification dans l'application seulement [12.9]",
                "non lues " + n4 + ", e-mails nouveaux " + (mails4 - mails3));
        g.appel("DELETE", "/api/v1/admin/habilitations/" + hab2.json().path("id").asText(), tAdmin, null, null);
        g.json("PUT", "/api/v1/notifications/preferences", tTiers, Map.of("courrielActif", true));
        g.appel("POST", "/api/v1/notifications/lecture", tTiers, null, null);

        // Isolement : un utilisateur ne voit que ses notifications
        Rep autres = g.get("/api/v1/notifications", tDep);
        verif("N-07", autres.code() == 200 && !autres.corps().contains(notif.path("id").asText()),
                "Chaque utilisateur ne voit que ses propres notifications", "HTTP " + autres.code());

        // Audit de l'expédition, sans adresse ni texte
        JsonNode ev = g.get("/api/v1/audit/evenements?action=NOTIFICATION_ENVOYEE&taille=5", tAdmin).json().path("content").path(0);
        verif("N-08", !ev.isMissingNode() && !ev.toString().contains(courriel), "Expédition auditée (NOTIFICATION_ENVOYEE) sans adresse e-mail [12.9, 7.4.1]",
                ev.isMissingNode() ? "aucun événement" : ev.path("action").asText());
        res("N-09", "NA", "Circuits de validation (ouverture, décision, annulation) et fin de conservation", "déclencheurs livrés avec E8 : à recetter à la livraison");
        System.exit(bilan("Notifications " + g.url));
    }

    static void pile(JsonNode n, Map<String, String> acc) {
        for (JsonNode x : n) {
            acc.put(x.path("name").asText(), x.path("id").asText());
            pile(x.path("children"), acc);
        }
    }
}
