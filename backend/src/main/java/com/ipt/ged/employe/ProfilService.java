package com.ipt.ged.employe;

import java.util.UUID;
import com.ipt.ged.accessgroup.AccessGroup;
import com.ipt.ged.accessgroup.AccessGroupRepository;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.employe.dto.ProfilResponse;
import com.ipt.ged.security.CompteUtilisateur;
import com.ipt.ged.security.CompteUtilisateurRepository;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.signature.SignatureStatus;
import com.ipt.ged.signature.WorkflowSignatureRepository;
import com.ipt.ged.workspace.WorkSpaceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Assemble la fiche de profil d'un utilisateur.
 *
 * <p>Les agrégats sont calculés côté serveur plutôt que reconstitués par le
 * frontend : compter les dossiers d'une personne en filtrant une page de liste
 * donnerait un chiffre juste seulement tant que tout tient sur une page.
 */
@Service
public class ProfilService {

    private final EmployeRepository employes;
    private final WorkSpaceRepository workspaces;
    private final AccessGroupRepository groupes;
    private final UploadDocumentRepository documents;
    private final WorkflowSignatureRepository signatures;
    private final CompteUtilisateurRepository comptes;

    public ProfilService(EmployeRepository employes, WorkSpaceRepository workspaces,
                         AccessGroupRepository groupes, UploadDocumentRepository documents,
                         WorkflowSignatureRepository signatures, CompteUtilisateurRepository comptes) {
        this.employes = employes;
        this.workspaces = workspaces;
        this.groupes = groupes;
        this.documents = documents;
        this.signatures = signatures;
        this.comptes = comptes;
    }

    @Transactional(readOnly = true)
    public ProfilResponse profil(UUID employeId) {
        Employe e = employes.findById(employeId)
                .orElseThrow(() -> new EntityNotFoundException("Employé introuvable : " + employeId));

        /* Le compte porte l'adresse de connexion et la derniere visite. Un
           employe sans compte n'en a pas : les champs restent nuls plutot que
           d'afficher un vide qui ressemblerait a une donnee manquante. */
        CompteUtilisateur compte = comptes.findByEmployeId(e.getId()).orElse(null);

        List<ProfilResponse.Ref> dossiers = workspaces.findByDeletedFalseOrderByIdAsc().stream()
                .filter(w -> w.getOwner() != null && w.getOwner().getId().equals(employeId))
                .map(w -> new ProfilResponse.Ref(w.getId(), w.getName()))
                .toList();

        List<ProfilResponse.Ref> mesGroupes = groupes.findByDeletedFalseOrderByIdAsc().stream()
                .filter(g -> membre(g, employeId))
                .map(g -> new ProfilResponse.Ref(g.getId(), g.getName()))
                .toList();

        int deposes = (int) documents.findByDeletedFalseOrderByIdDesc().stream()
                .filter(d -> auteur(d, employeId))
                .count();

        int enAttente = signatures
                .findByEmployeIdAndStatusOrderByStepOrderAsc(employeId, SignatureStatus.PENDING).size();
        int traitees = signatures
                .findByEmployeIdAndStatusInOrderByIdDesc(employeId,
                        List.of(SignatureStatus.SIGNED, SignatureStatus.REJECTED)).size();

        return new ProfilResponse(
                e.getId(), e.getFullName(), e.getFirstName(), e.getLastName(), e.isHasUser(),
                compte != null ? compte.getEmail() : null,
                compte != null && compte.isActif(),
                compte != null ? compte.getDerniereConnexion() : null,
                dossiers, mesGroupes, deposes, enAttente, traitees);
    }

    /** Les membres sont chargés paresseusement : hors session, on ne conclut pas. */
    private boolean membre(AccessGroup g, UUID employeId) {
        try {
            return g.getUsers().stream().anyMatch(u -> u.getId().equals(employeId));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean auteur(UploadDocument d, UUID employeId) {
        return d.getCreatedBy() != null && d.getCreatedBy().getId().equals(employeId);
    }

    /**
     * Profil de l'utilisateur authentifié.
     *
     * <p>Cette méthode renvoyait auparavant {@code findByHasUserTrue().get(0)} :
     * « mon profil » exposait donc la fiche du premier compte de la base — ses
     * dossiers, son activité — à n'importe quel appelant, et
     * affichait à chacun une identité qui n'était pas la sienne. L'identité est
     * désormais résolue à partir du jeton, seule source qui ne se falsifie pas.
     *
     * <p>L'identité est reçue en paramètre (le contrôleur la tire du principal)
     * plutôt que lue ici dans le {@code SecurityContextHolder} : le service reste
     * testable sans contexte de sécurité, et l'origine de l'identifiant se lit
     * dans la signature.
     */
    @Transactional(readOnly = true)
    public ProfilResponse profilCourant(UtilisateurConnecte utilisateur) {
        if (utilisateur == null) {
            throw new AccessDeniedException("Aucun utilisateur authentifié");
        }
        return profil(utilisateur.getEmployeId());
    }
}
