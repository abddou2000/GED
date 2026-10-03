package com.ipt.ged.corpus;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/**
 * Réservoirs de vocabulaire et petites fonctions de tirage du générateur de corpus.
 *
 * <p>Toutes les entités (sociétés, personnes, numéros) sont fictives : elles
 * donnent au texte l'allure d'un courrier administratif (accents, chiffres,
 * sigles, montants) sans reprendre aucune donnée réelle.
 */
final class Donnees {

    private Donnees() {
    }

    static final Locale FR = Locale.FRANCE;

    static final String[] VILLES = {"Rabat", "Casablanca", "Fès", "Marrakech", "Tanger", "Agadir", "Oujda",
            "Meknès", "Kénitra", "Tétouan", "El Jadida", "Béni Mellal", "Safi", "Nador", "Settat", "Khouribga",
            "Errachidia", "Laâyoune", "Taza", "Salé"};

    static final String[] PRENOMS = {"Mohammed", "Fatima Zahra", "Youssef", "Khadija", "Hicham", "Nadia",
            "Karim", "Salma", "Omar", "Leïla", "Rachid", "Imane", "Abdelilah", "Souad", "Mehdi", "Ghizlane",
            "Abderrahim", "Naïma", "Hamza", "Meryem", "Driss", "Hajar", "Anouar", "Loubna", "Saïd", "Zineb",
            "Jaouad", "Asmae", "Brahim", "Houda"};

    static final String[] NOMS = {"Alami", "Bennani", "El Idrissi", "Tazi", "Chraïbi", "Berrada", "Lahlou",
            "Ouazzani", "Benjelloun", "Squalli", "Amrani", "El Fassi", "Kettani", "Bouzidi", "Naciri", "Sbai",
            "Benkirane", "El Ouardi", "Zniber", "Tahiri", "Mernissi", "Belhaj", "Rhazi", "Daoudi", "Hajji",
            "Essaïdi", "Filali", "Guessous", "Lamrani", "Benchekroun"};

    static final String[] FONCTIONS = {"Directeur Général", "Directrice des Ressources Humaines",
            "Chef du Service Administratif", "Responsable des Achats", "Chef de la Division Financière",
            "Directeur Régional", "Secrétaire Général", "Chef du Bureau d'Ordre", "Ingénieur d'État",
            "Technicien Principal", "Administrateur", "Chef de Projet", "Responsable Juridique",
            "Comptable", "Gestionnaire du Patrimoine", "Chef du Service Informatique"};

    static final String[] SOC_PREFIXE = {"Société", "Groupe", "Entreprise", "Cabinet", "Bureau d'Études",
            "Comptoir", "Établissements"};
    static final String[] SOC_NOM = {"Atlas", "Al Manar", "Nour", "Saïss", "Tafilalet", "Oum Errabia", "Rif",
            "Souss", "Bouregreg", "Andalous", "Majorelle", "Zénith", "Assafa", "Al Amal", "Ifrane", "Toubkal",
            "Dakhla", "Moulouya", "Sebou", "Draa"};
    static final String[] SOC_ACTIVITE = {"Travaux", "Ingénierie", "Distribution", "Conseil", "Équipements",
            "Fournitures de Bureau", "Informatique", "Transport", "Bâtiment", "Nettoyage", "Gardiennage",
            "Impression", "Études Techniques", "Mobilier"};
    static final String[] SOC_FORME = {"SARL", "SA", "SARL AU", "SNC"};

    static final String[] SERVICES = {"Direction des Affaires Administratives", "Division des Ressources Humaines",
            "Service du Courrier", "Direction des Systèmes d'Information", "Division du Budget et des Marchés",
            "Service des Affaires Juridiques", "Direction du Patrimoine", "Service de la Comptabilité",
            "Division de la Logistique", "Direction de la Communication", "Service des Archives"};

    static final String[] RUES = {"Avenue Mohammed V", "Boulevard Zerktouni", "Rue Ibn Sina", "Avenue Hassan II",
            "Rue Al Massira", "Boulevard Abdelmoumen", "Rue de Fès", "Avenue des FAR", "Rue Moulay Ismaïl",
            "Boulevard Anfa", "Rue Oued Ziz", "Avenue Allal El Fassi", "Rue Tarik Ibn Ziad", "Quartier Industriel"};

