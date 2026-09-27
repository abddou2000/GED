package com.ipt.ged.fichier.reprise;

import com.ipt.ged.fichier.ErreurFichierException;
import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.fichier.controle.SourceFichier;
import com.ipt.ged.ocr.file.EnfilageOcr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Reprise des versions de l'ancien stockage en clair vers le stockage chiffré
 * (§6.1.1 à §6.1.4), <b>pilotée par la base</b> : chaque ligne de
 * {@code version_document} sans {@code cle_fichier_id} désigne, par son
 * {@code file_path}, un fichier sous l'ancienne racine.
 *
 * <p>Pour chaque version : chemin confiné sous la racine, type réel, antivirus
 * facultatif, chiffrement sous une DEK neuve avec empreinte, <b>relecture
 * complète de contrôle</b>, puis, dans une transaction, renseignement de la
 * version (clé, empreinte, type, taille) et, pour une version courante, envoi à
 * l'OCR — le fonds repris devient interrogeable. Une ligne de rapport CSV par
 * version. Aucun fichier en clair n'est supprimé : c'est une étape
 * d'exploitation séparée, après sauvegarde.
 *
 * <p>Reprenable : seules les versions encore sans clé sont traitées ; la
 * mise à jour est conditionnelle ({@code cle_fichier_id IS NULL}). Quand
 * toutes sont reprises, le changeset « contract » s'applique à la montée
 * suivante et retire les chemins en clair.
 */
public class RepriseVersionsEnClair {

    private static final Logger log = LoggerFactory.getLogger(RepriseVersionsEnClair.class);
    static final String ENTETE = "version_id;chemin_relatif;fichier_id;empreinte_sha256;taille_octets;type_mime;statut";
    private static final int PAGE = 200;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StockageChiffre stockage;
    private final DetecteurTypeReel detecteur;
    private final AnalyseurAntivirus antivirus;
    private final EnfilageOcr ocr;
    private final long plafondOctets;

