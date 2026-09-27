package com.ipt.ged.socle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workflow.WorkflowStep;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Socle de données (étape E1) vu depuis l'application en fonctionnement :
 * compte d'exécution, stack imposée, identifiants UUID, suppression douce et
 * métadonnées JSONB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class SocleDonneesTest {

    /** SQLSTATE PostgreSQL « insufficient_privilege » (indépendant de la langue du serveur). */
    private static final String DROIT_REFUSE = "42501";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private EmployeRepository employeRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkSpaceRepository workspaceRepository;
    @Autowired private TypeDocumentRepository typeRepository;
    @Autowired private UploadDocumentRepository documentRepository;

    @Test
    @DisplayName("L'application s'exécute avec ged_app, dans le schéma ged")
    void compteApplicatif() {
        assertEquals("ged_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals("ged", jdbc.queryForObject("SELECT current_schema()", String.class));
    }

    @Test
    @DisplayName("ged_app n'a aucun droit DDL ni accès au registre des migrations (§4.2.3)")
    void compteApplicatifSansDdl() {
        assertDroitRefuse(() -> jdbc.execute("CREATE TABLE ged.intrusion (id uuid)"));
        assertDroitRefuse(() -> jdbc.execute("ALTER TABLE ged.document ADD COLUMN intrusion text"));
        assertDroitRefuse(() -> jdbc.execute("DROP TABLE ged.etiquette"));
        assertDroitRefuse(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ged_liquibase.databasechangelog", Integer.class));
    }

    @Test
    @DisplayName("PostgreSQL 16 ou plus, configuration de recherche arabe disponible (§2.2.1)")
    void stackPostgresql() {
        int version = jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);
        assertTrue(version >= 160000, "PostgreSQL 16 minimum, trouvé : " + version);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_ts_config WHERE cfgname = 'arabic'", Integer.class));
        // La configuration répond effectivement : racinisation d'un mot arabe.
        String lexemes = jdbc.queryForObject("SELECT to_tsvector('arabic', 'المكتبات')::text", String.class);
        assertNotNull(lexemes);
        assertTrue(!lexemes.isBlank(), "to_tsvector('arabic', ...) doit produire des lexèmes");
    }

    @Test
    @DisplayName("Les identifiants exposés par l'API sont des UUID ; un identifiant mal formé donne 400")
    void identifiantsOpaques() throws Exception {
        String reponse = mvc.perform(post("/api/v1/etiquettes").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TAG-UUID\",\"tag\":\"Opaque\",\"couleur\":\"#000000\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(om.readTree(reponse).get("id").asText());
        assertEquals(7, id.version(), "UUID version 7 attendu");

        mvc.perform(get("/api/v1/documents/12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("UUID")));
        mvc.perform(get("/api/v1/documents/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Suppression douce : auteur et date posés à la suppression, effacés à la restauration (§12.5)")
    void suppressionDouceAvecAuteurEtDate() throws Exception {
        String reponse = mvc.perform(post("/api/v1/etiquettes").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"TAG-SD\",\"tag\":\"Corbeille\",\"couleur\":\"#000000\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = om.readTree(reponse).get("id").asText();
        UUID admin = Comptes.idAdmin(employeRepository);

        mvc.perform(delete("/api/v1/etiquettes/" + id)).andExpect(status().isNoContent());
        Map<String, Object> ligne = ligneEtiquette(id);
        assertEquals(Boolean.TRUE, ligne.get("supprime"));
        assertEquals(admin, ligne.get("supprime_par"));
        assertNotNull(ligne.get("supprime_le"));

        mvc.perform(patch("/api/v1/etiquettes/" + id + "/restore")).andExpect(status().isNoContent());
        ligne = ligneEtiquette(id);
        assertEquals(Boolean.FALSE, ligne.get("supprime"));
        assertNull(ligne.get("supprime_par"));
        assertNull(ligne.get("supprime_le"));

        // Suppression multiple : même traçabilité.
        mvc.perform(delete("/api/v1/etiquettes/multiple-delete").contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isNoContent());
        assertEquals(admin, ligneEtiquette(id).get("supprime_par"));
    }

    @Test
    @DisplayName("La base refuse un auteur de suppression sur un objet vivant (ck_etiquette_suppression)")
    void contrainteSuppressionCoherente() {
        UUID admin = Comptes.idAdmin(employeRepository);
        DataAccessException refus = assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO etiquette (id, code, tag, couleur, supprime, supprime_par, supprime_le)"
                        + " VALUES (?, 'TAG-CK', 'x', '#000', false, ?, now())", UUID.randomUUID(), admin));
        assertEquals("23514", sqlState(refus), "violation de contrainte CHECK attendue");
    }

    @Test
    @DisplayName("document.metadonnees vaut {} par défaut, jamais NULL (§12.7)")
    void metadonneesParDefaut() {
        Employe e = employeRepository.findById(Comptes.idAdmin(employeRepository)).orElseThrow();
        WorkflowGed wf = new WorkflowGed("WF socle");
        wf.addStep(new WorkflowStep(e, "Validation", 1));
        workflowRepository.save(wf);
        WorkSpace ws = new WorkSpace("Socle", "WS-SOCLE");
        ws.setStatus(WorkspaceStatus.ACTIF);
        ws.setOwner(e);
        ws.setWorkflow(wf);
        workspaceRepository.save(ws);
        TypeDocument type = new TypeDocument("TD-SOCLE", "Pièce");
        type.setDescription("desc");
        type.setWorkspace(ws);
        type.setTailleMaxMo(1);
        typeRepository.save(type);
        UploadDocument doc = new UploadDocument("Pièce socle");
        doc.setWorkspace(ws);
        doc.setTypeDocument(type);
        documentRepository.save(doc);
        em.flush();

        assertEquals("{}", jdbc.queryForObject(
                "SELECT metadonnees::text FROM document WHERE id = ?", String.class, doc.getId()));

        // Écriture et relecture d'une valeur : le type JSONB est bien celui
        // qu'Hibernate attend (sinon ddl-auto: validate aurait refusé de démarrer).
        doc.getMetadonnees().put("numero_courrier", "BO-2026-0001");
        em.flush();
        assertEquals("BO-2026-0001", jdbc.queryForObject(
                "SELECT metadonnees ->> 'numero_courrier' FROM document WHERE id = ?", String.class, doc.getId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM document WHERE metadonnees @> '{\"numero_courrier\":\"BO-2026-0001\"}'",
                Integer.class));
    }

    /* ---------------------------------------------------------------- outillage */

    private Map<String, Object> ligneEtiquette(String id) {
        em.flush();
        return jdbc.queryForMap("SELECT supprime, supprime_par, supprime_le FROM etiquette WHERE id = ?",
                UUID.fromString(id));
    }

    private static void assertDroitRefuse(Runnable ordre) {
        DataAccessException refus = assertThrows(DataAccessException.class, ordre::run);
        assertEquals(DROIT_REFUSE, sqlState(refus), "refus de droit attendu : " + refus.getMessage());
    }

    private static String sqlState(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        assertInstanceOf(SQLException.class, cause);
        return ((SQLException) cause).getSQLState();
    }
}
