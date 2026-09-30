package com.ipt.ged.recherche;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.erreur.ParametresConnus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code GET /api/v1/recherche/plein-texte?q=…} : recherche dans le contenu
 * des documents (§4.4), combinée en ET avec des critères de métadonnées
 * (type, espace, période de dépôt, plage de date du document, confidentialité,
 * déposant), filtrée par droits à la source, triée (pertinence, date, nom,
 * type) et paginée. Les extraits sont rendus en segments (texte et
 * surlignage), jamais en HTML. Un paramètre inconnu est refusé (400
 * {@code PARAMETRE_INCONNU}) au lieu d'être ignoré (ANO-F-011).
 */
@RestController
@RequestMapping("/api/v1/recherche")
@ConditionalOnProperty(prefix = "ged.ocr.chaine", name = "actif", havingValue = "true")
public class RechercheController {

    /**
     * Paramètres acceptés ; tout autre est refusé. {@code size} est l'alias de
     * {@code taille} posé par les conventions de l'API (FiltreConventionsApi).
     */
    static final Set<String> PARAMETRES = Set.of("q", "page", "taille", "size", "tri", "typeDocumentId", "workspaceId",
            "du", "au", "archives", "canal", "echeanceDepassee", "dateDocumentDu", "dateDocumentAu",
            "confidentialite", "deposantUtilisateurId");

    private final SearchIndexer indexer;

    public RechercheController(SearchIndexer indexer) {
        this.indexer = indexer;
    }

    @GetMapping("/plein-texte")
    public PageResultats pleinTexte(@RequestParam("q") String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int taille,
                                    @RequestParam(defaultValue = "PERTINENCE") RequeteRecherche.Tri tri,
                                    @RequestParam(required = false) UUID typeDocumentId,
                                    @RequestParam(required = false) UUID workspaceId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate du,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate au,
                                    @RequestParam(defaultValue = "INCLURE") CriteresMetadonnees.Archives archives,
                                    @RequestParam(required = false) String canal,
                                    @RequestParam(defaultValue = "false") boolean echeanceDepassee,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDocumentDu,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDocumentAu,
                                    @RequestParam(required = false) Confidentialite confidentialite,
                                    @RequestParam(required = false) UUID deposantUtilisateurId,
                                    Authentication utilisateur, HttpServletRequest requete) {
        ParametresConnus.exiger(requete, PARAMETRES);
        if (q.length() > 500) {
            throw new IllegalArgumentException("La recherche ne peut pas dépasser 500 caractères.");
        }
        CriteresMetadonnees criteres = new CriteresMetadonnees(typeDocumentId, workspaceId, du, au, archives, canal,
                echeanceDepassee);
        List<FragmentSql> filtres = new ArrayList<>(criteres.fragments());
        filtres.addAll(new CriteresDocument(dateDocumentDu, dateDocumentAu, confidentialite, deposantUtilisateurId)
                .fragments());
        return indexer.rechercher(new RequeteRecherche(q, page, taille, tri, filtres), utilisateur);
    }
}
