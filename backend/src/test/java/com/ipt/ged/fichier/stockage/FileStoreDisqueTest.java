package com.ipt.ged.fichier.stockage;

import com.ipt.ged.fichier.ErreurFichierException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** §6.1.1 — arborescence aa/bb, écriture unique et atomique, lecture en flux. */
class FileStoreDisqueTest {

    @TempDir Path racine;

    private static byte[] octets(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Le chemin suit /<racine>/aa/bb/<uuid>.enc, dérivé de l'identifiant")
    void arborescence() {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID id = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertEquals(racine.toAbsolutePath().normalize().resolve("3f").resolve("a8")
                .resolve("3fa85f64-5717-4562-b3fc-2c963f66afa6.enc"), store.chemin(id));
        assertTrue(store.chemin(id).startsWith(store.racine()));
    }

    @Test
    @DisplayName("Écriture puis lecture en flux restituent les octets")
    void allerRetour() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID id = UUID.randomUUID();
        store.ecrire(id, new ByteArrayInputStream(octets("contenu opaque")));
        assertTrue(store.existe(id));
        assertEquals(14, store.taille(id));
        try (InputStream in = store.lire(id)) {
            assertEquals("contenu opaque", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("Écriture unique : un identifiant existant n'est jamais réécrit")
    void ecritureUnique() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID id = UUID.randomUUID();
        store.ecrire(id, new ByteArrayInputStream(octets("original")));
        assertThrows(FichierDejaPresentException.class,
                () -> store.ecrire(id, new ByteArrayInputStream(octets("écrasement"))));
        try (InputStream in = store.lire(id)) {
            assertEquals("original", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(0, temporaires(), "aucun temporaire ne doit subsister");
    }

    @Test
    @DisplayName("Atomicité : rien n'est visible sous le nom définitif pendant l'écriture")
    void invisiblePendantEcriture() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID id = UUID.randomUUID();
        List<Boolean> vuPendant = new ArrayList<>();
        store.ecrire(id, sortie -> {
            sortie.write(octets("début"));
            vuPendant.add(store.existe(id));
            sortie.write(octets(" fin"));
        });
        assertEquals(List.of(false), vuPendant);
        assertTrue(store.existe(id));
    }

    @Test
    @DisplayName("Écriture interrompue : ni fichier publié ni temporaire résiduel")
    void ecritureInterrompue() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID id = UUID.randomUUID();
        IOException panne = assertThrows(IOException.class, () -> store.ecrire(id, sortie -> {
            sortie.write(octets("moitié"));
            throw new IOException("panne simulée");
        }));
        assertEquals("panne simulée", panne.getMessage());
        assertFalse(store.existe(id));
        assertEquals(0, temporaires());
    }

    @Test
    @DisplayName("L'écrivain qui ferme son flux ne compromet pas la publication")
    void ecrivainQuiFerme() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID id = UUID.randomUUID();
        store.ecrire(id, sortie -> {
            try (sortie) {
                sortie.write(octets("fermé tôt"));
            }
        });
        try (InputStream in = store.lire(id)) {
            assertEquals("fermé tôt", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("Fichier absent → 404 FICHIER_INTROUVABLE")
    void absent() {
        FileStoreDisque store = new FileStoreDisque(racine);
        ErreurFichierException e = assertThrows(ErreurFichierException.class, () -> store.lire(UUID.randomUUID()));
        assertEquals(404, e.statut().value());
        assertEquals("FICHIER_INTROUVABLE", e.code());
    }

    @Test
    @DisplayName("Suppression (purge) puis parcours des identifiants publiés")
    void suppressionEtParcours() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        store.ecrire(a, new ByteArrayInputStream(octets("a")));
        store.ecrire(b, new ByteArrayInputStream(octets("b")));
        assertTrue(store.supprimer(a));
        assertFalse(store.supprimer(a));
        List<UUID> vus = new ArrayList<>();
        store.parcourir(vus::add);
        assertEquals(List.of(b), vus);
    }

    @Test
    @DisplayName("Les temporaires abandonnés anciens sont nettoyés, pas les récents")
    void nettoyageTemporaires() throws IOException {
        FileStoreDisque store = new FileStoreDisque(racine);
        Path dossier = Files.createDirectories(racine.resolve("ab").resolve("cd"));
        Path ancien = Files.writeString(dossier.resolve(".ancien.tmp"), "x");
        Files.setLastModifiedTime(ancien, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Path recent = Files.writeString(dossier.resolve(".recent.tmp"), "y");
        assertEquals(1, store.nettoyerTemporaires(Duration.ofHours(1)));
        assertFalse(Files.exists(ancien));
        assertTrue(Files.exists(recent));
    }

    private long temporaires() throws IOException {
        try (Stream<Path> s = Files.walk(racine)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
    }
}
