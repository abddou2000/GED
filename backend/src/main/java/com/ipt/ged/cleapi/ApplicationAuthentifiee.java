package com.ipt.ged.cleapi;

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
 */
public class ApplicationAuthentifiee extends AbstractAuthenticationToken {

    public static final String AUTORITE = "ROLE_APPLICATION";

    private final UUID applicationId;
    private final String code;
    private final UUID cleId;
    private final boolean delegation;

    public ApplicationAuthentifiee(UUID applicationId, String code, UUID cleId, boolean delegation) {
        super(AuthorityUtils.createAuthorityList(AUTORITE));
        this.applicationId = applicationId;
        this.code = code;
        this.cleId = cleId;
        this.delegation = delegation;
        setAuthenticated(true);
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
        return this;
    }

    @Override
    public String getName() {
        return "application:" + code;
    }
}
