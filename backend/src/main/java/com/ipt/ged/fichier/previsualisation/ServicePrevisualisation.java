package com.ipt.ged.fichier.previsualisation;

import com.ipt.ged.fichier.StockageChiffre;
import com.ipt.ged.fichier.controle.FormatsReconnus;
import com.ipt.ged.fichier.Refus;
import com.ipt.ged.fichier.stockage.FichierDejaPresentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Prévisualisation en ligne (§6.1.6).
 *
 * <ul>
 *   <li><b>PDF, images, texte</b> : servis déchiffrés à la volée, en flux ;
 *       aucune copie en clair n'est écrite, nulle part.</li>
 *   <li><b>Bureautique</b> : converti en PDF par LibreOffice au premier
 *       affichage ; la copie de prévisualisation est rangée dans un cache
 *       <b>chiffré</b> comme tout fichier (sa propre DEK dans
 *       {@code cle_fichier}), puis servie comme un PDF.</li>
 * </ul>
 *
 * <p>La conversion exige un fichier d'entrée : le document est déchiffré dans
 * un répertoire de travail propre à la conversion, supprimé aussitôt après.
 * Ce répertoire doit être un {@code tmpfs} en production
 * ({@code ged.fichiers.previsualisation.repertoire-travail}), comme celui de
 * l'OCR (§6.1.3) : la copie ne touche alors jamais un disque persistant.
 *
 * <p>Les droits ne sont pas contrôlés ici : c'est le rôle du contrôleur, via
 * {@link ControleAccesPrevisualisation}.
 */
public class ServicePrevisualisation {

    private static final Logger log = LoggerFactory.getLogger(ServicePrevisualisation.class);
    private static final String PDF = FormatsReconnus.PDF;
    /** Formats que le navigateur affiche nativement, servis sans conversion. */
    private static final Set<String> DIRECTS = Set.of(PDF, "image/png", "image/jpeg", "image/gif", "image/tiff",
            "image/bmp", FormatsReconnus.TEXTE, "text/csv");

    private final StockageChiffre documents;
    private final StockageChiffre cache;
    private final ConvertisseurBureautique convertisseur;
    private final Path repertoireTravail;
    private final long plafondOctets;

    public ServicePrevisualisation(StockageChiffre documents, StockageChiffre cache,
                                   ConvertisseurBureautique convertisseur, Path repertoireTravail, long plafondOctets) {
        this.documents = documents;
        this.cache = cache;
        this.convertisseur = convertisseur;
        this.repertoireTravail = repertoireTravail.toAbsolutePath().normalize();
        this.plafondOctets = plafondOctets;
    }

    /**
     * Ouvre l'aperçu d'un fichier stocké.
     *
     * @param typeMime type réel enregistré au dépôt (jamais recalculé ici).
     */
    public Apercu ouvrir(UUID fichierId, String typeMime) {
        if (DIRECTS.contains(typeMime)) {
            // Le texte est servi avec un jeu de caractères explicite : sans
            // lui, le navigateur devinerait, et pourrait l'interpréter.
            String servi = typeMime.startsWith("text/") ? "text/plain;charset=" + StandardCharsets.UTF_8.name() : typeMime;
            return new Apercu(documents.lire(fichierId), servi, documents.tailleEnClair(fichierId));
        }
        if (!FormatsReconnus.bureautique(typeMime)) {
            throw Refus.apercuNonDisponible(typeMime);
        }
        UUID idCache = idCache(fichierId);
        if (!cache.existe(idCache)) {
            // Clé sans fichier ou fichier sans clé (arrêt brutal pendant une
            // mise en cache) : l'entrée est inutilisable, on la reconstruit.
            cache.detruire(idCache);
            convertirEtMettreEnCache(fichierId, typeMime, idCache);
        }
        return new Apercu(cache.lire(idCache), PDF, cache.tailleEnClair(idCache));
    }

    /**
     * Supprime la copie de prévisualisation d'un fichier. À appeler à la purge
     * définitive du document : la copie en cache est une copie du contenu et
     * doit disparaître avec lui (destruction cryptographique comprise).
     */
    public void invaliderCache(UUID fichierId) {
        cache.detruire(idCache(fichierId));
    }

    /**
     * Identifiant du cache dérivé de celui du document (UUID de type 3) : pas
     * de table de correspondance, et un seul emplacement possible par document.
     */
    static UUID idCache(UUID fichierId) {
        return UUID.nameUUIDFromBytes(("apercu-pdf:" + fichierId).getBytes(StandardCharsets.US_ASCII));
    }

    private void convertirEtMettreEnCache(UUID fichierId, String typeMime, UUID idCache) {
        if (!convertisseur.disponible()) {
            throw Refus.conversionIndisponible("LibreOffice n'est pas installé sur le serveur.", null);
        }
        Path travail = null;
        try {
            Files.createDirectories(repertoireTravail);
            travail = Files.createTempDirectory(repertoireTravail, "apercu-");
            Path source = travail.resolve("document." + FormatsReconnus.extensionPour(typeMime));
            try (InputStream clair = documents.lire(fichierId)) {
                Files.copy(clair, source);
            }
            Path pdf = convertisseur.convertirEnPdf(source, travail);
            try (InputStream in = Files.newInputStream(pdf)) {
                cache.ecrire(idCache, in, plafondOctets);
            } catch (FichierDejaPresentException concurrent) {
                // Une autre requête a converti le même document entre-temps :
                // son résultat est aussi bon que le nôtre.
                log.debug("Aperçu {} déjà mis en cache par une requête concurrente", fichierId);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Préparation de l'aperçu impossible", e);
        } finally {
            Fichiers.supprimerArborescence(travail);
        }
    }

    /**
     * Aperçu prêt à servir. L'appelant ferme le flux.
     *
     * @param taille taille en clair, ou -1 si inconnue.
     */
    public record Apercu(InputStream flux, String typeMime, long taille) {
    }
}
