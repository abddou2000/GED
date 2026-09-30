package com.ipt.ged.identite.annuaire;

import com.ipt.ged.identite.erreur.AnnuaireIndisponibleException;

import java.util.UUID;

/**
 * État d'un compte dans l'annuaire, lu au seul moment d'une délégation
 * d'identité ({@code X-On-Behalf-Of}, DAT §5.5).
 *
 * <p><b>Exception bornée à la décision D1</b> (décision D15 du 30/09/2026) :
 * Marchica Med autorise la lecture de {@code userAccountControl}, en lecture
 * seule et <i>limitée à ce contrôle</i>. Rien d'autre ne l'emploie : ni la
 * connexion interactive, ni le renouvellement, ni les sessions (D1 y reste
 * entière : un compte désactivé y est bloqué par l'échec de la liaison), et
 * aucune relecture périodique n'existe.
 */
public interface EtatCompteAnnuaire {

    /** Résultat de la lecture. */
    enum Etat {
        /** Bit ACCOUNTDISABLE absent. */
        ACTIF,
        /** Bit ACCOUNTDISABLE (0x2) présent : aucune délégation vers ce compte. */
        DESACTIVE,
        /** Aucune entrée (ou plusieurs) pour cet objectGUID : le compte n'existe plus. */
        INTROUVABLE,
        /** Entrée sans {@code userAccountControl} lisible : l'état ne peut pas être vérifié. */
        INDETERMINE
    }

    /**
     * @param objectGuid clé immuable du compte
     * @throws AnnuaireIndisponibleException aucun contrôleur ne répond
     */
    Etat etat(UUID objectGuid);
}
