package com.ipt.ged.autorisation.admin;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.ArbreNoeuds;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.autorisation.DroitsResolus;
import com.ipt.ged.autorisation.ResolveurDroits;
import com.ipt.ged.autorisation.Sujet;
import com.ipt.ged.autorisation.TypeSujet;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.document.UploadDocumentRepository;
import com.ipt.ged.identite.Utilisateur;
import com.ipt.ged.identite.UtilisateurRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Consultation des droits effectifs avec leur origine (§12.2.3, P-22) : pour un
 * sujet et un objet, les permissions résolues et d'où elles viennent — rôle,
 * nœud d'attribution, héritage, rattachement, document isolé, accès global,
 * confidentialité. Calculées par la MÊME fonction de décision que les accès :
 * l'écran ne peut pas dire autre chose que ce que le serveur applique.
 */
@Service
public class ServiceDroitsEffectifs {

    public record OrigineVue(String nature, String role, UUID habilitationId, UUID noeudAttributionId,
                             String noeudAttribution, String via, String viaLibelle, List<String> permissions) {}

    public record EmplacementVue(UUID noeudId, String noeud, boolean principal, List<String> permissions,
                                 List<OrigineVue> origines) {}

    public record ConfidentialiteVue(String niveau, boolean autorise, String motif) {}

    /**
     * @param cible          GLOBALE, NOEUD ou DOCUMENT
     * @param permissions    permissions effectives sur la cible (après confidentialité pour un document)
     * @param administration permissions d'administration (portée globale)
     */
    public record DroitsEffectifs(UUID sujetId, String sujet, String cible, UUID cibleId, String cibleLibelle,
                                  List<String> permissions, List<OrigineVue> origines,
                                  List<EmplacementVue> emplacements, ConfidentialiteVue confidentialite,
                                  List<String> administration, List<String> rolesGlobaux, List<String> roles,
                                  boolean accesGlobal, boolean voirPrive, boolean voirConfidentiel) {}

    private final AccessPredicate predicat;
    private final UtilisateurRepository utilisateurs;
    private final UploadDocumentRepository documents;

    public ServiceDroitsEffectifs(AccessPredicate predicat, UtilisateurRepository utilisateurs,
                                  UploadDocumentRepository documents) {
        this.predicat = predicat;
        this.utilisateurs = utilisateurs;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public DroitsEffectifs calculer(UUID utilisateurId, UUID noeudId, UUID documentId) {
        if (noeudId != null && documentId != null) {
            throw new IllegalArgumentException("Choisir un nœud OU un document");
        }
        Utilisateur u = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> new EntityNotFoundException("Identité introuvable : " + utilisateurId));
        Sujet sujet = new Sujet(TypeSujet.UTILISATEUR, u.getId(), u.getEmploye().getId(),
                u.getEmploye().getFullName());
        DroitsResolus d = predicat.droits(sujet);
        ArbreNoeuds arbre = predicat.arbre();

        String cible = "GLOBALE";
        String libelle = null;
        List<String> perms = List.of();
        List<OrigineVue> origines = new ArrayList<>();
        List<EmplacementVue> emplacements = new ArrayList<>();
        ConfidentialiteVue conf = null;

        if (noeudId != null) {
            ArbreNoeuds.Noeud n = arbre.noeud(noeudId);
            if (n == null) throw new EntityNotFoundException("Nœud introuvable : " + noeudId);
            cible = "NOEUD";
            libelle = n.nom();
            perms = noms(d.surNoeud(noeudId));
            origines.addAll(vues(ResolveurDroits.expliquerNoeud(d.attributions(), arbre, noeudId), arbre));
        } else if (documentId != null) {
            UploadDocument doc = documents.findById(documentId)
                    .orElseThrow(() -> new EntityNotFoundException("Document introuvable : " + documentId));
            cible = "DOCUMENT";
            libelle = doc.getName();
            AccessPredicate.VerdictConfidentialite v = predicat.confidentialite(d, documentId).orElseThrow();
            conf = new ConfidentialiteVue(v.niveau().name(), v.autorise(), v.motif());
            emplacements.add(emplacement(d, arbre, v.principal(), true));
            for (UUID r : v.rattachements()) emplacements.add(emplacement(d, arbre, r, false));
            origines.addAll(vues(ResolveurDroits.expliquerDocumentIsole(d.attributions(), documentId), arbre));
            perms = noms(predicat.permissionsSurDocument(d, documentId));
        } else {
            perms = noms(d.administration());
            for (var a : d.attributions()) {
                if (a.globale() && a.roleCode() != null) {
                    origines.add(new OrigineVue("PORTEE_GLOBALE", a.roleCode(), a.origineId(), null, null,
                            a.viaType().name(), a.viaLibelle(), noms(a.permissions())));
                }
            }
        }
        return new DroitsEffectifs(u.getId(), u.getEmploye().getFullName() + " (" + u.getIdentifiant() + ")",
                cible, noeudId != null ? noeudId : documentId, libelle, perms, origines, emplacements, conf,
                noms(d.administration()), List.copyOf(d.rolesGlobaux()), List.copyOf(d.roles()),
                d.accesGlobal(), d.voirPrive(), d.voirConfidentiel());
    }

    private EmplacementVue emplacement(DroitsResolus d, ArbreNoeuds arbre, UUID noeudId, boolean principal) {
        ArbreNoeuds.Noeud n = arbre.noeud(noeudId);
        return new EmplacementVue(noeudId, n != null ? n.nom() : null, principal, noms(d.surNoeud(noeudId)),
                vues(ResolveurDroits.expliquerNoeud(d.attributions(), arbre, noeudId), arbre));
    }

    private static List<OrigineVue> vues(List<ResolveurDroits.Origine> l, ArbreNoeuds arbre) {
        return l.stream().map(o -> {
            ArbreNoeuds.Noeud n = o.noeudAttribution() != null ? arbre.noeud(o.noeudAttribution()) : null;
            return new OrigineVue(o.nature(), o.roleCode(), o.habilitationId(), o.noeudAttribution(),
                    n != null ? n.nom() : null, o.viaType() != null ? o.viaType().name() : null, o.viaLibelle(),
                    noms(o.permissions()));
        }).toList();
    }

    private static List<String> noms(Set<CodePermission> s) {
        return s.stream().map(Enum::name).sorted().toList();
    }
}
