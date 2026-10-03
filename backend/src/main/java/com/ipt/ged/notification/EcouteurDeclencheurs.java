package com.ipt.ged.notification;

import com.ipt.ged.autorisation.evenement.HabilitationModifiee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *   <li><b>Accès à un espace</b> : {@link HabilitationModifiee} du lot
 *       autorisation (E3). Seules les attributions sont notifiées : ajout d'une
 *       habilitation avec rôle sur un nœud (à l'utilisateur, ou aux membres du
 *       groupe), ajout d'un membre à un groupe qui porte des accès (un avis par
 *       espace du groupe). Jamais un retrait, ni une habilitation sur un
 *       document (la diffusion n'est pas un des trois cas), ni une rupture
 *       d'héritage seule.</li>
 * </ul>
 *
 * <p>L'auteur de l'attribution n'est pas notifié de son propre geste.
 */
@Component
public class EcouteurDeclencheurs {

    private final Notifications notifications;
    private final AnnuaireDestinataires annuaire;

    @PersistenceContext
    private EntityManager em;

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
    public void surHabilitation(HabilitationModifiee evenement) {
        if (evenement.apres() == null) return;
        synchroniser();
        if (HabilitationModifiee.HABILITATION.equals(evenement.objet())
                && HabilitationModifiee.AJOUT.equals(evenement.operation())) {
            accesAttribue(evenement.apres(), evenement.auteurId());
        } else if (HabilitationModifiee.GROUPE_GED.equals(evenement.objet())
                && HabilitationModifiee.MODIFICATION.equals(evenement.operation())) {
            membresAjoutes(evenement.objetId(), evenement.avant(), evenement.apres(), evenement.auteurId());
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

    /**
     * Membres ajoutés à un groupe : ils reçoivent l'accès aux espaces du groupe.
     * Les membres d'un groupe GED sont des identités GED (T-025) : l'avis leur va
     * directement. Une appartenance en attente (personne jamais connectée) ne
     * figure pas dans {@code membres} et n'est pas notifiée.
     */
    private void membresAjoutes(UUID groupe, Map<String, Object> avant, Map<String, Object> apres, UUID auteur) {
        if (Boolean.TRUE.equals(apres.get("supprime"))) return;
        Set<UUID> ajoutes = uuids(apres.get("membres"));
        ajoutes.removeAll(avant == null ? Set.of() : uuids(avant.get("membres")));
        if (groupe == null || ajoutes.isEmpty()) return;
        Set<UUID> destinataires = new LinkedHashSet<>(ajoutes);
        destinataires.remove(auteur);
        if (destinataires.isEmpty()) return;
        for (Map.Entry<UUID, String> espace : annuaire.espacesDuGroupe(groupe).entrySet()) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("espace", espace.getValue());
            variables.put("groupe", apres.get("nom"));
            notifications.envoyer(DemandeNotification.a(TypeNotification.ACCES_ESPACE_ATTRIBUE, destinataires,
                    "ESPACE", espace.getKey(), variables, "espaces-de-travail/" + espace.getKey()));
        }
    }

    /**
     * L'annuaire des destinataires lit la base en SQL (membres, espaces) : les
     * écritures JPA de la transaction en cours — le membre ajouté juste avant —
     * doivent y être poussées d'abord.
     */
    private void synchroniser() {
        if (em != null && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly() && em.isJoinedToTransaction()) {
            em.flush();
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
