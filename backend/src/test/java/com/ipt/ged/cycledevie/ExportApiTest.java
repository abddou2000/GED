package com.ipt.ged.cycledevie;

import com.fasterxml.jackson.databind.JsonNode;
import com.ipt.ged.document.evenement.DocumentExporte;
import com.ipt.ged.fichier.controle.Echantillons;
import com.ipt.ged.support.Comptes;
import com.ipt.ged.support.Pdfs;
import com.ipt.ged.identite.Role;
import com.ipt.ged.support.IdentitesDeTest;
import com.ipt.ged.support.JeuDroits;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import com.ipt.ged.workspace.WorkSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Export de dossier (§12.10) : archive ZIP en flux avec l'arborescence, les
 * documents à leur version courante et manifeste.csv ; filtrage par le
 * prédicat de droits (documents omis silencieusement) ; un événement
 * DOCUMENT_EXPORTE par document ; traitement de fond au-delà des seuils.
 */
@RecordApplicationEvents
class ExportApiTest extends BaseCycleDeVieApiTest {

    @Autowired private ProprietesCycleDeVie proprietes;
    @Autowired private ExportDossiers exports;
    @Autowired private ApplicationEvents evenements;
    @Autowired private JeuDroits jeu;
    @Autowired private IdentitesDeTest identites;

    private WorkSpace racine, sous;
    private UUID typeRacine, typeSous;
    private int seuilInitial;

    @BeforeEach
    void preparer() {
        seuilInitial = proprietes.getExport().getSeuilDocuments();
        racine = espace("Export " + suffixe, null);
        sous = espace("Annexes", racine);
        typeRacine = type(racine, "pdf,png");
        typeSous = type(sous, "pdf,png");
    }

    @AfterEach
    void retablir() {
        proprietes.getExport().setSeuilDocuments(seuilInitial);
    }

