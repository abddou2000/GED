package com.ipt.ged.workflow.circuit;

import com.ipt.ged.typedocument.TypeDocument;
import com.ipt.ged.workflow.WorkflowGed;
import com.ipt.ged.workspace.WorkSpace;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Règle de workflow applicable à un dépôt (§12.8) : la plus SPÉCIFIQUE
 * l'emporte. Le type de document est plus précis que tout emplacement (il
 * désigne la nature même de la pièce) ; à défaut, le nœud le plus proche en
 * remontant de l'emplacement principal vers l'espace. Une règle en corbeille
 * est ignorée, comme si elle n'était pas rattachée : on continue de remonter.
 */
@Component
public class ReglesApplicables {

    /** Origine d'une règle applicable. */
    public enum Origine { TYPE, NOEUD }

    public record RegleApplicable(WorkflowGed regle, Origine origine, UUID origineId) {}

    public Optional<RegleApplicable> pour(TypeDocument type, WorkSpace emplacement) {
        if (type != null && vivante(type.getRegleWorkflow())) {
            return Optional.of(new RegleApplicable(type.getRegleWorkflow(), Origine.TYPE, type.getId()));
        }
        Set<UUID> vus = new HashSet<>();
        for (WorkSpace n = emplacement; n != null && vus.add(n.getId()); n = n.getParent()) {
            if (vivante(n.getWorkflow())) {
                return Optional.of(new RegleApplicable(n.getWorkflow(), Origine.NOEUD, n.getId()));
            }
        }
        return Optional.empty();
    }

    private static boolean vivante(WorkflowGed r) {
        return r != null && !r.isSupprime();
    }
}
