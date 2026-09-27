package com.ipt.ged.identite.annuaire;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;

import java.io.InputStream;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulateur d'Active Directory fondé sur le serveur LDAP embarqué UnboundID.
 * Sert aux tests et au compte de démonstration du profil dev — jamais ailleurs
 * (voir {@link AnnuaireEmbarque}).
 *
 * <p>Ce qu'il reproduit d'AD, et que la GED doit supporter :
 * <ul>
 *   <li>attributs {@code sAMAccountName}, {@code objectGUID} binaire,
 *       {@code givenName}, {@code sn}, {@code displayName}, {@code mail},
 *       {@code department} (schéma non vérifié, comme un AD étendu) ;</li>
 *   <li><b>compte désactivé</b> : une entrée dont {@code userAccountControl}
 *       porte le bit ACCOUNTDISABLE (2) voit sa liaison refusée avec le code 49
 *       et le sous-code AD 533, exactement comme un contrôleur de domaine. La
 *       GED ne lit jamais cet attribut (décision D1) : elle constate le refus.</li>
 * </ul>
 */
public final class SimulateurAnnuaire implements AutoCloseable {

    /** Bit ACCOUNTDISABLE de userAccountControl. */
    private static final int COMPTE_DESACTIVE = 0x2;

    private final InMemoryDirectoryServer serveur;
    private final String protocole;

    private SimulateurAnnuaire(InMemoryDirectoryServer serveur, String protocole) {
        this.serveur = serveur;
        this.protocole = protocole;
    }

    /** Serveur LDAP en clair sur {@code port} (0 = port libre tiré au sort). */
    public static SimulateurAnnuaire demarrer(String baseDn, int port, InputStream ldif) throws Exception {
        return demarrer(baseDn, InMemoryListenerConfig.createLDAPConfig(
                "ldap", InetAddress.getLoopbackAddress(), port, null), "ldap", ldif);
    }

    /**
     * Serveur LDAPS : le certificat vient du magasin {@code keystore} (PKCS#12).
     * Seul le côté serveur est configuré ; le client (la GED) valide avec son
     * propre magasin de confiance.
     */
    public static SimulateurAnnuaire demarrerLdaps(String baseDn, int port, InputStream ldif,
                                                   String keystore, char[] motDePasse) throws Exception {
        SSLUtil ssl = new SSLUtil(new KeyStoreKeyManager(keystore, motDePasse, "PKCS12", null),
                new TrustAllTrustManager());
        return demarrer(baseDn, InMemoryListenerConfig.createLDAPSConfig(
                "ldaps", InetAddress.getLoopbackAddress(), port, ssl.createSSLServerSocketFactory(), null),
                "ldaps", ldif);
    }

    private static SimulateurAnnuaire demarrer(String baseDn, InMemoryListenerConfig ecoute, String protocole,
                                               InputStream ldif) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(baseDn);
        config.setSchema(null);                 // attributs AD hors schéma standard
        config.setListenerConfigs(ecoute);
        AtomicReference<InMemoryDirectoryServer> ref = new AtomicReference<>();
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest requete) throws LDAPException {
                InMemoryDirectoryServer s = ref.get();
                String dn = requete.getRequest().getBindDN();
                if (s == null || dn == null || dn.isEmpty()) return;
                Entry entree = s.getEntry(dn);
                if (entree != null && entree.hasAttribute("userAccountControl")) {
                    Integer uac = entree.getAttributeValueAsInteger("userAccountControl");
                    if (uac != null && (uac & COMPTE_DESACTIVE) != 0) {
                        throw new LDAPException(ResultCode.INVALID_CREDENTIALS,
                                "80090308: LdapErr: DSID-0C09044E, comment: AcceptSecurityContext error, data 533, v4563");
                    }
                }
            }
        });
        InMemoryDirectoryServer serveur = new InMemoryDirectoryServer(config);
        ref.set(serveur);
        try (LDIFReader lecteur = new LDIFReader(ldif)) {
            serveur.importFromLDIF(true, lecteur);
        }
        serveur.startListening();
        return new SimulateurAnnuaire(serveur, protocole);
    }

    public int port() {
        return serveur.getListenPort();
    }

    /** URL à donner à la GED, par exemple {@code ldap://localhost:33389}. */
    public String url() {
        return protocole + "://localhost:" + port();
    }

    /** Accès direct, pour que les tests modifient l'annuaire (désactivation, renommage…). */
    public InMemoryDirectoryServer serveur() {
        return serveur;
    }

    public void arreter() {
        serveur.shutDown(true);
    }

    @Override
    public void close() {
        arreter();
    }
}
