package com.ipt.ged.fichier.reprise;

import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.AnalyseurAntivirus;
import com.ipt.ged.fichier.controle.DetecteurTypeReel;
import com.ipt.ged.fichier.controle.SourceFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reprise des fichiers existants, stockés en clair par l'ancien
 * {@code StorageService} ({@code <racine>/<espace>/<uuid>.<ext>}), vers le
 * stockage chiffré.
 *
 * <p>Pour chaque fichier : type réel détecté, analyse antivirus facultative,
 * écriture chiffrée sous un nouvel identifiant avec calcul de l'empreinte,
 * puis <b>relecture de contrôle</b> (déchiffrement complet et comparaison de
 * l'empreinte) avant d'inscrire la ligne au rapport. Le rapport CSV fait le
 * lien entre l'ancien chemin et le nouveau fichier ; c'est lui que le script
 * SQL de bascule applique à {@code version_document}.
 *
 * <p><b>Aucun original n'est supprimé</b> : la suppression des fichiers en
 * clair est une étape séparée, après application du rapport en base et
 * sauvegarde (voir le suivi dev3).
 *
 * <p><b>Reprenable</b> : les chemins déjà inscrits au rapport avec le statut
 * {@code OK} sont ignorés ; une reprise interrompue se relance telle quelle.
 */
public class RepriseFichiersEnClair {

    private static final Logger log = LoggerFactory.getLogger(RepriseFichiersEnClair.class);
    static final String ENTETE = "chemin_relatif;fichier_id;empreinte_sha256;taille_octets;type_mime;kek_id;statut";

    private final StockageChiffre stockage;
    private final DetecteurTypeReel detecteur;
    private final AnalyseurAntivirus antivirus;
    private final long plafondOctets;

    /**
     * @param antivirus {@code null} pour ne pas analyser (fonds historique
     *                  déjà présent, ClamAV absent de l'environnement de reprise).
     */
    public RepriseFichiersEnClair(StockageChiffre stockage, DetecteurTypeReel detecteur,
                                  AnalyseurAntivirus antivirus, long plafondOctets) {
        this.stockage = stockage;
        this.detecteur = detecteur;
        this.antivirus = antivirus;
        this.plafondOctets = plafondOctets;
    }

    public Rapport reprendre(Path racineEnClair, Path fichierRapport) throws IOException {
        Path racine = racineEnClair.toAbsolutePath().normalize();
        Set<String> dejaFaits = dejaReprises(fichierRapport);
        boolean nouveau = !Files.exists(fichierRapport);
        int ok = 0, ignores = 0, echecs = 0;
        if (fichierRapport.getParent() != null) Files.createDirectories(fichierRapport.getParent());
        try (BufferedWriter rapport = Files.newBufferedWriter(fichierRapport, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             Stream<Path> fichiers = Files.walk(racine)) {
            if (nouveau) {
                rapport.write(ENTETE);
                rapport.newLine();
            }
            Path rapportAbsolu = fichierRapport.toAbsolutePath().normalize();
            // Liens symboliques ignorés : ils pourraient faire reprendre un
            // fichier hors de la racine.
            List<Path> liste = fichiers
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.toAbsolutePath().normalize().equals(rapportAbsolu))
                    .sorted()
                    .toList();
            for (Path fichier : liste) {
                String relatif = racine.relativize(fichier).toString().replace('\\', '/');
                if (dejaFaits.contains(relatif)) {
                    ignores++;
                    continue;
                }
                String ligne = reprendreUn(fichier, relatif);
                rapport.write(ligne);
                rapport.newLine();
                rapport.flush();
                if (ligne.endsWith(";OK")) ok++; else echecs++;
            }
        }
        log.info("Reprise terminée : {} fichier(s) chiffré(s), {} déjà repris, {} échec(s). Rapport : {}",
                ok, ignores, echecs, fichierRapport);
        return new Rapport(ok, ignores, echecs);
    }

    private String reprendreUn(Path fichier, String relatif) {
        try {
            SourceFichier source = SourceFichier.de(fichier);
            String type = detecteur.detecter(source);
            if (antivirus != null) {
                try {
                    antivirus.analyser(source);
                } catch (ErreurFichierException e) {
                    log.error("Reprise de {} refusée : {}", relatif, e.getMessage());
                    return ligne(relatif, "", "", "", type, "", e.code());
                }
            }
            StockageChiffre.ResultatStockage r;
            try (InputStream in = Files.newInputStream(fichier)) {
                r = stockage.ecrire(in, plafondOctets);
            }
            String relue = empreinteRelue(r);
            if (!relue.equals(r.empreinte())) {
                stockage.detruire(r.id());
                return ligne(relatif, "", "", "", type, "", "ECHEC_RELECTURE");
            }
            return ligne(relatif, r.id().toString(), r.empreinte(), String.valueOf(r.tailleOctets()),
                    type, r.kekId(), "OK");
        } catch (ErreurFichierException e) {
            log.error("Reprise de {} impossible : {}", relatif, e.getMessage());
            return ligne(relatif, "", "", "", "", "", e.code());
        } catch (IOException | RuntimeException e) {
            log.error("Reprise de {} impossible", relatif, e);
            return ligne(relatif, "", "", "", "", "", "ECHEC");
        }
    }

    private String empreinteRelue(StockageChiffre.ResultatStockage r) throws IOException {
        MessageDigest sha = StockageChiffre.sha256();
        try (InputStream in = stockage.lire(r.id())) {
            byte[] tampon = new byte[64 * 1024];
            int n;
            while ((n = in.read(tampon)) >= 0) sha.update(tampon, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static Set<String> dejaReprises(Path rapport) throws IOException {
        Set<String> faits = new HashSet<>();
        if (!Files.exists(rapport)) return faits;
        try (Stream<String> lignes = Files.lines(rapport, StandardCharsets.UTF_8)) {
            lignes.skip(1).filter(l -> l.endsWith(";OK")).forEach(l -> faits.add(champ(l, 0)));
        }
        return faits;
    }

    /** Champs séparés par {@code ;} ; le chemin est protégé s'il contient le séparateur. */
    private static String ligne(String... champs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < champs.length; i++) {
            if (i > 0) sb.append(';');
            String c = champs[i];
            if (c.contains(";") || c.contains("\"")) c = '"' + c.replace("\"", "\"\"") + '"';
            sb.append(c);
        }
        return sb.toString();
    }

    private static String champ(String ligne, int rang) {
        if (ligne.startsWith("\"") && rang == 0) {
            int fin = ligne.indexOf("\";");
            return ligne.substring(1, fin).replace("\"\"", "\"");
        }
        return ligne.split(";", -1)[rang];
    }

    public record Rapport(int reprises, int dejaReprises, int echecs) {
    }
}
