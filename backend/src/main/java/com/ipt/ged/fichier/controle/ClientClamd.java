package com.ipt.ged.fichier.controle;

import com.ipt.ged.fichier.Refus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client du démon ClamAV ({@code clamd}) par le protocole {@code INSTREAM} en
 * TCP.
 *
 * <p>Protocole : commande {@code zINSTREAM\0}, puis le fichier en blocs
 * préfixés de leur longueur (entier non signé de 4 octets, ordre réseau),
 * terminé par un bloc de longueur nulle ; clamd répond {@code stream: OK} ou
 * {@code stream: <signature> FOUND}, terminé par {@code \0}.
 *
 * <p>Le fichier est lu en flux, bloc par bloc : un document de 200 Mo n'est
 * jamais en mémoire. Côté serveur, {@code StreamMaxLength} doit être au moins
 * égal au plafond de plateforme (200 Mo) : au-delà, clamd coupe l'analyse, ce
 * qui est traité ici comme une indisponibilité, donc un refus.
 */
public class ClientClamd implements AnalyseurAntivirus {

    private static final Logger log = LoggerFactory.getLogger(ClientClamd.class);
    private static final int REPONSE_MAX = 4096;

    private final String hote;
    private final int port;
    private final int delaiConnexionMs;
    private final int delaiLectureMs;
    private final int tailleBloc;

    public ClientClamd(String hote, int port, int delaiConnexionMs, int delaiLectureMs, int tailleBloc) {
        if (tailleBloc <= 0) throw new IllegalArgumentException("Taille de bloc invalide : " + tailleBloc);
        this.hote = hote;
        this.port = port;
        this.delaiConnexionMs = delaiConnexionMs;
        this.delaiLectureMs = delaiLectureMs;
        this.tailleBloc = tailleBloc;
    }

    @Override
    public String analyser(SourceFichier source) {
        String reponse;
        try (Socket socket = connecter();
             InputStream fichier = source.ouvrir()) {
            DataOutputStream sortie = new DataOutputStream(socket.getOutputStream());
            sortie.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] bloc = new byte[tailleBloc];
            try {
                int n;
                while ((n = fichier.readNBytes(bloc, 0, bloc.length)) > 0) {
                    sortie.writeInt(n);
                    sortie.write(bloc, 0, n);
                }
                sortie.writeInt(0);
                sortie.flush();
            } catch (IOException ecriture) {
                // clamd coupe la connexion quand StreamMaxLength est dépassé :
                // sa réponse d'erreur peut encore être lisible.
                log.debug("Envoi vers clamd interrompu", ecriture);
            }
            reponse = lireReponse(socket.getInputStream());
        } catch (IOException e) {
            log.error("ClamAV injoignable ({}:{}) : dépôt refusé (échec fermé)", hote, port, e);
            throw Refus.antivirusIndisponible(e);
        }
        return interpreter(reponse, source.nomOrigine());
    }

    @Override
    public boolean disponible() {
        try (Socket socket = connecter()) {
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return "PONG".equals(lireReponse(socket.getInputStream()));
        } catch (IOException e) {
            return false;
        }
    }

    private Socket connecter() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(hote, port), delaiConnexionMs);
            socket.setSoTimeout(delaiLectureMs);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static String lireReponse(InputStream entree) throws IOException {
        ByteArrayOutputStream tampon = new ByteArrayOutputStream();
        int b;
        while ((b = entree.read()) > 0 && tampon.size() < REPONSE_MAX) {
            tampon.write(b);
        }
        return tampon.toString(StandardCharsets.US_ASCII).trim();
    }

    /** Toute réponse autre que {@code OK} ou {@code FOUND} est un refus. */
    private String interpreter(String reponse, String nom) {
        if (reponse.endsWith(" OK") || reponse.equals("OK")) {
            return reponse;
        }
        if (reponse.endsWith(" FOUND")) {
            String signature = reponse.substring(reponse.indexOf(':') + 1, reponse.length() - " FOUND".length()).trim();
            log.warn("Fichier infecté refusé : « {} », signature {}", nom, signature);
            throw Refus.infecte(signature);
        }
        log.error("Réponse clamd inattendue « {} » : dépôt refusé (échec fermé)", reponse);
        throw Refus.antivirusIndisponible(new IOException("Réponse clamd inattendue : " + reponse));
    }
}
