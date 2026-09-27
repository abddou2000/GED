package com.ipt.ged.stats;

import com.ipt.ged.accessgroup.AccessGroupRepository;
import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.signature.SignatureService;
import com.ipt.ged.workspace.WorkSpaceRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indicateurs synthétiques du tableau de bord.
 *
 * <p>Le tableau de bord est paramétrable : l'utilisateur choisit les encarts
 * qu'il affiche. Chacun doit donc s'appuyer sur un chiffre compté en base, et
 * non sur une estimation reconstituée côté navigateur à partir d'une page de
 * résultats — une « répartition » calculée sur les 200 derniers documents
 * n'est pas une répartition.</p>
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    /** Bornes du paramètre {@code jours} de {@link #depots(int)}. */
    private static final int JOURS_MIN = 7;
    private static final int JOURS_MAX = 180;

    private final WorkSpaceRepository workspaces;
    private final StatistiquesPerimetre documents;
    private final AccessGroupRepository accessGroups;
    private final SignatureService signatures;
    private final AccessPredicate droits;
    private final ControleAcces controle;

    public StatsController(WorkSpaceRepository workspaces, StatistiquesPerimetre documents,
                           AccessGroupRepository accessGroups, SignatureService signatures,
                           AccessPredicate droits, ControleAcces controle) {
        this.workspaces = workspaces;
        this.documents = documents;
        this.accessGroups = accessGroups;
        this.signatures = signatures;
        this.droits = droits;
        this.controle = controle;
    }

    public record Overview(long workspaces, long documents, long pendingSignatures, long accessGroups) {}

    /** Une part de la répartition par type de document. */
    public record Part(String label, long count) {}

    /** Un point de la courbe des dépôts : une journée, un nombre de documents. */
    public record Point(LocalDate date, long count) {}

    /**
     * Tuiles du tableau de bord.
     *
     * <p>{@code pendingSignatures} comptait auparavant TOUTES les demandes
     * {@code PENDING} de la base : celles d'autres approbateurs, celles dont
     * l'étape précédente n'est pas signée, et celles de documents mis à la
     * corbeille. La tuile annonçait 73 quand l'écran « Mes workflow » en
     * affichait 41, et rien ne permettait au lecteur de comprendre l'écart.
     *
     * <p>Le compteur est désormais produit par la même méthode que la liste
     * ({@code SignatureService.pending}), à partir du porteur du jeton. Un
     * chiffre de tableau de bord n'a de sens que s'il désigne une liste que
     * l'utilisateur peut ouvrir et traiter.
     */
    @GetMapping("/overview")
    public Overview overview(@AuthenticationPrincipal UtilisateurConnecte principal) {
        long enAttente = principal != null && principal.getEmployeId() != null
                ? signatures.nombreEnAttente(principal.getEmployeId()) : 0L;
        // Lot E3 (P5) : chaque tuile ne compte que le périmètre de l'appelant —
        // espaces couverts par ses habilitations, documents qu'il peut
        // consulter, et, hors administrateur des droits, ses propres groupes.
        var couverts = droits.noeudsVisibles(SecurityContextHolder.getContext().getAuthentication())
                .entrySet().stream().filter(java.util.Map.Entry::getValue).map(java.util.Map.Entry::getKey).toList();
        long espaces = couverts.isEmpty() ? 0 : workspaces.findAllById(couverts).stream()
                .filter(w -> !w.isSupprime()).count();
        long groupes = controle.administre(CodePermission.GERER_ROLES_HABILITATIONS)
                ? accessGroups.countBySupprimeFalse()
                : principal == null ? 0 : accessGroups.compterPourMembre(principal.getEmployeId());
        return new Overview(espaces, documents.documents(), enAttente, groupes);
    }

    /**
     * Répartition des documents vivants par type, du plus fourni au moins fourni.
     * Les documents sans type sont regroupés sous « Sans type » plutôt qu'écartés :
     * un total qui ne retombe pas sur celui de la tuile « Documents » serait
     * incompréhensible pour le lecteur.
     */
    @GetMapping("/par-type")
    public List<Part> parType() {
        return documents.parType().stream()
                .map(p -> new Part(p.libelle() == null || p.libelle().isBlank() ? "Sans type" : p.libelle(),
                                   p.total()))
                .toList();
    }

    /**
     * Dépôts par jour sur la fenêtre demandée.
     *
     * <p>La base ne renvoie que les journées où quelque chose a été déposé ; on
     * complète les journées vides à zéro. Sans cela, sept points étalés sur un
     * mois se liraient comme une activité continue.</p>
     */
    @GetMapping("/depots")
    public List<Point> depots(@RequestParam(defaultValue = "30") int jours) {
        int fenetre = Math.min(JOURS_MAX, Math.max(JOURS_MIN, jours));
        ZoneId zone = ZoneId.systemDefault();
        LocalDate debut = LocalDate.now(zone).minusDays(fenetre - 1L);

        Map<LocalDate, Long> parJour = new HashMap<>();
        for (Instant creation : documents.datesDeCreationDepuis(debut.atStartOfDay(zone).toInstant())) {
            parJour.merge(LocalDate.ofInstant(creation, zone), 1L, Long::sum);
        }

        List<Point> courbe = new ArrayList<>(fenetre);
        for (int i = 0; i < fenetre; i++) {
            LocalDate jour = debut.plusDays(i);
            courbe.add(new Point(jour, parJour.getOrDefault(jour, 0L)));
        }
        return courbe;
    }
}
