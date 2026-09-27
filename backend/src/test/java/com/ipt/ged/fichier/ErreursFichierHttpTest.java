package com.ipt.ged.fichier;

import com.ipt.ged.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codes 413, 415, 422 (et 503, 404) du lot stockage rendus par le gestionnaire
 * commun au format {@code application/problem+json}, code métier conservé.
 */
class ErreursFichierHttpTest {

    private final GlobalExceptionHandler gestionnaire = new GlobalExceptionHandler();
    private final MockHttpServletRequest requete = new MockHttpServletRequest("POST", "/api/v1/documents");

    private void verifier(ErreurFichierException e, int statut, String code) {
        ResponseEntity<ProblemDetail> r = gestionnaire.fichier(e, requete);
        assertEquals(statut, r.getStatusCode().value());
        ProblemDetail corps = r.getBody();
        assertNotNull(corps);
        assertEquals(statut, corps.getStatus());
        assertEquals(code, corps.getProperties().get("code"));
        assertEquals("/api/v1/documents", corps.getInstance().toString());
        assertFalse(corps.getDetail().isBlank());
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
        ResponseEntity<Object> r = new GestionnaireExpose().multipart(
                new MaxUploadSizeExceededException(200L * 1024 * 1024), new ServletWebRequest(requete));
        assertEquals(413, r.getStatusCode().value());
        ProblemDetail corps = (ProblemDetail) r.getBody();
        assertNotNull(corps);
        assertEquals(413, corps.getStatus());
        assertEquals("FICHIER_TROP_VOLUMINEUX", corps.getProperties().get("code"));
    }

    /** Donne accès à la méthode protégée héritée de ResponseEntityExceptionHandler. */
    private static final class GestionnaireExpose extends GlobalExceptionHandler {
        ResponseEntity<Object> multipart(MaxUploadSizeExceededException e, ServletWebRequest w) {
            return handleMaxUploadSizeExceededException(e, new HttpHeaders(), HttpStatus.PAYLOAD_TOO_LARGE, w);
        }
    }
}
