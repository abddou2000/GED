package com.ipt.ged.autorisation;

import org.springframework.security.access.AccessDeniedException;

/**
 * Objet visible, permission manquante : rendu 403. Hérite de
 * {@link AccessDeniedException} pour emprunter la traduction HTTP commune.
 */
public class PermissionRefuseeException extends AccessDeniedException {

    public PermissionRefuseeException(String message) {
        super(message);
    }
}
