package com.ipt.ged.identite;

import com.ipt.ged.accessgroup.AppartenancesEnAttente;
import com.ipt.ged.autorisation.Habilitation;
import com.ipt.ged.autorisation.HabilitationRepository;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.autorisation.admin.ServiceHabilitations;
import com.ipt.ged.autorisation.admin.dto.DemandeHabilitation;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.annuaire.FicheAnnuaire;
import com.ipt.ged.security.UtilisateurConnecte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Cycle de vie des identités GED (dossier technique §3.3, §3.4.2).
 *
 * <h2>Provisionnement automatique sans rôle</h2>
 * <p>À la première authentification réussie d'une personne inconnue de la GED,
 * l'identité est créée à la volée, clé {@code objectGUID}, <b>sans rôle</b> : la
 * personne voit une page d'accueil vide jusqu'à ce que l'Administrateur lui en
 * attribue un. Seule exception : les identifiants listés dans
 * {@code ged.identite.amorcage.administrateurs} reçoivent le rôle Administrateur
 * en portée globale (habilitation sans cible) à leur première connexion, faute
 * de quoi personne ne pourrait jamais attribuer de rôle.
 *
 * <h2>Rattachement à la personne métier</h2>
 * <p>L'identité est liée à un {@link Employe} : c'est lui que référencent
 * dossiers, dépôts et circuits. Si une fiche employé sans identité porte
 * l'adresse dérivée « prénom.nom@domaine » égale au courriel de l'annuaire
 * (fiches reprises de l'ancienne base), elle est réutilisée — son historique
 * suit la personne ; sinon une fiche est créée depuis l'annuaire. Le courriel ne
 * sert qu'à ce rapprochement, jamais à l'authentification (D2).
 *
 * <h2>Appartenances préparées</h2>
 * <p>Les groupes GED réunissent des identités (§12.1, T-025). Les appartenances
 * reprises ou préparées pour la fiche avant que la personne ait une identité
 * attendent dans {@code groupe_membre_attente} ; elles deviennent des
 * appartenances réelles à la création de l'identité, dans la même transaction,
 * sans action de l'Administrateur ({@link AppartenancesEnAttente}).
 *
 * <h2>Renommage</h2>
 * <p>Un changement de {@code sAMAccountName} met à jour l'identifiant : la clé
 * {@code objectGUID} étant immuable, aucun doublon n'est créé.
 */
@Service
public class ServiceIdentites {

    private static final Logger log = LoggerFactory.getLogger(ServiceIdentites.class);

    /** Résultat d'un provisionnement : l'identité, et si elle vient d'être créée. */
    public record Provisionnement(Utilisateur utilisateur, boolean nouvelle) {}

    private final UtilisateurRepository utilisateurs;
    private final EmployeRepository employes;
    private final RoleRepository roles;
    private final ServiceCacheAnnuaire cache;
    private final ProprietesIdentite proprietes;
    private final HabilitationRepository habilitations;
    private final ServiceHabilitations serviceHabilitations;
    private final AppartenancesEnAttente appartenancesEnAttente;

    public ServiceIdentites(UtilisateurRepository utilisateurs, EmployeRepository employes, RoleRepository roles,
                            ServiceCacheAnnuaire cache, ProprietesIdentite proprietes,
                            HabilitationRepository habilitations, ServiceHabilitations serviceHabilitations,
                            AppartenancesEnAttente appartenancesEnAttente) {
        this.utilisateurs = utilisateurs;
        this.employes = employes;
        this.roles = roles;
        this.cache = cache;
        this.proprietes = proprietes;
        this.habilitations = habilitations;
        this.serviceHabilitations = serviceHabilitations;
        this.appartenancesEnAttente = appartenancesEnAttente;
    }

    /**
     * Identité correspondant à une fiche d'annuaire authentifiée : créée si elle
     * est inconnue, mise à jour sinon (identifiant, nom), cache annuaire rafraîchi.
     *
     * @param connexion vrai pour une connexion réelle : la date de dernière
     *                  connexion est alors mise à jour
     */
    @Transactional
    public Provisionnement provisionner(FicheAnnuaire fiche, boolean connexion) {
        Optional<Utilisateur> existante = utilisateurs.findByObjectGuid(fiche.objectGuid());
        Utilisateur u = existante.orElseGet(() -> creer(fiche));
        if (!u.getIdentifiant().equals(fiche.identifiant())) {
            log.info("Identifiant d'annuaire modifié : {} devient {} (même objectGUID).",
                    u.getIdentifiant(), fiche.identifiant());
            u.setIdentifiant(fiche.identifiant());
        }
        synchroniserEmploye(u.getEmploye(), fiche);
        if (connexion) {
            u.setDerniereConnexionLe(Instant.now());
        }
        utilisateurs.save(u);
        cache.enregistrer(u.getId(), fiche);
        return new Provisionnement(u, existante.isEmpty());
    }

    /** Principal de la requête, rôles relus en base (habilitations directes et par groupe). */
    @Transactional(readOnly = true)
    public Optional<UtilisateurConnecte> principal(UUID utilisateurId, UUID sessionId) {
        return utilisateurs.findById(utilisateurId).map(u -> connecte(u, sessionId));
    }

    @Transactional(readOnly = true)
    public Optional<UtilisateurConnecte> principalParIdentifiant(String identifiant) {
        return utilisateurs.findByIdentifiant(identifiant).map(u -> connecte(u, null));
    }

    @Transactional(readOnly = true)
    public List<Utilisateur> toutes() {
        return utilisateurs.findAll();
    }

    /** Rôles détenus par une identité : {@code [globaux, tous]}. */
    @Transactional(readOnly = true)
    public List<Set<String>> roles(Utilisateur u) {
        Set<String> globaux = new TreeSet<>();
        Set<String> tous = new TreeSet<>();
        for (Habilitation h : habilitations.applicablesA(u.getId())) {
            if (h.getRole() == null) continue;
            tous.add(h.getRole().getCode());
            if (h.globale()) globaux.add(h.getRole().getCode());
        }
        return List.of(globaux, tous);
    }

    private UtilisateurConnecte connecte(Utilisateur u, UUID sessionId) {
        List<Set<String>> r = roles(u);
        return UtilisateurConnecte.depuis(u, r.get(0), r.get(1), sessionId);
    }

    private Utilisateur creer(FicheAnnuaire fiche) {
        Employe employe = employeRattache(fiche).orElseGet(() -> employes.save(nouvelEmploye(fiche)));
        if (!employe.isHasUser()) {
            employe.setHasUser(true);
        }
        // Écrite tout de suite : les appartenances en attente vont la référencer.
        Utilisateur u = utilisateurs.saveAndFlush(new Utilisateur(fiche.objectGuid(), fiche.identifiant(), employe));
        int groupes = appartenancesEnAttente.convertir(u.getId(), employe.getId());
        if (groupes > 0) {
            log.info("{} appartenance(s) à des groupes GED préparée(s) pour {} appliquée(s) à sa première connexion.",
                    groupes, fiche.identifiant());
        }
        boolean administrateurInitial = proprietes.getAmorcage().getAdministrateurs().stream()
                .anyMatch(a -> a != null && a.trim().equalsIgnoreCase(fiche.identifiant()));
        if (administrateurInitial) {
            roles.findByCode(Role.ADMINISTRATEUR).ifPresent(r -> serviceHabilitations.attribuer(
                    new DemandeHabilitation(TypeSujet.UTILISATEUR, u.getId(), r.getId(), null, null, false), null));
            log.warn("Rôle Administrateur attribué par amorçage à {} (ged.identite.amorcage.administrateurs).",
                    fiche.identifiant());
        }
        log.info("Identité GED provisionnée pour {}{}.", fiche.identifiant(),
                administrateurInitial ? " (Administrateur d'amorçage)" : ", sans rôle");
        return u;
    }

    /** Fiche employé reprise de l'ancienne base, sans identité, au courriel dérivé identique. */
    private Optional<Employe> employeRattache(FicheAnnuaire fiche) {
        if (fiche.courriel() == null || fiche.courriel().isBlank()) return Optional.empty();
        List<Employe> candidats = employes.findAll().stream()
                .filter(e -> courrielDerive(e).equalsIgnoreCase(fiche.courriel().trim()))
                .filter(e -> !utilisateurs.existsByEmployeId(e.getId()))
                .toList();
        // Deux fiches homonymes : on n'en choisit aucune au hasard.
        return candidats.size() == 1 ? Optional.of(candidats.get(0)) : Optional.empty();
    }

    private static Employe nouvelEmploye(FicheAnnuaire f) {
        String prenom = nonVide(f.prenom(), nonVide(f.nomAffiche(), f.identifiant()));
        String nom = nonVide(f.nom(), "");
        return new Employe(prenom, nom, true);
    }

    /** Le nom affiché suit l'annuaire, seule source de vérité. */
    private static void synchroniserEmploye(Employe e, FicheAnnuaire f) {
        if (f.prenom() != null && !f.prenom().isBlank() && !f.prenom().equals(e.getFirstName())) {
            e.setFirstName(f.prenom().trim());
        }
        if (f.nom() != null && !f.nom().isBlank() && !f.nom().equals(e.getLastName())) {
            e.setLastName(f.nom().trim());
        }
    }

    private String courrielDerive(Employe e) {
        return sansAccent(e.getFirstName()) + "." + sansAccent(e.getLastName()) + "@" + proprietes.getDomaineCourriel();
    }

    private static String sansAccent(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static String nonVide(String valeur, String repli) {
        return valeur == null || valeur.isBlank() ? repli : valeur.trim();
    }
}
