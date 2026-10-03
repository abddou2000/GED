package com.ipt.ged.autorisation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.autorisation.evenement.AccesDocumentModifie;
import com.ipt.ged.autorisation.evenement.HabilitationModifiee;
import com.ipt.ged.document.DocumentRattachement;
import com.ipt.ged.document.DocumentRattachementRepository;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.previsualisation.ControleAccesPrevisualisation;
import com.ipt.ged.fichier.previsualisation.ResolveurFichierVersion;
import com.ipt.ged.identite.Role;
import com.ipt.ged.recherche.FragmentSql;
import com.ipt.ged.recherche.PredicatDroits;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.ipt.ged.autorisation.Confidentialite.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Chaque chemin d'accès, éprouvé avec un utilisateur au périmètre restreint
 * (dossier technique §12.2.3 : « des tests automatisés vérifient chaque chemin
 * d'accès » ; P5 : un objet hors périmètre n'apparaît ni comme résultat, ni
 * dans un total, ni comme extrait).
 *
 * <pre>
 *   A (espace)                B (espace)
 *   ├── A1   dA1, dRattache*  └── dB, dRattache (principal)
 *   ├── A2   dA2
 *   └── dA, dPrive (déposant : admin), dConf (déposant : admin)
 * </pre>
 * L'utilisateur éprouvé ({@code Comptes.SANS_ROLE}) part sans aucune
 * habilitation ; chaque test pose les siennes. Les requêtes sans identité
 * explicite partent sous l'Administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@WithUserDetails(Comptes.ADMIN)
class CheminsAccesApiTest {

    private static final String U = Comptes.SANS_ROLE;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;
    @Autowired private DocumentRattachementRepository rattachements;
    @Autowired private UploadDocumentRepository documents;
    @Autowired private WorkSpaceRepository noeuds;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PredicatDroits predicatDroits;
    @Autowired private ControleAccesPrevisualisation controlePrevisualisation;
    @Autowired private ApplicationEvents evenements;
    @Autowired private EntityManager em;

    private UUID a, a1, a2, b, tA, tA1, tB;
    private UUID dA, dA1, dA2, dB, dPrive, dConf, dRattache;

    @BeforeEach
    void jeu() {
        UUID admin = jeu.employeId(Comptes.ADMIN);
        a = jeu.noeud("A", null);
        a1 = jeu.noeud("A1", a);
        a2 = jeu.noeud("A2", a);
        b = jeu.noeud("B", null);
        tA = jeu.type(a, PUBLIC);
        tA1 = jeu.type(a1, PUBLIC);
        UUID tA2 = jeu.type(a2, PUBLIC);
        tB = jeu.type(b, PUBLIC);
        dA = jeu.document("dA", tA, PUBLIC, admin);
        dA1 = jeu.document("dA1", tA1, PUBLIC, admin);
        dA2 = jeu.document("dA2", tA2, PUBLIC, admin);
        dB = jeu.document("dB", tB, PUBLIC, admin);
        dPrive = jeu.document("dPrive", tA, PRIVE, admin);
        dConf = jeu.document("dConf", tA, CONFIDENTIEL, admin);
        dRattache = jeu.document("dRattache", tB, PUBLIC, admin);
        rattachements.saveAndFlush(new DocumentRattachement(documents.findById(dRattache).orElseThrow(),
                noeuds.findById(a1).orElseThrow(), null));
        jeu.identite(U);
    }

    /* ---------------------------------------------------------------- outils */

    /** Identité rechargée à chaque requête : ses rôles suivent les habilitations. */
    private RequestPostProcessor comme(String identifiant) {
        return user(identites.loadUserByUsername(identifiant));
    }

