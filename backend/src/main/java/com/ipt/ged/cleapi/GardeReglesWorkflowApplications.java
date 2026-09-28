package com.ipt.ged.cleapi;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.PermissionRefuseeException;
import com.ipt.ged.autorisation.Sujet;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.common.erreur.AccesRefuseException;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.workflow.api.AccesApiWorkflowCles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gestion des règles de workflow par une application (D8 : « désigner les
 * validateurs, gérer les circuits » depuis l'intranet ; ANO-E8-001).
 *
 * <p>Une règle est un référentiel : pour un utilisateur de l'interface, son
 * écriture exige {@code GERER_REFERENTIELS} ({@code GardeDroitsRequetes}). Pour
 * une application, la décision est une <b>intersection explicite</b> de trois
 * conditions, toutes exigées :
 * <ol>
 *   <li><b>délégation</b> (§5.5) : l'application agit pour le compte d'une
 *       personne nommée ({@code X-On-Behalf-Of}), enregistrée comme auteur ; la
 *       double identité est écrite au journal (personne et application) ;</li>
 *   <li><b>portée de la clé</b> (§5.4) : l'opération {@code WORKFLOW_PILOTAGE}
 *       figure explicitement dans la portée de la clé, sur chaque nœud où la
 *       règle s'applique (nœuds et types de document auxquels elle est
 *       rattachée) et sur chaque périmètre de validateur par rôle (appliqué au
 *       corps de la requête par {@link PorteeReglesWorkflowCorps}) ; une règle
 *       encore rattachée à rien exige la portée sur au moins un nœud. La
 *       portée d'un nœud couvre sa sous-arborescence ;</li>
 *   <li><b>droits de la personne</b> : {@code GERER_REFERENTIELS}, comme pour la
 *       même opération faite dans l'interface, et comme le rattachement d'une
 *       règle par API ({@code RattachementRegles}). Une application ne donne
 *       jamais à une personne un droit qu'elle n'a pas.</li>
 * </ol>
 * La portée est lue opération par opération dans {@code cle_api_portee} et non
 * déduite des permissions : une clé de versement (Consulter + Modifier) ne
 * pilote pas le workflow.
 *
 * <p>Les opérations de masse ({@code multiple-delete}, {@code multiple-restore})
 * restent réservées à l'interface : leurs identifiants voyagent dans le corps.
 */
@Component
public class GardeReglesWorkflowApplications {

    private static final String REGLE = "/api/v1/workflow/regles/{id}/**";
    private static final String REGLE_SEULE = "/api/v1/workflow/regles/{id}";
    private static final String ANCIEN = "/api/v1/workflowgeds/{id}/**";
    private static final String ANCIEN_SEUL = "/api/v1/workflowgeds/{id}";

    private final AntPathMatcher chemins = new AntPathMatcher();
    private final PorteeCleApiRepository portees;
    private final AccessPredicate predicat;
    private final JdbcTemplate jdbc;

    public GardeReglesWorkflowApplications(PorteeCleApiRepository portees, AccessPredicate predicat,
                                           JdbcTemplate jdbc) {
        this.portees = portees;
        this.predicat = predicat;
        this.jdbc = jdbc;
    }

    /** Écriture sur {@code /workflow/regles} (ou l'ancien chemin) par une application. */
    public void exigerEcriture(ApplicationAuthentifiee application, String chemin) {
        UtilisateurConnecte personne = exigerDelegation(application);
        if (chemin.contains("/multiple-")) {
            throw new AccesRefuseException("Opération de masse sur les règles réservée à l'interface.");
        }
        UUID regle = regle(chemin);
        Set<UUID> noeuds = regle == null ? Set.of() : noeudsDeLaRegle(regle);
        if (noeuds.isEmpty()) {
            exigerPorteeQuelconque(application);
        } else {
            exigerPortee(application, noeuds);
        }
        exigerReferentiels(personne);
    }

