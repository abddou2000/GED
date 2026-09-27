package com.ipt.ged.audit;

/**
 * Contrôle d'accès à la consultation et à l'export du journal (DAT §7.4.3 :
 * « écran réservé à l'Administrateur et à la Direction Générale »).
 *
 * <p>Point d'extension : le lot autorisation (E3) fournit l'implémentation
 * fondée sur la permission {@code CONSULTER_AUDIT}. D'ici là,
 * {@link ConfigurationAudit} déclare une garde qui exige un appelant
 * authentifié ; l'application n'a aujourd'hui qu'un compte, l'Administrateur.
 * Un refus lève {@link com.ipt.ged.common.erreur.AccesRefuseException}, tracé
 * au journal par le gestionnaire d'erreurs.
 */
@FunctionalInterface
public interface GardeConsultationAudit {

    /** Lève une exception si l'appelant n'a pas le droit de consulter le journal. */
    void verifier();
}
