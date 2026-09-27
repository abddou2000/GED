package com.ipt.ged.documentationapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.conventionsapi.ProprietesConventionsApi;
import com.ipt.ged.idempotence.ProprietesIdempotence;
import com.ipt.ged.support.Comptes;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spécification OpenAPI 3 complète (T-053, DAT §5.3) : chaque charge utile
 * décrite champ par champ avec exemple, erreurs problem+json par opération,
 * en-têtes de l'API d'intégration, sécurité, documentation fermée en production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class SpecificationOpenApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private EnrichissementOpenApi enrichissement;

    private JsonNode spec;

    @BeforeEach
    void charger() throws Exception {
        spec = om.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Map.Entry<String, JsonNode>> operations() {
        List<Map.Entry<String, JsonNode>> ops = new ArrayList<>();
        spec.get("paths").fields().forEachRemaining(p -> p.getValue().fields()
                .forEachRemaining(m -> ops.add(Map.entry(m.getKey().toUpperCase() + " " + p.getKey(), m.getValue()))));
        return ops;
    }

    private JsonNode operation(String methode, String chemin) {
        return spec.get("paths").get(chemin).get(methode.toLowerCase());
    }

    private static List<String> noms(JsonNode tableau, String champ) {
        List<String> l = new ArrayList<>();
        if (tableau != null) tableau.forEach(n -> l.add(n.has(champ) ? n.get(champ).asText() : ""));
        return l;
    }

    @Test
    @DisplayName("Charges utiles : chaque schéma et chaque champ décrits, chaque valeur simple avec un exemple")
    void chargesUtilesDecrites() {
        assertThat(enrichissement.manques())
                .as("Éléments à décrire dans documentationapi/champs.yml (objets, champs, surcharges, parametres, corps)")
                .isEmpty();
        List<String> sansDescription = new ArrayList<>();
        List<String> sansExemple = new ArrayList<>();
        spec.get("components").get("schemas").fields().forEachRemaining(s -> {
            if (!s.getValue().has("description")) sansDescription.add(s.getKey());
            JsonNode proprietes = s.getValue().get("properties");
            if (proprietes == null) return;
            proprietes.fields().forEachRemaining(p -> {
                JsonNode v = p.getValue();
                String nom = s.getKey() + "." + p.getKey();
                if (!v.has("description")) sansDescription.add(nom);
                boolean simple = v.has("type") && !"object".equals(v.get("type").asText())
                        && !(v.has("items") && (v.get("items").has("$ref")
                        || "object".equals(v.get("items").path("type").asText())));
                if (simple && !v.has("example") && !v.has("enum")) sansExemple.add(nom);
            });
        });
        assertThat(sansDescription).isEmpty();
        assertThat(sansExemple).isEmpty();
        operations().forEach(o -> {
            JsonNode params = o.getValue().get("parameters");
            if (params != null) params.forEach(p -> {
                if (!p.has("$ref")) assertThat(p.has("description")).as(o.getKey() + " " + p.get("name")).isTrue();
            });
        });
    }

    @Test
    @DisplayName("Erreurs : problem+json avec codes et exemples sur chaque opération ; 401, 403, 404, 429 là où ils s'appliquent")
    void erreursProblemJson() {
        for (Map.Entry<String, JsonNode> o : operations()) {
            JsonNode reponses = o.getValue().get("responses");
            assertThat(reponses.has("500")).as(o.getKey()).isTrue();
            assertThat(reponses.has("401")).as(o.getKey()).isTrue();
            if (!o.getKey().endsWith("/api/v1/auth/login")) {
                assertThat(reponses.has("403")).as(o.getKey()).isTrue();
            }
            if (o.getKey().contains("{")) assertThat(reponses.has("404")).as(o.getKey()).isTrue();
            reponses.fields().forEachRemaining(r -> {
                int statut = Integer.parseInt(r.getKey());
                if (statut < 400) return;
                JsonNode media = r.getValue().get("content").get("application/problem+json");
                assertThat(media).as(o.getKey() + " " + statut).isNotNull();
                assertThat(media.get("schema").get("$ref").asText()).endsWith("/Probleme");
                media.get("examples").fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue().get("value");
                    assertThat(v.get("status").asInt()).isEqualTo(statut);
                    assertThat(v.get("code").asText()).isEqualTo(e.getKey());
                    assertThat(v.get("type").asText()).startsWith("urn:ged:erreur:");
                });
                if (statut == 429) assertThat(r.getValue().get("headers").has("Retry-After")).isTrue();
            });
        }
        assertThat(spec.get("components").get("schemas").get("Probleme").get("required").toString())
                .contains("code", "status", "type", "title");
    }

    @Test
    @DisplayName("Sécurité : jeton Bearer ou clé X-API-Key ; connexion publique ; routes réservées aux utilisateurs")
    void securite() {
        JsonNode schemas = spec.get("components").get("securitySchemes");
        assertThat(schemas.get("jeton").get("scheme").asText()).isEqualTo("bearer");
        assertThat(schemas.get("cleApi").get("type").asText()).isEqualTo("apiKey");
        assertThat(schemas.get("cleApi").get("in").asText()).isEqualTo("header");
        assertThat(schemas.get("cleApi").get("name").asText()).isEqualTo("X-API-Key");
        assertThat(spec.get("security").toString()).contains("jeton", "cleApi");

        assertThat(operation("POST", "/api/v1/auth/login").get("security")).isEmpty();
        assertThat(operation("GET", "/api/v1/applications").get("security").toString()).isEqualTo("[{\"jeton\":[]}]");
        assertThat(operation("GET", "/api/v1/notifications").get("security").toString()).isEqualTo("[{\"jeton\":[]}]");
        assertThat(operation("GET", "/api/v1/documents").get("security")).isNull();   // sécurité globale
        assertThat(noms(operation("GET", "/api/v1/documents").get("parameters"), "$ref"))
                .contains("#/components/parameters/XOnBehalfOf");
        assertThat(noms(operation("GET", "/api/v1/applications").get("parameters"), "$ref"))
                .doesNotContain("#/components/parameters/XOnBehalfOf");
        assertThat(operation("GET", "/api/v1/documents").get("responses").get("429")).isNotNull();
        assertThat(spec.get("components").get("parameters").get("XOnBehalfOf").get("name").asText())
                .isEqualTo("X-On-Behalf-Of");
    }

    @Test
    @DisplayName("Idempotence : Idempotency-Key obligatoire sur les créations, rejeu et conflit documentés")
    void idempotence() {
        JsonNode param = spec.get("components").get("parameters").get("IdempotencyKey");
        assertThat(param.get("name").asText()).isEqualTo("Idempotency-Key");
        assertThat(param.get("required").asBoolean()).isTrue();
        assertThat(param.get("schema").get("format").asText()).isEqualTo("uuid");
        for (String[] creation : new String[][]{{"POST", "/api/v1/documents"}, {"POST", "/api/v1/workspaces"},
                {"POST", "/api/v1/documents/{id}/versions"}}) {
            JsonNode op = operation(creation[0], creation[1]);
            assertThat(noms(op.get("parameters"), "$ref")).as(creation[1])
                    .contains("#/components/parameters/IdempotencyKey");
            assertThat(op.get("responses").get("422").get("content").get("application/problem+json")
                    .get("examples").has("IDEMPOTENCE_CONFLIT")).isTrue();
            assertThat(op.get("responses").get("409").get("content").get("application/problem+json")
                    .get("examples").has("IDEMPOTENCE_EN_COURS")).isTrue();
            op.get("responses").fields().forEachRemaining(r -> {
                if (r.getKey().startsWith("2")) {
                    assertThat(r.getValue().get("headers").has("Idempotency-Replayed")).isTrue();
                }
            });
        }
        assertThat(noms(operation("GET", "/api/v1/documents").get("parameters"), "$ref"))
                .doesNotContain("#/components/parameters/IdempotencyKey");
    }

    @Test
    @DisplayName("Pagination : 50 par défaut, 200 au plus ; réponses JSON ; exemple de corps sans schéma nommé")
    void paginationEtFormats() {
        JsonNode size = null;
        for (JsonNode p : operation("GET", "/api/v1/documents").get("parameters")) {
            if ("size".equals(p.path("name").asText())) size = p.get("schema");
        }
        assertThat(size).isNotNull();
        assertThat(size.get("default").asInt()).isEqualTo(50);
        assertThat(size.get("maximum").asInt()).isEqualTo(200);
        assertThat(operation("GET", "/api/v1/documents").get("responses").get("200").get("content")
                .has("application/json")).isTrue();
        assertThat(operation("DELETE", "/api/v1/documents/multiple-delete").get("requestBody").get("content")
                .get("application/json").get("example").toString()).contains("\"ids\"");
    }

    @Test
    @DisplayName("Dépréciation annoncée : opérations marquées, en-têtes Deprecation, Sunset et Link documentés")
    void depreciation() {
        EnrichissementOpenApi e = new EnrichissementOpenApi(new DictionnaireDocumentation(),
                new ProprietesIdempotence(null, null, null),
                new ProprietesConventionsApi(null, List.of(new ProprietesConventionsApi.Depreciation(
                        "/api/v1/", LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1), "/api/v2/"))));
        Operation op = new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse()));
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/v1/etiquettes", new PathItem().get(op)));
        e.customise(api);
        assertThat(op.getDeprecated()).isTrue();
        assertThat(op.getResponses().get("200").getHeaders()).containsKeys("Deprecation", "Sunset", "Link");
        assertThat(op.getResponses().get("200").getHeaders().get("Sunset").getDescription())
                .contains("1 Jan 2028");
    }

    @Test
    @DisplayName("Documentation fermée en production : springdoc désactivé par le profil prod")
    void fermeeEnProduction() throws Exception {
        boolean trouve = false;
        try (InputStream in = new ClassPathResource("api.yml").getInputStream()) {
            for (Iterator<Object> it = new Yaml().loadAll(in).iterator(); it.hasNext(); ) {
                Object doc = it.next();
                if (!(doc instanceof Map<?, ?> m) || !String.valueOf(m).contains("on-profile=prod")) continue;
                Map<?, ?> springdoc = (Map<?, ?>) m.get("springdoc");
                assertThat(((Map<?, ?>) springdoc.get("api-docs")).get("enabled")).isEqualTo(false);
                assertThat(((Map<?, ?>) springdoc.get("swagger-ui")).get("enabled")).isEqualTo(false);
                trouve = true;
            }
        }
        assertThat(trouve).isTrue();
    }
}
