package com.ipt.ged.security;

import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Crée le compte de connexion unique de l'application : l'administrateur.
 *
 * <p>Le cahier des charges ne prévoit qu'un seul utilisateur. Ce seeder ne
 * parcourt donc plus la table des employés pour ouvrir un accès à chacun de ceux
 * qui portent {@code hasUser} : il amorce une seule adresse, celle de la
 * configuration.
 *
 * <p>S'exécute juste après les employés ({@code @Order(2)}) : le compte doit
 * pouvoir se rattacher à l'un d'eux.
 *
 * <h2>Rattachement à un employé</h2>
 * <p>{@link CompteUtilisateur} exige un employé ({@code optional = false}) et
 * les écrans affichent son nom : le compte ne peut pas flotter seul. La règle
 * retenue est de <b>réutiliser</b> l'employé dont l'adresse dérivée
 * (« prénom.nom@domaine », la convention appliquée dans toute l'application)
 * correspond à l'adresse configurée, et de n'en créer un que si aucun ne
 * correspond. Créer systématiquement un employé « Administrateur » aurait
 * produit un doublon de la personne réelle : elle serait apparue deux fois dans
 * les sélecteurs d'approbateur, et son activité aurait été comptée sur deux
 * fiches.
 *
 * <p>Un compte existant n'est jamais modifié, et rien n'est créé si la base en
 * porte déjà un : relancer l'application ne doit pas réinitialiser un mot de
 * passe changé depuis, ni ajouter un second accès.
 *
 * <p>Le mot de passe initial vient de la configuration. Il n'est pas écrit dans
 * ce fichier — un secret dans le code source part avec le dépôt et se retrouve
 * dans l'historique pour toujours. Absent, aucun compte n'est créé.
 */
@Component
@Order(2)
public class CompteSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CompteSeeder.class);

    private final CompteUtilisateurRepository comptes;
    private final EmployeRepository employes;
    private final PasswordEncoder encodeur;
    private final String domaine;
    private final String emailAdmin;
    private final String nomAdmin;
    private final String motDePasseInitial;

    public CompteSeeder(CompteUtilisateurRepository comptes,
                        EmployeRepository employes,
                        PasswordEncoder encodeur,
                        @Value("${ged.securite.domaine-email:marchica.ma}") String domaine,
                        @Value("${ged.securite.email-admin:}") String emailAdmin,
                        @Value("${ged.securite.nom-admin:Administrateur GED}") String nomAdmin,
                        @Value("${ged.securite.mot-de-passe-initial:}") String motDePasseInitial) {
        this.comptes = comptes;
        this.employes = employes;
        this.encodeur = encodeur;
        this.domaine = domaine;
        this.emailAdmin = emailAdmin;
        this.nomAdmin = nomAdmin;
        this.motDePasseInitial = motDePasseInitial;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (motDePasseInitial == null || motDePasseInitial.isBlank()) {
            log.warn("ged.securite.mot-de-passe-initial absent : aucun compte n'est créé. "
                    + "Renseignez-le pour amorcer la connexion de l'administrateur.");
            return;
        }

        String email = adresseAdmin();
        if (email.isBlank()) {
            log.warn("ged.securite.email-admin absent et indéterminable : aucun compte n'est créé.");
            return;
        }

        // Un accès existe déjà : ne rien toucher. L'application n'a qu'un
        // utilisateur, en ajouter un second serait une régression silencieuse.
        if (comptes.count() > 0) {
            return;
        }

        Employe titulaire = employeCorrespondant(email);
        comptes.save(new CompteUtilisateur(email, encodeur.encode(motDePasseInitial), titulaire));
        log.info("Compte administrateur amorcé : {} (employé « {} »).", email, titulaire.getFullName());
    }

    /** L'adresse configurée, ou à défaut « admin@domaine ». */
    private String adresseAdmin() {
        if (emailAdmin != null && !emailAdmin.isBlank()) {
            return emailAdmin.trim().toLowerCase(Locale.ROOT);
        }
        if (domaine == null || domaine.isBlank()) {
            return "";
        }
        return "admin@" + domaine.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * L'employé derrière le compte : celui dont l'adresse dérivée correspond,
     * sinon un employé créé pour l'occasion à partir de {@code ged.securite.nom-admin}.
     */
    private Employe employeCorrespondant(String email) {
        for (Employe e : employes.findAll()) {
            if (emailDe(e).equalsIgnoreCase(email)) {
                // Le drapeau reste la marque « cette personne peut se connecter
                // et donc signer » : il doit être vrai pour le titulaire.
                if (!e.isHasUser()) {
                    e.setHasUser(true);
                    employes.save(e);
                }
                return e;
            }
        }
        String nom = (nomAdmin == null || nomAdmin.isBlank()) ? "Administrateur GED" : nomAdmin.trim();
        int coupure = nom.indexOf(' ');
        String prenom = coupure < 0 ? nom : nom.substring(0, coupure);
        String patronyme = coupure < 0 ? "" : nom.substring(coupure + 1).trim();
        return employes.save(new Employe(prenom, patronyme, true));
    }

    /** « Sara Bennani » → « sara.bennani@domaine ». Les accents sont retirés :
     *  un e-mail ne doit pas dépendre de la façon dont le clavier les compose. */
    private String emailDe(Employe e) {
        return sansAccent(e.getFirstName()) + "." + sansAccent(e.getLastName()) + "@" + domaine;
    }

    private String sansAccent(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
