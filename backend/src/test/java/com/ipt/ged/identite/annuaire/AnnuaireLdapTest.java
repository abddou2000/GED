package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.ipt.ged.identite.erreur.IdentifiantsRefusesException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.ipt.ged.support.CertificatsDeTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client d'annuaire contre le simulateur d'Active Directory (UnboundID) :
 * search-then-bind par {@code sAMAccountName} (D2), attributs minimaux (D3),
 * compte désactivé refusé sans lire son état (D1), N contrôleurs dont un seul
 * suffit (D4), délais, LDAPS avec magasin de confiance, secret rechargé à chaud.
 *
 * <p><b>Vérifié par simulateur uniquement</b> : aucun contrôleur de domaine réel
 * n'est disponible sur le poste de développement.
 */
class AnnuaireLdapTest {

    private static final String BASE = "DC=marchicamed,DC=ma";
    private static final String SERVICE = "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma";
    private static final String MDP = "test-only-password";
    /** objectGUID de sbennani dans annuaire-test.ldif, sous sa forme usuelle. */
    private static final UUID GUID_SARA = UUID.fromString("5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e01");

    private static SimulateurAnnuaire principal;

    @BeforeAll
    static void demarrer() throws Exception {
        principal = simulateur();
    }

    @AfterAll
    static void arreter() {
        principal.arreter();
    }

    @AfterEach
    void contexteTlsParDefaut() {
        FabriqueSocketsLdaps.configurer(null);
    }

    private static SimulateurAnnuaire simulateur() throws Exception {
        try (InputStream ldif = ldif()) {
            return SimulateurAnnuaire.demarrer(BASE, 0, ldif);
        }
    }

    private static InputStream ldif() {
        return AnnuaireLdapTest.class.getResourceAsStream("/annuaire/annuaire-test.ldif");
    }

    private static ProprietesIdentite proprietes(String... urls) {
        ProprietesIdentite p = new ProprietesIdentite();
        p.getAnnuaire().setUrls(List.of(urls));
        p.getAnnuaire().setBase(BASE);
        p.getAnnuaire().setCompteService(SERVICE);
        p.getAnnuaire().setMotDePasseService(MDP);
        p.getAnnuaire().setExigerLdaps(false);
        p.getAnnuaire().setPool(false);
        p.getAnnuaire().setDelaiConnexion(Duration.ofSeconds(1));
        p.getAnnuaire().setDelaiLecture(Duration.ofSeconds(2));
        return p;
    }

    private static AnnuaireLdap annuaire(ProprietesIdentite p) {
        ConfigurationAnnuaire config = new ConfigurationAnnuaire();
        return (AnnuaireLdap) config.annuaire(config.controleursAnnuaire(p), p);
    }

    private static AnnuaireLdap annuaire(String... urls) {
        return annuaire(proprietes(urls));
    }

