package com.ipt.ged.autorisation.extension;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.autorisation.HorsPerimetreException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Garde des points d'entrée que le lot autorisation protège sans en être
 * propriétaire (plan de vagues : les modules des autres lots ne sont modifiés
 * que pour les contrôles d'accès, et le moins possible) :
 *
 * <ul>
 *   <li><b>référentiels</b> (types, index, plans, circuits, étiquettes) :
 *       toute écriture exige {@code GERER_REFERENTIELS} ;</li>
 *   <li><b>groupes GED</b> : toute écriture exige
 *       {@code GERER_ROLES_HABILITATIONS} (un groupe est un sujet de droits) ;</li>
 *   <li><b>routes par document</b> des modules OCR et indexation : Consulter
 *       pour lire (404 hors périmètre), Modifier pour écrire, et pas
 *       d'écriture sur un document verrouillé ou archivé (409) ;</li>
 *   <li><b>supervision OCR</b> : {@code SUPERVISER_TRAITEMENTS}.</li>
 * </ul>
 *
 * <p>La décision reste celle d'{@link ControleAcces} : cette classe ne fait
 * qu'associer une route à une permission. Elle vit dans sa propre
 * configuration MVC, hors de {@code SecurityConfig} (propriété de dev2 pour la
 * vague 3).
 */
@Component
public class GardeDroitsRequetes implements HandlerInterceptor {

    private static final Set<String> ECRITURES = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final List<String> REFERENTIELS = List.of(
            "/api/v1/type-documents/**", "/api/v1/type-documents",
            "/api/v1/indices/**", "/api/v1/indices",
            "/api/v1/plan-indexations/**", "/api/v1/plan-indexations",
            "/api/v1/workflowgeds/**", "/api/v1/workflowgeds",
            "/api/v1/etiquettes/**", "/api/v1/etiquettes");

    private static final List<String> GROUPES = List.of("/api/v1/access-groups/**", "/api/v1/access-groups");

    private static final List<String> DOCUMENT = List.of(
            "/api/v1/ocr/documents/{id}/**", "/api/v1/indexation/documents/{id}/**",
            "/api/v1/indexation/documents/{id}");

    private static final List<String> SUPERVISION = List.of("/api/v1/ocr/diagnostic", "/api/v1/ocr/etat");

    private final AntPathMatcher chemins = new AntPathMatcher();
    private final ControleAcces controle;
    private final com.ipt.ged.document.GardeEcriture garde;

    public GardeDroitsRequetes(ControleAcces controle, com.ipt.ged.document.GardeEcriture garde) {
        this.controle = controle;
        this.garde = garde;
    }

    @Override
    public boolean preHandle(HttpServletRequest requete, HttpServletResponse reponse, Object handler) {
        String chemin = requete.getRequestURI().substring(requete.getContextPath().length());
        boolean ecriture = ECRITURES.contains(requete.getMethod());

        if (ecriture && correspond(REFERENTIELS, chemin)) {
            controle.exigerAdministration(CodePermission.GERER_REFERENTIELS);
        }
        if (ecriture && correspond(GROUPES, chemin)) {
            controle.exigerAdministration(CodePermission.GERER_ROLES_HABILITATIONS);
        }
        if (correspond(SUPERVISION, chemin)) {
            controle.exigerAdministration(CodePermission.SUPERVISER_TRAITEMENTS);
        }
        for (String motif : DOCUMENT) {
            if (chemins.match(motif, chemin)) {
                Map<String, String> v = chemins.extractUriTemplateVariables(motif, chemin);
                UUID id;
                try {
                    id = UUID.fromString(v.get("id"));
                } catch (RuntimeException e) {
                    // Identifiant illisible : le contrôleur répondra 400, rien à protéger.
                    return true;
                }
                if (ecriture) {
                    controle.exigerSurDocument(CodePermission.MODIFIER, id);
                    // Réindexation : refusée sur un document verrouillé ou archivé (409, §12.8).
                    garde.exigerModifiable(id);
                } else if (!controle.documentLisible(id)) {
                    throw new HorsPerimetreException("Document introuvable : " + id);
                }
                break;
            }
        }
        return true;
    }

    private boolean correspond(List<String> motifs, String chemin) {
        for (String m : motifs) if (chemins.match(m, chemin)) return true;
        return false;
    }
}
