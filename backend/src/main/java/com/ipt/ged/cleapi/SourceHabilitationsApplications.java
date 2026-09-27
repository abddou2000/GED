package com.ipt.ged.cleapi;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.ArbreNoeuds;
import com.ipt.ged.autorisation.Attribution;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.DroitsResolus;
import com.ipt.ged.autorisation.HabilitationRepository;
import com.ipt.ged.autorisation.SourceHabilitations;
import com.ipt.ged.autorisation.Sujet;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * La clé d'API comme sujet des droits (DAT §5.4, §12.2 : « une clé d'API est un
 * sujet comme un autre ») : seconde {@link SourceHabilitations}, à côté de celle
 * des utilisateurs (lot E3). Aucun autre code n'applique la portée : tous les
 * contrôles passent par le même point d'application unique
 * ({@link AccessPredicate}), qui réunit les attributions de toutes les sources.
 *
 * <h2>Sujet</h2>
 * <p>Le sujet d'un appel est <b>la clé</b> ({@code Sujet.id} = identifiant de la
 * clé) : deux clés d'une même application peuvent avoir des portées
 * différentes, et le cache des droits résolus est tenu par sujet. Ses
 * attributions sont :
 * <ul>
 *   <li>les lignes {@code cle_api_portee} de la clé : un nœud (sous-arborescence
 *       comprise) et des opérations, traduites en permissions élémentaires
 *       ({@link OperationApi#permissions()}) ;</li>
 *   <li>les habilitations de sujet APPLICATION posées sur l'application dans
 *       l'écran des habilitations (rôle sur un nœud).</li>
 * </ul>
 * Jamais de permission d'administration : une application n'administre rien.
 *
 * <h2>Délégation (§5.5)</h2>
 * <p>En écriture, les droits restent ceux de la clé (l'utilisateur délégué n'est
 * que l'auteur : déposant, acteur du journal). En lecture, le sujet est
 * l'<b>intersection</b> des droits de la clé et de ceux de l'utilisateur :
 * chaque nœud reçoit une attribution explicite portant les seules permissions
 * communes (pas d'héritage à recalculer), un document isolé l'intersection de
 * ses permissions isolées, et la confidentialité les seules permissions
 * {@code VOIR_PRIVE} / {@code VOIR_CONFIDENTIEL} détenues des deux côtés. Le
 * sujet composé n'a pas de personne métier : un document privé du délégué ne
 * lui est visible que si la clé porte aussi {@code VOIR_PRIVE} (intersection
 * stricte, au plus prudent).
 */
@Component
@Order(-10)
public class SourceHabilitationsApplications implements SourceHabilitations {

    /** Sujet composé d'une lecture déléguée : la clé et l'utilisateur. */
    private record Composition(Sujet cle, Sujet utilisateur) {}

    private static final int COMPOSITIONS_MAX = 1000;

    private final JdbcTemplate jdbc;
    private final HabilitationRepository habilitations;
    private final ObjectProvider<AccessPredicate> predicat;
    private final Map<UUID, Composition> compositions = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Composition> eldest) {
                    return size() > COMPOSITIONS_MAX;
                }
            });

    public SourceHabilitationsApplications(JdbcTemplate jdbc, HabilitationRepository habilitations,
                                           ObjectProvider<AccessPredicate> predicat) {
        this.jdbc = jdbc;
        this.habilitations = habilitations;
        this.predicat = predicat;
    }

    @Override
    public Optional<Sujet> sujet(Authentication authentification) {
        if (!(authentification instanceof ApplicationAuthentifiee a)) return Optional.empty();
        Sujet cle = new Sujet(TypeSujet.APPLICATION, a.cleId(), null, "application " + a.code());
        UtilisateurConnecte u = a.deleguee();
        if (u == null || !a.lecture()) return Optional.of(cle);
        UUID id = UUID.nameUUIDFromBytes(("delegation:" + a.cleId() + ":" + u.getUtilisateurId())
                .getBytes(StandardCharsets.UTF_8));
        compositions.put(id, new Composition(cle, Sujet.utilisateur(u)));
        return Optional.of(new Sujet(TypeSujet.APPLICATION, id, null,
                "application " + a.code() + " pour " + u.getUsername()));
    }

    @Override
    public List<Attribution> attributions(Sujet sujet) {
        if (sujet.type() != TypeSujet.APPLICATION) return List.of();
        Composition c = compositions.get(sujet.id());
        if (c != null) return intersection(c);
        return attributionsDeLaCle(sujet.id());
    }

    /** Portée de la clé et habilitations de son application. */
    private List<Attribution> attributionsDeLaCle(UUID cleId) {
        List<UUID> application = jdbc.queryForList("SELECT application_id FROM cle_api WHERE id = ?", UUID.class, cleId);
        if (application.isEmpty()) return List.of();
        UUID applicationId = application.get(0);
        List<Attribution> attributions = new ArrayList<>();
        jdbc.query("SELECT id, noeud_id, operations FROM cle_api_portee WHERE cle_api_id = ?", rs -> {
            Set<OperationApi> ops = EnumSet.noneOf(OperationApi.class);
            for (String code : rs.getString(3).split(",")) {
                if (!code.isBlank()) ops.add(OperationApi.valueOf(code.trim()));
            }
            attributions.add(new Attribution(rs.getObject(1, UUID.class), TypeSujet.APPLICATION, applicationId,
                    "Portée de la clé", null, null, false, OperationApi.permissions(ops),
                    rs.getObject(2, UUID.class), null, false));
        }, cleId);
        habilitations.findByApplicationId(applicationId).forEach(h -> {
            Attribution a = Attribution.depuis(h, "Application");
            // Une application n'administre rien : seules les permissions élémentaires valent.
            attributions.add(new Attribution(a.origineId(), a.viaType(), a.viaId(), a.viaLibelle(), a.roleId(),
                    a.roleCode(), false, elementaires(a.permissions()), a.noeudId(), a.documentId(), a.rupture()));
        });
        return attributions;
    }

    /** Droits communs à la clé et à l'utilisateur délégué, nœud par nœud. */
    private List<Attribution> intersection(Composition c) {
        AccessPredicate p = predicat.getObject();
        DroitsResolus cle = p.droits(c.cle());
        DroitsResolus utilisateur = p.droits(c.utilisateur());
        ArbreNoeuds arbre = p.arbre();
        List<Attribution> attributions = new ArrayList<>();
        for (ArbreNoeuds.Noeud n : arbre.ordonnes()) {
            Set<CodePermission> communes = EnumSet.noneOf(CodePermission.class);
            communes.addAll(cle.surNoeud(n.id()));
            communes.retainAll(utilisateur.surNoeud(n.id()));
            attributions.add(new Attribution(null, TypeSujet.APPLICATION, c.cle().id(), "Délégation",
                    null, null, false, communes, n.id(), null, communes.isEmpty()));
        }
        Set<UUID> documents = new java.util.HashSet<>(cle.documents(CodePermission.CONSULTER));
        documents.retainAll(utilisateur.documents(CodePermission.CONSULTER));
        for (UUID d : documents) {
            Set<CodePermission> communes = EnumSet.noneOf(CodePermission.class);
            communes.addAll(cle.surDocumentIsole(d));
            communes.retainAll(utilisateur.surDocumentIsole(d));
            attributions.add(new Attribution(null, TypeSujet.APPLICATION, c.cle().id(), "Délégation",
                    null, null, false, communes, null, d, false));
        }
        Set<CodePermission> confidentialite = EnumSet.noneOf(CodePermission.class);
        if (cle.voirPrive() && utilisateur.voirPrive()) confidentialite.add(CodePermission.VOIR_PRIVE);
        if (cle.voirConfidentiel() && utilisateur.voirConfidentiel()) confidentialite.add(CodePermission.VOIR_CONFIDENTIEL);
        if (!confidentialite.isEmpty()) {
            attributions.add(new Attribution(null, TypeSujet.APPLICATION, c.cle().id(), "Délégation",
                    null, null, false, confidentialite, null, null, false));
        }
        return attributions;
    }

    private static Set<CodePermission> elementaires(Set<CodePermission> permissions) {
        Set<CodePermission> e = EnumSet.noneOf(CodePermission.class);
        for (CodePermission p : permissions) {
            if (p.categorie() != CodePermission.Categorie.ADMINISTRATION) e.add(p);
        }
        return e;
    }
}
