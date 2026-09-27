package com.ipt.ged.autorisation;

import com.ipt.ged.common.erreur.ConflitException;

/**
 * Conflit d'état, rendu 409 avec un code stable : attribution déjà posée,
 * rattachement déjà présent, rôle encore attribué, document verrouillé ou
 * archivé, nom déjà utilisé dans le dossier.
 *
 * <p>Depuis la fusion du contrat d'erreurs (problem+json, dev2) : une
 * {@link ConflitException}, rendue par le gestionnaire commun ; le conseil
 * provisoire {@code GestionErreursAutorisation} est supprimé.
 */
public class ConflitAutorisationException extends ConflitException {

    public ConflitAutorisationException(String message) {
        super(message);
    }

    public ConflitAutorisationException(String code, String message) {
        super(code, message);
    }
}
