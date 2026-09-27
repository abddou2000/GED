package com.ipt.ged.audit;

import com.ipt.ged.common.erreur.RequeteInvalideException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Critères de consultation du journal (DAT §7.4.3) : période, utilisateur,
 * application, action, objet et résultat. Tous facultatifs ; la période est
 * bornée par défaut pour que la base élague les partitions inutiles.
 */
public record FiltreAudit(Instant du, Instant au, UUID utilisateur, UUID application, String action,
                          String objetType, UUID objetId, ResultatAudit resultat) {

    public FiltreAudit {
        if (du != null && au != null && !au.isAfter(du)) {
            throw new RequeteInvalideException("La fin de période doit suivre son début.");
        }
        action = normaliser(action);
        objetType = normaliser(objetType);
    }

    private static String normaliser(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw new RequeteInvalideException("Code invalide : « " + code + " ».");
        }
        return c;
    }

    /** Clause WHERE paramétrée (jamais de valeur concaténée). */
    Clause clause() {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        List<Object> p = new ArrayList<>();
        if (du != null) { sql.append(" AND horodatage >= ?"); p.add(java.sql.Timestamp.from(du)); }
        if (au != null) { sql.append(" AND horodatage < ?"); p.add(java.sql.Timestamp.from(au)); }
        if (utilisateur != null) { sql.append(" AND acteur_utilisateur_id = ?"); p.add(utilisateur); }
        if (application != null) { sql.append(" AND acteur_application_id = ?"); p.add(application); }
        if (action != null) { sql.append(" AND action = ?"); p.add(action); }
        if (objetType != null) { sql.append(" AND objet_type = ?"); p.add(objetType); }
        if (objetId != null) { sql.append(" AND objet_id = ?"); p.add(objetId); }
        if (resultat != null) { sql.append(" AND resultat = ?"); p.add(resultat.name()); }
        return new Clause(sql.toString(), p.toArray());
    }

    /** Critères tels qu'ils figurent dans la trace de consultation et en tête d'export. */
    Map<String, Object> commeValeurs() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (du != null) m.put("du", du.toString());
        if (au != null) m.put("au", au.toString());
        if (utilisateur != null) m.put("utilisateur", utilisateur.toString());
        if (application != null) m.put("application", application.toString());
        if (action != null) m.put("action", action);
        if (objetType != null) m.put("objetType", objetType);
        if (objetId != null) m.put("objetId", objetId.toString());
        if (resultat != null) m.put("resultat", resultat.name());
        return m;
    }

    record Clause(String sql, Object[] parametres) {}
}
