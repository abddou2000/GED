package com.ipt.ged.depot;

import com.ipt.ged.document.DocumentService;
import com.ipt.ged.document.dto.DocumentResponse;
import com.ipt.ged.indexation.dto.ValeurRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ANO-E6-002 : le motif d'échec du temps 2 renvoyé au client
 * ({@code motifIndexation}) ne recopie jamais le message d'une exception
 * technique — texte SQL, nom de table ou de colonne. Les refus métier, eux,
 * restent lisibles tels quels.
 */
class DepotServiceMotifTest {

    private static final String SQL_BRUT = "could not execute statement [ERROR: value too long for type character "
            + "varying(255)] [update document set active=?,application_id=? where id=?]; SQL [update document set …]";

    /** Dépose avec un temps 2 qui échoue sur {@code panne} ; rend le motif transmis au client. */
    private static String motifApres(RuntimeException panne) {
        DocumentService documents = mock(DocumentService.class);
        MetadonneesDepot metadonnees = mock(MetadonneesDepot.class);
        IndexationAuDepot indexation = mock(IndexationAuDepot.class);
        DocumentResponse recu = mock(DocumentResponse.class);
        UUID id = UUID.randomUUID();
        ValeurRequest valeurs = new ValeurRequest(List.of(new ValeurRequest.Ligne(UUID.randomUUID(), "x")));

        when(metadonnees.lire(any(), any())).thenReturn(Optional.of(valeurs));
        when(documents.upload(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(recu);
        when(recu.id()).thenReturn(id);
        when(recu.statutIndexation()).thenReturn("A_INDEXER");
        when(recu.statutOcr()).thenReturn("TERMINE");
        when(recu.avecIndexation(anyString(), anyString())).thenReturn(recu);
        doThrow(panne).when(indexation).indexer(id, valeurs);

        new DepotService(documents, metadonnees, indexation)
                .deposer(null, "nom", UUID.randomUUID(), null, null, null, null, "{}");

        ArgumentCaptor<String> motif = ArgumentCaptor.forClass(String.class);
        verify(recu).avecIndexation(eq("A_INDEXER"), motif.capture());
        return motif.getValue();
    }

    private static void sansTexteTechnique(String motif) {
        for (String technique : List.of("SQL", "update document", "could not execute", "varying", "application_id")) {
            assertFalse(motif.contains(technique), motif);
        }
    }

    @Test
    @DisplayName("Refus du schéma : même libellé que l'API (DONNEE_REFUSEE), sans la requête SQL")
    void refusDuSchema() {
        String motif = motifApres(new DataIntegrityViolationException(SQL_BRUT,
                new SQLException("ERROR: value too long for type character varying(255)")));
        sansTexteTechnique(motif);
        assertTrue(motif.startsWith("Métadonnées non enregistrées : donnée refusée par la base"), motif);
        assertTrue(motif.contains("255 caractères"), motif);
    }

    @Test
    @DisplayName("Autre panne technique : libellé générique, rien du message d'origine")
    void panneTechnique() {
        String motif = motifApres(new InvalidDataAccessResourceUsageException(SQL_BRUT));
        sansTexteTechnique(motif);
        assertTrue(motif.contains("reprenez l'indexation"), motif);
    }

    @Test
    @DisplayName("Refus métier : recopié tel quel")
    void refusMetier() {
        assertEquals("Métadonnées non enregistrées : L'index « Montant » est obligatoire.",
                motifApres(new IllegalArgumentException("L'index « Montant » est obligatoire.")));
    }
}
