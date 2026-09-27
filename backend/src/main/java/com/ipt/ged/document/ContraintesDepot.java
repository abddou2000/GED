package com.ipt.ged.document;

import com.ipt.ged.typedocument.TypeDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Contraintes de dépôt portées par un type de document : formats acceptés et
 * taille maximale.
 *
 * <p>Elles vivaient en double dans {@code DocumentService} (dépôt initial et
 * nouvelle version) et <b>nulle part</b> dans l'aperçu d'indexation, qui reçoit
 * pourtant le même fichier, juste avant. Un {@code .exe} de 50 Mo y passait donc
 * en 200 alors que le dépôt qu'il précède l'aurait refusé — et comme l'écran
 * appelle l'aperçu à chaque sélection de fichier, le serveur acceptait de lire
 * n'importe quoi, en boucle, sans plafond.
 *
 * <p>Mutualiser la règle est le seul moyen que les deux portes disent la même
 * chose : recopiée, elle divergera au premier ajout de format.
 */
public final class ContraintesDepot {

    private ContraintesDepot() {}

    /**
     * Vérifie qu'un fichier peut être déposé sous ce type.
     *
     * @param fichier fichier reçu ; {@code null} ou vide n'est pas contrôlé ici
     *                (l'obligation du fichier relève de l'appelant, l'aperçu
     *                sachant travailler sur le seul nom).
     */
    public static void valider(TypeDocument type, MultipartFile fichier) {
        if (fichier == null || fichier.isEmpty()) return;
        String nom = fichier.getOriginalFilename() != null ? fichier.getOriginalFilename() : "document";
        validerFormat(type, extension(nom));
        validerTaille(type, fichier.getSize());
    }

    /** Contrôle du seul format, quand aucun contenu n'accompagne le nom. */
    public static void validerFormat(TypeDocument type, String extension) {
        List<String> autorises = type.formatsAutorises();
        if (!autorises.isEmpty() && !autorises.contains(extension.toLowerCase())) {
            throw new IllegalArgumentException("Format « ." + extension + " » non autorisé (autorisés : "
                    + String.join(", ", autorises) + ")");
        }
    }

    public static void validerTaille(TypeDocument type, long octets) {
        long maxOctets = (long) type.getTailleMaxMo() * 1024 * 1024;
        if (octets > maxOctets) {
            throw new IllegalArgumentException("Fichier trop volumineux (max " + type.getTailleMaxMo() + " Mo)");
        }
    }

    /**
     * Refuse un type mis à la corbeille.
     *
     * <p>Un type supprimé disparaît des sélecteurs mais son identifiant reste
     * valide : déposer dessus créait un document rangé sous une catégorie que
     * plus personne ne peut voir ni administrer.
     */
    public static void validerTypeVivant(TypeDocument type) {
        if (type.isSupprime()) {
            throw new IllegalArgumentException("Le type de document « " + type.getTypeDeDocument()
                    + " » est en corbeille : dépôt impossible.");
        }
    }

    /** Extension d'un nom de fichier, sans le point ; chaîne vide s'il n'y en a pas. */
    public static String extension(String nomFichier) {
        if (nomFichier == null) return "";
        int point = nomFichier.lastIndexOf('.');
        return (point >= 0 && point < nomFichier.length() - 1) ? nomFichier.substring(point + 1) : "";
    }
}
