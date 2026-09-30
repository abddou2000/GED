package com.ipt.ged.fichier.integrite;

import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.ControleAcces;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Vérification d'intégrité à la demande (§6.1.4, T-059), réservée à
 * l'Administrateur ({@code /api/v1/admin/**}) et à la permission
 * {@code SUPERVISER_TRAITEMENTS} ; chaque demande est tracée
 * ({@code INTEGRITE_VERIFIEE}).
 *
 * <ul>
 *   <li>{@code POST /api/v1/admin/integrite/documents/{id}} : vérifie sur le
 *       champ les versions et copies de conservation du document, bilan par
 *       fichier (200) ; 404 si le document n'existe pas ;</li>
 *   <li>{@code POST /api/v1/admin/integrite/verification} : lance la
 *       vérification du fonds entier en tâche de fond (202), 409 si une passe
 *       (planifiée ou demandée) est déjà en cours ;</li>
 *   <li>{@code GET /api/v1/admin/integrite/verification} : état de la passe en
 *       cours ou de la dernière (début, fin, décompte par statut).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/integrite")
public class IntegriteController {

    private final VerificationALaDemande verification;
    private final ControleAcces controle;

    public IntegriteController(VerificationALaDemande verification, ControleAcces controle) {
        this.verification = verification;
        this.controle = controle;
    }

    @PostMapping("/documents/{id}")
    public VerificationALaDemande.BilanDocument verifierDocument(@PathVariable UUID id) {
        controle.exigerAdministration(CodePermission.SUPERVISER_TRAITEMENTS);
        return verification.verifierDocument(id)
                .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + id));
    }

    @PostMapping("/verification")
    public ResponseEntity<VerificationPeriodique.Etat> verifierFonds() {
        controle.exigerAdministration(CodePermission.SUPERVISER_TRAITEMENTS);
        boolean demarree = verification.verifierFonds();
        return ResponseEntity.status(demarree ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(verification.etatFonds());
    }

    @GetMapping("/verification")
    public VerificationPeriodique.Etat etat() {
        controle.exigerAdministration(CodePermission.SUPERVISER_TRAITEMENTS);
        return verification.etatFonds();
    }
}
