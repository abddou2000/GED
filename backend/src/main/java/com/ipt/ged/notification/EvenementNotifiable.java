package com.ipt.ged.notification;

/**
 * <b>Contrat pour les lots qui déclenchent une notification</b> (workflow,
 * conservation), sur le modèle de {@code EvenementAudit} : l'événement de
 * domaine implémente cette interface et est publié par
 * {@code ApplicationEventPublisher} dans la transaction de l'écriture ;
 * {@link EcouteurDeclencheurs} écrit alors la notification dans cette même
 * transaction (patron boîte d'envoi : pas de notification pour une opération
 * annulée, pas d'opération sans sa notification).
 *
 * <p>Exemples attendus (lots E8 de dev1 et dev3) :
 * <pre>{@code
 * public record CircuitOuvert(UUID circuitId, UUID documentId, String document,
 *                             List<UUID> validateurs, ...) implements EvenementAudit, EvenementNotifiable {
 *     public DemandeNotification notification() {
 *         return DemandeNotification.a(TypeNotification.CIRCUIT_OUVERT, validateurs, "DOCUMENT", documentId,
 *                 Map.of("document", document, "auteur", initiateur), "documents/" + documentId);
 *     }
 * }
 * public record EcheanceAtteinte(...) implements EvenementNotifiable {
 *     public DemandeNotification notification() {
 *         return DemandeNotification.auRole(TypeNotification.ECHEANCE_CONSERVATION, "AGENT_ARCHIVE",
 *                 "DOCUMENT", documentId, Map.of("document", nom, "echeance", date), "documents/" + documentId);
 *     }
 * }
 * }</pre>
 * Une seule méthode, nommée pour ne heurter aucune méthode de
 * {@code EvenementAudit} quand un événement implémente les deux contrats.
 *
 * <p>Un lot peut aussi appeler directement {@link Notifications#envoyer} ;
 * l'événement est préférable : le lot déclencheur ne dépend alors pas des
 * notifications.
 */
public interface EvenementNotifiable {

    /** La notification à écrire ; {@code null} = rien à notifier pour cet événement. */
    DemandeNotification notification();
}
