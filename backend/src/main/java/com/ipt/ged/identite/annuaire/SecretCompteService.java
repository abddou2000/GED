package com.ipt.ged.identite.annuaire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.AuthenticationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Identité du compte de service de l'annuaire, avec rechargement à chaud du
 * secret (dossier technique §3.3 : « rotation selon la politique de MMED avec
 * rechargement à chaud, sans redémarrage »).
 *
 * <p>Deux sources, par ordre de priorité :
 * <ol>
 *   <li>un fichier (déposé par le coffre de secrets de MMED), relu dès que sa
 *       date de modification change : une rotation prend effet à la connexion
 *       suivante du compte de service ;</li>
 *   <li>une valeur fixe venue d'une variable d'environnement.</li>
 * </ol>
 * Le secret n'est jamais journalisé.
 */
public class SecretCompteService implements AuthenticationSource {

    private static final Logger log = LoggerFactory.getLogger(SecretCompteService.class);

    private final String dn;
    private final String valeurFixe;
    private final Path fichier;

    private volatile FileTime lu;
    private volatile String secret;

    public SecretCompteService(String dn, String valeurFixe, String fichier) {
        this.dn = dn == null ? "" : dn;
        this.valeurFixe = valeurFixe == null ? "" : valeurFixe;
        this.fichier = fichier == null || fichier.isBlank() ? null : Path.of(fichier);
    }

    @Override
    public String getPrincipal() {
        return dn;
    }

    @Override
    public String getCredentials() {
        if (fichier == null) return valeurFixe;
        try {
            FileTime modifie = Files.getLastModifiedTime(fichier);
            if (secret == null || !modifie.equals(lu)) {
                secret = Files.readString(fichier, StandardCharsets.UTF_8).strip();
                if (lu != null) {
                    log.info("Secret du compte de service de l'annuaire rechargé ({}).", fichier);
                }
                lu = modifie;
            }
            return secret;
        } catch (IOException e) {
            // Fichier momentanément illisible pendant une rotation : on garde le
            // dernier secret connu plutôt que de couper l'annuaire.
            log.warn("Secret du compte de service illisible ({}) : dernier secret connu conservé.", fichier);
            return secret != null ? secret : valeurFixe;
        }
    }
}
