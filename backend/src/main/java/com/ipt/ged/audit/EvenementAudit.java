package com.ipt.ged.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Événement à inscrire au journal d'audit (DAT §7.4.1).
 *
 * <p><b>Contrat pour les autres lots.</b> Un lot rend ses événements de domaine
 * auditables en leur faisant implémenter cette interface, puis les publie par
 * {@code ApplicationEventPublisher} : le lot audit les écoute
 * ({@link EcouteurEvenementsAudit}) sans que personne ne dépende de lui, et
 * sans que deux lots modifient le même service. Exemple :
 * <pre>{@code
 * public sealed interface EvenementDocument extends EvenementAudit … {
 *     default String action()          { return type(); }
 *     default String objetType()       { return "DOCUMENT"; }
 *     default UUID objetId()           { return documentId(); }
 *     default UUID acteurUtilisateurId() { return acteur().employeId(); }
 *     default UUID acteurApplicationId() { return acteur().applicationId(); }
 * }
 * }</pre>
 * Règle de publication : une écriture publie dans sa transaction (l'audit est
 * alors écrit dans la même transaction : tout ou rien) ; une lecture publie
 * après le contrôle d'accès ; un refus publie un événement de résultat
 * {@link ResultatAudit#REFUS}, écrit dans une transaction propre pour survivre
 * à l'annulation de l'opération refusée.
 *
 * <p>Les valeurs laissées {@code null} (acteur, adresse IP, trace) sont
 * complétées à partir de la requête en cours : identité authentifiée, adresse
 * de confiance et traceId du contexte de journalisation (DAT 7.2).
 */
public interface EvenementAudit {

    /** Code stable de l'action ({@link ActionAudit#code()} ou code du lot). */
    String action();

    /** Nature de l'objet concerné : {@code DOCUMENT}, {@code ESPACE}, {@code TYPE_DOCUMENT}… */
    default String objetType() {
        return null;
    }

    default UUID objetId() {
        return null;
    }

    /** Valeurs avant la modification, champs modifiés seulement. */
    default Map<String, Object> avant() {
        return null;
    }

    /** Valeurs après la modification (ou valeurs créées). */
    default Map<String, Object> apres() {
        return null;
    }

    default ResultatAudit resultat() {
        return ResultatAudit.SUCCES;
    }

    /** Motif d'un refus ou d'un échec ; complément utile d'un succès. */
    default String motif() {
        return null;
    }

    /** Utilisateur auteur, ou utilisateur délégué ; {@code null} = celui de la requête. */
    default UUID acteurUtilisateurId() {
        return null;
    }

    /** Application appelante (clé d'API) ; {@code null} = celle de la requête, s'il y en a une. */
    default UUID acteurApplicationId() {
        return null;
    }

    /** Identifiant lisible de l'acteur ; {@code null} = celui de la requête. */
    default String acteurNom() {
        return null;
    }

    /** Adresse du client ; {@code null} = celle de la requête. */
    default String adresseIp() {
        return null;
    }
}
