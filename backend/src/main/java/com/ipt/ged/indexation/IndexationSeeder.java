package com.ipt.ged.indexation;

import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.index.IndexField;
import com.ipt.ged.index.IndexRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Données de démonstration : attribue des valeurs d'index aux documents déjà
 * déposés, afin que la recherche par index renvoie des résultats parlants.
 * Ne fait rien si des valeurs existent déjà, ou s'il n'y a aucun document.
 */
@Component
@Order(9)
// Jeu de DÉMONSTRATION, profil dev uniquement : ce sont des référentiels
// métier, qui ne se créent en production que depuis l'interface (dossier
// technique §4.2.1). Ni changeset ni amorçage automatique en prod.
@Profile("dev")
public class IndexationSeeder implements CommandLineRunner {

    private final UploadDocumentRepository documents;
    private final IndexRepository indices;
    private final DocumentIndexRepository valeurs;

    public IndexationSeeder(UploadDocumentRepository documents, IndexRepository indices,
                            DocumentIndexRepository valeurs) {
        this.documents = documents;
        this.indices = indices;
        this.valeurs = valeurs;
    }

    @Override
    public void run(String... args) {
        if (valeurs.count() > 0) return;

        List<UploadDocument> actifs = documents.findByDeletedFalseOrderByIdDesc();
        if (actifs.isEmpty()) return;

        Map<String, IndexField> parCode = indices.findByDeletedFalseOrderByIdAsc().stream()
                .collect(Collectors.toMap(IndexField::getCode, Function.identity(), (a, b) -> a));

        // Jeux de valeurs distincts, pour que le filtrage et le groupage se voient
        String[][] jeux = {
            {"2026001", "2026-01-15", "ACME Distribution", "Haute"},
            {"2026002", "2026-02-03", "Atlas Fournitures", "Normale"},
            {"2026003", "2026-03-21", "ACME Distribution", "Urgente"},
        };

        int i = 0;
        for (UploadDocument doc : actifs) {
            String[] jeu = jeux[i % jeux.length];
            poser(doc, parCode.get("IDX-NUMFACT"), jeu[0]);
            poser(doc, parCode.get("IDX-DATEEMI"), jeu[1]);
            poser(doc, parCode.get("IDX-FOURN"),   jeu[2]);
            poser(doc, parCode.get("IDX-PRIO"),    jeu[3]);
            i++;
        }
    }

    private void poser(UploadDocument doc, IndexField champ, String valeur) {
        if (champ == null) return;
        valeurs.save(new DocumentIndex(doc, champ, valeur));
    }
}
