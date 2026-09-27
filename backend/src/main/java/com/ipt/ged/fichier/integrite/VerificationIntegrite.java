package com.ipt.ged.fichier.integrite;

import com.ipt.ged.fichier.CodesErreurFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Vérification d'intégrité d'un fichier (§6.1.4) : relecture complète,
 * déchiffrement et recalcul de l'empreinte SHA-256 du clair, comparée à celle
 * enregistrée au dépôt.
 *
 * <p>Deux protections se cumulent : l'authentification GCM détecte toute
 * altération du fichier chiffré, l'empreinte détecte un fichier intègre mais
 * <i>différent</i> de celui déposé (clé et fichier échangés, restauration
 * d'une mauvaise sauvegarde). Toute divergence produit un {@link
 * AnomalieIntegrite} — que l'audit et la supervision écoutent — et une
 * trace d'erreur au journal.
 */
public class VerificationIntegrite {

    private static final Logger log = LoggerFactory.getLogger(VerificationIntegrite.class);

    private final StockageChiffre stockage;
    private final ApplicationEventPublisher evenements;

    public VerificationIntegrite(StockageChiffre stockage, ApplicationEventPublisher evenements) {
        this.stockage = stockage;
        this.evenements = evenements;
    }

    /** Vérifie un fichier ; utilisable à la demande comme par la tâche mensuelle. */
    public Resultat verifier(UUID fichierId, String empreinteAttendue, String reference) {
        Statut statut;
        String calculee = null;
        try (InputStream in = stockage.lire(fichierId)) {
            MessageDigest sha = StockageChiffre.sha256();
            byte[] tampon = new byte[64 * 1024];
            int n;
            while ((n = in.read(tampon)) >= 0) {
                sha.update(tampon, 0, n);
            }
            calculee = HexFormat.of().formatHex(sha.digest());
            statut = egales(calculee, empreinteAttendue) ? Statut.CONFORME : Statut.EMPREINTE_DIVERGENTE;
        } catch (ErreurFichierException e) {
            statut = CodesErreurFichier.FICHIER_INTROUVABLE.equals(e.code()) ? Statut.ABSENT : Statut.ALTERE;
        } catch (IOException | UncheckedIOException e) {
            statut = Statut.ILLISIBLE;
        }
        Resultat r = new Resultat(fichierId, reference, statut, empreinteAttendue, calculee);
        if (statut != Statut.CONFORME) {
            log.error("ANOMALIE D'INTEGRITE {} sur le fichier {} ({})", statut, fichierId, reference);
            evenements.publishEvent(new AnomalieIntegrite(fichierId, reference, statut, Instant.now()));
        }
        return r;
    }

    /** Comparaison en temps constant, insensible à la casse de l'hexadécimal. */
    private static boolean egales(String calculee, String attendue) {
        if (attendue == null) return false;
        return MessageDigest.isEqual(calculee.getBytes(StandardCharsets.US_ASCII),
                attendue.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public enum Statut {
        /** Empreinte recalculée identique. */
        CONFORME,
        /** Fichier lisible et authentique, mais contenu différent de celui déposé. */
        EMPREINTE_DIVERGENTE,
        /** Authentification GCM en échec ou clé inutilisable : fichier altéré. */
        ALTERE,
        /** Fichier ou clé absents. */
        ABSENT,
        /** Erreur d'entrée-sortie (support, droits). */
        ILLISIBLE
    }

    public record Resultat(UUID fichierId, String reference, Statut statut,
                           String empreinteAttendue, String empreinteCalculee) {
        public boolean conforme() {
            return statut == Statut.CONFORME;
        }
    }

    /**
     * Événement d'anomalie d'intégrité : à journaliser dans l'audit (lot
     * traçabilité) et à remonter en alerte de supervision.
     */
    public record AnomalieIntegrite(UUID fichierId, String reference, Statut statut, Instant detecteeLe)
            implements com.ipt.ged.audit.EvenementAudit {
        @Override
        public String action() {
            return "INTEGRITE_ANOMALIE";
        }

        @Override
        public String objetType() {
            return "FICHIER";
        }

        @Override
        public UUID objetId() {
            return fichierId;
        }

        @Override
        public com.ipt.ged.audit.ResultatAudit resultat() {
            return com.ipt.ged.audit.ResultatAudit.ECHEC;
        }

        @Override
        public String motif() {
            return statut + " (" + reference + ")";
        }
    }
}
