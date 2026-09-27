package com.ipt.ged.identite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.annuaire.AnnuaireEmbarque;
import com.ipt.ged.identite.evenement.ConnexionEchouee;
import com.ipt.ged.identite.evenement.ConnexionReussie;
import com.ipt.ged.identite.evenement.MotifEchecConnexion;
import com.ipt.ged.identite.evenement.SessionsRevoquees;
import com.ipt.ged.identite.session.MotifRevocation;
import com.ipt.ged.support.Comptes;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parcours complet d'authentification par l'annuaire et de gestion des
 * sessions, de bout en bout par l'API (dossier technique §3.3, §3.4 ; décisions
 * D1 à D4, R26). L'annuaire est le simulateur UnboundID du profil de test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class AuthentificationApiTest {

    private static final String COOKIE = "ged_renouvellement";
    private static final String CSRF = "X-GED-Renouvellement";
    private static final String GUID_SARA = "5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e01";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private LimiteurConnexions limiteur;
    @Autowired private EmployeRepository employes;
    @Autowired private UtilisateurRepository utilisateurs;
    @Autowired private AnnuaireEmbarque annuaireEmbarque;
    @Autowired private ApplicationEvents evenements;

    private int ip;

    @BeforeEach
    void reinitialiser() {
        limiteur.vider();
    }

    /** Chaque connexion part d'une IP différente, sauf quand le test vise la limite par IP. */
    private String ipSuivante() {
        return "10.20.0." + (++ip);
    }

    private MvcResult connexion(String identifiant, String motDePasse, String adresse) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr(adresse); return r; })
                        .content(om.writeValueAsString(java.util.Map.of(
                                "identifiant", identifiant, "motDePasse", motDePasse))))
                .andReturn();
    }

    /** Connexion réussie : jeton d'accès et cookie de renouvellement. */
    private Session ouvrir(String identifiant) throws Exception {
        MvcResult r = connexion(identifiant, Comptes.MOT_DE_PASSE, ipSuivante());
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return session(r);
    }

    private Session session(MvcResult r) throws Exception {
        JsonNode corps = om.readTree(r.getResponse().getContentAsString());
        Cookie c = r.getResponse().getCookie(COOKIE);
        assertNotNull(c, "cookie de renouvellement");
        return new Session(corps.get("token").asText(), c.getValue(),
                UUID.fromString(corps.get("utilisateur").get("id").asText()), corps);
    }

    private record Session(String jeton, String renouvellement, UUID utilisateurId, JsonNode corps) {
        String bearer() { return "Bearer " + jeton; }
    }

    private MvcResult renouveler(String cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").header(CSRF, "1").cookie(new Cookie(COOKIE, cookie)))
                .andReturn();
    }

    /* ================================================================== connexion */

    @Test
    @DisplayName("Connexion : jeton d'accès, cookie HttpOnly/Secure/SameSite=Strict, identité provisionnée, cache 15 min")
    void connexionReussie() throws Exception {
        MvcResult r = connexion(Comptes.ADMIN, Comptes.MOT_DE_PASSE, ipSuivante());
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));

        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertTrue(setCookie.startsWith(COOKIE + "="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/auth"), setCookie);
        assertTrue(setCookie.contains("Max-Age=28"), "durée absolue 8 h : " + setCookie);

        Session s = session(r);
        // Le jeton de renouvellement ne figure jamais dans le corps.
        assertFalse(r.getResponse().getContentAsString().contains(s.renouvellement()));
        assertEquals("Bearer", s.corps().get("tokenType").asText());
        assertEquals(900, s.corps().get("expiresIn").asLong());
        assertEquals("sbennani", s.corps().get("utilisateur").get("identifiant").asText());
        assertEquals("sara.bennani@marchica.ma", s.corps().get("utilisateur").get("email").asText());
        assertEquals("ADMINISTRATEUR", s.corps().get("utilisateur").get("roles").get(0).asText());
        // Rattachée à la fiche employé reprise (courriel dérivé identique).
        assertEquals(Comptes.idAdmin(employes).toString(), s.corps().get("utilisateur").get("employeId").asText());

        em.flush();
        assertEquals(GUID_SARA, jdbc.queryForObject(
                "SELECT object_guid::text FROM utilisateur WHERE id = ?", String.class, s.utilisateurId()));
        // Seule l'empreinte du jeton est stockée.
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM session WHERE utilisateur_id = ?",
                Integer.class, s.utilisateurId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM session WHERE empreinte = ?",
                Integer.class, s.renouvellement()));
        Duration cache = Duration.ofSeconds(jdbc.queryForObject(
                "SELECT extract(epoch FROM expire_le - lu_le)::int FROM cache_annuaire WHERE utilisateur_id = ?",
                Integer.class, s.utilisateurId()));
        assertEquals(Duration.ofMinutes(15), cache);

        mvc.perform(get("/api/v1/documents").header("Authorization", s.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifiant").value("sbennani"))
                .andExpect(jsonPath("$.direction").value("Direction Financière"));

        assertEquals(1, evenements.stream(ConnexionReussie.class)
                .filter(e -> e.utilisateurId().equals(s.utilisateurId())).count());
    }

    @Test
    @DisplayName("Le jeton d'accès n'est accepté que dans l'en-tête Authorization (ni cookie ni paramètre)")
    void jetonDansLEnteteSeulement() throws Exception {
        Session s = ouvrir(Comptes.ADMIN);
        mvc.perform(get("/api/v1/documents").param("access_token", s.jeton())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/documents").cookie(new Cookie("Authorization", s.bearer())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/documents").header("Authorization", s.jeton())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refus : mot de passe faux, compte désactivé dans l'annuaire (D1), inconnu — même réponse, événement d'échec")
    void refus(CapturedOutput sortie) throws Exception {
        String secret = "Tr3s-S3cret-" + UUID.randomUUID();
        for (String identifiant : new String[]{Comptes.ADMIN, Comptes.DESACTIVE, "inconnu"}) {
            MvcResult r = connexion(identifiant, identifiant.equals(Comptes.DESACTIVE) ? Comptes.MOT_DE_PASSE : secret,
                    ipSuivante());
            assertEquals(401, r.getResponse().getStatus(), identifiant);
            JsonNode corps = om.readTree(r.getResponse().getContentAsString());
            assertEquals("IDENTIFIANTS_REFUSES", corps.get("code").asText());
            assertEquals("Identifiant ou mot de passe incorrect.", corps.get("detail").asText());
            assertTrue(r.getResponse().getCookie(COOKIE) == null, "aucun cookie sur un refus");
        }
        assertEquals(3, evenements.stream(ConnexionEchouee.class)
                .filter(e -> e.motifEchec() == MotifEchecConnexion.IDENTIFIANTS_REFUSES).count());
        // Un compte désactivé ne crée aucune identité.
        assertTrue(utilisateurs.findByIdentifiant(Comptes.DESACTIVE).isEmpty());
        // Le mot de passe n'est jamais journalisé.
        assertFalse(sortie.getAll().contains(secret), "mot de passe dans les journaux");
    }

    @Test
    @DisplayName("D2 : une adresse e-mail ou un userPrincipalName comme identifiant est refusé (400), sans appel à l'annuaire")
    void pasDeConnexionParCourriel() throws Exception {
        for (String identifiant : new String[]{"sara.bennani@marchica.ma", "sbennani@marchicamed.ma"}) {
            MvcResult r = connexion(identifiant, Comptes.MOT_DE_PASSE, ipSuivante());
            assertEquals(400, r.getResponse().getStatus());
            assertTrue(r.getResponse().getContentAsString().contains("identifiant Windows"));
        }
        assertEquals(0, evenements.stream(ConnexionEchouee.class).count());
    }

    @Test
    @DisplayName("Provisionnement automatique SANS rôle : /auth/me répond, tout le reste 403 ; pas de doublon à la reconnexion")
    void provisionnementSansRole() throws Exception {
        Session s = ouvrir(Comptes.SANS_ROLE);
        assertTrue(s.corps().get("utilisateur").get("roles").isEmpty());
        assertTrue(evenements.stream(ConnexionReussie.class).anyMatch(ConnexionReussie::premiereConnexion));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", empty()))
                .andExpect(jsonPath("$.fullName").value("Nadia Idrissi"));
        mvc.perform(get("/api/v1/documents").header("Authorization", s.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/utilisateurs").header("Authorization", s.bearer()))
                .andExpect(status().isForbidden());

        // Aucune fiche employé ne correspondait : elle est créée depuis l'annuaire.
        assertTrue(employes.findAll().stream().anyMatch(e -> e.getFullName().equals("Nadia Idrissi") && e.isHasUser()));

        Session deuxieme = ouvrir(Comptes.SANS_ROLE);
        assertEquals(s.utilisateurId(), deuxieme.utilisateurId());
        em.flush();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM utilisateur WHERE lower(identifiant) = 'nidrissi'",
                Integer.class));
    }

    @Test
    @DisplayName("Changement de login dans l'annuaire : même identité (objectGUID), identifiant mis à jour")
    void renommage() throws Exception {
        Session avant = ouvrir(Comptes.TROISIEME_ACTEUR);
        String dn = "CN=Yasmine Alaoui,OU=Utilisateurs,DC=marchicamed,DC=ma";
        var serveur = annuaireEmbarque.simulateur().serveur();
        serveur.modify(dn, new Modification(ModificationType.REPLACE, "sAMAccountName", "yalaoui.renomme"));
        try {
            Session apres = ouvrir("yalaoui.renomme");
            assertEquals(avant.utilisateurId(), apres.utilisateurId());
            assertEquals("yalaoui.renomme", apres.corps().get("utilisateur").get("identifiant").asText());
            em.flush();
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM utilisateur WHERE object_guid = ?::uuid",
                    Integer.class, "5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e03"));
        } finally {
            serveur.modify(dn, new Modification(ModificationType.REPLACE, "sAMAccountName", Comptes.TROISIEME_ACTEUR));
        }
    }

    /* ================================================================== renouvellement */

    @Test
    @DisplayName("Renouvellement : en-tête CSRF exigé, rotation à chaque usage, réutilisation = famille révoquée")
    void rotationEtReutilisation() throws Exception {
        Session s = ouvrir(Comptes.ADMIN);

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, s.renouvellement())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTETE_CSRF_MANQUANT"));
        mvc.perform(post("/api/v1/auth/refresh").header(CSRF, "1")).andExpect(status().isUnauthorized());

        MvcResult r = renouveler(s.renouvellement());
        assertEquals(200, r.getResponse().getStatus());
        Session renouvelee = session(r);
        assertNotEquals(s.renouvellement(), renouvelee.renouvellement());
        assertNotEquals(s.jeton(), renouvelee.jeton());
        mvc.perform(get("/api/v1/documents").header("Authorization", renouvelee.bearer())).andExpect(status().isOk());

        // Jeton déjà consommé présenté à nouveau : vol présumé, toute la famille tombe.
        assertEquals(401, renouveler(s.renouvellement()).getResponse().getStatus());
        assertEquals(401, renouveler(renouvelee.renouvellement()).getResponse().getStatus());
        mvc.perform(get("/api/v1/documents").header("Authorization", renouvelee.bearer()))
                .andExpect(status().isUnauthorized());
        assertEquals(1, evenements.stream(SessionsRevoquees.class)
                .filter(e -> e.motifRevocation() == MotifRevocation.REUTILISATION).count());
    }

    @Test
    @DisplayName("Inactivité de plus de 30 min ou durée absolue dépassée : renouvellement refusé, session close")
    void inactiviteEtDureeAbsolue() throws Exception {
        Session inactive = ouvrir(Comptes.ADMIN);
        em.flush();
        jdbc.update("UPDATE session SET derniere_activite_le = now() - interval '31 minutes' WHERE utilisateur_id = ?",
                inactive.utilisateurId());
        em.clear();
        assertEquals(401, renouveler(inactive.renouvellement()).getResponse().getStatus());
        em.flush();
        assertEquals("INACTIVITE", jdbc.queryForObject(
                "SELECT motif_revocation FROM session WHERE utilisateur_id = ? LIMIT 1", String.class,
                inactive.utilisateurId()));

        Session perimee = ouvrir(Comptes.ADMIN);
        em.flush();
        jdbc.update("UPDATE session SET expire_le = now() - interval '1 second' WHERE revoquee_le IS NULL"
                + " AND utilisateur_id = ?", perimee.utilisateurId());
        em.clear();
        assertEquals(401, renouveler(perimee.renouvellement()).getResponse().getStatus());
        mvc.perform(get("/api/v1/documents").header("Authorization", perimee.bearer()))
                .andExpect(status().isUnauthorized());
    }

    /* ================================================================== fin de session */

    @Test
    @DisplayName("Déconnexion : session révoquée, jeton d'accès aussitôt refusé, cookie effacé")
    void deconnexion() throws Exception {
        Session s = ouvrir(Comptes.ADMIN);
        MvcResult r = mvc.perform(post("/api/v1/auth/logout").header("Authorization", s.bearer())
                        .header(CSRF, "1").cookie(new Cookie(COOKIE, s.renouvellement())))
                .andExpect(status().isNoContent())
                .andReturn();
        assertTrue(r.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
        mvc.perform(get("/api/v1/documents").header("Authorization", s.bearer())).andExpect(status().isUnauthorized());
        assertEquals(401, renouveler(s.renouvellement()).getResponse().getStatus());
        // ANO-E4-003 : la déconnexion est tracée comme telle (code DECONNEXION du catalogue).
        assertTrue(evenements.stream(SessionsRevoquees.class).anyMatch(e ->
                e.motifRevocation() == MotifRevocation.DECONNEXION && "DECONNEXION".equals(e.action())
                        && com.ipt.ged.audit.ActionAudit.DECONNEXION.code().equals(e.action())));

        // Déconnexion par cookie sans l'en-tête personnalisé : refusée (CSRF).
        Session autre = ouvrir(Comptes.ADMIN);
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, autre.renouvellement())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R26 : l'Administrateur révoque toutes les sessions d'un utilisateur, effet immédiat")
    void revocationParLAdministrateur() throws Exception {
        Session karim1 = ouvrir(Comptes.SECOND_ACTEUR);
        Session karim2 = ouvrir(Comptes.SECOND_ACTEUR);
        Session admin = ouvrir(Comptes.ADMIN);

        mvc.perform(get("/api/v1/admin/utilisateurs").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.identifiant == 'kelfassi')].sessionsOuvertes", hasItem(2)));
        mvc.perform(get("/api/v1/admin/utilisateurs/" + karim1.utilisateurId() + "/sessions")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Un non-administrateur ne peut pas révoquer.
        mvc.perform(delete("/api/v1/admin/utilisateurs/" + admin.utilisateurId() + "/sessions")
                        .header("Authorization", karim1.bearer()))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/admin/utilisateurs/" + karim1.utilisateurId() + "/sessions")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionsFermees").value(2));

        for (Session k : new Session[]{karim1, karim2}) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", k.bearer())).andExpect(status().isUnauthorized());
            assertEquals(401, renouveler(k.renouvellement()).getResponse().getStatus());
        }
        mvc.perform(get("/api/v1/auth/me").header("Authorization", admin.bearer())).andExpect(status().isOk());
        assertTrue(evenements.stream(SessionsRevoquees.class).anyMatch(e ->
                e.motifRevocation() == MotifRevocation.REVOCATION_ADMINISTRATEUR
                        && e.parUtilisateurId().equals(admin.utilisateurId())));

        mvc.perform(delete("/api/v1/admin/utilisateurs/" + UUID.randomUUID() + "/sessions")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isNotFound());
    }

    /* ================================================================== force brute */

    @Test
    @DisplayName("5 tentatives par minute et par identifiant : la 6e répond 429 avec Retry-After")
    void limiteParIdentifiant() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(401, connexion(Comptes.ADMIN, "faux", ipSuivante()).getResponse().getStatus());
        }
        MvcResult r = connexion(Comptes.ADMIN, Comptes.MOT_DE_PASSE, ipSuivante());
        assertEquals(429, r.getResponse().getStatus());
        long attente = Long.parseLong(r.getResponse().getHeader("Retry-After"));
        assertTrue(attente >= 1 && attente <= 60, "Retry-After = " + attente);
        assertEquals("TROP_DE_TENTATIVES", om.readTree(r.getResponse().getContentAsString()).get("code").asText());
        assertEquals(1, evenements.stream(ConnexionEchouee.class)
                .filter(e -> e.motifEchec() == MotifEchecConnexion.TROP_DE_TENTATIVES).count());
    }

    @Test
    @DisplayName("5 tentatives par minute et par adresse IP, quel que soit l'identifiant visé")
    void limiteParAdresse() throws Exception {
        String adresse = "10.30.0.1";
        for (int i = 0; i < 5; i++) {
            assertEquals(401, connexion("inconnu" + i, "faux", adresse).getResponse().getStatus());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr(adresse); return r; })
                        .content("{\"identifiant\":\"sbennani\",\"motDePasse\":\"" + Comptes.MOT_DE_PASSE + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail", containsString("Réessayez")));
        // Une autre adresse n'est pas concernée.
        assertEquals(200, connexion(Comptes.ADMIN, Comptes.MOT_DE_PASSE, "10.30.0.2").getResponse().getStatus());
    }

    @Test
    @DisplayName("Rôles relus à chaque requête : un rôle retiré prend effet sans nouvelle connexion")
    void rolesRelusAChaqueRequete() throws Exception {
        Session s = ouvrir(Comptes.ADMIN);
        mvc.perform(get("/api/v1/documents").header("Authorization", s.bearer())).andExpect(status().isOk());
        em.flush();
        jdbc.update("DELETE FROM habilitation WHERE utilisateur_id = ?", s.utilisateurId());
        jdbc.update("UPDATE version_habilitations SET valeur = valeur + 1");
        em.clear();
        mvc.perform(get("/api/v1/documents").header("Authorization", s.bearer())).andExpect(status().isForbidden());
        assertTrue(Instant.now().isAfter(Instant.EPOCH));
    }
}
