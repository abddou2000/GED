package com.ipt.ged.fichier.cles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistance des DEK enveloppées (table {@code cle_fichier}).
 *
 * <p>Séparée du modèle JPA à dessein : la table est créée par un changeset
 * Liquibase indépendant et n'a pas besoin d'une entité pour être lue. La
 * supprimer d'une ligne est la <b>destruction cryptographique</b> du fichier
 * correspondant (§6.1.2, §12.5).
 */
public interface DepotClesFichier {

    void enregistrer(CleFichier cle);

    Optional<CleFichier> trouver(UUID id);

    /** @return {@code true} si une clé a été détruite. */
    boolean supprimer(UUID id);

    /**
     * Lot de clés qui ne sont pas enveloppées par {@code kekActive}, en ordre
     * d'identifiant strictement supérieur à {@code apres} (pagination par clé :
     * la rotation reste linéaire quelle que soit la taille du fonds).
     */
    List<CleFichier> lotHorsKek(String kekActive, UUID apres, int taille);

    /**
     * Remplace l'enveloppe si elle est toujours sous {@code ancienneKek}
     * (mise à jour conditionnelle : deux rotations concurrentes ne se
     * marchent pas dessus).
     *
     * @return {@code true} si la ligne a été mise à jour.
     */
    boolean remplacerEnveloppe(UUID id, String ancienneKek, CleEnveloppee nouvelle);

    long compterParKek(String kekId);
}
