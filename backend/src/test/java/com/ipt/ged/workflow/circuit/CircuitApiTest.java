package com.ipt.ged.workflow.circuit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.Role;
import com.ipt.ged.notification.TypeNotification;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.workflow.api.ActeurWorkflow;
import com.ipt.ged.workflow.circuit.dto.VuesWorkflow;
import com.ipt.ged.workflow.circuit.evenement.EvenementWorkflow;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Lot E8 — workflow de validation (dossier technique §12.8, D7, D1/QR1, D8) :
 * règle rattachée au nœud ou au type (la plus spécifique l'emporte), validateurs
 * nommés ou par rôle, circuit figé au dépôt, décisions parallèles sans ordre
 * sur la version courante, caducité au versement, annulation, réaffectation
 * manuelle tracée, anomalies, diffusion, événements d'audit et de notification.
 *
 * <p>Acteurs : Sara (Administrateur, déposante), Karim et Yasmine (Utilisateur
 * standard global : Valider partout), Nadia ({@link Comptes#SANS_ROLE}) qui ne
 * reçoit que les rôles posés par le test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@WithUserDetails(Comptes.ADMIN)
class CircuitApiTest {

    private static final String WF = "/api/v1/workflow";
    private static final String KARIM = Comptes.SECOND_ACTEUR;
    private static final String YASMINE = Comptes.TROISIEME_ACTEUR;
    private static final String NADIA = Comptes.SANS_ROLE;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEvents evenements;
    @Autowired private EmployeRepository employes;
    @Autowired private ServiceCircuits service;
    @Autowired private DecisionRepository decisions;

    private UUID espace, dossier, type;

    @BeforeEach
    void plan() {
        espace = jeu.noeud("Espace E8", null);
        dossier = jeu.noeud("Dossier E8", espace);
        type = jeu.type(dossier, Confidentialite.PUBLIC);
    }

    /* ---------------------------------------------------------------- outils */

    private RequestPostProcessor comme(String identifiant) {
        return user(identites.loadUserByUsername(identifiant));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Règle créée par l'API ; chaque validateur : {"employeId":…} ou {"roleId":…,"perimetreNoeudId":…}. */
    private UUID regle(String nom, String... validateurs) throws Exception {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < validateurs.length; i++) {
            if (i > 0) steps.append(',');
            steps.append(validateurs[i].replace("}", ",\"label\":\"V" + (i + 1) + "\",\"stepOrder\":" + (i + 1) + "}"));
        }
        return UUID.fromString(json(mvc.perform(post(WF + "/regles").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"" + nom + "\",\"steps\":[" + steps + "]}"))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private String nomme(String identifiant) {
        return "{\"employeId\":\"" + jeu.employeId(identifiant) + "\"}";
    }

    private void rattacherNoeud(UUID noeud, UUID regle) throws Exception {
        mvc.perform(put(WF + "/noeuds/" + noeud + "/regle").contentType(APPLICATION_JSON)
                .content("{\"regleId\":" + (regle == null ? "null" : "\"" + regle + "\"") + "}"))
                .andExpect(status().isNoContent());
    }

    private UUID deposer(String nom) throws Exception {
        return UUID.fromString(json(mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", nom + ".pdf", "application/pdf",
                                com.ipt.ged.support.Pdfs.pdf(nom + UUID.randomUUID())))
                        .param("name", nom).param("typeDocumentId", type.toString()))
                .andExpect(status().is2xxSuccessful())).get("id").asText());
    }

    private void verser(UUID doc) throws Exception {
        mvc.perform(multipart("/api/v1/documents/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "v2.pdf", "application/pdf",
                                com.ipt.ged.support.Pdfs.pdf("v2 " + UUID.randomUUID()))))
                .andExpect(status().is2xxSuccessful());
    }

    private JsonNode circuitDe(UUID doc) throws Exception {
        return json(mvc.perform(get(WF + "/documents/" + doc + "/circuits")).andExpect(status().isOk())).get(0);
    }

    private ResultActions decider(String qui, UUID circuit, String decision, String motif) throws Exception {
        String corps = "{\"decision\":\"" + decision + "\"" + (motif == null ? "" : ",\"motif\":\"" + motif + "\"") + "}";
        return mvc.perform(post(WF + "/circuits/" + circuit + "/decisions").with(comme(qui))
                .contentType(APPLICATION_JSON).content(corps));
    }

    private boolean actif(UUID doc) throws Exception {
        return json(mvc.perform(get("/api/v1/documents/" + doc)).andExpect(status().isOk())).get("active").asBoolean();
    }

    private UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asText());
    }

    /* ---------------------------------------------------------------- D7 : parallèle, sans ordre */

    @Test
    @DisplayName("Deux validateurs nommés, décisions dans n'importe quel ordre : VALIDE quand tous ont validé")
    void validationParallele() throws Exception {
        rattacherNoeud(espace, regle("Double", nomme(KARIM), nomme(YASMINE)));
        UUID doc = deposer("Facture parallèle");
        JsonNode c = circuitDe(doc);
        assertEquals("EN_COURS", c.get("statut").asText());
        assertEquals(2, c.get("validateurs").size());
        assertFalse(actif(doc), "un document en circuit n'est pas utilisable");

        // Le second validateur décide le premier : aucun ordre imposé.
        JsonNode apres = json(decider(YASMINE, id(c), "VALIDE", null).andExpect(status().isCreated()));
        assertEquals("EN_COURS", apres.get("statut").asText());
        apres = json(decider(KARIM, id(c), "VALIDE", null).andExpect(status().isCreated()));
        assertEquals("VALIDE", apres.get("statut").asText());
        assertNotNull(apres.get("closLe").asText(null));
        assertTrue(actif(doc));

        // Circuit clos : plus aucune décision.
        decider(KARIM, id(c), "REFUSE", "trop tard").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CIRCUIT_CLOS"));
    }

    @Test
    @DisplayName("Refus : motif obligatoire ; un refus suffit (REFUSE) ; le validateur peut retirer sa décision")
    void refusEtAnnulationDeDecision() throws Exception {
        rattacherNoeud(espace, regle("Refus", nomme(KARIM), nomme(YASMINE)));
        UUID doc = deposer("Facture refusée");
        UUID c = id(circuitDe(doc));

        decider(KARIM, c, "REFUSE", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOTIF_OBLIGATOIRE"));
        decider(KARIM, c, "ANNULEE", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DECISION_INCOHERENTE"));
        decider(YASMINE, c, "VALIDE", null).andExpect(status().isCreated());
        JsonNode r = json(decider(KARIM, c, "REFUSE", "Montant erroné").andExpect(status().isCreated()));
        assertEquals("REFUSE", r.get("statut").asText());
        assertFalse(actif(doc));

        // Karim retire son refus puis valide : le circuit aboutit.
        assertEquals("EN_COURS", json(decider(KARIM, c, "ANNULEE", null).andExpect(status().isCreated()))
                .get("statut").asText());
        assertEquals("VALIDE", json(decider(KARIM, c, "VALIDE", null).andExpect(status().isCreated()))
                .get("statut").asText());
        // Historique complet conservé, jamais réécrit.
        assertEquals(4, json(mvc.perform(get(WF + "/circuits/" + c))).get("decisions").size());
    }

    @Test
    @DisplayName("Versement : les décisions antérieures deviennent caduques, le circuit repart EN_COURS")
    void versementRendCaduc() throws Exception {
        rattacherNoeud(espace, regle("Caducité", nomme(KARIM)));
        UUID doc = deposer("Contrat versionné");
        UUID c = id(circuitDe(doc));
        decider(KARIM, c, "VALIDE", null).andExpect(status().isCreated());
        assertTrue(actif(doc));

        verser(doc);
        JsonNode apres = json(mvc.perform(get(WF + "/circuits/" + c)));
        assertEquals("EN_COURS", apres.get("statut").asText());
        assertEquals(2, apres.get("versionCouranteNumero").asInt());
        assertTrue(apres.get("decisions").get(0).get("caduque").asBoolean());
        assertEquals("EN_ATTENTE", apres.get("validateurs").get(0).get("etat").asText());
        assertFalse(actif(doc));
        // La liste « à traiter » de Karim le lui redemande.
        assertEquals(1, json(mvc.perform(get(WF + "/a-traiter").with(comme(KARIM)))).get("total").asInt());

        decider(KARIM, c, "VALIDE", null).andExpect(status().isCreated());
        assertTrue(actif(doc));
    }

    @Test
    @DisplayName("« À traiter » paginé comme les autres listes : 50 par défaut, 200 au plus, alias taille (T-050)")
    void aTraiterPagination() throws Exception {
        rattacherNoeud(espace, regle("Pagination", nomme(KARIM)));
        circuitDe(deposer("Pièce paginée"));

        JsonNode defaut = json(mvc.perform(get(WF + "/a-traiter").with(comme(KARIM))));
        assertEquals(1, defaut.get("total").asInt());
        assertEquals(50, defaut.get("size").asInt());
        // Avant : plafond 100 ; désormais 200, comme Tri pour toutes les listes.
        assertEquals(150, json(mvc.perform(get(WF + "/a-traiter").param("size", "150").with(comme(KARIM))))
                .get("size").asInt());
        assertEquals(200, json(mvc.perform(get(WF + "/a-traiter").param("size", "5000").with(comme(KARIM))))
                .get("size").asInt());
        assertEquals(120, json(mvc.perform(get(WF + "/a-traiter").param("taille", "120").with(comme(KARIM))))
                .get("size").asInt());
        assertEquals(50, json(mvc.perform(get(WF + "/a-traiter").param("size", "0").with(comme(KARIM))))
                .get("size").asInt());
        // Page au-delà de la fin : vide, sans erreur ; page négative : 400.
        JsonNode loin = json(mvc.perform(get(WF + "/a-traiter").param("page", "3").with(comme(KARIM))));
        assertEquals(0, loin.get("content").size());
        assertEquals(1, loin.get("total").asInt());
        mvc.perform(get(WF + "/a-traiter").param("page", "-1").with(comme(KARIM)))
                .andExpect(status().isBadRequest());
    }

    /* ---------------------------------------------------------------- règle : nœud, type, figée */

    @Test
    @DisplayName("Règle la plus spécifique : type > dossier > espace ; modifier la règle ne touche pas le circuit figé")
    void regleApplicableEtFigee() throws Exception {
        UUID regleEspace = regle("Espace", nomme(KARIM));
        UUID regleType = regle("Type", nomme(YASMINE));
        rattacherNoeud(espace, regleEspace);
        UUID d1 = deposer("Sans règle de type");
        assertEquals("NOEUD", json(mvc.perform(get(WF + "/documents/" + d1 + "/regle"))).get("origine").asText());
        assertEquals(jeu.employeId(KARIM).toString(),
                circuitDe(d1).get("validateurs").get(0).get("employeId").asText(), "règle héritée de l'espace");

        mvc.perform(put(WF + "/types/" + type + "/regle").contentType(APPLICATION_JSON)
                .content("{\"regleId\":\"" + regleType + "\"}")).andExpect(status().isNoContent());
        UUID d2 = deposer("Avec règle de type");
        JsonNode r = json(mvc.perform(get(WF + "/documents/" + d2 + "/regle")));
        assertEquals("TYPE", r.get("origine").asText());
        assertEquals(jeu.employeId(YASMINE).toString(), circuitDe(d2).get("validateurs").get(0).get("employeId").asText());

        // La règle d'espace change : le circuit de d1, copie figée, ne bouge pas.
        mvc.perform(put(WF + "/regles/" + regleEspace).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Espace v2\",\"steps\":[" + nomme(YASMINE).replace("}", ",\"label\":\"N\"}") + "]}"))
                .andExpect(status().isOk());
        assertEquals(jeu.employeId(KARIM).toString(), circuitDe(d1).get("validateurs").get(0).get("employeId").asText());

        // Rattacher une règle exige la gestion des référentiels.
        mvc.perform(put(WF + "/noeuds/" + dossier + "/regle").with(comme(KARIM)).contentType(APPLICATION_JSON)
                .content("{\"regleId\":\"" + regleType + "\"}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Sans règle (ou règle sans validateur), le document est utilisable d'emblée, sans circuit")
    void sansRegle() throws Exception {
        UUID doc = deposer("Libre");
        assertTrue(actif(doc));
        assertEquals(0, json(mvc.perform(get(WF + "/documents/" + doc + "/circuits"))).size());
        mvc.perform(get(WF + "/documents/" + doc + "/regle")).andExpect(status().isNoContent());
        mvc.perform(post(WF + "/documents/" + doc + "/circuits")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCUNE_REGLE"));
    }

    /* ---------------------------------------------------------------- validateur par rôle */

    @Test
    @DisplayName("Validateur par rôle : résolu au moment de la décision, sur le périmètre (héritage compris)")
    void validateurParRole() throws Exception {
        UUID autre = jeu.noeud("Ailleurs", null);
        rattacherNoeud(espace, regle("Par rôle", "{\"roleId\":\"" + jeu.idRole(Role.UTILISATEUR_STANDARD)
                + "\",\"perimetreNoeudId\":\"" + dossier + "\"}"));
        UUID doc = deposer("Note par rôle");
        JsonNode c = circuitDe(doc);
        assertEquals("ROLE", c.get("validateurs").get(0).get("type").asText());

        // Nadia détient le rôle ailleurs seulement : elle ne voit même pas le document.
        jeu.habiliter(NADIA, Role.UTILISATEUR_STANDARD, autre, null);
        decider(NADIA, id(c), "VALIDE", null).andExpect(status().isNotFound());

        // Rôle posé sur l'espace, ancêtre du périmètre : il vaut pour le dossier.
        jeu.habiliter(NADIA, Role.UTILISATEUR_STANDARD, espace, null);
        assertEquals(1, json(mvc.perform(get(WF + "/a-traiter").with(comme(NADIA)))).get("total").asInt());
        JsonNode r = json(decider(NADIA, id(c), "VALIDE", null).andExpect(status().isCreated()));
        assertEquals("VALIDE", r.get("statut").asText());
    }

    /* ---------------------------------------------------------------- annulation */

    @Test
    @DisplayName("Annulation par l'initiateur ou l'Administrateur, motif obligatoire ; décisions gardées ; nouveau circuit possible")
    void annulationEtNouveauCircuit() throws Exception {
        rattacherNoeud(espace, regle("Annulable", nomme(KARIM), nomme(YASMINE)));
        UUID doc = deposer("Pièce annulée");
        UUID c = id(circuitDe(doc));
        decider(KARIM, c, "VALIDE", null).andExpect(status().isCreated());

        mvc.perform(post(WF + "/circuits/" + c + "/annulation").with(comme(YASMINE)).contentType(APPLICATION_JSON)
                .content("{\"motif\":\"je ne suis pas l'initiatrice\"}")).andExpect(status().isForbidden());
        mvc.perform(post(WF + "/circuits/" + c + "/annulation").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MOTIF_OBLIGATOIRE"));
        JsonNode r = json(mvc.perform(post(WF + "/circuits/" + c + "/annulation").contentType(APPLICATION_JSON)
                .content("{\"motif\":\"Pièce remplacée\"}")).andExpect(status().isOk()));
        assertEquals("ANNULE", r.get("statut").asText());
        assertEquals("Pièce remplacée", r.get("motifAnnulation").asText());
        assertEquals(1, r.get("decisions").size(), "les décisions sont conservées");
        assertFalse(actif(doc));
        decider(YASMINE, c, "VALIDE", null).andExpect(status().isConflict());

        // Diffuser un document dont le seul circuit est annulé : refusé.
        mvc.perform(post(WF + "/documents/" + doc + "/diffusion").contentType(APPLICATION_JSON)
                        .content("{\"utilisateurIds\":[\"" + jeu.utilisateurId(NADIA) + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_NON_VALIDE"));

        JsonNode nouveau = json(mvc.perform(post(WF + "/documents/" + doc + "/circuits")).andExpect(status().isCreated()));
        assertEquals("EN_COURS", nouveau.get("statut").asText());
        assertEquals(0, nouveau.get("decisions").size(), "nouveau circuit, nouvelles décisions");
        mvc.perform(post(WF + "/documents/" + doc + "/circuits")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CIRCUIT_DEJA_OUVERT"));
        assertEquals(2, json(mvc.perform(get(WF + "/documents/" + doc + "/circuits"))).size());
    }

    /* ---------------------------------------------------------------- D1 / QR1 : réaffectation, anomalies */

    @Test
    @DisplayName("Anomalie signalée à l'Administrateur puis réaffectation manuelle tracée d'un validateur en attente")
    void anomalieEtReaffectation() throws Exception {
        Employe parti = employes.save(new Employe("Omar", "Parti-" + UUID.randomUUID().toString().substring(0, 4), false));
        rattacherNoeud(espace, regle("Défaillant", "{\"employeId\":\"" + parti.getId() + "\"}", nomme(KARIM)));
        UUID doc = deposer("Pièce bloquée");
        JsonNode c = circuitDe(doc);
        UUID circuit = id(c);
        UUID bloque = null, karim = null;
        for (JsonNode v : c.get("validateurs")) {
            if (parti.getId().toString().equals(v.get("employeId").asText())) bloque = id(v);
            else karim = id(v);
        }

        JsonNode anomalies = json(mvc.perform(get(WF + "/anomalies")).andExpect(status().isOk()));
        boolean trouvee = false;
        for (JsonNode a : anomalies) {
            if (a.get("validateurId").asText().equals(bloque.toString())) {
                assertEquals("SANS_IDENTITE", a.get("anomalie").asText());
                trouvee = true;
            }
        }
        assertTrue(trouvee, "le validateur sans identité est signalé");
        mvc.perform(get(WF + "/anomalies").with(comme(KARIM))).andExpect(status().isForbidden());

        decider(KARIM, circuit, "VALIDE", null).andExpect(status().isCreated());
        String corps = "{\"employeId\":\"" + jeu.employeId(YASMINE) + "\",\"motif\":\"Départ de l'agent\"}";
        mvc.perform(put(WF + "/circuits/" + circuit + "/validateurs/" + bloque).with(comme(KARIM))
                .contentType(APPLICATION_JSON).content(corps)).andExpect(status().isForbidden());
        mvc.perform(put(WF + "/circuits/" + circuit + "/validateurs/" + karim).contentType(APPLICATION_JSON)
                .content(corps)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALIDATEUR_DEJA_DECIDE"));
        JsonNode r = json(mvc.perform(put(WF + "/circuits/" + circuit + "/validateurs/" + bloque)
                .contentType(APPLICATION_JSON).content(corps)).andExpect(status().isOk()));
        JsonNode v = null;
        for (JsonNode x : r.get("validateurs")) if (x.get("id").asText().equals(bloque.toString())) v = x;
        assertEquals(jeu.employeId(YASMINE).toString(), v.get("employeId").asText());
        assertEquals(parti.getFullName(), v.get("reaffecteDe").asText());
        assertEquals("Départ de l'agent", v.get("motifReaffectation").asText());
        assertNotNull(v.get("reaffectePar").asText(null));

        assertEquals("VALIDE", json(decider(YASMINE, circuit, "VALIDE", null).andExpect(status().isCreated()))
                .get("statut").asText());
        assertTrue(evenements.stream(EvenementWorkflow.class)
                .anyMatch(e -> e.action().equals(EvenementWorkflow.VALIDATEUR_REAFFECTE)
                        && "Départ de l'agent".equals(e.motif())));
    }

    /* ---------------------------------------------------------------- diffusion */

    @Test
    @DisplayName("Diffusion d'un document validé : lecture accordée sur le document, sans copie, idempotente")
    void diffusion() throws Exception {
        rattacherNoeud(espace, regle("Diffusion", nomme(KARIM)));
        UUID doc = deposer("Note à diffuser");
        String corps = "{\"utilisateurIds\":[\"" + jeu.utilisateurId(NADIA) + "\"]}";
        mvc.perform(post(WF + "/documents/" + doc + "/diffusion").contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_NON_VALIDE"));
        decider(KARIM, id(circuitDe(doc)), "VALIDE", null).andExpect(status().isCreated());

        // Sans aucun rôle, Nadia n'a accès à rien (refus 403 d'ensemble).
        mvc.perform(get("/api/v1/documents/" + doc).with(comme(NADIA))).andExpect(status().is4xxClientError());
        mvc.perform(post(WF + "/documents/" + doc + "/diffusion").contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk()).andExpect(jsonPath("$.habilitationsPosees").value(1));
        mvc.perform(get("/api/v1/documents/" + doc).with(comme(NADIA))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/documents/" + doc).with(comme(NADIA)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post(WF + "/documents/" + doc + "/diffusion").contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isOk()).andExpect(jsonPath("$.habilitationsPosees").value(0));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM habilitation WHERE document_id = ? AND role_id = ?",
                Integer.class, doc, jeu.idRole("LECTEUR")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document WHERE name = 'Note à diffuser'",
                Integer.class), "aucune copie");
        // Sans la permission Diffuser : 403.
        mvc.perform(post(WF + "/documents/" + doc + "/diffusion").with(comme(NADIA)).contentType(APPLICATION_JSON)
                .content(corps)).andExpect(status().isForbidden());
    }

    /* ---------------------------------------------------------------- événements, D8 */

    @Test
    @DisplayName("Événements : audit et notification publiés une fois par action (ouverture, décision, annulation)")
    void evenementsAuditEtNotification() throws Exception {
        rattacherNoeud(espace, regle("Notifiée", nomme(KARIM), nomme(YASMINE)));
        UUID doc = deposer("Pièce notifiée");
        UUID c = id(circuitDe(doc));

        List<EvenementWorkflow> ouverts = evenements.stream(EvenementWorkflow.class)
                .filter(e -> e.action().equals(EvenementWorkflow.CIRCUIT_OUVERT)).toList();
        assertEquals(1, ouverts.size());
        assertEquals(TypeNotification.CIRCUIT_OUVERT, ouverts.get(0).notification().type());
        assertTrue(ouverts.get(0).notification().destinataires()
                .containsAll(List.of(jeu.utilisateurId(KARIM), jeu.utilisateurId(YASMINE))));
        assertEquals("DOCUMENT", ouverts.get(0).objetType());
        assertEquals(doc, ouverts.get(0).objetId());

        decider(KARIM, c, "REFUSE", "Incomplet").andExpect(status().isCreated());
        EvenementWorkflow refus = evenements.stream(EvenementWorkflow.class)
                .filter(e -> e.action().equals(EvenementWorkflow.VALIDATION_REJETEE)).findFirst().orElseThrow();
        assertEquals(TypeNotification.CIRCUIT_DECISION, refus.notification().type());
        assertEquals(List.of(jeu.utilisateurId(Comptes.ADMIN)), List.copyOf(refus.notification().destinataires()),
                "l'initiateur (déposante) est prévenu");
        assertEquals("Incomplet", refus.motif());
        assertEquals(jeu.utilisateurId(KARIM), refus.acteurUtilisateurId());

        mvc.perform(post(WF + "/circuits/" + c + "/annulation").contentType(APPLICATION_JSON)
                .content("{\"motif\":\"Abandon\"}")).andExpect(status().isOk());
        EvenementWorkflow annule = evenements.stream(EvenementWorkflow.class)
                .filter(e -> e.action().equals(EvenementWorkflow.CIRCUIT_ANNULE)).findFirst().orElseThrow();
        assertEquals(TypeNotification.CIRCUIT_ANNULE, annule.notification().type());
        assertTrue(annule.notification().destinataires().contains(jeu.utilisateurId(YASMINE)));
    }

    @Test
    @DisplayName("D8 : décision pour le compte d'un validateur par une application — double identité tracée")
    void decisionDelegueeParUneApplication() throws Exception {
        rattacherNoeud(espace, regle("Intranet", nomme(KARIM)));
        UUID doc = deposer("Pièce intranet");
        UUID c = id(circuitDe(doc));
        UUID application = UUID.randomUUID();
        ActeurWorkflow karimViaIntranet = new ActeurWorkflow(jeu.utilisateurId(KARIM), jeu.employeId(KARIM),
                application, "Karim El Fassi");

        // La personne déléguée doit elle-même être validatrice : Yasmine via l'intranet, non.
        ActeurWorkflow yasmine = new ActeurWorkflow(jeu.utilisateurId(YASMINE), jeu.employeId(YASMINE),
                application, "Yasmine Alaoui");
        ErreurWorkflowException ex = assertThrows(ErreurWorkflowException.class,
                () -> service.decider(c, new VuesWorkflow.DemandeDecision("VALIDE", null, null), yasmine));
        assertEquals(ErreurWorkflowException.PAS_VALIDATEUR, ex.code());

        assertEquals("VALIDE", service.decider(c, new VuesWorkflow.DemandeDecision("VALIDE", null, null),
                karimViaIntranet).statut());
        Decision d = decisions.duCircuit(c).get(0);
        assertEquals(jeu.utilisateurId(KARIM), d.getAuteurId());
        assertEquals(application, d.getApplicationId());
        EvenementWorkflow e = evenements.stream(EvenementWorkflow.class)
                .filter(x -> x.action().equals(EvenementWorkflow.VALIDATION_APPROUVEE)).findFirst().orElseThrow();
        assertEquals(application, e.acteurApplicationId());
        assertEquals(jeu.utilisateurId(KARIM), e.acteurUtilisateurId());
    }

    @Test
    @DisplayName("Tableau de bord : le compteur « à valider » suit la liste « à traiter »")
    void compteurTableauDeBord() throws Exception {
        long avant = json(mvc.perform(get("/api/v1/stats/overview").with(comme(KARIM)))).get("pendingSignatures").asLong();
        rattacherNoeud(espace, regle("Compteur", nomme(KARIM)));
        UUID doc = deposer("Pièce comptée");
        assertEquals(avant + 1, json(mvc.perform(get("/api/v1/stats/overview").with(comme(KARIM))))
                .get("pendingSignatures").asLong());
        decider(KARIM, id(circuitDe(doc)), "VALIDE", null).andExpect(status().isCreated());
        assertEquals(avant, json(mvc.perform(get("/api/v1/stats/overview").with(comme(KARIM))))
                .get("pendingSignatures").asLong());
        assertEquals(1, json(mvc.perform(get(WF + "/historique").with(comme(KARIM)))).size());
    }
    @Test
    @DisplayName("ANO-E8-002 : dépôt par une application sans délégation sous une règle : circuit ouvert, application tracée")
    void depotParApplicationSansDelegation() throws Exception {
        rattacherNoeud(espace, regle("Règle bureau d'ordre", nomme(KARIM)));
        String code = "bo-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode app = json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"nom\":\"Bureau d'ordre central\","
                                + "\"adressesAutorisees\":[\"127.0.0.1\"]}"))
                .andExpect(status().isCreated()));
        JsonNode g = json(mvc.perform(post("/api/v1/applications/" + app.get("id").asText() + "/cles")
                .contentType(APPLICATION_JSON).content("{\"delegation\":false}")).andExpect(status().isCreated()));
        mvc.perform(put("/api/v1/cles-api/" + g.get("details").get("id").asText() + "/portee")
                        .contentType(APPLICATION_JSON)
                        .content("{\"portee\":[{\"noeudId\":\"" + espace + "\",\"operations\":[\"DEPOT\"]}]}"))
                .andExpect(status().isOk());

        JsonNode d = json(mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "courrier.pdf", "application/pdf",
                                com.ipt.ged.support.Pdfs.pdf("courrier " + UUID.randomUUID())))
                        .param("name", "Courrier entrant").param("typeDocumentId", type.toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .anonymous())
                        .header(com.ipt.ged.cleapi.FiltreCleApi.ENTETE_CLE, g.get("cle").asText()))
                .andExpect(status().is2xxSuccessful()));
        UUID doc = UUID.fromString(d.get("id").asText());
        UUID appId = UUID.fromString(app.get("id").asText());

        // Circuit ouvert sans initiateur (aucune personne), notification au validateur.
        assertEquals("EN_COURS", jdbc.queryForObject("SELECT statut FROM circuit WHERE document_id = ?", String.class, doc));
        assertNull(jdbc.queryForObject("SELECT initiateur_id FROM circuit WHERE document_id = ?", UUID.class, doc));
        EvenementWorkflow ouverture = evenements.stream(EvenementWorkflow.class)
                .filter(e -> e.action().equals(EvenementWorkflow.CIRCUIT_OUVERT) && e.objetId().equals(doc))
                .findFirst().orElseThrow();
        assertEquals(appId, ouverture.apres().get("applicationId"));
        assertEquals("Application « Bureau d'ordre central »", ouverture.demande().variables().get("auteur"));
        assertTrue(ouverture.demande().destinataires().contains(jeu.utilisateurId(KARIM)));
        // Journal : l'application est l'actrice de l'ouverture.
        assertEquals(appId, jdbc.queryForObject("SELECT acteur_application_id FROM journal_audit"
                + " WHERE action = 'CIRCUIT_OUVERT' AND objet_id = ?", UUID.class, doc));
    }
}
