package com.ipt.ged.corpus;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.ipt.ged.corpus.Donnees.*;
import static com.ipt.ged.corpus.Page.Align.*;

/**
 * Mise en page de chaque type de document. Une page est dessinée en connaissant
 * son rang dans le document : la première porte l'en-tête, la dernière les
 * signatures et cachets, les pages intermédiaires la suite du corps.
 */
final class Modeles {

    private Modeles() {
    }

    private static final Color BLEU = new Color(30, 60, 170);
    private static final Color VIOLET = new Color(95, 45, 140);
    private static final Color ROUGE = new Color(190, 30, 40);

    static void dessiner(Page p, Dossier d, int num, int nb) {
        if (chance(p.r, 0.18)) {
            p.transparenceVerso(d.paragraphe(PHRASES_LETTRE, 8, 14));
        }
        boolean premiere = num == 1;
        boolean derniere = num == nb;
        if (!premiere) {
            suite(p, d, num, nb);
        }
        switch (d.type) {
            case COURRIER -> courrier(p, d, premiere, derniere, nb);
            case FACTURE -> facture(p, d, premiere, derniere, nb);
            case BON_COMMANDE -> bonCommande(p, d, premiere, derniere, nb);
            case CONTRAT -> contrat(p, d, premiere, derniere);
            case PROCES_VERBAL -> procesVerbal(p, d, premiere, derniere);
            case ATTESTATION -> attestation(p, d);
            case NOTE_SERVICE -> noteService(p, d, premiere, derniere, nb);
            case BORDEREAU -> bordereau(p, d, premiere, derniere, nb);
            case FORMULAIRE -> formulaire(p, d, premiere, derniere);
        }
        p.reserve = 0;
        if (nb > 1) {
            double y0 = p.y;
            p.y = Page.HAUTEUR - p.marge * 0.7;
            String pied = chance(p.r, 0.5) ? "Page " + num + "/" + nb : "- " + num + " -";
            p.reserve = -p.marge;
            p.texte(pied, p.petit, CENTRE);
            p.reserve = 0;
            p.y = y0;
        } else if (!d.administration && chance(p.r, 0.5)) {
            piedLegal(p, d);
        }
    }

    // ---------------------------------------------------------- éléments communs

    private static void enTete(Page p, Dossier d) {
        double y0 = p.y;
        double x = p.marge;
        if (chance(p.r, 0.5)) {
            p.logo(p.marge, p.y, 36);
            x += 44;
        }
        double l = p.largeur() * 0.62;
        Font nom = p.gras.deriveFont(p.gras.getSize2D() + 2f);
        p.texte(d.administration ? d.emetteur.toUpperCase(FR) : d.emetteur, nom, GAUCHE, x, l);
        p.texte(d.emetteurService, p.petit, GAUCHE, x, l);
        p.texte(d.emetteurAdresse, p.petit, GAUCHE, x, l);
        p.texte("Tél. : " + d.telephone + " – " + d.email, p.petit, GAUCHE, x, l);
        p.y = Math.max(p.y, y0 + 40) + 4;
        if (chance(p.r, 0.6)) {
            p.filet(0.6 + p.r.nextDouble());
        }
        p.espace(8);
    }

    private static void suite(Page p, Dossier d, int num, int nb) {
        if (chance(p.r, 0.5)) {
            p.texte("Réf. : " + d.reference + " (suite)", p.petit, DROITE);
            p.espace(6);
        } else {
            p.espace(10);
        }
    }

    private static void piedLegal(Page p, Dossier d) {
        double y0 = p.y;
        p.y = Page.HAUTEUR - p.marge * 0.75;
        p.reserve = -p.marge;
        p.texte("RC " + d.rc + " – IF " + d.identifiantFiscal + " – ICE " + d.ice + " – Patente "
                + chiffres(p.r, 8) + " – CNSS " + chiffres(p.r, 7), p.petit.deriveFont(p.petit.getSize2D() - 0.5f), CENTRE);
        p.reserve = 0;
        p.y = y0;
    }

