package com.ipt.ged.document.modele;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.document.DocumentRattachement;
import com.ipt.ged.document.DocumentRattachementRepository;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.identite.Role;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import com.ipt.ged.planindexation.PlanIndexation;
import com.ipt.ged.planindexation.PlanIndexationRepository;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.workspace.WorkSpaceRepository;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Lot E7, partie modèle : méta-modèle et métadonnées (§12.7), socle commun et
 * conservation (§12.9), type de document, versions et verrou (§12.8),
 * déplacement et renommage (§12.5), espaces d'échange (R-03, D12).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@WithUserDetails(Comptes.ADMIN)
class ModeleDocumentApiTest {

    private static final String DOCS = "/api/v1/documents";
    private static final String U = Comptes.SANS_ROLE;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEvents evenements;
    @Autowired private PlanIndexationRepository plans;
    @Autowired private UploadDocumentRepository documents;
    @Autowired private WorkSpaceRepository noeuds;
    @Autowired private DocumentRattachementRepository rattachements;

    private UUID espace, type;
    private IndexField date, montant, statut, urgent, note;

    @BeforeEach
    void plan() {
        espace = jeu.noeud("Espace E7", null);
        type = jeu.type(espace, Confidentialite.PUBLIC);
        date = jeu.index("DATE_FACT", IndexFieldType.DATE, true, null);
        montant = jeu.index("MONTANT", IndexFieldType.NOMBRE, false, null);
        statut = jeu.index("STATUT", IndexFieldType.LISTE, false, "Payée,Impayée");
        urgent = jeu.index("URGENT", IndexFieldType.BOOLEEN, false, null);
        note = jeu.index("NOTE", IndexFieldType.TEXTE, false, null);
        jeu.planPour(type, date, montant, statut, urgent, note);
    }

    private RequestPostProcessor comme(String identifiant) {
        return user(identites.loadUserByUsername(identifiant));
    }

