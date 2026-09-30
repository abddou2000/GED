import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * Pilotage de l'annuaire SIMULÉ (UnboundID embarqué du profil dev) pour la recette F-12 :
 * désactive ou réactive un compte comme le ferait un administrateur AD (userAccountControl
 * 514 / 512). Sans effet sur un vrai AD : la preuve est à refaire en UAT.
 */
public final class LdapSimule {

    private LdapSimule() {}

    public static void uac(String sam, int valeur) throws Exception {
        int port = Integer.parseInt(ClientGed.env("GED_LDAP_PORT", "33399"));
        String mdp = ClientGed.env("GED_RECETTE_MOT_DE_PASSE", "dev-local-only");
        try (LDAPConnection c = new LDAPConnection("localhost", port, "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma", mdp)) {
            SearchResult r = c.search("DC=marchicamed,DC=ma", SearchScope.SUB, "(sAMAccountName=" + sam + ")");
            String dn = r.getSearchEntries().get(0).getDN();
            c.modify(dn, new Modification(ModificationType.REPLACE, "userAccountControl", String.valueOf(valeur)));
        }
    }
}
