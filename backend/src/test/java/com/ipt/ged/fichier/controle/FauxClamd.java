package com.ipt.ged.fichier.controle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Faux démon clamd pour les tests : implémente le protocole {@code INSTREAM}
 * et {@code PING} sur un port local éphémère.
 *
 * <p>Ce n'est pas ClamAV : il reconnaît seulement la chaîne de test EICAR.
 * Il vérifie en revanche le protocole réel (commande, blocs préfixés de leur
 * longueur, bloc final nul), ce qui est l'objet du test du client.
 */
public class FauxClamd implements AutoCloseable {

    /** Comportements simulés. */
    public enum Mode { NORMAL, MUET, REPONSE_INVALIDE, LIMITE_DEPASSEE, FERMETURE_IMMEDIATE }

    /**
     * Chaîne EICAR reconstituée à l'exécution (stockée à l'envers) : elle
     * n'apparaît jamais d'un seul tenant dans un fichier source ou compilé, que
     * l'antivirus du poste de développement mettrait en quarantaine.
     */
    public static String eicar() {
        return new StringBuilder("*H+H$!ELIF-TSET-SURIVITNA-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X")
                .reverse().toString();
    }

    private final ServerSocket serveur;
    private final ExecutorService executeur = Executors.newCachedThreadPool();
    private volatile Mode mode = Mode.NORMAL;
    public final AtomicInteger connexions = new AtomicInteger();
    public final List<Integer> taillesBlocs = new CopyOnWriteArrayList<>();
    public volatile byte[] dernierContenu;

    public FauxClamd() throws IOException {
        serveur = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        executeur.submit(this::accepter);
    }

    public int port() {
        return serveur.getLocalPort();
    }

    public FauxClamd mode(Mode m) {
        this.mode = m;
        return this;
    }

    private void accepter() {
        while (!serveur.isClosed()) {
            try {
                Socket s = serveur.accept();
                connexions.incrementAndGet();
                executeur.submit(() -> servir(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void servir(Socket s) {
        try (s) {
            if (mode == Mode.FERMETURE_IMMEDIATE) return;
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String commande = lireCommande(in);
            if ("zPING".equals(commande)) {
                out.write("PONG\0".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            if (!"zINSTREAM".equals(commande)) {
                out.write("UNKNOWN COMMAND\0".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            ByteArrayOutputStream contenu = new ByteArrayOutputStream();
            int n;
            while ((n = in.readInt()) > 0) {
                taillesBlocs.add(n);
                contenu.write(in.readNBytes(n));
            }
            dernierContenu = contenu.toByteArray();
            switch (mode) {
                case MUET -> Thread.sleep(10_000);
                case REPONSE_INVALIDE -> out.write("n'importe quoi\0".getBytes(StandardCharsets.US_ASCII));
                case LIMITE_DEPASSEE -> out.write("INSTREAM size limit exceeded. ERROR\0".getBytes(StandardCharsets.US_ASCII));
                default -> {
                    String texte = contenu.toString(StandardCharsets.ISO_8859_1);
                    String reponse = texte.contains(eicar()) ? "stream: Eicar-Test-Signature FOUND" : "stream: OK";
                    out.write((reponse + "\0").getBytes(StandardCharsets.US_ASCII));
                }
            }
            out.flush();
        } catch (IOException | InterruptedException ignore) {
            // connexion coupée par le client (délai de lecture) : normal en mode MUET
        }
    }

    private static String lireCommande(DataInputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) > 0) b.write(c);
        return b.toString(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() throws IOException {
        serveur.close();
        executeur.shutdownNow();
    }
}
