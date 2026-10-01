package com.ipt.ged.documentationapi;

import com.ipt.ged.cleapi.ConfigurationSecuriteApplications;
import com.ipt.ged.cleapi.FiltreCleApi;
import com.ipt.ged.common.erreur.ChampsIgnores;
import com.ipt.ged.common.erreur.Problemes;
import com.ipt.ged.conventionsapi.ProprietesConventionsApi;
import com.ipt.ged.idempotence.FiltreIdempotence;
import com.ipt.ged.idempotence.ProprietesIdempotence;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Complète la spécification OpenAPI 3 générée par springdoc (T-053, DAT §5.3)
 * <b>sans annotation dans les contrôleurs des autres lots</b> :
 *
 * <ul>
 *   <li>sécurité : jeton de session (Bearer) ou clé d'API ({@code X-API-Key}),
 *       connexion publique, routes réservées aux utilisateurs ;</li>
 *   <li>charges utiles décrites champ par champ, avec exemples
 *       ({@code documentationapi/champs.yml}) ;</li>
 *   <li>erreurs {@code application/problem+json} par opération, avec leurs codes
 *       métier et un exemple chacune ;</li>
 *   <li>en-têtes : {@code Idempotency-Key} sur les créations,
 *       {@code X-On-Behalf-Of}, {@code Retry-After} sur 429,
 *       {@code Idempotency-Replayed}, {@code Deprecation} / {@code Sunset} ;</li>
 *   <li>pagination : 50 par défaut, 200 au plus.</li>
 * </ul>
 *
 * <p>Ce qui reste sans description est relevé par {@link #manques()} et fait
 * échouer {@code SpecificationOpenApiTest} : un champ ajouté à un DTO doit être
 * décrit dans le dictionnaire.
 */
public class EnrichissementOpenApi implements OpenApiCustomizer {

    static final String SCHEMA_PROBLEME = "Probleme";
    static final String JETON = "jeton";
    static final String CLE_API = "cleApi";
    static final String PROBLEME_JSON = "application/problem+json";
    static final String PARAM_IDEMPOTENCE = "IdempotencyKey";
    static final String PARAM_DELEGATION = "XOnBehalfOf";
    static final String CONNEXION = "/api/v1/auth/login";

    private static final String REF_SCHEMAS = "#/components/schemas/";
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** Recherches dont les champs inconnus sont signalés dans {@value ChampsIgnores#ENTETE} (P-08, ANO-F-011). */
    private static final Set<String> RECHERCHES = Set.of("/api/v1/documents/recherche", "/api/v1/recherches",
            "/api/v1/recherche/plein-texte", "/api/v1/indexation/recherche");

    /**
     * Chemins historiques de l'interface, conservés pour le front, et leur
     * équivalent au contrat d'API (§5.3.1) : les intégrateurs sont orientés vers
     * le chemin du contrat.
     */
    private static final Map<String, String> CHEMINS_DU_CONTRAT = Map.of(
            "POST /api/v1/workspaces", "POST /api/v1/noeuds/{id}/dossiers",
            "POST /api/v1/indexation/recherche", "POST /api/v1/recherches",
            "GET /api/v1/recherche/plein-texte", "POST /api/v1/recherches",
            "GET /api/v1/documents/{id}/download", "GET /api/v1/documents/{id}/contenu",
            "GET /api/v1/admin/droits-effectifs", "GET /api/v1/documents/{id}/droits, GET /api/v1/noeuds/{id}/droits");

    /** Une erreur documentée : statut, code métier stable, exemple de détail. */
    record Erreur(int statut, String code, String detail) {}

    private final DictionnaireDocumentation dictionnaire;
    private final List<PathPattern> creationsIdempotentes = new ArrayList<>();
    private final List<String> methodesIdempotentes = new ArrayList<>();
    private final List<PathPattern> reservesUtilisateurs;
    private final List<ProprietesConventionsApi.Depreciation> depreciations;
    private final SortedSet<String> manques = Collections.synchronizedSortedSet(new TreeSet<>());

    public EnrichissementOpenApi(DictionnaireDocumentation dictionnaire, ProprietesIdempotence idempotence,
                                 ProprietesConventionsApi conventions) {
        this.dictionnaire = dictionnaire;
        for (String route : idempotence.routes()) {
            String[] parties = route.trim().split("\\s+", 2);
            methodesIdempotentes.add(parties[0].toUpperCase());
            creationsIdempotentes.add(PathPatternParser.defaultInstance.parse(parties[1]));
        }
        this.reservesUtilisateurs = ConfigurationSecuriteApplications.CHEMINS_RESERVES_UTILISATEURS.stream()
                .map(PathPatternParser.defaultInstance::parse).toList();
        this.depreciations = conventions.depreciations();
    }

    /** Éléments restés sans description (schéma.champ, paramètre, corps) lors de la dernière génération. */
    public Set<String> manques() {
        return Set.copyOf(manques);
    }

    @Override
    public void customise(OpenAPI api) {
        manques.clear();
        if (api.getComponents() == null) api.setComponents(new Components());
        informations(api);
        composantsCommuns(api.getComponents());
        if (api.getComponents().getSchemas() != null) {
            api.getComponents().getSchemas().forEach(this::decrireSchema);
        }
        if (api.getPaths() != null) {
            api.getPaths().forEach((chemin, item) ->
                    item.readOperationsMap().forEach((methode, operation) -> decrireOperation(chemin, methode, operation)));
        }
    }

    /* ------------------------------------------------------------ généralités */

    private void informations(OpenAPI api) {
        api.info(new Info().title("API GED Marchica Med").version("v1").description("""
                API REST de la GED (dossier technique §5). Conventions (§5.3.2) :
                - JSON UTF-8 ; dates ISO 8601 en UTC ; identifiants opaques (UUID) ;
                - erreurs au format `application/problem+json` (RFC 7807) avec un `code` métier stable ;
                - pagination `page` (à partir de 0) et `size` : 50 par défaut, 200 au plus ; tri sur liste blanche ;
                - métadonnées limitées à 64 Ko par requête ;
                - créations soumises à `Idempotency-Key` (UUID, réponse mémorisée 24 h) ;
                - applications : clé `X-API-Key`, quotas par clé (429 + `Retry-After`) ;
                - version majeure dans l'URL (`/api/v1`) ; une version retirée l'annonce par `Deprecation` et `Sunset`,
                  et reste servie au moins 12 mois ;
                - compatibilité : évolutions additives dans une version majeure ; un champ ou un paramètre inconnu est
                  ignoré par le serveur ; les recherches le signalent dans l'en-tête `GED-Champs-Ignores`.
                Documentation fermée en production."""));
        api.servers(List.of(new Server().url("/").description("Même origine que l'application (derrière NGINX)")));
        api.security(List.of(new SecurityRequirement().addList(JETON), new SecurityRequirement().addList(CLE_API)));
    }

    private void composantsCommuns(Components c) {
        c.addSecuritySchemes(JETON, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .bearerFormat("JWT").description("Jeton de session d'un utilisateur, obtenu par " + CONNEXION + "."));
        c.addSecuritySchemes(CLE_API, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name(FiltreCleApi.ENTETE_CLE).description("""
                        Clé d'une application cliente (§5.4) : `ged_<env>_<identifiant>_<secret>`. Refus en 401 \
                        (CLE_API_INVALIDE, CLE_API_EXPIREE, CLE_API_REVOQUEE, CLE_API_AUTRE_ENVIRONNEMENT), 403 \
                        (APPLICATION_DESACTIVEE, ADRESSE_NON_AUTORISEE) ou 429 (QUOTA_MINUTE_DEPASSE, \
                        QUOTA_JOUR_DEPASSE)."""));

        c.addParameters(PARAM_IDEMPOTENCE, new Parameter().in("header").name(FiltreIdempotence.ENTETE).required(true)
                .description("UUID choisi par l'appelant pour cette création. Rejouée à l'identique dans les 24 h, "
                        + "la requête renvoie la réponse initiale sans rien recréer ; même clé avec un autre "
                        + "contenu : 422 IDEMPOTENCE_CONFLIT.")
                .schema(new StringSchema().format("uuid").example("5f0c8a52-2b7e-4d3c-9a61-0e4f7b2d9c18")));
        c.addParameters(PARAM_DELEGATION, new Parameter().in("header").name(FiltreCleApi.ENTETE_DELEGATION)
                .required(false)
                .description("Clé d'API autorisée à déléguer seulement : identifiant de l'utilisateur pour le compte "
                        + "duquel l'application agit (§5.5). Ouvert en vague 4 ; aujourd'hui refusé "
                        + "(422 IDENTITE_DELEGUEE_INVALIDE).")
                .schema(new StringSchema().example("s.bennani")));

        c.addHeaders("RetryAfter", new Header().description("Secondes à attendre avant de réessayer.")
                .schema(new IntegerSchema().example(30)));
        c.addHeaders("IdempotencyReplayed", new Header()
                .description("Présent (true) quand la réponse est celle, mémorisée, d'une création déjà faite.")
                .schema(new BooleanSchema().example(true)));
        c.addHeaders("Deprecation", new Header().description("Date d'annonce de la dépréciation (@<secondes>).")
                .schema(new StringSchema().example("@1798761600")));
        c.addHeaders("Sunset", new Header().description("Date de retrait (RFC 1123), au moins 12 mois après l'annonce.")
                .schema(new StringSchema().example("Sat, 1 Jan 2028 00:00:00 GMT")));
        c.addHeaders("ChampsIgnores", new Header().description("Champs du corps ou paramètres inconnus, ignorés "
                        + "(politique de compatibilité §5.3.2) : chemins séparés par des virgules, encodés en "
                        + "pourcentage hors ASCII. Absent si tout est connu. Un critère mal nommé n'a pas filtré.")
                .schema(new StringSchema().example("confidentialit, criteres[0].valeurr")));
        c.addHeaders("Link", new Header().description("Version qui remplace : rel=\"successor-version\".")
                .schema(new StringSchema().example("</api/v2/>; rel=\"successor-version\"")));

        c.addSchemas(SCHEMA_PROBLEME, schemaProbleme());
    }

    private static Schema<?> schemaProbleme() {
        Schema<?> s = new ObjectSchema().description("Erreur au format application/problem+json (RFC 7807, §5.3.2). "
                + "Le code métier est stable : c'est lui que le client teste, jamais le texte.");
        s.addProperty("type", new StringSchema().format("uri").description("Identifiant stable de l'erreur (URN).")
                .example("urn:ged:erreur:ressource-introuvable"));
        s.addProperty("title", new StringSchema().description("Titre court du statut, invariable.")
                .example("Ressource introuvable"));
        s.addProperty("status", new IntegerSchema().description("Statut HTTP.").example(404));
        s.addProperty("detail", new StringSchema().description("Explication lisible, en français.")
                .example("La ressource demandée est introuvable."));
        s.addProperty("instance", new StringSchema().format("uri-reference").description("Chemin de la requête.")
                .example("/api/v1/documents/0192f0a6-9999-7d1e-9f20-3a4b5c6d7e8f"));
        s.addProperty("code", new StringSchema().description("Code métier stable (ex. RESSOURCE_INTROUVABLE).")
                .example("RESSOURCE_INTROUVABLE"));
        s.addProperty("traceId", new StringSchema().description("Identifiant de trace W3C, commun aux journaux.")
                .example("4bf92f3577b34da6a3ce929d0e0e4736"));
        s.addProperty("erreurs", new MapSchema().additionalProperties(new StringSchema())
                .description("Message par champ en erreur (400 de validation seulement).")
                .example(Map.of("nom", "Le nom est obligatoire")));
        s.required(List.of("type", "title", "status", "code"));
        return s;
    }

    /* --------------------------------------------------------------- schémas */

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void decrireSchema(String nom, Schema schema) {
        if (schema.getDescription() == null) {
            String description = dictionnaire.objet(nom).orElseGet(() -> nom.startsWith("PageResponse")
                    ? "Page de résultats (" + nom.substring("PageResponse".length())
                      + ") : 50 éléments par défaut, 200 au plus."
                    : null);
            if (description == null && !SCHEMA_PROBLEME.equals(nom)) manques.add("objet:" + nom);
            schema.setDescription(description);
        }
        if (schema.getProperties() == null || SCHEMA_PROBLEME.equals(nom)) return;
        Map<String, Schema> proprietes = new LinkedHashMap<>(schema.getProperties());
        proprietes.forEach((champ, propriete) -> schema.getProperties().put(champ, decrireChamp(nom, champ, propriete)));
    }

    @SuppressWarnings("rawtypes")
    private Schema decrireChamp(String schema, String champ, Schema propriete) {
        DictionnaireDocumentation.Entree entree = dictionnaire.champ(schema, champ).orElse(null);
        if (entree == null || entree.description() == null) {
            manques.add("champ:" + schema + "." + champ);
            return propriete;
        }
        if (propriete.get$ref() != null) {
            // En OpenAPI 3.0, un frère de $ref est ignoré : allOf porte la description.
            return new ComposedSchema().addAllOfItem(new Schema<>().$ref(propriete.get$ref()))
                    .description(entree.description());
        }
        propriete.setDescription(entree.description());
        Object exemple = entree.exemple() != null ? entree.exemple() : exempleGenere(propriete);
        if (exemple != null) propriete.setExample(exemple);
        return propriete;
    }

    /** Exemple déduit du type quand le dictionnaire n'en donne pas (tableau d'objets, énumération…). */
    @SuppressWarnings("rawtypes")
    private static Object exempleGenere(Schema s) {
        if (s.getEnum() != null && !s.getEnum().isEmpty()) return s.getEnum().get(0);
        if (s instanceof ArraySchema a && a.getItems() != null && a.getItems().get$ref() == null) {
            Object element = exempleGenere(a.getItems());
            return element == null ? null : List.of(element);
        }
        String format = s.getFormat();
        if ("uuid".equals(format)) return "0192f0a6-5b3c-7d1e-9f20-3a4b5c6d7e8f";
        if ("date-time".equals(format)) return "2026-09-27T09:30:00Z";
        if ("date".equals(format)) return "2026-09-27";
        if ("boolean".equals(s.getType())) return true;
        if ("integer".equals(s.getType())) return 1;
        return null;
    }

    /* ------------------------------------------------------------ opérations */

    private void decrireOperation(String chemin, PathItem.HttpMethod methode, Operation op) {
        String concret = chemin.replaceAll("\\{[^}]+}", "x");
        boolean publique = CONNEXION.equals(chemin);
        boolean reserveUtilisateurs = reservesUtilisateurs.stream()
                .anyMatch(p -> p.matches(PathContainer.parsePath(concret)));
        boolean ouverteAuxApplications = !publique && !reserveUtilisateurs;
        boolean idempotente = estIdempotente(methode.name(), concret);
        boolean ecriture = methode != PathItem.HttpMethod.GET && methode != PathItem.HttpMethod.HEAD;
        boolean multipart = corpsDeType(op, "multipart/form-data");
        boolean corps = op.getRequestBody() != null;

        String equivalent = CHEMINS_DU_CONTRAT.get(methode.name() + " " + chemin);
        if (equivalent != null) {
            op.setDescription((op.getDescription() == null ? "" : op.getDescription() + "\n\n")
                    + "Chemin historique de l'interface. Intégrations : utiliser le chemin du contrat d'API (§5.3.1) "
                    + equivalent + ".");
        }

        // --- sécurité ---
        if (publique) {
            op.setSecurity(List.of());
        } else if (reserveUtilisateurs) {
            op.setSecurity(List.of(new SecurityRequirement().addList(JETON)));
        }

        // --- paramètres et en-têtes ---
        if (op.getParameters() != null) op.getParameters().forEach(p -> decrireParametre(chemin, p));
        if (idempotente) {
            op.addParametersItem(new Parameter().$ref("#/components/parameters/" + PARAM_IDEMPOTENCE));
        }
        if (ouverteAuxApplications) {
            op.addParametersItem(new Parameter().$ref("#/components/parameters/" + PARAM_DELEGATION));
        }
        if (corps) decrireCorps(methode, chemin, op.getRequestBody());
        normaliserReponses(op);

        // --- erreurs problem+json ---
        List<Erreur> erreurs = new ArrayList<>();
        if (publique) {
            erreurs.add(new Erreur(401, "NON_AUTHENTIFIE", "Identifiant ou mot de passe incorrect."));
            erreurs.add(new Erreur(429, "TROP_DE_REQUETES", "Trop de tentatives : réessayez plus tard."));
        } else {
            erreurs.add(new Erreur(401, "NON_AUTHENTIFIE", "Authentification requise."));
            erreurs.add(new Erreur(403, "ACCES_REFUSE", "Vous n'avez pas le droit d'effectuer cette opération."));
            if (ouverteAuxApplications) {
                erreurs.add(new Erreur(401, "CLE_API_INVALIDE", "Clé d'API invalide."));
                erreurs.add(new Erreur(401, "CLE_API_EXPIREE", "Clé d'API expirée."));
                erreurs.add(new Erreur(401, "CLE_API_REVOQUEE", "Clé d'API révoquée."));
                erreurs.add(new Erreur(403, "ADRESSE_NON_AUTORISEE", "Adresse d'appel non autorisée pour cette application."));
                erreurs.add(new Erreur(403, "APPLICATION_DESACTIVEE", "Application désactivée."));
                erreurs.add(new Erreur(403, "DELEGATION_NON_AUTORISEE", "Cette clé ne peut pas agir pour le compte d'un utilisateur."));
                erreurs.add(new Erreur(422, "IDENTITE_DELEGUEE_INVALIDE", "Identité déléguée non vérifiable."));
                erreurs.add(new Erreur(429, "QUOTA_MINUTE_DEPASSE", "Quota de 600 appels par minute dépassé."));
                erreurs.add(new Erreur(429, "QUOTA_JOUR_DEPASSE", "Quota de 100 000 appels par jour dépassé."));
            }
        }
        if (chemin.contains("{")) {
            erreurs.add(new Erreur(404, "RESSOURCE_INTROUVABLE", "La ressource demandée est introuvable."));
        }
        if (op.getParameters() != null && op.getParameters().stream().anyMatch(p -> "query".equals(p.getIn()))) {
            erreurs.add(new Erreur(400, "PARAMETRE_INVALIDE", "Paramètre invalide : page."));
        }
        if (corps) {
            erreurs.add(new Erreur(400, "REQUETE_INVALIDE", "Corps de requête illisible."));
            erreurs.add(new Erreur(400, "VALIDATION_ECHOUEE", "Un ou plusieurs champs sont invalides."));
            erreurs.add(new Erreur(413, "METADONNEES_TROP_VOLUMINEUSES", "Métadonnées limitées à 64 Ko."));
            erreurs.add(new Erreur(415, "TYPE_CONTENU_NON_SUPPORTE", "Type de contenu non pris en charge."));
            erreurs.add(new Erreur(422, "REGLE_METIER_VIOLEE", "La demande enfreint une règle métier."));
        }
        if (multipart) {
            erreurs.add(new Erreur(413, "REQUETE_TROP_VOLUMINEUSE", "Fichier trop volumineux."));
            erreurs.add(new Erreur(503, "SERVICE_INDISPONIBLE", "Contrôle antivirus momentanément indisponible."));
        }
        if (ecriture && !publique) {
            erreurs.add(new Erreur(409, "CONFLIT", "L'opération est en conflit avec l'état de la ressource."));
        }
        if (idempotente) {
            erreurs.add(new Erreur(400, "IDEMPOTENCE_CLE_ABSENTE", "En-tête Idempotency-Key (UUID) obligatoire sur une création."));
            erreurs.add(new Erreur(409, "IDEMPOTENCE_EN_COURS", "Une requête avec la même clé est en cours de traitement."));
            erreurs.add(new Erreur(422, "IDEMPOTENCE_CONFLIT", "Clé d'idempotence déjà utilisée pour une autre requête."));
        }
        erreurs.add(new Erreur(500, "ERREUR_INTERNE", "Erreur interne ; le traceId permet de la retrouver."));
        erreurs.forEach(e -> ajouterErreur(op, chemin, e));

        // --- en-têtes de réponse ---
        ApiResponses reponses = op.getResponses();
        reponses.forEach((statut, reponse) -> {
            if (statut.equals("429")) reponse.addHeaderObject("Retry-After", ref("RetryAfter"));
            if (statut.startsWith("2") && idempotente) {
                reponse.addHeaderObject(FiltreIdempotence.ENTETE_REJEU, ref("IdempotencyReplayed"));
            }
            if (statut.startsWith("2") && RECHERCHES.contains(chemin)) {
                reponse.addHeaderObject(ChampsIgnores.ENTETE, ref("ChampsIgnores"));
            }
        });
        for (ProprietesConventionsApi.Depreciation d : depreciations) {
            if (chemin.startsWith(d.prefixe())) {
                op.setDeprecated(true);
                reponses.forEach((statut, reponse) -> {
                    if (!statut.startsWith("2")) return;
                    reponse.addHeaderObject("Deprecation", ref("Deprecation"));
                    reponse.addHeaderObject("Sunset", ref("Sunset").description("Retrait le "
                            + RFC_1123.format(d.retrait().atStartOfDay(ZoneOffset.UTC)) + "."));
                    if (d.successeur() != null) reponse.addHeaderObject("Link", ref("Link"));
                });
            }
        }
    }

    private static Header ref(String nom) {
        return new Header().$ref("#/components/headers/" + nom);
    }

    private boolean estIdempotente(String methode, String concret) {
        PathContainer chemin = PathContainer.parsePath(concret);
        for (int i = 0; i < creationsIdempotentes.size(); i++) {
            if (methodesIdempotentes.get(i).equals(methode) && creationsIdempotentes.get(i).matches(chemin)) {
                return true;
            }
        }
        return false;
    }

    private static boolean corpsDeType(Operation op, String type) {
        return op.getRequestBody() != null && op.getRequestBody().getContent() != null
                && op.getRequestBody().getContent().containsKey(type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void decrireParametre(String chemin, Parameter p) {
        if (p.get$ref() != null) return;
        if (p.getDescription() == null) {
            dictionnaire.parametre(p.getName()).ifPresentOrElse(p::setDescription,
                    () -> manques.add("parametre:" + p.getName() + " (" + chemin + ")"));
        }
        Schema s = p.getSchema();
        if (s == null) return;
        if ("size".equals(p.getName()) || "taille".equals(p.getName())) {
            // Contrat §5.3.2 : 50 par défaut (même si le contrôleur déclare autre chose, le filtre
            // des conventions l'applique), 200 au plus (Tri.TAILLE_MAX).
            s.setDefault(50);
            s.setMinimum(java.math.BigDecimal.ONE);
            s.setMaximum(java.math.BigDecimal.valueOf(200));
        } else if ("page".equals(p.getName())) {
            s.setMinimum(java.math.BigDecimal.ZERO);
        }
        if (s.getExample() == null && p.getExample() == null) {
            Object exemple = exempleGenere(s);
            if (exemple != null) s.setExample(exemple);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void decrireCorps(PathItem.HttpMethod methode, String chemin, RequestBody corps) {
        if (corps.get$ref() != null || corps.getContent() == null) return;
        corps.getContent().forEach((type, media) -> {
            Schema s = media.getSchema();
            if (s == null) return;
            if (s.get$ref() != null) {
                String nom = s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1);
                if (corps.getDescription() == null) dictionnaire.objet(nom).ifPresent(corps::setDescription);
            } else if (s.getProperties() != null) {
                Map<String, Schema> proprietes = new LinkedHashMap<>(s.getProperties());
                proprietes.forEach((champ, p) -> s.getProperties().put(champ, decrireChamp("multipart", champ, p)));
            } else {
                dictionnaire.corps(chemin).ifPresentOrElse(e -> {
                    s.setDescription(e.description());
                    corps.setDescription(e.description());
                    if (e.exemple() != null) media.setExample(e.exemple());
                }, () -> manques.add("corps:" + methode + " " + chemin));
            }
        });
    }

    /**
     * Réponses JSON : springdoc annonce {@code *}/{@code *} faute d'indication du
     * contrôleur ; l'API ne produit que du JSON UTF-8 hors téléchargements.
     */
    @SuppressWarnings("rawtypes")
    private static void normaliserReponses(Operation op) {
        if (op.getResponses() == null) op.setResponses(new ApiResponses());
        op.getResponses().forEach((statut, reponse) -> {
            Content c = reponse.getContent();
            if (c == null || !c.containsKey("*/*")) return;
            MediaType m = c.get("*/*");
            Schema s = m.getSchema();
            boolean binaire = s == null || "binary".equals(s.getFormat())
                    || (s.getType() != null && "string".equals(s.getType()) && s.get$ref() == null);
            if (binaire) return;
            c.remove("*/*");
            c.addMediaType("application/json", m);
        });
    }

    private void ajouterErreur(Operation op, String chemin, Erreur e) {
        String statut = String.valueOf(e.statut());
        ApiResponse reponse = op.getResponses().computeIfAbsent(statut, s -> new ApiResponse());
        if (reponse.getContent() == null) reponse.setContent(new Content());
        MediaType media = reponse.getContent().computeIfAbsent(PROBLEME_JSON,
                t -> new MediaType().schema(new Schema<>().$ref(REF_SCHEMAS + SCHEMA_PROBLEME)));
        if (media.getExamples() == null) media.setExamples(new LinkedHashMap<>());
        Map<String, Object> valeur = new LinkedHashMap<>();
        valeur.put("type", Problemes.typePour(e.code()).toString());
        valeur.put("title", Problemes.titre(e.statut()));
        valeur.put("status", e.statut());
        valeur.put("detail", e.detail());
        valeur.put("instance", chemin);
        valeur.put("code", e.code());
        valeur.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        if ("VALIDATION_ECHOUEE".equals(e.code())) valeur.put("erreurs", Map.of("nom", "Le nom est obligatoire"));
        media.getExamples().putIfAbsent(e.code(), new Example().summary(e.code()).value(valeur));
        String codes = String.join(", ", media.getExamples().keySet());
        reponse.setDescription(Problemes.titre(e.statut()) + " — codes : " + codes);
    }
}
