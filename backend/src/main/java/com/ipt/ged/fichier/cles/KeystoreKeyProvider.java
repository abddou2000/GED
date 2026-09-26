package com.ipt.ged.fichier.cles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@link KeyProvider} adossé à un keystore PKCS#12 (§6.1.2).
 *
 * <p>Les KEK sont des clés AES-256 rangées sous les alias {@code kek-00001},
 * {@code kek-00002}… La plus récente est <b>active</b> (elle enveloppe les
 * nouvelles DEK) ; les autres sont <b>désactivées</b> et ne servent plus qu'à
 * désenvelopper les DEK et les sauvegardes qui les référencent encore. Porter
 * l'état dans le numéro d'alias plutôt que dans un attribut évite un second
 * fichier de configuration qui pourrait diverger du keystore.
 *
 * <p>La phrase secrète vient de l'environnement (coffre de secrets, distinct
 * pour DEV, UAT et PROD) ; le keystore est hors du dépôt et <b>hors du
 * référentiel de fichiers</b> : une clé rangée à côté des fichiers qu'elle
 * protège ne protège rien.
 *
 * <p>Enveloppe : AES-256-GCM, IV aléatoire de 96 bits, données authentifiées =
 * identifiant de la KEK + contexte (identifiant du fichier). Format :
 * {@code IV (12) || DEK chiffrée (32) || étiquette (16)}.
 */
public class KeystoreKeyProvider implements KeyProvider {

    private static final Logger log = LoggerFactory.getLogger(KeystoreKeyProvider.class);

    private static final String TYPE_KEYSTORE = "PKCS12";
    private static final String PREFIXE_ALIAS = "kek-";
    private static final Pattern ALIAS = Pattern.compile("kek-\\d{5}");
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_OCTETS = 12;
    private static final int ETIQUETTE_BITS = 128;
    private static final byte[] DOMAINE = "GED-DEK-v1".getBytes(StandardCharsets.US_ASCII);

    private final Path chemin;
    private final char[] motDePasse;
    private final SecureRandom aleatoire = new SecureRandom();

    /** Instantané immuable : les lectures concurrentes ne se verrouillent pas. */
    private volatile NavigableMap<String, SecretKey> keks;

    /**
     * @param creerSiAbsent vrai en développement et en test uniquement : en
     *                      production, un keystore absent est une erreur de
     *                      déploiement, et en créer un neuf rendrait tous les
     *                      fichiers existants illisibles.
     */
    public KeystoreKeyProvider(Path chemin, char[] motDePasse, boolean creerSiAbsent) {
        if (motDePasse == null || motDePasse.length == 0) {
            throw new IllegalStateException("Phrase secrète du keystore absente : renseigner GED_KEYSTORE_MDP.");
        }
        this.chemin = chemin.toAbsolutePath().normalize();
        this.motDePasse = motDePasse.clone();
        try {
            if (Files.exists(this.chemin)) {
                this.keks = charger();
                if (keks.isEmpty()) {
                    throw new IllegalStateException("Le keystore " + this.chemin + " ne contient aucune KEK.");
                }
            } else if (creerSiAbsent) {
                log.warn("Keystore absent, création d'un keystore NEUF avec une première KEK : {}", this.chemin);
                this.keks = Collections.unmodifiableNavigableMap(new TreeMap<>());
                ajouterKek();
            } else {
                throw new IllegalStateException("Keystore des clés maîtresses introuvable : " + this.chemin
                        + " (GED_KEYSTORE_CHEMIN). Aucun fichier ne peut être chiffré ni lu sans lui.");
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Keystore illisible : " + this.chemin
                    + " (phrase secrète erronée ou fichier corrompu).", e);
        }
        log.info("Keystore chargé : {} KEK, active = {}", keks.size(), kekActive());
    }

    @Override
    public String kekActive() {
        return keks.lastKey();
    }

    @Override
    public Set<String> kekConnues() {
        return keks.navigableKeySet();
    }

