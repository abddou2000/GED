package com.ipt.ged.fichier.reprise;

import com.ipt.ged.fichier.ProprietesFichiers;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Lance la reprise au démarrage quand {@code ged.fichiers.reprise.source} est
 * renseigné. Mode d'emploi (application arrêtée pour les utilisateurs) :
 *
 * <pre>
 * java -jar ged.jar --spring.profiles.active=prod --spring.main.web-application-type=none \
 *      --ged.fichiers.reprise.source=/srv/ged/storage/ged \
 *      --ged.fichiers.reprise.rapport=/srv/ged/reprise-fichiers.csv
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "ged.fichiers.reprise", name = "source")
public class LanceurReprise implements ApplicationRunner {

    private final ProprietesFichiers proprietes;
    private final StockageChiffre stockage;
    private final DetecteurTypeReel detecteur;
    private final AnalyseurAntivirus antivirus;

    public LanceurReprise(ProprietesFichiers proprietes, StockageChiffre stockage, DetecteurTypeReel detecteur,
                          AnalyseurAntivirus antivirus) {
        this.proprietes = proprietes;
        this.stockage = stockage;
        this.detecteur = detecteur;
        this.antivirus = antivirus;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ProprietesFichiers.Reprise r = proprietes.getReprise();
        new RepriseFichiersEnClair(stockage, detecteur, r.isAntivirus() ? antivirus : null,
                proprietes.getPlafondPlateformeMo() * 1024L * 1024L)
                .reprendre(Path.of(r.getSource()), Path.of(r.getRapport()));
    }
}
