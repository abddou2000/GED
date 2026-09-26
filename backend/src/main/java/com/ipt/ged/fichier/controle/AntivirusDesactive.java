package com.ipt.ged.fichier.controle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Antivirus neutralisé, pour un poste de développement sans ClamAV
 * ({@code ged.fichiers.antivirus.actif=false}).
 *
 * <p>Interdit en production : la configuration refuse de démarrer le profil
 * {@code prod} avec cet analyseur (voir {@code ConfigurationFichiers}). Chaque
 * fichier accepté sans analyse est signalé au journal, pour que l'état ne
 * passe jamais inaperçu.
 */
public class AntivirusDesactive implements AnalyseurAntivirus {

    private static final Logger log = LoggerFactory.getLogger(AntivirusDesactive.class);

    @Override
    public String analyser(SourceFichier source) {
        log.warn("ANTIVIRUS DESACTIVE : « {} » accepté sans analyse", source.nomOrigine());
        return "NON ANALYSE";
    }

    @Override
    public boolean disponible() {
        return false;
    }
}
