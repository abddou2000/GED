package com.ipt.ged.cycledevie;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des documents : purge définitive (§12.5), archivage d'un
 * document ou d'un dossier entier (§12.6, D10).
 * Aucune de ces opérations n'est automatique : chacune est une action
 * explicite d'un utilisateur.
 */
@RestController
public class CycleDeVieController {

    private final PurgeService purge;
    private final ArchivageService archivage;
    private final ArchivageDossiers archivageDossiers;

    public CycleDeVieController(PurgeService purge, ArchivageService archivage, ArchivageDossiers archivageDossiers) {
        this.purge = purge;
        this.archivage = archivage;
        this.archivageDossiers = archivageDossiers;
    }

    /* ---------- Purge définitive (corbeille) ---------- */

    @PostMapping("/api/v1/documents/{id}/purge")
    public ResponseEntity<Void> purger(@PathVariable UUID id) {
        purge.purger(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/documents/purge")
    public ResponseEntity<Void> purgerPlusieurs(@RequestBody Map<String, List<UUID>> corps) {
        purge.purger(corps.getOrDefault("ids", List.of()));
        return ResponseEntity.noContent().build();
    }

    /* ---------- Archivage d'un document ---------- */

    @PostMapping("/api/v1/documents/{id}/archivage")
    public ArchivageService.Resultat archiver(@PathVariable UUID id) {
        return archivage.archiver(id);
    }

    @DeleteMapping("/api/v1/documents/{id}/archivage")
    public ResponseEntity<Void> desarchiver(@PathVariable UUID id) {
        archivage.desarchiver(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/documents/{id}/conservation")
    public ArchivageService.Conservation conservation(@PathVariable UUID id) {
        return archivage.conservation(id);
    }

    /* ---------- Archivage d'un dossier entier ---------- */

    @PostMapping("/api/v1/archivage/dossiers/{dossierId}")
    public ResponseEntity<ArchivageDossiers.Job> archiverDossier(@PathVariable UUID dossierId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(archivageDossiers.archiverDossier(dossierId));
    }

    /** Retire le drapeau d'archivage du dossier ; ses documents restent archivés. */
    @DeleteMapping("/api/v1/archivage/dossiers/{dossierId}")
    public ResponseEntity<Void> retirerDrapeau(@PathVariable UUID dossierId) {
        archivageDossiers.retirerDrapeau(dossierId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/archivage/jobs")
    public List<ArchivageDossiers.Job> jobs(@RequestParam(required = false) UUID dossierId,
                                            @RequestParam(defaultValue = "50") int limite) {
        return archivageDossiers.jobs(dossierId, limite);
    }

    @GetMapping("/api/v1/archivage/jobs/{id}")
    public ArchivageDossiers.Job job(@PathVariable UUID id) {
        return archivageDossiers.job(id).orElseThrow(() -> ErreurCycleDeVie.introuvable("Job d'archivage " + id));
    }

    @PostMapping("/api/v1/archivage/jobs/{id}/annulation")
    public ArchivageDossiers.Job annuler(@PathVariable UUID id) {
        return archivageDossiers.annuler(id);
    }

    /** Rapport du job : un résultat par document ({@code resultat=EN_ATTENTE} pour les non traités). */
    @GetMapping("/api/v1/archivage/jobs/{id}/elements")
    public List<ArchivageDossiers.Element> elements(@PathVariable UUID id,
                                                    @RequestParam(required = false) String resultat,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int taille) {
        archivageDossiers.job(id).orElseThrow(() -> ErreurCycleDeVie.introuvable("Job d'archivage " + id));
        return archivageDossiers.elements(id, resultat, page, taille);
    }
}
