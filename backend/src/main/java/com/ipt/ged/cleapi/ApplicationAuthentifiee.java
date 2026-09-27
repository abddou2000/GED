package com.ipt.ged.cleapi;

import com.ipt.ged.security.UtilisateurConnecte;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.UUID;

/**
 * Identité d'une application authentifiée par sa clé d'API : un sujet comme
 * un autre pour la décision d'autorisation (DAT §12.2), porteur de
 * l'autorité {@code ROLE_APPLICATION} pour le distinguer d'un utilisateur.
 *
 * <p>{@link #getName()} vaut {@code application:<code>} : c'est ce que le
 * journal technique écrit dans {@code username} (DAT §5.5 « à défaut,
 * l'identifiant de l'application »).
 *
 * <p><b>Délégation</b> (§5.5, {@code X-On-Behalf-Of}) : {@link #avecDelegation}
 * porte l'identité de l'utilisateur délégué. {@link #getPrincipal()} renvoie
 * alors cette identité : l'auteur d'une écriture (déposant) et
 * {@code acteur_utilisateur_id} du journal sont l'utilisateur, l'application
 * restant tracée par {@code acteur_application_id} (double identité). Les
 * droits, eux, restent ceux de la clé : en écriture la portée de la clé, en
 * lecture l'intersection des droits de la clé et de l'utilisateur
 * ({@code SourceHabilitationsApplications}).
 */
public class ApplicationAuthentifiee extends AbstractAuthenticationToken {

    public static final String AUTORITE = "ROLE_APPLICATION";

    private final UUID applicationId;
    private final String code;
    private final UUID cleId;
    private final boolean delegation;
    /** Utilisateur pour le compte duquel l'application agit ; {@code null} sans délégation. */
    private final UtilisateurConnecte deleguee;
    /** Requête de lecture (GET, HEAD) : droits en intersection avec ceux du délégué. */
    private final boolean lecture;

    public ApplicationAuthentifiee(UUID applicationId, String code, UUID cleId, boolean delegation) {
        this(applicationId, code, cleId, delegation, null, false);
    }

    private ApplicationAuthentifiee(UUID applicationId, String code, UUID cleId, boolean delegation,
                                    UtilisateurConnecte deleguee, boolean lecture) {
        super(AuthorityUtils.createAuthorityList(AUTORITE));
        this.applicationId = applicationId;
        this.code = code;
        this.cleId = cleId;
        this.delegation = delegation;
        this.deleguee = deleguee;
        this.lecture = lecture;
        setAuthenticated(true);
    }

    /** La même application, agissant pour le compte de l'utilisateur donné. */
    public ApplicationAuthentifiee avecDelegation(UtilisateurConnecte utilisateur, boolean requeteDeLecture) {
        ApplicationAuthentifiee a = new ApplicationAuthentifiee(applicationId, code, cleId, delegation,
                utilisateur, requeteDeLecture);
        a.setDetails(getDetails());
        return a;
    }

    /** Utilisateur délégué, ou {@code null}. */
    public UtilisateurConnecte deleguee() {
        return deleguee;
    }

    /** Requête de lecture menée pour le compte d'un utilisateur. */
    public boolean lecture() {
        return lecture;
    }

    public UUID applicationId() {
        return applicationId;
    }

    public String code() {
        return code;
    }

    public UUID cleId() {
        return cleId;
    }

    /** La clé porte l'attribut « délégation » (§5.5). */
    public boolean delegation() {
        return delegation;
    }

    @Override
    public Object getCredentials() {
        // Le secret n'est jamais conservé en mémoire au-delà de sa vérification.
        return null;
    }

    @Override
    public Object getPrincipal() {
        return deleguee != null ? deleguee : this;
    }

    @Override
    public String getName() {
        return "application:" + code;
    }
}
