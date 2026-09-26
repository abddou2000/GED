package com.ipt.ged.ocr.file;

import com.ipt.ged.recherche.ReindexationComplete;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supervision des traitements OCR et réindexation complète, réservées à
 * l'Administrateur (§4.3.4, §4.4.1).
 *
 * <p><b>Droits</b> : l'API est aujourd'hui fermée aux seuls utilisateurs
 * authentifiés (compte unique). Le lot autorisation ajoutera la restriction au
 * profil Administrateur sur {@code /api/v1/admin/**}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(prefix = "ged.ocr.chaine", name = "actif", havingValue = "true")
public class SupervisionOcrController {

    private final OcrJobQueue file;
    private final ReindexationComplete reindexation;

    public SupervisionOcrController(OcrJobQueue file, ReindexationComplete reindexation) {
        this.file = file;
        this.reindexation = reindexation;
    }

    @GetMapping("/ocr/compteurs")
    public Map<StatutOcr, Long> compteurs() {
        return file.compterParStatut();
    }

    @GetMapping("/ocr/jobs")
    public List<OcrJob> jobs(@RequestParam(defaultValue = "OCR_ECHEC") StatutOcr statut,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int taille) {
        int t = Math.max(1, Math.min(taille, 200));
        return file.lister(statut, t, Math.max(0, page) * t);
    }

    /** Relance manuelle d'un job en échec. */
    @PostMapping("/ocr/jobs/{id}/relance")
    public ResponseEntity<Void> relancer(@PathVariable UUID id) {
        if (file.trouver(id).isEmpty()) throw new EntityNotFoundException("Job OCR introuvable : " + id);
        if (!file.relancer(id)) {
            throw new IllegalArgumentException("Seul un job en échec peut être relancé.");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/recherche/reindexation")
    public ResponseEntity<ReindexationComplete.Progression> reindexer() {
        boolean demarree = reindexation.demarrer();
        return ResponseEntity.status(demarree ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(reindexation.progression());
    }

    @GetMapping("/recherche/reindexation")
    public ReindexationComplete.Progression progression() {
        return reindexation.progression();
    }
}
