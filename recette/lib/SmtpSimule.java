import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;

/**
 * Relais SMTP SIMULÉ (GreenMail, dépendance de test du backend) pour recetter les
 * notifications par e-mail sur un poste sans relais : chaque message reçu est écrit dans
 * DOSSIER/<n>.eml (destinataires, sujet, corps) pour que la recette le lise.
 *
 * <p>Toute preuve obtenue avec lui est à refaire en UAT avec le relais SMTP de MMED.
 * Usage : java -cp <classpath de test du backend> SmtpSimule.java PORT DOSSIER
 */
public class SmtpSimule {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path dossier = Path.of(args[1]);
        Files.createDirectories(dossier);
        GreenMail smtp = new GreenMail(new ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        System.out.println("SMTP simulé à l'écoute sur 127.0.0.1:" + port + ", messages dans " + dossier);
        // GreenMail regroupe les messages reçus par boîte : leur rang dans getReceivedMessages() change
        // dès qu'un autre destinataire reçoit un message. Chaque message est donc identifié par son
        // Message-ID et ses destinataires, jamais par son rang (sinon doublons et pertes).
        Set<String> ecrits = new HashSet<>();
        int n = 0;
        while (true) {
            MimeMessage[] recus = smtp.getReceivedMessages();
            for (int i = 0; i < recus.length; i++) {
                if (ecrits.add(recus[i].getMessageID() + "|" + java.util.Arrays.toString(recus[i].getAllRecipients()))) {
                    StringBuilder s = new StringBuilder();
                    for (Address a : recus[i].getAllRecipients()) s.append("A: ").append(a).append('\n');
                    s.append("De: ").append(recus[i].getFrom()[0]).append('\n');
                    s.append("Sujet: ").append(recus[i].getSubject()).append("\n\n");
                    s.append(GreenMailUtil.getBody(recus[i]));
                    Files.writeString(dossier.resolve(String.format("%04d.eml", n++)), s, StandardCharsets.UTF_8);
                }
            }
            Thread.sleep(500);
        }
    }
}
