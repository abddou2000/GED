package com.ipt.ged.fichier.cles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rotation de la clé maîtresse (§6.1.2) : annuelle par défaut, immédiate en
 * cas de compromission.
 *
 * <p>La rotation <b>réenveloppe</b> les DEK (60 octets par fichier) et ne
 * rechiffre aucun fichier : sur un fonds de plusieurs téraoctets, rechiffrer
 * prendrait des jours et exposerait chaque fichier à une réécriture, alors que
 * seule la KEK a changé. Les anciennes KEK restent dans le keystore,
 * désactivées, tant que des DEK ou des sauvegardes les référencent.
 *
 * <p>Idempotente et reprenable : une rotation interrompue se termine en
 * relançant {@link #reenvelopper()}, qui ne traite que les DEK encore sous une
 * ancienne KEK.
 */
public class RotationKek {

    private static final Logger log = LoggerFactory.getLogger(RotationKek.class);
    private static final int LOT = 500;

    private final KeyProvider keyProvider;
    private final DepotClesFichier depot;

    public RotationKek(KeyProvider keyProvider, DepotClesFichier depot) {
        this.keyProvider = keyProvider;
        this.depot = depot;
    }

    /** Crée une nouvelle KEK active puis réenveloppe toutes les DEK. */
    public Rapport executer() {
        String ancienne = keyProvider.kekActive();
        String nouvelle = keyProvider.nouvelleKek();
        log.warn("Rotation de la KEK : {} -> {}. Sauvegarder le keystore dès la fin de l'opération.", ancienne, nouvelle);
        return reenvelopper();
    }

    /** Réenveloppe sous la KEK active toutes les DEK qui ne le sont pas encore. */
    public Rapport reenvelopper() {
        String active = keyProvider.kekActive();
        int traitees = 0;
        List<UUID> echecs = new ArrayList<>();
        UUID curseur = null;
        List<CleFichier> lot;
        do {
            lot = depot.lotHorsKek(active, curseur, LOT);
            for (CleFichier cle : lot) {
                curseur = cle.id();
                byte[] dek = null;
                try {
                    byte[] contexte = ContexteCle.de(cle.id());
                    dek = keyProvider.desenvelopper(cle.enveloppe(), contexte);
                    CleEnveloppee nouvelle = keyProvider.envelopper(dek, contexte);
                    if (depot.remplacerEnveloppe(cle.id(), cle.kekId(), nouvelle)) traitees++;
                } catch (RuntimeException e) {
                    // Une DEK illisible ne doit pas bloquer la rotation des
                    // autres : elle est signalée et laissée sous son ancienne
                    // KEK, que l'on ne pourra donc pas retirer.
                    log.error("Réenveloppement impossible pour le fichier {}", cle.id(), e);
                    echecs.add(cle.id());
                } finally {
                    KeystoreKeyProvider.effacer(dek);
                }
            }
        } while (lot.size() == LOT);
        log.info("Réenveloppement terminé sous {} : {} DEK traitées, {} échec(s)", active, traitees, echecs.size());
        return new Rapport(active, traitees, List.copyOf(echecs));
    }

    /**
     * Retire une KEK désactivée, seulement si plus aucune DEK ne l'utilise.
     * Les sauvegardes qui la référencent encore ne sont pas visibles d'ici :
     * c'est à l'exploitant de confirmer qu'elles sont expirées.
     */
    public void retirer(String kekId) {
        long restantes = depot.compterParKek(kekId);
        if (restantes > 0) {
            throw new IllegalStateException(restantes + " DEK sont encore enveloppées par " + kekId
                    + " : lancer le réenveloppement avant de la retirer.");
        }
        keyProvider.retirer(kekId);
    }

    public record Rapport(String kekActive, int traitees, List<UUID> echecs) {
    }
}
