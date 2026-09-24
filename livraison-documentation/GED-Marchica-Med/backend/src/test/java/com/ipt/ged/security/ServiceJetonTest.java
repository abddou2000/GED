package com.ipt.ged.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctif 3 : provenance de la clé et bornes de validité du jeton.
 *
 * <p>Tests unitaires purs (pas de contexte Spring) : chaque cas construit son
 * propre {@code ServiceJeton} avec la configuration à éprouver. C'est le seul
 * moyen d'exercer le profil {@code prod} sans démarrer une application en prod.
 *
 * <p>Chacune de ces assertions tombe si l'on retire le correctif : sans la borne
 * {@code exp - iat}, le jeton « 30 ans » passerait ; sans {@code iat}
 * obligatoire, il suffirait d'omettre la revendication pour contourner la borne ;
 * sans la vérification d'émetteur, un jeton d'une autre application signée avec
 * la même clé serait accepté ; sans le garde-fou de production, l'application
 * démarrerait avec une signature que personne n'a choisie.
 */
class ServiceJetonTest {

    private static final String CLE =
            "cle-de-test-uniquement-jamais-en-production-0123456789";
    private static final long VALIDITE_MINUTES = 120;
    private static final String EMETTEUR = "ged-api";

    private ServiceJeton service(String... profilsActifs) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profilsActifs);
        return new ServiceJeton(CLE, VALIDITE_MINUTES, EMETTEUR, 60, env);
    }

    private SecretKey cle() {
        return Keys.hmacShaKeyFor(CLE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("1. Un jeton émis par le service est relu correctement")
    void allerRetour() {
        ServiceJeton s = service("test");
        String jeton = Jwts.builder()
                .issuer(EMETTEUR)
                .subject("sara.bennani@marchica.ma")
                .claim("employeId", 1L)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(cle())
                .compact();
        assertEquals("sara.bennani@marchica.ma", s.emailDe(jeton));
    }

    @Test
    @DisplayName("2. Jeton forgé avec la bonne clé mais exp - iat = 30 ans → refusé")
    void dureeDeVieAberranteRefusee() {
        Instant maintenant = Instant.now();
        String jeton = Jwts.builder()
                .issuer(EMETTEUR)
                .subject("sara.bennani@marchica.ma")
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(Duration.ofDays(365L * 30))))
                .signWith(cle())
                .compact();
        // Signature parfaitement valide : seule la fenêtre est aberrante.
        assertNull(service("test").emailDe(jeton), "Un jeton de 30 ans doit être refusé");
    }

    @Test
    @DisplayName("3. Jeton sans iat → refusé (sinon la borne se contourne en omettant la date)")
    void sansIatRefuse() {
        String jeton = Jwts.builder()
                .issuer(EMETTEUR)
                .subject("sara.bennani@marchica.ma")
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(365L * 30))))
                .signWith(cle())
                .compact();
        assertNull(service("test").emailDe(jeton));
    }

    @Test
    @DisplayName("4. Émetteur inattendu → refusé")
    void emetteurInattenduRefuse() {
        Instant maintenant = Instant.now();
        String jeton = Jwts.builder()
                .issuer("autre-application")
                .subject("sara.bennani@marchica.ma")
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(Duration.ofMinutes(30))))
                .signWith(cle())
                .compact();
        assertNull(service("test").emailDe(jeton));
    }

    @Test
    @DisplayName("5. Jeton signé avec une autre clé → refusé")
    void autreCleRefusee() {
        Instant maintenant = Instant.now();
        SecretKey autre = Keys.hmacShaKeyFor(
                "une-toute-autre-cle-de-32-octets-au-moins-aaaa".getBytes(StandardCharsets.UTF_8));
        String jeton = Jwts.builder()
                .issuer(EMETTEUR)
                .subject("sara.bennani@marchica.ma")
                .issuedAt(Date.from(maintenant))
                .expiration(Date.from(maintenant.plus(Duration.ofMinutes(30))))
                .signWith(autre)
                .compact();
        assertNull(service("test").emailDe(jeton));
    }

    @Test
    @DisplayName("6. Profil prod sans clé → refus de démarrer")
    void prodSansCleEchoue() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ServiceJeton("", VALIDITE_MINUTES, EMETTEUR, 60, env));
        assertTrue(e.getMessage().contains("GED_JWT_CLE"));
    }

    @Test
    @DisplayName("7. Hors prod sans clé → démarrage possible avec une clé aléatoire")
    void horsProdSansCleTireUneCleAleatoire() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        ServiceJeton a = new ServiceJeton("", VALIDITE_MINUTES, EMETTEUR, 60, env);
        ServiceJeton b = new ServiceJeton("", VALIDITE_MINUTES, EMETTEUR, 60, env);

        // Deux instances = deux clés : un jeton de l'une n'est pas lu par l'autre.
        // C'est ce qui rend les jetons invalides après un redémarrage.
        String jeton = Jwts.builder()
                .issuer(EMETTEUR).subject("x@y.z")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(cle()).compact();
        assertNull(a.emailDe(jeton), "La clé aléatoire ne doit pas valider un jeton signé autrement");
        assertNull(b.emailDe(jeton));
    }

    @Test
    @DisplayName("8. Clé trop courte → refus de démarrer, même hors prod")
    void cleTropCourteRefusee() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ServiceJeton("trop-courte", VALIDITE_MINUTES, EMETTEUR, 60, env));
        assertTrue(e.getMessage().contains("trop courte"));
    }
}
