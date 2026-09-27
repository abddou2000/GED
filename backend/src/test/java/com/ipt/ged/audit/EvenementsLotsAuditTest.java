package com.ipt.ged.audit;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les événements des lots identité (E2) et autorisation (E3), publiés par
 * contrat {@link EvenementAudit}, arrivent au journal d'audit avec l'identité
 * GED de l'acteur ({@code utilisateur.id}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvenementsLotsAuditTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;

    private Map<String, Object> dernier(String action, String objetType, UUID objetId) {
        return jdbc.queryForMap("SELECT acteur_utilisateur_id, acteur_nom, resultat, motif FROM journal_audit"
                + " WHERE action = ? AND objet_type = ? AND objet_id = ? ORDER BY id DESC LIMIT 1",
                action, objetType, objetId);
    }

    @Test
    @DisplayName("Identité : connexion réussie et refusée au journal, acteur = identité GED")
    void connexions() throws Exception {
        UUID utilisateur = jeu.utilisateurId(Comptes.SECOND_ACTEUR);
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"identifiant\":\"" + Comptes.SECOND_ACTEUR + "\",\"motDePasse\":\""
                                + Comptes.MOT_DE_PASSE + "\"}"))
                .andExpect(status().isOk());
        assertThat(dernier("CONNEXION_REUSSIE", "UTILISATEUR", utilisateur))
                .containsEntry("acteur_utilisateur_id", utilisateur).containsEntry("resultat", "SUCCES");

        long refus = jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'CONNEXION_REFUSEE'",
                Long.class);
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"identifiant\":\"" + Comptes.SECOND_ACTEUR + "\",\"motDePasse\":\"faux\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'CONNEXION_REFUSEE'"
                + " AND resultat = 'REFUS'", Long.class)).isGreaterThan(refus);
    }

    @Test
    @DisplayName("Autorisation : attribution d'une habilitation au journal (HABILITATION_MODIFIEE, valeurs après)")
    void habilitation() {
        UUID noeud = jeu.noeud("Audit E3 " + UUID.randomUUID().toString().substring(0, 6), null);
        jeu.type(noeud, Confidentialite.PUBLIC);
        UUID habilitation = jeu.habiliter(Comptes.TROISIEME_ACTEUR, "UTILISATEUR_STANDARD", noeud, null).id();
        Map<String, Object> ligne = jdbc.queryForMap("SELECT motif, resultat, apres::text AS apres FROM journal_audit"
                + " WHERE action = 'HABILITATION_MODIFIEE' AND objet_id = ?", habilitation);
        assertThat(ligne).containsEntry("motif", "AJOUT").containsEntry("resultat", "SUCCES");
        assertThat((String) ligne.get("apres")).contains(noeud.toString(), "UTILISATEUR_STANDARD");
    }
}
