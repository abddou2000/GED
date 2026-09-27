package com.ipt.ged.supervision;

/**
 * Point d'extension de la sonde {@code antivirus} : « le moteur antivirus
 * répond-il ? ».
 *
 * <p>Si un bean de ce type existe, la sonde l'utilise ; sinon elle interroge
 * clamd elle-même ({@code zPING}). Une fois le lot stockage (E5) intégré, on
 * branche ici le client de dépôt, pour que la sonde teste EXACTEMENT le
 * chemin qu'emprunte un dépôt :
 * <pre>{@code
 * @Bean
 * VerificationAntivirus verificationAntivirus(AnalyseurAntivirus analyseur) {
 *     return analyseur::disponible;
 * }
 * }</pre>
 */
@FunctionalInterface
public interface VerificationAntivirus {

    /** Le moteur répond-il ? Ne doit jamais lever d'exception ni bloquer au-delà de son délai. */
    boolean disponible();
}
