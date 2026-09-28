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
import com.ipt.ged.indexation.IndexationService;
import com.ipt.ged.indexation.dto.GroupeResponse;
import com.ipt.ged.indexation.dto.RechercheRequest;
import com.ipt.ged.indexation.dto.ResultatResponse;
import com.ipt.ged.recherche.CriteresMetadonnees;
import com.ipt.ged.recherche.FragmentSql;
import com.ipt.ged.recherche.PageResultats;
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
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *       pagination), critères d'index de {@link IndexationService#rechercher}
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
    private final IndexationService indexation;
    private final SearchIndexer pleinTexte;
    private final ServiceDroitsEffectifs droits;
    private final ControleAcces controle;
    private final UtilisateurRepository utilisateurs;
    private final JdbcTemplate jdbc;

    public ServiceContratApi(WorkSpaceService espaces, WorkSpaceRepository noeuds, IndexationService indexation,
                             SearchIndexer pleinTexte, ServiceDroitsEffectifs droits, ControleAcces controle,
                             UtilisateurRepository utilisateurs, JdbcTemplate jdbc) {
        this.espaces = espaces;
        this.noeuds = noeuds;
        this.indexation = indexation;
        this.pleinTexte = pleinTexte;
        this.droits = droits;
        this.controle = controle;
        this.utilisateurs = utilisateurs;
        this.jdbc = jdbc;
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
        List<RechercheRequest.FiltreIndex> criteres = r.criteres() == null ? List.of() : r.criteres();

        // Critères d'index : documents retenus par l'indexation (droits à la source).
        Set<UUID> parCriteres = null;
        List<ResultatResponse> resultatsCriteres = List.of();
        if (!criteres.isEmpty() || !texte) {
            List<GroupeResponse> groupes = indexation.rechercher(new RechercheRequest(r.noeudId(), r.typeDocumentId(),
                    criteres, null, archives, r.canal()));
            resultatsCriteres = groupes.stream().flatMap(g -> g.documents().stream()).toList();
            parCriteres = new LinkedHashSet<>(resultatsCriteres.stream().map(ResultatResponse::id).toList());
        }

        if (texte) {
            List<FragmentSql> filtres = new ArrayList<>(new CriteresMetadonnees(r.typeDocumentId(), r.noeudId(),
                    r.deposeDu(), r.deposeAu(), CriteresMetadonnees.Archives.valueOf(archives), r.canal(),
                    Boolean.TRUE.equals(r.echeanceDepassee())).fragments());
            if (!criteres.isEmpty()) {
                if (parCriteres.isEmpty()) return new PageResultats(List.of(), 0, page, taille);
                filtres.add(new FragmentSql("d.id IN (:contrat_criteres)", Map.of("contrat_criteres", parCriteres)));
            }
            return pleinTexte.rechercher(new RequeteRecherche(r.texte(), page, taille,
                    r.tri() == null ? RequeteRecherche.Tri.PERTINENCE : r.tri(), filtres), appelant);
        }
        return pageSansTexte(resultatsCriteres, r, page, taille);
    }

    /** Sans plein texte : bornes de dépôt appliquées, tri en liste blanche, pagination. */
    private PageResultats pageSansTexte(List<ResultatResponse> resultats, RechercheContratRequest r, int page,
                                        int taille) {
        Map<UUID, Instant> deposes = new HashMap<>();
        Map<UUID, String> canaux = new HashMap<>();
        Map<UUID, LocalDate> echeances = new HashMap<>();
        if (!resultats.isEmpty()) {
            jdbc.query("SELECT id, created_at, canal_depot, echeance_conservation FROM document WHERE id = ANY (?)",
                    rs -> {
                        UUID id = rs.getObject(1, UUID.class);
                        deposes.put(id, instant(rs.getTimestamp(2)));
                        canaux.put(id, rs.getString(3));
                        echeances.put(id, rs.getObject(4, LocalDate.class));
                    },
                    (Object) resultats.stream().map(ResultatResponse::id).toArray(UUID[]::new));
        }
        Instant du = debut(r.deposeDu());
        Instant au = r.deposeAu() == null ? null : debut(r.deposeAu().plusDays(1));
        Comparator<PageResultats.Resultat> ordre = switch (r.tri() == null ? RequeteRecherche.Tri.DATE_DEPOT : r.tri()) {
            case NOM -> Comparator.comparing(PageResultats.Resultat::nom, Comparator.nullsLast(String::compareToIgnoreCase));
            case TYPE -> Comparator.comparing(PageResultats.Resultat::typeDocument,
                    Comparator.nullsLast(String::compareToIgnoreCase));
            default -> Comparator.comparing(PageResultats.Resultat::deposeLe,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        List<PageResultats.Resultat> tous = resultats.stream()
                .map(x -> new PageResultats.Resultat(x.id(), null, 0, List.of(), x.name(), x.typeDocument(),
                        x.workspace(), deposes.get(x.id()), x.statutConservation(), canaux.get(x.id()),
                        Echeances.depassee(echeances.get(x.id()))))
                // « Échéance dépassée » (T-112, §12.9) : même jour de référence que l'alerte et le plein texte.
                .filter(x -> !Boolean.TRUE.equals(r.echeanceDepassee()) || x.echeanceDepassee())
                .filter(x -> du == null || (x.deposeLe() != null && !x.deposeLe().isBefore(du)))
                .filter(x -> au == null || (x.deposeLe() != null && x.deposeLe().isBefore(au)))
                .sorted(ordre)
                .toList();
        int debut = (int) Math.min((long) page * taille, tous.size());
        return new PageResultats(tous.subList(debut, Math.min(debut + taille, tous.size())), tous.size(), page, taille);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Instant debut(LocalDate jour) {
        return jour == null ? null : jour.atStartOfDay().toInstant(ZoneOffset.UTC);
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
