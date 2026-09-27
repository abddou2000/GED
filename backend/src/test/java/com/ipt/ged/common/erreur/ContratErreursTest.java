package com.ipt.ged.common.erreur;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ipt.ged.common.GlobalExceptionHandler;
import com.ipt.ged.fichier.CodesErreurFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Contrat d'erreur de l'API (DAT 5.3.2) : chaque statut sort en
 * {@code application/problem+json} avec {@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}, {@code code} et
 * {@code traceId} ; les 400 de validation portent {@code erreurs} ; le 429
 * porte {@code Retry-After} ; un 404 ne dit jamais pourquoi.
 */
class ContratErreursTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private MockMvc mvc;

    record Saisie(@NotBlank(message = "Le nom est obligatoire.") String nom) {}

    @RestController
    static class ControleurEssai {
        @GetMapping("/essai/metier/{cas}")
        void metier(@PathVariable String cas) {
            switch (cas) {
                case "400" -> throw new RequeteInvalideException("Critère de tri inconnu.");
                case "401" -> throw new NonAuthentifieException("Jeton expiré.");
                case "403" -> throw new AccesRefuseException("Permission Déposer requise.");
                case "404" -> throw new RessourceIntrouvableException();
                case "409" -> throw (ConflitException) new ConflitException("DOCUMENT_VERROUILLE", "Document verrouillé.")
                        .avec("verrouillePar", "a.benali");
                case "422" -> throw new RegleMetierException("TYPE_UTILISE", "Type utilisé par des documents.");
                case "429" -> throw new TropDeRequetesException("Trop de tentatives.", Duration.ofMillis(29_500));
                case "503" -> throw new ServiceIndisponibleException("Annuaire injoignable.");
                default -> throw new IllegalStateException("cas inconnu");
            }
        }

        @GetMapping("/essai/historique/{cas}")
        void historique(@PathVariable String cas) {
            switch (cas) {
                case "introuvable" -> throw new EntityNotFoundException("Document introuvable : 42");
                case "illegal" -> throw new IllegalArgumentException("Ce code est déjà utilisé.");
                case "integrite" -> throw new DataIntegrityViolationException("x",
                        new RuntimeException("ERREUR: la valeur d'une clé dupliquée rompt la contrainte unique « uk_tag »"));
                case "concurrence" -> throw new CannotAcquireLockException("lock timeout");
                case "fichier" -> throw new ErreurFichierException(HttpStatus.UNPROCESSABLE_ENTITY,
                        CodesErreurFichier.FICHIER_INFECTE, "Fichier infecté : dépôt refusé.");
                case "auth" -> throw new BadCredentialsException("mot de passe faux pour admin");
                case "statut" -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Ralentissez.");
                default -> throw new IllegalStateException("panne interne : mot de passe=secret");
            }
        }

        @GetMapping("/essai/uuid/{id}")
        String uuid(@PathVariable UUID id) {
            return id.toString();
        }

        @PostMapping("/essai/valider")
        String valider(@Valid @RequestBody Saisie saisie) {
            return saisie.nom();
        }
    }

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.standaloneSetup(new ControleurEssai())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MDC.put("traceId", TRACE);
    }

    @AfterEach
    void nettoyer() {
        MDC.clear();
    }

    private JsonNode probleme(MvcResult r, int statut, String code) throws Exception {
        MockHttpServletResponse rep = r.getResponse();
        assertThat(rep.getStatus()).isEqualTo(statut);
        assertThat(MediaType.parseMediaType(rep.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .as("Content-Type %s", rep.getContentType()).isTrue();
        JsonNode corps = json.readTree(rep.getContentAsByteArray());
        assertThat(corps.path("status").asInt()).isEqualTo(statut);
        assertThat(corps.path("code").asText()).isEqualTo(code);
        assertThat(corps.path("type").asText()).isEqualTo(Problemes.typePour(code).toString());
        assertThat(corps.path("title").asText()).isNotBlank();
        assertThat(corps.path("detail").asText()).isNotBlank();
        assertThat(corps.path("instance").asText()).isEqualTo(r.getRequest().getRequestURI());
        assertThat(corps.path("traceId").asText()).isEqualTo(TRACE);
        return corps;
    }

    @Test
    @DisplayName("Chaque sous-classe d'ExceptionMetier donne son statut et son code")
    void exceptionsMetier() throws Exception {
        probleme(mvc.perform(get("/essai/metier/400")).andReturn(), 400, CodesErreur.REQUETE_INVALIDE);
        probleme(mvc.perform(get("/essai/metier/401")).andReturn(), 401, CodesErreur.NON_AUTHENTIFIE);
        probleme(mvc.perform(get("/essai/metier/403")).andReturn(), 403, CodesErreur.ACCES_REFUSE);
        probleme(mvc.perform(get("/essai/metier/422")).andReturn(), 422, "TYPE_UTILISE");
        probleme(mvc.perform(get("/essai/metier/503")).andReturn(), 503, CodesErreur.SERVICE_INDISPONIBLE);

        JsonNode conflit = probleme(mvc.perform(get("/essai/metier/409")).andReturn(), 409, "DOCUMENT_VERROUILLE");
        assertThat(conflit.path("verrouillePar").asText()).isEqualTo("a.benali");
        assertThat(conflit.path("title").asText()).isEqualTo("Conflit");
    }

    @Test
    @DisplayName("429 : en-tête Retry-After en secondes entières, arrondi au supérieur")
    void retryAfter() throws Exception {
        MvcResult r = mvc.perform(get("/essai/metier/429")).andReturn();
        probleme(r, 429, CodesErreur.TROP_DE_REQUETES);
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("30");
    }

    @Test
    @DisplayName("404 : même libellé pour un objet absent et un objet hors périmètre, identifiant jamais recopié")
    void introuvableIndiscernable() throws Exception {
        JsonNode metier = probleme(mvc.perform(get("/essai/metier/404")).andReturn(), 404, CodesErreur.RESSOURCE_INTROUVABLE);
        JsonNode jpa = probleme(mvc.perform(get("/essai/historique/introuvable")).andReturn(), 404, CodesErreur.RESSOURCE_INTROUVABLE);
        JsonNode route = probleme(mvc.perform(get("/essai/route-inconnue")).andReturn(), 404, CodesErreur.RESSOURCE_INTROUVABLE);
        assertThat(jpa.path("detail").asText())
                .isEqualTo(metier.path("detail").asText())
                .isEqualTo(route.path("detail").asText())
                .doesNotContain("42");
    }

    @Test
    @DisplayName("400 de validation : dictionnaire erreurs indexé par champ")
    void validation() throws Exception {
        JsonNode corps = probleme(mvc.perform(post("/essai/valider").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nom\":\"\"}")).andReturn(), 400, CodesErreur.VALIDATION_ECHOUEE);
        assertThat(corps.path("erreurs").path("nom").asText()).isEqualTo("Le nom est obligatoire.");
    }

    @Test
    @DisplayName("Exceptions standard de Spring : UUID invalide, corps illisible, méthode, type de contenu")
    void exceptionsSpring() throws Exception {
        JsonNode uuid = probleme(mvc.perform(get("/essai/uuid/abc")).andReturn(), 400, CodesErreur.PARAMETRE_INVALIDE);
        assertThat(uuid.path("detail").asText()).contains("UUID");
        probleme(mvc.perform(post("/essai/valider").contentType(MediaType.APPLICATION_JSON).content("{nom")).andReturn(),
                400, CodesErreur.REQUETE_INVALIDE);
        probleme(mvc.perform(put("/essai/valider").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(),
                405, CodesErreur.METHODE_NON_AUTORISEE);
        probleme(mvc.perform(post("/essai/valider").contentType(MediaType.TEXT_PLAIN).content("x")).andReturn(),
                415, CodesErreur.TYPE_CONTENU_NON_SUPPORTE);
        probleme(mvc.perform(get("/essai/historique/statut")).andReturn(), 429, CodesErreur.TROP_DE_REQUETES);
    }

    @Test
    @DisplayName("Exceptions héritées : 400, 409, codes du lot stockage, 401, 500 sans fuite")
    void exceptionsHeritees() throws Exception {
        JsonNode illegal = probleme(mvc.perform(get("/essai/historique/illegal")).andReturn(), 400, CodesErreur.REQUETE_INVALIDE);
        assertThat(illegal.path("detail").asText()).isEqualTo("Ce code est déjà utilisé.");
        JsonNode integrite = probleme(mvc.perform(get("/essai/historique/integrite")).andReturn(), 400, CodesErreur.DONNEE_REFUSEE);
        assertThat(integrite.path("detail").asText()).contains("déjà utilisée").doesNotContain("uk_tag");
        probleme(mvc.perform(get("/essai/historique/concurrence")).andReturn(), 409, CodesErreur.MODIFICATION_CONCURRENTE);
        probleme(mvc.perform(get("/essai/historique/fichier")).andReturn(), 422, CodesErreurFichier.FICHIER_INFECTE);
        JsonNode auth = probleme(mvc.perform(get("/essai/historique/auth")).andReturn(), 401, CodesErreur.NON_AUTHENTIFIE);
        assertThat(auth.path("detail").asText()).doesNotContain("admin");
        JsonNode interne = probleme(mvc.perform(get("/essai/historique/panne")).andReturn(), 500, CodesErreur.ERREUR_INTERNE);
        assertThat(interne.path("detail").asText()).doesNotContain("secret").contains("traceId");
    }

    @Test
    @DisplayName("401 et 403 de la chaîne de sécurité : même format problem+json")
    void chaineDeSecurite() throws Exception {
        ReponsesSecuriteProblem reponses = new ReponsesSecuriteProblem(json);
        MockHttpServletRequest requete = new MockHttpServletRequest("GET", "/api/v1/documents");

        MockHttpServletResponse r401 = new MockHttpServletResponse();
        reponses.commence(requete, r401, new BadCredentialsException("jeton expiré"));
        MockHttpServletResponse r403 = new MockHttpServletResponse();
        reponses.handle(requete, r403, new org.springframework.security.access.AccessDeniedException("non"));

        for (var cas : new Object[][]{{r401, 401, CodesErreur.NON_AUTHENTIFIE}, {r403, 403, CodesErreur.ACCES_REFUSE}}) {
            MockHttpServletResponse rep = (MockHttpServletResponse) cas[0];
            assertThat(rep.getStatus()).isEqualTo(cas[1]);
            assertThat(rep.getContentType()).startsWith("application/problem+json");
            JsonNode corps = json.readTree(rep.getContentAsByteArray());
            assertThat(corps.path("code").asText()).isEqualTo(cas[2]);
            assertThat(corps.path("instance").asText()).isEqualTo("/api/v1/documents");
            assertThat(corps.path("traceId").asText()).isEqualTo(TRACE);
            assertThat(corps.path("detail").asText()).isNotBlank().doesNotContain("jeton expiré");
        }
    }
}