    /** Un port où rien n'écoute : contrôleur de domaine arrêté. */
    private static String urlMorte() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return "ldap://localhost:" + s.getLocalPort();
        }
    }

    @Test
    @DisplayName("Search-then-bind par sAMAccountName : fiche minimale, objectGUID à l'endroit")
    void authentificationReussie() {
        FicheAnnuaire f = annuaire(principal.url()).authentifier("sbennani", MDP);
        assertEquals(GUID_SARA, f.objectGuid());
        assertEquals("sbennani", f.identifiant());
        assertEquals("Sara", f.prenom());
        assertEquals("Bennani", f.nom());
        assertEquals("sara.bennani@marchica.ma", f.courriel());
        assertEquals("Direction Financière", f.direction());
    }

    @Test
    @DisplayName("L'identifiant est insensible à la casse, comme dans AD")
    void casseIndifferente() {
        assertEquals(GUID_SARA, annuaire(principal.url()).authentifier("SBennani", MDP).objectGuid());
    }

    @Test
    @DisplayName("Mot de passe faux, identifiant inconnu, mot de passe vide : même refus")
    void refus() {
        AnnuaireLdap a = annuaire(principal.url());
        assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("sbennani", "faux"));
        assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("inconnu", MDP));
        // Liaison anonyme : un mot de passe vide ne doit jamais passer pour une réussite.
        assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("sbennani", ""));
    }

    @Test
    @DisplayName("D2 : l'e-mail et le userPrincipalName ne sont pas des identifiants de connexion")
    void pasDeConnexionParCourriel() {
        AnnuaireLdap a = annuaire(principal.url());
        assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("sara.bennani@marchica.ma", MDP));
        assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("sbennani@marchicamed.ma", MDP));
    }

    @Test
    @DisplayName("D1 : un compte désactivé dans l'annuaire est refusé par la liaison, sans lecture de son état")
    void compteDesactive() {
        assertThrows(IdentifiantsRefusesException.class, () -> annuaire(principal.url()).authentifier("otazi", MDP));
    }

    @Test
    @DisplayName("D3 / P2 : seuls les attributs minimaux sont demandés, jamais memberOf ni userAccountControl")
    void attributsMinimaux() {
        String[] demandes = AnnuaireLdap.attributsRetenus(List.of("sAMAccountName", "objectGUID", "givenName", "sn",
                "displayName", "mail", "department", "memberOf", "userAccountControl", "userPrincipalName"));
        assertEquals(List.of("sAMAccountName", "objectGUID", "givenName", "sn", "displayName", "mail", "department"),
                Arrays.asList(demandes));
        // Configuration vide : l'indispensable reste demandé.
        assertEquals(List.of("sAMAccountName", "objectGUID"), Arrays.asList(AnnuaireLdap.attributsRetenus(List.of())));
    }

    @Test
    @DisplayName("Relecture par objectGUID et par identifiant (compte de service)")
    void recherches() {
        AnnuaireLdap a = annuaire(principal.url());
        assertEquals("sbennani", a.rechercherParGuid(GUID_SARA).orElseThrow().identifiant());
        assertEquals(Optional.empty(), a.rechercherParGuid(UUID.randomUUID()));
        assertEquals(GUID_SARA, a.rechercherParIdentifiant("sbennani").orElseThrow().objectGuid());
        // Caractères de filtre échappés : pas d'injection LDAP.
        assertEquals(Optional.empty(), a.rechercherParIdentifiant("*"));
        assertEquals(Optional.empty(), a.rechercherParIdentifiant("sbennani)(sAMAccountName=*"));
    }

    @Test
    @DisplayName("Renommage dans l'annuaire : même objectGUID, nouvel identifiant")
    void renommage() throws Exception {
        try (SimulateurAnnuaire s = simulateur()) {
            s.serveur().modify("CN=Karim El Fassi,OU=Utilisateurs," + BASE,
                    new Modification(ModificationType.REPLACE, "sAMAccountName", "kelfassi2"));
            AnnuaireLdap a = annuaire(s.url());
            FicheAnnuaire f = a.authentifier("kelfassi2", MDP);
            assertEquals(UUID.fromString("5c1f0d2e-1a3b-4c5d-8e9f-0a1b2c3d4e02"), f.objectGuid());
            assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("kelfassi", MDP));
        }
    }

    @Test
    @DisplayName("D4 : un seul contrôleur suffit ; le premier arrêté, le second prend le relais")
    void basculeEntreControleurs() throws Exception {
        assertEquals(GUID_SARA, annuaire(principal.url()).authentifier("sbennani", MDP).objectGuid());
        assertEquals(GUID_SARA, annuaire(urlMorte(), principal.url()).authentifier("sbennani", MDP).objectGuid());
    }

    /**
     * ANO-E2-002 : contrôleur bloqué qui accepte la connexion TCP et ne répond
     * jamais. Sans bascule propre à la GED, le délai de lecture expire sur une
     * connexion déjà établie et JNDI n'essaie pas le suivant (avec le pool, la
     * connexion muette est resservie : 503 à chaque essai ; sans pool, chaque
     * connexion paie deux délais de lecture).
     */
    @Test
    @DisplayName("D4 / ANO-E2-002 : premier contrôleur muet, bascule à chaque connexion, puis mis à l'écart")
    void basculeSurControleurMuet() throws Exception {
        try (ControleurMuet muet = new ControleurMuet()) {
            for (boolean pool : new boolean[]{true, false}) {
                ProprietesIdentite p = proprietes(muet.url(), principal.url());
                p.getAnnuaire().setPool(pool);
                p.getAnnuaire().setDelaiLecture(Duration.ofSeconds(1));
                AnnuaireLdap a = annuaire(p);

                long debut = System.nanoTime();
                assertEquals(GUID_SARA, a.authentifier("sbennani", MDP).objectGuid(), "pool=" + pool);
                assertTrue(Duration.ofNanos(System.nanoTime() - debut).toMillis() < 3000,
                        "un seul délai de lecture pour basculer (pool=" + pool + ")");
                for (int i = 0; i < 4; i++) {
                    long t = System.nanoTime();
                    assertEquals(GUID_SARA, a.authentifier("sbennani", MDP).objectGuid(), "essai " + i + ", pool=" + pool);
                    assertTrue(Duration.ofNanos(System.nanoTime() - t).toMillis() < 900,
                            "contrôleur muet mis à l'écart : pas de délai de lecture (essai " + i + ", pool=" + pool + ")");
                }
                assertEquals("sbennani", a.rechercherParGuid(GUID_SARA).orElseThrow().identifiant());
                a.sonder();
                // Un refus reste un refus : il n'est pas transformé en panne.
                assertThrows(IdentifiantsRefusesException.class, () -> a.authentifier("sbennani", "faux"));
            }
            assertTrue(muet.connexionsAcceptees() >= 2, "le contrôleur muet a bien été essayé");
        }
    }

    @Test
    @DisplayName("D4 / ANO-E2-002 : tous les contrôleurs muets, 503 dans la somme des délais de lecture")
    void tousMuets() throws Exception {
        try (ControleurMuet m1 = new ControleurMuet(); ControleurMuet m2 = new ControleurMuet()) {
            ProprietesIdentite p = proprietes(m1.url(), m2.url());
            p.getAnnuaire().setDelaiLecture(Duration.ofSeconds(1));
            AnnuaireLdap a = annuaire(p);
            long debut = System.nanoTime();
            assertThrows(AnnuaireIndisponibleException.class, () -> a.authentifier("sbennani", MDP));
            assertTrue(Duration.ofNanos(System.nanoTime() - debut).toMillis() < 4000, "deux délais de lecture au plus");
            assertTrue(m1.connexionsAcceptees() >= 1 && m2.connexionsAcceptees() >= 1, "les deux essayés");
        }
    }

    /** Contrôleur bloqué : accepte les connexions TCP, ne répond jamais, ne ferme jamais. */
    private static final class ControleurMuet implements AutoCloseable {
        private final ServerSocket serveur = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        private final List<java.net.Socket> retenues = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        ControleurMuet() throws Exception {
            Thread t = new Thread(() -> {
                try {
                    while (!serveur.isClosed()) retenues.add(serveur.accept());
                } catch (Exception fin) {
                    // serveur fermé
                }
            }, "controleur-muet");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "ldap://localhost:" + serveur.getLocalPort();
        }

        int connexionsAcceptees() {
            return retenues.size();
        }

        @Override
        public void close() throws Exception {
            serveur.close();
            synchronized (retenues) {
                for (java.net.Socket s : retenues) s.close();
            }
        }
    }

    @Test
    @DisplayName("Annuaire injoignable : erreur explicite, dans le délai, sans mode dégradé")
    void annuaireIndisponible() throws Exception {
        AnnuaireLdap a = annuaire(urlMorte(), urlMorte());
        long debut = System.nanoTime();
        assertThrows(AnnuaireIndisponibleException.class, () -> a.authentifier("sbennani", MDP));
        assertThrows(AnnuaireIndisponibleException.class, a::sonder);
        assertTrue(Duration.ofNanos(System.nanoTime() - debut).toSeconds() < 10, "délais de connexion respectés");

        // Arrêt en cours de route : la connexion suivante échoue proprement.
        SimulateurAnnuaire s = simulateur();
        AnnuaireLdap b = annuaire(s.url());
        b.sonder();
        s.arreter();
        assertThrows(AnnuaireIndisponibleException.class, () -> b.authentifier("sbennani", MDP));
    }

    @Test
    @DisplayName("LDAP en clair refusé au démarrage quand LDAPS est exigé")
    void ldapEnClairInterdit() {
        ProprietesIdentite p = proprietes(principal.url());
        p.getAnnuaire().setExigerLdaps(true);
        assertThrows(IllegalStateException.class, () -> new ConfigurationAnnuaire().controleursAnnuaire(p));
        ProprietesIdentite vide = proprietes();
        assertThrows(IllegalStateException.class, () -> new ConfigurationAnnuaire().controleursAnnuaire(vide));
    }

    @Test
    @DisplayName("LDAPS : certificat validé contre le magasin de confiance de l'annuaire ; inconnu = refusé")
    void ldaps() throws Exception {
        Path dossier = Files.createTempDirectory("ged-ldaps");
        Path cle = dossier.resolve("controleur.p12");
        Path confiance = dossier.resolve("confiance.p12");
        CertificatsDeTest.autosigne(cle, "serveur", "controleur", 2048);
        try (SimulateurAnnuaire s = SimulateurAnnuaire.demarrerLdaps(BASE, 0, ldif(),
                cle.toString(), "serveur".toCharArray())) {
            magasinDeConfiance(cle.toFile(), "serveur".toCharArray(), confiance);

            ProprietesIdentite p = proprietes(s.url());
            p.getAnnuaire().setExigerLdaps(true);
            p.getAnnuaire().setTruststore(confiance.toString());
            p.getAnnuaire().setTruststoreMotDePasse("confiance");
            assertEquals(GUID_SARA, annuaire(p).authentifier("sbennani", MDP).objectGuid());

            // Sans le certificat dans le magasin : la poignée de main échoue.
            ProprietesIdentite sansConfiance = proprietes(s.url());
            sansConfiance.getAnnuaire().setExigerLdaps(true);
            assertThrows(AnnuaireIndisponibleException.class,
                    () -> annuaire(sansConfiance).authentifier("sbennani", MDP));
        } finally {
            Files.deleteIfExists(confiance);
            Files.deleteIfExists(cle);
            Files.deleteIfExists(dossier);
        }
    }

    @Test
    @DisplayName("Secret du compte de service relu à chaud depuis son fichier")
    void secretRechargeAChaud() throws Exception {
        Path fichier = Files.createTempFile("ged-ldap-secret", ".txt");
        try {
            Files.writeString(fichier, "mauvais-secret");
            ProprietesIdentite p = proprietes(principal.url());
            p.getAnnuaire().setMotDePasseService("");
            p.getAnnuaire().setMotDePasseServiceFichier(fichier.toString());
            AnnuaireLdap a = annuaire(p);
            assertThrows(AnnuaireIndisponibleException.class, a::sonder);

            Files.writeString(fichier, MDP + "\n");
            Files.setLastModifiedTime(fichier, java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() + 5000));
            a.sonder();
            assertFalse(a.rechercherParIdentifiant("yalaoui").isEmpty());
        } finally {
            Files.deleteIfExists(fichier);
        }
    }

    @Test
    @DisplayName("objectGUID : ordre d'octets Microsoft, aller-retour exact")
    void guid() {
        byte[] octets = {0x2e, 0x0d, 0x1f, 0x5c, 0x3b, 0x1a, 0x5d, 0x4c,
                (byte) 0x8e, (byte) 0x9f, 0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x01};
        assertEquals(GUID_SARA, GuidAnnuaire.depuisOctets(octets));
        assertTrue(Arrays.equals(octets, GuidAnnuaire.versOctets(GUID_SARA)));
        assertEquals("\\2e\\0d\\1f\\5c\\3b\\1a\\5d\\4c\\8e\\9f\\0a\\1b\\2c\\3d\\4e\\01",
                GuidAnnuaire.pourFiltre(GUID_SARA));
        assertThrows(IllegalArgumentException.class, () -> GuidAnnuaire.depuisOctets(new byte[3]));
    }

    /** Magasin de confiance ne contenant que le certificat du serveur de test. */
    private static void magasinDeConfiance(File cleServeur, char[] mdp, Path cible) throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(cleServeur)) {
            source.load(in, mdp);
        }
        Certificate certificat = source.getCertificate(source.aliases().nextElement());
        KeyStore confiance = KeyStore.getInstance("PKCS12");
        confiance.load(null, null);
        confiance.setCertificateEntry("controleur", certificat);
        try (FileOutputStream out = new FileOutputStream(cible.toFile())) {
            confiance.store(out, "confiance".toCharArray());
        }
    }
}