    @Override
    public CleEnveloppee envelopper(byte[] dek, byte[] contexte) {
        NavigableMap<String, SecretKey> instantane = keks;
        String kekId = instantane.lastKey();
        byte[] iv = new byte[IV_OCTETS];
        aleatoire.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, instantane.get(kekId), new GCMParameterSpec(ETIQUETTE_BITS, iv));
            c.updateAAD(aad(kekId, contexte));
            byte[] chiffre = c.doFinal(dek);
            return new CleEnveloppee(kekId, ByteBuffer.allocate(iv.length + chiffre.length).put(iv).put(chiffre).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Enveloppement de la clé de données impossible", e);
        }
    }

    @Override
    public byte[] desenvelopper(CleEnveloppee enveloppe, byte[] contexte) {
        SecretKey kek = keks.get(enveloppe.kekId());
        if (kek == null) {
            throw new CleIndisponibleException("KEK inconnue du keystore : " + enveloppe.kekId());
        }
        byte[] o = enveloppe.octets();
        if (o == null || o.length <= IV_OCTETS) {
            throw new CleIndisponibleException("Enveloppe de clé tronquée");
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(ETIQUETTE_BITS, o, 0, IV_OCTETS));
            c.updateAAD(aad(enveloppe.kekId(), contexte));
            return c.doFinal(o, IV_OCTETS, o.length - IV_OCTETS);
        } catch (AEADBadTagException e) {
            throw new CleIndisponibleException("Enveloppe de clé altérée ou rattachée à un autre fichier", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Désenveloppement de la clé de données impossible", e);
        }
    }

    @Override
    public synchronized String nouvelleKek() {
        try {
            return ajouterKek();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Création d'une nouvelle KEK impossible", e);
        }
    }

    @Override
    public synchronized void retirer(String kekId) {
        if (!keks.containsKey(kekId)) return;
        if (kekId.equals(kekActive())) {
            throw new IllegalStateException("La KEK active ne peut pas être retirée : effectuer d'abord une rotation.");
        }
        try {
            KeyStore ks = ouvrir();
            ks.deleteEntry(kekId);
            enregistrer(ks);
            this.keks = charger();
            log.warn("KEK retirée du keystore : {}", kekId);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Retrait de la KEK impossible", e);
        }
    }

    /* ---------- keystore ---------- */

    private String ajouterKek() throws IOException, GeneralSecurityException {
        KeyStore ks = Files.exists(chemin) ? ouvrir() : vide();
        int suivant = keks.keySet().stream()
                .mapToInt(a -> Integer.parseInt(a.substring(PREFIXE_ALIAS.length())))
                .max().orElse(0) + 1;
        String alias = String.format("%s%05d", PREFIXE_ALIAS, suivant);
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256, aleatoire);
        ks.setEntry(alias, new KeyStore.SecretKeyEntry(gen.generateKey()), new KeyStore.PasswordProtection(motDePasse));
        enregistrer(ks);
        this.keks = charger();
        log.info("Nouvelle KEK active : {}", alias);
        return alias;
    }

    private NavigableMap<String, SecretKey> charger() throws IOException, GeneralSecurityException {
        KeyStore ks = ouvrir();
        TreeMap<String, SecretKey> lues = new TreeMap<>();
        for (String alias : Collections.list(ks.aliases())) {
            if (!ALIAS.matcher(alias).matches()) continue;
            KeyStore.Entry e = ks.getEntry(alias, new KeyStore.PasswordProtection(motDePasse));
            if (e instanceof KeyStore.SecretKeyEntry s) {
                lues.put(alias, s.getSecretKey());
            }
        }
        return Collections.unmodifiableNavigableMap(lues);
    }

    private KeyStore ouvrir() throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(TYPE_KEYSTORE);
        try (InputStream in = Files.newInputStream(chemin)) {
            ks.load(in, motDePasse);
        }
        return ks;
    }

    private static KeyStore vide() throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(TYPE_KEYSTORE);
        ks.load(null, null);
        return ks;
    }

    /**
     * Réécrit le keystore de façon atomique (temporaire, fsync, renommage) : un
     * keystore à moitié écrit après une coupure rendrait tout le fonds
     * illisible. Ici le remplacement est voulu, contrairement aux fichiers.
     */
    private void enregistrer(KeyStore ks) throws IOException, GeneralSecurityException {
        Path dossier = chemin.getParent();
        Files.createDirectories(dossier);
        Path temporaire = Files.createTempFile(dossier, "." + chemin.getFileName(), ".tmp");
        try {
            restreindre(temporaire);
            try (FileChannel canal = FileChannel.open(temporaire, StandardOpenOption.WRITE)) {
                OutputStream out = Channels.newOutputStream(canal);
                ks.store(out, motDePasse);
                out.flush();
                canal.force(true);
            }
            Files.move(temporaire, chemin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaire);
        }
    }

    /** Lecture et écriture réservées au compte de service, là où POSIX le permet. */
    private static void restreindre(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignore) {
            // Windows : les ACL héritées du répertoire s'appliquent.
        }
    }

    private static byte[] aad(String kekId, byte[] contexte) {
        byte[] id = kekId.getBytes(StandardCharsets.US_ASCII);
        byte[] ctx = contexte != null ? contexte : new byte[0];
        return ByteBuffer.allocate(DOMAINE.length + 1 + id.length + 1 + ctx.length)
                .put(DOMAINE).put((byte) '|').put(id).put((byte) '|').put(ctx).array();
    }

    /** Efface une copie de clé en mémoire (au mieux : le ramasse-miettes a pu en faire d'autres). */
    public static void effacer(byte[] cle) {
        if (cle != null) Arrays.fill(cle, (byte) 0);
    }
}
