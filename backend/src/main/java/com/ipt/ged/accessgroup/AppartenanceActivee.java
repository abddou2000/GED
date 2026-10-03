package com.ipt.ged.accessgroup;

import com.ipt.ged.audit.EvenementAudit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Appartenance en attente devenue réelle à la première connexion de la
 * personne (T-025, ANO-F-030) : elle reçoit les droits du groupe sans geste de
 * l'Administrateur. Publiée par {@code ServiceIdentites}, une par groupe
 * rejoint, dans la transaction de la création de l'identité.
 *
 * <ul>
 *   <li><b>Audit</b> (§4.9.3, changement de droits) : action
 *       {@code GROUPE_MEMBRE_ACTIVE} sur le groupe, avec l'identité, la fiche
 *       employé et le motif ; l'acteur est le système (aucun Administrateur ne
 *       fait le geste à ce moment : celui qui a préparé l'appartenance est
 *       tracé par {@code GROUPE_CREE} / {@code GROUPE_MODIFIE}).</li>
 *   <li><b>Notification</b> (§4.6.6, F-65) : la personne reçoit le même avis
 *       {@code ACCES_ESPACE_ATTRIBUE} qu'un membre ajouté par l'Administrateur,
 *       un par espace du groupe ({@code EcouteurDeclencheurs}) ; aucun avis si
 *       le groupe est en corbeille (il n'apporte alors aucun droit).</li>
 * </ul>
 *
 * @param appartenanceId  ligne de {@code groupe_membre} (identifiant conservé de l'attente)
 * @param groupe          nom du groupe
 * @param groupeSupprime  groupe en corbeille au moment de la conversion
 * @param utilisateurId   identité GED créée
 * @param identifiant     identifiant d'annuaire de la personne
 * @param employeId       fiche employé à laquelle l'appartenance était préparée
 * @param motif           circonstance de la conversion (première connexion, délégation)
 */
public record AppartenanceActivee(UUID appartenanceId, UUID groupeId, String groupe, boolean groupeSupprime,
                                  UUID utilisateurId, String identifiant, UUID employeId, String motif,
                                  Instant instant) implements EvenementAudit {

    public static final String ACTION = "GROUPE_MEMBRE_ACTIVE";
    public static final String MOTIF_CONNEXION = "Première connexion";
    public static final String MOTIF_DELEGATION = "Première connexion par délégation d'une application";
    /** Acteur porté au journal : aucune personne n'est l'auteur de la conversion. */
    public static final String ACTEUR_SYSTEME = "Système";

    @Override
    public String action() { return ACTION; }

    @Override
    public String objetType() { return "GROUPE"; }

    @Override
    public UUID objetId() { return groupeId; }

    @Override
    public Map<String, Object> avant() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("membreEnAttente", employeId);
        return m;
    }

    @Override
    public Map<String, Object> apres() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupe", groupe);
        m.put("appartenanceId", appartenanceId);
        m.put("utilisateurId", utilisateurId);
        m.put("identifiant", identifiant);
        m.put("employeId", employeId);
        return m;
    }

    @Override
    public String acteurNom() { return ACTEUR_SYSTEME; }
}
