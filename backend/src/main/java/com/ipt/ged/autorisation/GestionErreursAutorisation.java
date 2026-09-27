package com.ipt.ged.autorisation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduction HTTP des conflits du lot autorisation (409), au format actuel des
 * erreurs de l'API. Le gestionnaire commun appartient à dev2 : ce conseil est
 * provisoire et disparaît avec la fusion de son contrat problem+json.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GestionErreursAutorisation {

    @ExceptionHandler(ConflitAutorisationException.class)
    public ResponseEntity<Map<String, Object>> conflit(ConflitAutorisationException ex) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("timestamp", Instant.now());
        corps.put("status", 409);
        corps.put("code", ex.code());
        corps.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(corps);
    }
}
