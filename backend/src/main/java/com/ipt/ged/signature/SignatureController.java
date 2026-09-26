package com.ipt.ged.signature;

import java.util.UUID;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.signature.dto.SignatureResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API REST du circuit de signature (« Mes workflow »). Base : /api/v1/signatures
 *
 * <p><b>L'acteur n'est jamais fourni par le client.</b> Il est lu dans le jeton
 * via {@link UtilisateurConnecte}. Auparavant l'identifiant de l'employé
 * arrivait en paramètre de requête ou dans le corps, et le service se contentait
 * de le comparer à l'assigné de l'étape : il suffisait donc d'envoyer
 * l'identifiant de l'assigné pour signer à sa place, avec son propre jeton. Le
 * contrôle d'appartenance ne vaut que si l'identité comparée vient du serveur.
 *
 * <p>Le paramètre {@code employeId} a été retiré des signatures plutôt que
 * simplement ignoré : un paramètre encore accepté laisserait croire à un appelant
 * — et au prochain développeur — qu'il agit sur le résultat.
 */
@RestController
@RequestMapping("/api/v1/signatures")
public class SignatureController {

    private final SignatureService service;

    public SignatureController(SignatureService service) {
        this.service = service;
    }

    /** GET /pending → MES signatures actionnables, celles du porteur du jeton. */
    @GetMapping("/pending")
    public List<SignatureResponse> pending(@AuthenticationPrincipal UtilisateurConnecte principal) {
        return service.pending(principal.getEmployeId());
    }

    /** GET /history → MON historique, celui du porteur du jeton. */
    @GetMapping("/history")
    public List<SignatureResponse> history(@AuthenticationPrincipal UtilisateurConnecte principal) {
        return service.history(principal.getEmployeId());
    }

    @GetMapping("/document/{documentId}")
    public List<SignatureResponse> documentCircuit(@PathVariable UUID documentId) {
        return service.documentCircuit(documentId);
    }

    /**
     * Relancer un circuit arrêté par un rejet.
     *
     * <p>Sans cette route, un refus était définitif : l'étape restait
     * {@code REJECTED} pour toujours et le document ne revenait dans aucune
     * file. Elle est ouverte à tout utilisateur authentifié, comme le reste :
     * relancer une validation après correction n'est pas un acte privilégié.</p>
     */
    @PatchMapping("/document/{documentId}/relancer")
    public List<SignatureResponse> relancer(@PathVariable UUID documentId) {
        return service.relancer(documentId);
    }

    /**
     * Approuver une étape.
     *
     * <p>Le seul contrôle est l'appartenance : être l'assigné de l'étape,
     * vérifié dans le service à partir du principal. La route elle-même est
     * fermée aux anonymes par la chaîne de filtres
     * ({@code anyRequest().authenticated()}).
     */
    @PatchMapping("/{id}/approve")
    public SignatureResponse approve(@PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     @AuthenticationPrincipal UtilisateurConnecte principal) {
        String motif = body != null && body.get("motif") != null ? body.get("motif").toString() : null;
        return service.approve(id, principal.getEmployeId(), motif);
    }

    /** Rejeter une étape — même raisonnement que {@link #approve}. */
    @PatchMapping("/{id}/reject")
    public SignatureResponse reject(@PathVariable UUID id,
                                    @RequestBody(required = false) Map<String, Object> body,
                                    @AuthenticationPrincipal UtilisateurConnecte principal) {
        String motif = body != null && body.get("motif") != null ? body.get("motif").toString() : null;
        return service.reject(id, principal.getEmployeId(), motif);
    }
}
