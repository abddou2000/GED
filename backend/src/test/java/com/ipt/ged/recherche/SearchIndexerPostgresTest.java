package com.ipt.ged.recherche;

import com.ipt.ged.support.BasePostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.4 — recherche plein texte PostgreSQL réelle : tsvector french + arabic,
 * normalisation (accents, diacritiques et formes de l'alef), websearch_to_tsquery,
 * ts_rank_cd, ts_headline, pagination, filtrage par droits à la source,
 * réindexation incrémentale et complète.
 */
class SearchIndexerPostgresTest {

    private static final Authentication UTILISATEUR = new UsernamePasswordAuthenticationToken(
            "sara.bennani@marchica.ma", null, AuthorityUtils.NO_AUTHORITIES);

    private static BasePostgres base;
    private static JdbcTemplate jdbc;
    private SearchIndexerPostgres indexer;

    @BeforeAll
    static void ouvrir() throws Exception {
        base = BasePostgres.ouvrir();
        jdbc = base.jdbc();
    }

    @AfterAll
    static void fermer() throws Exception {
        base.close();
    }

    @BeforeEach
    void vider() {
        jdbc.update("DELETE FROM document_texte");
        indexer = new SearchIndexerPostgres(jdbc, new PredicatDroitsProvisoire());
    }

    private UUID indexer(String texte) {
        return indexer(UUID.randomUUID(), texte);
    }

    private UUID indexer(UUID documentId, String texte) {
        UUID version = UUID.randomUUID();
        base.document(documentId, version);
        indexer.indexer(new SearchIndexer.TexteAIndexer(documentId, version, "fra+ara", texte, "OCR", 1));
        return documentId;
    }

    private List<UUID> chercher(String q) {
        return indexer.rechercher(RequeteRecherche.simple(q, 0, 50), UTILISATEUR).resultats().stream()
                .map(PageResultats.Resultat::documentId).toList();
    }

    private String sql(String requete, String valeur) {
        return jdbc.queryForObject(requete, String.class, valeur);
    }

    @Test
    @DisplayName("Normalisation arabe : diacritiques, tatweel et formes de l'alef ramenés à une forme unique")
    void normalisationArabe() {
        assertEquals("احمد", sql("SELECT ged_normaliser_arabe(?)", "أَحْمَد"));
        assertEquals("اسلام", sql("SELECT ged_normaliser_arabe(?)", "إسلام"));
        assertEquals("امن", sql("SELECT ged_normaliser_arabe(?)", "آمن"));
        assertEquals("الكتاب", sql("SELECT ged_normaliser_arabe(?)", "الكتـــاب"));
        assertEquals("Résilié", sql("SELECT ged_normaliser_arabe(?)", "Résilié"), "le latin n'est pas touché");
        assertEquals("Resilie", sql("SELECT ged_unaccent(?)", "Résilié"));
        assertEquals("عقد الإيجار", sql("SELECT ged_unaccent(?)", "عقد الإيجار"), "unaccent ne touche pas l'arabe");
    }

    @Test
    @DisplayName("Vecteur : chaque écriture analysée par sa configuration, sans accents ni mots vides croisés")
    void vecteur() {
        String v = sql("SELECT ged_document_tsvector(?)::text", "Contrat résilié — عقد الإيجار");
        assertTrue(v.contains("'resil'"), v);
        assertTrue(v.contains("'contrat'"), v);
        assertFalse(v.contains("é"), v);
        assertTrue(v.contains("ايجار"), v);
        assertEquals(4, jdbc.queryForObject("SELECT length(ged_document_tsvector(?))", Integer.class,
                "Contrat résilié — عقد الإيجار"), "contrat, resil, عقد, ايجار : un lexème par mot, pas deux");
    }

    @Test
    @DisplayName("Français : insensible aux accents et aux flexions")
    void francais() {
        UUID a = indexer("Le contrat de location a été résilié par la société Marchica.");
        UUID b = indexer("Procès-verbal de réception des travaux de voirie.");
        assertEquals(List.of(a), chercher("resilie"));
        assertEquals(List.of(a), chercher("résiliés"));
        assertEquals(List.of(a), chercher("CONTRATS"));
        assertEquals(List.of(b), chercher("reception travaux"));
    }

    @Test
    @DisplayName("Arabe : forme de l'alef, diacritiques et article défini sans effet sur la recherche")
    void arabe() {
        UUID a = indexer("عقد الإيجار السنوي للشركة");
        indexer("محضر استلام الأشغال");
        assertEquals(List.of(a), chercher("الايجار"), "sans hamza");
        assertEquals(List.of(a), chercher("الإِيجَار"), "avec diacritiques");
        assertEquals(List.of(a), chercher("إيجار"), "sans article");
        assertEquals(List.of(a), chercher("شركة"));
    }

    @Test
    @DisplayName("Document bilingue trouvé par l'une ou l'autre langue ; requête mixte")
    void mixte() {
        UUID a = indexer("Convention de partenariat\nاتفاقية الشراكة");
        assertEquals(List.of(a), chercher("partenariat"));
        assertEquals(List.of(a), chercher("اتفاقية"));
        assertEquals(List.of(a), chercher("convention اتفاقية"));
    }

    @Test
    @DisplayName("Syntaxe websearch : expression exacte entre guillemets et exclusion")
    void syntaxe() {
        UUID a = indexer("Avenant au marché de travaux de la lagune.");
        UUID b = indexer("Marché de fournitures ; travaux annexes de la lagune.");
        assertEquals(List.of(a), chercher("\"marché de travaux\""));
        assertEquals(List.of(b), chercher("lagune -avenant"));
    }

    @Test
    @DisplayName("Tri par pertinence (ts_rank_cd), total et pagination")
    void pertinencePagination() {
        UUID fort = indexer("facture facture facture de la société, facture de mars");
        UUID faible = indexer("rapport annuel mentionnant une facture");
        for (int i = 0; i < 25; i++) indexer("note " + i + " sur la facture du lot " + i);
        PageResultats p0 = indexer.rechercher(RequeteRecherche.simple("facture", 0, 10), UTILISATEUR);
        assertEquals(27, p0.total());
        assertEquals(10, p0.resultats().size());
        assertEquals(fort, p0.resultats().get(0).documentId());
        assertTrue(p0.resultats().get(0).pertinence() > p0.resultats().get(9).pertinence());
        PageResultats p2 = indexer.rechercher(RequeteRecherche.simple("facture", 2, 10), UTILISATEUR);
        assertEquals(7, p2.resultats().size());
        PageResultats p9 = indexer.rechercher(RequeteRecherche.simple("facture", 9, 10), UTILISATEUR);
        assertTrue(p9.resultats().isEmpty());
        assertEquals(27, p9.total(), "total exact même au-delà de la dernière page");
        List<UUID> tous = new java.util.ArrayList<>();
        for (int p = 0; p < 3; p++) {
            indexer.rechercher(RequeteRecherche.simple("facture", p, 10), UTILISATEUR).resultats()
                    .forEach(r -> tous.add(r.documentId()));
        }
        assertEquals(27, tous.stream().distinct().count(), "aucun doublon entre pages");
        assertTrue(tous.contains(faible));
    }

    @Test
    @DisplayName("Extraits ts_headline : terme surligné sur le texte d'origine, accents conservés, aucun HTML")
    void extraits() {
        indexer("<script>alert(1)</script> Le contrat a été résilié le 3 mars par la direction.");
        PageResultats.Resultat r = indexer.rechercher(RequeteRecherche.simple("resilie", 0, 5), UTILISATEUR)
                .resultats().get(0);
        List<PageResultats.Segment> surlignes = r.extrait().stream().filter(PageResultats.Segment::surligne).toList();
        assertEquals(List.of("résilié"), surlignes.stream().map(PageResultats.Segment::texte).toList());
        String tout = r.extrait().stream().map(PageResultats.Segment::texte).collect(Collectors.joining());
        assertTrue(tout.contains("résilié le 3 mars"), tout);
        assertFalse(tout.contains("\uE000"));
    }

    @Test
    @DisplayName("Extraits : terme arabe surligné, normalisé comme la requête")
    void extraitArabe() {
        indexer("تم توقيع عقد الإيجار مع الشركة");
        PageResultats.Resultat r = indexer.rechercher(RequeteRecherche.simple("الايجار", 0, 5), UTILISATEUR)
                .resultats().get(0);
        assertTrue(r.extrait().stream().anyMatch(s -> s.surligne() && s.texte().equals("الايجار")), r.extrait().toString());
    }

    @Test
    @DisplayName("Droits à la source : le prédicat restreint résultats et total ; anonyme = rien")
    void droits() {
        UUID visible = indexer("dossier foncier de la parcelle 12");
        UUID cache = indexer("dossier foncier de la parcelle 13");
        jdbc.update("UPDATE document SET nom = 'public' WHERE id = ?", visible);
        jdbc.update("UPDATE document SET nom = 'confidentiel' WHERE id = ?", cache);
        PredicatDroits parEspace = (colonne, u) -> new FragmentSql(
                "EXISTS (SELECT 1 FROM document d WHERE d.id = " + colonne + " AND d.nom = :droits_nom)",
                Map.of("droits_nom", "public"));
        SearchIndexerPostgres filtre = new SearchIndexerPostgres(jdbc, parEspace);
        PageResultats p = filtre.rechercher(RequeteRecherche.simple("parcelle", 0, 10), UTILISATEUR);
        assertEquals(List.of(visible), p.resultats().stream().map(PageResultats.Resultat::documentId).toList());
        assertEquals(1, p.total(), "le total ne révèle pas les documents hors périmètre");
        assertEquals(0, indexer.rechercher(RequeteRecherche.simple("parcelle", 0, 10), null).total());
    }

    @Test
    @DisplayName("Critère supplémentaire combiné en ET ; collision de paramètres refusée")
    void filtres() {
        UUID a = indexer("bon de commande 44");
        indexer("bon de commande 45");
        FragmentSql filtre = new FragmentSql("dt.document_id = :f_doc", Map.of("f_doc", a));
        assertEquals(List.of(a), indexer.rechercher(new RequeteRecherche("commande", 0, 10,
                RequeteRecherche.Tri.PERTINENCE, List.of(filtre)), UTILISATEUR)
                .resultats().stream().map(PageResultats.Resultat::documentId).toList());
        FragmentSql collision = new FragmentSql("TRUE", Map.of("q", "x"));
        assertThrows(IllegalArgumentException.class, () -> indexer.rechercher(new RequeteRecherche("commande", 0, 10,
                RequeteRecherche.Tri.PERTINENCE, List.of(collision)), UTILISATEUR));
    }

    @Test
    @DisplayName("Réindexation incrémentale : une nouvelle version remplace le texte de la précédente")
    void incrementale() {
        UUID doc = UUID.randomUUID();
        UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID();
        base.document(doc, v1);
        jdbc.update("INSERT INTO version_document (id, document_id) VALUES (?, ?)", v2, doc);
        indexer.indexer(new SearchIndexer.TexteAIndexer(doc, v1, "fra", "ancienne clause pénale", "OCR", 1));
        indexer.indexer(new SearchIndexer.TexteAIndexer(doc, v2, "fra", "nouvelle clause de révision", "OCR", 1));
        assertTrue(chercher("pénale").isEmpty());
        assertEquals(List.of(doc), chercher("révision"));
        assertEquals(1, indexer.compter());
        assertEquals(List.of(v2), indexer.versionsIndexees(List.of(v1, v2)));
        // Rejouer la même version (reprise) ne duplique rien.
        indexer.indexer(new SearchIndexer.TexteAIndexer(doc, v2, "fra", "nouvelle clause de révision", "OCR", 1));
        assertEquals(1, indexer.compter());
        assertTrue(indexer.supprimer(doc));
        assertEquals(0, indexer.compter());
    }

    @Test
    @DisplayName("Réindexation complète en tâche de fond : vecteurs recalculés, progression jusqu'à 100 %")
    void complete() {
        for (int i = 0; i < 23; i++) indexer("pièce " + i + " du marché de dragage");
        // Simule un changement de configuration linguistique : vecteurs périmés.
        jdbc.update("UPDATE document_texte SET tsv = ''::tsvector");
        assertTrue(chercher("dragage").isEmpty());
        ReindexationComplete r = new ReindexationComplete(indexer, Runnable::run, 5, Duration.ZERO);
        assertTrue(r.demarrer());
        ReindexationComplete.Progression p = r.progression();
        assertEquals(ReindexationComplete.Etat.TERMINEE, p.etat());
        assertEquals(23, p.traites());
        assertEquals(100, p.pourcentage());
        assertEquals(23, chercher("dragage").size());
    }

    @Test
    @DisplayName("Aucun plafond de pages : 800 pages de texte indexées, terme de la dernière page trouvé")
    void grosDocument() {
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= 800; p++) {
            sb.append("Page ").append(p).append(" : attachement de travaux, métré du lot ").append(p)
                    .append(", quantités et prix unitaires conformes au bordereau. ".repeat(25)).append("\n\n");
        }
        sb.append("Mention finale : réserve levée le 30 juin.");
        UUID doc = indexer(sb.toString());
        assertTrue(sb.length() > 1_000_000);
        assertEquals(List.of(doc), chercher("réserve levée"));
    }

    @Test
    @DisplayName("Requête vide ou composée de mots vides : aucun résultat, aucune erreur")
    void requeteVide() {
        indexer("le la les");
        assertEquals(0, indexer.rechercher(RequeteRecherche.simple("  ", 0, 10), UTILISATEUR).total());
        assertEquals(0, indexer.rechercher(RequeteRecherche.simple("le la", 0, 10), UTILISATEUR).total());
    }
}