    static final String[] ARTICLES = {"Ramette papier A4 80 g", "Classeur à levier dos 8 cm", "Toner imprimante noir",
            "Cartouche d'encre couleur", "Chemise cartonnée", "Boîte d'archives", "Agrafeuse de bureau",
            "Ordinateur portable 15 pouces", "Écran 24 pouces", "Clavier et souris sans fil", "Onduleur 1500 VA",
            "Disque dur externe 2 To", "Armoire métallique 2 portes", "Fauteuil de bureau ergonomique",
            "Bureau droit 160 x 80", "Câble réseau Cat. 6 (305 m)", "Commutateur 24 ports", "Scanner de production A3",
            "Prestation de maintenance trimestrielle", "Frais de déplacement", "Licence logiciel (1 an)",
            "Étiquettes code-barres", "Registre courrier arrivée", "Tampon dateur automatique",
            "Destructeur de documents", "Climatiseur mural 12 000 BTU", "Rayonnage d'archives 5 tablettes",
            "Formation des utilisateurs (jour)", "Main-d'œuvre technicien (heure)", "Peinture murale (m²)"};

    static final String[] UNITES = {"U", "Paquet", "Boîte", "Lot", "Forfait", "Jour", "Heure", "m²", "Rouleau"};

    static final String[] OBJETS_COURRIER = {"Demande de communication du dossier n° {ref}",
            "Réclamation relative à la facture n° {fact}", "Transmission des pièces justificatives",
            "Convocation à la réunion du comité de suivi", "Demande de prorogation du délai d'exécution",
            "Mise en demeure", "Demande d'attestation administrative", "Réponse à votre lettre du {date2}",
            "Notification d'attribution du marché n° {marche}", "Demande de mutation",
            "Envoi du rapport d'activité du premier trimestre", "Demande de congé administratif",
            "Rappel concernant le règlement de la facture n° {fact}", "Désignation d'un correspondant",
            "Demande de remboursement des frais de mission", "Résiliation de la convention n° {ref}",
            "Transmission du procès-verbal de réception provisoire", "Demande d'accès aux archives"};

    static final String[] PHRASES_LETTRE = {
            "J'ai l'honneur de vous faire parvenir, ci-joint, les documents relatifs au dossier cité en objet.",
            "Suite à votre correspondance du {date2}, je vous informe que votre demande a été examinée avec la plus grande attention.",
            "Je vous prie de bien vouloir me communiquer, dans un délai de {delai} jours, les pièces manquantes énumérées ci-après.",
            "À cet effet, je vous saurais gré de prendre les dispositions nécessaires afin de régulariser cette situation dans les meilleurs délais.",
            "Le montant restant dû s'élève à {montant} dirhams, conformément au décompte arrêté le {date2}.",
            "Je tiens à vous rappeler que le délai contractuel d'exécution expire le {date3}.",
            "Par ailleurs, une réunion de coordination se tiendra le {date3} à 10 heures au siège de la {service}.",
            "Les services concernés ont été saisis et une réponse vous sera adressée dès réception de leur avis.",
            "Je vous serais reconnaissant de bien vouloir désigner un interlocuteur chargé du suivi de ce dossier.",
            "Vous trouverez en annexe la copie du bon de commande n° {bc} ainsi que le bordereau de livraison correspondant.",
            "En l'absence de réponse de votre part avant le {date3}, nous nous verrons dans l'obligation d'appliquer les pénalités prévues à l'article {art} du contrat.",
            "Cette demande s'inscrit dans le cadre de la mise en œuvre du programme de dématérialisation engagé par notre établissement.",
            "Je reste à votre entière disposition pour tout complément d'information que vous jugeriez utile.",
            "Le dossier complet a été déposé au bureau d'ordre le {date2} sous le numéro {ref}.",
            "Il ressort de l'examen des pièces fournies que certaines attestations ne sont plus en cours de validité.",
            "Je vous informe que la commission a émis un avis favorable sous réserve de la production des justificatifs requis.",
            "Conformément aux dispositions en vigueur, toute réclamation doit être formulée par écrit dans un délai de {delai} jours.",
            "Nous avons bien reçu votre envoi du {date2} et nous vous en remercions.",
            "La livraison des fournitures est prévue à l'adresse suivante : {adresse}.",
            "Je vous demande de bien vouloir veiller au strict respect des consignes rappelées ci-dessus.",
            "Une copie de la présente est adressée pour information à M. le {fonction}.",
            "Les crédits nécessaires ont été inscrits au budget de l'exercice {annee}."};

    static final String[] FORMULES_APPEL = {"Monsieur,", "Madame,", "Monsieur le Directeur,", "Madame la Directrice,",
            "Messieurs,", "Monsieur le Président,"};

