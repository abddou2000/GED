package com.ipt.ged.cycledevie;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Permissions provisoires du cycle de vie, en attendant le moteur de droits
 * (E3, dev1) : tout utilisateur authentifié. Aucun rôle n'existe encore dans
 * l'application ; le lot E3 fournit son propre {@link AutorisationsCycleDeVie}.
 */
public class AutorisationsCycleDeVieProvisoires implements AutorisationsCycleDeVie {

    private static boolean authentifie() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated();
    }

    @Override
    public boolean peutPurger(UUID documentId) {
        return authentifie();
    }

    @Override
    public boolean peutArchiver(UUID documentId) {
        return authentifie();
    }

    @Override
    public boolean peutArchiverDossier(UUID dossierId) {
        return authentifie();
    }

    @Override
    public boolean peutDesarchiver(UUID documentId) {
        return authentifie();
    }
}
