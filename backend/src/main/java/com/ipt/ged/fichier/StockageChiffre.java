package com.ipt.ged.fichier;

import com.ipt.ged.fichier.chiffrement.FluxChiffrant;
import com.ipt.ged.fichier.chiffrement.FluxDechiffrant;
import com.ipt.ged.fichier.chiffrement.FormatChiffre;
import com.ipt.ged.fichier.cles.CleEnveloppee;
import com.ipt.ged.fichier.cles.CleFichier;
import com.ipt.ged.fichier.cles.CleIndisponibleException;
import com.ipt.ged.fichier.cles.ContexteCle;
import com.ipt.ged.fichier.cles.DepotClesFichier;
import com.ipt.ged.fichier.cles.KeyProvider;
import com.ipt.ged.fichier.cles.KeystoreKeyProvider;
import com.ipt.ged.fichier.stockage.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Point d'entrée unique pour écrire et lire un fichier chiffré (§6.1.1 à §6.1.4).
 *
 * <p>À l'écriture : une DEK AES-256 neuve est tirée pour ce seul fichier,
 * enveloppée par la KEK active <b>avant</b> toute écriture (si la KEK est
 * indisponible, rien n'est écrit), puis le contenu traverse en un seul passage
 * le calcul d'empreinte SHA-256 du clair, le contrôle de taille et le
 * chiffrement, jusqu'à l'écriture atomique du {@link FileStore}. La DEK en
 * clair n'existe qu'en mémoire, le temps de l'opération.
 *
 * <p>La DEK enveloppée est enregistrée <b>après</b> la publication du fichier ;
 * si cet enregistrement échoue, le fichier est supprimé. Dans une transaction
 * englobante, c'est à l'appelant de compenser un retour arrière ultérieur (voir
 * le plan de branchement dans {@code docs/conformite/suivi/dev3.md}).
 */
public class StockageChiffre {

    private static final Logger log = LoggerFactory.getLogger(StockageChiffre.class);
    private static final int TAMPON = 64 * 1024;

    private final FileStore store;
    private final KeyProvider keyProvider;
    private final DepotClesFichier cles;
    private final int tailleSegment;
    private final SecureRandom aleatoire = new SecureRandom();

    public StockageChiffre(FileStore store, KeyProvider keyProvider, DepotClesFichier cles) {
        this(store, keyProvider, cles, FormatChiffre.SEGMENT_PAR_DEFAUT);
    }

    public StockageChiffre(FileStore store, KeyProvider keyProvider, DepotClesFichier cles, int tailleSegment) {
        this.store = store;
        this.keyProvider = keyProvider;
        this.cles = cles;
        this.tailleSegment = tailleSegment;
    }

    /** Écrit un nouveau fichier sous un identifiant tiré au hasard. */
    public ResultatStockage ecrire(InputStream clair, long tailleMaxOctets) {
        return ecrire(UUID.randomUUID(), clair, tailleMaxOctets);
    }

    /**
     * Écrit un nouveau fichier sous l'identifiant donné.
     *
     * @param tailleMaxOctets plafond vérifié pendant le flux : une source dont
     *                        la taille annoncée mentait est arrêtée net, et rien
     *                        n'est publié (413).
     */
    public ResultatStockage ecrire(UUID id, InputStream clair, long tailleMaxOctets) {
        byte[] dek = nouvelleDek();
        MessageDigest sha = sha256();
        long[] total = {0};
        CleEnveloppee enveloppe;
        try {
            enveloppe = keyProvider.envelopper(dek, ContexteCle.de(id));
            SecretKey cle = new SecretKeySpec(dek, "AES");
            store.ecrire(id, sortie -> {
                try (FluxChiffrant chiffrant = new FluxChiffrant(sortie, cle, id, tailleSegment, aleatoire)) {
                    byte[] tampon = new byte[TAMPON];
                    int n;
                    while ((n = clair.read(tampon)) >= 0) {
                        total[0] += n;
                        if (total[0] > tailleMaxOctets) {
                            throw Refus.tropVolumineux(tailleMaxOctets);
                        }
                        sha.update(tampon, 0, n);
                        chiffrant.write(tampon, 0, n);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture du fichier chiffré impossible", e);
        } finally {
            KeystoreKeyProvider.effacer(dek);
        }
        try {
            cles.enregistrer(new CleFichier(id, enveloppe.octets(), enveloppe.kekId(), CleFichier.AES_256_GCM));
        } catch (RuntimeException e) {
            // Sans sa clé, le fichier publié serait illisible à jamais : on ne
            // le laisse pas occuper le stockage.
            supprimerSilencieusement(id);
            throw e;
        }
        return new ResultatStockage(id, HexFormat.of().formatHex(sha.digest()), total[0], enveloppe.kekId());
    }

    /**
     * Ouvre le fichier déchiffré, en flux. L'appelant ferme le flux.
     *
     * @throws ErreurFichierException 404 si la clé ou le fichier manquent
     *         (fichier purgé), 500 {@code INTEGRITE_COMPROMISE} à la lecture
     *         d'un segment altéré.
     */
    public InputStream lire(UUID id) {
        CleFichier cle = cles.trouver(id)
                .orElseThrow(() -> Refus.introuvable(id + " (clé de fichier absente ou détruite)"));
        byte[] dek;
        try {
            dek = keyProvider.desenvelopper(cle.enveloppe(), ContexteCle.de(id));
        } catch (CleIndisponibleException e) {
            throw Refus.integriteCompromise("clé de fichier inutilisable", e);
        }
        try {
            SecretKey secret = new SecretKeySpec(dek, "AES");
            InputStream brut = store.lire(id);
            try {
                return new FluxDechiffrant(brut, secret, id);
            } catch (IOException | RuntimeException e) {
                brut.close();
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du fichier " + id + " impossible", e);
        } finally {
            KeystoreKeyProvider.effacer(dek);
        }
    }

    /** Taille du contenu en clair, calculée sans déchiffrer. */
    public long tailleEnClair(UUID id) {
        try (InputStream in = store.lire(id)) {
            byte[] entete = in.readNBytes(FormatChiffre.ENTETE_OCTETS);
            if (entete.length < FormatChiffre.ENTETE_OCTETS) return -1;
            return FormatChiffre.tailleEnClair(store.taille(id), FluxDechiffrant.tailleSegment(entete));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fichier publié et clé présente : le contenu est lisible. */
    public boolean existe(UUID id) {
        return store.existe(id) && cles.trouver(id).isPresent();
    }

    /**
     * Destruction cryptographique puis physique (purge définitive, §12.5).
     *
     * <p>La DEK est détruite <b>en premier</b> : c'est elle qui rend le fichier
     * définitivement illisible, y compris dans les sauvegardes du référentiel
     * que la suppression du fichier n'atteint pas. Si la suppression physique
     * échoue ensuite, il ne reste qu'un bloc d'octets indéchiffrable.
     *
     * @return {@code true} si une clé ou un fichier ont été supprimés.
     */
    public boolean detruire(UUID id) {
        boolean cle = cles.supprimer(id);
        boolean fichier = supprimerSilencieusement(id);
        return cle || fichier;
    }

    private boolean supprimerSilencieusement(UUID id) {
        try {
            return store.supprimer(id);
        } catch (IOException | RuntimeException e) {
            log.warn("Fichier chiffré {} non supprimé (illisible, sa clé n'étant plus enregistrée)", id, e);
            return false;
        }
    }

    private byte[] nouvelleDek() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, aleatoire);
            return gen.generateKey().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Génération de la clé de données impossible", e);
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Résultat d'une écriture, à reporter sur la version de document.
     *
     * @param id          identifiant du fichier et de sa ligne {@code cle_fichier} ;
     * @param empreinte   SHA-256 du contenu <b>en clair</b>, en hexadécimal
     *                    minuscule (colonne {@code version_document.empreinte}) ;
     * @param tailleOctets taille du contenu en clair ;
     * @param kekId       KEK ayant enveloppé la DEK.
     */
    public record ResultatStockage(UUID id, String empreinte, long tailleOctets, String kekId) {
    }
}
