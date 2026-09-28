package com.ipt.ged.document.conservation;

import com.ipt.ged.common.tache.VerrouTache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alerte d'échéance de conservation (DAT §12.9, T-112 ; dossier fonctionnel
 * §4.6.3 et §4.6.6).
 *
 * <p>Un passage : sous <b>verrou de tâche</b> ({@link VerrouTache}, une seule
 * exécution à la fois même à plusieurs instances), sélectionne par tranches les
 * documents vivants dont l'échéance est atteinte (échéance &lt;= jour de MMED)
 * et <b>non encore signalés</b>, les marque signalés
 * ({@code document.echeance_signalee_le}) et publie pour chacun
 * {@link EcheanceConservationAtteinte} : trace d'audit et notification « fin
 * de conservation » aux Agents d'archive compétents, écrites dans la
 * transaction du marquage (tout ou rien par tranche).
 *
 * <p>Idempotent : un document signalé ne l'est plus, sauf si son échéance est
 * repoussée dans le futur (déclencheur {@code trg_document_echeance_resignaler}).
 * <b>Aucune suppression automatique</b> (P4) : seul le signalement change.
 * Les documents en corbeille ne sont pas signalés ; les documents archivés le
 * sont (leur conservation s'achève aussi).
 */
@Service
public class AlertesEcheanceConservation {

    /** Nom du verrou de tâche. */
    public static final String TACHE = "ALERTE_ECHEANCE_CONSERVATION";

    private static final Logger journal = LoggerFactory.getLogger(AlertesEcheanceConservation.class);

    private static final String A_SIGNALER = """
            SELECT d.id, d.name, d.echeance_conservation FROM document d
             WHERE NOT d.supprime AND d.echeance_signalee_le IS NULL
               AND d.echeance_conservation IS NOT NULL AND d.echeance_conservation <= ?
             ORDER BY d.echeance_conservation, d.id
             LIMIT ? FOR UPDATE SKIP LOCKED""";

    /**
     * Issue d'un passage.
     *
     * @param executee faux si une autre exécution détenait le verrou
     * @param signales documents signalés par ce passage
     * @param notifies documents dont au moins un Agent d'archive a été notifié
     */
    public record Bilan(boolean executee, int signales, int notifies) {
        static Bilan nonExecutee() {
            return new Bilan(false, 0, 0);
        }
    }

    private record ASignaler(UUID id, String nom, LocalDate echeance) {}

    private final JdbcTemplate jdbc;
    private final VerrouTache verrou;
    private final AgentsArchiveCompetents agents;
    private final ApplicationEventPublisher evenements;
    private final TransactionTemplate tranche;
    private final int taille;
    private final Duration bail;
    private Clock horloge = Clock.systemUTC();

    public AlertesEcheanceConservation(JdbcTemplate jdbc, VerrouTache verrou, AgentsArchiveCompetents agents,
                                       ApplicationEventPublisher evenements, PlatformTransactionManager transactions,
                                       @Value("${ged.conservation.alertes.tranche:200}") int taille,
                                       @Value("${ged.conservation.alertes.bail:PT2H}") Duration bail) {
        this.jdbc = jdbc;
        this.verrou = verrou;
        this.agents = agents;
        this.evenements = evenements;
        this.tranche = new TransactionTemplate(transactions);
        this.tranche.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.taille = Math.max(1, taille);
        this.bail = bail;
    }

    /** Horloge de référence (tests). */
    @Autowired(required = false)
    void setHorloge(Clock horloge) {
        if (horloge != null) this.horloge = horloge;
    }

    /** Un passage complet de la tâche ; sans effet si une autre exécution tient le verrou. */
    public Bilan executer() {
        Optional<VerrouTache.Jeton> jeton = verrou.prendre(TACHE, bail);
        if (jeton.isEmpty()) {
            journal.info("Alerte d'échéance de conservation : une autre exécution est en cours, passage ignoré");
            return Bilan.nonExecutee();
        }
        try {
            LocalDate aujourdhui = Echeances.aujourdhui(horloge);
            AgentsArchiveCompetents.Passage destinataires = agents.passage();
            int signales = 0;
            int notifies = 0;
            while (true) {
                int[] t = tranche.execute(s -> traiterTranche(aujourdhui, destinataires));
                if (t == null || t[0] == 0) break;
                signales += t[0];
                notifies += t[1];
                if (t[0] < taille) break;
            }
            if (signales > 0) {
                journal.info("Alerte d'échéance de conservation : {} document(s) signalé(s), {} avec Agent d'archive notifié",
                        signales, notifies);
            }
            return new Bilan(true, signales, notifies);
        } finally {
            verrou.liberer(jeton.get());
        }
    }

    /** Une tranche : marquage, trace et notification, dans une seule transaction. */
    private int[] traiterTranche(LocalDate aujourdhui, AgentsArchiveCompetents.Passage destinataires) {
        List<ASignaler> lot = jdbc.query(A_SIGNALER, (rs, i) -> new ASignaler(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getDate(3).toLocalDate()), Date.valueOf(aujourdhui), taille);
        Timestamp maintenant = Timestamp.from(horloge.instant());
        int notifies = 0;
        for (ASignaler d : lot) {
            jdbc.update("UPDATE document SET echeance_signalee_le = ? WHERE id = ?", maintenant, d.id());
            List<UUID> agentsDuDocument = destinataires.pour(d.id());
            if (!agentsDuDocument.isEmpty()) notifies++;
            evenements.publishEvent(new EcheanceConservationAtteinte(d.id(), d.nom(), d.echeance(), agentsDuDocument));
        }
        return new int[]{lot.size(), notifies};
    }
}
