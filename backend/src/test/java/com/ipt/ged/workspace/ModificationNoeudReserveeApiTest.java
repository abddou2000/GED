package com.ipt.ged.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ipt.ged.idempotence.FiltreIdempotence;
import com.ipt.ged.identite.Role;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ANO-F-026 (DF §4.3.4, D12) : modifier un espace ou un dossier
 * ({@code PUT /api/v1/workspaces/{id}}) relève de la gestion des espaces
 * ({@code GERER_ESPACES}), comme sa création. La permission Modifier que porte
 * l'Utilisateur standard ne suffit plus : sous elle, un standard renommait un
 * espace métier, en changeait le propriétaire, la règle de workflow, et le
 * basculait en espace d'échange pour s'y ouvrir la création de dossiers.
 *
 * <p>Exception D12 conservée : dans un espace d'échange, le membre qui a
 * Déposer crée des dossiers et sous-dossiers ; il ne les restructure pas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class ModificationNoeudReserveeApiTest {

    private static final String U = Comptes.SANS_ROLE;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;
    @Autowired private WorkSpaceRepository noeuds;
    @Autowired private JdbcTemplate jdbc;

    /** Espace métier, son dossier, un espace d'échange ; U est Utilisateur standard sur les deux espaces. */
    private UUID metier, dossier, echange, autreRegle;

    @BeforeEach
    void jeu() {
        metier = jeu.noeud("QA2 Projets", null);
        dossier = jeu.noeud("Études", metier);
        echange = jeu.noeud("Marchés CPS", null);
        WorkSpace e = noeuds.findById(echange).orElseThrow();
        e.setUsageEspace(UsageEspace.ECHANGE);
        noeuds.saveAndFlush(e);
        autreRegle = jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class,
                jeu.noeud("Autre", null));
        jeu.identite(U);
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, metier, null);
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, echange, null);
    }

    private RequestPostProcessor comme(String identifiant) {
        return user(identites.loadUserByUsername(identifiant));
    }

    /** Corps de PUT reproduisant le nœud tel qu'il est (ce que renvoie le formulaire). */
    private ObjectNode etatActuel(UUID id) {
        Map<String, Object> n = jdbc.queryForMap("SELECT nom, code, description, status, employe_id, parent_id,"
                + " regle_workflow_id, usage_espace FROM noeud WHERE id = ?", id);
        ObjectNode corps = om.createObjectNode();
        corps.put("name", (String) n.get("nom"));
        corps.put("code", (String) n.get("code"));
        corps.put("description", (String) n.get("description"));
        corps.put("status", (String) n.get("status"));
        corps.put("employeId", String.valueOf(n.get("employe_id")));
        if (n.get("parent_id") != null) corps.put("parentId", String.valueOf(n.get("parent_id")));
        if (n.get("regle_workflow_id") != null) corps.put("workflowId", String.valueOf(n.get("regle_workflow_id")));
        corps.put("usageEspace", (String) n.get("usage_espace"));
        return corps;
    }

    private ObjectNode modifie(UUID id, Consumer<ObjectNode> changement) {
        ObjectNode corps = etatActuel(id);
        changement.accept(corps);
        return corps;
    }

    /** Variantes du PUT : chaque champ structurant changé seul, et le PUT à l'identique. */
    private Map<String, ObjectNode> variantes(UUID id) {
        String autreProprietaire = jeu.employeId(Comptes.SECOND_ACTEUR).toString();
        return Map.of(
                "à l'identique", etatActuel(id),
                "nom", modifie(id, c -> c.put("name", "Renommé " + UUID.randomUUID())),
                "code", modifie(id, c -> c.put("code", "C-" + UUID.randomUUID().toString().substring(0, 8))),
                "propriétaire", modifie(id, c -> c.put("employeId", autreProprietaire)),
                "statut", modifie(id, c -> c.put("status", "ARCHIVE")),
                "usage", modifie(id, c -> c.put("usageEspace", "ECHANGE")),
                "règle de workflow", modifie(id, c -> c.put("workflowId", autreRegle.toString())),
                "parent", modifie(id, c -> {
                    if (c.has("parentId")) c.remove("parentId");
                    else c.put("parentId", echange.toString());
                }));
    }

    private long refusTraces(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'ACCES_REFUSE'"
                + " AND resultat = 'REFUS' AND motif LIKE ?", Long.class, "%PUT /api/v1/workspaces/" + id + "%");
    }

    @Test
    @DisplayName("Utilisateur standard : 403 sur chaque champ structurant d'un espace et d'un dossier, refus tracé, rien n'est écrit")
    void standardRefuse() throws Exception {
        for (UUID id : new UUID[]{metier, dossier}) {
            Map<String, Object> avant = jdbc.queryForMap("SELECT * FROM noeud WHERE id = ?", id);
            for (Map.Entry<String, ObjectNode> v : variantes(id).entrySet()) {
                long traces = refusTraces(id);
                mvc.perform(put("/api/v1/workspaces/" + id).with(comme(U)).contentType(APPLICATION_JSON)
                                .content(v.getValue().toString()))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
                assertThat(refusTraces(id)).as("refus tracé : " + v.getKey()).isEqualTo(traces + 1);
            }
            assertThat(jdbc.queryForMap("SELECT * FROM noeud WHERE id = ?", id)).isEqualTo(avant);
        }
    }

    @Test
    @DisplayName("ANO-F-026 : le standard ne bascule plus un espace métier en échange pour y créer des dossiers")
    void basculeVersEchangeRefusee() throws Exception {
        mvc.perform(put("/api/v1/workspaces/" + metier).with(comme(U)).contentType(APPLICATION_JSON)
                        .content(modifie(metier, c -> c.put("usageEspace", "ECHANGE")).toString()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT usage_espace FROM noeud WHERE id = ?", String.class, metier))
                .isEqualTo("METIER");
        mvc.perform(post("/api/v1/noeuds/" + metier + "/dossiers").with(comme(U)).contentType(APPLICATION_JSON)
                        .header(FiltreIdempotence.ENTETE, UUID.randomUUID().toString())
                        .content("{\"nom\":\"Contournement\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Administrateur (GERER_ESPACES) : chaque champ structurant se modifie")
    void administrateurModifie() throws Exception {
        for (UUID id : new UUID[]{metier, dossier}) {
            for (Map.Entry<String, ObjectNode> v : variantes(id).entrySet()) {
                mvc.perform(put("/api/v1/workspaces/" + id).contentType(APPLICATION_JSON)
                                .content(v.getValue().toString()))
                        .andExpect(status().isOk());
            }
        }
        mvc.perform(put("/api/v1/workspaces/" + metier).contentType(APPLICATION_JSON)
                        .content(modifie(metier, c -> {
                            c.put("name", "QA2 Projets bis");
                            c.put("usageEspace", "ECHANGE");
                            c.put("workflowId", autreRegle.toString());
                        }).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("QA2 Projets bis"))
                .andExpect(jsonPath("$.usageEspace").value("ECHANGE"))
                .andExpect(jsonPath("$.workflow.id").value(autreRegle.toString()));
    }

    @Test
    @DisplayName("D12 : dans un espace d'échange, le membre crée dossiers et sous-dossiers, mais ne les modifie pas")
    void exceptionEchange() throws Exception {
        String cree = mvc.perform(post("/api/v1/noeuds/" + echange + "/dossiers").with(comme(U))
                        .contentType(APPLICATION_JSON).header(FiltreIdempotence.ENTETE, UUID.randomUUID().toString())
                        .content("{\"nom\":\"Lot 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usageEspace").value("ECHANGE"))
                .andReturn().getResponse().getContentAsString();
        UUID lot = UUID.fromString(om.readTree(cree).get("id").asText());
        mvc.perform(post("/api/v1/noeuds/" + lot + "/dossiers").with(comme(U))
                        .contentType(APPLICATION_JSON).header(FiltreIdempotence.ENTETE, UUID.randomUUID().toString())
                        .content("{\"nom\":\"Pièces\"}"))
                .andExpect(status().isCreated());

        // Ni l'espace d'échange, ni le dossier qu'il vient de créer : la structure reste à l'Administrateur.
        for (UUID id : new UUID[]{echange, lot}) {
            mvc.perform(put("/api/v1/workspaces/" + id).with(comme(U)).contentType(APPLICATION_JSON)
                            .content(modifie(id, c -> c.put("name", "Renommé " + UUID.randomUUID())).toString()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(put("/api/v1/workspaces/" + echange).with(comme(U)).contentType(APPLICATION_JSON)
                        .content(modifie(echange, c -> c.put("usageEspace", "METIER")).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Fiche : MODIFIER n'est annoncé qu'à qui peut modifier le nœud (l'écran masque « Modifier »)")
    void permissionsDeLaFiche() throws Exception {
        for (UUID id : new UUID[]{metier, dossier, echange}) {
            mvc.perform(get("/api/v1/workspaces/" + id).with(comme(U)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[?(@ == 'CONSULTER')]").exists())
                    .andExpect(jsonPath("$.permissions[?(@ == 'DEPOSER')]").exists())
                    .andExpect(jsonPath("$.permissions[?(@ == 'MODIFIER')]").doesNotExist());
            mvc.perform(get("/api/v1/workspaces/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[?(@ == 'MODIFIER')]").exists());
        }
    }
}
