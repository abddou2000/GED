package com.ipt.ged.ocr;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OCRisation — lecture du contenu des documents.
 *
 * <p>Le paramétrage du moteur (activation, langues, résolution, pages lues) vit
 * dans {@code application.yml}, aux mains de l'exploitant : il n'existe pas
 * d'écran de réglages. Le choix d'utiliser ou non la lecture pour un dépôt donné
 * appartient à l'opérateur, via l'interrupteur de la fenêtre de dépôt.
 *
 * <ul>
 *   <li>{@code GET /documents/{id}/texte} — texte du document et provenance</li>
 *   <li>{@code GET /diagnostic}           — état de la chaîne d'extracteurs</li>
 *   <li>{@code GET /etat}                 — la lecture est-elle autorisée ici ?</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService service;

    @GetMapping("/documents/{id}/texte")
    public TexteExtrait texte(@PathVariable UUID id) {
        return service.lire(id);
    }

    /** Quels extracteurs sont en place, et lesquels répondent sur ce serveur. */
    @GetMapping("/diagnostic")
    public List<OcrService.EtatExtracteur> diagnostic() {
        return service.diagnostic();
    }

    /**
     * Consulté par la fenêtre de dépôt : quand la lecture est coupée côté
     * serveur, l'interrupteur est présenté désactivé avec son motif, plutôt que
     * de promettre un remplissage qui n'arrivera pas.
     */
    @GetMapping("/etat")
    public Etat etat() {
        return new Etat(service.estActif());
    }

    public record Etat(boolean actif) {}
}
