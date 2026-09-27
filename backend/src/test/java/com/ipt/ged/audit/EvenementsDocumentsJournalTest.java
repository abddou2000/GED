package com.ipt.ged.audit;

import com.ipt.ged.document.evenement.Acteur;
import com.ipt.ged.document.evenement.ApercuConsulte;
import com.ipt.ged.document.evenement.ContenuIndexe;
import com.ipt.ged.document.evenement.DocumentArchive;
import com.ipt.ged.document.evenement.DocumentDesarchive;
import com.ipt.ged.document.evenement.DocumentExporte;
import com.ipt.ged.document.evenement.DocumentPurge;
import com.ipt.ged.document.evenement.DocumentDepose;
import com.ipt.ged.document.evenement.DocumentRestaure;
import com.ipt.ged.document.evenement.DocumentSupprime;
import com.ipt.ged.document.evenement.DocumentTelecharge;
import com.ipt.ged.document.evenement.EvenementDocument;
import com.ipt.ged.document.evenement.MetadonneesModifiees;
import com.ipt.ged.document.evenement.OcrEnEchec;
import com.ipt.ged.document.evenement.VerrouModifie;
import com.ipt.ged.document.evenement.VersionAjoutee;
import com.ipt.ged.document.evenement.VersionRestauree;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les événements documents du lot E5/E6/E7 (dev3), publiés par contrat
 * {@link EvenementAudit}, arrivent au journal : action, objet, valeurs, et
 * l'identité GED de la requête ({@code utilisateur.id}) — pas l'employé. Un
 * traitement de fond (worker OCR) n'a pas d'acteur. Une lecture publiée dans une
 * transaction en lecture seule est journalisée dans sa propre transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithUserDetails(Comptes.ADMIN)
class EvenementsDocumentsJournalTest {

    @Autowired private ApplicationEventPublisher evenements;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JeuDroits jeu;

    @Test
    @DisplayName("Événements documents de dev3 : au journal, acteur = identité GED, système sans acteur")
    void evenementsAuJournal() {
        UUID doc = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        Acteur acteur = Acteur.courant();
        Instant t = Instant.now();
        List<EvenementDocument> tous = List.of(
                new DocumentDepose(doc, version, acteur, t, "n", null, null, "f.pdf", null, "e", "application/pdf", 3, null),
                new VersionAjoutee(doc, version, acteur, t, null, "f.pdf", "obs", null, "e", "application/pdf", 3, null),
                new VersionRestauree(doc, version, acteur, t, null, null),
                new MetadonneesModifiees(doc, version, acteur, t, Map.of("nom", "a"), Map.of("nom", "b")),
                new VerrouModifie(doc, version, acteur, t, false, true),
                new VerrouModifie(doc, version, acteur, t, true, false),
                new DocumentSupprime(doc, version, acteur, t, "n"),
                new DocumentRestaure(doc, version, acteur, t, "n"),
                new DocumentArchive(doc, version, acteur, t, "e", "VALIDE", null, null),
                new DocumentDesarchive(doc, version, acteur, t, "e"),
                new DocumentExporte(doc, version, acteur, t, UUID.randomUUID(), "e"),
                new DocumentPurge(doc, version, acteur, t, "n", 1, 1),
                new ContenuIndexe(doc, version, Acteur.SYSTEME, t, 3, "OCR", Duration.ofMinutes(5)),
                new OcrEnEchec(doc, version, Acteur.SYSTEME, t, UUID.randomUUID(), "DELAI_DEPASSE : page 3"));
        new TransactionTemplate(transactions).executeWithoutResult(s -> tous.forEach(evenements::publishEvent));

        // Lectures : publiées dans une transaction en lecture seule, comme par le service.
        TransactionTemplate lecture = new TransactionTemplate(transactions);
        lecture.setReadOnly(true);
        lecture.executeWithoutResult(s -> {
            evenements.publishEvent(new DocumentTelecharge(doc, version, acteur, t, "f.pdf"));
            evenements.publishEvent(new ApercuConsulte(doc, version, acteur, t, null));
        });

        UUID moi = jeu.utilisateurId(Comptes.ADMIN);
        List<Map<String, Object>> lignes = jdbc.queryForList("SELECT action, acteur_utilisateur_id, resultat,"
                + " apres::text AS apres FROM journal_audit WHERE objet_type = 'DOCUMENT' AND objet_id = ? ORDER BY id", doc);
        assertThat(lignes).extracting(l -> l.get("action")).containsExactly(
                "DOCUMENT_DEPOSE", "VERSION_AJOUTEE", "VERSION_RESTAUREE", "METADONNEES_MODIFIEES",
                "DOCUMENT_VERROUILLE", "DOCUMENT_DEVERROUILLE", "DOCUMENT_SUPPRIME", "DOCUMENT_RESTAURE", "DOCUMENT_ARCHIVE", "DOCUMENT_DESARCHIVE",
                "DOCUMENT_EXPORTE", "DOCUMENT_PURGE",
                "CONTENU_INDEXE", "OCR_ECHEC", "DOCUMENT_TELECHARGE", "APERCU_CONSULTE");
        for (Map<String, Object> l : lignes) {
            boolean systeme = "CONTENU_INDEXE".equals(l.get("action")) || "OCR_ECHEC".equals(l.get("action"));
            if (!systeme) assertThat(l.get("acteur_utilisateur_id")).as((String) l.get("action")).isEqualTo(moi);
        }
        assertThat(lignes.get(3).get("apres").toString()).contains("\"nom\": \"b\"");
        assertThat(lignes.get(13).get("resultat")).isEqualTo("ECHEC");
    }
}
