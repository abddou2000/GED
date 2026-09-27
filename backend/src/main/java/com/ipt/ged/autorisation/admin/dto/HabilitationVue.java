package com.ipt.ged.autorisation.admin.dto;

import com.ipt.ged.autorisation.TypeSujet;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Attribution telle que l'écran d'administration l'affiche. */
public record HabilitationVue(UUID id, TypeSujet sujetType, UUID sujetId, String sujetLibelle,
                              UUID roleId, String roleCode, String roleLibelle,
                              UUID noeudId, String noeudLibelle, UUID documentId, String documentLibelle,
                              boolean ruptureHeritage, Instant creeLe) {

    /** Portée lisible : Globale, nœud ou document. */
    public String portee() {
        if (noeudId != null) return "NOEUD";
        if (documentId != null) return "DOCUMENT";
        return "GLOBALE";
    }

    /** Instantané pour l'audit (valeurs avant / après). */
    public Map<String, Object> instantane() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("sujetType", sujetType);
        m.put("sujetId", sujetId);
        m.put("sujet", sujetLibelle);
        m.put("role", roleCode);
        m.put("noeudId", noeudId);
        m.put("noeud", noeudLibelle);
        m.put("documentId", documentId);
        m.put("ruptureHeritage", ruptureHeritage);
        return m;
    }
}
