package com.ipt.ged.autorisation;

/**
 * Conflit d'état, rendu 409 avec un code stable : attribution déjà posée,
 * rattachement déjà présent, rôle encore attribué, document verrouillé ou
 * archivé, nom déjà utilisé dans le dossier.
 *
 * <p>Provisoire : à la fusion du contrat d'erreurs de dev2 (problem+json), elle
 * deviendra une {@code ConflitException} (sous-classe d'{@code ExceptionMetier})
 * et {@link GestionErreursAutorisation} sera supprimé.
 */
public class ConflitAutorisationException extends RuntimeException {

    /** Code métier stable rendu dans la réponse (champ {@code code}). */
    private final String code;

    public ConflitAutorisationException(String message) {
        this("CONFLIT", message);
    }

    public ConflitAutorisationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