    private static void corps(Page p, Dossier d, String[] pool, int maxPar, boolean main) {
        for (int i = 0; i < maxPar; i++) {
            double estime = main ? p.taillePlume * 1.3 * 2 : Page.interligne(p.corps) * 2;
            if (!p.place(estime)) {
                break;
            }
            String par = d.paragraphe(pool, 2, 4);
            if (main) {
                p.manuscritFlux(par);
            } else {
                p.paragraphe(par);
            }
        }
    }

    /** Nombre de paragraphes : quelques-uns sur une page finale, la page pleine sinon. */
    private static int paragraphes(Page p, boolean derniere) {
        return derniere ? entre(p.r, 2, 5) : 99;
    }

    private static void signer(Page p, Dossier d, boolean tamponRond) {
        double x = p.marge + p.largeur() * 0.5;
        double l = p.largeur() * 0.5;
        p.espace(6);
        p.texte("Le " + d.fonctionSignataire, p.gras, CENTRE, x, l);
        double ys = p.y;
        p.signature(x + l * 0.25, ys, l * 0.5, 34);
        p.y = ys + 38;
        p.texte(d.signataire, p.corps, CENTRE, x, l);
        if (tamponRond) {
            tamponOrganisme(p, d, x + l * 0.35 + p.r.nextGaussian() * 15, ys + 10 + p.r.nextGaussian() * 8);
        }
    }

    private static void signaturesDoubles(Page p, String gauche, String droite) {
        double y0 = p.y;
        double l = p.largeur() / 2;
        p.texte(gauche, p.gras, CENTRE, p.marge, l);
        p.y = y0;
        p.texte(droite, p.gras, CENTRE, p.marge + l, l);
        double ys = p.y + 2;
        if (chance(p.r, 0.9)) {
            p.signature(p.marge + l * 0.25, ys, l * 0.5, 32);
        }
        if (chance(p.r, 0.9)) {
            p.signature(p.marge + l * 1.25, ys, l * 0.5, 32);
        }
        p.y = ys + 40;
    }

    private static void tamponOrganisme(Page p, Dossier d, double cx, double cy) {
        String nom = d.emetteur.toUpperCase(FR);
        if (nom.length() > 38) {
            nom = nom.substring(0, 38).trim();
        }
        List<String> l = new ArrayList<>();
        l.add(nom + " * " + d.ville.toUpperCase(FR));
        if (d.administration) {
            l.add("LE DIRECTEUR");
        } else {
            l.add("ICE");
            l.add(d.ice);
        }
        p.tampon(Page.Forme.ROND, l, cx, cy, chance(p.r, 0.6) ? BLEU : VIOLET, p.r.nextGaussian() * 12);
    }

    /** Tampon d'arrivée du bureau d'ordre ; date et numéro parfois remplis à la main. */
    private static void arrivee(Page p, Dossier d) {
        double cx = Page.LARGEUR - p.marge - 75 + p.r.nextGaussian() * 10;
        double cy = p.marge * 0.6 + 40 + p.r.nextDouble() * 90;
        String num = d.date.getYear() + "/" + chiffres(p.r, 5);
        String date = dateCourte(d.date.plusDays(entre(p.r, 1, 6)));
        boolean main = chance(p.r, 0.45);
        // Rempli à la main : les libellés sont complétés d'espaces pour laisser la place à l'écriture.
        String blanc = " ".repeat(26);
        List<String> l = main
                ? List.of("BUREAU D'ORDRE", "COURRIER ARRIVÉE", "Le :" + blanc, "N° :" + blanc)
                : List.of("BUREAU D'ORDRE", "COURRIER ARRIVÉE", "Le : " + date, "N° : " + num);
        double angle = main ? p.r.nextGaussian() * 1.2 : p.r.nextGaussian() * 8;
        p.tampon(Page.Forme.RECTANGLE, l, cx, cy, chance(p.r, 0.5) ? BLEU : VIOLET, angle);
        if (main) {
            Font ft = new Font(Font.SANS_SERIF, Font.BOLD, 9);
            double il = Page.interligne(ft);
            double lw = 0;
            for (String s : l) {
                lw = Math.max(lw, p.larg(s, ft));
            }
            lw += 20;
            double haut = 4 * il + 14;
            double x = cx - lw / 2 + (lw - p.larg(l.get(2), ft)) / 2 + p.larg("Le : ", ft);
            float t = p.taillePlume * 0.85f;
            double base = cy - haut / 2 + 7 + ft.getSize2D() + 2 * il;
            p.manuscrit(date, x, base - t * 0.85, lw * 0.6, t);
            p.manuscrit(num, x, base + il - t * 0.85, lw * 0.6, t);
        }
    }