    static final String[] FORMULES_POLITESSE = {
            "Veuillez agréer, Monsieur, l'expression de mes salutations distinguées.",
            "Je vous prie d'agréer, Madame, Monsieur, l'assurance de ma considération distinguée.",
            "Dans l'attente de votre réponse, veuillez recevoir, Monsieur le Directeur, mes respectueuses salutations.",
            "Veuillez agréer, Messieurs, nos salutations les meilleures.",
            "Je vous prie de croire, Madame, à l'assurance de ma haute considération."};

    static final String[] TYPES_CONTRAT = {"PRESTATION DE SERVICES", "MAINTENANCE INFORMATIQUE", "BAIL À USAGE PROFESSIONNEL",
            "FOURNITURE ET INSTALLATION", "ASSISTANCE TECHNIQUE", "GARDIENNAGE ET SÉCURITÉ", "NETTOYAGE DES LOCAUX"};

    static final String[] TITRES_ARTICLES = {"Objet du contrat", "Durée", "Prix et modalités de paiement",
            "Obligations du Prestataire", "Obligations du Client", "Confidentialité", "Responsabilité et assurances",
            "Pénalités de retard", "Résiliation", "Force majeure", "Propriété des livrables", "Révision des prix",
            "Sous-traitance", "Réception des prestations", "Règlement des litiges", "Élection de domicile",
            "Documents contractuels", "Entrée en vigueur"};

    static final String[] PHRASES_CONTRAT = {
            "Le présent contrat a pour objet de définir les conditions dans lesquelles le Prestataire s'engage à exécuter les prestations décrites en annexe.",
            "Il est conclu pour une durée de {duree} mois à compter de la date de sa signature par les deux parties.",
            "Il pourra être renouvelé par tacite reconduction pour des périodes successives d'un an, sauf dénonciation par l'une des parties.",
            "Le prix global et forfaitaire est fixé à {montant} dirhams toutes taxes comprises.",
            "Les paiements interviendront dans un délai de {delai} jours à compter de la réception de la facture conforme.",
            "Le Prestataire s'engage à mettre à disposition un personnel qualifié et en nombre suffisant.",
            "Le Client s'engage à fournir au Prestataire toutes les informations nécessaires à la bonne exécution de sa mission.",
            "Chacune des parties s'oblige à tenir confidentielles toutes les informations dont elle aura connaissance à l'occasion du présent contrat.",
            "En cas de retard imputable au Prestataire, une pénalité de {pourmille} pour mille du montant du contrat sera appliquée par jour calendaire de retard.",
            "Le montant cumulé des pénalités ne pourra excéder dix pour cent (10 %) du montant total du contrat.",
            "En cas de manquement grave de l'une des parties, l'autre partie pourra résilier le contrat après mise en demeure restée sans effet pendant quinze (15) jours.",
            "Aucune des parties ne sera tenue responsable de l'inexécution de ses obligations résultant d'un cas de force majeure.",
            "Le Prestataire déclare être titulaire d'une police d'assurance couvrant sa responsabilité civile professionnelle.",
            "Les livrables produits dans le cadre du présent contrat deviennent la propriété exclusive du Client dès leur réception.",
            "Tout différend relatif à l'interprétation ou à l'exécution du présent contrat sera soumis au tribunal de commerce de {ville}.",
            "Pour l'exécution des présentes, les parties font élection de domicile à leurs adresses respectives indiquées ci-dessus.",
            "Le Prestataire ne peut sous-traiter tout ou partie des prestations sans l'accord préalable et écrit du Client.",
            "La réception des prestations fera l'objet d'un procès-verbal signé contradictoirement par les deux parties.",
            "Les prix sont révisables annuellement selon la formule figurant à l'annexe {annexe} du présent contrat.",
            "Le présent contrat entre en vigueur à la date de sa notification au Prestataire."};

    static final String[] PHRASES_PV = {
            "Le président de séance a ouvert la réunion en rappelant l'ordre du jour et les objectifs fixés lors de la précédente réunion.",
            "Le représentant de la {service} a présenté l'état d'avancement du projet, estimé à {pourcent} % à la date de la réunion.",
            "Les membres présents ont relevé un retard dans la livraison des équipements, imputable au fournisseur.",
            "Après discussion, il a été convenu de fixer une nouvelle échéance au {date3}.",
            "Le comité a examiné les réclamations reçues depuis le {date2} et a décidé d'y donner suite.",
            "Il a été rappelé que les documents doivent être numérisés et indexés dans la GED dès leur réception au bureau d'ordre.",
            "La question du budget complémentaire de {montant} dirhams a été soumise à l'appréciation de la direction.",
            "Les participants ont souligné la nécessité de renforcer la coordination entre les services.",
            "Le prochain comité de suivi se tiendra le {date3} dans les mêmes locaux.",
            "Aucune autre question n'ayant été soulevée, le président a remercié les participants."};

