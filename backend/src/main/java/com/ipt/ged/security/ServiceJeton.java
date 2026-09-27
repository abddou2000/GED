package com.ipt.ged.security;

import com.ipt.ged.identite.ProprietesIdentite;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Jeton d'accès : JWT signé <b>RS256</b>, valable 15 minutes (dossier technique
 * §3.3, §3.4.1).
 *
 * <p>Il ne porte que l'identité : l'identifiant technique GED ({@code sub}),
 * l'identifiant d'annuaire ({@code uid}) et la session d'origine ({@code sid}).
 * <b>Aucune permission</b> : rôles et habilitations sont relus côté GED à chaque
 * requête.
 *
 * <p><b>Clé privée hors dépôt</b> : elle est lue dans un magasin PKCS#12 dont le
 * chemin et le mot de passe viennent de l'environnement (coffre de MMED). Sans
 * magasin, le démarrage échoue — sauf si {@code cle-ephemere-autorisee} est vrai
 * (profils dev et test), auquel cas une paire RSA est tirée au démarrage et les
 * jetons ne survivent pas à un redémarrage.
 *
 * <p>Vérifications à la lecture : signature RSA avec la clé publique (un jeton
 * {@code alg: none} ou HMAC est refusé), émetteur, expiration avec tolérance
 * d'horloge, et borne haute sur {@code exp - iat} (un jeton de longue durée ne
 * peut pas venir de ce service).
 */
@Service
public class ServiceJeton {

    private static final Logger log = LoggerFactory.getLogger(ServiceJeton.class);

    /** Taille minimale acceptée pour la clé RSA. */
    static final int BITS_MINIMUM = 2048;

    /** Revendications portées par un jeton valide. */
    public record JetonAcces(UUID utilisateurId, String identifiant, UUID sessionId) {}

    private final PrivateKey clePrivee;
    private final PublicKey clePublique;
    private final String idCle;
    private final Duration validite;
    private final String emetteur;
    private final long toleranceSecondes;

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceJeton(ProprietesIdentite proprietes) {
        this(proprietes, chargerOuTirer(proprietes.getJeton()));
    }

    /** Paire de clés fournie : réservé aux tests, qui forgent des jetons avec la même clé. */
    ServiceJeton(ProprietesIdentite proprietes, KeyPair paire) {
        ProprietesIdentite.Jeton p = proprietes.getJeton();
        this.validite = p.getValidite();
        this.emetteur = p.getEmetteur();
        this.toleranceSecondes = Math.max(0, p.getToleranceHorloge().toSeconds());
        this.clePrivee = paire.getPrivate();
        this.clePublique = paire.getPublic();
        this.idCle = p.getAlias();
    }

    private static KeyPair chargerOuTirer(ProprietesIdentite.Jeton p) {
        if (p.getKeystore() == null || p.getKeystore().isBlank()) {
            if (!p.isCleEphemereAutorisee()) {
                throw new IllegalStateException("Clé de signature JWT absente : renseigner GED_JWT_KEYSTORE "
                        + "(magasin PKCS#12 contenant une clé RSA de " + BITS_MINIMUM + " bits au moins), "
                        + "GED_JWT_KEYSTORE_MOT_DE_PASSE et GED_JWT_ALIAS. Voir DEPLOIEMENT.md.");
            }
            log.warn("Aucun magasin de clé JWT : paire RSA tirée pour cette exécution (dev/test uniquement). "
                    + "Les jetons émis deviendront invalides au prochain redémarrage.");
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(3072);
                return g.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("Génération RSA impossible", e);
            }
        }
        return charger(p.getKeystore(), p.getKeystoreMotDePasse(), p.getAlias());
    }

    /** Lit la clé privée et la clé publique (certificat) d'une entrée PKCS#12. */
    static KeyPair charger(String chemin, String motDePasse, String alias) {
        char[] mdp = motDePasse == null ? new char[0] : motDePasse.toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(chemin))) {
            KeyStore magasin = KeyStore.getInstance("PKCS12");
            magasin.load(in, mdp);
            Key cle = magasin.getKey(alias, mdp);
            Certificate certificat = magasin.getCertificate(alias);
            if (!(cle instanceof PrivateKey privee) || certificat == null) {
                throw new IllegalStateException("Entrée « " + alias + " » sans clé privée ni certificat dans " + chemin);
            }
            PublicKey publique = certificat.getPublicKey();
            if (!(publique instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < BITS_MINIMUM) {
                throw new IllegalStateException("La clé JWT doit être une clé RSA de " + BITS_MINIMUM
                        + " bits au moins (RS256).");
            }
            return new KeyPair(publique, privee);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Magasin de clé JWT illisible : " + chemin, e);
        }
    }

    /** Durée de validité, publiée au client pour qu'il sache quand renouveler. */
    public long validiteSecondes() {
        return validite.toSeconds();
    }

    public String emettre(UUID utilisateurId, String identifiant, UUID sessionId) {
        Instant maintenant = Instant.now();
        return Jwts.builder()
                .header().keyId(idCle).and()
                .issuer(emetteur)
                .subject(utilisateurId.toString())
                .claim("uid", identifiant)
                .claim("sid", sessionId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(validite)))
                .signWith(clePrivee, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Revendications d'un jeton valide, ou vide si le jeton est absent, expiré,
     * mal formé, signé avec une autre clé ou un autre algorithme, émis par un
     * autre émetteur, ou d'une durée de vie supérieure à celle qu'accorde ce
     * service.
     */
    public Optional<JetonAcces> lire(String jeton) {
        try {
            Claims contenu = Jwts.parser()
                    .verifyWith(clePublique)
                    .requireIssuer(emetteur)
                    .clockSkewSeconds(toleranceSecondes)
                    .build()
                    .parseSignedClaims(jeton)
                    .getPayload();
            if (!dureeDeVieAcceptable(contenu)) {
                log.debug("Jeton refusé : durée de vie supérieure à la validité configurée.");
                return Optional.empty();
            }
            String sid = contenu.get("sid", String.class);
            String uid = contenu.get("uid", String.class);
            if (sid == null || uid == null || contenu.getSubject() == null) return Optional.empty();
            return Optional.of(new JetonAcces(UUID.fromString(contenu.getSubject()), uid, UUID.fromString(sid)));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Jeton refusé : {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean dureeDeVieAcceptable(Claims contenu) {
        Date emisLe = contenu.getIssuedAt();
        Date expireLe = contenu.getExpiration();
        if (emisLe == null || expireLe == null) return false;
        long dureeSecondes = (expireLe.getTime() - emisLe.getTime()) / 1000L;
        return dureeSecondes > 0 && dureeSecondes <= validite.toSeconds() + toleranceSecondes;
    }
}
