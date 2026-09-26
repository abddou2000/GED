package com.ipt.ged.recherche;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/recherche/plein-texte?q=…} : recherche dans le contenu
 * des documents (§4.4), filtrée par droits à la source, paginée.
 *
 * <p>Actif avec la chaîne OCR ({@code ged.ocr.chaine.actif}). Les critères
 * multicritères sur les métadonnées s'ajouteront en {@link FragmentSql} une
 * fois le modèle documentaire UUID intégré.
 */
@RestController
@RequestMapping("/api/v1/recherche")
@ConditionalOnProperty(prefix = "ged.ocr.chaine", name = "actif", havingValue = "true")
public class RechercheController {

    private final SearchIndexer indexer;

    public RechercheController(SearchIndexer indexer) {
        this.indexer = indexer;
    }

    @GetMapping("/plein-texte")
    public PageResultats pleinTexte(@RequestParam("q") String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int taille,
                                    @RequestParam(defaultValue = "PERTINENCE") RequeteRecherche.Tri tri,
                                    Authentication utilisateur) {
        if (q.length() > 500) {
            throw new IllegalArgumentException("La recherche ne peut pas dépasser 500 caractères.");
        }
        return indexer.rechercher(new RequeteRecherche(q, page, taille, tri, List.of()), utilisateur);
    }
}
