package com.ipt.ged.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Fabrique et vérifie les jetons JWT.
 *
 * <p>Signature HMAC-SHA256 : le serveur seul détient la clé, donc lui seul peut
 * produire un jeton valide. Un jeton modifié côté client — pour s'attribuer un
 * autre e-mail, par exemple — échoue à la vérification de signature.
 *
 * <p>Le jeton ne transporte que l'e-mail, l'identifiant d'employé et les dates.
 * L'état du compte (actif ou non) est relu en base à chaque requête : une
 * désactivation prend donc effet immédiatement, alors qu'un état gravé dans le
 * jeton resterait valable jusqu'à son expiration.
 *
 * <h2>Provenance de la clé (constat d'audit E1)</h2>
 * <p>Aucune clé par défaut n'existe plus dans un fichier versionné. Une clé « de
 * développement » commise reste lisible par quiconque obtient le dépôt ou son
 * historique : elle permet alors de <b>forger</b> un jeton super-administrateur
 * accepté par le serveur, sans jamais connaître de mot de passe. La clé vient
 * donc exclusivement de l'environnement ({@code GED_JWT_CLE}).
 * <ul>
 *   <li><b>prod</b> : clé absente = refus de démarrer. Mieux vaut une panne
 *       bruyante qu'un service qui tourne avec une signature devinable.</li>
 *   <li><b>hors prod</b> (dev, test) : une clé aléatoire est tirée au démarrage.
 *       Les jetons ne survivent pas à un redémarrage — c'est voulu : rien de
 *       durable ne peut être signé avec un secret que personne n'a choisi.</li>
 * </ul>
 *
 * <h2>Borne sur la durée de vie (constat d'audit M1)</h2>
 * <p>Vérifier signature + expiration ne suffit pas : un jeton forgé (ou émis par
 * une version antérieure mal configurée) peut porter une expiration à trente
 * ans. On rejette donc tout jeton dont l'intervalle {@code exp - iat} dépasse la
 * validité configurée, et on exige l'émetteur attendu.
 */
@Service
public class ServiceJeton {

    private static final Logger log = LoggerFactory.getLogger(ServiceJeton.class);

    /** Longueur minimale de la clé : HMAC-SHA256 exige 256 bits. */
    private static final int OCTETS_MINIMUM = 32;

    /** Taille de la clé tirée au sort hors production (384 bits). */
    private static final int OCTETS_ALEATOIRES = 48;

    private final SecretKey cle;
    private final Duration validite;
    private final String emetteur;
    private final long toleranceHorlogeSecondes;

    public ServiceJeton(@Value("${ged.securite.jwt.cle:}") String cleBrute,
                        @Value("${ged.securite.jwt.validite-minutes:120}") long validiteMinutes,
                        @Value("${ged.securite.jwt.emetteur:ged-api}") String emetteur,
                        @Value("${ged.securite.jwt.tolerance-horloge-secondes:60}") long toleranceHorlogeSecondes,
                        Environment environnement) {
        this.validite = Duration.ofMinutes(validiteMinutes);
        this.emetteur = emetteur;
        this.toleranceHorlogeSecondes = Math.max(0, toleranceHorlogeSecondes);
        this.cle = resoudreCle(cleBrute, estProduction(environnement));
    }

    /**
     * Le profil {@code prod} est le seul à exiger une clé fournie. Le choix se
     * fait ici, dans le constructeur, plutôt que par deux beans conditionnels :
     * la règle « d'où vient la clé » reste lisible en un seul endroit, et elle
     * s'applique automatiquement à tous les profils non listés (test, ci…) sans
     * qu'on ait à penser à les déclarer.
     */
    private static boolean estProduction(Environment environnement) {
        if (environnement == null) return false;
        for (String profil : environnement.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profil)) return true;
        }
        return false;
    }

    private static SecretKey resoudreCle(String brute, boolean production) {
        if (brute == null || brute.isBlank()) {
            if (production) {
                // Échec explicite et documenté : l'exploitant doit savoir quoi faire.
                throw new IllegalStateException(
                        "Clé JWT absente en profil prod. Définissez la variable d'environnement "
                                + "GED_JWT_CLE (32 octets minimum) avant de démarrer. "
                                + "Génération d'une clé correcte : openssl rand -base64 48. "
                                + "Ne remettez jamais cette valeur dans un fichier versionné.");
            }
            SecretKey aleatoire = cleAleatoire();
            log.warn("""
                    GED_JWT_CLE absente : une clé aléatoire a été tirée pour cette exécution. \
                    Les jetons émis deviendront invalides au prochain redémarrage. \
                    Acceptable en développement uniquement — en production le démarrage échoue.""");
            return aleatoire;
        }
        return construireCle(brute);
    }

    private static SecretKey cleAleatoire() {
        byte[] octets = new byte[OCTETS_ALEATOIRES];
        new SecureRandom().nextBytes(octets);
        return Keys.hmacShaKeyFor(octets);
    }

    /**
     * Accepte une clé en Base64 ou en texte brut. Une clé trop courte est
     * refusée au démarrage plutôt qu'à la première connexion : une application
     * qui démarre avec une signature faible est un piège silencieux.
     *
     * <p>Le décodage Base64 est <b>strict</b> ({@link Base64#getDecoder()}) et
     * non celui, permissif, de la bibliothèque JWT : ce dernier s'arrête au
     * premier caractère hors alphabet, ce qui transformait silencieusement une
     * clé de 52 caractères en 36 octets seulement. Une clé partiellement
     * ignorée réduit d'autant l'effort d'une attaque hors ligne.
     */
    private static SecretKey construireCle(String brute) {
        String valeur = brute.trim();
        byte[] octets;
        try {
            octets = Base64.getDecoder().decode(valeur);
        } catch (IllegalArgumentException e) {
            // Pas du Base64 valide : on prend la chaîne telle quelle, sans
            // rien tronquer.
            octets = valeur.getBytes(StandardCharsets.UTF_8);
        }
        // Un Base64 qui décode en moins que la chaîne source peut aussi être
        // une phrase de passe : on garde la forme la plus longue des deux.
        byte[] enTexte = valeur.getBytes(StandardCharsets.UTF_8);
        if (octets.length < OCTETS_MINIMUM && enTexte.length >= OCTETS_MINIMUM) {
            octets = enTexte;
        }
        if (octets.length < OCTETS_MINIMUM) {
            throw new IllegalStateException(
                    "ged.securite.jwt.cle trop courte (" + octets.length + " octets) : "
                            + OCTETS_MINIMUM + " minimum pour HMAC-SHA256. "
                            + "Générez-en une avec : openssl rand -base64 48");
        }
        return Keys.hmacShaKeyFor(octets);
    }

    /** Durée de validité, publiée au client pour qu'il sache quand se reconnecter. */
    public long validiteSecondes() {
        return validite.toSeconds();
    }

    public String emettre(UtilisateurConnecte utilisateur) {
        Instant maintenant = Instant.now();
        return Jwts.builder()
                .issuer(emetteur)
                .subject(utilisateur.getUsername())
                .claim("employeId", utilisateur.getEmployeId())
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(validite)))
                .signWith(cle)
                .compact();
    }

    /**
     * E-mail porté par le jeton, ou {@code null} si le jeton est absent,
     * expiré, mal formé, signé avec une autre clé, émis par un autre émetteur,
     * ou d'une durée de vie supérieure à celle que ce service accorde.
     *
     * <p>Pas d'{@code audience} : il n'y a ici qu'un seul destinataire, cette
     * API. Une revendication d'audience ne discriminerait rien tant qu'aucun
     * second service ne partage la même clé — l'ajouter donnerait l'illusion
     * d'un contrôle sans en apporter un.
     */
    public String emailDe(String jeton) {
        try {
            Claims contenu = Jwts.parser()
                    .verifyWith(cle)
                    .requireIssuer(emetteur)
                    // Tolérance d'horloge explicite : sans elle, quelques
                    // secondes de dérive entre machines refusent des jetons
                    // parfaitement légitimes.
                    .clockSkewSeconds(toleranceHorlogeSecondes)
                    .build()
                    .parseSignedClaims(jeton)
                    .getPayload();

            if (!dureeDeVieAcceptable(contenu)) {
                log.debug("Jeton refusé : durée de vie supérieure à la validité configurée.");
                return null;
            }
            return contenu.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            // Volontairement en debug : un jeton invalide est un événement
            // ordinaire (expiration), pas une anomalie serveur.
            log.debug("Jeton refusé : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Borne haute sur {@code exp - iat} (constat M1). Un jeton dont la fenêtre
     * dépasse celle que ce service émet n'a pas pu venir de nous dans sa
     * configuration actuelle : soit il a été forgé, soit il date d'un
     * paramétrage plus permissif. Dans les deux cas il ne doit plus servir.
     *
     * <p>{@code iat} devient obligatoire : sans lui, la fenêtre est inconnue et
     * le contrôle contournable en omettant simplement la revendication.
     */
    private boolean dureeDeVieAcceptable(Claims contenu) {
        Date emisLe = contenu.getIssuedAt();
        Date expireLe = contenu.getExpiration();
        if (emisLe == null || expireLe == null) return false;

        long dureeSecondes = (expireLe.getTime() - emisLe.getTime()) / 1000L;
        if (dureeSecondes <= 0) return false;
        return dureeSecondes <= validite.toSeconds() + toleranceHorlogeSecondes;
    }
}