    private static void annotation(Page p, Dossier d) {
        String a = d.remplir(un(p.r, ANNOTATIONS));
        double x = p.marge + p.r.nextDouble() * 60;
        double y = 12 + p.r.nextDouble() * Math.max(10, p.marge * 0.5);
        p.manuscrit(a, x, y, p.largeur() * 0.8, p.taillePlume);
        if (chance(p.r, 0.5)) {
            p.signature(x + p.largeur() * 0.3, y + p.taillePlume * 0.5, 40, 18);
        }
    }

    // ------------------------------------------------------------------ types

    private static void courrier(Page p, Dossier d, boolean premiere, boolean derniere, int nb) {
        boolean main = d.lettreManuscrite;
        if (premiere) {
            if (main) {
                double y0 = p.y + 10;
                p.y = y0 + p.manuscrit(d.civiliteInteresse + " " + d.interesse, p.marge, y0, 260, p.taillePlume);
                p.y += p.manuscrit(adresse(p.r, d.ville), p.marge, p.y, 260, p.taillePlume);
                p.y += p.manuscrit("GSM : " + gsm(p.r), p.marge, p.y, 260, p.taillePlume);
                p.manuscrit(d.ville + ", le " + dateCourte(d.date), p.marge + p.largeur() * 0.55, y0, 200, p.taillePlume);
                p.espace(14);
            } else {
                enTete(p, d);
                p.colonnes(List.of("Réf. : " + d.reference),
                        List.of(d.ville + ", le " + dateLongue(d.date)), p.corps);
                p.espace(12);
            }
            double x = p.marge + p.largeur() * 0.48;
            double l = p.largeur() * 0.52;
            p.texte("À", p.corps, GAUCHE, x, l);
            p.texte("Monsieur le " + un(p.r, FONCTIONS), p.gras, GAUCHE, x, l);
            p.texte(d.destinataire, p.corps, GAUCHE, x, l);
            p.texte(d.destinataireAdresse, p.corps, GAUCHE, x, l);
            p.espace(14);
            if (main) {
                p.manuscritFlux("Objet : " + d.objet);
            } else {
                p.texte("Objet : " + d.objet, p.gras, GAUCHE);
                if (chance(p.r, 0.4)) {
                    p.texte("P.J. : " + entre(p.r, 1, 6) + " pièces", p.corps, GAUCHE);
                }
            }
            p.espace(10);
            if (!main) {
                p.paragraphe(un(p.r, FORMULES_APPEL));
            }
        }
        if (derniere) {
            p.reserve = 120;
        }
        corps(p, d, PHRASES_LETTRE, paragraphes(p, derniere), main);
        p.reserve = 0;
        if (derniere) {
            if (main) {
                p.manuscritFlux(un(p.r, FORMULES_POLITESSE));
                p.signature(p.marge + p.largeur() * 0.6, p.y, 110, 36);
                p.y += 40;
            } else {
                p.paragraphe(un(p.r, FORMULES_POLITESSE));
                signer(p, d, chance(p.r, 0.5));
            }
        }
        if (premiere && chance(p.r, 0.75)) {
            arrivee(p, d);
        }
        if (chance(p.r, 0.35)) {
            annotation(p, d);
        }
    }