    static final String[] ORDRES_DU_JOUR = {"Lecture et approbation du procès-verbal de la réunion précédente",
            "État d'avancement des travaux", "Examen des réclamations", "Point sur la situation budgétaire",
            "Planification des prochaines étapes", "Organisation de l'archivage des documents", "Questions diverses"};

    static final String[] DECISIONS = {"Relancer le fournisseur par lettre recommandée avant le {date3}.",
            "Constituer une commission technique chargée de la réception des équipements.",
            "Transmettre le présent procès-verbal à l'ensemble des services concernés.",
            "Mettre à jour le planning du projet et le diffuser sous huitaine.",
            "Organiser une session de formation au profit des agents du bureau d'ordre.",
            "Procéder au paiement du décompte n° {num} après vérification du service fait."};

    static final String[] TITRES_ATTESTATION = {"ATTESTATION DE TRAVAIL", "ATTESTATION DE SALAIRE",
            "ATTESTATION DE STAGE", "ATTESTATION DE BONNE EXÉCUTION", "CERTIFICAT DE PRISE DE SERVICE",
            "ATTESTATION DE RÉCEPTION"};

    static final String[] PIECES = {"Original de la facture n° {fact}", "Copie du bon de commande n° {bc}",
            "Procès-verbal de réception", "Attestation de régularité fiscale", "Attestation CNSS",
            "Relevé d'identité bancaire", "Décompte provisoire n° {num}", "Copie de la CIN",
            "Demande manuscrite de l'intéressé", "Certificat médical", "Rapport d'activité", "Convention signée (2 exemplaires)",
            "Copie du contrat n° {ref}", "Ordre de service n° {num}", "Bordereau des prix"};

    static final String[] OBSERVATIONS = {"Pour attribution", "Pour information", "Pour signature", "Pour avis",
            "Original", "Copie", "Urgent", "À retourner signé", "Pour classement", "Suite à donner"};

    static final String[] ANNOTATIONS = {"Vu. À traiter en urgence", "Transmis au service juridique pour avis",
            "M. {nom} : pour suite à donner", "Classer au dossier", "Répondre avant le {date3}", "Vu et approuvé",
            "Bon pour paiement", "À diffuser à tous les services", "Me voir à ce sujet", "Pour information",
            "Dossier incomplet, relancer", "Copie à la DRH", "Reçu le {date2}", "Lu et approuvé"};

    static final String[] DEMANDES_FORMULAIRE = {"DEMANDE D'ATTESTATION", "DEMANDE DE CONGÉ",
            "DEMANDE DE COMMUNICATION DE DOCUMENTS", "FICHE DE RÉCLAMATION", "DEMANDE DE RENDEZ-VOUS",
            "DEMANDE D'AUTORISATION D'ABSENCE"};

    static final String[] PHRASES_MANUSCRITES = {
            "Je soussigné demande la délivrance d'une attestation de travail pour la constitution d'un dossier de crédit.",
            "Je sollicite un congé de {delai} jours à compter du {date3} pour des raisons familiales.",
            "Je vous prie de bien vouloir me communiquer une copie de mon dossier administratif.",
            "Suite à mon passage le {date2}, je n'ai toujours pas reçu de réponse à ma demande.",
            "Merci de traiter ma demande dans les meilleurs délais.",
            "Je reste joignable au numéro indiqué ci-dessus pour tout renseignement.",
            "Je joins à la présente une copie de ma carte nationale et un justificatif de domicile.",
            "Ma demande concerne le dossier enregistré sous le numéro {ref}.",
            "Je vous remercie par avance pour votre compréhension."};

    static final String[] DESTINATAIRES_NOTE = {"Tous les chefs de division", "Tous les chefs de service",
            "Le personnel du bureau d'ordre", "Les directeurs régionaux", "L'ensemble du personnel",
            "Le service informatique", "Les gestionnaires des marchés"};

    static final String[] MOTS_TAMPON = {"PAYÉ", "URGENT", "COPIE CONFORME", "CONFIDENTIEL", "ANNULÉ", "DUPLICATA",
            "VU", "ENREGISTRÉ", "COPIE"};

    static final DateTimeFormatter JJ_MM_AAAA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter LONGUE = DateTimeFormatter.ofPattern("d MMMM yyyy", FR);

