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

    /**
     * Ajoute à l'annuaire simulé un compte actif jamais vu de la GED (F-11 rejouable : qa2neuf1
     * est connu depuis la première exécution). Le compte n'existe que jusqu'au redémarrage de
     * l'instance (annuaire en mémoire) ; même mot de passe que les personas.
     */
    public static void ajouter(String sam, String prenom, String nom) throws Exception {
        int port = Integer.parseInt(ClientGed.env("GED_LDAP_PORT", "33399"));
        String mdp = ClientGed.env("GED_RECETTE_MOT_DE_PASSE", "dev-local-only");
        String cn = prenom + " " + nom;
        try (LDAPConnection c = new LDAPConnection("localhost", port, "CN=svc-ged-ldap,OU=Services,DC=marchicamed,DC=ma", mdp)) {
            c.add(new com.unboundid.ldap.sdk.Entry("CN=" + cn + ",OU=Utilisateurs,DC=marchicamed,DC=ma",
                    new com.unboundid.ldap.sdk.Attribute("objectClass", "top", "person", "organizationalPerson", "user"),
                    new com.unboundid.ldap.sdk.Attribute("cn", cn),
                    new com.unboundid.ldap.sdk.Attribute("sAMAccountName", sam),
                    new com.unboundid.ldap.sdk.Attribute("userPrincipalName", sam + "@marchicamed.ma"),
                    new com.unboundid.ldap.sdk.Attribute("objectGUID", guid()),
                    new com.unboundid.ldap.sdk.Attribute("givenName", prenom),
                    new com.unboundid.ldap.sdk.Attribute("sn", nom),
                    new com.unboundid.ldap.sdk.Attribute("displayName", cn),
                    new com.unboundid.ldap.sdk.Attribute("mail", sam + "@marchica.ma"),
                    new com.unboundid.ldap.sdk.Attribute("department", "Direction Technique"),
                    new com.unboundid.ldap.sdk.Attribute("userAccountControl", "512"),
                    new com.unboundid.ldap.sdk.Attribute("userPassword", mdp)));
        }
    }

    /** objectGUID de 16 octets, comme dans l'AD. */
    private static byte[] guid() {
        java.util.UUID u = java.util.UUID.randomUUID();
        return java.nio.ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
    }
}
