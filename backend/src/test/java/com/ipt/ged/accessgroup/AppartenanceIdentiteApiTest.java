package com.ipt.ged.accessgroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.identite.Role;
import com.ipt.ged.identite.ServiceIdentites;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.annuaire.FicheAnnuaire;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-025, écart 2 (décision du client du 03/10) : le membre d'un groupe GED est
 * une identité GED ({@code groupe_membre.utilisateur_id}). L'API des groupes
 * garde son contrat (membres désignés par leur fiche employé) ; une personne
 * qui ne s'est jamais connectée est membre EN ATTENTE, sans aucun droit, et le
 * devient réellement à sa première connexion, sans action de l'Administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithUserDetails(Comptes.ADMIN)
class AppartenanceIdentiteApiTest {

    private static final String BASE = "/api/v1/access-groups";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JeuDroits jeu;
    @Autowired private EmployeRepository employes;
    @Autowired private ServiceIdentites identites;
    @Autowired private ProprietesIdentite proprietes;
    @Autowired private AccessPredicate droits;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    /** Fiche employé reprise, sans identité GED ; nom unique (rattachement par courriel dérivé). */
    private Employe ficheSansIdentite() {
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return employes.saveAndFlush(new Employe("Ilyas", "Berrada" + suffixe, false));
    }

    private JsonNode creer(String code, List<UUID> espaces, List<UUID> membres) throws Exception {
        String corps = om.writeValueAsString(java.util.Map.of("code", code, "name", "Groupe " + code,
                "workspaceIds", espaces, "userIds", membres));
        return om.readTree(mvc.perform(post(BASE).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int compter(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Membre sans identité : en attente, sans droit ; à sa première connexion il devient membre et reçoit les droits du groupe")
    void droitsAppliquesALaPremiereConnexion() throws Exception {
        UUID espace = jeu.noeud("Espace préparé", null);
        Employe fiche = ficheSansIdentite();

        JsonNode groupe = creer("AG-PREP-" + fiche.getId().toString().substring(30), List.of(espace), List.of(fiche.getId()));
        UUID groupeId = UUID.fromString(groupe.get("id").asText());
        // Contrat inchangé : le membre figure dans users par sa fiche, et il est signalé en attente.
        assertThat(groupe.get("usersCount").asInt()).isEqualTo(1);
        assertThat(groupe.get("users").get(0).get("id").asText()).isEqualTo(fiche.getId().toString());
        assertThat(groupe.get("pendingUserIds").get(0).asText()).isEqualTo(fiche.getId().toString());
        assertThat(compter("SELECT count(*) FROM groupe_membre WHERE groupe_ged_id = ?", groupeId)).isZero();
        assertThat(compter("SELECT count(*) FROM groupe_membre_attente WHERE groupe_ged_id = ? AND employe_id = ?",
                groupeId, fiche.getId())).isEqualTo(1);

        // Première connexion : l'annuaire présente la personne ; l'identité est rattachée à la fiche reprise.
        String identifiant = "i" + fiche.getLastName().toLowerCase();
        Utilisateur u = identites.provisionner(new FicheAnnuaire(UUID.randomUUID(), identifiant, "Ilyas",
                fiche.getLastName(), null, "ilyas." + fiche.getLastName().toLowerCase() + "@"
                + proprietes.getDomaineCourriel(), null), true).utilisateur();
        assertThat(u.getEmploye().getId()).isEqualTo(fiche.getId());

        // L'appartenance est devenue réelle, sans action de l'Administrateur.
        assertThat(compter("SELECT count(*) FROM groupe_membre WHERE groupe_ged_id = ? AND utilisateur_id = ?",
                groupeId, u.getId())).isEqualTo(1);
        assertThat(compter("SELECT count(*) FROM groupe_membre_attente WHERE employe_id = ?", fiche.getId())).isZero();

        // Et le droit du groupe s'applique : rôle Utilisateur standard sur l'espace couvert.
        UtilisateurConnecte principal = identites.principal(u.getId(), null).orElseThrow();
        assertThat(identites.roles(u).get(1)).contains(Role.UTILISATEUR_STANDARD);
        assertThat(droits.noeudsAccessibles(new UsernamePasswordAuthenticationToken(principal, null,
                principal.getAuthorities()), CodePermission.CONSULTER)).contains(espace);
        mvc.perform(get("/api/v1/workspaces/" + espace).with(user(principal))).andExpect(status().isOk());

        // L'écran voit désormais un membre ordinaire. (La conversion écrit en SQL, normalement dans
        // la transaction de connexion ; ici tout partage la transaction du test : le groupe chargé
        // par la création est retiré du contexte de persistance pour être relu.)
        em.flush();
        em.clear();
        mvc.perform(get(BASE + "/" + groupeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].id").value(fiche.getId().toString()))
                .andExpect(jsonPath("$.pendingUserIds").isEmpty());
    }

    @Test
    @DisplayName("userIds accepte la fiche employé (contrat d'origine) ou l'identité GED ; un membre connecté n'est jamais en attente")
    void identifiantsTraduits() throws Exception {
        UtilisateurConnecte admin = jeu.identite(Comptes.ADMIN);
        Employe fiche = ficheSansIdentite();

        // Fiche employé d'une personne connectée -> son identité est membre.
        JsonNode parFiche = creer("AG-FICHE-" + fiche.getId().toString().substring(30), List.of(),
                List.of(admin.getEmployeId(), fiche.getId()));
        UUID g1 = UUID.fromString(parFiche.get("id").asText());
        assertThat(compter("SELECT count(*) FROM groupe_membre WHERE groupe_ged_id = ? AND utilisateur_id = ?",
                g1, admin.getUtilisateurId())).isEqualTo(1);
        assertThat(parFiche.get("pendingUserIds")).hasSize(1);
        assertThat(parFiche.get("pendingUserIds").get(0).asText()).isEqualTo(fiche.getId().toString());

        // Identité GED directement -> même appartenance ; la réponse la désigne par sa fiche.
        JsonNode parIdentite = creer("AG-IDENT-" + fiche.getId().toString().substring(30), List.of(),
                List.of(admin.getUtilisateurId()));
        UUID g2 = UUID.fromString(parIdentite.get("id").asText());
        assertThat(compter("SELECT count(*) FROM groupe_membre WHERE groupe_ged_id = ? AND utilisateur_id = ?",
                g2, admin.getUtilisateurId())).isEqualTo(1);
        assertThat(parIdentite.get("users").get(0).get("id").asText()).isEqualTo(admin.getEmployeId().toString());
        assertThat(parIdentite.get("pendingUserIds")).isEmpty();

        // L'écran renvoie les identifiants qu'il a reçus : rien ne change, l'attente est conservée.
        String corps = om.writeValueAsString(java.util.Map.of("code", parFiche.get("code").asText(),
                "name", parFiche.get("name").asText(),
                "userIds", List.of(admin.getEmployeId(), fiche.getId())));
        mvc.perform(put(BASE + "/" + g1).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usersCount").value(2))
                .andExpect(jsonPath("$.pendingUserIds[0]").value(fiche.getId().toString()));

        // Retirer le membre en attente le retire de l'attente.
        corps = om.writeValueAsString(java.util.Map.of("code", parFiche.get("code").asText(),
                "name", parFiche.get("name").asText(), "userIds", List.of(admin.getEmployeId())));
        mvc.perform(put(BASE + "/" + g1).contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usersCount").value(1))
                .andExpect(jsonPath("$.pendingUserIds").isEmpty());
        assertThat(compter("SELECT count(*) FROM groupe_membre_attente WHERE groupe_ged_id = ?", g1)).isZero();
    }
}
