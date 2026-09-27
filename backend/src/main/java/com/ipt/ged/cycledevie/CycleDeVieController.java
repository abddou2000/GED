package com.ipt.ged.cycledevie;

import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.document.evenement.Acteur;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des documents : purge définitive (§12.5), archivage d'un
 * document ou d'un dossier entier (§12.6, D10), export de dossier (§12.10).
 * Aucune de ces opérations n'est automatique : chacune est une action
 * explicite d'un utilisateur.
 */
@RestController
public class CycleDeVieController {

    private final PurgeService purge;
    private final ArchivageService archivage;
    private final ArchivageDossiers archivageDossiers;
    private final ExportDossiers exports;

    public CycleDeVieController(PurgeService purge, ArchivageService archivage, ArchivageDossiers archivageDossiers,
                                ExportDossiers exports) {
        this.purge = purge;
        this.archivage = archivage;
        this.archivageDossiers = archivageDossiers;
        this.exports = exports;
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

    /* ---------- Export de dossier ---------- */

    /**
     * Exporte un dossier : l'archive ZIP en flux (200), ou, au-delà de 500
     * documents ou de 2 Go, un export de fond (202) à retrouver dans
     * {@code GET /api/v1/exports}.
     */
    @PostMapping("/api/v1/exports/dossiers/{dossierId}")
    public ResponseEntity<?> exporter(@PathVariable UUID dossierId) throws java.io.IOException {
        Authentication utilisateur = SecurityContextHolder.getContext().getAuthentication();
        ExportDossiers.Selection s = exports.selectionner(dossierId, utilisateur);
        if (exports.enTraitementDeFond(s)) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(exports.differer(s, ActeurCourant.employeId()));
        }
        exports.journaliser(s, Acteur.courant(), null);
        // Archive produite par un fil dédié dans un tube, lu et servi dans le
        // fil de la requête : seul ce dernier touche à la réponse.
        InputStreamResource corps = new InputStreamResource(exports.fluxZip(s));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(ExportDossiers.nettoyer(s.dossierNom(), "export") + ".zip"))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(corps);
    }

    /** Exports de fond de l'utilisateur. */
    @GetMapping("/api/v1/exports")
    public List<ExportDossiers.Export> mesExports() {
        return exports.exports(ActeurCourant.employeId());
    }

    @GetMapping("/api/v1/exports/{id}")
    public ExportDossiers.Export export(@PathVariable UUID id) {
        return exports.export(id, ActeurCourant.employeId())
                .orElseThrow(() -> ErreurCycleDeVie.introuvable("Export " + id));
    }

    @GetMapping("/api/v1/exports/{id}/fichier")
    public ResponseEntity<Resource> telechargerExport(@PathVariable UUID id) {
        UUID moi = ActeurCourant.employeId();
        ExportDossiers.Export e = exports.export(id, moi).orElseThrow(() -> ErreurCycleDeVie.introuvable("Export " + id));
        InputStreamResource corps = new InputStreamResource(exports.ouvrir(id, moi));
        ResponseEntity.BodyBuilder r = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition(ExportDossiers.nettoyer(e.dossierNom(), "export") + ".zip"))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/zip"));
        if (e.tailleOctets() != null) r.contentLength(e.tailleOctets());
        return r.body(corps);
    }

    private static String disposition(String nom) {
        StringBuilder ascii = new StringBuilder();
        for (char c : nom.toCharArray()) ascii.append(c < 0x20 || c > 0x7E || c == '"' || c == '\\' ? '_' : c);
        StringBuilder utf8 = new StringBuilder();
        for (byte b : nom.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            boolean sur = (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                    || "!#$&+-.^_`|~".indexOf(v) >= 0;
            utf8.append(sur ? String.valueOf((char) v) : String.format("%%%02X", v));
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
    }
}
