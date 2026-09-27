package com.ipt.ged.support;

import com.ipt.ged.autorisation.Confidentialite;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.autorisation.admin.ServiceHabilitations;
import com.ipt.ged.autorisation.admin.dto.DemandeHabilitation;
import com.ipt.ged.autorisation.admin.dto.HabilitationVue;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.employe.EmployeRepository;
import com.ipt.ged.identite.RoleRepository;
import com.ipt.ged.identite.UtilisateurRepository;
import com.ipt.ged.security.UtilisateurConnecte;
import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.typedocument.TypeDocumentRepository;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workflow.WorkflowRepository;
import com.ipt.ged.workspace.WorkSpace;
import com.ipt.ged.workspace.WorkSpaceRepository;
import com.ipt.ged.workspace.WorkspaceStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Jeu d'essai des tests d'autorisation : nœuds, types, documents et
 * habilitations posés directement par les dépôts et le service
 * d'habilitations, sans passer par les contrôles d'accès qu'on veut éprouver.
 *
 * <p>Chaque objet reçoit un code unique : les classes qui ne sont pas
 * transactionnelles peuvent l'appeler sans collision. Les identités viennent du
 * simulateur d'annuaire ({@link IdentitesDeTest}).
 */
@Component
public class JeuDroits {

    private final WorkSpaceRepository noeuds;
    private final TypeDocumentRepository types;
    private final UploadDocumentRepository documents;
    private final WorkflowRepository workflows;
    private final EmployeRepository employes;
    private final UtilisateurRepository utilisateurs;
    private final RoleRepository roles;
    private final ServiceHabilitations habilitations;
    private final IdentitesDeTest identites;

    public JeuDroits(WorkSpaceRepository noeuds, TypeDocumentRepository types, UploadDocumentRepository documents,
                     WorkflowRepository workflows, EmployeRepository employes, UtilisateurRepository utilisateurs,
                     RoleRepository roles, ServiceHabilitations habilitations, IdentitesDeTest identites) {
        this.noeuds = noeuds;
        this.types = types;
        this.documents = documents;
        this.workflows = workflows;
        this.employes = employes;
        this.utilisateurs = utilisateurs;
        this.roles = roles;
        this.habilitations = habilitations;
        this.identites = identites;
    }

    private static String unique(String prefixe) {
        return prefixe + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Identité provisionnée depuis le simulateur (rôles de test compris). */
    public UtilisateurConnecte identite(String identifiant) {
        return (UtilisateurConnecte) identites.loadUserByUsername(identifiant);
    }

    /** Espace (parent {@code null}) ou dossier, circuit sans étape. */
    public UUID noeud(String nom, UUID parentId) {
        Employe proprietaire = employes.findById(identite(Comptes.ADMIN).getEmployeId()).orElseThrow();
        WorkflowGed wf = workflows.save(new WorkflowGed(unique("WF")));
        WorkSpace w = new WorkSpace(nom, unique("N"));
        w.setStatus(WorkspaceStatus.ACTIF);
        w.setOwner(proprietaire);
        w.setWorkflow(wf);
        if (parentId != null) w.setParent(noeuds.findById(parentId).orElseThrow());
        return noeuds.saveAndFlush(w).getId();
    }

    /** Type de document rangé dans un nœud, niveau de confidentialité par défaut donné. */
    public UUID type(UUID noeudId, Confidentialite defaut) {
        TypeDocument t = new TypeDocument(unique("TD"), "Type " + unique("t"));
        t.setDescription("Type de test");
        t.setWorkspace(noeuds.findById(noeudId).orElseThrow());
        t.setTypeAutorise("pdf");
        t.setTailleMaxMo(5);
        t.setConfidentialiteDefaut(defaut);
        return types.saveAndFlush(t).getId();
    }

    /** Document (fiche seule, sans fichier) dans le nœud principal de son type. */
    public UUID document(String nom, UUID typeId, Confidentialite niveau, UUID deposantEmployeId) {
        TypeDocument t = types.findById(typeId).orElseThrow();
        UploadDocument d = new UploadDocument(nom);
        d.setTypeDocument(t);
        d.setWorkspace(t.getWorkspace());
        d.setFileName(nom + ".pdf");
        d.setFilePath("jeu/" + UUID.randomUUID() + ".pdf");
        d.setExtension("pdf");
        d.setConfidentialite(niveau);
        if (deposantEmployeId != null) d.setCreatedBy(employes.findById(deposantEmployeId).orElseThrow());
        return documents.saveAndFlush(d).getId();
    }

    /** Document public rangé dans un nouvel espace. */
    public UUID documentPublic() {
        UUID n = noeud(unique("Espace"), null);
        return document(unique("Doc"), type(n, Confidentialite.PUBLIC), Confidentialite.PUBLIC, null);
    }

    public UUID idRole(String code) {
        return roles.findByCode(code).orElseThrow().getId();
    }

    /** Attribution d'un rôle à une identité, sur un nœud, un document ou en portée globale. */
    public HabilitationVue habiliter(String identifiant, String role, UUID noeudId, UUID documentId) {
        UUID u = identite(identifiant).getUtilisateurId();
        return habilitations.attribuer(new DemandeHabilitation(TypeSujet.UTILISATEUR, u, idRole(role), noeudId,
                documentId, false), null);
    }

    /** Rupture d'héritage seule, sans rôle. */
    public HabilitationVue rupture(String identifiant, UUID noeudId) {
        UUID u = identite(identifiant).getUtilisateurId();
        return habilitations.attribuer(new DemandeHabilitation(TypeSujet.UTILISATEUR, u, null, noeudId, null, true),
                null);
    }

    /** Attribution d'un rôle désigné par son identifiant (rôle composé dans le test). */
    public HabilitationVue habiliterRole(String identifiant, UUID roleId, UUID noeudId) {
        return habilitations.attribuer(new DemandeHabilitation(TypeSujet.UTILISATEUR, utilisateurId(identifiant),
                roleId, noeudId, null, false), null);
    }

    public HabilitationVue habiliterGroupe(UUID groupeId, String role, UUID noeudId) {
        return habilitations.attribuer(new DemandeHabilitation(TypeSujet.GROUPE, groupeId, idRole(role), noeudId,
                null, false), null);
    }

    public UUID utilisateurId(String identifiant) {
        return identite(identifiant).getUtilisateurId();
    }

    public UUID employeId(String identifiant) {
        return identite(identifiant).getEmployeId();
    }

    public UtilisateurRepository utilisateurs() {
        return utilisateurs;
    }
}
