package com.ipt.ged.charge;

import com.ipt.ged.contratapi.ServiceContratApi;
import com.ipt.ged.contratapi.dto.DtoContratApi.RechercheContratRequest;
import com.ipt.ged.cycledevie.ArchivageDossiers;
import com.ipt.ged.cycledevie.ExportDossiers;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.identite.Role;
import com.ipt.ged.indexation.IndexationService;
import com.ipt.ged.indexation.dto.RechercheRequest;
import com.ipt.ged.recherche.CriteresMetadonnees;
import com.ipt.ged.recherche.PageResultats;
import com.ipt.ged.recherche.RequeteRecherche;
import com.ipt.ged.recherche.SearchIndexer;
import com.ipt.ged.support.Comptes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Essais de volumétrie (§6.6) : archivage d'un dossier de 1 000 documents,
 * export ZIP proche de 2 Go, recherche plein texte et multicritère sur
 * 10 000 documents au moins avec filtrage par droits. Chaîne OCR inactive
 * (profil de test) : le texte indexé est injecté directement, la recherche ne
 * dépend pas de la façon dont il a été extrait.
 *
 * <p>Base dédiée, conservée d'une exécution à l'autre (pas de
 * {@code drop-first}) : la recherche complète le fonds jusqu'à
 * {@code GED_CHARGE_DOCUMENTS} documents (10 000 par défaut) puis mesure.
 */
class EssaiVolumetrieChargeIT extends BaseCharge {

    private static final int DOCUMENTS = Integer.parseInt(System.getenv().getOrDefault("GED_CHARGE_DOCUMENTS", "10000"));
    private static final int ESPACES = 20;

    @Autowired private ArchivageDossiers archivage;
    @Autowired private ExportDossiers exports;
    @Autowired private SearchIndexer indexer;
    @Autowired private IndexationService indexation;
    @Autowired private ServiceContratApi contrat;

    @Test
    void archivageDossierMille() throws Exception {
        commeAdmin();
        UUID noeud = jeu.noeud("Archivage 1000 " + UUID.randomUUID().toString().substring(0, 6), null);
        UUID type = typePdf(noeud, 5);
        List<byte[]> modeles = new ArrayList<>();
        for (int i = 0; i < 20; i++) modeles.add(GenerateurCharge.scanLeger(500 + i));
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            deposer(type, "piece-" + i, modeles.get(i % modeles.size()));
        }
        long depotMs = (System.nanoTime() - t0) / 1_000_000;
        Mesures.noterPoste("archivage");
        Mesures.noter("archivage.depot_1000.s", Mesures.f(depotMs / 1000.0));
        Mesures.noter("archivage.depot_1000.documents_par_s", Mesures.f(1000 * 1000.0 / depotMs));

