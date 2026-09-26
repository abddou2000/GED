package com.ipt.ged.supervision;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * Sonde {@code annuaire} : l'annuaire LDAP/AD de MMED répond (DAT 6.7).
 *
 * <p>Lecture anonyme du RootDSE ({@code namingContexts}), que les contrôleurs
 * de domaine AD autorisent sans compte : la sonde ne manipule donc aucun
 * secret et ne consomme pas de bind du compte de service. Elle vérifie la
 * chaîne réseau, TLS (URL {@code ldaps://}, DAT 6.2.1) et le service LDAP.
 *
 * <p>JNDI plutôt que Spring LDAP : aucune dépendance supplémentaire tant que
 * l'authentification par annuaire (E2) n'est pas livrée. Désactivée par
 * défaut pour la même raison ({@code ged.supervision.annuaire.actif}).
 */
@Component("annuaireHealthIndicator")
public class SondeAnnuaire implements HealthIndicator {

    private final boolean actif;
    private final String url;
    private final int delaiMs;

    public SondeAnnuaire(@Value("${ged.supervision.annuaire.actif:false}") boolean actif,
                         @Value("${ged.supervision.annuaire.url:ldaps://localhost:636}") String url,
                         @Value("${ged.supervision.annuaire.delai-ms:3000}") int delaiMs) {
        this.actif = actif;
        this.url = url;
        this.delaiMs = delaiMs;
    }

    @Override
    public Health health() {
        if (!actif) {
            return Health.up().withDetail("supervision", "désactivée (ged.supervision.annuaire.actif)").build();
        }
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "none");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(delaiMs));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(delaiMs));
        DirContext contexte = null;
        try {
            contexte = new InitialDirContext(env);
            Attributes attributs = contexte.getAttributes("", new String[]{"namingContexts"});
            Attribute nc = attributs.get("namingContexts");
            return Health.up().withDetail("url", url)
                    .withDetail("namingContexts", nc != null ? nc.size() : 0).build();
        } catch (NamingException e) {
            return Health.down().withDetail("url", url)
                    .withDetail("anomalie", e.getClass().getSimpleName()).build();
        } finally {
            if (contexte != null) {
                try {
                    contexte.close();
                } catch (NamingException ignoree) {
                    // Connexion déjà perdue : rien à libérer.
                }
            }
        }
    }
}
