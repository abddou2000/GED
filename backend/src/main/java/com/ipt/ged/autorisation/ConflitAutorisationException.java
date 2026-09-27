package com.ipt.ged.autorisation;

/**
 * Conflit d'état du modèle de droits, rendu 409 : attribution déjà posée,
 * rattachement déjà présent, rôle encore attribué.
 *
 * <p>Provisoire : à la fusion du contrat d'erreurs de dev2 (problem+json), elle
 * deviendra une {@code ConflitException} (sous-classe d'{@code ExceptionMetier})
 * et {@link GestionErreursAutorisation} sera supprimé.
 */
public class ConflitAutorisationException extends RuntimeException {

    public ConflitAutorisationException(String message) {
        super(message);
    }
}
