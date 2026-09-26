package com.ipt.ged.fichier.stockage;

import com.ipt.ged.fichier.Refus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@link FileStore} sur un volume monté : {@code /<racine>/aa/bb/<uuid>.enc}.
 *
 * <p><b>Arborescence.</b> Les deux niveaux de deux caractères hexadécimaux
 * (256 × 256 répertoires) sont tirés de l'identifiant lui-même : aucun
 * répertoire ne sature quand le fonds croît, et le chemin se recalcule sans
 * table de correspondance.
 *
 * <p><b>Écriture atomique et unique.</b> Le contenu est écrit dans un fichier
 * temporaire <i>du même répertoire</i> (donc du même volume — un renommage
 * entre volumes serait une copie non atomique), forcé sur le support par
 * {@code fsync}, puis publié. La publication passe par un <b>lien physique</b>,
 * dont la création échoue si la cible existe : c'est l'unique primitive
 * portable qui publie atomiquement <i>sans jamais remplacer</i> (un renommage
 * atomique, lui, écrase silencieusement la cible sous POSIX comme sous
 * Windows). Le répertoire est ensuite forcé à son tour, pour que l'entrée
 * survive à une coupure de courant.
 *
 * <p><b>Confinement.</b> Le chemin ne dérive que d'un {@link UUID} : il ne peut
 * pas contenir {@code ..}. Le contrôle {@code startsWith(racine)} est gardé
 * malgré tout, parce qu'il ne coûte rien et survivrait à une évolution du
 * schéma de nommage.
 */
