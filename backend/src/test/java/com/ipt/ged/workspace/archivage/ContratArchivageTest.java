package com.ipt.ged.workspace.archivage;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.common.StatutConservation;
import com.ipt.ged.document.GardeEcriture;
import com.ipt.ged.document.archivage.ArchivageDocuments;
import com.ipt.ged.autorisation.ConflitAutorisationException;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.JeuDroits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrats d'archivage livrés au lot cycle de vie (D10, §12.6) : drapeau sur le
 * nœud et sa sous-arborescence, sélection des documents par tranches,
 * statut du document et garde d'écriture.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContratArchivageTest {

    @Autowired private ArchivageNoeuds noeuds;
    @Autowired private ArchivageDocuments documents;
    @Autowired private GardeEcriture garde;
    @Autowired private JeuDroits jeu;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Dossier : drapeau sur toute la sous-arborescence, idempotent, levé au désarchivage")
    void drapeauNoeud() {
        UUID e = jeu.noeud("E", null), d = jeu.noeud("D", e), dd = jeu.noeud("DD", d), autre = jeu.noeud("F", null);
        UUID admin = jeu.utilisateurId(Comptes.ADMIN);
        assertEquals(3, noeuds.marquerArchive(e, admin));
        assertEquals(0, noeuds.marquerArchive(e, admin));
        assertEquals(StatutConservation.ARCHIVE, noeuds.statut(dd));
        assertEquals(StatutConservation.ACTIF, noeuds.statut(autre));
        assertEquals(admin, jdbc.queryForObject("SELECT archive_par FROM noeud WHERE id = ?", UUID.class, dd));
        assertEquals(3, noeuds.marquerActif(e));
        assertEquals(StatutConservation.ACTIF, noeuds.statut(d));
    }

    @Test
    @DisplayName("Documents à archiver : emplacement principal dans la sous-arborescence, par tranches ordonnées")
    void tranches() {
        UUID e = jeu.noeud("E", null), d = jeu.noeud("D", e), ailleurs = jeu.noeud("X", null);
        UUID tE = jeu.type(e, Confidentialite.PUBLIC), tD = jeu.type(d, Confidentialite.PUBLIC);
        List<UUID> attendus = new ArrayList<>();
        for (int i = 0; i < 3; i++) attendus.add(jeu.document("e" + i, tE, Confidentialite.PUBLIC, null));
        for (int i = 0; i < 2; i++) attendus.add(jeu.document("d" + i, tD, Confidentialite.PUBLIC, null));
        jeu.document("x", jeu.type(ailleurs, Confidentialite.PUBLIC), Confidentialite.PUBLIC, null);
        UUID supprime = jeu.document("s", tE, Confidentialite.PUBLIC, null);
        jdbc.update("UPDATE document SET supprime = true WHERE id = ?", supprime);

        List<UUID> vus = new ArrayList<>();
        UUID apres = null;
        List<UUID> tranche;
        do {
            tranche = noeuds.documentsAArchiver(e, apres, 2);
            vus.addAll(tranche);
            if (!tranche.isEmpty()) apres = tranche.get(tranche.size() - 1);
        } while (tranche.size() == 2);
        assertEquals(attendus.stream().sorted().toList(), vus);

        documents.archiver(attendus.get(0), jeu.utilisateurId(Comptes.ADMIN));
        assertFalse(noeuds.documentsAArchiver(e, null, 100).contains(attendus.get(0)));
    }

    @Test
    @DisplayName("Document : archivage idempotent, lecture seule (409), refus si verrouillé, désarchivage")
    void statutDocument() {
        UUID doc = jeu.documentPublic();
        UUID admin = jeu.utilisateurId(Comptes.ADMIN);
        assertTrue(documents.archiver(doc, admin));
        assertFalse(documents.archiver(doc, admin));
        ConflitAutorisationException ex = assertThrows(ConflitAutorisationException.class,
                () -> garde.exigerModifiable(doc));
        assertEquals(GardeEcriture.DOCUMENT_ARCHIVE, ex.code());
        assertTrue(documents.desarchiver(doc));
        assertDoesNotThrow(() -> garde.exigerModifiable(doc));

        jdbc.update("UPDATE document SET is_locked = true, verrou_le = now(), verrou_motif = 'contrôle' WHERE id = ?", doc);
        ConflitAutorisationException verrou = assertThrows(ConflitAutorisationException.class,
                () -> documents.archiver(doc, admin));
        assertEquals(GardeEcriture.DOCUMENT_VERROUILLE, verrou.code());
        assertTrue(verrou.getMessage().contains("contrôle"));
    }
}
