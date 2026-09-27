package com.ipt.ged.notification;

import com.ipt.ged.audit.EvenementAudit;
import com.ipt.ged.audit.ResultatAudit;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Branche les notifications sur les événements de domaine, sans que les lots
 * déclencheurs dépendent de ce paquet. Écouteurs synchrones : la notification
 * est écrite dans la transaction de l'événement (patron boîte d'envoi).
 *
 * <ul>
 *   <li><b>Circuits de validation et fin de conservation</b> : tout événement
 *       qui implémente {@link EvenementNotifiable} (contrat pour les lots E8 de
 *       dev1 et dev3, voir cette interface).</li>
 *   <li><b>Accès à un espace</b> : l'événement {@code HabilitationModifiee} du lot
 *       autorisation (dev1, E3), lu par son contrat d'audit
 *       ({@link EvenementAudit}, action {@code HABILITATION_MODIFIEE}) pour ne
 *       pas compiler contre une classe d'un autre lot. Seules les attributions
 *       sont notifiées : ajout d'une habilitation avec rôle sur un nœud, ajout
 *       d'un membre à un groupe qui porte des accès. Jamais un retrait, ni une
 *       habilitation sur un document (la diffusion n'est pas un des trois cas).</li>
 * </ul>
 *
 * <p>L'auteur de l'attribution n'est pas notifié de son propre geste.
 */
@Component
public class EcouteurDeclencheurs {

    static final String HABILITATION_MODIFIEE = "HABILITATION_MODIFIEE";
    static final String OBJET_HABILITATION = "HABILITATION";
    static final String OBJET_GROUPE = "GROUPE_GED";
    static final String AJOUT = "AJOUT";
    static final String MODIFICATION = "MODIFICATION";

    private final Notifications notifications;
    private final AnnuaireDestinataires annuaire;

    public EcouteurDeclencheurs(Notifications notifications, AnnuaireDestinataires annuaire) {
        this.notifications = notifications;
        this.annuaire = annuaire;
    }

    @EventListener
    public void surEvenementNotifiable(EvenementNotifiable evenement) {
        DemandeNotification demande = evenement.notification();
        if (demande != null) notifications.envoyer(demande);
    }

    @EventListener
    public void surHabilitation(EvenementAudit evenement) {
        if (!HABILITATION_MODIFIEE.equals(evenement.action()) || evenement.resultat() != ResultatAudit.SUCCES
                || evenement.apres() == null) {
            return;
        }
        if (OBJET_HABILITATION.equals(evenement.objetType()) && AJOUT.equals(evenement.motif())) {
            accesAttribue(evenement.apres(), evenement.acteurUtilisateurId());
        } else if (OBJET_GROUPE.equals(evenement.objetType()) && MODIFICATION.equals(evenement.motif())) {
            membresAjoutes(evenement.objetId(), evenement.avant(), evenement.apres(), evenement.acteurUtilisateurId());
        }
    }

    /** Habilitation avec rôle posée sur un nœud (espace ou dossier). */
    private void accesAttribue(Map<String, Object> h, UUID auteur) {
        UUID noeud = uuid(h.get("noeudId"));
        if (noeud == null || h.get("documentId") != null || h.get("role") == null) {
            return;   // habilitation globale, sur un document, ou rupture d'héritage seule
        }
        UUID sujet = uuid(h.get("sujetId"));
        String typeSujet = String.valueOf(h.get("sujetType"));
        Set<UUID> destinataires = new LinkedHashSet<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("espace", h.get("noeud"));
        if ("UTILISATEUR".equals(typeSujet)) {
            destinataires.add(sujet);
        } else if ("GROUPE".equals(typeSujet) && sujet != null) {
            destinataires.addAll(annuaire.membresDuGroupe(sujet));
            variables.put("groupe", h.get("sujet"));
        }
        // APPLICATION : une application n'a pas de boîte de notifications.
        destinataires.remove(auteur);
        destinataires.remove(null);
        if (destinataires.isEmpty()) return;
        notifications.envoyer(DemandeNotification.a(TypeNotification.ACCES_ESPACE_ATTRIBUE, destinataires,
                "ESPACE", noeud, variables, "espaces-de-travail/" + noeud));
    }

    /** Membres ajoutés à un groupe : ils reçoivent l'accès aux espaces du groupe. */
    private void membresAjoutes(UUID groupe, Map<String, Object> avant, Map<String, Object> apres, UUID auteur) {
        Set<UUID> ajoutes = uuids(apres.get("membres"));
        ajoutes.removeAll(avant == null ? Set.of() : uuids(avant.get("membres")));
        ajoutes.remove(auteur);
        if (groupe == null || ajoutes.isEmpty()) return;
        Map<UUID, String> espaces = annuaire.espacesDuGroupe(groupe);
        for (Map.Entry<UUID, String> espace : espaces.entrySet()) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("espace", espace.getValue());
            variables.put("groupe", apres.get("nom"));
            notifications.envoyer(DemandeNotification.a(TypeNotification.ACCES_ESPACE_ATTRIBUE, ajoutes,
                    "ESPACE", espace.getKey(), variables, "espaces-de-travail/" + espace.getKey()));
        }
    }

    private static UUID uuid(Object valeur) {
        if (valeur == null) return null;
        if (valeur instanceof UUID u) return u;
        try {
            return UUID.fromString(valeur.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<UUID> uuids(Object valeur) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (valeur instanceof Collection<?> c) {
            c.stream().map(EcouteurDeclencheurs::uuid).filter(Objects::nonNull).forEach(ids::add);
        }
        return ids;
    }
}
