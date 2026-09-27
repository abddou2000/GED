package com.ipt.ged.fichier.reprise;

import com.ipt.ged.fichier.ProprietesFichiers;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.ocr.file.EnfilageOcr;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

/**
 * Lance la reprise des versions en clair au démarrage quand
 * {@code ged.fichiers.reprise.source} (ancienne racine {@code ged.storage.root})
 * est renseigné. Procédure (application fermée aux utilisateurs) :
 *
 * <pre>
 * java -jar ged.jar --spring.profiles.active=prod --spring.main.web-application-type=none \
 *      --ged.fichiers.reprise.source=/srv/ged/storage/ged \
 *      --ged.fichiers.reprise.rapport=/srv/ged/reprise-fichiers.csv
 * </pre>
 * puis redémarrage normal : le changeset « contract » s'applique si toutes les
 * versions ont été reprises.
 */
@Component
@ConditionalOnProperty(prefix = "ged.fichiers.reprise", name = "source")
public class LanceurReprise implements ApplicationRunner {

    private final ProprietesFichiers proprietes;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final StockageChiffre stockage;
    private final DetecteurTypeReel detecteur;
    private final AnalyseurAntivirus antivirus;
    private final EnfilageOcr ocr;

    public LanceurReprise(ProprietesFichiers proprietes, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                          StockageChiffre stockage, DetecteurTypeReel detecteur, AnalyseurAntivirus antivirus,
                          EnfilageOcr ocr) {
        this.proprietes = proprietes;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.stockage = stockage;
        this.detecteur = detecteur;
        this.antivirus = antivirus;
        this.ocr = ocr;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ProprietesFichiers.Reprise r = proprietes.getReprise();
        new RepriseVersionsEnClair(jdbc, new TransactionTemplate(transactions), stockage, detecteur,
                r.isAntivirus() ? antivirus : null, ocr, proprietes.getPlafondPlateformeMo() * 1024L * 1024L)
                .reprendre(Path.of(r.getSource()), Path.of(r.getRapport()));
    }
}
