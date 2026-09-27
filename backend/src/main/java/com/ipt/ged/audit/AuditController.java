package com.ipt.ged.audit;

import com.ipt.ged.common.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * API du journal d'audit (DAT §7.4.3) : consultation filtrée, export CSV et
 * JSON, scellements, vérification à la demande.
 *
 * <p><b>Lecture seule</b> : aucun point d'entrée ne modifie ni ne supprime un
 * enregistrement (revue technique D11) — la table ne l'accepterait d'ailleurs
 * pas (privilèges et déclencheurs).
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final ConsultationAudit consultation;
    private final VerificationAudit verification;
    private final GardeConsultationAudit garde;

    public AuditController(ConsultationAudit consultation, VerificationAudit verification,
                           GardeConsultationAudit garde) {
        this.consultation = consultation;
        this.verification = verification;
        this.garde = garde;
    }

    @GetMapping("/evenements")
    public PageResponse<LigneAudit> evenements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant du,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant au,
            @RequestParam(required = false) UUID utilisateur,
            @RequestParam(required = false) UUID application,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String objetType,
            @RequestParam(required = false) UUID objetId,
            @RequestParam(required = false) ResultatAudit resultat,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int taille) {
        return consultation.rechercher(
                new FiltreAudit(du, au, utilisateur, application, action, objetType, objetId, resultat), page, taille);
    }

    /** Export de la sélection ; en-tête {@code X-Empreinte-SHA256} : empreinte du fichier. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporter(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant du,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant au,
            @RequestParam(required = false) UUID utilisateur,
            @RequestParam(required = false) UUID application,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String objetType,
            @RequestParam(required = false) UUID objetId,
            @RequestParam(required = false) ResultatAudit resultat) {
        ConsultationAudit.Export e = consultation.exporter(
                new FiltreAudit(du, au, utilisateur, application, action, objetType, objetId, resultat), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(e.typeContenu()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(e.nomFichier(), StandardCharsets.UTF_8).build().toString())
                .header("X-Empreinte-SHA256", e.empreinte())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Empreinte-SHA256, Content-Disposition")
                .body(e.contenu());
    }

    @GetMapping("/scellements")
    public PageResponse<Map<String, Object>> scellements(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "50") int taille) {
        return consultation.scellements(page, taille);
    }

    /** Vérification de la chaîne à la demande (commande d'administration, §7.4.2) ; résultat tracé. */
    @PostMapping("/verifications")
    public VerificationAudit.Rapport verifier() {
        garde.verifier();
        return verification.verifier();
    }
}
