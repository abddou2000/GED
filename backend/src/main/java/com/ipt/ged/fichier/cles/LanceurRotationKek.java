package com.ipt.ged.fichier.cles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Rotation <b>immédiate</b> de la KEK, lancée par l'exploitant en cas de
 * compromission présumée (§6.1.2, modèle de menaces P-11 §3 bis). La rotation
 * planifiée ({@code rotation-planifiee}) ne suffit pas : elle est annuelle et
 * désactivée par défaut, et attendre sa date laisserait la clé volée active.
 *
 * <pre>
 * java -jar ged.jar --spring.profiles.active=prod --spring.main.web-application-type=none \
 *      --ged.fichiers.cles.rotation-immediate=true [--ged.fichiers.cles.retirer-ancienne=true]
 * </pre>
 *
 * <p>Un réenveloppement incomplet fait échouer le lancement (code de sortie non
 * nul) : l'exploitant ne doit pas croire la clé compromise hors d'usage alors
 * que des DEK en dépendent encore. L'ancienne KEK n'est retirée du keystore que
 * sur demande explicite, parce que les sauvegardes antérieures en ont besoin
 * pour être restaurées (RESTAURATION.md).
 */
@Component
@ConditionalOnProperty(prefix = "ged.fichiers.cles", name = "rotation-immediate", havingValue = "true")
public class LanceurRotationKek implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LanceurRotationKek.class);

    private final KeyProvider keyProvider;
    private final RotationKek rotation;
    private final boolean retirerAncienne;

    public LanceurRotationKek(KeyProvider keyProvider, RotationKek rotation,
                              @Value("${ged.fichiers.cles.retirer-ancienne:false}") boolean retirerAncienne) {
        this.keyProvider = keyProvider;
        this.rotation = rotation;
        this.retirerAncienne = retirerAncienne;
    }

    @Override
    public void run(ApplicationArguments args) {
        String ancienne = keyProvider.kekActive();
        RotationKek.Rapport rapport = rotation.executer();
        if (!rapport.echecs().isEmpty()) {
            throw new IllegalStateException("Rotation incomplète : " + rapport.echecs().size()
                    + " DEK restent sous " + ancienne + " (voir le journal). Relancer après correction ; "
                    + ancienne + " ne peut pas être retirée.");
        }
        log.warn("Rotation immédiate terminée : {} -> {}, {} DEK réenveloppées. Sauvegarder le keystore maintenant.",
                ancienne, rapport.kekActive(), rapport.traitees());
        if (retirerAncienne) {
            rotation.retirer(ancienne);
            log.warn("KEK {} retirée du keystore : les sauvegardes antérieures à la rotation exigent le keystore sauvegardé.",
                    ancienne);
        }
    }
}
