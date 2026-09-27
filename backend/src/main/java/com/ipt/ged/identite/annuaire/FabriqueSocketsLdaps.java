package com.ipt.ged.identite.annuaire;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Fabrique de sockets TLS pour les connexions LDAPS (dossier technique §3.3,
 * §6.2.1) : le certificat des contrôleurs de domaine est validé contre le
 * magasin de confiance de MMED configuré pour l'annuaire — et lui seul —, et
 * seules TLS 1.2 et 1.3 sont proposées.
 *
 * <p>JNDI instancie la fabrique par son nom de classe et appelle
 * {@link #getDefault()} : la configuration est donc portée par un état statique,
 * posé une fois au démarrage par {@code ConfigurationAnnuaire}. Le magasin de
 * confiance reste propre à l'annuaire au lieu de remplacer celui de toute la JVM
 * ({@code javax.net.ssl.trustStore}).
 *
 * <p>La vérification du nom d'hôte reste celle de JNDI (identification du point
 * de terminaison « LDAPS », active par défaut) : elle s'applique aux sockets
 * produites ici.
 *
 * <p>Implémente {@link Comparator} : c'est la condition pour que le pool de
 * connexions JNDI accepte de mettre en commun des connexions ouvertes avec une
 * fabrique personnalisée.
 */
public class FabriqueSocketsLdaps extends SSLSocketFactory implements Comparator<Object> {

    static final String[] PROTOCOLES = {"TLSv1.3", "TLSv1.2"};

    private static volatile SSLContext contexte;

    private final SSLSocketFactory delegue;

    public FabriqueSocketsLdaps() {
        this.delegue = contexteCourant().getSocketFactory();
    }

    /** Point d'entrée imposé par JNDI. */
    public static SocketFactory getDefault() {
        return new FabriqueSocketsLdaps();
    }

    /** Contexte TLS à utiliser ; {@code null} = magasin de confiance par défaut de la JVM. */
    public static void configurer(SSLContext nouveau) {
        contexte = nouveau;
    }

    private static SSLContext contexteCourant() {
        SSLContext c = contexte;
        if (c != null) return c;
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("TLS indisponible dans cette JVM", e);
        }
    }

    private Socket restreindre(Socket s) {
        if (s instanceof SSLSocket ssl) {
            String[] retenus = Arrays.stream(PROTOCOLES)
                    .filter(p -> Arrays.asList(ssl.getSupportedProtocols()).contains(p))
                    .toArray(String[]::new);
            ssl.setEnabledProtocols(retenus);
        }
        return s;
    }

    @Override
    public Socket createSocket() throws IOException {
        return restreindre(delegue.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, String hote, int port, boolean fermer) throws IOException {
        return restreindre(delegue.createSocket(s, hote, port, fermer));
    }

    @Override
    public Socket createSocket(String hote, int port) throws IOException {
        return restreindre(delegue.createSocket(hote, port));
    }

    @Override
    public Socket createSocket(String hote, int port, InetAddress local, int portLocal) throws IOException {
        return restreindre(delegue.createSocket(hote, port, local, portLocal));
    }

    @Override
    public Socket createSocket(InetAddress hote, int port) throws IOException {
        return restreindre(delegue.createSocket(hote, port));
    }

    @Override
    public Socket createSocket(InetAddress hote, int port, InetAddress local, int portLocal) throws IOException {
        return restreindre(delegue.createSocket(hote, port, local, portLocal));
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegue.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegue.getSupportedCipherSuites();
    }

    /** Toutes les instances sont équivalentes pour le pool JNDI. */
    @Override
    public int compare(Object a, Object b) {
        return a.getClass().getName().compareTo(b.getClass().getName());
    }
}