    /** Entrées de l'archive, nom → contenu. */
    private static Map<String, byte[]> lire(byte[] zip) throws IOException {
        Map<String, byte[]> entrees = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) entrees.put(e.getName(), in.readAllBytes());
        }
        return entrees;
    }

    /** PDF de ~1,6 Mo (deux segments chiffrés de 1 Mio) au contenu incompressible. */
    private static byte[] pdfDeuxSegments() {
        byte[] alea = new byte[1_600_000];
        new java.util.Random(12).nextBytes(alea);
        byte[] debut = "%PDF-1.4\n1 0 obj << /Length 1600000 >> stream\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] fin = "\nendstream endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = new byte[debut.length + alea.length + fin.length];
        System.arraycopy(debut, 0, pdf, 0, debut.length);
        System.arraycopy(alea, 0, pdf, debut.length, alea.length);
        System.arraycopy(fin, 0, pdf, debut.length + alea.length, fin.length);
        return pdf;
    }

    @Test
    @DisplayName("ANO-E5-003 : fichier altéré au-delà du premier segment → 500 problem+json INTEGRITE_COMPROMISE "
            + "avant tout envoi, aucune archive tronquée, aucun DOCUMENT_EXPORTE, anomalie publiée")
    void exportAvecFichierAltere() throws Exception {
        deposer(typeRacine, "Sain", "sain.pdf", "application/pdf", Pdfs.pdf("sain"));
        UUID altere = deposer(typeSous, "Altéré", "altere.pdf", "application/pdf", pdfDeuxSegments());
        java.nio.file.Path enc = fichierChiffre(fichierCourant(altere));
        byte[] octets = java.nio.file.Files.readAllBytes(enc);
        assertTrue(octets.length > 1_200_000, "deux segments");
        octets[1_300_000] ^= 0x5A; // second segment : le premier reste authentique
        java.nio.file.Files.write(enc, octets);

        mvc.perform(post("/api/v1/exports/dossiers/" + racine.getId()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTEGRITE_COMPROMISE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(altere.toString())));
        assertEquals(0, evenements.stream(DocumentExporte.class).count(), "rien n'a été servi, rien n'est audité");
        assertTrue(evenements.stream(com.ipt.ged.fichier.integrite.VerificationIntegrite.AnomalieIntegrite.class)
                .anyMatch(a -> a.statut() == com.ipt.ged.fichier.integrite.VerificationIntegrite.Statut.ALTERE),
                "anomalie publiée pour l'exploitation");

        // Le document altéré mis à la corbeille, l'export repart normalement.
        mvc.perform(delete("/api/v1/documents/" + altere)).andExpect(status().isNoContent());
        MvcResult r = mvc.perform(post("/api/v1/exports/dossiers/" + racine.getId()))
                .andExpect(status().isOk()).andReturn();
        assertTrue(lire(r.getResponse().getContentAsByteArray()).keySet().stream().anyMatch(n -> n.endsWith("Sain.pdf")));
    }

    @Test
    @DisplayName("Export en flux : arborescence, version courante, doublons de nom, manifeste, droits, audit")
    void exportEnFlux() throws Exception {
        byte[] rapport = Pdfs.pdf("rapport");
        byte[] v2 = Pdfs.pdf("rapport-v2");
        byte[] png = Echantillons.image("png");
        UUID a = deposer(typeRacine, "Rapport", "rapport.pdf", "application/pdf", rapport);
        mvc.perform(multipart("/api/v1/documents/" + a + "/versions")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "rapport.pdf", "application/pdf", v2)))
                .andExpect(status().is2xxSuccessful());
        UUID b = deposer(typeRacine, "Rapport", "rapport-bis.pdf", "application/pdf", Pdfs.pdf("bis"));
        UUID c = deposer(typeSous, "Plan", "plan.png", "image/png", png);
        // Document PRIVÉ d'un autre déposant : hors du périmètre d'un utilisateur standard (§12.3).
        UUID secret = UUID.fromString(json(mvc.perform(multipart("/api/v1/documents")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "s.pdf", "application/pdf",
                        Pdfs.pdf("secret")))
                .param("name", "SECRET budget").param("typeDocumentId", typeSous.toString())
                .param("confidentialite", "PRIVE")).andExpect(status().is2xxSuccessful())).get("id").asText());
        UUID supprime = deposer(typeRacine, "Supprimé", "x.pdf", "application/pdf", Pdfs.pdf("x"));
        mvc.perform(delete("/api/v1/documents/" + supprime)).andExpect(status().isNoContent());

        // Export par un utilisateur standard sur le dossier : le prédicat de droits
        // réel (lot E3) écarte le document privé qu'il ne peut pas voir.
        jeu.habiliter(Comptes.SANS_ROLE, Role.UTILISATEUR_STANDARD, racine.getId(), null);
        MvcResult r = mvc.perform(post("/api/v1/exports/dossiers/" + racine.getId())
                        .with(user(identites.loadUserByUsername(Comptes.SANS_ROLE))))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn();
        Map<String, byte[]> zip = lire(r.getResponse().getContentAsByteArray());
        String dossier = "Export " + suffixe;

        // Manifeste d'abord, puis les documents ; l'arborescence est conservée.
        String premier = zip.keySet().iterator().next();
        assertEquals(dossier + "/manifeste.csv", premier);
        assertArrayEquals(v2, zip.get(dossier + "/Rapport.pdf"), "version courante");
        assertArrayEquals(Pdfs.pdf("bis"), zip.get(dossier + "/Rapport (2).pdf"), "nom en double");
        assertArrayEquals(png, zip.get(dossier + "/Annexes/Plan.png"));
        assertTrue(zip.keySet().stream().noneMatch(n -> n.contains("SECRET") || n.contains("Supprim")),
                "hors périmètre et corbeille omis silencieusement : " + zip.keySet());

        String manifeste = new String(zip.get(premier), StandardCharsets.UTF_8);
        assertTrue(manifeste.startsWith("﻿identifiant;fichier;chemins;nom;objet;type;date_document;date_depot;"
                + "deposant;confidentialite;statut;version;empreinte_sha256"), manifeste);
        String[] lignes = manifeste.split("\r\n");
        assertEquals(4, lignes.length, manifeste);
        String ligneA = java.util.Arrays.stream(lignes).filter(l -> l.startsWith(a.toString())).findFirst().orElseThrow();
        String empreinteV2 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(v2));
        // … statut, version, empreinte, puis canal du dépôt et application (T-040).
        assertTrue(ligneA.endsWith(";ACTIF;2;" + empreinteV2 + ";INTERFACE;"), ligneA);
        assertTrue(manifeste.contains(dossier + "/Annexes"), "chemin du document du sous-dossier");
        assertFalse(manifeste.contains(secret.toString()));

        assertEquals(3, evenements.stream(DocumentExporte.class).count(), "un DOCUMENT_EXPORTE par document exporté");
        assertTrue(evenements.stream(DocumentExporte.class).anyMatch(e -> e.documentId().equals(c)));
        assertTrue(evenements.stream(DocumentExporte.class).noneMatch(e -> e.documentId().equals(secret)));
        assertNotNull(b);
    }

    @Test
    @DisplayName("Au-delà du seuil : export de fond (202), liste de l'utilisateur, archive chiffrée téléchargeable, expiration")
    void exportDeFond() throws Exception {
        proprietes.getExport().setSeuilDocuments(1);
        byte[] un = Pdfs.pdf("un");
        deposer(typeRacine, "Un", "un.pdf", "application/pdf", un);
        deposer(typeSous, "Deux", "deux.pdf", "application/pdf", Pdfs.pdf("deux"));

        JsonNode export = json(mvc.perform(post("/api/v1/exports/dossiers/" + racine.getId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.etat").value("EN_ATTENTE"))
                .andExpect(jsonPath("$.nbDocuments").value(2)));
        UUID id = UUID.fromString(export.get("id").asText());
        mvc.perform(get("/api/v1/exports")).andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
        mvc.perform(get("/api/v1/exports/" + id + "/fichier")).andExpect(status().isConflict());
        assertEquals(0, evenements.stream(DocumentExporte.class).count(), "audit à la production de l'archive");

        while (exports.traiterUnJob()) {
            // d'éventuels exports d'autres tests passent aussi
        }
        mvc.perform(get("/api/v1/exports/" + id)).andExpect(jsonPath("$.etat").value("TERMINE"));
        assertEquals(2, evenements.stream(DocumentExporte.class).filter(e -> id.equals(e.exportId())).count());
        UUID fichier = jdbc.queryForObject("SELECT cle_fichier_id FROM job_export WHERE id = ?", UUID.class, id);
        assertFalse(new String(java.nio.file.Files.readAllBytes(fichierChiffre(fichier)), StandardCharsets.ISO_8859_1)
                .contains("manifeste.csv"), "archive chiffrée sur disque");

        Map<String, byte[]> zip = lire(flux("/api/v1/exports/" + id + "/fichier"));
        assertArrayEquals(un, zip.get("Export " + suffixe + "/Un.pdf"));
        assertTrue(zip.containsKey("Export " + suffixe + "/Annexes/Deux.pdf"));

        // Expiration : clé et fichier détruits, plus téléchargeable.
        jdbc.update("UPDATE job_export SET expire_le = now() - interval '1 minute' WHERE id = ?", id);
        exports.expirer();
        mvc.perform(get("/api/v1/exports/" + id)).andExpect(jsonPath("$.etat").value("EXPIRE"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cle_fichier WHERE id = ?", Integer.class, fichier));
        mvc.perform(get("/api/v1/exports/" + id + "/fichier")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Export d'un autre utilisateur : introuvable (404) ; dossier inconnu : 404")
    void exportDUnAutre() throws Exception {
        UUID autre = UUID.randomUUID();
        jdbc.update("INSERT INTO job_export (id, dossier_id, dossier_nom, demandeur_employe_id, etat) VALUES (?, ?, 'X', ?, 'TERMINE')",
                autre, racine.getId(), Comptes.idSecondActeur(employeRepository));
        mvc.perform(get("/api/v1/exports/" + autre)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/exports/" + autre + "/fichier")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/exports/dossiers/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ANO-E7-001 : dossier hors périmètre → 404 indiscernable, aucun ZIP ni export de fond")
    void dossierHorsPerimetre() throws Exception {
        deposer(typeRacine, "Invisible", "i.pdf", "application/pdf", Pdfs.pdf("invisible"));
        Integer exportsAvant = jdbc.queryForObject("SELECT count(*) FROM job_export", Integer.class);
        // Utilisateur sans aucune habilitation : le dossier n'est ni couvert ni de passage.
        mvc.perform(post("/api/v1/exports/dossiers/" + racine.getId())
                        .with(user(identites.loadUserByUsername(Comptes.SANS_ROLE))))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Content-Disposition"));
        proprietes.getExport().setSeuilDocuments(0);
        mvc.perform(post("/api/v1/exports/dossiers/" + racine.getId())
                        .with(user(identites.loadUserByUsername(Comptes.SANS_ROLE))))
                .andExpect(status().isNotFound());
        assertEquals(exportsAvant, jdbc.queryForObject("SELECT count(*) FROM job_export", Integer.class));
        // Même réponse qu'un dossier inexistant.
        mvc.perform(post("/api/v1/exports/dossiers/" + UUID.randomUUID())
                        .with(user(identites.loadUserByUsername(Comptes.SANS_ROLE))))
                .andExpect(status().isNotFound());
    }
}
