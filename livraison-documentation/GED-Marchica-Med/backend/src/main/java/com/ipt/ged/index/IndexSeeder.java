package com.ipt.ged.index;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Données de démonstration : quelques index (champs de métadonnées) courants.
 * S'exécute sur base vide, hors profil de test.
 */
@Component
@Order(5)
@Profile("!test")
public class IndexSeeder implements CommandLineRunner {

    private final IndexRepository repo;

    public IndexSeeder(IndexRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.count() > 0) {
            return;
        }
        repo.save(index("IDX-NUMFACT", "Numéro de facture", IndexFieldType.NOMBRE, null, true, true, false));
        repo.save(index("IDX-DATEEMI", "Date d'émission", IndexFieldType.DATE, null, true, true, false));
        repo.save(index("IDX-FOURN", "Fournisseur", IndexFieldType.TEXTE, null, false, true, true));

        IndexField priorite = index("IDX-PRIO", "Priorité", IndexFieldType.LISTE, "Basse,Normale,Haute,Urgente", false, true, true);
        priorite.setValeurParDefaut("Normale");
        repo.save(priorite);
    }

    private IndexField index(String code, String nom, IndexFieldType type, String valeurs,
                             boolean obligatoire, boolean recherche, boolean groupage) {
        IndexField x = new IndexField(code, nom);
        x.setFieldType(type);
        x.setValeurs(valeurs);
        x.setObligatoire(obligatoire);
        x.setIndexePourRecherche(recherche);
        x.setIndexDeGroupage(groupage);
        return x;
    }
}
