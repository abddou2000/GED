package com.ipt.ged.signature;

import com.ipt.ged.signature.dto.SignatureResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API REST du circuit de signature (« Mes workflow »). Base : /api/v1/signatures
 *
 * <p>Faute d'authentification à cette phase, l'utilisateur agissant est passé
 * explicitement (employeId) — le service vérifie qu'il est bien l'assigné.</p>
 */
@RestController
@RequestMapping("/api/v1/signatures")
public class SignatureController {

    private final SignatureService service;

    public SignatureController(SignatureService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public List<SignatureResponse> pending(@RequestParam Long employeId) {
        return service.pending(employeId);
    }

    @GetMapping("/history")
    public List<SignatureResponse> history(@RequestParam Long employeId) {
        return service.history(employeId);
    }

    @GetMapping("/document/{documentId}")
    public List<SignatureResponse> documentCircuit(@PathVariable Long documentId) {
        return service.documentCircuit(documentId);
    }

    @PatchMapping("/{id}/approve")
    public SignatureResponse approve(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long employeId = body.get("employeId") != null ? Long.valueOf(body.get("employeId").toString()) : null;
        String motif = body.get("motif") != null ? body.get("motif").toString() : null;
        return service.approve(id, employeId, motif);
    }

    @PatchMapping("/{id}/reject")
    public SignatureResponse reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long employeId = body.get("employeId") != null ? Long.valueOf(body.get("employeId").toString()) : null;
        String motif = body.get("motif") != null ? body.get("motif").toString() : null;
        return service.reject(id, employeId, motif);
    }
}
