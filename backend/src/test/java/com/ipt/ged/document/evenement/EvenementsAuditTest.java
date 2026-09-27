package com.ipt.ged.document.evenement;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.audit.ResultatAudit;
import com.ipt.ged.common.erreur.ExceptionMetier;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.controle.ControleFichiers;
import com.ipt.ged.fichier.integrite.VerificationIntegrite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrats du lot traçabilité (dev2) : les événements des lots fichiers et
 * documents sont des {@link EvenementAudit} aux codes du catalogue
 * {@code ActionAudit}, et les erreurs de fichier des {@link ExceptionMetier}.
 */
class EvenementsAuditTest {

    /** Codes du catalogue ActionAudit (lot traçabilité) utilisés par les lots E5/E6. */
    private static final Set<String> CATALOGUE = Set.of("DOCUMENT_DEPOSE", "VERSION_AJOUTEE", "VERSION_RESTAUREE",
            "DOCUMENT_TELECHARGE", "APERCU_CONSULTE", "METADONNEES_MODIFIEES", "DOCUMENT_VERROUILLE",
            "DOCUMENT_DEVERROUILLE", "DOCUMENT_SUPPRIME", "DOCUMENT_RESTAURE", "CONTENU_INDEXE", "OCR_ECHEC",
            "FICHIER_INFECTE", "INTEGRITE_ANOMALIE");

    private final UUID doc = UUID.randomUUID(), version = UUID.randomUUID(), employe = UUID.randomUUID();
    private final Acteur acteur = new Acteur(employe, null);
    private final Instant t = Instant.now();

    @Test
    @DisplayName("Événements document : action = type (catalogue), objet DOCUMENT, acteur de l'opération")
    void evenementsDocument() {
        EvenementDocument[] tous = {
                new DocumentDepose(doc, version, acteur, t, "n", null, null, "f.pdf", null, "e", "application/pdf", 3, null),
                new VersionAjoutee(doc, version, acteur, t, null, "f.pdf", "obs", null, "e", "application/pdf", 3, null),
                new VersionRestauree(doc, version, acteur, t, null, null),
                new DocumentTelecharge(doc, version, acteur, t, "f.pdf"),
                new ApercuConsulte(doc, version, acteur, t, null),
                new MetadonneesModifiees(doc, version, acteur, t, Map.of("nom", "a"), Map.of("nom", "b")),
                new VerrouModifie(doc, version, acteur, t, false, true),
                new VerrouModifie(doc, version, acteur, t, true, false),
                new DocumentSupprime(doc, version, acteur, t, "n"),
                new DocumentRestaure(doc, version, acteur, t, "n"),
                new ContenuIndexe(doc, version, Acteur.SYSTEME, t, 3, "OCR", Duration.ofMinutes(5)),
                new OcrEnEchec(doc, version, Acteur.SYSTEME, t, UUID.randomUUID(), "DELAI_DEPASSE : page 3"),
        };
        for (EvenementDocument e : tous) {
            EvenementAudit a = e;
            assertTrue(CATALOGUE.contains(a.action()), a.action());
            assertEquals(e.type(), a.action());
            assertEquals("DOCUMENT", a.objetType());
            assertEquals(doc, a.objetId());
        }
        // Identité complétée par le journal (utilisateur.id de la requête, lot E2) : l'employé
        // est la personne métier, pas l'identité GED.
        assertNull(tous[0].acteurUtilisateurId());
        assertEquals(employe, tous[0].acteur().employeId());
        assertNull(tous[10].acteurUtilisateurId(), "traitement de fond : acteur complété par le journal");
        assertEquals(Map.of("nom", "a"), tous[5].avant());
        assertEquals(Map.of("verrouille", false), tous[6].avant());
        assertEquals(Map.of("verrouille", true), tous[6].apres());
        assertEquals(ResultatAudit.ECHEC, tous[11].resultat());
        assertEquals("DELAI_DEPASSE : page 3", tous[11].motif());
        assertEquals(ResultatAudit.SUCCES, tous[0].resultat());
    }

    @Test
    @DisplayName("Refus antivirus et anomalie d'intégrité : REFUS / ECHEC, objet FICHIER")
    void evenementsFichier() {
        EvenementAudit infecte = new ControleFichiers.FichierInfecte("note.txt", "Eicar", t);
        assertEquals("FICHIER_INFECTE", infecte.action());
        assertEquals(ResultatAudit.REFUS, infecte.resultat());
        assertEquals("FICHIER", infecte.objetType());
        UUID fichier = UUID.randomUUID();
        EvenementAudit anomalie = new VerificationIntegrite.AnomalieIntegrite(fichier, "version x",
                VerificationIntegrite.Statut.ALTERE, t);
        assertEquals("INTEGRITE_ANOMALIE", anomalie.action());
        assertEquals(ResultatAudit.ECHEC, anomalie.resultat());
        assertEquals(fichier, anomalie.objetId());
    }

    @Test
    @DisplayName("Erreurs de fichier : ExceptionMetier, codes et statuts conservés")
    void erreursFichier() {
        ExceptionMetier e = Refus.infecte("Eicar");
        assertEquals(422, e.statut().value());
        assertEquals("FICHIER_INFECTE", e.code());
        assertEquals(413, Refus.tropVolumineux(1).statut().value());
        assertEquals("FORMAT_NON_AUTORISE", Refus.formatNonAutorise("x").code());
        assertEquals("ANTIVIRUS_INDISPONIBLE", Refus.antivirusIndisponible(null).code());
        assertEquals("INTEGRITE_COMPROMISE", Refus.integriteCompromise("x", null).code());
    }
}
