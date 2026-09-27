package com.ipt.ged.cycledevie;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Paramètres du cycle de vie ({@code ged.cycledevie.*}) : archivage, copie de conservation, export. */
@ConfigurationProperties(prefix = "ged.cycledevie")
public class ProprietesCycleDeVie {

    /** Travailleur des traitements de fond (jobs d'archivage et d'export). */
    private final Travailleur travailleur = new Travailleur();
    private final Archivage archivage = new Archivage();
    private final Export export = new Export();

    public Travailleur getTravailleur() {
        return travailleur;
    }

    public Archivage getArchivage() {
        return archivage;
    }

    public Export getExport() {
        return export;
    }

    public static class Travailleur {
        /** Faux : aucun job n'est traité par cette instance (tests, instance de consultation). */
        private boolean actif = true;
        /** Délai entre deux recherches de job. */
        private Duration intervalle = Duration.ofSeconds(5);
        /** Bail d'un job réservé, prolongé à chaque tranche ; au-delà, une autre instance le reprend. */
        private Duration bail = Duration.ofMinutes(15);

        public boolean isActif() {
            return actif;
        }

        public void setActif(boolean actif) {
            this.actif = actif;
        }

        public Duration getIntervalle() {
            return intervalle;
        }

        public void setIntervalle(Duration intervalle) {
            this.intervalle = intervalle;
        }

        public Duration getBail() {
            return bail;
        }

        public void setBail(Duration bail) {
            this.bail = bail;
        }
    }

    public static class Archivage {
        /** Documents par tranche, chacune dans sa transaction (§12.6). */
        private int tranche = 100;
        /** Répertoire de travail de la conversion PDF/A : un tmpfs en production (copie en clair éphémère). */
        private String repertoireTravail = System.getProperty("java.io.tmpdir") + "/ged-conservation";

        public int getTranche() {
            return tranche;
        }

        public void setTranche(int tranche) {
            this.tranche = tranche;
        }

        public String getRepertoireTravail() {
            return repertoireTravail;
        }

        public void setRepertoireTravail(String repertoireTravail) {
            this.repertoireTravail = repertoireTravail;
        }
    }

    public static class Export {
        /** Au-delà, l'export devient un traitement de fond (§12.10). */
        private int seuilDocuments = 500;
        private long seuilOctets = 2L * 1024 * 1024 * 1024;
        /** Durée de conservation d'un export produit, puis destruction (clé et fichier). */
        private Duration retention = Duration.ofDays(7);
        /** Plafond d'une archive produite en traitement de fond. */
        private long tailleMaxOctets = 50L * 1024 * 1024 * 1024;

        public int getSeuilDocuments() {
            return seuilDocuments;
        }

        public void setSeuilDocuments(int seuilDocuments) {
            this.seuilDocuments = seuilDocuments;
        }

        public long getSeuilOctets() {
            return seuilOctets;
        }

        public void setSeuilOctets(long seuilOctets) {
            this.seuilOctets = seuilOctets;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }

        public long getTailleMaxOctets() {
            return tailleMaxOctets;
        }

        public void setTailleMaxOctets(long tailleMaxOctets) {
            this.tailleMaxOctets = tailleMaxOctets;
        }
    }
}
