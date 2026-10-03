package com.ipt.ged.corpus;

import java.time.LocalDate;
import java.util.Random;

import static com.ipt.ged.corpus.Donnees.*;

/**
 * Contexte d'un document : émetteur, destinataire, références, dates. Partagé
 * par toutes les pages du document pour qu'elles restent cohérentes entre elles.
 */
final class Dossier {

    final TypeDoc type;
    final Random r;
    final boolean administration;
    final String emetteur;
    final String emetteurService;
    final String ville;
    final String emetteurAdresse;
    final String telephone;
    final String email;
    final String ice;
    final String rc;
    final String identifiantFiscal;
    final String destinataire;
    final String destinataireAdresse;
    final LocalDate date;
    final String reference;
    final String numero;
    final String objet;
    final String signataire;
    final String fonctionSignataire;
    final String interesse;
    final String civiliteInteresse;
    final boolean lettreManuscrite;
    final String typeContrat;
    int article = 1;
    int rangee = 1;
    long totalHtCentimes;

    Dossier(TypeDoc type, Random r) {
        this.type = type;
        this.r = r;
        administration = switch (type) {
            case FACTURE, BON_COMMANDE, CONTRAT -> chance(r, 0.25);
            case NOTE_SERVICE, PROCES_VERBAL, FORMULAIRE -> true;
            default -> chance(r, 0.6);
        };
        ville = un(r, VILLES);
        String soc = societe(r);
        emetteur = administration ? "Office Régional des Services Administratifs de " + ville : soc;
        emetteurService = administration ? un(r, SERVICES) : un(r, SOC_ACTIVITE) + " – Études – Réalisation";
        emetteurAdresse = adresse(r, ville);
        telephone = telephone(r);
        String dom = un(r, SOC_NOM).toLowerCase(FR).replace(' ', '-').replace("é", "e").replace("è", "e")
                .replace("ï", "i").replace("â", "a");
        email = (administration ? "bo." : "contact@") + dom + (administration ? "@admin-exemple.ma" : ".ma");
        ice = "00" + chiffres(r, 13);
        rc = chiffres(r, 5);
        identifiantFiscal = chiffres(r, 8);
        destinataire = administration ? societe(r) : "Office Régional des Services Administratifs de " + un(r, VILLES);
        destinataireAdresse = adresse(r, un(r, VILLES));
        date = Donnees.date(r);
        reference = entre(r, 100, 9999) + "/" + date.getYear() + "/" + un(r, new String[]{"DAA", "DRH", "DSI", "BO", "DF", "SJ"});
        numero = String.valueOf(entre(r, 1, 480));
        objet = remplir(un(r, OBJETS_COURRIER));
        signataire = nomPersonne(r);
        fonctionSignataire = un(r, FONCTIONS);
        interesse = nomPersonne(r);
        civiliteInteresse = civilite(r);
        lettreManuscrite = type == TypeDoc.COURRIER && chance(r, 0.22);
        typeContrat = un(r, TYPES_CONTRAT);
    }

    String remplir(String modele) {
        String s = modele;
        while (s.contains("{")) {
            int a = s.indexOf('{');
            int b = s.indexOf('}', a);
            String cle = s.substring(a + 1, b);
            s = s.substring(0, a) + valeur(cle) + s.substring(b + 1);
        }
        return s;
    }

    private String valeur(String cle) {
        return switch (cle) {
            case "ref" -> reference;
            case "fact" -> "F-" + date.getYear() + "-" + chiffres(r, 4);
            case "bc" -> "BC-" + date.getYear() + "-" + chiffres(r, 3);
            case "marche" -> entre(r, 1, 60) + "/" + date.getYear() + "/" + un(r, new String[]{"DAA", "DSI", "DP"});
            case "date2" -> dateCourte(date.minusDays(entre(r, 5, 120)));
            case "date3" -> dateCourte(date.plusDays(entre(r, 5, 90)));
            case "delai" -> String.valueOf(un(r, new Integer[]{8, 10, 15, 30, 45, 60}));
            case "montant" -> montant(entre(r, 5_000, 2_500_000) * 100L + (chance(r, 0.4) ? entre(r, 1, 99) : 0));
            case "service" -> un(r, SERVICES);
            case "art" -> String.valueOf(entre(r, 2, 15));
            case "adresse" -> adresse(r, un(r, VILLES));
            case "fonction" -> un(r, FONCTIONS);
            case "annee" -> String.valueOf(date.getYear());
            case "duree" -> String.valueOf(un(r, new Integer[]{6, 12, 24, 36}));
            case "pourmille" -> String.valueOf(entre(r, 1, 3));
            case "ville" -> un(r, VILLES);
            case "annexe" -> String.valueOf(entre(r, 1, 3));
            case "pourcent" -> String.valueOf(entre(r, 15, 95));
            case "num" -> String.valueOf(entre(r, 1, 24));
            case "nom" -> un(r, NOMS);
            default -> cle;
        };
    }

    String paragraphe(String[] pool, int min, int max) {
        int n = entre(r, min, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(remplir(un(r, pool)));
        }
        return sb.toString();
    }
}
