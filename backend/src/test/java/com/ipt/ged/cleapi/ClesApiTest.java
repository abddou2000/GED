package com.ipt.ged.cleapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.support.Comptes;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cycle de vie des clés d'API et authentification des applications (DAT §5.4),
 * de bout en bout : l'administrateur crée l'application et sa clé, l'application
 * appelle l'API avec {@code X-API-Key}, chaque étape est tracée.
 *
 * <p>Les appels de l'application sont faits en anonyme ({@code with(anonymous())}) :
 * elle ne porte que sa clé, jamais la session d'un utilisateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class ClesApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrometheusMeterRegistry prometheus;

    private record AppEtCle(UUID applicationId, String code, UUID cleId, String cle) {}

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private AppEtCle creer(String adresses, int quotaMinute, boolean delegation) throws Exception {
        String code = "app-" + UUID.randomUUID().toString().substring(0, 8);
        String corps = "{\"code\":\"" + code + "\",\"nom\":\"Bureau d'ordre\",\"quotaMinute\":" + quotaMinute
                + ",\"quotaJour\":1000" + (adresses != null ? ",\"adressesAutorisees\":[" + adresses + "]" : "") + "}";
        UUID appId = UUID.fromString(json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                .content(corps)).andExpect(status().isCreated()).andReturn()).get("id").asText());
        JsonNode g = json(mvc.perform(post("/api/v1/applications/" + appId + "/cles").contentType(APPLICATION_JSON)
                        .content("{\"delegation\":" + delegation + "}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn());
        return new AppEtCle(appId, code, UUID.fromString(g.get("details").get("id").asText()), g.get("cle").asText());
    }

    private int appeler(String cle) throws Exception {
        return mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, cle))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("Génération : format ged_<env>_<id>_<secret>, secret affiché une seule fois, empreinte seule en base")
    void generation() throws Exception {
        AppEtCle a = creer(null, 600, false);
        assertThat(a.cle()).matches("^ged_dev_[0-9a-f]{16}_[0-9a-f]{64}$");
        String secret = a.cle().substring(a.cle().lastIndexOf('_') + 1);

        String consultation = mvc.perform(get("/api/v1/applications/" + a.applicationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cles[0].etat").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(consultation).doesNotContain(secret).doesNotContain(FormatCleApi.empreinte(secret));

        Map<String, Object> ligne = jdbc.queryForMap("SELECT empreinte, expire_le, cree_le FROM cle_api WHERE id = ?", a.cleId());
        assertThat(((String) ligne.get("empreinte")).trim()).isEqualTo(FormatCleApi.empreinte(secret));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cle_api WHERE empreinte LIKE ?", Long.class, "%" + secret + "%"))
                .isZero();
        // Validité par défaut : 12 mois.
        Duration validite = Duration.between(((Timestamp) ligne.get("cree_le")).toInstant(),
                ((Timestamp) ligne.get("expire_le")).toInstant());
        assertThat(validite).isEqualTo(Duration.ofDays(365));

        // Traces d'administration, sans le secret.
        List<String> traces = jdbc.queryForList("SELECT action || ' ' || coalesce(apres::text, '') FROM journal_audit"
                + " WHERE objet_id = ? ORDER BY id", String.class, a.applicationId());
        assertThat(traces).anySatisfy(t -> assertThat(t).startsWith("APPLICATION_CREEE"))
                .anySatisfy(t -> assertThat(t).startsWith("CLE_API_GENEREE").contains(a.cle().split("_")[2]));
        assertThat(String.join(" ", traces)).doesNotContain(secret);
    }

    @Test
    @DisplayName("Appel authentifié par X-API-Key : l'application est le sujet, l'appel est tracé à son nom")
    void appelAuthentifie() throws Exception {
        AppEtCle a = creer(null, 600, false);
        assertThat(appeler(a.cle())).isEqualTo(200);

        Map<String, Object> trace = jdbc.queryForMap("SELECT acteur_application_id, acteur_nom, apres::text AS apres"
                + " FROM journal_audit WHERE action = 'APPEL_API' AND objet_id = ? ORDER BY id DESC LIMIT 1", a.applicationId());
        assertThat(trace.get("acteur_application_id")).isEqualTo(a.applicationId());
        assertThat(trace.get("acteur_nom")).isEqualTo("application:" + a.code());
        assertThat((String) trace.get("apres")).contains("/api/v1/etiquettes").contains("200");
    }

    @Test
    @DisplayName("Métrique : appels par application et par clé à /actuator/prometheus, jamais le secret (ANO-E10-002)")
    void metriqueAppelsParCle() throws Exception {
        AppEtCle a = creer(null, 600, false);
        AppEtCle filtree = creer("\"10.9.9.0/24\"", 600, false);
        assertThat(appeler(a.cle())).isEqualTo(200);
        assertThat(appeler(a.cle())).isEqualTo(200);
        assertThat(appeler(filtree.cle())).isEqualTo(403);
        String inconnue = FormatCleApi.generer("dev").valeur();
        assertThat(appeler(inconnue)).isEqualTo(401);

        String id = a.cle().split("_")[2];
        List<String> lignes = prometheus.scrape().lines().filter(l -> l.startsWith("ged_api_appels_total{")).toList();
        assertThat(lignes).anySatisfy(l -> assertThat(l).contains("application=\"" + a.code() + "\"")
                .contains("cle=\"" + id + "\"").contains("resultat=\"accepte\"").contains("statut=\"200\"")
                .endsWith(" 2.0"));
        assertThat(lignes).anySatisfy(l -> assertThat(l).contains("application=\"" + filtree.code() + "\"")
                .contains("cle=\"" + filtree.cle().split("_")[2] + "\"").contains("resultat=\"refuse\"")
                .contains("statut=\"403\""));
        // Clé inconnue : comptée sans que l'identifiant présenté devienne une étiquette.
        assertThat(lignes).anySatisfy(l -> assertThat(l).contains("application=\"inconnue\"")
                .contains("cle=\"inconnue\"").contains("statut=\"401\""));
        String tout = String.join("\n", lignes);
        assertThat(tout).doesNotContain(inconnue.split("_")[2])
                .doesNotContain(a.cle().substring(a.cle().lastIndexOf('_') + 1));
    }

    @Test
    @DisplayName("Clé invalide, inconnue ou mal formée : 401 problem+json CLE_API_INVALIDE, refus tracé")
    void cleInvalide() throws Exception {
        AppEtCle a = creer(null, 600, false);
        String faux = a.cle().substring(0, a.cle().length() - 1) + (a.cle().endsWith("0") ? "1" : "0");
        for (String cle : List.of(faux, "n'importe quoi", FormatCleApi.generer("dev").valeur())) {
            mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, cle))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                    .andExpect(jsonPath("$.code").value(CodesErreurCleApi.CLE_API_INVALIDE));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'CLE_API_REFUSEE'"
                + " AND acteur_nom = ?", Long.class, "cle:" + a.cle().split("_")[2])).isPositive();
    }

    @Test
    @DisplayName("Une application ne peut ni administrer les clés ni consulter l'audit")
    void administrationReservee() throws Exception {
        AppEtCle a = creer(null, 600, false);
        for (String chemin : List.of("/api/v1/applications", "/api/v1/audit/evenements", "/api/v1/notifications")) {
            mvc.perform(get(chemin).with(anonymous()).header(FiltreCleApi.ENTETE_CLE, a.cle()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCES_REFUSE"));
        }
    }

    @Test
    @DisplayName("Révocation : la clé est refusée aussitôt (CLE_API_REVOQUEE)")
    void revocation() throws Exception {
        AppEtCle a = creer(null, 600, false);
        mvc.perform(post("/api/v1/cles-api/" + a.cleId() + "/revocation").contentType(APPLICATION_JSON)
                        .content("{\"motif\":\"Clé exposée dans un journal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.etat").value("REVOQUEE"));
        mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, a.cle()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.CLE_API_REVOQUEE));
    }

    @Test
    @DisplayName("Régénération : nouvelle clé active, ancienne valide 7 jours (chevauchement)")
    void regeneration() throws Exception {
        AppEtCle a = creer(null, 600, false);
        JsonNode r = json(mvc.perform(post("/api/v1/cles-api/" + a.cleId() + "/regeneration"))
                .andExpect(status().isCreated()).andReturn());
        String nouvelle = r.get("cle").asText();
        assertThat(nouvelle).isNotEqualTo(a.cle());
        assertThat(appeler(nouvelle)).isEqualTo(200);
        assertThat(appeler(a.cle())).isEqualTo(200);

        Instant expire = jdbc.queryForObject("SELECT expire_le FROM cle_api WHERE id = ?", Timestamp.class, a.cleId()).toInstant();
        assertThat(Duration.between(Instant.now(), expire)).isBetween(Duration.ofDays(6), Duration.ofDays(7));
        // Une clé déjà remplacée ne se régénère pas une seconde fois.
        mvc.perform(post("/api/v1/cles-api/" + a.cleId() + "/regeneration"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.CLE_API_NON_REGENERABLE));
    }

    @Test
    @DisplayName("Clé expirée : 401 CLE_API_EXPIREE ; clé proche de l'expiration signalée")
    void expiration() throws Exception {
        AppEtCle a = creer(null, 600, false);
        jdbc.update("UPDATE cle_api SET expire_le = now() + interval '10 days' WHERE id = ?", a.cleId());
        mvc.perform(get("/api/v1/applications/" + a.applicationId()))
                .andExpect(jsonPath("$.cles[0].expireBientot").value(true))
                .andExpect(jsonPath("$.clesExpirantBientot").value(1));
        jdbc.update("UPDATE cle_api SET cree_le = now() - interval '2 days', expire_le = now() - interval '1 second'"
                + " WHERE id = ?", a.cleId());
        mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, a.cle()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.CLE_API_EXPIREE));
    }

    @Test
    @DisplayName("Adresses autorisées : adresse hors liste refusée (403), adresse de la liste acceptée")
    void adresses() throws Exception {
        AppEtCle refusee = creer("\"10.9.9.0/24\"", 600, false);
        mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, refusee.cle()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.ADRESSE_NON_AUTORISEE));
        AppEtCle acceptee = creer("\"127.0.0.1\"", 600, false);
        assertThat(appeler(acceptee.cle())).isEqualTo(200);
    }

    @Test
    @DisplayName("Quota par minute dépassé : 429 avec Retry-After, dépassement tracé")
    void quota() throws Exception {
        AppEtCle a = creer(null, 2, false);
        assertThat(appeler(a.cle())).isEqualTo(200);
        assertThat(appeler(a.cle())).isEqualTo(200);
        MvcResult r = mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, a.cle()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.QUOTA_MINUTE_DEPASSE))
                .andReturn();
        long attente = Long.parseLong(r.getResponse().getHeader("Retry-After"));
        assertThat(attente).isBetween(1L, 60L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_audit WHERE action = 'QUOTA_DEPASSE'"
                + " AND acteur_application_id = ?", Long.class, a.applicationId())).isPositive();
    }

    @Test
    @DisplayName("Délégation : sans l'attribut 403 ; avec l'attribut, identité non vérifiable 422 (échec fermé)")
    void delegation() throws Exception {
        AppEtCle sans = creer(null, 600, false);
        mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, sans.cle())
                        .header(FiltreCleApi.ENTETE_DELEGATION, "a.benali"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.DELEGATION_NON_AUTORISEE));

        // Délégation sans liste d'adresses : refusée à la génération (§5.4).
        String code = "app-" + UUID.randomUUID().toString().substring(0, 8);
        UUID appId = UUID.fromString(json(mvc.perform(post("/api/v1/applications").contentType(APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"nom\":\"Sans adresses\"}")).andReturn()).get("id").asText());
        mvc.perform(post("/api/v1/applications/" + appId + "/cles").contentType(APPLICATION_JSON)
                        .content("{\"delegation\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.DELEGATION_SANS_ADRESSES));

        AppEtCle avec = creer("\"127.0.0.1\"", 600, true);
        mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, avec.cle())
                        .header(FiltreCleApi.ENTETE_DELEGATION, "a.benali"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.IDENTITE_DELEGUEE_INVALIDE));
    }

    @Test
    @DisplayName("Application désactivée : ses clés sont refusées (403)")
    void desactivation() throws Exception {
        AppEtCle a = creer(null, 600, false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/applications/" + a.applicationId() + "/activation")
                        .contentType(APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/etiquettes").with(anonymous()).header(FiltreCleApi.ENTETE_CLE, a.cle()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(CodesErreurCleApi.APPLICATION_DESACTIVEE));
    }
}