        try (Mesures.PicTas pic = new Mesures.PicTas(); Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
            long a0 = System.nanoTime();
            ArchivageDossiers.Job job = archivage.archiverDossier(noeud);
            long creationMs = (System.nanoTime() - a0) / 1_000_000;
            assertEquals(1000, job.total());
            while (archivage.traiterUnJob()) {
                // job suivant
            }
            long totalMs = (System.nanoTime() - a0) / 1_000_000;
            ArchivageDossiers.Job fin = archivage.job(job.id()).orElseThrow();
            Mesures.noter("archivage.1000.creation_job_ms", creationMs);
            Mesures.noter("archivage.1000.duree_min", Mesures.f(totalMs / 60_000.0));
            Mesures.noter("archivage.1000.ms_par_document", Mesures.f(totalMs / 1000.0));
            Mesures.noter("archivage.1000.etat", fin.etat());
            Mesures.noter("archivage.1000.archives", fin.archives());
            Mesures.noter("archivage.1000.anomalies_copie", fin.anomalies());
            Mesures.noter("archivage.1000.echecs", fin.echecs());
            Mesures.noter("archivage.1000.pic_tas_mo", pic.picMo());
            Mesures.noter("archivage.1000.performance", pm.resume());
        }
    }

    @Test
    void exportProcheDeDeuxGo() throws Exception {
        Authentication admin = commeAdmin();
        Mesures.noterPoste("export");
        UUID noeud = jeu.noeud("Export 2Go " + UUID.randomUUID().toString().substring(0, 6), null);
        UUID sous = jeu.noeud("Annexes", noeud);
        UUID type = typePdf(noeud, 20), typeSous = typePdf(sous, 20);
        int taille = 10 * 1024 * 1024;
        int nb = 195;
        long t0 = System.nanoTime();
        for (int i = 0; i < nb; i++) {
            deposer(i % 3 == 0 ? typeSous : type, "volumineux-" + i, GenerateurCharge.pdfVolumineux(i, taille));
        }
        long depotMs = (System.nanoTime() - t0) / 1_000_000;
        double mo = nb * (taille / 1048576.0);
        Mesures.noter("export.depot.mo", Mesures.f(mo));
        Mesures.noter("export.depot.mo_par_s", Mesures.f(mo * 1000 / depotMs));

        try (Mesures.PicTas pic = new Mesures.PicTas(); Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
            long s0 = System.nanoTime();
            ExportDossiers.Selection s = exports.selectionner(noeud, admin);
            long selectionMs = (System.nanoTime() - s0) / 1_000_000;
            assertEquals(nb, s.lignes().size());
            assertFalse(exports.enTraitementDeFond(s), "sous le seuil de 2 Go : export en flux");
            long[] octets = {0};
            OutputStream compteur = new OutputStream() {
                @Override public void write(int b) { octets[0]++; }
                @Override public void write(byte[] b, int off, int len) { octets[0] += len; }
            };
            // Vérification d'intégrité avant la réponse (ANO-E5-003) : délai
            // avant le premier octet de l'export synchrone.
            long v0 = System.nanoTime();
            exports.verifierIntegrite(s);
            long verificationMs = (System.nanoTime() - v0) / 1_000_000;
            Mesures.noter("export.verification_integrite_s", Mesures.f(verificationMs / 1000.0));
            long e0 = System.nanoTime();
            exports.ecrire(s, compteur);
            long ecritureMs = (System.nanoTime() - e0) / 1_000_000;
            Mesures.noter("export.selection_ms", selectionMs);
            Mesures.noter("export.zip.mo", Mesures.f(octets[0] / 1048576.0));
            Mesures.noter("export.zip.duree_s", Mesures.f(ecritureMs / 1000.0));
            Mesures.noter("export.zip.mo_par_s", Mesures.f(octets[0] / 1048576.0 * 1000 / ecritureMs));
            Mesures.noter("export.zip.pic_tas_mo", pic.picMo());
            Mesures.noter("export.zip.performance", pm.resume());
        }
    }

    /* ---------- recherche ---------- */

    private record Fonds(List<UUID> noeuds, List<UUID> types) {
    }

    /**
     * 20 espaces ; l'utilisateur standard n'est habilité que sur 5 (un quart
     * du fonds). Retrouvés par leur nom d'une exécution à l'autre.
     */
    private Fonds fonds() {
        List<UUID> noeuds = new ArrayList<>(), types = new ArrayList<>();
        for (int i = 0; i < ESPACES; i++) {
            String nom = String.format("Recherche-%02d", i);
            List<UUID> existant = jdbc.queryForList("SELECT id FROM noeud WHERE nom = ?", UUID.class, nom);
            if (existant.isEmpty()) {
                UUID n = jeu.noeud(nom, null);
                noeuds.add(n);
                types.add(typePdf(n, 5));
                if (i < 5) jeu.habiliter(Comptes.SANS_ROLE, Role.UTILISATEUR_STANDARD, n, null);
            } else {
                noeuds.add(existant.get(0));
                types.add(jdbc.queryForObject("SELECT type_document_id FROM document WHERE noeud_principal_id = ? "
                        + "LIMIT 1", UUID.class, existant.get(0)));
            }
        }
        return new Fonds(noeuds, types);
    }

    /** Complète le fonds jusqu'à {@link #DOCUMENTS} documents indexés, sur 6 fils de dépôt. */
    private void charger(Fonds f) throws Exception {
        Long deja = jdbc.queryForObject("SELECT count(*) FROM document_texte dt JOIN document d ON d.id = dt.document_id "
                + "WHERE d.name LIKE 'document-%'", Long.class);
        int debut = deja == null ? 0 : deja.intValue();
        if (debut >= DOCUMENTS) return;
        byte[] pdf = GenerateurCharge.pdfVolumineux(7, 2048);
        AtomicInteger suivant = new AtomicInteger(debut);
        AtomicInteger pages = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        long t0 = System.nanoTime();
        try {
            List<Future<?>> f2 = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                f2.add(pool.submit(() -> {
                    commeAdmin();
                    int i;
                    while ((i = suivant.getAndIncrement()) < DOCUMENTS) {
                        Random r = new Random(i);
                        int nbPages = GenerateurCharge.pagesDocument(r);
                        pages.addAndGet(nbPages);
                        DocumentResponse d = deposer(f.types().get(i % ESPACES), "document-" + i, pdf);
                        indexer.indexer(new SearchIndexer.TexteAIndexer(d.id(), d.versions().get(0).id(), "fra+ara",
                                GenerateurCharge.texteRealiste(r, nbPages), "OCR", nbPages));
                    }
                    return null;
                }));
            }
            for (Future<?> x : f2) x.get();
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int n = DOCUMENTS - debut;
        Mesures.noter("recherche.chargement." + debut + "_a_" + DOCUMENTS + ".min", Mesures.f(ms / 60_000.0));
        Mesures.noter("recherche.chargement." + debut + "_a_" + DOCUMENTS + ".documents_par_s", Mesures.f(n * 1000.0 / ms));
        Mesures.noter("recherche.chargement." + debut + "_a_" + DOCUMENTS + ".pages_texte", pages.get());
    }

    @Test
    void rechercheSurDixMille() throws Exception {
        commeAdmin();
        Fonds f = fonds();
        Mesures.noterPoste("recherche");
        charger(f);
        jdbc.execute("ANALYZE document_texte");
        jdbc.execute("ANALYZE document");
        String n = "recherche." + DOCUMENTS;
        Mesures.noter(n + ".documents", jdbc.queryForObject("SELECT count(*) FROM document", Long.class));
        Mesures.noter(n + ".document_texte.lignes", jdbc.queryForObject("SELECT count(*) FROM document_texte", Long.class));
        Mesures.noter(n + ".document_texte.texte_mo", jdbc.queryForObject(
                "SELECT sum(octet_length(texte)) / 1048576 FROM document_texte", Long.class));
        Mesures.noter(n + ".document_texte.table_toast_mo", jdbc.queryForObject(
                "SELECT pg_table_size('document_texte') / 1048576", Long.class));
        Mesures.noter(n + ".document_texte.index_gin_mo", jdbc.queryForObject(
                "SELECT pg_relation_size('idx_document_texte_tsv') / 1048576", Long.class));
        Mesures.noter(n + ".base_mo", jdbc.queryForObject("SELECT pg_database_size(current_database()) / 1048576",
                Long.class));

        // Requêtes de sélectivité croissante (loi de Zipf, voir GenerateurCharge.Lexique).
        String moyen = GenerateurCharge.Lexique.rang(300), peuFrequent = GenerateurCharge.Lexique.rang(3000),
                rare = GenerateurCharge.Lexique.rang(30000);
        Map<String, String> requetes = new java.util.LinkedHashMap<>();
        requetes.put("tres_frequent", "contrat");
        requetes.put("deux_frequents", "facture paiement");
        requetes.put("moyen", moyen);
        requetes.put("peu_frequent", peuFrequent);
        requetes.put("rare", rare);
        requetes.put("expression", "\"" + GenerateurCharge.Lexique.rang(50) + " " + GenerateurCharge.Lexique.rang(51) + "\"");
        requetes.put("exclusion", "contrat -" + peuFrequent);
        requetes.put("arabe_frequent", "عقد");
        requetes.put("absent", "zzqxwv");
        Mesures.noter(n + ".requetes", requetes);

        for (String qui : List.of(Comptes.ADMIN, Comptes.SANS_ROLE)) {
            Authentication a = comme(qui);
            String cle = n + ".plein_texte." + (qui.equals(Comptes.ADMIN) ? "admin" : "standard_1_4");
            for (RequeteRecherche.Tri tri : List.of(RequeteRecherche.Tri.PERTINENCE, RequeteRecherche.Tri.DATE_DEPOT)) {
                for (Map.Entry<String, String> q : requetes.entrySet()) {
                    List<Long> ms = new ArrayList<>();
                    String total = "";
                    for (int rep = 0; rep < 6; rep++) {
                        long q0 = System.nanoTime();
                        PageResultats p = indexer.rechercher(new RequeteRecherche(q.getValue(), 0, 20, tri, List.of()), a);
                        if (rep > 0) ms.add((System.nanoTime() - q0) / 1_000_000); // 1er passage : cache froid
                        // « + » : total plafonné, à lire « plus de » (R32).
                        total = p.total() + (p.totalPlafonne() ? "+" : "");
                    }
                    Mesures.noter(cle + "." + tri + "." + q.getKey(), "p50=" + Mesures.centile(ms, 50) + " ms, max="
                            + Mesures.centile(ms, 100) + " ms, resultats=" + total);
                }
            }
            // Plein texte combiné à des critères de métadonnées (type + période).
            List<Long> combi = new ArrayList<>();
            for (int rep = 0; rep < 6; rep++) {
                long q0 = System.nanoTime();
                indexer.rechercher(new RequeteRecherche("contrat", 0, 20, RequeteRecherche.Tri.PERTINENCE,
                        new CriteresMetadonnees(f.types().get(rep % 5), null, java.time.LocalDate.now().minusDays(1),
                                java.time.LocalDate.now()).fragments()), a);
                combi.add((System.nanoTime() - q0) / 1_000_000);
            }
            Mesures.noter(cle + ".contrat_type_periode.p50_ms", Mesures.centile(combi, 50));
            // Dernière page d'une requête très fréquente (pagination profonde).
            long q0 = System.nanoTime();
            indexer.rechercher(new RequeteRecherche("contrat", 400, 20, RequeteRecherche.Tri.PERTINENCE, List.of()), a);
            Mesures.noter(cle + ".contrat_page_400_ms", (System.nanoTime() - q0) / 1_000_000);

            // Multicritère historique (IndexationService.rechercher) : un type, puis tout le fonds.
            String mc = n + ".multicritere." + (qui.equals(Comptes.ADMIN) ? "admin" : "standard_1_4");
            try (Mesures.PicTas pic = new Mesures.PicTas()) {
                List<Long> multi = new ArrayList<>();
                for (int rep = 0; rep < 3; rep++) {
                    long m0 = System.nanoTime();
                    indexation.rechercher(new RechercheRequest(null, f.types().get(rep), List.of(), null, null, null));
                    multi.add((System.nanoTime() - m0) / 1_000_000);
                }
                Mesures.noter(mc + ".un_type.p50_ms", Mesures.centile(multi, 50));
                long m0 = System.nanoTime();
                int groupes = indexation.rechercher(new RechercheRequest(null, null, List.of(), null, null, null)).size();
                Mesures.noter(mc + ".tout_le_fonds_ms", (System.nanoTime() - m0) / 1_000_000);
                Mesures.noter(mc + ".tout_le_fonds_groupes", groupes);
                Mesures.noter(mc + ".pic_tas_mo", pic.picMo());
            } catch (Exception e) {
                Mesures.noter(mc + ".echec", e.getClass().getSimpleName() + " : " + racine(e));
            }
            // Multicritère du contrat (POST /recherches sans texte) : filtré, trié et paginé
            // par la base (R32) ; un type, puis tout le fonds, première page et page 100.
            String mcc = n + ".multicritere_contrat." + (qui.equals(Comptes.ADMIN) ? "admin" : "standard_1_4");
            try (Mesures.PicTas pic = new Mesures.PicTas()) {
                List<Long> multi = new ArrayList<>();
                long totalType = 0;
                for (int rep = 0; rep < 3; rep++) {
                    long m0 = System.nanoTime();
                    totalType = contrat.rechercher(new RechercheContratRequest(null, List.of(), f.types().get(rep), null,
                            null, null, null, null, null, null, 0, 50), a).total();
                    multi.add((System.nanoTime() - m0) / 1_000_000);
                }
                Mesures.noter(mcc + ".un_type.p50_ms", Mesures.centile(multi, 50) + " (total " + totalType + ")");
                for (int pageNo : List.of(0, 100)) {
                    long m0 = System.nanoTime();
                    long total = contrat.rechercher(new RechercheContratRequest(null, List.of(), null, null, null, null,
                            null, null, null, null, pageNo, 50), a).total();
                    Mesures.noter(mcc + ".tout_le_fonds_page_" + pageNo + "_ms", (System.nanoTime() - m0) / 1_000_000
                            + " (total " + total + ")");
                }
                Mesures.noter(mcc + ".pic_tas_mo", pic.picMo());
            } catch (Exception e) {
                Mesures.noter(mcc + ".echec", e.getClass().getSimpleName() + " : " + racine(e));
            }
            // Liste paginée des documents (filtrage par droits à la source).
            List<Long> liste = new ArrayList<>();
            for (int rep = 0; rep < 10; rep++) {
                long l0 = System.nanoTime();
                documents.list(rep, 50, "", null, null, null);
                liste.add((System.nanoTime() - l0) / 1_000_000);
            }
            Mesures.noter(n + ".documents.liste." + (qui.equals(Comptes.ADMIN) ? "admin" : "standard_1_4") + ".p50_ms",
                    Mesures.centile(liste, 50));
        }
        concurrence(n, new ArrayList<>(requetes.values()));
    }

    /** 8 utilisateurs simultanés (standard), requêtes mêlées : débit et centiles. */
    private void concurrence(String n, List<String> requetes) throws Exception {
        int fils = 8, parFil = 25;
        ExecutorService pool = Executors.newFixedThreadPool(fils);
        List<Long> ms = Collections.synchronizedList(new ArrayList<>());
        try {
            long t0 = System.nanoTime();
            List<Future<?>> f = new ArrayList<>();
            for (int k = 0; k < fils; k++) {
                int graine = k;
                f.add(pool.submit(() -> {
                    Authentication a = comme(Comptes.SANS_ROLE);
                    Random r = new Random(graine);
                    for (int i = 0; i < parFil; i++) {
                        long q0 = System.nanoTime();
                        indexer.rechercher(new RequeteRecherche(requetes.get(r.nextInt(requetes.size())), 0, 20,
                                RequeteRecherche.Tri.PERTINENCE, List.of()), a);
                        ms.add((System.nanoTime() - q0) / 1_000_000);
                    }
                    return null;
                }));
            }
            for (Future<?> x : f) x.get();
            long total = (System.nanoTime() - t0) / 1_000_000;
            Mesures.noter(n + ".concurrence_8.requetes_par_s", Mesures.f(fils * parFil * 1000.0 / total));
            Mesures.noter(n + ".concurrence_8.p50_ms", Mesures.centile(ms, 50));
            Mesures.noter(n + ".concurrence_8.p95_ms", Mesures.centile(ms, 95));
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }
    }

    private static String racine(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = String.valueOf(t.getMessage());
        return t.getClass().getSimpleName() + " : " + (m.length() > 200 ? m.substring(0, 200) : m);
    }
}
