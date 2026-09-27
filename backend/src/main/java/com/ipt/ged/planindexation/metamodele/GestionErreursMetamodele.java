package com.ipt.ged.planindexation.metamodele;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 400 {@code METADONNEES_INVALIDES} avec le dictionnaire d'erreurs par champ.
 * Provisoire, comme les autres conseils des lots : disparaît avec le contrat
 * problem+json de dev2 (propriété {@code erreurs} de l'exception métier).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GestionErreursMetamodele {

    @ExceptionHandler(MetadonneesInvalidesException.class)
    public ResponseEntity<Map<String, Object>> invalides(MetadonneesInvalidesException ex) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("timestamp", Instant.now());
        corps.put("status", 400);
        corps.put("code", MetadonneesInvalidesException.CODE);
        corps.put("message", ex.getMessage());
        corps.put("erreurs", ex.erreurs());
        return ResponseEntity.badRequest().body(corps);
    }
}
