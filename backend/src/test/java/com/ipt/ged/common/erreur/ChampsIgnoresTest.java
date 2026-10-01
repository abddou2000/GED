package com.ipt.ged.common.erreur;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** En-tête {@code GED-Champs-Ignores} (P-08) : encodage, dédoublonnage, borne. */
class ChampsIgnoresTest {

    @Test
    @DisplayName("Noms encodés hors ASCII imprimable, virgule et % ; doublons retirés ; ordre d'arrivée")
    void encodage() {
        MockHttpServletRequest requete = new MockHttpServletRequest();
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        ChampsIgnores.signaler(requete, reponse, "date du");
        ChampsIgnores.signaler(requete, reponse, "confidentialité");
        ChampsIgnores.signaler(requete, reponse, "a,b%");
        ChampsIgnores.signaler(requete, reponse, "date du");
        assertThat(reponse.getHeader(ChampsIgnores.ENTETE)).isEqualTo("date%20du, confidentialit%C3%A9, a%2Cb%25");
    }

    @Test
    @DisplayName("Au plus 20 noms de 100 caractères, puis « ... »")
    void borne() {
        MockHttpServletRequest requete = new MockHttpServletRequest();
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        for (int i = 0; i < 50; i++) ChampsIgnores.signaler(requete, reponse, i + "x".repeat(500));
        String[] noms = reponse.getHeader(ChampsIgnores.ENTETE).split(", ");
        assertThat(noms).hasSize(21);
        assertThat(noms[0]).hasSize(100);
        assertThat(noms[20]).isEqualTo("...");
    }

    @Test
    @DisplayName("Paramètres d'URL : seuls les inconnus sont signalés")
    void parametres() {
        MockHttpServletRequest requete = new MockHttpServletRequest();
        requete.addParameter("q", "x");
        requete.addParameter("dateDu", "2026-09-01");
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        ParametresConnus.signaler(requete, reponse, Set.of("q"));
        assertThat(reponse.getHeader(ChampsIgnores.ENTETE)).isEqualTo("dateDu");
    }
}
