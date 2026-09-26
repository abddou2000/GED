package com.ipt.ged.fichier.controle;

import java.util.Collection;
import java.util.Set;

/**
 * Règles de dépôt d'un type documentaire : taille maximale et types réels
 * admis. Construites par {@link ControleFichiers#regles}, qui applique le
 * plafond de plateforme et la liste blanche par défaut.
 */
public record ReglesDepot(long tailleMaxOctets, Set<String> typesAdmis, Collection<String> formatsAffiches) {

    public boolean admet(String typeMime) {
        return typesAdmis.contains(typeMime);
    }
}