public class FileStoreDisque implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(FileStoreDisque.class);

    static final String EXTENSION = ".enc";
    private static final String SUFFIXE_TEMPORAIRE = ".tmp";
    private static final int TAMPON = 64 * 1024;

    private final Path racine;

    public FileStoreDisque(Path racine) {
        this.racine = racine.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.racine);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de créer la racine du stockage : " + this.racine, e);
        }
    }

    public Path racine() {
        return racine;
    }

    /** Chemin physique d'un identifiant ; visible pour les tests et la reprise. */
    public Path chemin(UUID id) {
        String s = id.toString();
        Path cible = racine.resolve(s.substring(0, 2)).resolve(s.substring(2, 4)).resolve(s + EXTENSION).normalize();
        if (!cible.startsWith(racine)) {
            throw new IllegalStateException("Chemin hors du stockage : " + cible);
        }
        return cible;
    }

    @Override
    public void ecrire(UUID id, EcrivainFlux ecrivain) throws IOException {
        Path cible = chemin(id);
        if (Files.exists(cible)) {
            throw new FichierDejaPresentException(id);
        }
        Path dossier = cible.getParent();
        Files.createDirectories(dossier);
        Path temporaire = Files.createTempFile(dossier, "." + id + ".", SUFFIXE_TEMPORAIRE);
        try {
            try (FileChannel canal = FileChannel.open(temporaire, StandardOpenOption.WRITE)) {
                OutputStream sortie = new BufferedOutputStream(Channels.newOutputStream(canal), TAMPON);
                // L'écrivain peut fermer « son » flux (try-with-resources) :
                // il ne doit pas fermer le canal avant le fsync ci-dessous.
                ecrivain.ecrire(new NonFermant(sortie));
                sortie.flush();
                canal.force(true);
            }
            publier(temporaire, cible, id);
            forcerRepertoire(dossier);
        } finally {
            Files.deleteIfExists(temporaire);
        }
    }

    /**
     * Publie le temporaire sous son nom définitif sans jamais écraser.
     *
     * <p>Repli si le système de fichiers refuse les liens physiques (certains
     * montages réseau) : déplacement simple, sans remplacement. Java vérifie
     * alors l'absence de la cible avant de renommer : il reste une fenêtre de
     * concurrence infime, sans objet ici puisque l'identifiant est un UUID tiré
     * au hasard par l'écrivain lui-même.
     */
    private static void publier(Path temporaire, Path cible, UUID id) throws IOException {
        try {
            Files.createLink(cible, temporaire);
        } catch (FileAlreadyExistsException e) {
            throw new FichierDejaPresentException(id);
        } catch (UnsupportedOperationException | FileSystemException e) {
            log.debug("Lien physique refusé sur {}, repli sur un renommage sans remplacement", cible, e);
            try {
                Files.move(temporaire, cible);
            } catch (FileAlreadyExistsException existe) {
                throw new FichierDejaPresentException(id);
            }
        }
    }

    /**
     * {@code fsync} du répertoire : sans lui, l'entrée de répertoire créée par
     * la publication peut être perdue sur coupure alors que le contenu est
     * sur disque. Windows ne permet pas d'ouvrir un répertoire en canal : la
     * journalisation NTFS couvre ce cas, l'échec est donc ignoré.
     */
    private static void forcerRepertoire(Path dossier) {
        try (FileChannel canal = FileChannel.open(dossier, StandardOpenOption.READ)) {
            canal.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            log.trace("fsync du répertoire non disponible sur ce système : {}", dossier);
        }
    }

    @Override
    public InputStream lire(UUID id) throws IOException {
        try {
            return Files.newInputStream(chemin(id), StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            throw Refus.introuvable(id.toString());
        }
    }

    @Override
    public boolean existe(UUID id) {
        return Files.exists(chemin(id));
    }

    @Override
    public long taille(UUID id) throws IOException {
        try {
            return Files.size(chemin(id));
        } catch (NoSuchFileException e) {
            throw Refus.introuvable(id.toString());
        }
    }

    @Override
    public boolean supprimer(UUID id) throws IOException {
        return Files.deleteIfExists(chemin(id));
    }

    /**
     * Supprime les temporaires abandonnés par une écriture interrompue (arrêt
     * brutal de la JVM entre la création et le {@code finally}). Ils ne sont
     * jamais publiés, donc jamais lus, mais occupent de la place.
     *
     * @param ageMinimum ne touche pas aux temporaires plus récents : une
     *                   écriture peut être en cours.
     * @return nombre de temporaires supprimés.
     */
    public int nettoyerTemporaires(Duration ageMinimum) throws IOException {
        Instant limite = Instant.now().minus(ageMinimum);
        int supprimes = 0;
        try (Stream<Path> fichiers = Files.walk(racine, 3)) {
            for (Path p : (Iterable<Path>) fichiers::iterator) {
                String nom = p.getFileName().toString();
                if (nom.startsWith(".") && nom.endsWith(SUFFIXE_TEMPORAIRE) && Files.isRegularFile(p)) {
                    FileTime modif = Files.getLastModifiedTime(p);
                    if (modif.toInstant().isBefore(limite) && Files.deleteIfExists(p)) supprimes++;
                }
            }
        }
        return supprimes;
    }

    /** Parcourt les identifiants publiés (reprise, vérifications globales). */
    public void parcourir(java.util.function.Consumer<UUID> visiteur) throws IOException {
        try (DirectoryStream<Path> niveau1 = Files.newDirectoryStream(racine, Files::isDirectory)) {
            for (Path d1 : niveau1) {
                try (DirectoryStream<Path> niveau2 = Files.newDirectoryStream(d1, Files::isDirectory)) {
                    for (Path d2 : niveau2) {
                        try (DirectoryStream<Path> fichiers = Files.newDirectoryStream(d2, "*" + EXTENSION)) {
                            for (Path f : fichiers) {
                                String nom = f.getFileName().toString();
                                try {
                                    visiteur.accept(UUID.fromString(nom.substring(0, nom.length() - EXTENSION.length())));
                                } catch (IllegalArgumentException ignore) {
                                    log.warn("Fichier étranger au stockage ignoré : {}", f);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Empêche l'écrivain de fermer le canal avant le fsync. */
    private static final class NonFermant extends FilterOutputStream {
        NonFermant(OutputStream out) { super(out); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { out.flush(); }
    }
}