    /** Périmètres des validateurs par rôle désignés dans le corps d'une règle. */
    public void exigerPerimetres(ApplicationAuthentifiee application, Collection<UUID> perimetres) {
        Set<UUID> p = new HashSet<>();
        for (UUID n : perimetres) if (n != null) p.add(n);
        if (!p.isEmpty()) exigerPortee(application, p);
    }

    private static UtilisateurConnecte exigerDelegation(ApplicationAuthentifiee application) {
        UtilisateurConnecte u = application.deleguee();
        if (u == null) {
            throw new AccesRefuseException(AccesApiWorkflowCles.DELEGATION_REQUISE,
                    "Une opération de workflow est faite au nom d'une personne : en-tête X-On-Behalf-Of requis.");
        }
        return u;
    }

    private void exigerReferentiels(UtilisateurConnecte personne) {
        Sujet s = new Sujet(TypeSujet.UTILISATEUR, personne.getUtilisateurId(), personne.getEmployeId(),
                personne.getNomComplet());
        if (!predicat.droits(s).administre(CodePermission.GERER_REFERENTIELS)) {
            throw new PermissionRefuseeException("Permission d'administration « GERER_REFERENTIELS » requise"
                    + " de la personne déléguée");
        }
    }

    private List<UUID> noeudsPilotes(ApplicationAuthentifiee application) {
        return portees.findByCleApiId(application.cleId()).stream()
                .filter(p -> p.operationsAutorisees().contains(OperationApi.WORKFLOW_PILOTAGE))
                .map(PorteeCleApi::getNoeudId)
                .toList();
    }

    private void exigerPorteeQuelconque(ApplicationAuthentifiee application) {
        if (noeudsPilotes(application).isEmpty()) {
            throw new AccesRefuseException("Portée de la clé insuffisante : WORKFLOW_PILOTAGE requis.");
        }
    }

    /** Chaque nœud doit être un nœud piloté ou l'un de ses descendants (chemin matérialisé). */
    private void exigerPortee(ApplicationAuthentifiee application, Set<UUID> noeuds) {
        List<UUID> pilotes = noeudsPilotes(application);
        if (pilotes.isEmpty()) {
            throw new AccesRefuseException("Portée de la clé insuffisante : WORKFLOW_PILOTAGE requis.");
        }
        for (UUID n : noeuds) {
            if (n == null) {
                throw new AccesRefuseException("Règle rattachée hors de toute arborescence : hors de la portée de la clé.");
            }
            String chemin = jdbc.queryForList("SELECT chemin FROM noeud WHERE id = ?", String.class, n)
                    .stream().findFirst().orElse(null);
            boolean couvert = chemin != null && pilotes.stream().anyMatch(p -> chemin.contains("/" + p + "/"));
            if (!couvert) {
                throw new AccesRefuseException("Portée de la clé insuffisante : WORKFLOW_PILOTAGE requis sur "
                        + "chaque nœud où la règle s'applique.");
            }
        }
    }

    /** Nœuds où la règle s'applique : rattachée au nœud, à un type de document du nœud, ou périmètre d'un validateur. */
    private Set<UUID> noeudsDeLaRegle(UUID regle) {
        Set<UUID> n = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM noeud WHERE regle_workflow_id = ?", UUID.class, regle));
        n.addAll(jdbc.queryForList(
                "SELECT noeud_id FROM type_document WHERE regle_workflow_id = ?", UUID.class, regle));
        n.addAll(jdbc.queryForList("SELECT perimetre_noeud_id FROM regle_validateur"
                + " WHERE regle_workflow_id = ? AND perimetre_noeud_id IS NOT NULL", UUID.class, regle));
        return n;
    }

    private UUID regle(String chemin) {
        for (String motif : List.of(REGLE_SEULE, REGLE, ANCIEN_SEUL, ANCIEN)) {
            if (chemins.match(motif, chemin)) {
                try {
                    return UUID.fromString(chemins.extractUriTemplateVariables(motif, chemin).get("id"));
                } catch (RuntimeException e) {
                    // Segment qui n'est pas un identifiant : création ou route sans règle.
                    return null;
                }
            }
        }
        return null;
    }
}
