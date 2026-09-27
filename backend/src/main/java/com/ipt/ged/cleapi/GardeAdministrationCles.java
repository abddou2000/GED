package com.ipt.ged.cleapi;

/**
 * Contrôle d'accès à l'administration des applications et des clés d'API
 * (DAT §5.4 : cycle de vie « piloté depuis l'interface d'administration »).
 *
 * <p>Point d'extension : le lot autorisation (E3) fournit la garde fondée sur
 * la permission d'administration « clés d'API » (§12.2.1). D'ici là,
 * {@link ConfigurationCleApi} exige un utilisateur authentifié qui ne soit pas
 * une application : une application ne gère jamais ses propres clés.
 */
@FunctionalInterface
public interface GardeAdministrationCles {

    void verifier();
}
