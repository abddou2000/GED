package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.ProprietesIdentite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liaison à l'annuaire (dossier technique §3.3, tableau des paramètres ;
 * décisions client D2 à D4 ; exigence P-02).
 *
 * <ul>
 *   <li><b>N contrôleurs, un seul suffit</b> (D4) : les URL sont passées à JNDI
 *       dans l'ordre ; en cas d'échec de connexion il essaie la suivante.</li>
 *   <li><b>LDAPS obligatoire</b> hors dev et test : une URL {@code ldap://}
 *       empêche le démarrage (LDAP en clair interdit).</li>
 *   <li><b>Délais</b> : 3 s de connexion, 5 s de lecture, par défaut.</li>
 *   <li><b>Pool</b> des connexions du compte de service (pas des liaisons
 *       utilisateur, qui ne sont jamais réutilisées).</li>
 * </ul>
 */
@Configuration
public class ConfigurationAnnuaire {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationAnnuaire.class);

    static {
        /* Le pool JNDI ne met en commun QUE les connexions en clair si on ne lui
           dit rien : les connexions LDAPS du compte de service seraient rouvertes
           (poignée de main TLS comprise) à chaque recherche. Ces propriétés sont
           lues une seule fois par la JVM, avant la première connexion. */
        System.setProperty("com.sun.jndi.ldap.connect.pool.protocol",
                System.getProperty("com.sun.jndi.ldap.connect.pool.protocol", "plain ssl"));
        System.setProperty("com.sun.jndi.ldap.connect.pool.timeout",
                System.getProperty("com.sun.jndi.ldap.connect.pool.timeout", "300000"));
    }

    @Bean
    public LdapContextSource sourceAnnuaire(ProprietesIdentite proprietes) {
        ProprietesIdentite.Annuaire a = proprietes.getAnnuaire();
        List<String> urls = a.getUrls().stream().map(String::trim).filter(u -> !u.isEmpty()).toList();
        if (urls.isEmpty()) {
            throw new IllegalStateException("Aucun contrôleur de domaine configuré : renseigner "
                    + "ged.identite.annuaire.urls (variable GED_LDAP_URLS).");
        }
        boolean toutLdaps = urls.stream().allMatch(u -> u.toLowerCase().startsWith("ldaps://"));
        if (a.isExigerLdaps() && !toutLdaps) {
            throw new IllegalStateException("LDAP en clair interdit : toutes les URL de l'annuaire doivent "
                    + "être en ldaps:// (reçu : " + urls + ").");
        }
        if (!toutLdaps) {
            log.warn("Annuaire en LDAP non chiffré ({}) : admis en dev et test uniquement.", urls);
        }

        LdapContextSource source = new LdapContextSource();
        source.setUrls(urls.toArray(String[]::new));
        source.setAuthenticationSource(new SecretCompteService(
                a.getCompteService(), a.getMotDePasseService(), a.getMotDePasseServiceFichier()));
        source.setPooled(a.isPool());

        Map<String, Object> env = new HashMap<>();
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(a.getDelaiConnexion().toMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(a.getDelaiLecture().toMillis()));
        // objectGUID est binaire : sans cette déclaration JNDI le rend en texte abîmé.
        env.put("java.naming.ldap.attributes.binary", "objectGUID");
        env.put("java.naming.referral", "ignore");
        if (toutLdaps) {
            FabriqueSocketsLdaps.configurer(contexteTls(a));
            env.put("java.naming.ldap.factory.socket", FabriqueSocketsLdaps.class.getName());
        }
        source.setBaseEnvironmentProperties(env);
        source.afterPropertiesSet();
        log.info("Annuaire : {} contrôleur(s) de domaine déclaré(s), base « {} ».", urls.size(), a.getBase());
        return source;
    }

    @Bean
    public Annuaire annuaire(LdapContextSource sourceAnnuaire, ProprietesIdentite proprietes) {
        ProprietesIdentite.Annuaire a = proprietes.getAnnuaire();
        return new AnnuaireLdap(sourceAnnuaire, a.getBase(), a.getAttributs(), a.getDelaiLecture());
    }

    /**
     * Contexte TLS de l'annuaire : magasin de confiance de MMED s'il est fourni,
     * sinon celui de la JVM. Aucun contournement de la validation n'est possible.
     */
    static SSLContext contexteTls(ProprietesIdentite.Annuaire a) {
        try {
            SSLContext contexte = SSLContext.getInstance("TLS");
            if (a.getTruststore() == null || a.getTruststore().isBlank()) {
                contexte.init(null, null, null);
                return contexte;
            }
            KeyStore magasin = KeyStore.getInstance(a.getTruststoreType());
            try (InputStream in = Files.newInputStream(Path.of(a.getTruststore()))) {
                char[] mdp = a.getTruststoreMotDePasse() == null ? null : a.getTruststoreMotDePasse().toCharArray();
                magasin.load(in, mdp);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(magasin);
            contexte.init(null, tmf.getTrustManagers(), null);
            return contexte;
        } catch (Exception e) {
            throw new IllegalStateException("Magasin de confiance de l'annuaire illisible : " + a.getTruststore(), e);
        }
    }
}
