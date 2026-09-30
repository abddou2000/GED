package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import com.ipt.ged.identite.erreur.IdentifiantsRefusesException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;

import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Annuaire Active Directory par Spring Security LDAP (dossier technique §3.3).
 *
 * <p><b>Identifiant de connexion</b> : {@code sAMAccountName} et lui seul
 * (décision client D2). Le filtre est fixé dans le code, pas dans la
 * configuration : on ne peut pas réintroduire l'e-mail ou le
 * {@code userPrincipalName} par un réglage.
 *
 * <p><b>Attributs</b> : la liste configurée (strict minimum, D3) est expurgée de
 * tout attribut d'appartenance ou d'état ({@link #INTERDITS}) — principe P2,
 * décision D1 — et complétée des deux attributs indispensables.
 */
public class AnnuaireLdap implements Annuaire {

    private static final Logger log = LoggerFactory.getLogger(AnnuaireLdap.class);

    /** Seul identifiant de connexion admis (D2). {0} est échappé par Spring Security. */
    static final String FILTRE_IDENTIFIANT = "(&(objectClass=user)(sAMAccountName={0}))";

    /** Jamais demandés à l'annuaire, quelle que soit la configuration. */
    static final Set<String> INTERDITS = Set.of(
            "memberof", "tokengroups", "primarygroupid", "useraccountcontrol",
            "msds-user-account-control-computed", "userprincipalname", "userpassword", "unicodepwd");

    private final ControleursAnnuaire controleurs;
    private final String base;
    private final String[] attributs;
    private final int delaiLectureMs;
    /** Par contrôleur (indice = rang) : liaison utilisateur et recherches du compte de service. */
    private final BindAuthenticator[] authentificateurs;
    private final LdapTemplate[] modeles;

    public AnnuaireLdap(ControleursAnnuaire controleurs, String base, List<String> attributsConfigures,
                        Duration delaiLecture) {
        this.controleurs = controleurs;
        this.base = base == null ? "" : base;
        this.attributs = attributsRetenus(attributsConfigures);
        this.delaiLectureMs = (int) Math.min(Integer.MAX_VALUE, delaiLecture.toMillis());

        int n = controleurs.controleurs().size();
        this.authentificateurs = new BindAuthenticator[n];
        this.modeles = new LdapTemplate[n];
        for (ControleursAnnuaire.Controleur c : controleurs.controleurs()) {
            LdapContextSource source = c.source();
            FilterBasedLdapUserSearch recherche = new FilterBasedLdapUserSearch(this.base, FILTRE_IDENTIFIANT, source);
            recherche.setSearchSubtree(true);
            recherche.setReturningAttributes(attributs);
            recherche.setSearchTimeLimit(delaiLectureMs);
            recherche.setDerefLinkFlag(false);

            BindAuthenticator authentificateur = new BindAuthenticator(source);
            authentificateur.setUserSearch(recherche);
            authentificateur.setUserAttributes(attributs);
            authentificateurs[c.rang()] = authentificateur;

            LdapTemplate modele = new LdapTemplate(source);
            modele.setIgnorePartialResultException(true);
            modeles[c.rang()] = modele;
        }
    }

    /** Liste demandée au serveur : configuration moins les interdits, plus l'indispensable. */
    static String[] attributsRetenus(List<String> configures) {
        Set<String> retenus = new LinkedHashSet<>(List.of("sAMAccountName", "objectGUID"));
        if (configures != null) {
            for (String a : configures) {
                if (a == null || a.isBlank()) continue;
                if (INTERDITS.contains(a.trim().toLowerCase(Locale.ROOT))) {
                    log.warn("Attribut d'annuaire « {} » ignoré : jamais lu par la GED (principe P2, décision D1).", a);
                    continue;
                }
                retenus.add(a.trim());
            }
        }
        return retenus.toArray(String[]::new);
    }

    String[] attributs() {
        return attributs.clone();
    }

    @Override
    public FicheAnnuaire authentifier(String identifiant, String motDePasse) {
        // Recherche et liaison sur le MÊME contrôleur ; un refus n'est jamais rejoué ailleurs.
        try {
            return controleurs.executer(c -> {
                try {
                    DirContextOperations entree = authentificateurs[c.rang()].authenticate(
                            new UsernamePasswordAuthenticationToken(identifiant, motDePasse));
                    return fiche(entree);
                } catch (BadCredentialsException | UsernameNotFoundException e) {
                    // Mot de passe faux, identifiant inconnu, compte désactivé : l'annuaire
                    // refuse la liaison, la GED ne distingue pas (D1).
                    throw new IdentifiantsRefusesException();
                } catch (InternalAuthenticationServiceException | org.springframework.ldap.NamingException e) {
                    throw new AnnuaireIndisponibleException(e);
                }
            });
        } catch (AnnuaireIndisponibleException e) {
            log.warn("Annuaire injoignable pendant une connexion : {}", e.getCause() == null ? e.getMessage()
                    : e.getCause().getMessage());
            throw e;
        }
    }

    @Override
    public Optional<FicheAnnuaire> rechercherParGuid(UUID objectGuid) {
        return rechercher("(&(objectClass=user)(objectGUID=" + GuidAnnuaire.pourFiltre(objectGuid) + "))");
    }

    @Override
    public Optional<FicheAnnuaire> rechercherParIdentifiant(String identifiant) {
        return rechercher("(&(objectClass=user)(sAMAccountName=" + LdapEncoder.filterEncode(identifiant) + "))");
    }

    private Optional<FicheAnnuaire> rechercher(String filtre) {
        SearchControls controles = new SearchControls(SearchControls.SUBTREE_SCOPE, 2, delaiLectureMs,
                attributs, false, false);
        return controleurs.executer(c -> {
            try {
                List<FicheAnnuaire> trouves = modeles[c.rang()].search(base, filtre, controles,
                        (ContextMapper<FicheAnnuaire>) ctx -> fiche((DirContextOperations) ctx));
                return trouves.size() == 1 ? Optional.of(trouves.get(0)) : Optional.empty();
            } catch (org.springframework.ldap.NamingException e) {
                throw new AnnuaireIndisponibleException(e);
            }
        });
    }

    @Override
    public void sonder() {
        controleurs.executer(c -> {
            try {
                DirContext ctx = c.source().getReadOnlyContext();
                ctx.close();
                return null;
            } catch (org.springframework.ldap.NamingException | javax.naming.NamingException e) {
                throw new AnnuaireIndisponibleException(e);
            }
        });
    }

    private static FicheAnnuaire fiche(DirContextOperations e) {
        Object guid = e.getObjectAttribute("objectGUID");
        if (!(guid instanceof byte[] octets)) {
            throw new IllegalStateException("objectGUID absent ou non binaire pour " + e.getDn()
                    + " : vérifier java.naming.ldap.attributes.binary");
        }
        return new FicheAnnuaire(
                GuidAnnuaire.depuisOctets(octets),
                e.getStringAttribute("sAMAccountName"),
                e.getStringAttribute("givenName"),
                e.getStringAttribute("sn"),
                e.getStringAttribute("displayName"),
                e.getStringAttribute("mail"),
                e.getStringAttribute("department"));
    }
}
