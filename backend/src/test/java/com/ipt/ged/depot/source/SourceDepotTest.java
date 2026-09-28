package com.ipt.ged.depot.source;

import com.ipt.ged.security.UtilisateurConnecte;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Origine d'un dépôt (T-040) : interface, application, bureau d'ordre, délégation. */
class SourceDepotTest {

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private static UtilisateurConnecte utilisateur(UUID id) {
        return new UtilisateurConnecte(id, "sbennani", UUID.randomUUID(), "Sara Bennani", Set.of(), Set.of(), null);
    }

    @Test
    @DisplayName("Utilisateur de l'interface : canal INTERFACE, déposant = son identité GED")
    void interfaceWeb() {
        UUID id = UUID.randomUUID();
        UtilisateurConnecte u = utilisateur(id);
        SourceDepot.Origine o = new SourceDepotParDefaut().origine(
                new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        assertEquals(CanalDepot.INTERFACE, o.canal());
        assertEquals(id, o.deposantUtilisateurId());
        assertNull(o.applicationId());
        assertFalse(o.delegue());
    }

    @Test
    @DisplayName("Application (ROLE_APPLICATION) : canal API ; bureau d'ordre reconnu à son code")
    void application() {
        TestingAuthenticationToken app = new TestingAuthenticationToken("application:BO", null, "ROLE_APPLICATION");
        app.setAuthenticated(true);
        assertEquals(CanalDepot.API, new SourceDepotParDefaut().origine(app).canal());

        SecurityContextHolder.getContext().setAuthentication(app);
        assertEquals(CanalDepot.BUREAU_ORDRE,
                new ResolutionOrigineDepot(new SourceDepotParDefaut(), List.of("bo", "")).courante().canal());
        assertEquals(CanalDepot.API,
                new ResolutionOrigineDepot(new SourceDepotParDefaut(), List.of("AUTRE")).courante().canal());
    }

    @Test
    @DisplayName("Délégation fournie par le lot intégration : application et déposant délégué conservés")
    void delegation() {
        UUID appli = UUID.randomUUID(), personne = UUID.randomUUID();
        SourceDepot integration = a -> new SourceDepot.Origine(CanalDepot.API, appli, "application:BO", personne, true);
        SourceDepot.Origine o = new ResolutionOrigineDepot(integration, List.of("BO")).courante();
        assertEquals(CanalDepot.BUREAU_ORDRE, o.canal());
        assertEquals(appli, o.applicationId());
        assertEquals(personne, o.deposantUtilisateurId());
        assertTrue(o.delegue());
    }
}