    private Authentication authentification(String identifiant) {
        UserDetails u = identites.loadUserByUsername(identifiant);
        return new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities());
    }

    private JsonNode json(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** Identifiants des documents de la liste, et total annoncé. */
    private Set<UUID> documentsVus(String url) throws Exception {
        JsonNode page = json(mvc.perform(get(url).with(comme(U))).andExpect(status().isOk()));
        Set<UUID> ids = new HashSet<>();
        page.get("content").forEach(n -> ids.add(UUID.fromString(n.get("id").asText())));
        assertEquals(ids.size(), page.get("total").asInt(), "le total ne compte que le périmètre");
        return ids;
    }

    private void standardSur(UUID noeud) {
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, noeud, null);
    }

    /* ---------------------------------------------------------------- listes et totaux */

    @Test
    @DisplayName("Liste : seul le périmètre est listé et compté (emplacements, rattachement, confidentialité)")
    void listeEtTotal() throws Exception {
        standardSur(a);
        assertEquals(Set.of(dA, dA1, dA2, dRattache), documentsVus("/api/v1/documents?size=200"));
        // Filtre par dossier : le document rattaché figure dans A1.
        assertEquals(Set.of(dA1, dRattache), documentsVus("/api/v1/documents?size=200&workspaceId=" + a1));
        assertEquals(Set.of(), documentsVus("/api/v1/documents/trashed?size=200"));
    }

    @Test
    @DisplayName("Pagination (T-050) : 50 par défaut, plafond 200, total calculé sur le seul périmètre")
    void paginationAuPerimetre() throws Exception {
        standardSur(a);
        mvc.perform(get("/api/v1/documents").with(comme(U)))
                .andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/v1/documents?size=100000").with(comme(U))).andExpect(jsonPath("$.size").value(200));
        mvc.perform(get("/api/v1/documents?size=1&page=2").with(comme(U)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.totalPages").value(4));
        mvc.perform(get("/api/v1/workspaces?size=1").with(comme(U)))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @DisplayName("Emplacement affiché : le premier emplacement accessible quand le principal ne l'est pas")
    void emplacementAffiche() throws Exception {
        standardSur(a1);
        JsonNode page = json(mvc.perform(get("/api/v1/documents?size=200").with(comme(U))));
        JsonNode r = null;
        for (JsonNode n : page.get("content")) if (n.get("id").asText().equals(dRattache.toString())) r = n;
        assertNotNull(r);
        assertEquals(a1.toString(), r.get("workspace").get("id").asText());
    }

    @Test
    @DisplayName("Sans aucune habilitation : aucun accès (compte sans rôle)")
    void sansDroit() throws Exception {
        mvc.perform(get("/api/v1/documents").with(comme(U))).andExpect(status().isForbidden());
    }

    /* ---------------------------------------------------------------- 404 hors périmètre */

    @Test
    @DisplayName("Hors périmètre : 404 sur chaque chemin, indiscernable d'un document inexistant")
    void horsPerimetre404() throws Exception {
        standardSur(a);
        UUID inexistant = UUID.randomUUID();
        for (UUID id : List.of(dB, inexistant)) {
            JsonNode corps = json(mvc.perform(get("/api/v1/documents/" + id).with(comme(U)))
                    .andExpect(status().isNotFound()));
            // problem+json (contrat dev2) : même code et même libellé fixe pour un document
            // hors périmètre et un document inexistant (P5).
            assertEquals("RESSOURCE_INTROUVABLE", corps.get("code").asText());
            assertEquals(com.ipt.ged.common.erreur.RessourceIntrouvableException.DETAIL, corps.get("detail").asText());
        }
        String b64 = dB.toString();
        mvc.perform(get("/api/v1/documents/" + b64 + "/download").with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/documents/" + b64).with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + b64).with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/documents/" + b64 + "/verrou").param("verrouille", "true").with(comme(U)))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/documents/" + b64 + "/versions")
                        .file(new MockMultipartFile("file", "v.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .with(comme(U)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + b64 + "/rattachements").with(comme(U)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + b64 + "/designes").with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workflow/documents/" + b64 + "/circuits").with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ocr/documents/" + b64 + "/texte").with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/indexation/documents/" + b64).with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/indexation/documents/" + b64 + "/champs").with(comme(U)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/indexation/documents/" + b64).with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"valeurs\":[]}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workspaces/" + b).with(comme(U))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Visible mais permission manquante : 403 ; permission présente : l'écriture passe")
    void permissionManquante403() throws Exception {
        standardSur(a);
        mvc.perform(delete("/api/v1/documents/" + dA).with(comme(U))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/documents/" + dA).with(comme(U)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"renommé\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray());
        mvc.perform(get("/api/v1/documents/" + dA).with(comme(U)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[?(@ == 'MODIFIER')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'SUPPRIMER')]").doesNotExist());
    }

    @Test
    @DisplayName("ANO-F-018 : la fiche d'un nœud porte les permissions effectives de l'appelant, pas les listes")
    void permissionsSurLaFicheDuNoeud() throws Exception {
        standardSur(a);
        // Hérité sur A1 : l'Utilisateur standard consulte et dépose, sans supprimer ni archiver. Son
        // Modifier vaut pour les documents ; modifier le nœud relève de GERER_ESPACES (ANO-F-026) : la
        // fiche ne l'annonce pas.
        mvc.perform(get("/api/v1/workspaces/" + a1).with(comme(U)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[?(@ == 'CONSULTER')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'DEPOSER')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'MODIFIER')]").doesNotExist())
                .andExpect(jsonPath("$.permissions[?(@ == 'SUPPRIMER')]").doesNotExist())
                .andExpect(jsonPath("$.permissions[?(@ == 'ARCHIVER')]").doesNotExist());
        mvc.perform(get("/api/v1/workspaces/" + a).with(comme(Comptes.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[?(@ == 'MODIFIER')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'SUPPRIMER')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'ARCHIVER')]").exists());
        mvc.perform(get("/api/v1/workspaces?size=200").with(comme(U)))
                .andExpect(jsonPath("$.content[0].permissions").doesNotExist());
    }

    /* ---------------------------------------------------------------- arborescence */

    @Test
    @DisplayName("Rupture d'héritage : le dossier rompu et son contenu disparaissent")
    void rupture() throws Exception {
        standardSur(a);
        jeu.rupture(U, a2);
        assertEquals(Set.of(dA, dA1, dRattache), documentsVus("/api/v1/documents?size=200"));
        mvc.perform(get("/api/v1/documents/" + dA2).with(comme(U))).andExpect(status().isNotFound());
        String arbre = mvc.perform(get("/api/v1/workspaces/tree").with(comme(U))).andReturn().getResponse()
                .getContentAsString();
        assertFalse(arbre.contains(a2.toString()));
    }

    @Test
    @DisplayName("Arborescence : ancêtre non couvert réduit au libellé de passage, rien d'autre")
    void arbreDePassage() throws Exception {
        standardSur(a1);
        JsonNode arbre = json(mvc.perform(get("/api/v1/workspaces/tree").with(comme(U))).andExpect(status().isOk()));
        JsonNode racineA = null;
        for (JsonNode n : arbre) {
            assertNotEquals(b.toString(), n.get("id").asText(), "l'espace B ne doit pas apparaître");
            if (n.get("id").asText().equals(a.toString())) racineA = n;
        }
        assertNotNull(racineA);
        assertTrue(racineA.get("passage").asBoolean());
        assertTrue(racineA.get("status").isNull());
        assertEquals(1, racineA.get("children").size(), "A2 n'est ni couvert ni ancêtre d'un nœud couvert");
        assertEquals(a1.toString(), racineA.get("children").get(0).get("id").asText());
        assertFalse(racineA.get("children").get(0).get("passage").asBoolean());

        // Listes et fiche : seul le nœud couvert.
        JsonNode liste = json(mvc.perform(get("/api/v1/workspaces?size=200").with(comme(U))));
        assertEquals(1, liste.get("total").asInt());
        assertEquals(a1.toString(), liste.get("content").get(0).get("id").asText());
        mvc.perform(get("/api/v1/workspaces/" + a).with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workspaces/" + a1).with(comme(U))).andExpect(status().isOk());
        String select = mvc.perform(get("/api/v1/workspaces/for-select").with(comme(U))).andReturn()
                .getResponse().getContentAsString();
        assertTrue(select.contains(a1.toString()));
        assertFalse(select.contains(a.toString()) || select.contains(b.toString()));
    }

    @Test
    @DisplayName("Compteurs du tableau de bord : périmètre seul (documents, espaces, répartition)")
    void compteurs() throws Exception {
        standardSur(a);
        JsonNode o = json(mvc.perform(get("/api/v1/stats/overview").with(comme(U))).andExpect(status().isOk()));
        assertEquals(4, o.get("documents").asLong());
        assertEquals(3, o.get("workspaces").asLong());
        assertEquals(0, o.get("accessGroups").asLong());
        long somme = 0;
        for (JsonNode p : json(mvc.perform(get("/api/v1/stats/par-type").with(comme(U))))) somme += p.get("count").asLong();
        assertEquals(4, somme);
        long depots = 0;
        for (JsonNode p : json(mvc.perform(get("/api/v1/stats/depots").with(comme(U))))) depots += p.get("count").asLong();
        assertEquals(4, depots);
    }

    @Test
    @DisplayName("Recherche par index : aucun document hors périmètre dans les résultats")
    void rechercheParIndex() throws Exception {
        standardSur(a1);
        String r = mvc.perform(post("/api/v1/indexation/recherche").with(comme(U)).contentType(APPLICATION_JSON)
                        .content("{\"criteres\":[]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(r.contains(dA1.toString()));
        for (UUID interdit : List.of(dA, dB, dA2, dPrive, dConf)) assertFalse(r.contains(interdit.toString()));
    }

    /* ---------------------------------------------------------------- confidentialité */

    @Test
    @DisplayName("PRIVE : visible du déposant et des porteurs de VOIR_PRIVE seulement")
    void prive() throws Exception {
        standardSur(a);
        String cree = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "note.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("typeDocumentId", tA.toString()).param("confidentialite", "PRIVE")
                        .with(comme(U)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confidentialite").value("PRIVE"))
                .andReturn().getResponse().getContentAsString();
        UUID mien = UUID.fromString(om.readTree(cree).get("id").asText());
        mvc.perform(get("/api/v1/documents/" + mien).with(comme(U))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/" + dPrive).with(comme(U))).andExpect(status().isNotFound());
        assertFalse(documentsVus("/api/v1/documents?size=200").contains(dPrive));

        // Agent d'archive, même sur un autre espace : le sujet porte VOIR_PRIVE.
        jeu.habiliter(U, Role.AGENT_ARCHIVE, b, null);
        mvc.perform(get("/api/v1/documents/" + dPrive).with(comme(U))).andExpect(status().isOk());
        assertTrue(documentsVus("/api/v1/documents?size=200").contains(dPrive));
    }

    @Test
    @DisplayName("CONFIDENTIEL : désignation, retrait à effet immédiat, déposant désigné par défaut")
    void confidentiel() throws Exception {
        standardSur(a);
        mvc.perform(get("/api/v1/documents/" + dConf).with(comme(U))).andExpect(status().isNotFound());
        UUID uid = jeu.utilisateurId(U);
        mvc.perform(post("/api/v1/documents/" + dConf + "/designes").contentType(APPLICATION_JSON)
                .content("{\"utilisateurId\":\"" + uid + "\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/" + dConf).with(comme(U))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/documents/" + dConf + "/designes/" + uid)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/" + dConf).with(comme(U))).andExpect(status().isNotFound());
        assertEquals(2, evenements.stream(AccesDocumentModifie.class)
                .filter(e -> e.documentId().equals(dConf)).count());

        // Dépôt confidentiel : le déposant est désigné, il garde l'accès à son dépôt.
        String cree = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "secret.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("typeDocumentId", tA.toString()).param("confidentialite", "CONFIDENTIEL")
                        .with(comme(U)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID mien = UUID.fromString(om.readTree(cree).get("id").asText());
        mvc.perform(get("/api/v1/documents/" + mien).with(comme(U))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/" + mien + "/designes").with(comme(U)))
                .andExpect(jsonPath("$[0].id").value(uid.toString()));
    }

    @Test
    @DisplayName("Niveau par défaut du type au dépôt, et changement de niveau audité")
    void niveauParDefautEtChangement() throws Exception {
        UUID tConf = jeu.type(a, CONFIDENTIEL);
        String cree = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "d.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("typeDocumentId", tConf.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confidentialite").value("CONFIDENTIEL"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(om.readTree(cree).get("id").asText());
        mvc.perform(put("/api/v1/documents/" + id).contentType(APPLICATION_JSON)
                        .content("{\"confidentialite\":\"PUBLIC\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confidentialite").value("PUBLIC"));
        assertTrue(evenements.stream(AccesDocumentModifie.class).anyMatch(e ->
                e.type().equals(AccesDocumentModifie.CONFIDENTIALITE_MODIFIEE) && "CONFIDENTIEL".equals(e.niveauAvant())
                        && "PUBLIC".equals(e.niveauApres())));
    }

    /* ---------------------------------------------------------------- rattachement */

    @Test
    @DisplayName("Rattachement : droits en union ; retrait de la ligne ; suppression depuis le principal = corbeille")
    void rattachement() throws Exception {
        standardSur(a1);
        mvc.perform(get("/api/v1/documents/" + dRattache).with(comme(U))).andExpect(status().isOk());
        // Retrait du rattachement : le document reste, mais hors du périmètre de U.
        mvc.perform(delete("/api/v1/documents/" + dRattache + "/rattachements/" + a1)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/" + dRattache).with(comme(U))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/" + dRattache)).andExpect(status().isOk())
                .andExpect(jsonPath("$.supprime").value(false));
        // Nouveau rattachement ; refus au nœud principal et en double.
        mvc.perform(post("/api/v1/documents/" + dRattache + "/rattachements").contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + a1 + "\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/documents/" + dRattache + "/rattachements").contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + a1 + "\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/documents/" + dRattache + "/rattachements").contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + b + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/documents/" + dRattache).with(comme(U))).andExpect(status().isOk());
        assertTrue(evenements.stream(AccesDocumentModifie.class).anyMatch(e ->
                e.type().equals("RATTACHEMENT_AJOUTE")));
        assertTrue(evenements.stream(AccesDocumentModifie.class).anyMatch(e ->
                e.type().equals("RATTACHEMENT_RETIRE")));
        // Suppression depuis le principal : invisible de tous ses emplacements.
        mvc.perform(delete("/api/v1/documents/" + dRattache)).andExpect(status().isNoContent());
        assertFalse(documentsVus("/api/v1/documents?size=200").contains(dRattache));
        assertTrue(documentsVus("/api/v1/documents/trashed?size=200").contains(dRattache));
    }

    @Test
    @DisplayName("Rattacher exige l'écriture sur la destination : destination hors périmètre = 404")
    void rattachementSansDroitDestination() throws Exception {
        standardSur(a);
        mvc.perform(post("/api/v1/documents/" + dA + "/rattachements").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + b + "\"}")).andExpect(status().isNotFound());
        jeu.habiliterRole(U, jeu.idRole(Role.UTILISATEUR_STANDARD), null);
        mvc.perform(post("/api/v1/documents/" + dA + "/rattachements").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + b + "\"}")).andExpect(status().isCreated());
    }

    /* ---------------------------------------------------------------- groupes, document isolé, DG */

    @Test
    @DisplayName("Groupe GED : les droits du groupe valent pour ses membres, retrait du membre immédiat")
    void groupe() throws Exception {
        UUID employe = jeu.employeId(U);
        String g = mvc.perform(post("/api/v1/access-groups").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"G-DROITS\",\"name\":\"Groupe droits\",\"userIds\":[\"" + employe + "\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID groupe = UUID.fromString(om.readTree(g).get("id").asText());
        jeu.habiliterGroupe(groupe, Role.UTILISATEUR_STANDARD, b);
        mvc.perform(get("/api/v1/documents/" + dB).with(comme(U))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/access-groups/" + groupe).contentType(APPLICATION_JSON)
                .content("{\"code\":\"G-DROITS\",\"name\":\"Groupe droits\",\"userIds\":[]}")).andExpect(status().isOk());
        standardSur(a);
        mvc.perform(get("/api/v1/documents/" + dB).with(comme(U))).andExpect(status().isNotFound());
        assertTrue(evenements.stream(HabilitationModifiee.class).anyMatch(e ->
                e.objet().equals(HabilitationModifiee.GROUPE_GED) && e.objetId().equals(groupe)));
    }

    @Test
    @DisplayName("Document isolé : une habilitation sur un seul document l'ouvre, sans ouvrir son espace")
    void documentIsole() throws Exception {
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, null, dB);
        mvc.perform(get("/api/v1/documents/" + dB).with(comme(U))).andExpect(status().isOk());
        assertEquals(Set.of(dB), documentsVus("/api/v1/documents?size=200"));
        String arbre = mvc.perform(get("/api/v1/workspaces/tree").with(comme(U))).andReturn().getResponse()
                .getContentAsString();
        assertFalse(arbre.contains(b.toString()));
    }

    @Test
    @DisplayName("Direction Générale : tout voir sans ligne par nœud, privés et confidentiels compris ; pas l'administration")
    void directionGenerale() throws Exception {
        jeu.habiliter(U, Role.DIRECTION_GENERALE, null, null);
        Set<UUID> vus = documentsVus("/api/v1/documents?size=200");
        assertTrue(vus.containsAll(Set.of(dA, dA1, dA2, dB, dPrive, dConf, dRattache)));
        UUID nouveau = jeu.noeud("Espace futur", null);
        mvc.perform(get("/api/v1/workspaces/" + nouveau).with(comme(U))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/roles").with(comme(U))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/etiquettes").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"tag\":\"x\",\"couleur\":\"#000000\"}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ANO-F-002 : la Direction Générale consulte et dépose partout, sans Déplacer, Archiver ni Supprimer")
    void directionGeneraleSansStructuration() throws Exception {
        jeu.habiliter(U, Role.DIRECTION_GENERALE, null, null);
        List<String> structuration = List.of("DEPLACER", "ARCHIVER", "SUPPRIMER");

        // Consultation et dépôt, partout (accès global).
        mvc.perform(get("/api/v1/documents/" + dA1).with(comme(U))).andExpect(status().isOk());
        String cree = mvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "dg.pdf", "application/pdf", com.ipt.ged.support.Pdfs.pdf()))
                        .param("typeDocumentId", tB.toString())
                        .with(comme(U)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID depose = UUID.fromString(om.readTree(cree).get("id").asText());
        mvc.perform(get("/api/v1/documents/" + depose).with(comme(U))).andExpect(status().isOk());

        // Ce que l'interface lit pour afficher les boutons : ni l'identité, ni la fiche du document,
        // ni celle du nœud ne portent une permission de structuration.
        JsonNode moi = json(mvc.perform(get("/api/v1/auth/me").with(comme(U))).andExpect(status().isOk()));
        Set<String> exercees = new HashSet<>();
        moi.get("permissions").forEach(p -> exercees.add(p.asText()));
        assertTrue(exercees.containsAll(List.of("CONSULTER", "DEPOSER", "CONSULTER_AUDIT")), exercees.toString());
        JsonNode fiche = json(mvc.perform(get("/api/v1/documents/" + dA).with(comme(U))).andExpect(status().isOk()));
        JsonNode noeud = json(mvc.perform(get("/api/v1/workspaces/" + a1).with(comme(U))).andExpect(status().isOk()));
        for (String p : structuration) {
            assertFalse(exercees.contains(p), p);
            fiche.get("permissions").forEach(n -> assertNotEquals(p, n.asText()));
            noeud.get("permissions").forEach(n -> assertNotEquals(p, n.asText()));
        }

        // Documents : déplacer, archiver, supprimer, restaurer → 403.
        mvc.perform(patch("/api/v1/documents/" + dA + "/emplacement").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"noeudId\":\"" + a1 + "\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/documents/" + dA + "/archivage").with(comme(U))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/documents/" + dA).with(comme(U))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/documents/" + depose).with(comme(U))).andExpect(status().isForbidden());
        // Nœuds : déplacer, archiver, supprimer, créer un dossier dans un espace métier → 403.
        mvc.perform(patch("/api/v1/workspaces/" + a1 + "/parent").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"parentId\":\"" + b + "\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/workspaces/" + a1 + "/archive").with(comme(U))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/workspaces/" + a1).with(comme(U))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workspaces").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Sous-dossier DG\",\"code\":\"DG-1\",\"parentId\":\"" + a + "\",\"employeId\":\""
                        + jeu.employeId(U) + "\",\"workflowId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
        assertFalse(documents.findById(dA).orElseThrow().isSupprime());
        assertFalse(noeuds.findById(a1).orElseThrow().isSupprime());
    }

    /* ---------------------------------------------------------------- administration */

    @Test
    @DisplayName("Administration : /api/v1/admin/** et écritures de référentiels réservées")
    void administrationReservee() throws Exception {
        standardSur(a);
        for (String url : List.of("/api/v1/admin/roles", "/api/v1/admin/habilitations", "/api/v1/admin/permissions",
                "/api/v1/admin/utilisateurs")) {
            mvc.perform(get(url).with(comme(U))).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/etiquettes").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"tag\":\"x\",\"couleur\":\"#000000\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/access-groups").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"code\":\"G-X\",\"name\":\"X\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/workspaces").with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"X\",\"code\":\"X-1\",\"employeId\":\"" + jeu.employeId(U)
                        + "\",\"workflowId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Attribution et retrait par l'API : effet immédiat, événement avant/après, rôle à une identité sans rôle")
    void attributionImmediate() throws Exception {
        UUID uid = jeu.utilisateurId(U);
        String h = mvc.perform(post("/api/v1/admin/habilitations").contentType(APPLICATION_JSON)
                        .content("{\"sujetType\":\"UTILISATEUR\",\"sujetId\":\"" + uid + "\",\"roleId\":\""
                                + jeu.idRole(Role.UTILISATEUR_STANDARD) + "\",\"noeudId\":\"" + b + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleCode").value(Role.UTILISATEUR_STANDARD))
                .andReturn().getResponse().getContentAsString();
        UUID hid = UUID.fromString(om.readTree(h).get("id").asText());
        mvc.perform(get("/api/v1/documents/" + dB).with(comme(U))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").with(comme(U)))
                .andExpect(jsonPath("$.roles[0]").value(Role.UTILISATEUR_STANDARD))
                .andExpect(jsonPath("$.permissions[?(@ == 'CONSULTER')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'GERER_ROLES_HABILITATIONS')]").doesNotExist());

        mvc.perform(delete("/api/v1/admin/habilitations/" + hid)).andExpect(status().isNoContent());
        standardSur(a);
        mvc.perform(get("/api/v1/documents/" + dB).with(comme(U))).andExpect(status().isNotFound());

        List<HabilitationModifiee> ev = evenements.stream(HabilitationModifiee.class)
                .filter(e -> hid.equals(e.objetId())).toList();
        assertEquals(2, ev.size());
        assertNull(ev.get(0).avant());
        assertEquals(b, ev.get(0).apres().get("noeudId"));
        assertEquals(b, ev.get(1).avant().get("noeudId"));
        assertNull(ev.get(1).apres());
        assertEquals("HABILITATION_MODIFIEE", ev.get(1).action());

        // Doublon refusé.
        jeu.habiliter(U, Role.UTILISATEUR_STANDARD, b, null);
        mvc.perform(post("/api/v1/admin/habilitations").contentType(APPLICATION_JSON)
                .content("{\"sujetType\":\"UTILISATEUR\",\"sujetId\":\"" + uid + "\",\"roleId\":\""
                        + jeu.idRole(Role.UTILISATEUR_STANDARD) + "\",\"noeudId\":\"" + b + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Droits effectifs : permissions et origines (héritage, rattachement, rupture, confidentialité)")
    void droitsEffectifs() throws Exception {
        standardSur(a);
        jeu.rupture(U, a2);
        UUID uid = jeu.utilisateurId(U);
        JsonNode doc = json(mvc.perform(get("/api/v1/admin/droits-effectifs")
                .param("utilisateurId", uid.toString()).param("documentId", dRattache.toString()))
                .andExpect(status().isOk()));
        assertEquals("DOCUMENT", doc.get("cible").asText());
        assertEquals("PUBLIC", doc.get("confidentialite").get("niveau").asText());
        assertTrue(doc.get("confidentialite").get("autorise").asBoolean());
        JsonNode principal = doc.get("emplacements").get(0);
        assertTrue(principal.get("principal").asBoolean());
        assertEquals(0, principal.get("permissions").size());
        JsonNode ratt = doc.get("emplacements").get(1);
        assertEquals(a1.toString(), ratt.get("noeudId").asText());
        assertEquals("HERITAGE", ratt.get("origines").get(0).get("nature").asText());
        assertEquals(a.toString(), ratt.get("origines").get(0).get("noeudAttributionId").asText());
        assertTrue(doc.get("permissions").toString().contains("CONSULTER"));

        JsonNode rompu = json(mvc.perform(get("/api/v1/admin/droits-effectifs")
                .param("utilisateurId", uid.toString()).param("noeudId", a2.toString())));
        assertEquals(0, rompu.get("permissions").size());
        assertEquals("RUPTURE", rompu.get("origines").get(0).get("nature").asText());

        JsonNode conf = json(mvc.perform(get("/api/v1/admin/droits-effectifs")
                .param("utilisateurId", uid.toString()).param("documentId", dConf.toString())));
        assertFalse(conf.get("confidentialite").get("autorise").asBoolean());
        assertEquals("REFUS", conf.get("confidentialite").get("motif").asText());
        assertEquals(0, conf.get("permissions").size());
    }

    @Test
    @DisplayName("Point 9 (E7) : un Administrateur global membre d'un groupe repris garde ses droits ; le lien est au rapport")
    void administrateurMembreDUnGroupeRepris() throws Exception {
        var groupe = new com.ipt.ged.accessgroup.AccessGroup("AG-REPRIS", "Groupe repris");
        groupe.getMembres().add(em.find(com.ipt.ged.identite.Utilisateur.class, jeu.utilisateurId(Comptes.ADMIN)));
        em.persist(groupe);
        em.flush();
        // Ce que la reprise écrit désormais : un lien au rapport, aucune habilitation.
        jdbc.update("INSERT INTO reprise_lien_groupe_espace (groupe_ged_id, noeud_id) VALUES (?, ?)", groupe.getId(), a);

        mvc.perform(get("/api/v1/admin/reprise/liens-groupes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupe == 'Groupe repris')].noeud").value("A"))
                .andExpect(jsonPath("$[?(@.groupe == 'Groupe repris')].habilitationPosee").value(false));
        mvc.perform(delete("/api/v1/documents/" + dA)).andExpect(status().isNoContent());
    }

    /* ---------------------------------------------------------------- points d'extension */

    @Test
    @DisplayName("PredicatDroits (recherche) : le fragment SQL ne laisse passer que le périmètre")
    void predicatSqlDeRecherche() {
        standardSur(a1);
        em.flush();
        FragmentSql f = predicatDroits.predicat("d.id", authentification(U));
        NamedParameterJdbcTemplate nomme = new NamedParameterJdbcTemplate(jdbc);
        List<UUID> ids = nomme.queryForList("SELECT d.id FROM document d WHERE d.id IN (:candidats) AND " + f.sql(),
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(f.parametres())
                        .addValue("candidats", List.of(dA, dA1, dA2, dB, dPrive, dConf, dRattache)), UUID.class);
        assertEquals(Set.of(dA1, dRattache), new HashSet<>(ids));
        assertEquals(FragmentSql.FAUX, predicatDroits.predicat("d.id", null));
    }

    @Test
    @DisplayName("Prévisualisation : même décision que le téléchargement, 404 hors périmètre")
    void previsualisation() {
        standardSur(a);
        Authentication u = authentification(U);
        ResolveurFichierVersion.FichierVersion horsPerimetre = new ResolveurFichierVersion.FichierVersion(
                UUID.randomUUID(), dB, UUID.randomUUID(), "application/pdf", "b.pdf");
        ErreurFichierException ex = assertThrows(ErreurFichierException.class,
                () -> controlePrevisualisation.verifierLecture(horsPerimetre, u));
        assertEquals(404, ex.statut().value());
        assertDoesNotThrow(() -> controlePrevisualisation.verifierLecture(new ResolveurFichierVersion.FichierVersion(
                UUID.randomUUID(), dA, UUID.randomUUID(), "application/pdf", "a.pdf"), u));
    }

    @Test
    @DisplayName("Composition d'un rôle : modifiée depuis l'API, effective immédiatement ; Administrateur verrouillé")
    void compositionDeRole() throws Exception {
        String r = mvc.perform(post("/api/v1/admin/roles").contentType(APPLICATION_JSON)
                        .content("{\"code\":\"LECTEUR_ESSAI\",\"libelle\":\"Lecteur\",\"permissions\":[\"CONSULTER\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID role = UUID.fromString(om.readTree(r).get("id").asText());
        jeu.habiliterRole(U, role, a);
        mvc.perform(put("/api/v1/documents/" + dA).with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/roles/" + role).contentType(APPLICATION_JSON)
                        .content("{\"libelle\":\"Lecteur\",\"permissions\":[\"CONSULTER\",\"MODIFIER\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/documents/" + dA).with(comme(U)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/roles/" + jeu.idRole(Role.ADMINISTRATEUR)).contentType(APPLICATION_JSON)
                .content("{\"permissions\":[\"CONSULTER\"]}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/admin/roles/" + role)).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/admin/roles/" + jeu.idRole(Role.AGENT_ARCHIVE))).andExpect(status().isBadRequest());
        assertTrue(evenements.stream(HabilitationModifiee.class).anyMatch(e ->
                e.objet().equals(HabilitationModifiee.ROLE) && role.equals(e.objetId())
                        && e.operation().equals(HabilitationModifiee.MODIFICATION)));
    }
}
