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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sonde {@code annuaire} : les contrôleurs de domaine de MMED répondent
 * (DAT 6.7).
 *
 * <p><b>Un ou plusieurs contrôleurs</b> (revue technique, décision D4) : MMED
 * n'en a qu'un aujourd'hui, un second est prévu. Chaque URL de la liste est
 * interrogée séparément :
 * <ul>
 *   <li>tous répondent : UP ;</li>
 *   <li>au moins un répond : UP — l'authentification bascule sur les
 *       contrôleurs restants —, avec la liste des contrôleurs tombés dans le
 *       détail et la métrique {@code ged_annuaire_controleur{controleur}} à 0,
 *       qui déclenche une alerte d'avertissement ;</li>
 *   <li>aucun ne répond : DOWN, plus personne ne peut se connecter.</li>
 * </ul>
 * Avec un seul contrôleur, le cas intermédiaire n'existe pas : UP ou DOWN.
 *
 * <p>Lecture anonyme du RootDSE ({@code namingContexts}), que les contrôleurs
 * AD autorisent sans compte : aucun secret manipulé, aucun bind du compte de
 * service consommé. JNDI plutôt que Spring LDAP : aucune dépendance ajoutée
 * avant l'authentification par annuaire (E2). La liste reprend par défaut
 * {@code spring.ldap.urls}, celle que l'authentification utilisera.
 */
@Component("annuaireHealthIndicator")
public class SondeAnnuaire implements HealthIndicator {

    private final boolean actif;
    private final List<String> urls;
    private final int delaiMs;
    /** Dernier état connu de chaque contrôleur, lu par la métrique. */
    private final Map<String, Boolean> etats = new ConcurrentHashMap<>();

    public SondeAnnuaire(@Value("${ged.supervision.annuaire.actif:false}") boolean actif,
                         @Value("${ged.supervision.annuaire.urls:${spring.ldap.urls:ldaps://localhost:636}}") List<String> urls,
                         @Value("${ged.supervision.annuaire.delai-ms:3000}") int delaiMs) {
        this.actif = actif;
        this.urls = urls.stream().map(String::trim).filter(u -> !u.isEmpty()).toList();
        this.delaiMs = delaiMs;
    }

    boolean actif() {
        return actif;
    }

    List<String> urls() {
        return urls;
    }

    /** 1 si le contrôleur a répondu à la dernière interrogation, 0 sinon, NaN si jamais interrogé. */
    double etatControleur(String url) {
        Boolean etat = etats.get(url);
        return etat == null ? Double.NaN : (etat ? 1.0 : 0.0);
    }

    @Override
    public Health health() {
        if (!actif) {
            return Health.up().withDetail("supervision", "désactivée (ged.supervision.annuaire.actif)").build();
        }
        if (urls.isEmpty()) {
            return Health.down().withDetail("anomalie", "aucun contrôleur configuré").build();
        }
        Map<String, Object> controleurs = new LinkedHashMap<>();
        int disponibles = 0;
        for (String url : urls) {
            String anomalie = interroger(url);
            etats.put(url, anomalie == null);
            controleurs.put(url, anomalie == null ? "UP" : "DOWN (" + anomalie + ")");
            if (anomalie == null) disponibles++;
        }
        var sante = disponibles > 0 ? Health.up() : Health.down();
        sante.withDetail("controleurs", controleurs)
                .withDetail("disponibles", disponibles + "/" + urls.size());
        if (disponibles > 0 && disponibles < urls.size()) {
            sante.withDetail("anomalie", "fonctionnement dégradé : au moins un contrôleur ne répond pas");
        }
        return sante.build();
    }

    /** @return {@code null} si le contrôleur répond, sinon la nature de l'échec. */
    private String interroger(String url) {
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
            return nc != null && nc.size() > 0 ? null : "RootDSE sans namingContexts";
        } catch (NamingException e) {
            return e.getClass().getSimpleName();
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
