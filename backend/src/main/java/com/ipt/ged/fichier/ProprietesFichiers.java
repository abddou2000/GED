package com.ipt.ged.fichier;

import com.ipt.ged.fichier.chiffrement.FormatChiffre;
import com.ipt.ged.fichier.controle.FormatsReconnus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Paramètres du stockage sécurisé ({@code ged.fichiers.*}). Chaque valeur est
 * documentée dans {@code application.yml} et {@code backend/.env.example}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ged.fichiers")
public class ProprietesFichiers {

    /** Racine du référentiel chiffré ({@code /<racine>/aa/bb/<uuid>.enc}). */
    private String racine = "./storage/coffre";
    /** Racine du cache chiffré des aperçus bureautiques. */
    private String racineCacheApercu = "./storage/cache-apercu";
    /** Taille maximale par défaut d'un type documentaire (§6.1.5). */
    private int tailleMaxDefautMo = 100;
    /** Plafond de plateforme, aligné sur NGINX et la configuration multipart. */
    private int plafondPlateformeMo = 200;
    /** Liste blanche par défaut, en extensions. */
    private List<String> formatsParDefaut = new ArrayList<>(FormatsReconnus.PAR_DEFAUT);
    /** Taille des segments chiffrés (octets). */
    private int tailleSegment = FormatChiffre.SEGMENT_PAR_DEFAUT;

    private Cles cles = new Cles();
    private Antivirus antivirus = new Antivirus();
    private Integrite integrite = new Integrite();
    private Previsualisation previsualisation = new Previsualisation();
    private Reprise reprise = new Reprise();

    @Getter
    @Setter
    public static class Cles {
        /** Chemin du keystore PKCS#12 des KEK (hors du référentiel de fichiers). */
        private String keystore;
        /** Phrase secrète, injectée par le coffre de secrets (GED_KEYSTORE_MDP). */
        private String motDePasse;
        /** Créer un keystore neuf s'il manque : développement et test uniquement. */
        private boolean creerSiAbsent = false;
        /** Rotation annuelle automatique de la KEK. */
        private boolean rotationPlanifiee = false;
        private String rotationCron = "0 0 2 1 1 *";
    }

    @Getter
    @Setter
    public static class Antivirus {
        /** Faux uniquement sur un poste sans ClamAV ; refusé en production. */
        private boolean actif = true;
        private String hote = "localhost";
        private int port = 3310;
        private int delaiConnexionMs = 2000;
        /** Délai de réponse : clamd ne répond qu'une fois tout le flux analysé. */
        private int delaiLectureMs = 120_000;
        private int tailleBlocOctets = 64 * 1024;
    }

    @Getter
    @Setter
    public static class Integrite {
        private boolean verificationPlanifiee = false;
        /** Mensuelle : le 1er du mois à 3 h. */
        private String cron = "0 0 3 1 * *";
    }

    @Getter
    @Setter
    public static class Previsualisation {
        /** Active le point d'entrée d'aperçu (exige un ResolveurFichierVersion). */
        private boolean apiActive = false;
        /** Commande LibreOffice (exécutable puis arguments éventuels). */
        private List<String> libreofficeCommande = new ArrayList<>(List.of("soffice"));
        private int delaiConversionS = 120;
        /** Répertoire de travail de la conversion : un tmpfs en production. */
        private String repertoireTravail = System.getProperty("java.io.tmpdir") + "/ged-apercu";
    }

    @Getter
    @Setter
    public static class Reprise {
        /** Racine de l'ancien stockage en clair ; renseignée = reprise lancée au démarrage. */
        private String source;
        /** Rapport CSV (ancien chemin → fichier chiffré, empreinte). */
        private String rapport = "./reprise-fichiers.csv";
        /** Analyser aussi le fonds historique avec ClamAV. */
        private boolean antivirus = false;
    }
}
