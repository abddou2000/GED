package com.ipt.ged.fichier;

import com.ipt.ged.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codes 413, 415, 422 (et 503) rendus par le gestionnaire commun, au format
 * habituel ({@code timestamp}, {@code status}, {@code message}) augmenté de
 * {@code code}.
 */
class ErreursFichierHttpTest {

    private final GlobalExceptionHandler gestionnaire = new GlobalExceptionHandler();

    private void verifier(ErreurFichierException e, int statut, String code) {
        ResponseEntity<Map<String, Object>> r = gestionnaire.handleFichier(e);
        assertEquals(statut, r.getStatusCode().value());
        Map<String, Object> corps = r.getBody();
        assertNotNull(corps);
        assertEquals(statut, corps.get("status"));
        assertEquals(code, corps.get("code"));
        assertNotNull(corps.get("timestamp"));
        assertFalse(((String) corps.get("message")).isBlank());
    }

    @Test
    @DisplayName("413 FICHIER_TROP_VOLUMINEUX, 415 FORMAT_NON_AUTORISE, 422 FICHIER_INFECTE, 503 ANTIVIRUS_INDISPONIBLE")
    void codes() {
        verifier(Refus.tropVolumineux(100L * 1024 * 1024), 413, "FICHIER_TROP_VOLUMINEUX");
        verifier(Refus.formatNonAutorise("application/x-dosexec"), 415, "FORMAT_NON_AUTORISE");
        verifier(Refus.infecte("Eicar-Test-Signature"), 422, "FICHIER_INFECTE");
        verifier(Refus.antivirusIndisponible(new java.io.IOException("refusé")), 503, "ANTIVIRUS_INDISPONIBLE");
        verifier(Refus.introuvable("x"), 404, "FICHIER_INTROUVABLE");
    }

    @Test
    @DisplayName("Le message de 503 ne révèle pas l'infrastructure (hôte, port)")
    void pasDeFuite() {
        ErreurFichierException e = Refus.antivirusIndisponible(new java.io.IOException("Connection refused clamav.interne:3310"));
        assertFalse(e.getMessage().contains("3310"));
    }

    @Test
    @DisplayName("Plafond multipart dépassé → 413 au même format")
    void multipart() {
        ResponseEntity<Map<String, Object>> r = gestionnaire.handleTailleMultipart(
                new MaxUploadSizeExceededException(200L * 1024 * 1024));
        assertEquals(413, r.getStatusCode().value());
        assertEquals(413, r.getBody().get("status"));
        assertEquals("FICHIER_TROP_VOLUMINEUX", r.getBody().get("code"));
    }
}
