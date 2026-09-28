package com.ipt.ged.charge;

import com.ipt.ged.ocr.moteur.ExtracteurDocumentOcr;
import com.ipt.ged.ocr.moteur.TexteDocument;
import com.ipt.ged.fichier.controle.FormatsReconnus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Essais OCR (§6.6, §4.3.4, revue client D6) : débit du moteur Tesseract en
 * pages par minute et par cœur sur des scans denses français et arabes, puis
 * délai dépôt → disponibilité en recherche sous file chargée, un document de
 * 400 pages compris (cas des attachements).
 */
@TestPropertySource(properties = {
        "ged.ocr.chaine.actif=true",
        "ged.ocr.chaine.workers=${GED_CHARGE_WORKERS:4}",
        "ged.ocr.chaine.scrutation=1s"
})
class EssaiOcrChargeIT extends BaseCharge {

    @Autowired private ExtracteurDocumentOcr extracteur;
    @org.springframework.beans.factory.annotation.Value("${ged.ocr.tessdata}") private String tessdata;
    @org.springframework.beans.factory.annotation.Value("${ged.ocr.chaine.workers}") private int workers;

    private record Mesure(int pages, long ms) {
        double pagesParMinute() {
            return pages * 60_000.0 / ms;
        }
    }

    private Mesure ocr(byte[] pdf, String langue) throws Exception {
        long t0 = System.nanoTime();
        TexteDocument t = extracteur.extraire(new ByteArrayInputStream(pdf), FormatsReconnus.PDF, langue,
                ExtracteurDocumentOcr.SuiviPages.AUCUN);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(t.texte().length() > 1000, "texte reconnu");
        return new Mesure(t.nbPages(), ms);
    }

