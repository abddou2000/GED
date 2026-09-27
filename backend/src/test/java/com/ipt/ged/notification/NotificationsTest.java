package com.ipt.ged.notification;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.support.Comptes;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notifications (DAT §12.9) de bout en bout : écriture dans la transaction du
 * déclencheur, expédition asynchrone vers un SMTP simulé (GreenMail) avec trois
 * tentatives, centre de notifications, préférence e-mail, déclencheurs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class NotificationsTest {

    /** Port de test 3025, celui de application-test.yml ; boîtes vidées à chaque test. */
    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired private Notifications notifications;
    @Autowired private ExpediteurCourriels expediteur;
    @Autowired private ApplicationEventPublisher evenements;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private AnnuaireDestinataires annuaire;

    /** Événement de test ayant la forme de HabilitationModifiee (lot autorisation, dev1). */
    record Habilitation(String objetType, String motif, UUID objetId, Map<String, Object> avant,
                        Map<String, Object> apres, UUID acteurUtilisateurId) implements EvenementAudit {
        @Override public String action() { return "HABILITATION_MODIFIEE"; }
    }

    /** Événement de test d'un lot déclencheur (workflow) qui implémente le contrat. */
    record DecisionRendue(UUID documentId, String decision, String motif, UUID initiateur)
            implements EvenementNotifiable {
        @Override
        public DemandeNotification notification() {
            Map<String, Object> v = new HashMap<>();
            v.put("document", "Marché 2026-014");
            v.put("decision", decision);
            v.put("motif", motif);
            return DemandeNotification.a(TypeNotification.CIRCUIT_DECISION, List.of(initiateur), "DOCUMENT",
                    documentId, v, "televerser/" + documentId);
        }
    }

    private UUID moi() {
        return jdbc.queryForObject("SELECT employe_id FROM compte_utilisateur WHERE email = ?", UUID.class,
                Comptes.ADMIN);
    }

    private <T> T dansTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(s -> action.get());
    }

    private void publier(Object evenement) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> evenements.publishEvent(evenement));
    }

    private UUID ouvrirCircuit(UUID destinataire, UUID documentId) {
        return dansTransaction(() -> notifications.envoyer(DemandeNotification.a(TypeNotification.CIRCUIT_OUVERT,
                List.of(destinataire), "DOCUMENT", documentId,
                Map.of("document", "Contrat 42", "auteur", "Karim Alaoui"), "televerser/" + documentId))).get(0);
    }

    private Map<String, Object> ligne(UUID id) {
        return jdbc.queryForMap("SELECT * FROM notification WHERE id = ?", id);
    }

    private List<Map<String, Object>> lignesPourObjet(UUID objetId) {
        return jdbc.queryForList("SELECT * FROM notification WHERE objet_id = ?", objetId);
    }

    /** Messages reçus par le SMTP simulé qui concernent ce document (d'autres tests en laissent en attente). */
    private List<MimeMessage> messagesPour(UUID document) {
        return Arrays.stream(smtp.getReceivedMessages()).filter(m -> {
            try {
                return m.getContent().toString().contains(document.toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** La boîte peut contenir des notifications d'autres tests : on relève jusqu'à traiter la nôtre. */
    private String expedierJusqua(UUID id) {
        for (int i = 0; i < 20; i++) {
            expediteur.expedier();
            String etat = (String) ligne(id).get("courriel_etat");
            if (!"A_ENVOYER".equals(etat) || ((Timestamp) ligne(id).get("courriel_prochain_essai"))
                    .toInstant().isAfter(Instant.now())) {
                return etat;
            }
        }
        return (String) ligne(id).get("courriel_etat");
    }

    @Test
    @DisplayName("Envoi : ligne écrite dans la transaction, e-mail expédié en français avec lien, envoi audité")
    void envoiEtExpedition() throws Exception {
        UUID document = UUID.randomUUID();
        UUID id = ouvrirCircuit(moi(), document);
        assertThat(ligne(id)).containsEntry("courriel_etat", "A_ENVOYER").containsEntry("courriel_tentatives", 0);

        assertThat(expedierJusqua(id)).isEqualTo("ENVOYE");
        List<MimeMessage> recus = messagesPour(document);
        assertThat(recus).hasSize(1);
        MimeMessage recu = recus.get(0);
        assertThat(recu.getAllRecipients()[0].toString()).isEqualTo(Comptes.ADMIN);
        assertThat(recu.getSubject()).isEqualTo("Validation demandée : Contrat 42");
        assertThat(recu.getContent().toString())
                .contains("« Contrat 42 », circuit ouvert par Karim Alaoui")
                .contains("Ouvrir dans la GED : http://localhost:4200/#/televerser/" + document)
                .contains("désactiver ces e-mails");
        assertThat(ligne(id)).containsEntry("courriel_tentatives", 1);
        assertThat(ligne(id).get("courriel_envoye_le")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'NOTIFICATION_ENVOYEE' "
                + "AND objet_id = ? AND resultat = 'SUCCES' AND acteur_nom = 'ged:notifications'", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT apres::text FROM journal_audit WHERE objet_id = ?", String.class, id))
                .doesNotContain(Comptes.ADMIN);

        // Une seconde relève ne renvoie rien : l'état n'est plus A_ENVOYER.
        expediteur.expedier();
        assertThat(messagesPour(document)).hasSize(1);
    }

    @Test
    @DisplayName("Boîte d'envoi : opération annulée = aucune notification")
    void transactionAnnulee() {
        UUID document = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            notifications.envoyer(DemandeNotification.a(TypeNotification.CIRCUIT_ANNULE, List.of(moi()),
                    "DOCUMENT", document, Map.of("document", "X"), null));
            s.setRollbackOnly();
        });
        assertThat(lignesPourObjet(document)).isEmpty();
    }

    @Test
    @DisplayName("Reprises : trois tentatives espacées, puis ECHEC audité ; la notification in-app subsiste")
    void reprisesPuisEchec() {
        UUID id = ouvrirCircuit(moi(), UUID.randomUUID());
        smtp.stop();
        try {
            assertThat(expedierJusqua(id)).isEqualTo("A_ENVOYER");
            Map<String, Object> apres1 = ligne(id);
            assertThat(apres1).containsEntry("courriel_tentatives", 1);
            Instant prochain = ((Timestamp) apres1.get("courriel_prochain_essai")).toInstant();
            assertThat(Duration.between(Instant.now(), prochain)).isBetween(Duration.ofSeconds(45), Duration.ofSeconds(61));
            assertThat((String) apres1.get("courriel_erreur")).isNotBlank();

            jdbc.update("UPDATE notification SET courriel_prochain_essai = now() - interval '1 second' WHERE id = ?", id);
            assertThat(expedierJusqua(id)).isEqualTo("A_ENVOYER");
            Instant prochain2 = ((Timestamp) ligne(id).get("courriel_prochain_essai")).toInstant();
            assertThat(Duration.between(Instant.now(), prochain2)).isBetween(Duration.ofSeconds(105), Duration.ofSeconds(121));

            jdbc.update("UPDATE notification SET courriel_prochain_essai = now() - interval '1 second' WHERE id = ?", id);
            assertThat(expedierJusqua(id)).isEqualTo("ECHEC");
            assertThat(ligne(id)).containsEntry("courriel_tentatives", 3);
            assertThat(ligne(id).get("courriel_prochain_essai")).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'NOTIFICATION_ECHEC' "
                    + "AND objet_id = ? AND resultat = 'ECHEC' AND motif = 'TENTATIVES_EPUISEES'", Integer.class, id))
                    .isEqualTo(1);
            assertThat(ligne(id).get("lue_le")).isNull();
        } finally {
            smtp.start();
        }
    }

    @Test
    @DisplayName("Préférence : e-mail désactivé → notification in-app seule ; modification auditée")
    void preferenceCourriel() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences").contentType(APPLICATION_JSON)
                        .content("{\"courrielActif\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courrielActif").value(false))
                .andExpect(jsonPath("$.inAppActif").value(true));
        try {
            mvc.perform(get("/api/v1/notifications/preferences")).andExpect(jsonPath("$.courrielActif").value(false));
            UUID id = ouvrirCircuit(moi(), UUID.randomUUID());
            assertThat(ligne(id)).containsEntry("courriel_etat", "DESACTIVE");
            expediteur.expedier();
            assertThat(ligne(id)).containsEntry("courriel_etat", "DESACTIVE");
            assertThat(messagesPour((UUID) ligne(id).get("objet_id"))).isEmpty();
            mvc.perform(get("/api/v1/notifications").param("nonLues", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.id == '" + id + "')].courriel").value("DESACTIVE"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = "
                    + "'PREFERENCE_NOTIFICATION_MODIFIEE' AND objet_id = ?", Integer.class, moi())).isPositive();
        } finally {
            mvc.perform(put("/api/v1/notifications/preferences").contentType(APPLICATION_JSON)
                    .content("{\"courrielActif\":true}")).andExpect(status().isOk());
        }
        mvc.perform(put("/api/v1/notifications/preferences").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Centre : liste et pastille personnelles, marquage lu, notification d'autrui introuvable")
    void centreDeNotifications() throws Exception {
        UUID a = ouvrirCircuit(moi(), UUID.randomUUID());
        UUID b = ouvrirCircuit(moi(), UUID.randomUUID());
        UUID autrui = ouvrirCircuit(UUID.randomUUID(), UUID.randomUUID());
        long avant = jdbc.queryForObject("SELECT count(*) FROM notification WHERE destinataire_id = ? "
                + "AND lue_le IS NULL", Long.class, moi());

        mvc.perform(get("/api/v1/notifications/compteur"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nonLues").value(avant));
        String liste = mvc.perform(get("/api/v1/notifications").param("nonLues", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].famille").value("CIRCUIT_VALIDATION"))
                .andReturn().getResponse().getContentAsString();
        assertThat(liste).contains(a.toString(), b.toString()).doesNotContain(autrui.toString());

        mvc.perform(post("/api/v1/notifications/" + autrui + "/lecture"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESSOURCE_INTROUVABLE"));
        assertThat(ligne(autrui).get("lue_le")).isNull();

        mvc.perform(post("/api/v1/notifications/" + a + "/lecture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lue").value(true));
        mvc.perform(get("/api/v1/notifications/compteur")).andExpect(jsonPath("$.nonLues").value(avant - 1));

        mvc.perform(post("/api/v1/notifications/lecture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marquees").value(avant - 1));
        mvc.perform(get("/api/v1/notifications/compteur")).andExpect(jsonPath("$.nonLues").value(0));
        assertThat(ligne(autrui).get("lue_le")).isNull();
    }

    @Test
    @DisplayName("Accès à un espace : l'attribution notifie, jamais le retrait, ni un document, ni l'auteur")
    void attributionAccesEspace() {
        UUID moi = moi();
        UUID espace = UUID.randomUUID();
        Map<String, Object> h = new HashMap<>();
        h.put("sujetType", "UTILISATEUR");
        h.put("sujetId", moi);
        h.put("role", "LECTEUR");
        h.put("noeudId", espace);
        h.put("noeud", "Marchés 2026");
        publier(new Habilitation("HABILITATION", "AJOUT", UUID.randomUUID(), null, h, UUID.randomUUID()));
        List<Map<String, Object>> l = lignesPourObjet(espace);
        assertThat(l).hasSize(1);
        assertThat(l.get(0)).containsEntry("type", "ACCES_ESPACE_ATTRIBUE").containsEntry("destinataire_id", moi)
                .containsEntry("titre", "Accès attribué : Marchés 2026")
                .containsEntry("lien", "espaces-de-travail/" + espace);

        UUID autreEspace = UUID.randomUUID();
        Map<String, Object> h2 = new HashMap<>(h);
        h2.put("noeudId", autreEspace);
        publier(new Habilitation("HABILITATION", "RETRAIT", UUID.randomUUID(), h2, null, UUID.randomUUID()));
        publier(new Habilitation("HABILITATION", "AJOUT", UUID.randomUUID(), null, h2, moi));        // auteur
        Map<String, Object> rupture = new HashMap<>(h2);
        rupture.remove("role");
        publier(new Habilitation("HABILITATION", "AJOUT", UUID.randomUUID(), null, rupture, UUID.randomUUID()));
        Map<String, Object> document = new HashMap<>(h2);
        document.put("documentId", UUID.randomUUID());
        publier(new Habilitation("HABILITATION", "AJOUT", UUID.randomUUID(), null, document, UUID.randomUUID()));
        assertThat(lignesPourObjet(autreEspace)).isEmpty();
    }

    @Test
    @DisplayName("Accès par groupe : membres du groupe notifiés, puis membres ajoutés pour chaque espace du groupe")
    void attributionParGroupe() {
        // Le jeu de test n'a ni groupe ni espace : l'annuaire (point d'extension)
        // est simulé pour ce groupe seulement ; ses requêtes réelles restent valides.
        assertThat(annuaire.membresDuGroupe(UUID.randomUUID())).isEmpty();
        assertThat(annuaire.espacesDuGroupe(UUID.randomUUID())).isEmpty();
        UUID groupe = UUID.randomUUID();
        UUID collegue = UUID.randomUUID();
        List<UUID> membres = List.of(moi(), collegue);
        UUID espaceA = UUID.randomUUID();
        UUID espaceB = UUID.randomUUID();
        Map<UUID, String> espaces = new java.util.LinkedHashMap<>();
        espaces.put(espaceA, "Direction financière");
        espaces.put(espaceB, "Marchés publics");
        doReturn(new java.util.LinkedHashSet<>(membres)).when(annuaire).membresDuGroupe(groupe);
        doReturn(espaces).when(annuaire).espacesDuGroupe(groupe);

        UUID nouvelEspace = UUID.randomUUID();
        Map<String, Object> h = new HashMap<>();
        h.put("sujetType", "GROUPE");
        h.put("sujetId", groupe);
        h.put("sujet", "Comptables");
        h.put("role", "UTILISATEUR_STANDARD");
        h.put("noeudId", nouvelEspace);
        h.put("noeud", "Archives 2019");
        publier(new Habilitation("HABILITATION", "AJOUT", UUID.randomUUID(), null, h, null));
        assertThat(lignesPourObjet(nouvelEspace)).extracting(r -> r.get("destinataire_id"))
                .containsExactlyInAnyOrderElementsOf(membres);
        assertThat((String) lignesPourObjet(nouvelEspace).get(0).get("message"))
                .isEqualTo("Un accès à l'espace « Archives 2019 » vous a été attribué par votre ajout au groupe « Comptables ».");

        // Ajout du collègue au groupe : un accès par espace du groupe, pour lui seul.
        Map<String, Object> av = Map.of("nom", "Comptables", "membres", List.of(moi().toString()));
        Map<String, Object> ap = Map.of("nom", "Comptables", "membres",
                membres.stream().map(UUID::toString).collect(Collectors.toList()));
        publier(new Habilitation("GROUPE_GED", "MODIFICATION", groupe, av, ap, null));
        assertThat(lignesPourObjet(espaceA)).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("destinataire_id", collegue);
            assertThat(r).containsEntry("titre", "Accès attribué : Direction financière");
        });
        assertThat(lignesPourObjet(espaceB)).hasSize(1);

        // Membre retiré seulement : rien.
        publier(new Habilitation("GROUPE_GED", "MODIFICATION", groupe, ap, av, null));
        assertThat(lignesPourObjet(espaceA)).hasSize(1);
    }

    @Test
    @DisplayName("Contrat EvenementNotifiable : décision rendue, libellé et motif facultatif")
    void evenementNotifiable() {
        UUID refuse = UUID.randomUUID();
        UUID valide = UUID.randomUUID();
        publier(new DecisionRendue(refuse, "REFUSE", "pièces manquantes", moi()));
        publier(new DecisionRendue(valide, "VALIDE", null, moi()));
        Map<String, Object> r = lignesPourObjet(refuse).get(0);
        assertThat(r).containsEntry("titre", "Décision rendue sur « Marché 2026-014 » : refusé");
        assertThat((String) r.get("message")).isEqualTo(
                "Décision rendue sur le document « Marché 2026-014 » : refusé. Motif : pièces manquantes.");
        assertThat((String) lignesPourObjet(valide).get(0).get("message"))
                .isEqualTo("Décision rendue sur le document « Marché 2026-014 » : validé.");
    }

    @Test
    @DisplayName("Trois familles exclusivement : catalogue fermé, contrainte en base")
    void troisFamilles() {
        assertThat(Arrays.stream(TypeNotification.values()).map(TypeNotification::famille).distinct())
                .containsExactlyInAnyOrder(TypeNotification.Famille.values());
        assertThat(TypeNotification.Famille.values()).hasSize(3);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification (id, type, destinataire_id, titre, message, cree_le, courriel_etat)
                VALUES (?, 'DOCUMENT_DEPOSE', ?, 't', 'm', now(), 'A_ENVOYER')""", UUID.randomUUID(), moi()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Sans destinataire (rôle non résolu) : rien n'est écrit")
    void sansDestinataire() {
        UUID document = UUID.randomUUID();
        List<UUID> ids = dansTransaction(() -> notifications.envoyer(DemandeNotification.auRole(
                TypeNotification.ECHEANCE_CONSERVATION, "AGENT_ARCHIVE", "DOCUMENT", document,
                Map.of("document", "PV 2001"), null)));
        assertThat(ids).isEmpty();
        assertThat(lignesPourObjet(document)).isEmpty();
    }
}
