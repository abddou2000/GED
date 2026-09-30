package com.ipt.ged.recherche;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.support.Pdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-F-011 (§4.4.3) : plage de date du document, niveau de confidentialité et
 * déposant filtrent les trois recherches ({@code POST /documents/recherche},
 * {@code POST /recherches} avec et sans texte, {@code GET /recherche/plein-texte}) ;
 * un paramètre inconnu est refusé en 400 {@code PARAMETRE_INCONNU} au lieu d'être
 * ignoré. Et {@code POST /indexation/recherche} est paginé (plafond 200).
 *
 * <p>Mêmes propriétés que {@code OcrApiTest} (chaîne OCR active, sans worker) :
 * le contrôleur plein texte n'existe qu'avec la chaîne, et le contexte est partagé.
 */
@SpringBootTest(properties = {"ged.ocr.chaine.actif=true", "ged.ocr.chaine.workers=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class CriteresImposesApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;
    @Autowired private SearchIndexer indexeur;

    private UUID type;
    private String mot;
    /** Septembre, public, déposé par l'administrateur. */
    private UUID a;
    /** Août, privé, déposé par l'administrateur. */
    private UUID b;
    /** Juillet, public, déposé pour le compte du second acteur. */
    private UUID c;
    private UUID second;

    @BeforeEach
    void preparer() throws Exception {
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        mot = "zarkolinet" + suffixe.replaceAll("[0-9]", "");
        type = jeu.type(jeu.noeud("Criteres " + suffixe, null), Confidentialite.PUBLIC);
        a = deposer("A-" + mot, "2026-09-10");
        b = deposer("B-" + mot, "2026-08-15");
        c = deposer("C-" + mot, "2026-07-01");
        second = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        jdbc.update("UPDATE document SET confidentialite = 'PRIVE' WHERE id = ?", b);
        jdbc.update("UPDATE document SET deposant_utilisateur_id = ? WHERE id = ?", second, c);
        for (UUID d : new UUID[]{a, b, c}) {
            UUID v = jdbc.queryForObject("SELECT id FROM version_document WHERE document_id = ?", UUID.class, d);
            indexeur.indexer(new SearchIndexer.TexteAIndexer(d, v, "fra", "Rapport " + mot, "OCR", 1));
        }
    }

    private UUID deposer(String nom, String dateDocument) throws Exception {
        JsonNode d = json(mvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf", Pdfs.pdf(nom)))
                .param("name", nom).param("typeDocumentId", type.toString()).param("dateDocument", dateDocument)));
        return UUID.fromString(d.get("id").asText());
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Critères en fragment JSON (sans accolades) ou en paramètres d'URL « nom=valeur&… ». */
    private static final String SEPTEMBRE = "\"dateDocumentDu\":\"2026-09-01\",\"dateDocumentAu\":\"2026-09-30\"";
    private static final String PRIVE = "\"confidentialite\":\"PRIVE\"";

    private String deposant() {
        return "\"deposantUtilisateurId\":\"" + second + "\"";
    }

    private List<String> metadonnees(String criteres) throws Exception {
        JsonNode r = json(mvc.perform(post("/api/v1/documents/recherche").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\",\"texte\":\"" + mot + "\"," + criteres + "}"))
                .andExpect(status().isOk()));
        List<String> ids = new ArrayList<>();
        r.get("content").forEach(l -> ids.add(l.get("id").asText()));
        return ids;
    }

    private List<String> contrat(String criteres, boolean texte) throws Exception {
        JsonNode r = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"typeDocumentId\":\"" + type + "\"," + (texte ? "\"texte\":\"" + mot + "\"," : "")
                        + criteres + "}"))
                .andExpect(status().isOk()));
        return r.get("resultats").findValuesAsText("documentId");
    }

    private List<String> pleinTexte(String... parametres) throws Exception {
        MockHttpServletRequestBuilder g = get("/api/v1/recherche/plein-texte").param("q", mot)
                .param("typeDocumentId", type.toString());
        for (int i = 0; i < parametres.length; i += 2) g.param(parametres[i], parametres[i + 1]);
        JsonNode r = json(mvc.perform(g).andExpect(status().isOk()));
        return r.get("resultats").findValuesAsText("documentId");
    }

    @Test
    @DisplayName("POST /documents/recherche : date du document (plage), confidentialité, déposant")
    void rechercheSurMetadonnees() throws Exception {
        assertThat(metadonnees("\"size\":50")).containsExactlyInAnyOrder(a.toString(), b.toString(), c.toString());
        assertThat(metadonnees(SEPTEMBRE)).containsExactly(a.toString());
        assertThat(metadonnees("\"dateDocumentAu\":\"2026-08-15\"")).containsExactly(b.toString(), c.toString());
        assertThat(metadonnees(PRIVE)).containsExactly(b.toString());
        assertThat(metadonnees(deposant())).containsExactly(c.toString());
        assertThat(metadonnees(PRIVE + "," + deposant())).isEmpty();
    }

    @Test
    @DisplayName("Déposant d'un document antérieur à l'identité du déposant : l'employé auteur du dépôt fait foi")
    void deposantDUnDocumentAncien() throws Exception {
        jdbc.update("UPDATE document SET deposant_utilisateur_id = NULL, created_by_employe_id = ? WHERE id = ?",
                jeu.employeId(Comptes.SECOND_ACTEUR), a);
        assertThat(metadonnees(deposant())).containsExactly(a.toString(), c.toString());
        assertThat(contrat(deposant(), false)).containsExactly(a.toString(), c.toString());
        assertThat(pleinTexte("deposantUtilisateurId", second.toString(), "tri", "DATE_DOCUMENT"))
                .containsExactly(a.toString(), c.toString());
    }

    @Test
    @DisplayName("POST /recherches : mêmes critères, sans texte (SQL) et avec texte (plein texte)")
    void rechercheDuContrat() throws Exception {
        for (boolean texte : new boolean[]{false, true}) {
            assertThat(contrat("\"taille\":50", texte)).hasSize(3);
            assertThat(contrat(SEPTEMBRE, texte)).containsExactly(a.toString());
            assertThat(contrat(PRIVE, texte)).containsExactly(b.toString());
            assertThat(contrat(deposant(), texte)).containsExactly(c.toString());
        }
    }

    @Test
    @DisplayName("GET /recherche/plein-texte : mêmes critères en paramètres d'URL")
    void recherchePleinTexte() throws Exception {
        assertThat(pleinTexte()).hasSize(3);
        assertThat(pleinTexte("dateDocumentDu", "2026-09-01", "dateDocumentAu", "2026-09-30"))
                .containsExactly(a.toString());
        assertThat(pleinTexte("confidentialite", "PRIVE")).containsExactly(b.toString());
        assertThat(pleinTexte("deposantUtilisateurId", second.toString())).containsExactly(c.toString());
    }

    @Test
    @DisplayName("Paramètre inconnu : 400 PARAMETRE_INCONNU (problem+json) sur les quatre recherches, au lieu d'être ignoré")
    void parametreInconnuRefuse() throws Exception {
        mvc.perform(post("/api/v1/documents/recherche").contentType(APPLICATION_JSON)
                        .content("{\"typeDocumentId\":\"" + type + "\",\"confidentialit\":\"PRIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PARAMETRE_INCONNU"))
                .andExpect(jsonPath("$.parametre").value("confidentialit"));
        mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                        .content("{\"typeDocumentId\":\"" + type + "\",\"deposant\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRE_INCONNU"))
                .andExpect(jsonPath("$.parametre").value("deposant"));
        // Champ inconnu dans un critère imbriqué : chemin complet.
        mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                        .content("{\"criteres\":[{\"indexFieldId\":\"" + UUID.randomUUID() + "\",\"valeurr\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRE_INCONNU"))
                .andExpect(jsonPath("$.parametre").value("criteres[0].valeurr"));
        mvc.perform(get("/api/v1/recherche/plein-texte").param("q", mot).param("dateDu", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PARAMETRE_INCONNU"))
                .andExpect(jsonPath("$.parametre").value("dateDu"));
        mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON).content("{\"size\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRE_INCONNU"));
        // Valeur hors liste et bornes inversées : 400 également.
        mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON).content("{\"confidentialite\":\"SECRET\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/recherche/plein-texte").param("q", mot)
                        .param("dateDocumentDu", "2026-10-01").param("dateDocumentAu", "2026-09-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /indexation/recherche : paginé (taille, plafond 200), total des documents et des groupes")
    void rechercheParIndexPaginee() throws Exception {
        String parType = "\"typeDocumentId\":\"" + type + "\"";
        JsonNode p0 = json(mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{" + parType + ",\"taille\":2}")).andExpect(status().isOk()));
        assertThat(p0.get("total").asLong()).isEqualTo(3);
        assertThat(p0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p0.get("size").asInt()).isEqualTo(2);
        assertThat(p0.get("content").get(0).get("documents")).hasSize(2);
        assertThat(p0.get("content").get(0).get("total").asInt()).isEqualTo(3);
        JsonNode p1 = json(mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{" + parType + ",\"taille\":2,\"page\":1}")).andExpect(status().isOk()));
        assertThat(p1.get("content").get(0).get("documents")).hasSize(1);
        JsonNode plafond = json(mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{" + parType + ",\"taille\":100000}")).andExpect(status().isOk()));
        assertThat(plafond.get("size").asInt()).isEqualTo(200);
        JsonNode defaut = json(mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content("{" + parType + "}")).andExpect(status().isOk()));
        assertThat(defaut.get("size").asInt()).isEqualTo(50);

        // Groupage : groupes triés, effectif de chaque groupe sur tout l'ensemble.
        UUID index = jeu.index("GRP", com.ipt.ged.index.IndexFieldType.TEXTE, false, null).getId();
        jdbc.update("INSERT INTO document_index_valeur (id, document_id, index_def_id, valeur) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), a, index, "Fournisseur B");
        jdbc.update("INSERT INTO document_index_valeur (id, document_id, index_def_id, valeur) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), b, index, "Fournisseur B");
        String groupe = "{" + parType + ",\"grouperPar\":\"" + index + "\",\"taille\":1";
        JsonNode g0 = json(mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content(groupe + "}")).andExpect(status().isOk()));
        assertThat(g0.get("content").get(0).get("libelle").asText()).isEqualTo("(non renseigné)");
        assertThat(g0.get("content").get(0).get("documents").get(0).get("id").asText()).isEqualTo(c.toString());
        JsonNode g1 = json(mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                .content(groupe + ",\"page\":1}")).andExpect(status().isOk()));
        assertThat(g1.get("content").get(0).get("libelle").asText()).isEqualTo("Fournisseur B");
        assertThat(g1.get("content").get(0).get("total").asInt()).isEqualTo(2);
        assertThat(g1.get("content").get(0).get("documents")).hasSize(1);
    }
}
