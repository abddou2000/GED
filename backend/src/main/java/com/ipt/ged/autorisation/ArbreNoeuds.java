package com.ipt.ged.autorisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Instantané de l'arborescence des nœuds (espaces et dossiers), vivants ou en
 * corbeille, dans l'ordre du chemin matérialisé : tout parent précède ses
 * descendants. Immuable ; rechargé quand {@code version_habilitations} change.
 */
public final class ArbreNoeuds {

    /** Un nœud de l'instantané. */
    public record Noeud(UUID id, UUID parentId, String chemin, String nom, boolean supprime, String statut) {

        /** Identifiants des ancêtres, de l'espace au parent, lus dans le chemin. */
        public List<UUID> ancetres() {
            List<UUID> l = new ArrayList<>();
            for (String s : chemin.split("/")) {
                if (!s.isEmpty()) l.add(UUID.fromString(s));
            }
            l.remove(l.size() - 1);
            return l;
        }
    }

    private final Map<UUID, Noeud> parId;

    public ArbreNoeuds(List<Noeud> ordonnes) {
        Map<UUID, Noeud> m = new LinkedHashMap<>();
        for (Noeud n : ordonnes) m.put(n.id(), n);
        this.parId = Collections.unmodifiableMap(m);
    }

    /** Nœuds, parents avant enfants. */
    public Iterable<Noeud> ordonnes() {
        return parId.values();
    }

    public Noeud noeud(UUID id) {
        return parId.get(id);
    }

    public boolean contient(UUID id) {
        return parId.containsKey(id);
    }

    public int taille() {
        return parId.size();
    }

    /** Index enfants par parent, pour les parcours descendants. */
    public Map<UUID, List<UUID>> enfants() {
        Map<UUID, List<UUID>> m = new HashMap<>();
        for (Noeud n : parId.values()) {
            if (n.parentId() != null) m.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n.id());
        }
        return m;
    }
}
