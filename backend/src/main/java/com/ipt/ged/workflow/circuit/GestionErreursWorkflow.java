package com.ipt.ged.workflow.circuit;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduction HTTP des refus du workflow (400, 403, 409 avec {@code code}), au
 * format actuel des erreurs de l'API. Le gestionnaire commun appartient à
 * dev2 : ce conseil est provisoire et disparaît avec son contrat problem+json.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GestionErreursWorkflow {

    @ExceptionHandler(ErreurWorkflowException.class)
    public ResponseEntity<Map<String, Object>> refus(ErreurWorkflowException ex) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("timestamp", Instant.now());
        corps.put("status", ex.statut().value());
        corps.put("code", ex.code());
        corps.put("message", ex.getMessage());
        return ResponseEntity.status(ex.statut()).body(corps);
    }
}
