package com.ipt.ged.document;

import com.ipt.ged.autorisation.ConflitAutorisationException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Garde commune de TOUTE écriture sur un document (dossier technique §12.6,
 * §12.8) : fiche, métadonnées, versement, désignation de version courante,
 * déplacement, renommage, réindexation, rattachement, suppression, archivage.
 *
 * <ul>
 *   <li>document verrouillé → 409 {@link #DOCUMENT_VERROUILLE} ;</li>
 *   <li>document archivé → 409 {@link #DOCUMENT_ARCHIVE} (seul le désarchivage
 *       y échappe).</li>
 * </ul>
 * Lue en SQL, sans passer par le contexte de persistance : la garde voit
 * l'état validé (ou écrit plus tôt dans la même transaction), jamais une copie
 * en mémoire périmée. Point d'entrée unique des lots modèle (dev1) et cycle de
 * vie (dev3).
 */
@Component
public class GardeEcriture {

    public static final String DOCUMENT_VERROUILLE = "DOCUMENT_VERROUILLE";
    public static final String DOCUMENT_ARCHIVE = "DOCUMENT_ARCHIVE";

    private final JdbcTemplate jdbc;

    public GardeEcriture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Etat(boolean verrouille, String motif, String statut) {}

    private Etat etat(UUID documentId) {
        List<Etat> l = jdbc.query("SELECT is_locked, verrou_motif, statut_conservation FROM document WHERE id = ?",
                (rs, i) -> new Etat(rs.getBoolean(1), rs.getString(2), rs.getString(3)), documentId);
        if (l.isEmpty()) throw new EntityNotFoundException("Document introuvable : " + documentId);
        return l.get(0);
    }

    /** Refuse toute écriture sur un document verrouillé ou archivé. */
    public void exigerModifiable(UUID documentId) {
        Etat e = etat(documentId);
        exigerNonVerrouille(e);
        if ("ARCHIVE".equals(e.statut())) {
            throw new ConflitAutorisationException(DOCUMENT_ARCHIVE,
                    "Document archivé : lecture seule. Seul le désarchivage est possible.");
        }
    }

    /** Refuse si le document est verrouillé (l'archivage d'un document archivé reste idempotent). */
    public void exigerNonVerrouille(UUID documentId) {
        exigerNonVerrouille(etat(documentId));
    }

    private static void exigerNonVerrouille(Etat e) {
        if (e.verrouille()) {
            throw new ConflitAutorisationException(DOCUMENT_VERROUILLE, "Document verrouillé"
                    + (e.motif() != null && !e.motif().isBlank() ? " (" + e.motif() + ")" : "")
                    + " : aucune modification tant que le verrou tient.");
        }
    }
}
