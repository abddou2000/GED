import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lance le faux clamd des tests de dev3 (com.ipt.ged.fichier.controle.FauxClamd : protocole
 * INSTREAM/PING réel, reconnaît la chaîne EICAR seulement) comme un service autonome, pour
 * recetter l'antivirus de bout en bout sur un poste sans ClamAV.
 *
 * <p>SIMULATEUR : toute preuve obtenue avec lui est à refaire en UAT avec un vrai clamd.
 *
 * <p>Usage : java -cp "backend/target/classes;backend/target/test-classes;<classpath>" ClamdSimule.java FICHIER_PORT
 * Écrit le port d'écoute dans FICHIER_PORT, puis tourne jusqu'à ce que le processus soit tué
 * (arrêter le processus = « ClamAV indisponible », pour le test d'échec fermé).
 */
public class ClamdSimule {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.ipt.ged.fichier.controle.FauxClamd");
        Object serveur = c.getConstructor().newInstance();
        Method port = c.getMethod("port");
        Files.writeString(Path.of(args[0]), String.valueOf(port.invoke(serveur)));
        System.out.println("clamd simulé à l'écoute sur 127.0.0.1:" + port.invoke(serveur));
        Thread.currentThread().join();
    }
}
