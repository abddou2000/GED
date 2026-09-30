package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.naming.directory.Attribute;
import javax.naming.directory.SearchControls;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Lecture de {@code userAccountControl} par le compte de service (décision
 * D15), sur les contrôleurs et avec les réglages de {@link AnnuaireLdap} (LDAPS,
 * délais, bascule D4).
 *
 * <p>Limitée à ce contrôle : une recherche en lecture seule par
 * {@code objectGUID}, qui ne demande QUE {@code userAccountControl}. La liste
 * des attributs de {@link AnnuaireLdap} (connexion, fiche) continue d'exclure cet
 * attribut (D1, principe P2).
 */
public class EtatCompteAnnuaireLdap implements EtatCompteAnnuaire {

    /** Seul attribut demandé. */
    static final String ATTRIBUT = "userAccountControl";

    /** Bit ACCOUNTDISABLE de userAccountControl. */
    static final int COMPTE_DESACTIVE = 0x2;

    private final LdapTemplate modele;
    private final String base;
    private final int delaiLectureMs;

    public EtatCompteAnnuaireLdap(LdapContextSource source, String base, Duration delaiLecture) {
        this.modele = new LdapTemplate(source);
        this.modele.setIgnorePartialResultException(true);
        this.base = base == null ? "" : base;
        this.delaiLectureMs = (int) Math.min(Integer.MAX_VALUE, delaiLecture.toMillis());
    }

    /** Attributs demandés à l'annuaire (pour les tests : userAccountControl seul). */
    String[] attributsDemandes() {
        return new String[]{ATTRIBUT};
    }

    @Override
    public Etat etat(UUID objectGuid) {
        SearchControls controles = new SearchControls(SearchControls.SUBTREE_SCOPE, 2, delaiLectureMs,
                attributsDemandes(), false, false);
        String filtre = "(&(objectClass=user)(objectGUID=" + GuidAnnuaire.pourFiltre(objectGuid) + "))";
        try {
            List<Integer> valeurs = modele.search(base, filtre, controles, (AttributesMapper<Integer>) attributs -> {
                Attribute a = attributs.get(ATTRIBUT);
                if (a == null || a.get() == null) return null;
                try {
                    return Integer.valueOf(a.get().toString().trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            });
            if (valeurs.size() != 1) return Etat.INTROUVABLE;
            Integer uac = valeurs.get(0);
            if (uac == null) return Etat.INDETERMINE;
            return (uac & COMPTE_DESACTIVE) != 0 ? Etat.DESACTIVE : Etat.ACTIF;
        } catch (org.springframework.ldap.NamingException e) {
            throw new AnnuaireIndisponibleException(e);
        }
    }
}
