package com.ipt.ged.typedocument.retypage;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Re-typologisation d'un lot (§12.7) : {@code POST} crée le travail (202,
 * traitement de fond), {@code GET} suit sa progression et son rapport.
 * Réservé à {@code GERER_REFERENTIELS}.
 */
@RestController
@RequestMapping("/api/v1/type-documents/retypages")
public class RetypageController {

    private final ServiceRetypage service;

    public RetypageController(ServiceRetypage service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JobRetypage> lancer(@RequestBody ServiceRetypage.Demande demande) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.lancer(demande));
    }

    @GetMapping
    public List<JobRetypage> derniers() {
        return service.derniers();
    }

    @GetMapping("/{id}")
    public JobRetypage consulter(@PathVariable UUID id) {
        return service.consulter(id);
    }
}
