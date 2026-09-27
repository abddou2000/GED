package com.ipt.ged.fichier.integrite;

import com.ipt.ged.fichier.CodesErreurFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.chiffrement.FormatChiffre;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Lecture d'un fichier déchiffré destinée à une réponse HTTP (téléchargement,
 * aperçu, export) — anomalie ANO-E5-002.
 *
 * <p>Le déchiffrement authentifie chaque segment au fil de la lecture : une
 * altération n'apparaissait qu'une fois la réponse engagée (en-têtes du
 * téléchargement déjà partis), d'où un 500 sans corps ni code. Le premier
 * segment (1 Mio, soit le fichier entier dans la plupart des cas) est donc
 * lu et authentifié <b>avant</b> que la réponse ne soit construite : une
 * altération de l'en-tête ou du début lève {@code INTEGRITE_COMPROMISE}, rendu
 * en problem+json par le gestionnaire commun.
 *
 * <p>Toute altération, détectée d'emblée ou plus loin dans le flux (fichier
 * de plusieurs segments), est journalisée avec le contexte de la requête et
 * publiée en {@link VerificationIntegrite.AnomalieIntegrite} (audit, alerte).
 */
@Component
public class LectureControlee {

    private static final Logger log = LoggerFactory.getLogger(LectureControlee.class);

    private final StockageChiffre stockage;
    private final ApplicationEventPublisher evenements;

    public LectureControlee(StockageChiffre stockage, ApplicationEventPublisher evenements) {
        this.stockage = stockage;
        this.evenements = evenements;
    }

    /** Ouvre un fichier du stockage chiffré pour une réponse, premier segment authentifié. */
    public InputStream ouvrir(UUID fichierId, String reference) {
        InputStream flux;
        try {
            flux = stockage.lire(fichierId);
        } catch (ErreurFichierException e) {
            signaler(e, fichierId, reference);
            throw e;
        }
        return controler(flux, fichierId, reference);
    }

    /**
     * @param flux      flux déchiffré ({@code StockageChiffre.lire}) ;
     * @param fichierId fichier lu ;
     * @param reference ce que l'on servait (pour le journal).
     * @return un flux équivalent, dont le premier segment est déjà authentifié.
     */
    public InputStream controler(InputStream flux, UUID fichierId, String reference) {
        byte[] debut;
        try {
            debut = flux.readNBytes(FormatChiffre.SEGMENT_PAR_DEFAUT);
        } catch (ErreurFichierException e) {
            fermer(flux);
            signaler(e, fichierId, reference);
            throw e;
        } catch (IOException e) {
            fermer(flux);
            throw new UncheckedIOException("Lecture du fichier " + fichierId + " impossible", e);
        }
        InputStream suite = new FilterInputStream(flux) {
            @Override
            public int read() throws IOException {
                try {
                    return super.read();
                } catch (ErreurFichierException e) {
                    signaler(e, fichierId, reference);
                    throw e;
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    return super.read(b, off, len);
                } catch (ErreurFichierException e) {
                    signaler(e, fichierId, reference);
                    throw e;
                }
            }
        };
        return new SequenceInputStream(new ByteArrayInputStream(debut), suite) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    suite.close();
                }
            }
        };
    }

    private void signaler(ErreurFichierException e, UUID fichierId, String reference) {
        if (!CodesErreurFichier.INTEGRITE_COMPROMISE.equals(e.code())) return;
        log.error("Intégrité compromise à la lecture du fichier {} ({}) : {}", fichierId, reference, e.getMessage());
        try {
            evenements.publishEvent(new VerificationIntegrite.AnomalieIntegrite(fichierId, reference,
                    VerificationIntegrite.Statut.ALTERE, Instant.now()));
        } catch (RuntimeException ex) {
            log.warn("Anomalie d'intégrité du fichier {} non publiée", fichierId, ex);
        }
    }

    private static void fermer(InputStream flux) {
        try {
            flux.close();
        } catch (IOException ignoree) {
            // le flux est abandonné
        }
    }
}
