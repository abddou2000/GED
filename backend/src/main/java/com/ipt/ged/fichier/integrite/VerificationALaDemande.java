package com.ipt.ged.fichier.integrite;

import com.ipt.ged.audit.ResultatAudit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vérification d'intégrité <b>à la demande</b> (§6.1.4, T-059 : « tâche
 * mensuelle et commande à la demande ») : d'un document (toutes ses versions
 * chiffrées et ses copies de conservation), ou du fonds entier en tâche de
 * fond. Chaque demande est tracée au journal d'audit
 * ({@code INTEGRITE_VERIFIEE}, avec son bilan) ; chaque divergence produit en
 * plus l'anomalie {@code INTEGRITE_ANOMALIE} de {@link VerificationIntegrite}.
 * Réservée à l'Administrateur (contrôle au point d'entrée).
 */
public class VerificationALaDemande {

    private final JdbcTemplate jdbc;
    private final VerificationIntegrite verification;
    private final VerificationPeriodique fonds;
    private final ApplicationEventPublisher evenements;

    public VerificationALaDemande(JdbcTemplate jdbc, VerificationIntegrite verification, VerificationPeriodique fonds,
                                  ApplicationEventPublisher evenements) {
        this.jdbc = jdbc;
        this.verification = verification;
        this.fonds = fonds;
        this.evenements = evenements;
    }

    /**
     * Vérifie tous les fichiers d'un document ; vide si le document n'existe pas.
     */
    public Optional<BilanDocument> verifierDocument(UUID documentId) {
        Integer existe = jdbc.queryForObject("SELECT count(*) FROM document WHERE id = ?", Integer.class, documentId);
        if (existe == null || existe == 0) return Optional.empty();
        List<Fichier> fichiers = new ArrayList<>(jdbc.query(
                "SELECT id, cle_fichier_id, empreinte, numero FROM version_document "
                        + "WHERE document_id = ? AND cle_fichier_id IS NOT NULL ORDER BY numero, id",
                (rs, i) -> new Fichier(rs.getObject("id", UUID.class), rs.getObject("cle_fichier_id", UUID.class),
                        rs.getString("empreinte"), "version " + rs.getObject("id", UUID.class)), documentId));
        fichiers.addAll(jdbc.query("SELECT c.version_id, c.cle_fichier_id, c.empreinte FROM copie_conservation c "
                        + "JOIN version_document v ON v.id = c.version_id "
                        + "WHERE v.document_id = ? AND c.cle_fichier_id IS NOT NULL ORDER BY c.cree_le, c.id",
                (rs, i) -> new Fichier(rs.getObject("version_id", UUID.class), rs.getObject("cle_fichier_id", UUID.class),
                        rs.getString("empreinte"), "copie de conservation de la version "
                        + rs.getObject("version_id", UUID.class)), documentId));

        List<Ligne> lignes = new ArrayList<>();
        Map<VerificationIntegrite.Statut, Integer> bilan = new EnumMap<>(VerificationIntegrite.Statut.class);
        for (Fichier f : fichiers) {
            VerificationIntegrite.Resultat r = verification.verifier(f.fichierId(), f.empreinte(), f.reference());
            lignes.add(new Ligne(f.versionId(), f.reference(), r.statut()));
            bilan.merge(r.statut(), 1, Integer::sum);
        }
        BilanDocument b = new BilanDocument(documentId, lignes.stream().allMatch(l -> l.statut()
                == VerificationIntegrite.Statut.CONFORME), lignes);
        evenements.publishEvent(new VerificationDemandee("DOCUMENT", documentId, b.conforme(), bilan));
        return Optional.of(b);
    }

    /**
     * Lance la vérification du fonds entier.
     *
     * @return l'état au lancement ; vide si une passe est déjà en cours.
     */
    public Optional<VerificationPeriodique.Etat> verifierFonds() {
        Optional<VerificationPeriodique.Etat> lancee = fonds.demarrerEnFond();
        if (lancee.isPresent()) {
            evenements.publishEvent(new VerificationDemandee("FONDS", null, true, Map.of()));
        }
        return lancee;
    }

    public VerificationPeriodique.Etat etatFonds() {
        return fonds.etat();
    }

    private record Fichier(UUID versionId, UUID fichierId, String empreinte, String reference) {
    }

    /** Résultat pour un fichier du document. */
    public record Ligne(UUID versionId, String reference, VerificationIntegrite.Statut statut) {
    }

    public record BilanDocument(UUID documentId, boolean conforme, List<Ligne> fichiers) {
    }

    /**
     * Demande de vérification, tracée (acteur de la requête). Pour un document :
     * résultat {@code ECHEC} si un fichier diverge, bilan en motif ; pour le
     * fonds : lancement de la passe (son bilan est au journal technique et
     * dans l'état de la passe, chaque divergence en {@code INTEGRITE_ANOMALIE}).
     */
    public record VerificationDemandee(String portee, UUID documentId, boolean conforme,
                                       Map<VerificationIntegrite.Statut, Integer> bilan)
            implements com.ipt.ged.audit.EvenementAudit {
        @Override
        public String action() {
            return "INTEGRITE_VERIFIEE";
        }

        @Override
        public String objetType() {
            return documentId == null ? null : "DOCUMENT";
        }

        @Override
        public UUID objetId() {
            return documentId;
        }

        @Override
        public ResultatAudit resultat() {
            return conforme ? ResultatAudit.SUCCES : ResultatAudit.ECHEC;
        }

        @Override
        public String motif() {
            return "FONDS".equals(portee) ? "vérification du fonds lancée à la demande"
                    : "vérification à la demande : " + bilan;
        }
    }
}
