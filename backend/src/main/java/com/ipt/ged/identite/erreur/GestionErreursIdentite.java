package com.ipt.ged.identite.erreur;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduction des {@link ErreurIdentite} en réponses HTTP, au format des autres
 * erreurs de l'API ({@code timestamp}, {@code status}, {@code message}) plus un
 * {@code code} stable.
 *
 * <p>Volontairement distinct du gestionnaire commun, dont dev2 est propriétaire
 * (passage à problem+json) : le jour où {@code ExceptionMetier} existe, les
 * erreurs d'identité en héritent et cette classe est supprimée.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GestionErreursIdentite {

    @ExceptionHandler(ErreurIdentite.class)
    public ResponseEntity<Map<String, Object>> traiter(ErreurIdentite e) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("timestamp", Instant.now());
        corps.put("status", e.getStatut().value());
        corps.put("code", e.getCode());
        corps.put("message", e.getMessage());
        ResponseEntity.BodyBuilder reponse = ResponseEntity.status(e.getStatut());
        if (e instanceof TropDeTentativesException t) {
            reponse.header(HttpHeaders.RETRY_AFTER, String.valueOf(t.getReessayerDansSecondes()));
        }
        return reponse.body(corps);
    }
}
