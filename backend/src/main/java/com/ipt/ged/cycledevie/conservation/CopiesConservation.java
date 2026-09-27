package com.ipt.ged.cycledevie.conservation;

import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.previsualisation.Fichiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Copies de conservation PDF/A-2 des versions (§6.1.4), table
 * {@code copie_conservation}.
 *
 * <p>Production : la version est déchiffrée dans un répertoire de travail
 * éphémère (tmpfs en production), convertie et validée
 * ({@link ConvertisseurPdfA}), puis la copie est <b>chiffrée comme tout
 * fichier</b> (DEK propre, empreinte SHA-256). L'original est conservé. La
 * production se fait hors transaction (la conversion peut durer) ;
 * l'enregistrement, lui, rejoint la transaction de l'archivage.
 */
public class CopiesConservation {

    private static final Logger log = LoggerFactory.getLogger(CopiesConservation.class);

    private final JdbcTemplate jdbc;
    private final StockageChiffre stockage;
    private final ConvertisseurPdfA convertisseur;
    private final Path travail;
    private final long plafondOctets;

    public CopiesConservation(JdbcTemplate jdbc, StockageChiffre stockage, ConvertisseurPdfA convertisseur,
                              Path travail, long plafondOctets) {
        this.jdbc = jdbc;
        this.stockage = stockage;
        this.convertisseur = convertisseur;
        this.travail = travail;
        this.plafondOctets = plafondOctets;
    }

    /** Copie valide enregistrée pour une version. */
    public record CopieValide(UUID cleFichierId, String empreinte, long tailleOctets) {
    }

    /**
     * Résultat d'une production : {@code VALIDE} avec son fichier chiffré, ou
     * {@code ECHEC} avec son motif (l'archivage n'en est pas bloqué).
     */
    public record Production(String statut, UUID cleFichierId, String empreinte, Long tailleOctets,
                             String methode, String motif, boolean reutilisee) {
        public boolean valide() {
            return "VALIDE".equals(statut);
        }

        static Production echec(String motif) {
            return new Production("ECHEC", null, null, null, null, motif, false);
        }
    }

    public Optional<CopieValide> valide(UUID versionId) {
        return jdbc.query("SELECT cle_fichier_id, empreinte, taille_octets FROM copie_conservation "
                        + "WHERE version_id = ? AND statut = 'VALIDE'",
                (rs, i) -> new CopieValide(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)), versionId)
                .stream().findFirst();
    }

    /** Copie d'une version telle qu'affichée sur la fiche du document. */
    public record Info(String statut, String methode, String format, String motif, String empreinte,
                       Long tailleOctets, Instant creeLe) {
    }

    public Optional<Info> info(UUID versionId) {
        return jdbc.query("SELECT statut, methode, format, motif, empreinte, taille_octets, cree_le "
                        + "FROM copie_conservation WHERE version_id = ?",
                (rs, i) -> new Info(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Long) rs.getObject(6),
                        rs.getTimestamp(7) != null ? rs.getTimestamp(7).toInstant() : null), versionId)
                .stream().findFirst();
    }

    /** Statut de la copie d'une version ({@code VALIDE}, {@code ECHEC}) ou vide si aucune. */
    public Optional<String> statut(UUID versionId) {
        return jdbc.queryForList("SELECT statut FROM copie_conservation WHERE version_id = ?", String.class, versionId)
                .stream().findFirst();
    }

    /**
     * Produit la copie de conservation d'une version, ou réutilise celle qui
     * existe déjà (désarchivage puis réarchivage de la même version).
     */
    public Production produire(UUID versionId, UUID cleFichierId, String typeMime, String titre) {
        Optional<CopieValide> existante = valide(versionId);
        if (existante.isPresent() && stockage.existe(existante.get().cleFichierId())) {
            CopieValide c = existante.get();
            return new Production("VALIDE", c.cleFichierId(), c.empreinte(), c.tailleOctets(), null, null, true);
        }
        Path dossier = null;
        try {
            Files.createDirectories(travail);
            dossier = Files.createTempDirectory(travail, "conservation-");
            Path source = dossier.resolve("source" + extension(typeMime));
            try (InputStream in = stockage.lire(cleFichierId)) {
                Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
            }
            ConvertisseurPdfA.Copie copie = convertisseur.convertir(source, typeMime, titre, dossier);
            StockageChiffre.ResultatStockage r;
            try (InputStream pdf = Files.newInputStream(copie.pdf())) {
                r = stockage.ecrire(pdf, plafondOctets);
            }
            return new Production("VALIDE", r.id(), r.empreinte(), r.tailleOctets(), copie.methode(), null, false);
        } catch (ConvertisseurPdfA.ConversionImpossible e) {
            log.warn("Copie de conservation PDF/A impossible pour la version {} : {}", versionId, e.getMessage());
            return Production.echec(tronquer(e.getMessage()));
        } catch (IOException | UncheckedIOException | com.ipt.ged.fichier.ErreurFichierException e) {
            log.warn("Copie de conservation PDF/A impossible pour la version {}", versionId, e);
            return Production.echec(tronquer("production de la copie : " + e.getMessage()));
        } finally {
            Fichiers.supprimerArborescence(dossier);
        }
    }

    /**
     * Enregistre le résultat pour la version, dans la transaction de
     * l'appelant ; une copie précédente d'une autre production est remplacée
     * (son fichier détruit après validation par l'appelant).
     *
     * @return identifiant du fichier de la copie remplacée, à détruire, ou vide.
     */
    public Optional<UUID> enregistrer(UUID versionId, Production p, UUID par) {
        Optional<UUID> remplace = jdbc.queryForList(
                "SELECT cle_fichier_id FROM copie_conservation WHERE version_id = ? AND cle_fichier_id IS NOT NULL",
                UUID.class, versionId).stream().findFirst().filter(id -> !id.equals(p.cleFichierId()));
        jdbc.update("DELETE FROM copie_conservation WHERE version_id = ?", versionId);
        jdbc.update("INSERT INTO copie_conservation (id, version_id, cle_fichier_id, empreinte, taille_octets, statut, "
                        + "methode, format, motif, cree_le, cree_par_employe_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), versionId, p.cleFichierId(), p.empreinte(), p.tailleOctets(), p.statut(),
                p.methode(), p.valide() ? "PDF/A-2B" : null, p.motif(), java.sql.Timestamp.from(Instant.now()), par);
        return remplace;
    }

    private static String extension(String typeMime) {
        if (typeMime == null) return "";
        return switch (typeMime) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/tiff" -> ".tif";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.ms-powerpoint" -> ".ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "application/vnd.oasis.opendocument.text" -> ".odt";
            case "application/vnd.oasis.opendocument.spreadsheet" -> ".ods";
            case "application/vnd.oasis.opendocument.presentation" -> ".odp";
            case "application/rtf" -> ".rtf";
            default -> "";
        };
    }

    private static String tronquer(String s) {
        return s != null && s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
