package com.ipt.ged.workspace.dto;

import java.util.List;

/**
 * Nœud de l'arborescence (vue « Arbre ») : un dossier + ses sous-dossiers imbriqués.
 */
public record TreeNode(
        Long id,
        String name,
        String status,
        Long parentId,
        List<TreeNode> children
) {}
