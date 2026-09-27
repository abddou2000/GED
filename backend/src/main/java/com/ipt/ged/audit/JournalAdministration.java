package com.ipt.ged.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Traces des opérations d'administration des référentiels (espaces, types,
 * index, plans, circuits, étiquettes, groupes…), appelées par leurs services
 * (DAT §7.4.1 : « opération d'administration », valeurs avant et après).
 *
 * <p>Les valeurs avant et après sont celles de la représentation publique de
 * l'objet (le DTO renvoyé par l'API) : ce que l'Administrateur a vu et changé,
 * sans détail interne. Pour une modification, seuls les champs qui ont changé
 * sont conservés.
 *
 * <p>Écrit dans la transaction de l'opération (voir {@link AuditService}).
 */
@Component
public class JournalAdministration {

    private final AuditService audit;
    private final ObjectMapper json;

    public JournalAdministration(AuditService audit, ObjectMapper json) {
        this.audit = audit;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Création : valeurs créées. */
    public void cree(ActionAudit action, String objetType, UUID objetId, Object apres) {
        audit.enregistrer(EntreeAudit.de(action, objetType, objetId).avecApres(valeurs(apres)));
    }

    /** Modification : champs modifiés, avant et après. Aucune trace si rien n'a changé. */
    public void modifie(ActionAudit action, String objetType, UUID objetId, Object avant, Object apres) {
        Map<String, Object> a = valeurs(avant);
        Map<String, Object> b = valeurs(apres);
        Map<String, Object> avantModifie = new LinkedHashMap<>();
        Map<String, Object> apresModifie = new LinkedHashMap<>();
        for (String cle : union(a, b)) {
            Object va = a.get(cle);
            Object vb = b.get(cle);
            if (!Objects.equals(va, vb)) {
                avantModifie.put(cle, va);
                apresModifie.put(cle, vb);
            }
        }
        if (avantModifie.isEmpty()) return;
        audit.enregistrer(EntreeAudit.de(action, objetType, objetId).avecAvantApres(avantModifie, apresModifie));
    }

    /** Opération sans valeurs (suppression, restauration, archivage…). */
    public void action(ActionAudit action, String objetType, UUID objetId) {
        audit.enregistrer(EntreeAudit.de(action, objetType, objetId));
    }

    /** Opération avec un complément (déplacement : ancien et nouveau parent). */
    public void action(ActionAudit action, String objetType, UUID objetId,
                       Map<String, Object> avant, Map<String, Object> apres) {
        audit.enregistrer(EntreeAudit.de(action, objetType, objetId).avecAvantApres(avant, apres));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valeurs(Object representation) {
        if (representation == null) return Map.of();
        if (representation instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return json.convertValue(representation, LinkedHashMap.class);
    }

    private static java.util.Set<String> union(Map<String, Object> a, Map<String, Object> b) {
        java.util.Set<String> cles = new java.util.LinkedHashSet<>(a.keySet());
        cles.addAll(b.keySet());
        return cles;
    }
}
