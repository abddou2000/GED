package com.ipt.ged.cleapi;

import com.ipt.ged.cleapi.dto.DtoCleApi.ActivationRequest;
import com.ipt.ged.cleapi.dto.DtoCleApi.ApplicationRequest;
import com.ipt.ged.cleapi.dto.DtoCleApi.ApplicationResponse;
import com.ipt.ged.cleapi.dto.DtoCleApi.CleGenereeResponse;
import com.ipt.ged.cleapi.dto.DtoCleApi.CleResponse;
import com.ipt.ged.cleapi.dto.DtoCleApi.GenerationRequest;
import com.ipt.ged.cleapi.dto.DtoCleApi.RevocationRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Administration des applications clientes et de leurs clés d'API (DAT §5.4).
 * Refusée aux applications elles-mêmes (chaîne des applications et garde).
 */
@RestController
public class ClesApiController {

    private final ServiceClesApi service;

    public ClesApiController(ServiceClesApi service) {
        this.service = service;
    }

    @GetMapping("/api/v1/applications")
    public List<ApplicationResponse> lister() {
        return service.lister();
    }

    @GetMapping("/api/v1/applications/{id}")
    public ApplicationResponse consulter(@PathVariable UUID id) {
        return service.consulter(id);
    }

    @PostMapping("/api/v1/applications")
    public ResponseEntity<ApplicationResponse> creer(@Valid @RequestBody ApplicationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.creer(req));
    }

    @PutMapping("/api/v1/applications/{id}")
    public ApplicationResponse modifier(@PathVariable UUID id, @Valid @RequestBody ApplicationRequest req) {
        return service.modifier(id, req);
    }

    @PatchMapping("/api/v1/applications/{id}/activation")
    public ApplicationResponse activer(@PathVariable UUID id, @RequestBody ActivationRequest req) {
        return service.activer(id, req.active());
    }

    /** La valeur complète de la clé n'apparaît que dans cette réponse, jamais mise en cache. */
    @PostMapping("/api/v1/applications/{id}/cles")
    public ResponseEntity<CleGenereeResponse> generer(@PathVariable UUID id,
                                                      @Valid @RequestBody(required = false) GenerationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.generer(id, req));
    }

    @PostMapping("/api/v1/cles-api/{cleId}/regeneration")
    public ResponseEntity<CleGenereeResponse> regenerer(@PathVariable UUID cleId) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.regenerer(cleId));
    }

    @PostMapping("/api/v1/cles-api/{cleId}/revocation")
    public CleResponse revoquer(@PathVariable UUID cleId, @Valid @RequestBody RevocationRequest req) {
        return service.revoquer(cleId, req.motif());
    }
}
