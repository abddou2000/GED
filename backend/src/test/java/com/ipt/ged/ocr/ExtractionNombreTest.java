package com.ipt.ged.ocr;

import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexFieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Déduction d'un index numérique à partir du contenu d'un document.
 *
 * <p>Le cas qui a motivé ces tests : un plan à trois index numériques
 * (« ryovallecano », « test125 », « testsoufiane ») affichait <b>3.4 dans les
 * trois</b>. Aucun de ces libellés n'apparaissait dans le document ; le moteur
 * se rabattait alors sur le texte entier et retenait le premier nombre venu —
 * ici un numéro de section. Trois champs sans rapport recevaient la même
 * valeur, et rien ne le signalait.</p>
 */
class ExtractionNombreTest {

    private final ExtracteurValeurs extracteur = new ExtracteurValeurs(new LecteurPositionnel());

    private static IndexField champ(String nom, IndexFieldType type) {
        IndexField f = new IndexField();
        f.setNomIndex(nom);
        f.setFieldType(type);
        return f;
    }

    @Test
    @DisplayName("un nombre est déduit quand il suit le libellé de son index")
    void deduitApresLeLibelle() {
        String texte = "Fiche technique\nMontant total 1250,50 MAD\nPage 1";
        assertThat(extracteur.deduire(champ("Montant total", IndexFieldType.NOMBRE), texte, List.of()))
                .isEqualTo("1250.50");
    }

    @Test
    @DisplayName("aucun nombre n'est déduit si le libellé est absent du document")
    void refuseSansLibelle() {
        // « 3.4 » est bien présent, mais rien ne le rattache à cet index.
        String texte = "Architecture backend\n3.4 Couche de persistance\nAnnexe";
        assertThat(extracteur.deduire(champ("test125", IndexFieldType.NOMBRE), texte, List.of()))
                .isNull();
    }

    @Test
    @DisplayName("trois index sans libellé dans le document restent tous vides")
    void pasDeValeurRecopieeSurPlusieursIndex() {
        String texte = "Architecture backend\n3.4 Couche de persistance\nAnnexe";
        for (String nom : List.of("ryovallecano", "test125", "testsoufiane")) {
            assertThat(extracteur.deduire(champ(nom, IndexFieldType.NOMBRE), texte, List.of()))
                    .as("index « %s »", nom)
                    .isNull();
        }
    }

    @Test
    @DisplayName("une date, elle, reste reconnaissable sans son libellé")
    void laDateGardeSonRepli() {
        /* Une date se reconnaît à sa forme, un nombre non : « 28/08/2026 » ne
           peut être qu'une date, alors que « 3.4 » peut être n'importe quoi.
           Le repli sur le texte entier reste donc légitime ici. */
        String texte = "Décompte provisoire\nÉtabli le 28/08/2026 à Nador";
        assertThat(extracteur.deduire(champ("Date d'émission", IndexFieldType.DATE), texte, List.of()))
                .isEqualTo("2026-08-28");
    }
}
