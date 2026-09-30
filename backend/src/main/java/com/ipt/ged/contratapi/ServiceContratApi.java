package com.ipt.ged.contratapi;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.autorisation.admin.ServiceDroitsEffectifs;
import com.ipt.ged.cleapi.ApplicationAuthentifiee;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.common.erreur.RessourceIntrouvableException;
import com.ipt.ged.contratapi.dto.DtoContratApi.DossierRequest;
import com.ipt.ged.contratapi.dto.DtoContratApi.RechercheContratRequest;
import com.ipt.ged.document.conservation.Echeances;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.indexation.CriteresIndexSql;
import com.ipt.ged.recherche.CriteresMetadonnees;
import com.ipt.ged.recherche.FragmentSql;
import com.ipt.ged.recherche.PageResultats;
import com.ipt.ged.recherche.PredicatDroits;
import com.ipt.ged.recherche.RequeteRecherche;
import com.ipt.ged.recherche.SearchIndexer;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkSpaceService;
import com.ipt.ged.workspace.WorkspaceStatus;
import com.ipt.ged.workspace.dto.WorkSpaceRequest;
import com.ipt.ged.workspace.dto.WorkSpaceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chemins du contrat d'API (DAT §5.3.1) qui n'avaient pas d'équivalent exact.
 * <b>Aucune règle n'est réimplémentée</b> : chaque opération délègue au service
 * métier du lot propriétaire, qui applique les droits (point d'application
 * unique), l'audit, le verrou et l'archivage.
 *
 * <ul>
 *   <li>dossier : {@link WorkSpaceService#create} (Déposer sur le parent) ;</li>
 *   <li>recherche : plein texte de {@link SearchIndexer} (droits à la source,
 *       pagination), critères d'index traduits en SQL ({@link CriteresIndexSql})
 *       (droits à la source), combinés en ET ;</li>
 *   <li>droits : {@link ServiceDroitsEffectifs} (même fonction de décision).</li>
 * </ul>
 */
@Service
public class ServiceContratApi {

    static final int TAILLE_DEFAUT = 50;
    static final int TAILLE_MAX = 200;

    private final WorkSpaceService espaces;
    private final WorkSpaceRepository noeuds;
    private final CriteresIndexSql criteresIndex;
    private final PredicatDroits perimetre;
    private final SearchIndexer pleinTexte;
    private final ServiceDroitsEffectifs droits;
    private final ControleAcces controle;
    private final UtilisateurRepository utilisateurs;
    private final NamedParameterJdbcTemplate jdbc;

    public ServiceContratApi(WorkSpaceService espaces, WorkSpaceRepository noeuds, CriteresIndexSql criteresIndex,
                             PredicatDroits perimetre, SearchIndexer pleinTexte, ServiceDroitsEffectifs droits,
                             ControleAcces controle, UtilisateurRepository utilisateurs, JdbcTemplate jdbc) {
        this.espaces = espaces;
        this.noeuds = noeuds;
        this.criteresIndex = criteresIndex;
        this.perimetre = perimetre;
        this.pleinTexte = pleinTexte;
        this.droits = droits;
        this.controle = controle;
        this.utilisateurs = utilisateurs;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /* ------------------------------------------------------------ dossier */

    /**
     * Crée un dossier sous le nœud donné. Le dossier suit la règle de workflow
     * de ses ancêtres (§12.8) ; son propriétaire est la personne de la requête (l'utilisateur, ou
     * l'utilisateur délégué d'une application), à défaut celui du parent.
     */
    public WorkSpaceResponse creerDossier(UUID parentId, DossierRequest demande) {
        WorkSpace parent = noeuds.findById(parentId).filter(n -> !n.isSupprime())
                .orElseThrow(RessourceIntrouvableException::new);
        // Le droit (Déposer sur le parent, 404 hors périmètre) est exigé par le service
        // avant toute écriture ; rien de ce qui suit ne révèle le parent à un tiers.
        UUID proprietaire = Optional.ofNullable(ActeurCourant.employeId())
                .orElse(parent.getOwner() != null ? parent.getOwner().getId() : null);
        String code = demande.code() != null && !demande.code().isBlank() ? demande.code().trim()
                : "DOS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return espaces.create(new WorkSpaceRequest(demande.nom().trim(), code, demande.description(),
                WorkspaceStatus.ACTIF, proprietaire, parentId,
                // Aucune règle propre : le dossier suit celle du nœud le plus proche (E8).
                null, null));
    }

    /* ------------------------------------------------------------ recherche */

    public PageResultats rechercher(RechercheContratRequest r, Authentication appelant) {
        int page = r.page() == null ? 0 : r.page();
        int taille = r.taille() == null || r.taille() < 1 ? TAILLE_DEFAUT : Math.min(r.taille(), TAILLE_MAX);
        String archives = r.archives() == null || r.archives().isBlank() ? "INCLURE" : r.archives();
        boolean texte = r.texte() != null && !r.texte().isBlank();

        // Métadonnées (espace ou dossier : emplacement principal ou rattachement, §12.4),
        // puis critères d'index, en SQL et combinés en ET (R32 : rien n'est chargé
        // en mémoire au-delà de la page).
        List<FragmentSql> filtres = new ArrayList<>(new CriteresMetadonnees(r.typeDocumentId(), r.noeudId(),
                r.deposeDu(), r.deposeAu(), CriteresMetadonnees.Archives.valueOf(archives), r.canal(),
                Boolean.TRUE.equals(r.echeanceDepassee())).fragments());
        filtres.addAll(criteresIndex.fragments(r.criteres()));

        if (texte) {
            return pleinTexte.rechercher(new RequeteRecherche(r.texte(), page, taille,
                    r.tri() == null ? RequeteRecherche.Tri.PERTINENCE : r.tri(), filtres), appelant);
        }
        return pageSansTexte(filtres, r.tri(), page, taille, appelant);
    }

    /**
     * Sans plein texte : filtrage par droits à la source, tri en liste blanche et
     * pagination par la base ; total exact sur le seul périmètre autorisé.
     */
    private PageResultats pageSansTexte(List<FragmentSql> filtres, RequeteRecherche.Tri tri, int page, int taille,
                                        Authentication appelant) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("contrat_taille", taille)
                .addValue("contrat_decalage", (long) page * taille);
        StringBuilder ou = new StringBuilder(" FROM document d LEFT JOIN type_document t ON t.id = d.type_document_id"
                + " LEFT JOIN noeud w ON w.id = d.noeud_principal_id WHERE NOT d.supprime");
        List<FragmentSql> tous = new ArrayList<>();
        tous.add(perimetre.predicat("d.id", appelant));
        tous.addAll(filtres);
        for (FragmentSql f : tous) {
            ou.append(" AND (").append(f.sql()).append(')');
            for (Map.Entry<String, Object> e : f.parametres().entrySet()) {
                if (p.hasValue(e.getKey())) throw new IllegalArgumentException("Paramètre SQL en double : " + e.getKey());
                p.addValue(e.getKey(), e.getValue());
            }
        }
        // Sans texte, la pertinence n'a pas de sens : la date du document est la clé
        // de tri par défaut (§12.7, P-21, ANO-E7-004), départagée par l'identifiant.
        // Colonnes en liste blanche : jamais de texte de l'appelant dans l'ORDER BY.
        String ordre = switch (tri == null ? RequeteRecherche.Tri.DATE_DOCUMENT : tri) {
            case NOM -> "lower(d.name) ASC, d.id DESC";
            case TYPE -> "lower(t.type_de_document) ASC NULLS LAST, d.id DESC";
            case DATE_DEPOT, INDEXATION_RECENTE -> "d.created_at DESC NULLS LAST, d.id DESC";
            case DATE_DOCUMENT, PERTINENCE -> "d.date_document DESC NULLS LAST, d.id DESC";
        };
        Long total = jdbc.queryForObject("SELECT count(*)" + ou, p, Long.class);
        List<PageResultats.Resultat> lignes = jdbc.query("SELECT d.id, d.name, t.type_de_document, w.nom AS espace,"
                        + " d.created_at, d.statut_conservation, d.canal_depot, d.echeance_conservation, d.date_document"
                        + ou + " ORDER BY " + ordre + " LIMIT :contrat_taille OFFSET :contrat_decalage", p,
                (rs, i) -> new PageResultats.Resultat(rs.getObject("id", UUID.class), null, 0, List.of(),
                        rs.getString("name"), rs.getString("type_de_document"), rs.getString("espace"),
                        instant(rs.getTimestamp("created_at")), rs.getString("statut_conservation"),
                        rs.getString("canal_depot"),
                        // « Échéance dépassée » (T-112, §12.9) : même jour de référence que l'alerte.
                        Echeances.depassee(rs.getObject("echeance_conservation", LocalDate.class)),
                        rs.getObject("date_document", LocalDate.class)));
        return new PageResultats(lignes, total == null ? 0 : total, page, taille);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /* ------------------------------------------------------------ droits */

    /**
     * Droits effectifs sur un nœud ou un document (DAT §5.3.1). L'objet doit être
     * visible de l'appelant (404 sinon, P5). Sans {@code pourUtilisateur} : les
     * droits de l'appelant (utilisateur, clé d'API, ou droits de la délégation).
     * Pour un tiers : la permission d'administration des droits est exigée, sauf
     * s'il s'agit de l'utilisateur pour le compte duquel l'application agit.
     */
    public ServiceDroitsEffectifs.DroitsEffectifs droits(UUID noeudId, UUID documentId, String pourUtilisateur,
                                                          Authentication appelant) {
        if (noeudId != null) {
            controle.exigerSurNoeud(CodePermission.CONSULTER, noeudId);
        } else {
            controle.exigerLectureDocument(documentId);
        }
        if (pourUtilisateur == null || pourUtilisateur.isBlank()) {
            return droits.calculerPourAppelant(appelant, noeudId, documentId);
        }
        Utilisateur tiers = identite(pourUtilisateur.trim()).orElseThrow(RessourceIntrouvableException::new);
        if (!estLAppelantOuSonDelegue(tiers.getId(), appelant)) {
            controle.exigerAdministration(CodePermission.GERER_ROLES_HABILITATIONS);
        }
        return droits.calculer(tiers.getId(), noeudId, documentId);
    }

    private Optional<Utilisateur> identite(String valeur) {
        try {
            return utilisateurs.findById(UUID.fromString(valeur));
        } catch (IllegalArgumentException e) {
            return utilisateurs.findByIdentifiant(valeur);
        }
    }

    private static boolean estLAppelantOuSonDelegue(UUID utilisateurId, Authentication appelant) {
        if (appelant instanceof ApplicationAuthentifiee a) {
            return a.deleguee() != null && utilisateurId.equals(a.deleguee().getUtilisateurId());
        }
        return appelant != null && appelant.getPrincipal() instanceof UtilisateurConnecte u
                && utilisateurId.equals(u.getUtilisateurId());
    }
}