    private MockMultipartHttpServletRequestBuilder depot(UUID typeId, String contenu) {
        return (MockMultipartHttpServletRequestBuilder) multipart(DOCS)
                .file(new MockMultipartFile("file", "piece.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf(contenu)))
                .param("typeDocumentId", typeId.toString());
    }

    /** Métadonnées du dépôt : une partie JSON de la requête multipart, comme l'envoie l'interface. */
    private static MockMultipartFile meta(String json) {
        return new MockMultipartFile("metadonnees", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private UUID deposer(String nom, String metadonnees, String dateDocument) throws Exception {
        MockMultipartHttpServletRequestBuilder req = depot(type, "contenu " + nom);
        req.param("name", nom);
        if (metadonnees != null) req.file(meta(metadonnees));
        if (dateDocument != null) req.param("dateDocument", dateDocument);
        return UUID.fromString(json(mvc.perform(req).andExpect(status().isCreated())).get("id").asText());
    }

    /* ---------------------------------------------------------------- §12.7 méta-modèle */

    @Test
    @DisplayName("Métadonnées au dépôt : validées contre le plan et normalisées par nature (booléen compris)")
    void metadonneesNormalisees() throws Exception {
        String meta = "{\"" + date.getCode().toLowerCase() + "\":\"2026-03-01\",\"" + montant.getId()
                + "\":\"1250,5\",\"" + statut.getCode() + "\":\"payée\",\"" + urgent.getCode() + "\":\"oui\"}";
        JsonNode d = json(mvc.perform(depot(type, "a").file(meta(meta))).andExpect(status().isCreated()));
        JsonNode m = d.get("metadonnees");
        assertEquals("2026-03-01", m.get(date.getCode()).asText());
        assertEquals(1250.5, m.get(montant.getCode()).asDouble());
        assertTrue(m.get(montant.getCode()).isNumber());
        assertEquals("Payée", m.get(statut.getCode()).asText());
        assertTrue(m.get(urgent.getCode()).asBoolean());
        assertTrue(m.get(urgent.getCode()).isBoolean());
        assertNotNull(documents.findById(UUID.fromString(d.get("id").asText())).orElseThrow()
                .getPlanIndexationVersionId(), "le document référence la version en vigueur");
    }

    @Test
    @DisplayName("Métadonnées refusées : 400 METADONNEES_INVALIDES avec une erreur par champ, rien n'est déposé")
    void metadonneesRefusees() throws Exception {
        long avant = jdbc.queryForObject("SELECT count(*) FROM document", Long.class);
        mvc.perform(depot(type, "b").file(meta("{\"" + montant.getCode() + "\":\"beaucoup\",\""
                        + statut.getCode() + "\":\"Annulée\",\"" + urgent.getCode() + "\":\"peut-être\",\"INCONNU\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METADONNEES_INVALIDES"))
                .andExpect(jsonPath("$.erreurs." + montant.getCode()).value("attend un nombre."))
                .andExpect(jsonPath("$.erreurs." + statut.getCode(), containsString("n'accepte pas")))
                .andExpect(jsonPath("$.erreurs." + urgent.getCode()).exists())
                .andExpect(jsonPath("$.erreurs.INCONNU").exists())
                .andExpect(jsonPath("$.erreurs." + date.getCode()).value("est obligatoire."));
        assertEquals(avant, jdbc.queryForObject("SELECT count(*) FROM document", Long.class));
        // Sans métadonnées : dépôt en deux temps, les obligatoires attendent l'indexation (§12.11).
        mvc.perform(depot(type, "c")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ANO-E7-003 : la valeur par défaut d'un index non transmis est conservée au dépôt (index et fiche)")
    void valeurParDefaut() throws Exception {
        IndexField observation = jeu.index("OBS", IndexFieldType.TEXTE, false, null);
        IndexField ref = jeu.index("REFD", IndexFieldType.TEXTE, true, null);
        jdbc.update("UPDATE index_def SET valeur_par_defaut = 'sans observation' WHERE id = ?", observation.getId());
        observation.setValeurParDefaut("sans observation");   // l'entité en mémoire suit la base
        UUID autreType = jeu.type(espace, Confidentialite.PUBLIC);
        jeu.planPour(autreType, ref, observation);

        // Dépôt avec métadonnées, sans l'index à défaut.
        JsonNode d = json(mvc.perform(depot(autreType, "défaut").file(meta("{\"" + ref.getCode() + "\":\"R-1\"}")))
                .andExpect(status().isCreated()));
        assertEquals("sans observation", d.get("metadonnees").get(observation.getCode()).asText());
        UUID doc = UUID.fromString(d.get("id").asText());
        assertEquals("sans observation", jdbc.queryForObject("SELECT valeur FROM document_index_valeur WHERE document_id = ?"
                + " AND index_def_id = ?", String.class, doc, observation.getId()));
        // Relue par la fiche : toujours là.
        mvc.perform(get(DOCS + "/" + doc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadonnees." + observation.getCode()).value("sans observation"));

        // Dépôt en deux temps (aucune métadonnée) : le défaut est posé, l'obligatoire attend l'indexation.
        JsonNode sans = json(mvc.perform(depot(autreType, "deux temps")).andExpect(status().isCreated()));
        assertEquals("sans observation", sans.get("metadonnees").get(observation.getCode()).asText());
        assertFalse(sans.get("metadonnees").has(ref.getCode()));
    }

    @Test
    @DisplayName("Plan versionné : modifier le plan n'altère pas les documents déjà déposés")
    void planVersionne() throws Exception {
        UUID ancien = deposer("Ancien", "{\"" + date.getCode() + "\":\"2026-01-10\"}", null);
        UUID versionAncienne = documents.findById(ancien).orElseThrow().getPlanIndexationVersionId();

        // Nouvel index OBLIGATOIRE ajouté au plan par l'API : nouvelle version.
        IndexField ref = jeu.index("REF", IndexFieldType.TEXTE, true, null);
        PlanIndexation plan = plans.findById(jdbc.queryForObject(
                "SELECT plan_indexation_id FROM type_document WHERE id = ?", UUID.class, type)).orElseThrow();
        List<String> ids = new ArrayList<>();
        plan.getIndices().forEach(i -> ids.add("\"" + i.getId() + "\""));
        ids.add("\"" + ref.getId() + "\"");
        mvc.perform(put("/api/v1/plan-indexations/" + plan.getId()).contentType(APPLICATION_JSON)
                        .content("{\"code\":\"" + plan.getCode() + "\",\"nomDuPlan\":\"P\",\"manuel\":true,"
                                + "\"indexIds\":[" + String.join(",", ids) + "]}"))
                .andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject("SELECT max(numero) FROM plan_indexation_version WHERE plan_indexation_id = ?",
                Integer.class, plan.getId()));

        // L'ancien document se modifie contre SA version : REF n'y existe pas.
        mvc.perform(put(DOCS + "/" + ancien).contentType(APPLICATION_JSON)
                        .content("{\"metadonnees\":{\"" + date.getCode() + "\":\"2026-01-11\"}}"))
                .andExpect(status().isOk());
        assertEquals(versionAncienne, documents.findById(ancien).orElseThrow().getPlanIndexationVersionId());
        // Un nouveau dépôt suit la nouvelle version : REF devient exigé.
        mvc.perform(depot(type, "d").file(meta("{\"" + date.getCode() + "\":\"2026-01-12\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erreurs." + ref.getCode()).value("est obligatoire."));
    }

    @Test
    @DisplayName("Recherche sur métadonnées : critères par nature, date du document en tri prioritaire, périmètre seul")
    void recherche() throws Exception {
        UUID a = deposer("A", "{\"" + date.getCode() + "\":\"2026-02-01\",\"" + montant.getCode() + "\":100,\""
                + statut.getCode() + "\":\"Payée\",\"" + urgent.getCode() + "\":true,\"" + note.getCode() + "\":\"Fournisseur ACME\"}",
                "2026-02-01");
        UUID b = deposer("B", "{\"" + date.getCode() + "\":\"2026-03-01\",\"" + montant.getCode() + "\":500,\""
                + statut.getCode() + "\":\"Payée\",\"" + urgent.getCode() + "\":false}", "2026-05-01");
        UUID c = deposer("C", "{\"" + date.getCode() + "\":\"2026-04-01\",\"" + montant.getCode() + "\":900,\""
                + statut.getCode() + "\":\"Impayée\"}", "2026-03-01");

        String base = "{\"typeDocumentId\":\"" + type + "\",\"criteres\":[";
        assertEquals(List.of(b, c, a), ids(base + "]}"), "date du document décroissante");
        assertEquals(List.of(b, a), ids(base + "{\"code\":\"" + statut.getCode() + "\",\"valeur\":\"payée\"}]}"));
        assertEquals(List.of(b, c), ids(base + "{\"code\":\"" + montant.getCode() + "\",\"de\":\"200\"}]}"));
        assertEquals(List.of(b, c), ids(base + "{\"code\":\"" + date.getCode()
                + "\",\"de\":\"2026-02-15\",\"a\":\"2026-04-01\"}]}"));
        assertEquals(List.of(a), ids(base + "{\"code\":\"" + urgent.getCode() + "\",\"valeur\":\"oui\"}]}"));
        assertEquals(List.of(a), ids(base + "{\"code\":\"" + note.getCode() + "\",\"valeur\":\"acme\"}]}"));

        // Périmètre : un utilisateur sans droit sur l'espace ne voit ni résultat ni total.
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, jeu.noeud("Ailleurs", null), null);
        mvc.perform(post(DOCS + "/recherche").with(comme(U)).contentType(APPLICATION_JSON).content(base + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("ANO-F-006 : la liste des documents se trie sur la date du document, ex-aequo départagés")
    void listeTrieeParDateDuDocument() throws Exception {
        String prefixe = "TRI-" + UUID.randomUUID().toString().substring(0, 6);
        UUID a = deposer(prefixe + "-A", null, "2026-02-01");
        UUID b = deposer(prefixe + "-B", null, "2025-11-15");
        UUID c = deposer(prefixe + "-C", null, "2026-05-01");
        UUID d = deposer(prefixe + "-D", null, "2026-02-01");

        List<UUID> croissant = liste(prefixe, "asc");
        List<UUID> decroissant = liste(prefixe, "desc");
        assertEquals(b, croissant.get(0));
        assertEquals(c, croissant.get(3));
        assertEquals(java.util.Set.of(a, d), java.util.Set.copyOf(croissant.subList(1, 3)));
        assertEquals(c, decroissant.get(0));
        assertEquals(b, decroissant.get(3));
        // Ex-aequo (même date) : ordre stable d'un appel à l'autre, pages comprises.
        assertEquals(croissant.subList(1, 3), liste(prefixe, "asc").subList(1, 3));
    }

    private List<UUID> liste(String recherche, String sens) throws Exception {
        JsonNode r = json(mvc.perform(get(DOCS).param("search", recherche).param("sortBy", "dateDocument")
                .param("sortDir", sens)).andExpect(status().isOk()));
        List<UUID> l = new ArrayList<>();
        r.get("content").forEach(n -> l.add(UUID.fromString(n.get("id").asText())));
        return l;
    }

    @Test
    @DisplayName("ANO-F-005 : les champs à saisir d'un document disent lesquels sont obligatoires")
    void champsObligatoiresExposes() throws Exception {
        UUID doc = deposer("À indexer", null, null);
        JsonNode champs = json(mvc.perform(get("/api/v1/indexation/documents/" + doc + "/champs"))
                .andExpect(status().isOk()));
        java.util.Map<String, Boolean> parCode = new java.util.HashMap<>();
        champs.forEach(c -> parCode.put(c.get("code").asText(), c.get("obligatoire").asBoolean()));
        assertEquals(Boolean.TRUE, parCode.get(date.getCode()));
        assertEquals(Boolean.FALSE, parCode.get(montant.getCode()));
    }

    private List<UUID> ids(String requete) throws Exception {
        JsonNode r = json(mvc.perform(post(DOCS + "/recherche").contentType(APPLICATION_JSON).content(requete))
                .andExpect(status().isOk()));
        List<UUID> l = new ArrayList<>();
        r.get("content").forEach(n -> l.add(UUID.fromString(n.get("id").asText())));
        assertEquals(l.size(), r.get("total").asInt());
        return l;
    }

    /* ---------------------------------------------------------------- §12.9 conservation, type */

    private LocalDate echeance(UUID doc) {
        return jdbc.queryForObject("SELECT echeance_conservation FROM document WHERE id = ?", LocalDate.class, doc);
    }

    @Test
    @DisplayName("Échéance : calculée à partir du type, recalculée si la date, le type ou le point de départ change")
    void echeance() throws Exception {
        jdbc.update("UPDATE type_document SET duree_conservation_mois = 12 WHERE id = ?", type);
        UUID doc = deposer("Conservé", "{\"" + date.getCode() + "\":\"2026-06-15\"}", "2026-01-31");
        assertEquals(LocalDate.of(2027, 1, 31), echeance(doc));

        jdbc.update("UPDATE document SET date_document = '2026-02-10' WHERE id = ?", doc);
        assertEquals(LocalDate.of(2027, 2, 10), echeance(doc));

        // Le type change de durée : recalcul de tous ses documents.
        jdbc.update("UPDATE type_document SET duree_conservation_mois = 120 WHERE id = ?", type);
        assertEquals(LocalDate.of(2036, 2, 10), echeance(doc));

        // Point de départ = métadonnée date du plan.
        jdbc.update("UPDATE type_document SET point_depart = 'METADONNEE', point_depart_index_code = ? WHERE id = ?",
                date.getCode(), type);
        assertEquals(LocalDate.of(2036, 6, 15), echeance(doc));

        jdbc.update("UPDATE type_document SET duree_conservation_mois = NULL, point_depart = 'DATE_DOCUMENT',"
                + " point_depart_index_code = NULL WHERE id = ?", type);
        assertNull(echeance(doc));
    }

    @Test
    @DisplayName("Type : point de départ contrôlé ; type utilisé non supprimable (409) mais désactivable ; désactivé = plus de dépôt")
    void typeDocument() throws Exception {
        String corps = "{\"code\":\"TD-E7-" + UUID.randomUUID().toString().substring(0, 6) + "\",\"typeDeDocument\":\"Facture E7\","
                + "\"description\":\"d\",\"workspaceId\":\"" + espace + "\",\"planIndexationId\":\""
                + jdbc.queryForObject("SELECT plan_indexation_id FROM type_document WHERE id = ?", UUID.class, type)
                + "\",\"typeAutorise\":[\"pdf\"],\"tailleMaxMo\":5,\"dureeConservationMois\":24,"
                + "\"pointDepart\":\"METADONNEE\",\"pointDepartIndexCode\":\"" + note.getCode() + "\"}";
        mvc.perform(post("/api/v1/type-documents").contentType(APPLICATION_JSON).content(corps))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail", containsString("nature date")));
        String t = mvc.perform(post("/api/v1/type-documents").contentType(APPLICATION_JSON)
                        .content(corps.replace(note.getCode(), date.getCode().toLowerCase())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointDepartIndexCode").value(date.getCode()))
                .andExpect(jsonPath("$.versionPlan").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID nouveau = UUID.fromString(om.readTree(t).get("id").asText());
        mvc.perform(delete("/api/v1/type-documents/" + nouveau)).andExpect(status().isNoContent());

        deposer("Utilise", null, null);
        mvc.perform(delete("/api/v1/type-documents/" + type))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TYPE_UTILISE"));
        assertEquals("r", jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint"
                + " WHERE conname = 'fk_document_type_document' AND connamespace = current_schema()::regnamespace", String.class), "FK RESTRICT en base");
        mvc.perform(patch("/api/v1/type-documents/" + type + "/actif").param("actif", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actif").value(false));
        mvc.perform(depot(type, "refus")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("désactivé")));
    }

    /* ---------------------------------------------------------------- §12.8 versions */

    /** Empreinte du PDF de test portant ce marqueur (le contrôle du type réel exige un vrai PDF). */
    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(com.ipt.ged.support.Pdfs.pdf(s)));
    }

    @Test
    @DisplayName("Versions : numéro, auteur, empreinte ; D9 au versement ; ancienne version désignable ; historique en lecture seule")
    void versions() throws Exception {
        UUID doc = deposer("Versionné", null, null);
        JsonNode v2 = json(mvc.perform(multipart(DOCS + "/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "v2.pdf", "application/pdf",
                                com.ipt.ged.support.Pdfs.pdf("deuxième"))))
                .andExpect(status().isCreated()));
        JsonNode liste = v2.get("versions");
        assertEquals(2, liste.get(0).get("numero").asInt());
        assertTrue(liste.get(0).get("principale").asBoolean());
        assertFalse(liste.get(1).get("principale").asBoolean());
        assertEquals(sha256("deuxième"), liste.get(0).get("empreinte").asText());
        assertEquals(sha256("contenu Versionné"), liste.get(1).get("empreinte").asText());
        assertEquals(jeu.utilisateurId(Comptes.ADMIN).toString(), liste.get(0).get("auteurId").asText());

        String premiere = liste.get(1).get("id").asText();
        mvc.perform(patch(DOCS + "/" + doc + "/versions/" + premiere + "/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[1].principale").value(true))
                .andExpect(jsonPath("$.versions[0].principale").value(false))
                .andExpect(jsonPath("$.fileName").value("piece.pdf"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM version_document WHERE document_id = ? AND courante",
                Integer.class, doc));
        mvc.perform(get(DOCS + "/" + doc + "/versions/" + premiere + "/download")).andExpect(status().isOk());

        // Historique en lecture seule, en base.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("UPDATE version_document SET file_name = 'falsifié.pdf' WHERE id = ?", UUID.fromString(premiere)));
        // Une seule publication par action : les événements du lot stockage.
        assertEquals(1, evenements.stream(com.ipt.ged.document.evenement.VersionAjoutee.class)
                .filter(e -> e.documentId().equals(doc)).count());
        assertEquals(1, evenements.stream(com.ipt.ged.document.evenement.VersionRestauree.class)
                .filter(e -> e.documentId().equals(doc)).count());
        assertEquals(0, evenements.stream(EvenementModeleDocument.class).filter(e -> e.documentId().equals(doc))
                .count());
    }

    /* ---------------------------------------------------------------- §12.8 verrou */

    @Test
    @DisplayName("Verrou : posé par l'Administrateur avec motif, bloque toute écriture en 409, levé, audité")
    void verrou() throws Exception {
        UUID doc = deposer("Verrouillé", null, null);
        UUID autre = jeu.noeud("Autre", null);
        jeu.habiliter(U, Role.ADMINISTRATEUR, espace, null);
        mvc.perform(patch(DOCS + "/" + doc + "/verrou").param("verrouille", "true").with(comme(U)))
                .andExpect(status().isForbidden());

        mvc.perform(patch(DOCS + "/" + doc + "/verrou").param("verrouille", "true").param("motif", "Contrôle fiscal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verrouille").value(true))
                .andExpect(jsonPath("$.verrouMotif").value("Contrôle fiscal"))
                .andExpect(jsonPath("$.verrouLe").exists());
        assertEquals(jeu.utilisateurId(Comptes.ADMIN),
                jdbc.queryForObject("SELECT verrou_par FROM document WHERE id = ?", UUID.class, doc));

        List<ResultActions> refus = List.of(
                mvc.perform(put(DOCS + "/" + doc).contentType(APPLICATION_JSON).content("{\"name\":\"x\"}")),
                mvc.perform(multipart(DOCS + "/" + doc + "/versions")
                        .file(new MockMultipartFile("file", "v.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf("v")))),
                mvc.perform(patch(DOCS + "/" + doc + "/emplacement").contentType(APPLICATION_JSON)
                        .content("{\"noeudId\":\"" + autre + "\"}")),
                mvc.perform(post(DOCS + "/" + doc + "/rattachements").contentType(APPLICATION_JSON)
                        .content("{\"noeudId\":\"" + autre + "\"}")),
                mvc.perform(delete(DOCS + "/" + doc)));
        for (ResultActions r : refus) {
            r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_VERROUILLE"))
                    .andExpect(jsonPath("$.detail", containsString("Contrôle fiscal")));
        }
        mvc.perform(patch(DOCS + "/" + doc + "/verrou").param("verrouille", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verrouMotif").doesNotExist());
        mvc.perform(put(DOCS + "/" + doc).contentType(APPLICATION_JSON).content("{\"name\":\"libre\"}"))
                .andExpect(status().isOk());
        // Pose et levée : une trace chacune (DOCUMENT_VERROUILLE, DOCUMENT_DEVERROUILLE), motif compris.
        List<com.ipt.ged.document.evenement.VerrouModifie> verrous = evenements
                .stream(com.ipt.ged.document.evenement.VerrouModifie.class).filter(e -> e.documentId().equals(doc)).toList();
        assertEquals(List.of("DOCUMENT_VERROUILLE", "DOCUMENT_DEVERROUILLE"),
                verrous.stream().map(com.ipt.ged.document.evenement.VerrouModifie::type).toList());
        assertEquals("Contrôle fiscal", verrous.get(0).motifVerrou());
    }

    /* ---------------------------------------------------------------- §12.5 déplacement, renommage */

    @Test
    @DisplayName("Déplacement : Déplacer sur l'origine et écriture sur la destination, rattachement doublon retiré, audité")
    void deplacement() throws Exception {
        UUID doc = deposer("Mobile", null, null);
        UUID dest = jeu.noeud("Destination", null);
        rattachements.saveAndFlush(new DocumentRattachement(documents.findById(doc).orElseThrow(),
                noeuds.findById(dest).orElseThrow(), null));

        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, espace, null);
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, dest, null);
        mvc.perform(patch(DOCS + "/" + doc + "/emplacement").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + dest + "\"}")).andExpect(status().isForbidden());

        jeu.habiliter(U, Role.AGENT_ARCHIVE, espace, null);
        mvc.perform(patch(DOCS + "/" + doc + "/emplacement").with(comme(U)).contentType(APPLICATION_JSON)
                        .content("{\"noeudId\":\"" + dest + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(dest.toString()));
        assertEquals(0, rattachements.findByDocumentIdOrderByCreeLeAsc(doc).size());
        EvenementModeleDocument e = evenements.stream(EvenementModeleDocument.class)
                .filter(x -> x.action().equals(EvenementModeleDocument.DOCUMENT_DEPLACE)).findFirst().orElseThrow();
        assertEquals(espace, e.avant().get("noeudId"));
        assertEquals(dest, e.apres().get("noeudId"));
    }

    @Test
    @DisplayName("Renommage : nom unique dans le dossier (409), document et dossier")
    void renommage() throws Exception {
        deposer("Rapport", null, null);
        UUID autre = deposer("Brouillon", null, null);
        mvc.perform(put(DOCS + "/" + autre).contentType(APPLICATION_JSON).content("{\"name\":\"rapport\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOM_DEJA_UTILISE"));
        mvc.perform(put(DOCS + "/" + autre).contentType(APPLICATION_JSON).content("{\"name\":\"Rapport final\"}"))
                .andExpect(status().isOk());
        assertTrue(evenements.stream(EvenementModeleDocument.class).anyMatch(x ->
                x.action().equals(EvenementModeleDocument.DOCUMENT_RENOMME) && "Brouillon".equals(x.avant().get("nom"))));

        UUID d1 = jeu.noeud("Factures", espace);
        jeu.noeud("Contrats", espace);
        mvc.perform(put("/api/v1/workspaces/" + d1).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"contrats\",\"code\":\"" + jdbc.queryForObject("SELECT code FROM noeud WHERE id = ?",
                                String.class, d1) + "\",\"employeId\":\"" + jeu.employeId(Comptes.ADMIN)
                                + "\",\"workflowId\":\"" + jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?",
                                UUID.class, d1) + "\",\"parentId\":\"" + espace + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOM_DEJA_UTILISE"));
    }

    /* ---------------------------------------------------------------- R-03 / D12 espaces d'échange */

    @Test
    @DisplayName("Espace d'échange : les membres habilités y créent des dossiers ; pas dans un espace métier ; usage hérité")
    void espaceEchange() throws Exception {
        UUID echange = jeu.noeud("Marchés CPS", null);
        var espaceEchange = noeuds.findById(echange).orElseThrow();
        espaceEchange.setUsageEspace(com.ipt.ged.workspace.UsageEspace.ECHANGE);
        noeuds.saveAndFlush(espaceEchange);
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, echange, null);
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, espace, null);
        UUID wf = jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class, echange);
        String corps = "{\"name\":\"Lot 1\",\"code\":\"CPS-" + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"employeId\":\"" + jeu.employeId(U) + "\",\"workflowId\":\"" + wf + "\",\"parentId\":\"%s\"}";

        String cree = mvc.perform(post("/api/v1/workspaces").with(comme(U)).contentType(APPLICATION_JSON)
                        .content(corps.formatted(echange)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usageEspace").value("ECHANGE"))
                .andExpect(jsonPath("$.nature").value("DOSSIER"))
                .andReturn().getResponse().getContentAsString();
        UUID lot = UUID.fromString(om.readTree(cree).get("id").asText());
        mvc.perform(post("/api/v1/workspaces").with(comme(U)).contentType(APPLICATION_JSON)
                        .content(corps.formatted(lot).replace("Lot 1", "Pièces").replace("CPS-", "CPS2-")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/workspaces").with(comme(U)).contentType(APPLICATION_JSON)
                        .content(corps.formatted(espace).replace("CPS-", "MET-")))
                .andExpect(status().isForbidden());

        // L'espace redevient métier : ses dossiers suivent.
        jdbc.update("UPDATE noeud SET usage_espace = 'METIER' WHERE id = ?", echange);
        assertEquals("METIER", jdbc.queryForObject("SELECT usage_espace FROM noeud WHERE id = ?", String.class, lot));
    }

    /** Espace d'échange neuf, avec son type de document et un membre standard (Déposer). */
    private UUID espaceEchangeAvecMembre(String nom) {
        UUID echange = jeu.noeud(nom, null);
        var e = noeuds.findById(echange).orElseThrow();
        e.setUsageEspace(com.ipt.ged.workspace.UsageEspace.ECHANGE);
        noeuds.saveAndFlush(e);
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, echange, null);
        return echange;
    }

    private UUID dossierPar(String identifiant, UUID parent, String nom) throws Exception {
        UUID wf = jdbc.queryForObject("SELECT regle_workflow_id FROM noeud WHERE id = ?", UUID.class, parent);
        String corps = "{\"name\":\"" + nom + "\",\"code\":\"D-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"employeId\":\"" + jeu.employeId(identifiant) + "\",\"workflowId\":\"" + wf
                + "\",\"parentId\":\"" + parent + "\"}";
        return UUID.fromString(json(mvc.perform(post("/api/v1/workspaces").with(comme(identifiant))
                .contentType(APPLICATION_JSON).content(corps)).andExpect(status().isCreated())).get("id").asText());
    }

    private ResultActions deplacerComme(String identifiant, UUID doc, UUID destination) throws Exception {
        return mvc.perform(patch(DOCS + "/" + doc + "/emplacement").with(comme(identifiant))
                .contentType(APPLICATION_JSON).content("{\"noeudId\":\"" + destination + "\"}"));
    }

    @Test
    @DisplayName("ANO-F-016 (D12) : dans un espace d'échange, un membre crée un dossier, y dépose et y range ses documents")
    void rangementDansEspaceEchange() throws Exception {
        UUID echange = espaceEchangeAvecMembre("Partage CPS");
        UUID typeEchange = jeu.type(echange, Confidentialite.PUBLIC);
        UUID lotA = dossierPar(U, echange, "Lot A");
        UUID lotB = dossierPar(U, echange, "Lot B");
        UUID pieces = dossierPar(U, lotA, "Pièces");

        // Dépôt avec emplacement cible : le document va dans le dossier choisi.
        MockMultipartHttpServletRequestBuilder req = depot(typeEchange, "offre");
        req.param("noeudId", pieces.toString()).with(comme(U));
        UUID doc = UUID.fromString(json(mvc.perform(req).andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspace.id").value(pieces.toString()))).get("id").asText());
        assertEquals(pieces, documents.findById(doc).orElseThrow().getWorkspace().getId());

        // Déplacement entre dossiers du même espace d'échange : Déposer suffit (pas Déplacer).
        deplacerComme(U, doc, lotB).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(lotB.toString()));
        deplacerComme(U, doc, echange).andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.id").value(echange.toString()));
        assertTrue(evenements.stream(EvenementModeleDocument.class)
                .anyMatch(x -> x.action().equals(EvenementModeleDocument.DOCUMENT_DEPLACE)), "déplacement audité");

        // Sans dépôt explicite, le document va toujours dans le dossier du type.
        MockMultipartHttpServletRequestBuilder sansCible = depot(typeEchange, "annexe");
        sansCible.with(comme(U));
        mvc.perform(sansCible).andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspace.id").value(echange.toString()));
    }

    @Test
    @DisplayName("ANO-F-016 (D12) : espaces métier inchangés ; pas de sortie de l'espace d'échange sans Déplacer")
    void rangementLimiteALEspaceEchange() throws Exception {
        UUID echange = espaceEchangeAvecMembre("Partage Lots");
        UUID typeEchange = jeu.type(echange, Confidentialite.PUBLIC);
        UUID autreEchange = espaceEchangeAvecMembre("Autre partage");
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, espace, null);
        UUID sousMetier = jeu.noeud("Sous-dossier métier", espace);

        // Espace métier : le dépôt ne choisit pas son dossier, le déplacement exige Déplacer.
        MockMultipartHttpServletRequestBuilder metier = depot(type, "métier");
        metier.param("noeudId", sousMetier.toString()).with(comme(U));
        mvc.perform(metier).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPLACEMENT_HORS_ESPACE_ECHANGE"));
        UUID docMetier = deposer("Métier", null, null);
        deplacerComme(U, docMetier, sousMetier).andExpect(status().isForbidden());

        // Espace d'échange : pas de dépôt dans un autre espace, ni de sortie par déplacement.
        MockMultipartHttpServletRequestBuilder ailleurs = depot(typeEchange, "ailleurs");
        ailleurs.param("noeudId", autreEchange.toString()).with(comme(U));
        mvc.perform(ailleurs).andExpect(status().isUnprocessableEntity());
        MockMultipartHttpServletRequestBuilder ok = depot(typeEchange, "partagé");
        ok.with(comme(U));
        UUID doc = UUID.fromString(json(mvc.perform(ok).andExpect(status().isCreated())).get("id").asText());
        deplacerComme(U, doc, autreEchange).andExpect(status().isForbidden());
        deplacerComme(U, doc, espace).andExpect(status().isForbidden());

        // Hors périmètre : un dossier d'échange sans habilitation reste introuvable.
        UUID horsPerimetre = jeu.noeud("Partage fermé", null);
        var f = noeuds.findById(horsPerimetre).orElseThrow();
        f.setUsageEspace(com.ipt.ged.workspace.UsageEspace.ECHANGE);
        noeuds.saveAndFlush(f);
        MockMultipartHttpServletRequestBuilder ferme = depot(typeEchange, "fermé");
        ferme.param("noeudId", horsPerimetre.toString()).with(comme(U));
        mvc.perform(ferme).andExpect(status().isNotFound());
    }
}
