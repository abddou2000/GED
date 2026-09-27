package com.ipt.ged.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Modèles français des notifications : variables, segments facultatifs, bornes. */
class ModelesNotificationTest {

    private final ModelesNotification modeles = new ModelesNotification();

    @Test
    @DisplayName("Chaque type a un titre et un message ; variable absente = tiret, segment facultatif omis")
    void rendu() {
        for (TypeNotification t : TypeNotification.values()) {
            assertThat(modeles.rendre(t, Map.of()).titre()).isNotBlank();
        }
        ModelesNotification.Texte t = modeles.rendre(TypeNotification.CIRCUIT_ANNULE, Map.of("document", "PV 12"));
        assertThat(t.message()).isEqualTo("Le circuit de validation du document « PV 12 » a été annulé. "
                + "Aucune décision n'est plus attendue de votre part.");
        assertThat(modeles.rendre(TypeNotification.ACCES_ESPACE_ATTRIBUE, Map.of()).titre())
                .isEqualTo("Accès attribué : " + ModelesNotification.ABSENT);
    }

    @Test
    @DisplayName("Valeurs insérées telles quelles : pas d'interprétation d'une accolade ou d'un $ dans une donnée")
    void valeursLitterales() {
        Map<String, Object> v = new HashMap<>();
        v.put("document", "Facture {motif} $1 \\ <b>");
        v.put("motif", "x");
        assertThat(modeles.rendre(TypeNotification.CIRCUIT_OUVERT, v).titre())
                .isEqualTo("Validation demandée : Facture {motif} $1 \\ <b>");
    }

    @Test
    @DisplayName("Titre borné à 300 caractères, e-mail avec lien et pied de page")
    void bornesEtCourriel() {
        String titre = modeles.rendre(TypeNotification.CIRCUIT_OUVERT, Map.of("document", "d".repeat(500))).titre();
        assertThat(titre).hasSize(300).endsWith("…");
        assertThat(modeles.corpsCourriel("Bonjour", "https://ged/#/x"))
                .startsWith("Bonjour\n\nOuvrir dans la GED : https://ged/#/x")
                .contains("merci de ne pas y répondre");
        assertThat(modeles.corpsCourriel("Bonjour", null)).doesNotContain("Ouvrir dans la GED");
    }
}
