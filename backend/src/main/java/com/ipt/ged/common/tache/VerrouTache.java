package com.ipt.ged.common.tache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Verrou des tâches planifiées (DAT §12.9 : « verrou de tâche pour éviter une
 * double exécution en cas de plusieurs instances »), table {@code verrou_tache}.
 *
 * <p>Un <b>bail</b> plutôt qu'un verrou de session : la prise est un
 * {@code UPDATE} conditionnel validé aussitôt dans sa propre transaction, donc
 * vu de toutes les instances ; une instance qui meurt en cours de tâche ne
 * bloque la tâche que jusqu'à la fin du bail. La tâche elle-même doit rester
 * idempotente (une reprise après expiration du bail ne refait rien de ce qui
 * est déjà marqué).
 */
@Component
public class VerrouTache {

    /** Preuve de détention, à rendre à {@link #liberer(Jeton)}. */
    public record Jeton(String nom, String detenteur) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionPropre;
    private final Clock horloge;

    @Autowired
    public VerrouTache(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    VerrouTache(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock horloge) {
        this.jdbc = jdbc;
        this.transactionPropre = new TransactionTemplate(transactions);
        this.transactionPropre.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.horloge = horloge;
    }

    /**
     * Prend le verrou de la tâche pour la durée du bail.
     *
     * @return le jeton, ou vide si une autre exécution (de cette instance ou
     *         d'une autre) détient un bail encore valide
     */
    public Optional<Jeton> prendre(String nom, Duration bail) {
        Objects.requireNonNull(nom, "nom");
        if (bail == null || bail.isNegative() || bail.isZero()) {
            throw new IllegalArgumentException("Bail du verrou de tâche invalide : " + bail);
        }
        String detenteur = instance() + "/" + UUID.randomUUID();
        Instant maintenant = horloge.instant();
        Integer pris = transactionPropre.execute(statut -> {
            // Ligne créée à la première prise ; libre d'emblée (bail expiré).
            jdbc.update("INSERT INTO verrou_tache (nom, verrouille_jusqu_a) VALUES (?, ?) ON CONFLICT (nom) DO NOTHING",
                    nom, Timestamp.from(Instant.EPOCH));
            // Atomique : une seule transaction peut voir le bail expiré et le reprendre.
            return jdbc.update("UPDATE verrou_tache SET detenteur = ?, pris_le = ?, verrouille_jusqu_a = ?"
                            + " WHERE nom = ? AND verrouille_jusqu_a <= ?",
                    detenteur, Timestamp.from(maintenant), Timestamp.from(maintenant.plus(bail)), nom,
                    Timestamp.from(maintenant));
        });
        return pris != null && pris == 1 ? Optional.of(new Jeton(nom, detenteur)) : Optional.empty();
    }

    /** Rend le verrou (sans effet si le bail a expiré et a été repris par un autre). */
    public void liberer(Jeton jeton) {
        Instant maintenant = horloge.instant();
        transactionPropre.executeWithoutResult(statut -> jdbc.update(
                "UPDATE verrou_tache SET verrouille_jusqu_a = ?, derniere_fin = ? WHERE nom = ? AND detenteur = ?",
                Timestamp.from(maintenant), Timestamp.from(maintenant), jeton.nom(), jeton.detenteur()));
    }

    private static String instance() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "instance";
        }
    }
}
