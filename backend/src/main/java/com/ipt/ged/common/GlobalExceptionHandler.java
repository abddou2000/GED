package com.ipt.ged.common;

import com.ipt.ged.fichier.CodesErreurFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Traduit les erreurs en réponses JSON exploitables par le frontend
 * (messages de validation champ par champ, ressource introuvable…).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Erreurs de validation (@Valid) → 400 avec le détail par champ. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 400);
        body.put("message", "Données invalides");
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    /** Ressource introuvable → 404. */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(EntityNotFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 404);
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Accès refusé → 403, au même format JSON que les autres erreurs.
     *
     * <p>L'autorisation par rôle a disparu (utilisateur unique) : il n'y a plus
     * de {@code @PreAuthorize} pour lever cette exception. Une source subsiste
     * cependant — {@code ProfilService.profilCourant} la lève quand le principal
     * reçu est nul — et le gestionnaire est conservé pour elle : sans lui, la
     * réponse sortirait avec un corps <b>vide</b> alors que le frontend lit
     * systématiquement {@code $.message}, et présenterait un refus comme une
     * panne indéterminée.
     *
     * <p>Ce gestionnaire ne peut pas transformer un 401 en 403 : l'API est
     * fermée par défaut, un appelant anonyme est arrêté par la chaîne de filtres
     * (401) avant même d'atteindre un contrôleur.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 403);
        body.put("message", "Accès refusé.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** Règle métier violée (code déjà pris, déplacement invalide…) → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 400);
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Donnée refusée par le schéma (chaîne trop longue, unicité, colonne non
     * nulle) → 400.
     *
     * <p>Sans ce gestionnaire, une saisie de 256 caractères ressortait en
     * <b>500 nu</b> : le client ne pouvait pas distinguer sa propre faute d'une
     * panne du serveur, et n'apprenait ni quel champ ni quelle limite. Les
     * champs connus sont désormais bornés en amont ({@link Limites}) et nomment
     * eux-mêmes le champ ; ce filet couvre les colonnes qui auraient échappé au
     * contrôle applicatif, en remontant au moins la contrainte que la base a
     * refusée.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrite(DataIntegrityViolationException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 400);
        body.put("message", "Donnée refusée par la base : " + causeLisible(ex));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Deux écritures concurrentes sur la même ressource → 409.
     *
     * <p>Le dépôt de version prend un verrou pessimiste sur le document : les
     * requêtes simultanées se sérialisent et aboutissent toutes. Reste le cas où
     * le verrou n'est pas obtenu (délai d'attente, interblocage détecté par la
     * base) : c'est un conflit d'accès, que l'appelant peut rejouer — pas une
     * panne. Le rendre en <b>409</b> plutôt qu'en 500 dit exactement cela.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, Object>> handleConflit(ConcurrencyFailureException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 409);
        body.put("message", "Le document est en cours de modification par une autre requête. Réessayez.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Refus et pannes liés aux fichiers (§6.1.5) : 413, 415, 422
     * {@code FICHIER_INFECTE}, 503 antivirus indisponible…
     *
     * <p>Même format que les autres erreurs, augmenté du champ {@code code} :
     * stable, il laisse le client distinguer deux refus de même statut sans
     * analyser un libellé destiné à l'utilisateur.
     */
    @ExceptionHandler(ErreurFichierException.class)
    public ResponseEntity<Map<String, Object>> handleFichier(ErreurFichierException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", ex.statut().value());
        body.put("message", ex.getMessage());
        body.put("code", ex.code());
        return ResponseEntity.status(ex.statut()).body(body);
    }

    /**
     * Requête multipart au-delà du plafond de plateforme → 413.
     *
     * <p>Le refus est levé par la couche multipart avant tout contrôleur ; sans
     * ce gestionnaire il sortait en 500, alors que c'est une faute de
     * l'appelant, et qu'il n'apprenait pas la limite.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTailleMultipart(MaxUploadSizeExceededException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", 413);
        body.put("message", "Fichier trop volumineux : plafond de la plateforme dépassé.");
        body.put("code", CodesErreurFichier.FICHIER_TROP_VOLUMINEUX);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * Cause racine ramenée à une phrase. On ne recopie pas le message SQL brut :
     * il expose le schéma (nom de table, de contrainte) à l'appelant.
     */
    private static String causeLisible(DataIntegrityViolationException ex) {
        Throwable racine = ex.getMostSpecificCause();
        String message = racine != null && racine.getMessage() != null ? racine.getMessage() : "";
        String bas = message.toLowerCase();
        if (bas.contains("too long") || bas.contains("value too large")
                || bas.contains("data truncation") || bas.contains("trop long")) {
            return "une valeur dépasse la longueur autorisée pour sa colonne ("
                    + Limites.TEXTE + " caractères pour un champ texte).";
        }
        if (bas.contains("unique") || bas.contains("duplicate")) {
            return "cette valeur est déjà utilisée.";
        }
        if (bas.contains("null")) {
            return "un champ obligatoire n'a pas été renseigné.";
        }
        return "contrainte d'intégrité violée.";
    }
}
