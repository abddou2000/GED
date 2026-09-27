package com.ipt.ged.workspace.dto;

import java.util.List;
import java.util.UUID;

/**
 * Nœud de l'arborescence (vue « Arbre ») : un dossier + ses sous-dossiers imbriqués.
 *
 * @param status  {@code null} pour un nœud de passage
 * @param passage nœud non couvert par une habilitation de l'appelant, montré
 *                seulement parce qu'il mène à un nœud couvert (P5) : seul son
 *                libellé est visible, aucune action n'y est possible
 */
public record TreeNode(
        UUID id,
        String name,
        String status,
        UUID parentId,
        boolean passage,
        List<TreeNode> children
) {}