    @Test
    void debitMoteur() throws Exception {
        Mesures.noterPoste("ocr.debit");
        byte[] fr = GenerateurCharge.scan(1, GenerateurCharge.Langue.FR, 20);
        byte[] ar = GenerateurCharge.scan(2, GenerateurCharge.Langue.AR, 20);
        Mesures.noter("ocr.scan20.taille_ko.fr", fr.length / 1024);
        Mesures.noter("ocr.scan20.taille_ko.ar", ar.length / 1024);
        ocr(GenerateurCharge.scan(3, GenerateurCharge.Langue.FR, 1), "fra"); // chauffe (JIT, modèles)

        // Un seul fil : pages/minute/cœur (OMP_THREAD_LIMIT=1 fixe Tesseract à un cœur).
        for (var cas : List.of(Map.entry("fr.fra", Map.entry(fr, "fra")), Map.entry("fr.fra+ara", Map.entry(fr, "fra+ara")),
                Map.entry("ar.ara", Map.entry(ar, "ara")), Map.entry("ar.fra+ara", Map.entry(ar, "fra+ara")))) {
            Mesure m = ocr(cas.getValue().getKey(), cas.getValue().getValue());
            Mesures.noter("ocr.un_fil." + cas.getKey() + ".s_par_page", Mesures.f(m.ms() / 1000.0 / m.pages()));
            Mesures.noter("ocr.un_fil." + cas.getKey() + ".pages_par_minute", Mesures.f(m.pagesParMinute()));
        }

        // N fils en parallèle, langues du dossier (fra+ara) : débit agrégé.
        for (int fils : List.of(2, 4, 6)) {
            ExecutorService pool = Executors.newFixedThreadPool(fils);
            try (Mesures.PicTas pic = new Mesures.PicTas()) {
                long t0 = System.nanoTime();
                List<Future<Mesure>> f = new ArrayList<>();
                for (int i = 0; i < fils; i++) {
                    byte[] doc = i % 2 == 0 ? fr : ar;
                    f.add(pool.submit(() -> ocr(doc, "fra+ara")));
                }
                int pages = 0;
                for (Future<Mesure> x : f) pages += x.get().pages();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                double ppm = pages * 60_000.0 / ms;
                Mesures.noter("ocr.parallele." + fils + ".pages_par_minute", Mesures.f(ppm));
                Mesures.noter("ocr.parallele." + fils + ".pages_par_minute_par_coeur", Mesures.f(ppm / fils));
                Mesures.noter("ocr.parallele." + fils + ".pic_tas_mo", pic.picMo());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * File chargée : 12 scans de 20 pages (FR et AR) et un attachement de 400
     * pages déposés d'un coup ; les workers du pool (4 par défaut) les
     * traitent ; délai dépôt → document interrogeable de chacun.
     */
    @Test
    void delaiSousFileChargee() throws Exception {
        commeAdmin();
        UUID noeud = jeu.noeud("Charge OCR " + UUID.randomUUID().toString().substring(0, 6), null);
        UUID type = typePdf(noeud, 200);
        Mesures.noter("ocr.file.configuration", workers + " workers, modèles " + tessdata);
        Mesures.noterPoste("ocr.file");
        Map<UUID, Integer> pagesParDocument = new LinkedHashMap<>();
        // Scans produits AVANT le chronomètre : seul le dépôt compte.
        byte[] gros = CorpusOcr.document(CorpusOcr.Langue.MIXTE, CorpusOcr.Qualite.NB, 400, 400);
        Mesures.noter("ocr.attachement400.taille_mo", Mesures.f(gros.length / 1048576.0));
        List<byte[]> scans = new ArrayList<>();
        CorpusOcr.Langue[] langues = CorpusOcr.Langue.values();
        for (int i = 0; i < 12; i++) {
            scans.add(CorpusOcr.document(langues[i % 3], i % 2 == 0 ? CorpusOcr.Qualite.NB : CorpusOcr.Qualite.PROPRE,
                    20, 100 + i));
        }
        Instant debut = Instant.now();
        try (Mesures.PicTas pic = new Mesures.PicTas(); Mesures.PerfMoyenne pm = new Mesures.PerfMoyenne()) {
            for (int i = 0; i < 13; i++) {
                if (i == 6) {
                    pagesParDocument.put(deposer(type, "attachement-400p", gros).id(), 400);
                } else {
                    pagesParDocument.put(deposer(type, "scan-" + i, scans.get(i < 6 ? i : i - 1)).id(), 20);
                }
            }
            Mesures.noter("ocr.file.depot_s", Duration.between(debut, Instant.now()).toSeconds());
            // Attente : tous les jobs terminés (ou en échec), au plus GED_CHARGE_ATTENTE_MIN
            // minutes (240 par défaut : un attachement de 400 pages, traité par un seul
            // worker, a dépassé 90 min sur le poste chargé).
            Instant limite = Instant.now().plus(Duration.ofMinutes(
                    Long.parseLong(System.getenv().getOrDefault("GED_CHARGE_ATTENTE_MIN", "240"))));
            while (Instant.now().isBefore(limite)) {
                Integer restants = jdbc.queryForObject("SELECT count(*) FROM ocr_job WHERE document_id = ANY(?) "
                        + "AND statut IN ('EN_ATTENTE_OCR', 'EN_COURS_OCR')", Integer.class,
                        (Object) pagesParDocument.keySet().toArray(new UUID[0]));
                if (restants != null && restants == 0) break;
                Thread.sleep(5000);
            }
            Mesures.noter("ocr.file.pic_tas_mo", pic.picMo());
            Mesures.noter("ocr.file.performance", pm.resume());
        }
        long totalMs = Duration.between(debut, Instant.now()).toMillis();
        int pages = pagesParDocument.values().stream().mapToInt(Integer::intValue).sum();
        Mesures.noter("ocr.file.pages", pages);
        Mesures.noter("ocr.file.duree_totale_min", Mesures.f(totalMs / 60_000.0));
        Mesures.noter("ocr.file.pages_par_minute", Mesures.f(pages * 60_000.0 / totalMs));
        List<Long> delais20 = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : pagesParDocument.entrySet()) {
            List<Map<String, Object>> l = jdbc.queryForList("SELECT j.statut, EXTRACT(EPOCH FROM (t.indexe_le - "
                    + "j.depose_le)) * 1000 AS delai FROM ocr_job j LEFT JOIN document_texte t ON t.version_id = "
                    + "j.version_id WHERE j.document_id = ?", e.getKey());
            Map<String, Object> ligne = l.get(0);
            Object d = ligne.get("delai");
            long delai = d == null ? -1 : ((Number) d).longValue();
            if (e.getValue() == 400) {
                Mesures.noter("ocr.file.attachement400.statut", ligne.get("statut"));
                Mesures.noter("ocr.file.attachement400.delai_min", Mesures.f(delai / 60_000.0));
            } else if (delai >= 0) {
                delais20.add(delai);
            }
        }
        Mesures.noter("ocr.file.scan20.delai_p50_min", Mesures.f(Mesures.centile(delais20, 50) / 60_000.0));
        Mesures.noter("ocr.file.scan20.delai_p95_min", Mesures.f(Mesures.centile(delais20, 95) / 60_000.0));
        Mesures.noter("ocr.file.scan20.delai_max_min", Mesures.f(Mesures.centile(delais20, 100) / 60_000.0));
        Mesures.noter("ocr.file.scan20.termines", delais20.size());
        Mesures.noter("ocr.file.echecs", jdbc.queryForObject("SELECT count(*) FROM ocr_job WHERE document_id = ANY(?) "
                + "AND statut = 'OCR_ECHEC'", Integer.class, (Object) pagesParDocument.keySet().toArray(new UUID[0])));
    }
}
