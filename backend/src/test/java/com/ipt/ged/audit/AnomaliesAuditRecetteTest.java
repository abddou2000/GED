package com.ipt.ged.audit;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.document.GardeEcriture;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anomalies de recette (qa, ANOMALIES.md) sur le code du lot modèle :
 * ANO-E4-001 consultation de fiche tracée, ANO-E4-002 accès hors périmètre
 * tracé côté serveur (404 inchangé pour le client), ANO-E4-003 codes de
 * désignation au catalogue, ANO-E7-002 écritures refusées sur un document
 * archivé ou verrouillé (rattachement, détachement, désignations).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class AnomaliesAuditRecetteTest {

    @Autowired private MockMvc mvc;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;
    @Autowired private JdbcTemplate jdbc;

    private UUID espace, autre, document;

    @BeforeEach
    void jeu() {
        espace = jeu.noeud("Espace recette", null);
        autre = jeu.noeud("Autre espace recette", null);
        document = jeu.document("Pièce recette", jeu.type(espace, Confidentialite.PUBLIC), Confidentialite.PUBLIC,
                null);
    }

    private RequestPostProcessor comme(String identifiant) {
        return user(identites.loadUserByUsername(identifiant));
    }

    private int traces(String action, UUID objet) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = ? AND objet_id = ?",
                Integer.class, action, objet);
    }

    @Test
    @DisplayName("ANO-E4-001 : la lecture d'une fiche document est tracée DOCUMENT_CONSULTE")
    void consultationTracee() throws Exception {
        mvc.perform(get("/api/v1/documents/" + document)).andExpect(status().isOk());
        assertThat(traces("DOCUMENT_CONSULTE", document)).isEqualTo(1);
        assertThat(ActionAudit.connu("DOCUMENT_CONSULTE")).isTrue();
    }

    @Test
    @DisplayName("ANO-E4-002 : hors périmètre = 404 pour le client, ACCES_HORS_PERIMETRE (refus) au journal")
    void horsPerimetreTrace() throws Exception {
        // Nadia détient un rôle, mais pas sur l'espace du document.
        jeu.habiliter(Comptes.SANS_ROLE, Role.UTILISATEUR_STANDARD, autre, null);
        mvc.perform(get("/api/v1/documents/" + document).with(comme(Comptes.SANS_ROLE)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'ACCES_HORS_PERIMETRE'"
                + " AND objet_id = ? AND resultat = 'REFUS'", Integer.class, document)).isEqualTo(1);

        // Un identifiant qui ne désigne rien : même 404, aucune trace.
        UUID absent = UUID.randomUUID();
        mvc.perform(get("/api/v1/documents/" + absent).with(comme(Comptes.SANS_ROLE)))
                .andExpect(status().isNotFound());
        assertThat(traces("ACCES_HORS_PERIMETRE", absent)).isZero();
    }

    @Test
    @DisplayName("ANO-E4-003 : codes de désignation au catalogue, émis à la désignation")
    void designationAuCatalogue() throws Exception {
        assertThat(ActionAudit.connu("DESIGNATION_AJOUTEE")).isTrue();
        assertThat(ActionAudit.connu("DESIGNATION_RETIREE")).isTrue();
        assertThat(ActionAudit.connu("CONFIDENTIALITE_MODIFIEE")).isTrue();
        assertThat(ActionAudit.connu("DECONNEXION")).isTrue();
        UUID karim = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        mvc.perform(post("/api/v1/documents/" + document + "/designes").contentType(APPLICATION_JSON)
                .content("{\"utilisateurId\":\"" + karim + "\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/documents/" + document + "/designes/" + karim))
                .andExpect(status().isNoContent());
        assertThat(traces("DESIGNATION_AJOUTEE", document)).isEqualTo(1);
        assertThat(traces("DESIGNATION_RETIREE", document)).isEqualTo(1);
    }

    @Test
    @DisplayName("ANO-E7-002 : document archivé — rattachement, détachement et désignations refusés (409)")
    void archiveEnLectureSeule() throws Exception {
        mvc.perform(post("/api/v1/documents/" + document + "/rattachements").contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + autre + "\"}")).andExpect(status().isCreated());
        jdbc.update("UPDATE document SET statut_conservation = 'ARCHIVE', archive_le = now() WHERE id = ?", document);

        mvc.perform(post("/api/v1/documents/" + document + "/rattachements").contentType(APPLICATION_JSON)
                        .content("{\"noeudId\":\"" + jeu.noeud("Troisième", null) + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(GardeEcriture.DOCUMENT_ARCHIVE));
        mvc.perform(delete("/api/v1/documents/" + document + "/rattachements/" + autre))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(GardeEcriture.DOCUMENT_ARCHIVE));
        mvc.perform(post("/api/v1/documents/" + document + "/designes").contentType(APPLICATION_JSON)
                        .content("{\"utilisateurId\":\"" + jeu.utilisateurId(Comptes.SECOND_ACTEUR) + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(GardeEcriture.DOCUMENT_ARCHIVE));
        mvc.perform(delete("/api/v1/documents/" + document + "/designes/" + jeu.utilisateurId(Comptes.ADMIN)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(GardeEcriture.DOCUMENT_ARCHIVE));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_rattachement WHERE document_id = ?",
                Integer.class, document)).isEqualTo(1);
    }

    @Test
    @DisplayName("ANO-E7-002 : document verrouillé — rattachement refusé (409 DOCUMENT_VERROUILLE)")
    void verrouille() throws Exception {
        mvc.perform(patch("/api/v1/documents/" + document + "/verrou").param("verrouille", "true")
                .param("motif", "contrôle")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/documents/" + document + "/rattachements").contentType(APPLICATION_JSON)
                        .content("{\"noeudId\":\"" + autre + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(GardeEcriture.DOCUMENT_VERROUILLE));
    }
}
