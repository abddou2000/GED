package com.ipt.ged.autorisation;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.EntreeAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Façade d'exceptions du point d'application unique ({@link AccessPredicate}),
 * pour les contrôles d'écriture et de lecture unitaire des services.
 *
 * <h2>404 plutôt que 403 hors périmètre (P5)</h2>
 * <p>Un objet que l'appelant ne peut pas voir est « introuvable », exactement
 * comme un identifiant inexistant : répondre 403 confirmerait son existence.
 * Le 403 est réservé à l'objet VISIBLE sur lequel manque la permission
 * demandée (consulter un document mais pas le supprimer).
 *
 * <p>Le client ne distingue pas les deux cas, mais le serveur, lui, trace
 * l'accès hors périmètre au journal d'audit ({@code ACCES_HORS_PERIMETRE},
 * résultat REFUS, transaction propre : ANO-E4-002) — un objet réellement
 * absent n'est pas tracé.
 */
@Component
public class ControleAcces {

    private static final Logger journal = LoggerFactory.getLogger(ControleAcces.class);

    private final AccessPredicate predicat;

    /** Journal d'audit (lot E4), résolu à l'usage : aucune dépendance au démarrage. */
    @Autowired
    private ObjectProvider<AuditService> audit;

    public ControleAcces(AccessPredicate predicat) {
        this.predicat = predicat;
    }

    private static Authentication courant() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** Le document est visible de l'appelant (CONSULTER), sinon 404. */
    public void exigerLectureDocument(UUID documentId) {
        exigerSurDocument(CodePermission.CONSULTER, documentId);
    }

    /**
     * Permission sur un document : 404 si le document est hors périmètre de
     * lecture, 403 s'il est visible mais que la permission manque.
     */
    public void exigerSurDocument(CodePermission permission, UUID documentId) {
        Set<CodePermission> perms = predicat.permissionsSurDocument(courant(), documentId);
        if (!perms.contains(CodePermission.CONSULTER)) {
            throw horsPerimetre("DOCUMENT", documentId, "Document introuvable : " + documentId);
        }
        if (!perms.contains(permission)) {
            throw new PermissionRefuseeException("Permission « " + permission + " » requise sur ce document");
        }
    }

    /** Le document est-il lisible ? (sans exception, pour les filtres unitaires) */
    public boolean documentLisible(UUID documentId) {
        return predicat.peut(courant(), CodePermission.CONSULTER, documentId);
    }

    /**
     * Permission sur un nœud : 404 si le nœud n'est ni couvert ni de passage
     * pour l'appelant, 403 s'il est visible mais que la permission manque.
     */
    public void exigerSurNoeud(CodePermission permission, UUID noeudId) {
        DroitsResolus d = predicat.droits(courant());
        if (d.peutSurNoeud(permission, noeudId)) return;
        if (!predicat.noeudsVisibles(courant()).containsKey(noeudId)) {
            throw horsPerimetre("NOEUD", noeudId, "Espace de travail introuvable : " + noeudId);
        }
        throw new PermissionRefuseeException("Permission « " + permission + " » requise sur ce nœud");
    }

    /** Le nœud est visible (couvert ou de passage), sinon 404. */
    public void exigerNoeudVisible(UUID noeudId) {
        if (!predicat.noeudsVisibles(courant()).containsKey(noeudId)) {
            throw horsPerimetre("NOEUD", noeudId, "Espace de travail introuvable : " + noeudId);
        }
    }

    /** Permission d'administration (portée globale), sinon 403. */
    public void exigerAdministration(CodePermission permission) {
        if (!predicat.droits(courant()).administre(permission)) {
            throw new PermissionRefuseeException("Permission d'administration « " + permission + " » requise");
        }
    }

    public boolean administre(CodePermission permission) {
        return predicat.droits(courant()).administre(permission);
    }

    public DroitsResolus droits() {
        return predicat.droits(courant());
    }

    /**
     * 404 pour un objet hors périmètre, tracé au journal s'il existe (ANO-E4-002).
     * À employer par tout service qui conclut lui-même au hors-périmètre.
     */
    public HorsPerimetreException horsPerimetre(String objetType, UUID objetId, String message) {
        if (audit != null && objetId != null && existe(objetType, objetId)) {
            AuditService a = audit.getIfAvailable();
            if (a != null) {
                try {
                    // Refus : transaction propre, la trace survit à l'annulation de la requête.
                    a.enregistrer(EntreeAudit.de(ActionAudit.ACCES_HORS_PERIMETRE, objetType, objetId)
                            .refus("Objet hors du périmètre de l'appelant"));
                } catch (RuntimeException e) {
                    journal.error("Accès hors périmètre non tracé au journal d'audit", e);
                }
            }
        }
        return new HorsPerimetreException(message);
    }

    /** Un identifiant qui ne désigne rien n'est pas un accès hors périmètre : pas de trace. */
    private boolean existe(String objetType, UUID id) {
        return switch (objetType) {
            case "DOCUMENT" -> predicat.existeDocument(id);
            case "NOEUD" -> predicat.arbre().contient(id);
            default -> true;
        };
    }
}
