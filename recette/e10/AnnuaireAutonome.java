import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import com.ipt.ged.identite.annuaire.SimulateurAnnuaire;

/**
 * Recette P-02 (DAT 3.3, D4) : contrôleur de domaine SIMULÉ lancé HORS de l'application, pour pouvoir
 * l'arrêter pendant qu'elle tourne (l'annuaire embarqué du profil dev vit dans la JVM de la GED et ne
 * s'arrête pas).
 *
 * <ul>
 *   <li>{@code ldap <port> <fichier.ldif>} : même simulateur que le profil dev
 *       ({@link SimulateurAnnuaire}, liaison refusée pour un compte désactivé), processus à part ;</li>
 *   <li>{@code trou <port>} : « contrôleur muet » — accepte la connexion TCP et ne répond jamais,
 *       pour éprouver le délai de lecture (5 s) et la bascule vers le contrôleur suivant.</li>
 * </ul>
 * Tourne jusqu'à l'arrêt du processus. Compilé contre {@code backend/target/classes} (voir
 * {@code recette/README.md}).
 */
public final class AnnuaireAutonome {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[1]);
        if ("trou".equals(args[0])) {
            List<Socket> retenues = new ArrayList<>();
            try (ServerSocket s = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
                System.out.println("PRET|trou|" + port);
                while (true) {
                    retenues.add(s.accept());   // jamais de réponse, jamais de fermeture
                }
            }
        }
        try (InputStream in = new FileInputStream(args[2])) {
            SimulateurAnnuaire a = SimulateurAnnuaire.demarrer("DC=marchicamed,DC=ma", port, in);
            System.out.println("PRET|ldap|" + a.url());
        }
        Thread.currentThread().join();
    }
}
