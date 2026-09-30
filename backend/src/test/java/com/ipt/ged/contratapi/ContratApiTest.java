package com.ipt.ged.contratapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.cleapi.FiltreCleApi;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.idempotence.CleIdempotenceParDefautDesTests;
import com.ipt.ged.idempotence.FiltreIdempotence;
import com.ipt.ged.recherche.SearchIndexer;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.support.Pdfs;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import com.ipt.ged.workflow.circuit.ReglesApplicables;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat d'API du DAT §5.3.1, chemins exacts : création de dossier,
 * téléchargement par version, recherche multicritère et plein texte paginée,
 * consultation des droits (appelant, tiers, clé d'API).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class ContratApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;
    @Autowired private SearchIndexer indexeur;
    @Autowired private WorkflowRepository workflows;
    @Autowired private EmployeRepository employes;
    @Autowired private WorkSpaceRepository noeuds;
    @Autowired private TypeDocumentRepository types;
    @Autowired private ReglesApplicables regles;
    @Autowired private PlatformTransactionManager transactions;

    private UUID espace;
    private UUID type;
    private String suffixe;

    @BeforeEach
    void preparer() {
        suffixe = UUID.randomUUID().toString().substring(0, 8);
        Employe e = employes.findById(jeu.employeId(Comptes.ADMIN)).orElseThrow();
        WorkflowGed wf = workflows.save(new WorkflowGed("WF contrat " + suffixe));
        WorkSpace w = new WorkSpace("Contrat " + suffixe, "WS-CTR-" + suffixe);
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(e);
        w.setWorkflow(wf);
        espace = noeuds.saveAndFlush(w).getId();
        TypeDocument t = new TypeDocument("TD-CTR-" + suffixe, "Marché " + suffixe);
        t.setDescription("desc");
        t.setWorkspace(w);
        t.setTypeAutorise("pdf");
        t.setTailleMaxMo(5);
        type = types.saveAndFlush(t).getId();
        // Un acteur hors périmètre : rupture d'héritage sur l'espace (son rôle global ne s'y applique plus).
        jeu.rupture(Comptes.TROISIEME_ACTEUR, espace);
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Dépôt par le chemin du contrat ; renvoie [document, version]. */
    private UUID[] deposer(String nom, byte[] contenu) throws Exception {
        JsonNode d = json(mvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf", contenu))
                .param("name", nom).param("typeDocumentId", type.toString())));
        UUID doc = UUID.fromString(d.get("id").asText());
        UUID version = jdbc.queryForObject("SELECT id FROM version_document WHERE document_id = ?", UUID.class, doc);
        return new UUID[]{doc, version};
    }

    @Test
    @DisplayName("POST /noeuds/{id}/dossiers : 201 et Location, circuit hérité, Idempotency-Key exigée et rejouée")
    void creationDeDossier() throws Exception {
        String cle = UUID.randomUUID().toString();
        MvcResult r = mvc.perform(post("/api/v1/noeuds/" + espace + "/dossiers").contentType(APPLICATION_JSON)
                        .header(FiltreIdempotence.ENTETE, cle).content("{\"nom\":\"Offres 2026\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/workspaces/")))
                .andExpect(jsonPath("$.parent.id").value(espace.toString()))
                .andReturn();
        UUID dossier = UUID.fromString(om.readTree(r.getResponse().getContentAsString()).get("id").asText());
        // Circuit hérité (E8, §12.8) : le dossier ne porte aucune règle propre, et la
        // règle qui s'applique à un dépôt dans le dossier est celle de l'espace.
        assertThat(jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class, dossier))
                .isNull();
        UUID regleEspace = jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class, espace);
        new TransactionTemplate(transactions).executeWithoutResult(t -> {
            // Une règle n'est applicable qu'avec au moins un validateur.
            WorkflowGed wf = workflows.findById(regleEspace).orElseThrow();
            wf.addStep(new WorkflowStep(employes.findById(jeu.employeId(Comptes.ADMIN)).orElseThrow(), "Validation", 1));
            workflows.saveAndFlush(wf);
            ReglesApplicables.RegleApplicable applicable = regles.pour(null, noeuds.findById(dossier).orElseThrow())
                    .orElseThrow();
            assertThat(applicable.origine()).isEqualTo(ReglesApplicables.Origine.NOEUD);
            assertThat(applicable.origineId()).isEqualTo(espace);
            assertThat(applicable.regle().getId()).isEqualTo(regleEspace);
            t.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT code FROM noeud WHERE id = ?", String.class, dossier)).startsWith("DOS-");

        mvc.perform(post("/api/v1/noeuds/" + espace + "/dossiers").contentType(APPLICATION_JSON)
                        .header(FiltreIdempotence.ENTETE, cle).content("{\"nom\":\"Offres 2026\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(FiltreIdempotence.ENTETE_REJEU, "true"))
                .andExpect(jsonPath("$.id").value(dossier.toString()));
        mvc.perform(post("/api/v1/noeuds/" + espace + "/dossiers").contentType(APPLICATION_JSON)
                        .header(FiltreIdempotence.ENTETE, CleIdempotenceParDefautDesTests.SANS_CLE)
                        .content("{\"nom\":\"Sans clé\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/noeuds/" + espace + "/dossiers").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erreurs.nom").exists());
        // Hors périmètre : 404, comme un nœud inexistant (P5).
        mvc.perform(post("/api/v1/noeuds/" + espace + "/dossiers").with(user(jeu.identite(Comptes.TROISIEME_ACTEUR)))
                        .contentType(APPLICATION_JSON).content("{\"nom\":\"Intrus\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/noeuds/" + UUID.randomUUID() + "/dossiers").contentType(APPLICATION_JSON)
                        .content("{\"nom\":\"Nulle part\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /documents/{id}/contenu : version courante ou version donnée, audité ; hors périmètre 404")
    void telechargement() throws Exception {
        byte[] pdf = Pdfs.pdf("contenu-" + suffixe);
        UUID[] d = deposer("Contrat-" + suffixe, pdf);
        long avant = jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'DOCUMENT_TELECHARGE'"
                + " AND objet_id = ?", Long.class, d[0]);
        byte[] courant = mvc.perform(get("/api/v1/documents/" + d[0] + "/contenu"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(courant).isEqualTo(pdf);
        byte[] parVersion = mvc.perform(get("/api/v1/documents/" + d[0] + "/contenu").param("version", d[1].toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(parVersion).isEqualTo(pdf);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'DOCUMENT_TELECHARGE'"
                + " AND objet_id = ?", Long.class, d[0])).isEqualTo(avant + 2);

        UUID[] autre = deposer("Autre-" + suffixe, Pdfs.pdf("autre-" + suffixe));
        mvc.perform(get("/api/v1/documents/" + d[0] + "/contenu").param("version", autre[1].toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + d[0] + "/contenu").with(user(jeu.identite(Comptes.TROISIEME_ACTEUR))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /recherches : multicritère paginé, plein texte, ET des deux, total limité au périmètre")
    void recherche() throws Exception {
        UUID[] a = deposer("Alpha-" + suffixe, Pdfs.pdf("a-" + suffixe));
        UUID[] b = deposer("Beta-" + suffixe, Pdfs.pdf("b-" + suffixe));
        String parEspace = "{\"noeudId\":\"" + espace + "\",\"taille\":1,\"tri\":\"NOM\"}";
        JsonNode p0 = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON).content(parEspace))
                .andExpect(status().isOk()));
        assertThat(p0.get("total").asLong()).isEqualTo(2);
        assertThat(p0.get("resultats")).hasSize(1);
        assertThat(p0.get("resultats").get(0).get("documentId").asText()).isEqualTo(a[0].toString());
        JsonNode p1 = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parEspace.replace("\"taille\":1", "\"taille\":1,\"page\":1"))));
        assertThat(p1.get("resultats").get(0).get("documentId").asText()).isEqualTo(b[0].toString());

        // Filtrage à la source : un utilisateur sans droit ne voit rien, total compris.
        JsonNode rien = json(mvc.perform(post("/api/v1/recherches").with(user(jeu.identite(Comptes.TROISIEME_ACTEUR)))
                .contentType(APPLICATION_JSON).content(parEspace)));
        assertThat(rien.get("total").asLong()).isZero();

        // Plein texte (texte indexé comme par l'OCR), combiné au filtre d'espace.
        String mot = "portuaire" + suffixe.replaceAll("[0-9]", "");
        indexeur.indexer(new SearchIndexer.TexteAIndexer(a[0], a[1], "fra", "Marché de travaux " + mot, "OCR", 1));
        JsonNode texte = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"texte\":\"" + mot + "\",\"noeudId\":\"" + espace + "\"}")).andExpect(status().isOk()));
        assertThat(texte.get("total").asLong()).isEqualTo(1);
        assertThat(texte.get("resultats").get(0).get("documentId").asText()).isEqualTo(a[0].toString());
        // ET avec un critère d'index qu'aucun document ne satisfait : aucun résultat.
        JsonNode et = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"texte\":\"" + mot + "\",\"criteres\":[{\"indexFieldId\":\"" + UUID.randomUUID()
                        + "\",\"valeur\":\"x\"}]}")));
        assertThat(et.get("total").asLong()).isZero();

        // Canal du dépôt (T-040) : critère et colonne de résultat, avec ou sans plein texte.
        assertThat(p0.get("resultats").get(0).get("canalDepot").asText()).isEqualTo("INTERFACE");
        assertThat(texte.get("resultats").get(0).get("canalDepot").asText()).isEqualTo("INTERFACE");
        String parCanal = parEspace.replace("\"taille\":1,", "");
        assertThat(json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parCanal.replace("{", "{\"canal\":\"interface\",")))).get("total").asLong()).isEqualTo(2);
        assertThat(json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parCanal.replace("{", "{\"canal\":\"API\",")))).get("total").asLong()).isZero();
        assertThat(json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"texte\":\"" + mot + "\",\"canal\":\"BUREAU_ORDRE\"}"))).get("total").asLong()).isZero();
        mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON).content("{\"canal\":\"COURRIER\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON).content("{\"archives\":\"TOUT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ANO-E7-004 : date du document, clé de tri par défaut de GET /documents et de POST /recherches")
    void triParDateDuDocument() throws Exception {
        // Déposés dans l'ordre a, b, c ; dates du document 01/02, 01/05, 01/03 : attendu b, c, a.
        UUID a = deposerDate("TriA-" + suffixe, "2026-02-01");
        UUID b = deposerDate("TriB-" + suffixe, "2026-05-01");
        UUID c = deposerDate("TriC-" + suffixe, "2026-03-01");
        java.util.List<String> attendu = java.util.List.of(b.toString(), c.toString(), a.toString());

        JsonNode liste = json(mvc.perform(get("/api/v1/documents").param("workspaceId", espace.toString()))
                .andExpect(status().isOk()));
        assertThat(ids(liste.get("content"))).containsExactlyElementsOf(attendu);
        JsonNode croissant = json(mvc.perform(get("/api/v1/documents").param("workspaceId", espace.toString())
                .param("sortBy", "dateDocument").param("sortDir", "asc")).andExpect(status().isOk()));
        assertThat(ids(croissant.get("content"))).containsExactly(a.toString(), c.toString(), b.toString());

        String parEspace = "{\"noeudId\":\"" + espace + "\"";
        JsonNode defaut = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parEspace + "}")).andExpect(status().isOk()));
        assertThat(defaut.get("resultats").findValuesAsText("documentId")).containsExactlyElementsOf(attendu);
        assertThat(defaut.get("resultats").get(0).get("dateDocument").asText()).isEqualTo("2026-05-01");
        JsonNode explicite = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parEspace + ",\"tri\":\"DATE_DOCUMENT\"}")).andExpect(status().isOk()));
        assertThat(explicite.get("resultats").findValuesAsText("documentId")).containsExactlyElementsOf(attendu);

        // Plein texte trié par date du document (le défaut y reste la pertinence).
        String mot = "cadastral" + suffixe.replaceAll("[0-9]", "");
        for (UUID d : new UUID[]{a, b, c}) {
            UUID v = jdbc.queryForObject("SELECT id FROM version_document WHERE document_id = ?", UUID.class, d);
            indexeur.indexer(new SearchIndexer.TexteAIndexer(d, v, "fra", "Plan " + mot, "OCR", 1));
        }
        JsonNode texte = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parEspace + ",\"texte\":\"" + mot + "\",\"tri\":\"DATE_DOCUMENT\"}"))
                .andExpect(status().isOk()));
        assertThat(texte.get("resultats").findValuesAsText("documentId")).containsExactlyElementsOf(attendu);
    }

    @Test
    @DisplayName("R32 : critères d'index évalués en SQL, même règle par nature (texte, liste, date, nombre, booléen)")
    void criteresIndexEnSql() throws Exception {
        UUID a = deposerDate("IdxA-" + suffixe, "2026-01-01");
        UUID b = deposerDate("IdxB-" + suffixe, "2026-01-02");
        UUID c = deposerDate("IdxC-" + suffixe, "2026-01-03");
        UUID txt = index("TEXTE"), lst = index("LISTE"), dt = index("DATE"), nb = index("NOMBRE"), bo = index("BOOLEEN");
        valeur(a, txt, "Marché de Travaux");
        valeur(a, lst, "Lot 1");
        valeur(a, dt, "2026-03-15");
        valeur(a, nb, "1 500");          // illisible : écarté de tout critère numérique
        valeur(a, bo, "oui");
        valeur(b, txt, "fournitures");
        valeur(b, lst, "lot 2");
        valeur(b, dt, "2025-12-01");
        valeur(b, nb, "2500,50");
        valeur(b, bo, "Non");
        valeur(c, txt, "   ");           // blanche : ne satisfait aucun critère

        assertThat(parCriteres(critere(txt, "travaux", null, null))).containsExactly(a.toString());
        assertThat(parCriteres(critere(lst, "LOT 2", null, null))).containsExactly(b.toString());
        assertThat(parCriteres(critere(dt, null, "2026-01-01", null))).containsExactly(a.toString());
        assertThat(parCriteres(critere(dt, null, null, "2026-01-01"))).containsExactly(b.toString());
        assertThat(parCriteres(critere(nb, null, "2000", "2500.5"))).containsExactly(b.toString());
        assertThat(parCriteres(critere(nb, "x", null, null))).containsExactly(b.toString());
        assertThat(parCriteres(critere(bo, "vrai", null, null))).containsExactly(a.toString());
        assertThat(parCriteres(critere(bo, "N", null, null))).containsExactly(b.toString());
        assertThat(parCriteres(critere(txt, "a", null, null) + "," + critere(lst, "lot 1", null, null)))
                .containsExactly(a.toString());
        // Filtre non renseigné : ignoré (tous les documents de l'espace, date du document décroissante).
        assertThat(parCriteres(critere(txt, "", null, null)))
                .containsExactly(c.toString(), b.toString(), a.toString());
        assertThat(parCriteres(critere(UUID.randomUUID(), "x", null, null))).isEmpty();

        // Recherche historique (POST /indexation/recherche) : même règle, désormais en SQL.
        String historique = mvc.perform(post("/api/v1/indexation/recherche").contentType(APPLICATION_JSON)
                        .content("{\"workspaceId\":\"" + espace + "\",\"criteres\":[" + critere(txt, "travaux", null, null)
                                + "]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(historique).contains(a.toString()).doesNotContain(b.toString()).doesNotContain(c.toString());
    }

    private UUID index(String nature) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO index_def (id, code, nom_index, type_champs, valeurs) VALUES (?, ?, ?, ?, ?)", id,
                "IDX-" + nature + "-" + suffixe, "Index " + nature, nature, "LISTE".equals(nature) ? "Lot 1,Lot 2" : null);
        return id;
    }

    private void valeur(UUID document, UUID index, String valeur) {
        jdbc.update("INSERT INTO document_index_valeur (id, document_id, index_def_id, valeur) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), document, index, valeur);
    }

    private static String critere(UUID index, String valeur, String de, String a) {
        return "{\"indexFieldId\":\"" + index + "\"" + (valeur == null ? "" : ",\"valeur\":\"" + valeur + "\"")
                + (de == null ? "" : ",\"de\":\"" + de + "\"") + (a == null ? "" : ",\"a\":\"" + a + "\"") + "}";
    }

    private java.util.List<String> parCriteres(String criteres) throws Exception {
        JsonNode r = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + espace + "\",\"criteres\":[" + criteres + "]}")).andExpect(status().isOk()));
        return r.get("resultats").findValuesAsText("documentId");
    }

    /** Identifiants des lignes d'une page, dans l'ordre (sans les identifiants imbriqués). */
    private static java.util.List<String> ids(JsonNode lignes) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        lignes.forEach(l -> ids.add(l.get("id").asText()));
        return ids;
    }

    private UUID deposerDate(String nom, String dateDocument) throws Exception {
        JsonNode d = json(mvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf", Pdfs.pdf(nom)))
                .param("name", nom).param("typeDocumentId", type.toString()).param("dateDocument", dateDocument)));
        return UUID.fromString(d.get("id").asText());
    }

    @Test
    @DisplayName("POST /recherches : filtre « échéance dépassée » (T-112), avec et sans plein texte, dès le jour même")
    void rechercheEcheanceDepassee() throws Exception {
        UUID[] echu = deposer("Echu-" + suffixe, Pdfs.pdf("echu-" + suffixe));
        UUID[] jourMeme = deposer("JourMeme-" + suffixe, Pdfs.pdf("jour-" + suffixe));
        UUID[] vivant = deposer("Vivant-" + suffixe, Pdfs.pdf("vivant-" + suffixe));
        // Conservation de 12 mois à compter de la date du document ; échéance calculée par la base.
        jdbc.update("UPDATE type_document SET duree_conservation_mois = 12, point_depart = 'DATE_DOCUMENT' WHERE id = ?",
                type);
        java.time.LocalDate jour = com.ipt.ged.document.conservation.Echeances.aujourdhui();
        jdbc.update("UPDATE document SET date_document = ? WHERE id = ?", java.sql.Date.valueOf(jour.minusYears(2)), echu[0]);
        jdbc.update("UPDATE document SET date_document = ? WHERE id = ?", java.sql.Date.valueOf(jour.minusMonths(12)),
                jourMeme[0]);
        jdbc.update("UPDATE document SET date_document = ? WHERE id = ?", java.sql.Date.valueOf(jour), vivant[0]);

        // Sans plein texte : seuls les documents échus, signalés comme tels.
        String base = "{\"noeudId\":\"" + espace + "\",\"tri\":\"NOM\"";
        JsonNode echus = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(base + ",\"echeanceDepassee\":true}")).andExpect(status().isOk()));
        assertThat(echus.get("total").asLong()).isEqualTo(2);
        assertThat(echus.get("resultats").findValuesAsText("documentId"))
                .containsExactly(echu[0].toString(), jourMeme[0].toString());
        assertThat(echus.get("resultats").findValues("echeanceDepassee")).allMatch(JsonNode::asBoolean);
        // Sans le filtre (ou à faux) : tous, avec la mise en évidence propre à chacun.
        JsonNode tous = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(base + ",\"echeanceDepassee\":false}")).andExpect(status().isOk()));
        assertThat(tous.get("total").asLong()).isEqualTo(3);
        assertThat(tous.get("resultats").get(2).get("documentId").asText()).isEqualTo(vivant[0].toString());
        assertThat(tous.get("resultats").get(2).get("echeanceDepassee").asBoolean()).isFalse();

        // Avec plein texte : même filtre, appliqué dans la requête d'indexation.
        String mot = "conservation" + suffixe.replaceAll("[0-9]", "");
        for (UUID[] d : java.util.List.of(echu, jourMeme, vivant)) {
            indexeur.indexer(new SearchIndexer.TexteAIndexer(d[0], d[1], "fra", "Registre " + mot, "OCR", 1));
        }
        JsonNode texte = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"texte\":\"" + mot + "\",\"echeanceDepassee\":true}")).andExpect(status().isOk()));
        assertThat(texte.get("total").asLong()).isEqualTo(2);
        assertThat(texte.get("resultats").findValuesAsText("documentId"))
                .containsExactlyInAnyOrder(echu[0].toString(), jourMeme[0].toString());
        assertThat(json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content("{\"texte\":\"" + mot + "\"}"))).get("total").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET …/droits : droits de l'appelant, d'un tiers (administration), d'une clé d'API ; 403 et 404")
    void droits() throws Exception {
        UUID[] d = deposer("Droits-" + suffixe, Pdfs.pdf("d-" + suffixe));
        mvc.perform(get("/api/v1/noeuds/" + espace + "/droits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cible").value("NOEUD"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("CONSULTER")));
        mvc.perform(get("/api/v1/documents/" + d[0] + "/droits"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cible").value("DOCUMENT"));

        // Tiers : permission d'administration des droits (Administrateur).
        UUID collegue = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        jeu.habiliter(Comptes.SECOND_ACTEUR, "UTILISATEUR_STANDARD", espace, null);
        mvc.perform(get("/api/v1/noeuds/" + espace + "/droits").param("pourUtilisateur", Comptes.SECOND_ACTEUR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sujetId").value(collegue.toString()));
        // Le collègue voit l'espace mais ne peut pas consulter les droits d'un tiers : 403.
        mvc.perform(get("/api/v1/noeuds/" + espace + "/droits").with(user(jeu.identite(Comptes.SECOND_ACTEUR)))
                        .param("pourUtilisateur", Comptes.ADMIN))
                .andExpect(status().isForbidden());
        // … mais les siens, oui.
        mvc.perform(get("/api/v1/noeuds/" + espace + "/droits").with(user(jeu.identite(Comptes.SECOND_ACTEUR)))
                        .param("pourUtilisateur", Comptes.SECOND_ACTEUR))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/noeuds/" + espace + "/droits").with(user(jeu.identite(Comptes.TROISIEME_ACTEUR))))
                .andExpect(status().isNotFound());

        // Clé d'API : ses propres droits, selon sa portée.
        JsonNode app = json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                .content("{\"code\":\"app-ctr-" + suffixe + "\",\"nom\":\"Intranet\"}")));
        JsonNode g = json(mvc.perform(post("/api/v1/applications/" + app.get("id").asText() + "/cles")
                .contentType(APPLICATION_JSON).content("{}")));
        mvc.perform(put("/api/v1/cles-api/" + g.get("details").get("id").asText() + "/portee")
                        .contentType(APPLICATION_JSON)
                        .content("{\"portee\":[{\"noeudId\":\"" + espace + "\",\"operations\":[\"CONSULTATION_DROITS\"]}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/noeuds/" + espace + "/droits").with(anonymous())
                        .header(FiltreCleApi.ENTETE_CLE, g.get("cle").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sujet").value(org.hamcrest.Matchers.startsWith("application app-ctr-")))
                .andExpect(jsonPath("$.permissions[0]").value("CONSULTER"));
    }

    @Test
    @DisplayName("POST /documents par une application : portée DEPOT ; pour le compte d'un utilisateur, il est le déposant")
    void depotParApplication() throws Exception {
        JsonNode app = json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                .content("{\"code\":\"app-dep-" + suffixe + "\",\"nom\":\"Bureau d'ordre\","
                        + "\"adressesAutorisees\":[\"127.0.0.1\"]}")));
        JsonNode g = json(mvc.perform(post("/api/v1/applications/" + app.get("id").asText() + "/cles")
                .contentType(APPLICATION_JSON).content("{\"delegation\":true}")));
        mvc.perform(put("/api/v1/cles-api/" + g.get("details").get("id").asText() + "/portee")
                        .contentType(APPLICATION_JSON)
                        .content("{\"portee\":[{\"noeudId\":\"" + espace + "\",\"operations\":[\"DEPOT\"]}]}"))
                .andExpect(status().isOk());
        String cle = g.get("cle").asText();

        // Sans délégation : dépôt au nom de l'application (T-040 : canal API, application, sans déposant).
        UUID appId = UUID.fromString(app.get("id").asText());
        JsonNode seule = json(mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "a.pdf",
                                "application/pdf", Pdfs.pdf("app-" + suffixe)))
                        .param("name", "ParApplication").param("typeDocumentId", type.toString())
                        .with(anonymous()).header(FiltreCleApi.ENTETE_CLE, cle))
                .andExpect(status().is2xxSuccessful()));
        assertThat(seule.get("canalDepot").asText()).isEqualTo("API");
        assertThat(seule.get("applicationId").asText()).isEqualTo(appId.toString());
        assertThat(seule.get("deposantUtilisateurId").isNull()).isTrue();
        assertThat(seule.get("depotDelegue").asBoolean()).isFalse();

        // Pour le compte d'un utilisateur : il est le déposant ; double identité au journal.
        JsonNode d = json(mvc.perform(multipart("/api/v1/documents").file(new MockMultipartFile("file", "b.pdf",
                                "application/pdf", Pdfs.pdf("deleg-" + suffixe)))
                        .param("name", "PourLeCompte").param("typeDocumentId", type.toString())
                        .with(anonymous()).header(FiltreCleApi.ENTETE_CLE, cle)
                        .header(FiltreCleApi.ENTETE_DELEGATION, Comptes.SECOND_ACTEUR))
                .andExpect(status().is2xxSuccessful()));
        UUID doc = UUID.fromString(d.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT created_by_employe_id FROM document WHERE id = ?", UUID.class, doc))
                .isEqualTo(jeu.employeId(Comptes.SECOND_ACTEUR));
        java.util.Map<String, Object> trace = jdbc.queryForMap("SELECT acteur_utilisateur_id, acteur_application_id"
                + " FROM journal_audit WHERE action = 'DOCUMENT_DEPOSE' AND objet_id = ?", doc);
        assertThat(trace).containsEntry("acteur_utilisateur_id", jeu.utilisateurId(Comptes.SECOND_ACTEUR))
                .containsEntry("acteur_application_id", UUID.fromString(app.get("id").asText()));

        // Source du dépôt délégué (T-040, ANO-E9-001) : canal API, application, personne déléguée.
        UUID delegue = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        assertThat(d.get("canalDepot").asText()).isEqualTo("API");
        assertThat(d.get("applicationId").asText()).isEqualTo(appId.toString());
        assertThat(d.get("deposantUtilisateurId").asText()).isEqualTo(delegue.toString());
        assertThat(d.get("depotDelegue").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("SELECT canal_depot, application_id, deposant_utilisateur_id, depot_delegue"
                + " FROM document WHERE id = ?", doc))
                .containsEntry("canal_depot", "API").containsEntry("application_id", appId)
                .containsEntry("deposant_utilisateur_id", delegue).containsEntry("depot_delegue", true);

        // Rangés parmi les dépôts par API, jamais parmi ceux de l'interface.
        String parCanal = "{\"noeudId\":\"" + espace + "\",\"canal\":\"%s\"}";
        JsonNode api = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parCanal.formatted("API"))).andExpect(status().isOk()));
        assertThat(api.get("resultats").findValuesAsText("documentId"))
                .contains(doc.toString(), seule.get("id").asText());
        JsonNode web = json(mvc.perform(post("/api/v1/recherches").contentType(APPLICATION_JSON)
                .content(parCanal.formatted("INTERFACE"))).andExpect(status().isOk()));
        assertThat(web.get("resultats").findValuesAsText("documentId"))
                .doesNotContain(doc.toString(), seule.get("id").asText());
    }
}
