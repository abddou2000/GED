package com.ipt.ged.cleapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.autorisation.PermissionRefuseeException;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vague 4 du lot E9 : portée des clés (§5.4) et délégation d'identité (§5.5),
 * évaluées par le point d'application unique des droits du lot E3.
 *
 * <p>MockMvc appelle depuis 127.0.0.1 : c'est l'adresse autorisée des
 * applications de ce test (une clé qui délègue exige une liste d'adresses).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class PorteeEtDelegationApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;
    @Autowired private ControlePorteeApplication controlePortee;

    private UUID espaceA;
    private UUID espaceB;
    private UUID documentA;
    private UUID documentB;

    private record Cle(UUID applicationId, UUID cleId, String valeur) {}

    @BeforeEach
    void preparer() {
        String s = UUID.randomUUID().toString().substring(0, 6);
        espaceA = jeu.noeud("Portée A " + s, null);
        espaceB = jeu.noeud("Portée B " + s, null);
        documentA = jeu.document("DocA-" + s, jeu.type(espaceA, Confidentialite.PUBLIC), Confidentialite.PUBLIC, null);
        documentB = jeu.document("DocB-" + s, jeu.type(espaceB, Confidentialite.PUBLIC), Confidentialite.PUBLIC, null);
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Cle cle(boolean delegation) throws Exception {
        String code = "app-v4-" + UUID.randomUUID().toString().substring(0, 8);
        UUID app = UUID.fromString(json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"nom\":\"Bureau d'ordre\","
                                + "\"adressesAutorisees\":[\"127.0.0.1\"]}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        JsonNode g = json(mvc.perform(post("/api/v1/applications/" + app + "/cles").contentType(APPLICATION_JSON)
                        .content("{\"delegation\":" + delegation + "}"))
                .andExpect(status().isCreated()).andReturn());
        return new Cle(app, UUID.fromString(g.get("details").get("id").asText()), g.get("cle").asText());
    }

    private void portee(Cle c, Map<UUID, List<String>> lignes) throws Exception {
        StringBuilder b = new StringBuilder("{\"portee\":[");
        lignes.forEach((n, ops) -> b.append(b.length() > 12 ? "," : "").append("{\"noeudId\":\"").append(n)
                .append("\",\"operations\":[").append(String.join(",", ops.stream().map(o -> "\"" + o + "\"").toList()))
                .append("]}"));
        mvc.perform(put("/api/v1/cles-api/" + c.cleId() + "/portee").contentType(APPLICATION_JSON)
                        .content(b.append("]}").toString()))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder parCle(MockHttpServletRequestBuilder r, Cle c) {
        return r.with(anonymous()).header(FiltreCleApi.ENTETE_CLE, c.valeur());
    }

    private int lire(Cle c, UUID document, String deleguee) throws Exception {
        MockHttpServletRequestBuilder r = parCle(get("/api/v1/documents/" + document), c);
        if (deleguee != null) r.header(FiltreCleApi.ENTETE_DELEGATION, deleguee);
        return mvc.perform(r).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("Portée : sans portée rien n'est visible ; une portée CONSULTATION ouvre le nœud et lui seul ; audit")
    void porteeParNoeudEtOperation() throws Exception {
        Cle c = cle(false);
        assertThat(lire(c, documentA, null)).isEqualTo(404);

        portee(c, Map.of(espaceA, List.of("CONSULTATION")));
        assertThat(lire(c, documentA, null)).isEqualTo(200);
        assertThat(lire(c, documentB, null)).isEqualTo(404);
        // Opération hors portée sur un document visible : 403.
        mvc.perform(parCle(delete("/api/v1/documents/" + documentA), c)).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/cles-api/" + c.cleId() + "/portee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noeudId").value(espaceA.toString()))
                .andExpect(jsonPath("$[0].operations[0]").value("CONSULTATION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'CLE_API_PORTEE_MODIFIEE'"
                + " AND objet_id = ?", Integer.class, c.applicationId())).isEqualTo(1);

        // Portée retirée : effet immédiat (version des habilitations).
        mvc.perform(put("/api/v1/cles-api/" + c.cleId() + "/portee").contentType(APPLICATION_JSON)
                .content("{\"portee\":[]}")).andExpect(status().isOk());
        assertThat(lire(c, documentA, null)).isEqualTo(404);
    }

    @Test
    @DisplayName("Portée : nœud inconnu ou en double refusé (422) ; régénération : la nouvelle clé garde la portée")
    void validationEtRegeneration() throws Exception {
        Cle c = cle(false);
        mvc.perform(put("/api/v1/cles-api/" + c.cleId() + "/portee").contentType(APPLICATION_JSON)
                        .content("{\"portee\":[{\"noeudId\":\"" + UUID.randomUUID() + "\",\"operations\":[\"DEPOT\"]}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PORTEE_NOEUD_INCONNU"));
        mvc.perform(put("/api/v1/cles-api/" + c.cleId() + "/portee").contentType(APPLICATION_JSON)
                        .content("{\"portee\":[{\"noeudId\":\"" + espaceA + "\",\"operations\":[]}]}"))
                .andExpect(status().isBadRequest());

        portee(c, Map.of(espaceA, List.of("CONSULTATION")));
        JsonNode g = json(mvc.perform(post("/api/v1/cles-api/" + c.cleId() + "/regeneration"))
                .andExpect(status().isCreated()).andReturn());
        Cle nouvelle = new Cle(c.applicationId(), UUID.fromString(g.get("details").get("id").asText()),
                g.get("cle").asText());
        assertThat(lire(nouvelle, documentA, null)).isEqualTo(200);
        assertThat(lire(nouvelle, documentB, null)).isEqualTo(404);
    }

    @Test
    @DisplayName("ControlePorteeApplication : même décision que le point d'application unique")
    void controleExplicite() throws Exception {
        Cle c = cle(false);
        portee(c, Map.of(espaceA, List.of("CONSULTATION")));
        ApplicationAuthentifiee app = new ApplicationAuthentifiee(c.applicationId(), "app", c.cleId(), false);
        SecurityContext avant = SecurityContextHolder.getContext();
        try {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(app);
            SecurityContextHolder.setContext(ctx);
            controlePortee.verifier(app, OperationApi.CONSULTATION, espaceA);
            assertThatThrownBy(() -> controlePortee.verifier(app, OperationApi.DEPOT, espaceA))
                    .isInstanceOf(PermissionRefuseeException.class);
            assertThatThrownBy(() -> controlePortee.verifier(app, OperationApi.CONSULTATION, espaceB))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        } finally {
            SecurityContextHolder.setContext(avant);
        }
        assertThat(OperationApi.VERSEMENT.permissions()).contains(CodePermission.MODIFIER, CodePermission.CONSULTER);
    }

    @Test
    @DisplayName("Délégation : lecture = intersection des droits de la clé et de l'utilisateur")
    void lectureEnIntersection() throws Exception {
        Cle c = cle(true);
        portee(c, Map.of(espaceA, List.of("CONSULTATION")));
        assertThat(lire(c, documentA, null)).isEqualTo(200);
        // Le délégué n'a aucun droit sur A (rupture d'héritage : quels que soient ses droits
        // hérités posés par d'autres tests sur la même base) : l'intersection est vide.
        jeu.rupture(Comptes.SECOND_ACTEUR, espaceA);
        assertThat(lire(c, documentA, Comptes.SECOND_ACTEUR)).isEqualTo(404);
        // Habilité sur A et sur B : A seulement (B est hors portée de la clé).
        jeu.habiliter(Comptes.SECOND_ACTEUR, "UTILISATEUR_STANDARD", espaceA, null);
        jeu.habiliter(Comptes.SECOND_ACTEUR, "UTILISATEUR_STANDARD", espaceB, null);
        assertThat(lire(c, documentA, Comptes.SECOND_ACTEUR)).isEqualTo(200);
        assertThat(lire(c, documentB, Comptes.SECOND_ACTEUR)).isEqualTo(404);
    }

    @Test
    @DisplayName("Délégation : écriture avec les droits de la clé, double identité dans le journal")
    void ecritureDoubleIdentite() throws Exception {
        Cle c = cle(true);
        portee(c, Map.of(espaceA, List.of("CREATION_DOSSIER")));
        UUID delegue = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        String code = "DOS-V4-" + UUID.randomUUID().toString().substring(0, 8);
        String corps = "{\"name\":\"Dossier délégué\",\"code\":\"" + code + "\",\"employeId\":\""
                + jeu.employeId(Comptes.ADMIN) + "\",\"parentId\":\"" + espaceA + "\",\"workflowId\":\""
                + jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class, espaceA) + "\"}";
        UUID dossier = UUID.fromString(json(mvc.perform(parCle(post("/api/v1/workspaces"), c)
                        .header(FiltreCleApi.ENTETE_DELEGATION, Comptes.SECOND_ACTEUR)
                        .contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        Map<String, Object> trace = jdbc.queryForMap("SELECT acteur_utilisateur_id, acteur_application_id"
                + " FROM journal_audit WHERE action = 'ESPACE_CREE' AND objet_id = ?", dossier);
        assertThat(trace).containsEntry("acteur_utilisateur_id", delegue)
                .containsEntry("acteur_application_id", c.applicationId());
    }

    @Test
    @DisplayName("Délégation refusée : clé sans l'attribut (403), identité inconnue (422), application sans adresses (403)")
    void delegationRefusee() throws Exception {
        Cle sans = cle(false);
        portee(sans, Map.of(espaceA, List.of("CONSULTATION")));
        mvc.perform(parCle(get("/api/v1/documents/" + documentA), sans)
                        .header(FiltreCleApi.ENTETE_DELEGATION, Comptes.SECOND_ACTEUR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.DELEGATION_NON_AUTORISEE));

        Cle c = cle(true);
        portee(c, Map.of(espaceA, List.of("CONSULTATION")));
        mvc.perform(parCle(get("/api/v1/documents/" + documentA), c)
                        .header(FiltreCleApi.ENTETE_DELEGATION, "inconnu.total"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.IDENTITE_DELEGUEE_INVALIDE));
        mvc.perform(parCle(get("/api/v1/documents/" + documentA), c)
                        .header(FiltreCleApi.ENTETE_DELEGATION, "x\" OR 1=1"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(put("/api/v1/applications/" + c.applicationId()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + jdbc.queryForObject("SELECT code FROM application WHERE id = ?",
                                String.class, c.applicationId()) + "\",\"nom\":\"Sans adresses\",\"adressesAutorisees\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.DELEGATION_SANS_ADRESSES));
        // Donnée antérieure à la règle : le filtre refuse quand même la délégation.
        jdbc.update("UPDATE application SET adresses_autorisees = NULL WHERE id = ?", c.applicationId());
        mvc.perform(parCle(get("/api/v1/documents/" + documentA), c)
                        .header(FiltreCleApi.ENTETE_DELEGATION, Comptes.SECOND_ACTEUR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.DELEGATION_SANS_ADRESSES));
    }

    @Test
    @WithUserDetails(Comptes.SANS_ROLE)
    @DisplayName("Administration des clés réservée à GERER_CLES_API")
    void administrationReserveeALaPermission() throws Exception {
        mvc.perform(get("/api/v1/applications")).andExpect(status().isForbidden());
    }
}
