package com.ipt.ged.workspace.dto;

import java.util.List;
import java.util.UUID;

/**
 * Nœud de l'arborescence (vue « Arbre ») : un dossier + ses sous-dossiers imbriqués.
 */
public record TreeNode(
        UUID id,
        String name,
        String status,
        UUID parentId,
        List<TreeNode> children
) {}
