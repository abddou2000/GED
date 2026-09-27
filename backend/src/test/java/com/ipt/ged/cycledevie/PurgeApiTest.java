package com.ipt.ged.cycledevie;

import com.ipt.ged.document.evenement.DocumentPurge;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.support.Pdfs;
import com.ipt.ged.workspace.WorkSpace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Purge définitive (§12.5) : seulement depuis la corbeille ; lignes métier
 * supprimées, clés (DEK) détruites puis fichiers effacés ; audit conservé.
 */
@RecordApplicationEvents
class PurgeApiTest extends BaseCycleDeVieApiTest {

    @Autowired private StockageChiffre stockage;
    @Autowired private ApplicationEvents evenements;

    private UUID type;

    @BeforeEach
    void preparer() {
        WorkSpace w = espace("Purge " + suffixe, null);
        type = type(w, "pdf,png");
    }

    private int compte(String table, String colonne, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + colonne + " = ?", Integer.class, id);
    }

    @Test
    @DisplayName("Document vivant : 409 DOCUMENT_NON_SUPPRIME, rien n'est effacé ; inconnu : 404")
    void refusHorsCorbeille() throws Exception {
        UUID doc = deposer(type, "vivant", "v.pdf", "application/pdf", Pdfs.pdf());
        mvc.perform(post("/api/v1/documents/" + doc + "/purge"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErreurCycleDeVie.DOCUMENT_NON_SUPPRIME));
        assertEquals(1, compte("document", "id", doc));
        assertTrue(stockage.existe(fichierCourant(doc)));
        mvc.perform(post("/api/v1/documents/" + UUID.randomUUID() + "/purge")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Corbeille puis purge : lignes, clés et fichiers de toutes les versions détruits ; fichier indéchiffrable")
    void purge() throws Exception {
        UUID doc = deposer(type, "a purger", "p.pdf", "application/pdf", Pdfs.pdf());
        mvc.perform(multipart("/api/v1/documents/" + doc + "/versions")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "p2.png", "image/png",
                                Echantillons.image("png"))))
                .andExpect(status().is2xxSuccessful());
        List<UUID> fichiers = jdbc.queryForList("SELECT cle_fichier_id FROM version_document WHERE document_id = ?",
                UUID.class, doc);
        assertEquals(2, fichiers.size());
        fichiers.forEach(f -> assertTrue(Files.exists(fichierChiffre(f))));

        mvc.perform(delete("/api/v1/documents/" + doc)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/documents/" + doc + "/purge")).andExpect(status().isNoContent());

        assertEquals(0, compte("document", "id", doc));
        assertEquals(0, compte("version_document", "document_id", doc));
        assertEquals(0, compte("document_etiquette", "document_id", doc));
        assertEquals(0, compte("document_index_valeur", "document_id", doc));
        for (UUID f : fichiers) {
            assertEquals(0, compte("cle_fichier", "id", f), "DEK détruite");
            assertFalse(Files.exists(fichierChiffre(f)), "fichier effacé");
            assertFalse(stockage.existe(f));
            assertThrows(RuntimeException.class, () -> stockage.lire(f));
        }
        List<DocumentPurge> purges = evenements.stream(DocumentPurge.class).toList();
        assertEquals(1, purges.size());
        assertEquals("DOCUMENT_PURGE", purges.get(0).action());
        assertEquals(2, purges.get(0).nbVersions());
        assertEquals(2, purges.get(0).nbFichiers());

        mvc.perform(get("/api/v1/documents/" + doc)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Purge multiple : tout ou rien (un document vivant dans la liste = aucun purgé)")
    void purgeMultiple() throws Exception {
        UUID a = deposer(type, "a", "a.pdf", "application/pdf", Pdfs.pdf());
        UUID b = deposer(type, "b", "b.pdf", "application/pdf", Pdfs.pdf());
        UUID vivant = deposer(type, "c", "c.pdf", "application/pdf", Pdfs.pdf());
        mvc.perform(delete("/api/v1/documents/multiple-delete").contentType(APPLICATION_JSON)
                .content("{\"ids\":[\"" + a + "\",\"" + b + "\"]}")).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/documents/purge").contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"" + a + "\",\"" + b + "\",\"" + vivant + "\"]}"))
                .andExpect(status().isConflict());
        assertEquals(1, compte("document", "id", a));

        mvc.perform(post("/api/v1/documents/purge").contentType(APPLICATION_JSON)
                        .content("{\"ids\":[\"" + a + "\",\"" + b + "\"]}"))
                .andExpect(status().isNoContent());
        assertEquals(0, compte("document", "id", a));
        assertEquals(0, compte("document", "id", b));
        assertEquals(1, compte("document", "id", vivant));
    }
}