    /** @param antivirus {@code null} pour ne pas analyser le fonds historique. */
    public RepriseVersionsEnClair(JdbcTemplate jdbc, TransactionTemplate transaction, StockageChiffre stockage,
                                  DetecteurTypeReel detecteur, AnalyseurAntivirus antivirus, EnfilageOcr ocr,
                                  long plafondOctets) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.stockage = stockage;
        this.detecteur = detecteur;
        this.antivirus = antivirus;
        this.ocr = ocr;
        this.plafondOctets = plafondOctets;
    }

    private record Version(UUID id, UUID documentId, String chemin, boolean courante, String codeType) {
    }

    public Rapport reprendre(Path racineAncienStockage, Path fichierRapport) throws IOException {
        if (!colonneCheminPresente()) {
            log.info("Reprise sans objet : les chemins en clair ont déjà été retirés (changeset contract appliqué).");
            return new Rapport(0, 0);
        }
        Path racine = racineAncienStockage.toAbsolutePath().normalize();
        boolean nouveau = !Files.exists(fichierRapport);
        if (fichierRapport.toAbsolutePath().getParent() != null) {
            Files.createDirectories(fichierRapport.toAbsolutePath().getParent());
        }
        int ok = 0, echecs = 0;
        try (BufferedWriter rapport = Files.newBufferedWriter(fichierRapport, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (nouveau) {
                rapport.write(ENTETE);
                rapport.newLine();
            }
            UUID curseur = null;
            List<Version> page;
            do {
                page = lot(curseur);
                for (Version v : page) {
                    curseur = v.id();
                    String ligne = reprendreUne(racine, v);
                    rapport.write(ligne);
                    rapport.newLine();
                    rapport.flush();
                    if (ligne.endsWith(";OK")) ok++; else echecs++;
                }
            } while (page.size() == PAGE);
        }
        log.info("Reprise des versions : {} reprise(s), {} échec(s). Rapport : {}", ok, echecs, fichierRapport);
        return new Rapport(ok, echecs);
    }

    private boolean colonneCheminPresente() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = 'version_document' AND column_name = 'file_path'", Integer.class);
        return n != null && n > 0;
    }

    private List<Version> lot(UUID apres) {
        String sql = "SELECT v.id, v.document_id, v.file_path, v.is_default, t.code FROM version_document v "
                + "JOIN document d ON d.id = v.document_id JOIN type_document t ON t.id = d.type_document_id "
                + "WHERE v.cle_fichier_id IS NULL AND v.file_path IS NOT NULL "
                + (apres == null ? "" : "AND v.id > ? ") + "ORDER BY v.id LIMIT " + PAGE;
        org.springframework.jdbc.core.RowMapper<Version> m = (rs, i) -> new Version(rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("file_path"), rs.getBoolean("is_default"),
                rs.getString("code"));
        return apres == null ? jdbc.query(sql, m) : jdbc.query(sql, m, apres);
    }

    private String reprendreUne(Path racine, Version v) {
        Path fichier = racine.resolve(v.chemin()).normalize();
        if (!fichier.startsWith(racine)) {
            log.error("Reprise de la version {} refusée : chemin hors de l'ancienne racine ({})", v.id(), v.chemin());
            return ligne(v, "", "", "", "", "CHEMIN_HORS_RACINE");
        }
        if (!Files.isRegularFile(fichier, LinkOption.NOFOLLOW_LINKS)) {
            return ligne(v, "", "", "", "", "FICHIER_ABSENT");
        }
        StockageChiffre.ResultatStockage r = null;
        try {
            SourceFichier source = SourceFichier.de(fichier);
            String type = detecteur.detecter(source);
            if (antivirus != null) antivirus.analyser(source);
            try (InputStream in = Files.newInputStream(fichier)) {
                r = stockage.ecrire(in, plafondOctets);
            }
            if (!empreinteRelue(r.id()).equals(r.empreinte())) {
                stockage.detruire(r.id());
                return ligne(v, "", "", "", type, "ECHEC_RELECTURE");
            }
            StockageChiffre.ResultatStockage fait = r;
            Boolean maj = transaction.execute(st -> {
                int n = jdbc.update("UPDATE version_document SET cle_fichier_id = ?, empreinte = ?, type_mime = ?, "
                        + "taille_octets = ? WHERE id = ? AND cle_fichier_id IS NULL",
                        fait.id(), fait.empreinte(), type, fait.tailleOctets(), v.id());
                if (n == 1 && v.courante()) {
                    ocr.enfiler(v.documentId(), v.id(), fait.id(), type, v.codeType());
                }
                return n == 1;
            });
            if (!Boolean.TRUE.equals(maj)) {
                stockage.detruire(r.id());
                return ligne(v, "", "", "", type, "DEJA_REPRISE");
            }
            return ligne(v, r.id().toString(), r.empreinte(), String.valueOf(r.tailleOctets()), type, "OK");
        } catch (ErreurFichierException e) {
            if (r != null) stockage.detruire(r.id());
            log.error("Reprise de la version {} refusée : {}", v.id(), e.getMessage());
            return ligne(v, "", "", "", "", e.code());
        } catch (IOException | RuntimeException e) {
            if (r != null) stockage.detruire(r.id());
            log.error("Reprise de la version {} impossible", v.id(), e);
            return ligne(v, "", "", "", "", "ECHEC");
        }
    }

    private String empreinteRelue(UUID id) throws IOException {
        MessageDigest sha = StockageChiffre.sha256();
        try (InputStream in = stockage.lire(id)) {
            byte[] t = new byte[64 * 1024];
            int n;
            while ((n = in.read(t)) >= 0) sha.update(t, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static String ligne(Version v, String fichierId, String empreinte, String taille, String type, String statut) {
        return String.join(";", v.id().toString(), protege(v.chemin()), fichierId, empreinte, taille, type, statut);
    }

    private static String protege(String c) {
        return c.contains(";") || c.contains("\"") ? '"' + c.replace("\"", "\"\"") + '"' : c;
    }

    public record Rapport(int reprises, int echecs) {
    }
}