    private static void facture(Page p, Dossier d, boolean premiere, boolean derniere, int nb) {
        if (premiere) {
            enTete(p, d);
            p.texte("FACTURE N° F-" + d.date.getYear() + "-" + chiffres(p.r, 4), p.titre,
                    chance(p.r, 0.5) ? CENTRE : GAUCHE);
            p.espace(8);
            p.colonnes(List.of("Client : " + d.destinataire, d.destinataireAdresse, "ICE client : 00" + chiffres(p.r, 13)),
                    List.of("Date : " + dateCourte(d.date), "Échéance : " + dateCourte(d.date.plusDays(60)),
                            "Bon de commande : BC-" + d.date.getYear() + "-" + chiffres(p.r, 3)), p.corps);
            p.espace(10);
        }
        boolean ref = chance(p.r, 0.4);
        String[] ent = ref ? new String[]{"Réf.", "Désignation", "Qté", "P.U. HT", "Montant HT"}
                : new String[]{"Désignation", "Qté", "P.U. HT", "Montant HT"};
        double[] poids = ref ? new double[]{1.2, 4.5, 0.9, 1.6, 1.8} : new double[]{5, 1, 1.7, 1.9};
        int n = derniere ? entre(p.r, 3, 12) : 40;
        List<String[]> rg = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int q = entre(p.r, 1, 50);
            long pu = entre(p.r, 15, 9_000) * 100L + (chance(p.r, 0.3) ? entre(p.r, 1, 99) : 0);
            long mt = pu * q;
            d.totalHtCentimes += mt;
            String des = un(p.r, ARTICLES);
            rg.add(ref ? new String[]{"A" + chiffres(p.r, 4), des, String.valueOf(q), montant(pu), montant(mt)}
                    : new String[]{des, String.valueOf(q), montant(pu), montant(mt)});
        }
        if (derniere) {
            p.reserve = 150;
        }
        p.tableau(ent, rg, poids, Set.of(), -1);
        p.reserve = 0;
        if (derniere) {
            long ht = d.totalHtCentimes;
            long tva = Math.round(ht * 0.2);
            long ttc = ht + tva;
            double x = p.marge + p.largeur() * 0.5;
            double l = p.largeur() * 0.5;
            p.texte("Total HT : " + montant(ht), p.corps, DROITE, x, l);
            p.texte("TVA 20 % : " + montant(tva), p.corps, DROITE, x, l);
            p.texte("Total TTC : " + montant(ttc), p.gras, DROITE, x, l);
            p.espace(8);
            String lettres = enLettres(ttc / 100) + " dirhams";
            if (ttc % 100 > 0) {
                lettres += " et " + enLettres(ttc % 100) + " centimes";
            }
            p.paragraphe("Arrêtée la présente facture à la somme de : "
                    + (chance(p.r, 0.5) ? lettres.toUpperCase(FR) : lettres) + " TTC.");
            p.paragraphe("Mode de règlement : virement bancaire – RIB : " + chiffres(p.r, 3) + " " + chiffres(p.r, 3)
                    + " " + chiffres(p.r, 16) + " " + chiffres(p.r, 2));
            double ys = p.y;
            if (chance(p.r, 0.65)) {
                tamponOrganisme(p, d, Page.LARGEUR - p.marge - 90, ys + 40);
            }
            if (chance(p.r, 0.2)) {
                p.tampon(Page.Forme.MOT, List.of("PAYÉ"), p.marge + 120, ys + 30, ROUGE, p.r.nextGaussian() * 15);
            }
            if (chance(p.r, 0.3)) {
                p.manuscrit("Bon pour paiement le " + dateCourte(d.date.plusDays(entre(p.r, 10, 40))),
                        p.marge, ys + 70, 220, p.taillePlume);
                p.signature(p.marge + 30, ys + 70 + p.taillePlume * 1.3, 80, 26);
            }
        }
        if (premiere && chance(p.r, 0.35)) {
            arrivee(p, d);
        }
    }

    private static void bonCommande(Page p, Dossier d, boolean premiere, boolean derniere, int nb) {
        if (premiere) {
            enTete(p, d);
            p.texte("BON DE COMMANDE N° BC-" + d.date.getYear() + "-" + chiffres(p.r, 3), p.titre, CENTRE);
            p.espace(8);
            p.colonnes(List.of("Fournisseur : " + d.destinataire, d.destinataireAdresse),
                    List.of("Date : " + dateCourte(d.date),
                            "Imputation : chapitre " + entre(p.r, 1, 9) + "." + entre(p.r, 1, 9) + " – article " + entre(p.r, 10, 90)),
                    p.corps);
            p.espace(10);
        }
        int n = derniere ? entre(p.r, 3, 10) : 40;
        List<String[]> rg = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rg.add(new String[]{String.valueOf(d.rangee++), "R-" + chiffres(p.r, 5), un(p.r, ARTICLES),
                    String.valueOf(entre(p.r, 1, 200)), un(p.r, UNITES), montant(entre(p.r, 20, 6000) * 100L)});
        }
        if (derniere) {
            p.reserve = 130;
        }
        p.tableau(new String[]{"N°", "Référence", "Désignation", "Qté", "Unité", "P.U. HT"}, rg,
                new double[]{0.6, 1.3, 4.2, 0.8, 1, 1.5}, Set.of(), -1);
        p.reserve = 0;
        if (derniere) {
            p.texte("Lieu de livraison : " + d.emetteurAdresse, p.corps, GAUCHE);
            p.texte("Délai de livraison : " + entre(p.r, 7, 45) + " jours à compter de la réception du présent bon.",
                    p.corps, GAUCHE);
            p.espace(12);
            double ys = p.y;
            signaturesDoubles(p, "Le Demandeur", "Le Responsable des Achats");
            if (chance(p.r, 0.6)) {
                tamponOrganisme(p, d, Page.LARGEUR - p.marge - 110, ys + 30);
            }
        }
        if (premiere && chance(p.r, 0.3)) {
            arrivee(p, d);
        }
    }

    private static void contrat(Page p, Dossier d, boolean premiere, boolean derniere) {
        if (premiere) {
            p.espace(20);
            p.texte("CONTRAT DE " + d.typeContrat, p.titre, CENTRE);
            p.texte("N° " + d.reference, p.gras, CENTRE);
            p.espace(16);
            p.texte("ENTRE LES SOUSSIGNÉS :", p.gras, GAUCHE);
            p.espace(4);
            String partie1 = d.administration ? d.destinataire : d.emetteur;
            String partie2 = d.administration ? d.emetteur : d.destinataire;
            p.paragraphe(partie1 + ", au capital de " + montant(entre(p.r, 100, 5000) * 1000_00L)
                    + " dirhams, immatriculée au registre de commerce de " + d.ville + " sous le n° " + d.rc
                    + ", représentée par " + civilite(p.r) + " " + d.signataire + ", en qualité de "
                    + d.fonctionSignataire + ", ci-après dénommée « le Prestataire »,");
            p.texte("D'UNE PART,", p.gras, DROITE);
            p.texte("ET", p.gras, CENTRE);
            p.paragraphe(partie2 + ", sise " + d.destinataireAdresse + ", représentée par " + civilite(p.r) + " "
                    + d.interesse + ", ci-après dénommé « le Client »,");
            p.texte("D'AUTRE PART,", p.gras, DROITE);
            p.espace(6);
            p.texte("IL A ÉTÉ CONVENU ET ARRÊTÉ CE QUI SUIT :", p.gras, CENTRE);
            p.espace(8);
        }
        if (derniere) {
            p.reserve = 160;
        }
        int max = derniere ? entre(p.r, 1, 4) : 99;
        for (int i = 0; i < max && p.place(Page.interligne(p.corps) * 3); i++) {
            String titre = "Article " + d.article + " – "
                    + TITRES_ARTICLES[(d.article - 1) % TITRES_ARTICLES.length];
            d.article++;
            p.texte(titre, p.gras, GAUCHE);
            p.espace(2);
            p.paragraphe(d.paragraphe(PHRASES_CONTRAT, 1, 3));
        }
        p.reserve = 0;
        if (derniere) {
            p.espace(6);
            p.paragraphe("Fait à " + d.ville + ", le " + dateLongue(d.date) + ", en deux exemplaires originaux.");
            p.espace(4);
            double y0 = p.y;
            double l = p.largeur() / 2;
            p.texte("Pour le Client", p.gras, CENTRE, p.marge, l);
            p.y = y0;
            p.texte("Pour le Prestataire", p.gras, CENTRE, p.marge + l, l);
            double ys = p.y + 2;
            if (chance(p.r, 0.7)) {
                p.manuscrit("Lu et approuvé", p.marge + l * 0.25, ys, l * 0.7, p.taillePlume);
                p.manuscrit("Lu et approuvé", p.marge + l * 1.25, ys + p.r.nextGaussian() * 3, l * 0.7, p.taillePlume);
                ys += p.taillePlume * 1.4;
            }
            p.signature(p.marge + l * 0.25, ys, l * 0.5, 34);
            p.signature(p.marge + l * 1.25, ys, l * 0.5, 34);
            if (chance(p.r, 0.6)) {
                tamponOrganisme(p, d, p.marge + l * 1.5 + p.r.nextGaussian() * 12, ys + 18);
            }
            p.y = ys + 40;
        } else if (chance(p.r, 0.5)) {
            String paraphe = d.signataire.substring(0, 1) + "." + d.interesse.substring(0, 1) + ".";
            p.manuscrit(paraphe, Page.LARGEUR - p.marge - 40, Page.HAUTEUR - p.marge * 0.9 - p.taillePlume * 1.4,
                    40, p.taillePlume);
        }
    }

    private static void procesVerbal(Page p, Dossier d, boolean premiere, boolean derniere) {
        if (premiere) {
            enTete(p, d);
            p.texte("PROCÈS-VERBAL", p.titre, CENTRE);
            p.texte("de la réunion du comité de suivi du " + dateLongue(d.date), p.gras, CENTRE);
            p.espace(10);
            p.texte("Lieu : salle de réunion de la " + d.emetteurService, p.corps, GAUCHE);
            p.texte("Heure : " + entre(p.r, 9, 15) + " h " + un(p.r, new String[]{"00", "15", "30"}), p.corps, GAUCHE);
            p.espace(8);
            p.texte("Étaient présents :", p.gras, GAUCHE);
            List<String[]> pres = new ArrayList<>();
            int n = entre(p.r, 3, 7);
            for (int i = 0; i < n; i++) {
                pres.add(new String[]{nomPersonne(p.r), un(p.r, FONCTIONS), ""});
            }
            p.tableau(new String[]{"Nom et prénom", "Fonction", "Émargement"}, pres, new double[]{3, 3.5, 2}, Set.of(), 2);
            p.texte("Ordre du jour :", p.gras, GAUCHE);
            int k = entre(p.r, 3, 5);
            for (int i = 0; i < k; i++) {
                p.texte((i + 1) + ". " + ORDRES_DU_JOUR[i], p.corps, GAUCHE, p.marge + 14, p.largeur() - 14);
            }
            p.espace(8);
            p.texte("Déroulement de la réunion :", p.gras, GAUCHE);
            p.espace(2);
        }
        if (derniere) {
            p.reserve = 170;
        }
        corps(p, d, PHRASES_PV, derniere ? entre(p.r, 1, 3) : 99, false);
        p.reserve = 0;
        if (derniere) {
            p.texte("Décisions arrêtées :", p.gras, GAUCHE);
            int k = entre(p.r, 2, 4);
            for (int i = 0; i < k; i++) {
                p.texte("– " + d.remplir(DECISIONS[(i + p.r.nextInt(DECISIONS.length)) % DECISIONS.length]),
                        p.corps, GAUCHE, p.marge + 10, p.largeur() - 10);
            }
            p.espace(6);
            p.paragraphe("L'ordre du jour étant épuisé, la séance a été levée à " + entre(p.r, 11, 17) + " h 30.");
            signer(p, d, chance(p.r, 0.4));
        }
    }

    private static void attestation(Page p, Dossier d) {
        enTete(p, d);
        p.espace(24);
        String titre = un(p.r, TITRES_ATTESTATION);
        p.texte(titre, p.titre.deriveFont(p.titre.getSize2D() + 2f), CENTRE);
        p.espace(26);
        String org = d.administration ? d.emetteur : "la " + d.emetteur;
        p.paragraphe("Je soussigné(e), " + d.signataire + ", " + d.fonctionSignataire + " de " + org
                + ", atteste par la présente que " + d.civiliteInteresse + " " + d.interesse
                + ", titulaire de la CIN n° " + cin(p.r) + ", "
                + switch (titre) {
                    case "ATTESTATION DE STAGE" -> "a effectué un stage au sein de la " + d.emetteurService
                            + " du " + dateCourte(d.date.minusDays(90)) + " au " + dateCourte(d.date) + ".";
                    case "ATTESTATION DE SALAIRE" -> "perçoit un salaire mensuel brut de " + montant(entre(p.r, 4000, 30000) * 100L)
                            + " dirhams.";
                    case "ATTESTATION DE BONNE EXÉCUTION", "ATTESTATION DE RÉCEPTION" -> "a exécuté les prestations objet du bon de commande n° BC-"
                            + d.date.getYear() + "-" + chiffres(p.r, 3) + " conformément aux prescriptions techniques.";
                    default -> "exerce au sein de notre établissement depuis le " + dateCourte(d.date.minusDays(entre(p.r, 200, 4000)))
                            + " en qualité de " + un(p.r, FONCTIONS) + ", matricule n° " + chiffres(p.r, 6) + ".";
                });
        p.espace(6);
        p.paragraphe("La présente attestation est délivrée à l'intéressé(e), sur sa demande, pour servir et valoir ce que de droit.");
        p.espace(20);
        p.texte("Fait à " + d.ville + ", le " + dateCourte(d.date), p.corps, DROITE);
        p.espace(10);
        signer(p, d, chance(p.r, 0.9));
        if (chance(p.r, 0.15)) {
            p.tampon(Page.Forme.MOT, List.of("COPIE CONFORME"), p.marge + 110, p.y + 30, ROUGE, p.r.nextGaussian() * 10);
        }
    }

    private static void noteService(Page p, Dossier d, boolean premiere, boolean derniere, int nb) {
        if (premiere) {
            enTete(p, d);
            p.texte("NOTE DE SERVICE N° " + d.numero + "/" + d.date.getYear(), p.titre, CENTRE);
            p.texte(d.ville + ", le " + dateLongue(d.date), p.corps, DROITE);
            p.espace(10);
            p.texte("Objet : " + d.objet, p.gras, GAUCHE);
            p.espace(8);
        }
        if (derniere) {
            p.reserve = 170;
        }
        corps(p, d, PHRASES_LETTRE, paragraphes(p, derniere), false);
        p.reserve = 0;
        if (derniere) {
            p.texte("Destinataires :", p.gras, GAUCHE);
            int k = entre(p.r, 2, 4);
            for (int i = 0; i < k; i++) {
                p.texte("– " + DESTINATAIRES_NOTE[(i + d.numero.length()) % DESTINATAIRES_NOTE.length], p.corps,
                        GAUCHE, p.marge + 10, p.largeur() - 10);
            }
            signer(p, d, chance(p.r, 0.3));
        }
        if (chance(p.r, 0.3)) {
            annotation(p, d);
        }
        if (chance(p.r, 0.2)) {
            p.tampon(Page.Forme.MOT, List.of(un(p.r, new String[]{"COPIE", "VU", "ENREGISTRÉ"})),
                    Page.LARGEUR - p.marge - 60, p.marge + 30, chance(p.r, 0.5) ? ROUGE : BLEU, p.r.nextGaussian() * 12);
        }
    }

    private static void bordereau(Page p, Dossier d, boolean premiere, boolean derniere, int nb) {
        if (premiere) {
            enTete(p, d);
            p.texte("BORDEREAU D'ENVOI N° " + d.numero, p.titre, CENTRE);
            p.espace(8);
            p.colonnes(List.of("Expéditeur : " + d.emetteurService, d.ville + ", le " + dateCourte(d.date)),
                    List.of("Destinataire :", d.destinataire), p.corps);
            p.espace(10);
        }
        boolean main = chance(p.r, 0.55);
        int n = derniere ? entre(p.r, 3, 9) : 30;
        List<String[]> rg = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rg.add(new String[]{String.valueOf(d.rangee++), d.remplir(un(p.r, PIECES)),
                    String.valueOf(entre(p.r, 1, 5)), chance(p.r, 0.7) ? un(p.r, OBSERVATIONS) : ""});
        }
        if (derniere) {
            p.reserve = 120;
        }
        p.tableau(new String[]{"N°", "Désignation des pièces", "Nombre", "Observations"}, rg,
                new double[]{0.6, 5, 1.1, 2.6}, main ? Set.of(2, 3) : Set.of(), -1);
        p.reserve = 0;
        if (derniere) {
            double y0 = p.y;
            p.texte("Pièces reçues le :", p.corps, GAUCHE);
            if (chance(p.r, 0.5)) {
                p.manuscrit(dateCourte(d.date.plusDays(entre(p.r, 1, 8))), p.marge + p.larg("Pièces reçues le : ", p.corps),
                        y0 - p.taillePlume * 0.35, 150, p.taillePlume);
            }
            p.espace(12);
            signaturesDoubles(p, "L'Expéditeur", "Le Destinataire");
        }
        if (premiere && chance(p.r, 0.6)) {
            arrivee(p, d);
        }
    }

    private static void formulaire(Page p, Dossier d, boolean premiere, boolean derniere) {
        if (premiere) {
            if (chance(p.r, 0.6)) {
                enTete(p, d);
            } else {
                p.espace(20);
            }
            p.texte(un(p.r, DEMANDES_FORMULAIRE), p.titre, CENTRE);
            p.espace(16);
            champ(p, "Nom et prénom :", d.interesse);
            champ(p, "N° CIN :", cin(p.r));
            champ(p, "Adresse :", adresse(p.r, d.ville));
            champ(p, "Téléphone :", gsm(p.r));
            champ(p, "Service / Organisme :", un(p.r, SERVICES));
            champ(p, "Date de la demande :", dateCourte(d.date));
            p.espace(8);
            p.texte("Objet de la demande :", p.gras, GAUCHE);
            p.espace(4);
        }
        if (derniere) {
            p.reserve = 150;
        }
        double y0 = p.y;
        String texte = d.paragraphe(PHRASES_MANUSCRITES, derniere ? 2 : 5, derniere ? 5 : 9);
        double il = p.taillePlume * 1.3;
        int nl = p.couper(texte, p.plume.deriveFont(p.taillePlume), (p.largeur() - 12) * 0.92).size() + 1;
        g(p).setColor(new Color(190, 190, 200));
        g(p).setStroke(new BasicStroke(0.4f));
        for (double yy = y0 + p.taillePlume * 1.05; yy < Math.min(p.bas(), y0 + il * nl); yy += il) {
            g(p).draw(new Line2D.Double(p.marge, yy, Page.LARGEUR - p.marge, yy));
        }
        p.interligneImpose = il;
        p.manuscritFlux(texte);
        p.interligneImpose = 0;
        p.reserve = 0;
        if (derniere) {
            p.espace(10);
            double ys = p.y;
            p.texte("Signature du demandeur :", p.gras, GAUCHE, p.marge + p.largeur() * 0.5, p.largeur() * 0.5);
            p.signature(p.marge + p.largeur() * 0.6, p.y, 110, 34);
            p.y = ys + 56;
            double yc = p.y;
            p.texte("Cadre réservé à l'administration", p.petit, GAUCHE, p.marge + 6, p.largeur());
            g(p).setColor(p.encre);
            g(p).setStroke(new BasicStroke(0.6f));
            double hc = Math.min(80, p.bas() - yc + 10);
            g(p).draw(new java.awt.geom.Rectangle2D.Double(p.marge, yc - 2, p.largeur(), hc));
            if (chance(p.r, 0.5)) {
                arrivee(p, d);
            }
            if (chance(p.r, 0.5)) {
                p.manuscrit(d.remplir(un(p.r, ANNOTATIONS)), p.marge + 10, yc + 16, p.largeur() * 0.5, p.taillePlume);
            }
        }
    }

    private static void champ(Page p, String libelle, String valeur) {
        double y0 = p.y;
        p.texte(libelle, p.gras, GAUCHE);
        double lw = p.larg(libelle, p.gras);
        double base = y0 + p.gras.getSize2D() + 2;
        g(p).setColor(new Color(120, 120, 120));
        g(p).setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{1f, 2f}, 0f));
        g(p).draw(new Line2D.Double(p.marge + lw + 4, base, Page.LARGEUR - p.marge, base));
        p.manuscrit(valeur, p.marge + lw + 10, y0 - p.taillePlume * 0.4, p.largeur() - lw - 12, p.taillePlume);
        p.y = Math.max(p.y, y0 + p.taillePlume * 1.2) + 8;
    }

    private static java.awt.Graphics2D g(Page p) {
        return p.g;
    }
}