    // ---------------------------------------------------------------- tirages

    static <T> T un(Random r, T[] t) {
        return t[r.nextInt(t.length)];
    }

    static int entre(Random r, int min, int max) {
        return min + r.nextInt(max - min + 1);
    }

    static boolean chance(Random r, double p) {
        return r.nextDouble() < p;
    }

    static LocalDate date(Random r) {
        return LocalDate.of(2024, 1, 1).plusDays(r.nextInt(1000));
    }

    static String dateCourte(LocalDate d) {
        return d.format(JJ_MM_AAAA);
    }

    static String dateLongue(LocalDate d) {
        return d.format(LONGUE);
    }

    static String chiffres(Random r, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + r.nextInt(10)));
        }
        return sb.toString();
    }

    static String cin(Random r) {
        String l = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String p = String.valueOf(l.charAt(r.nextInt(l.length())));
        if (chance(r, 0.6)) {
            p += l.charAt(r.nextInt(l.length()));
        }
        return p + chiffres(r, 6 - (p.length() - 1));
    }

    static String telephone(Random r) {
        return "05 " + chiffres(r, 2) + " " + chiffres(r, 2) + " " + chiffres(r, 2) + " " + chiffres(r, 2);
    }

    static String gsm(Random r) {
        return "06" + chiffres(r, 8);
    }

    static String adresse(Random r, String ville) {
        return entre(r, 1, 250) + ", " + un(r, RUES) + ", " + ville;
    }

    static String nomPersonne(Random r) {
        return un(r, PRENOMS) + " " + un(r, NOMS).toUpperCase(FR);
    }

    static String civilite(Random r) {
        return chance(r, 0.55) ? "M." : "Mme";
    }

    static String societe(Random r) {
        return un(r, SOC_PREFIXE) + " " + un(r, SOC_NOM) + " " + un(r, SOC_ACTIVITE) + " " + un(r, SOC_FORME);
    }

    /** Montant au format français : espace pour les milliers, virgule décimale. */
    static String montant(long centimes) {
        long dh = centimes / 100;
        int ct = (int) (centimes % 100);
        String s = String.valueOf(dh);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(s.charAt(i));
        }
        return sb + "," + (ct < 10 ? "0" : "") + ct;
    }

    // ------------------------------------------------- nombres en toutes lettres

    private static final String[] UNITES_L = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept",
            "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"};
    private static final String[] DIZAINES_L = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante",
            "", "quatre-vingt"};

    /** Écriture française d'un entier (orthographe traditionnelle), jusqu'à 999 999 999. */
    static String enLettres(long n) {
        if (n == 0) {
            return "zéro";
        }
        StringBuilder sb = new StringBuilder();
        int millions = (int) (n / 1_000_000);
        int milliers = (int) (n / 1000 % 1000);
        int reste = (int) (n % 1000);
        if (millions > 0) {
            sb.append(moinsDeMille(millions, true)).append(millions > 1 ? " millions" : " million");
        }
        if (milliers > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(milliers == 1 ? "mille" : moinsDeMille(milliers, false) + " mille");
        }
        if (reste > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(moinsDeMille(reste, true));
        }
        return sb.toString();
    }

    private static String moinsDeMille(int n, boolean accordFinal) {
        int c = n / 100;
        int r = n % 100;
        StringBuilder sb = new StringBuilder();
        if (c == 1) {
            sb.append("cent");
        } else if (c > 1) {
            sb.append(UNITES_L[c]).append(" cent");
            if (r == 0 && accordFinal) {
                sb.append('s');
            }
        }
        if (r > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            String d = moinsDeCent(r);
            if (r == 80 && !accordFinal) {
                d = "quatre-vingt";
            }
            sb.append(d);
        }
        return sb.toString();
    }

    private static String moinsDeCent(int n) {
        if (n < 17) {
            return UNITES_L[n];
        }
        if (n < 20) {
            return "dix-" + UNITES_L[n - 10];
        }
        int d = n / 10;
        int u = n % 10;
        if (d == 7 || d == 9) {
            String base = d == 7 ? "soixante" : "quatre-vingt";
            if (d == 7 && u == 1) {
                return "soixante et onze";
            }
            return base + "-" + moinsDeCent(10 + u);
        }
        if (u == 0) {
            return d == 8 ? "quatre-vingts" : DIZAINES_L[d];
        }
        if (u == 1 && d != 8) {
            return DIZAINES_L[d] + " et un";
        }
        return DIZAINES_L[d] + "-" + UNITES_L[u];
    }
}
