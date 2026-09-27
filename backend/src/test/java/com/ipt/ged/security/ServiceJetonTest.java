package com.ipt.ged.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.support.CertificatsDeTest;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jeton d'accès RS256 de 15 minutes, identité seule (dossier technique §3.3,
 * §3.4.1). Tests unitaires, sans contexte Spring.
 */
class ServiceJetonTest {

    private static final UUID UTILISATEUR = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private static KeyPair cle;
    private static KeyPair autreCle;

    @TempDir
    Path dossier;

    @BeforeAll
    static void cles() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        cle = g.generateKeyPair();
        autreCle = g.generateKeyPair();
    }

    private static ServiceJeton service(ProprietesIdentite p, KeyPair paire) {
        return new ServiceJeton(p, paire);
    }

    private ProprietesIdentite avecMagasin(int bits) {
        Path magasin = dossier.resolve("jwt-" + bits + ".p12");
        CertificatsDeTest.autosigne(magasin, "mdp-test", "ged-jwt", bits);
        ProprietesIdentite p = new ProprietesIdentite();
        p.getJeton().setKeystore(magasin.toString());
        p.getJeton().setKeystoreMotDePasse("mdp-test");
        p.getJeton().setAlias("ged-jwt");
        return p;
    }

    private static JsonNode partie(String jeton, int rang) throws Exception {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(jeton.split("\\.")[rang]));
    }

    @Test
    @DisplayName("RS256, 15 minutes, identité seule : sub, uid, sid — aucune permission ; clé lue dans le magasin")
    void contenu() throws Exception {
        ServiceJeton s = new ServiceJeton(avecMagasin(2048));
        String jeton = s.emettre(UTILISATEUR, "sbennani", SESSION);

        JsonNode entete = partie(jeton, 0);
        assertEquals("RS256", entete.get("alg").asText());
        assertEquals("ged-jwt", entete.get("kid").asText());

        JsonNode corps = partie(jeton, 1);
        assertEquals(UTILISATEUR.toString(), corps.get("sub").asText());
        assertEquals("sbennani", corps.get("uid").asText());
        assertEquals(SESSION.toString(), corps.get("sid").asText());
        assertEquals("ged-api", corps.get("iss").asText());
        assertEquals(900, corps.get("exp").asLong() - corps.get("iat").asLong());
        for (String interdit : new String[]{"roles", "role", "authorities", "permissions", "scope"}) {
            assertFalse(corps.has(interdit), "le jeton ne porte aucune permission : " + interdit);
        }
        assertEquals(900, s.validiteSecondes());

        Optional<ServiceJeton.JetonAcces> lu = s.lire(jeton);
        assertEquals(Optional.of(new ServiceJeton.JetonAcces(UTILISATEUR, "sbennani", SESSION)), lu);
    }

    @Test
    @DisplayName("Sans magasin : refus de démarrer (hors dev/test) ; magasin illisible ou mauvais mot de passe : refus")
    void magasinObligatoire(@TempDir Path autre) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ServiceJeton(new ProprietesIdentite()));
        assertTrue(e.getMessage().contains("GED_JWT_KEYSTORE"));

        String chemin = avecMagasin(2048).getJeton().getKeystore();
        assertEquals("RSA", ServiceJeton.charger(chemin, "mdp-test", "ged-jwt").getPrivate().getAlgorithm());
        assertThrows(IllegalStateException.class, () -> ServiceJeton.charger(chemin, "mauvais", "ged-jwt"));
        assertThrows(IllegalStateException.class, () -> ServiceJeton.charger(chemin, "mdp-test", "autre-alias"));
        assertThrows(IllegalStateException.class,
                () -> ServiceJeton.charger(autre.resolve("absent.p12").toString(), "x", "ged-jwt"));
    }

    @Test
    @DisplayName("Clé RSA de moins de 2048 bits refusée")
    void cleTropCourte() {
        String chemin = avecMagasin(1024).getJeton().getKeystore();
        assertThrows(IllegalStateException.class, () -> ServiceJeton.charger(chemin, "mdp-test", "ged-jwt"));
    }

    @Test
    @DisplayName("Autre clé RSA, HS256, alg none, jeton illisible : refusés")
    void signaturesRefusees() {
        ServiceJeton s = service(new ProprietesIdentite(), cle);
        assertTrue(s.lire(service(new ProprietesIdentite(), autreCle).emettre(UTILISATEUR, "x", SESSION)).isEmpty());

        Instant t = Instant.now();
        String hmac = Jwts.builder().issuer("ged-api").subject(UTILISATEUR.toString())
                .claim("uid", "x").claim("sid", SESSION.toString())
                .issuedAt(Date.from(t)).expiration(Date.from(t.plusSeconds(60)))
                .signWith(new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"))
                .compact();
        assertTrue(s.lire(hmac).isEmpty());

        String none = Jwts.builder().issuer("ged-api").subject(UTILISATEUR.toString())
                .claim("uid", "x").claim("sid", SESSION.toString())
                .issuedAt(Date.from(t)).expiration(Date.from(t.plusSeconds(60))).compact();
        assertTrue(s.lire(none).isEmpty());
        assertTrue(s.lire("pas-un-jeton").isEmpty());
    }

    @Test
    @DisplayName("Émetteur inattendu, durée de vie supérieure à 15 min, jeton expiré, sid absent : refusés")
    void revendicationsRefusees() {
        ServiceJeton s = service(new ProprietesIdentite(), cle);
        Instant t = Instant.now();

        ProprietesIdentite intrus = new ProprietesIdentite();
        intrus.getJeton().setEmetteur("intrus");
        assertTrue(s.lire(service(intrus, cle).emettre(UTILISATEUR, "x", SESSION)).isEmpty());

        // Bonne clé, bon émetteur, mais trente jours de validité : pas émis par nous.
        String trenteJours = Jwts.builder().issuer("ged-api").subject(UTILISATEUR.toString())
                .claim("uid", "x").claim("sid", SESSION.toString())
                .issuedAt(Date.from(t)).expiration(Date.from(t.plus(Duration.ofDays(30))))
                .signWith(cle.getPrivate(), Jwts.SIG.RS256).compact();
        assertTrue(s.lire(trenteJours).isEmpty());

        String expire = Jwts.builder().issuer("ged-api").subject(UTILISATEUR.toString())
                .claim("uid", "x").claim("sid", SESSION.toString())
                .issuedAt(Date.from(t.minusSeconds(1200))).expiration(Date.from(t.minusSeconds(300)))
                .signWith(cle.getPrivate(), Jwts.SIG.RS256).compact();
        assertTrue(s.lire(expire).isEmpty());

        String sansSession = Jwts.builder().issuer("ged-api").subject(UTILISATEUR.toString()).claim("uid", "x")
                .issuedAt(Date.from(t)).expiration(Date.from(t.plusSeconds(60)))
                .signWith(cle.getPrivate(), Jwts.SIG.RS256).compact();
        assertTrue(s.lire(sansSession).isEmpty());
    }
}
