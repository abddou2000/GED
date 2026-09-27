package com.ipt.ged.autorisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Règle de résolution des droits (dossier technique §12.2.2), sans accès à la
 * base : attributions et arborescence en entrée, droits résolus en sortie.
 *
 * <h2>Règle</h2>
 * <ol>
 *   <li><b>Le plus spécifique prévaut</b> : sur un nœud N, si des attributions
 *       explicites existent pour le sujet (directement ou par ses groupes) AU
 *       NIVEAU de N, elles s'appliquent et REMPLACENT l'héritage — qu'elles
 *       étendent ou restreignent le périmètre ; sinon N hérite de son parent,
 *       et un espace hérite de la portée globale.</li>
 *   <li><b>Rupture d'héritage</b> : une attribution de rupture sans rôle est une
 *       attribution explicite qui n'apporte rien — aucun droit sur N ni en
 *       dessous, sauf attribution plus basse.</li>
 *   <li><b>Cumul</b> : à un même niveau, les permissions des attributions
 *       applicables s'additionnent (union).</li>
 *   <li><b>Document isolé</b> : une attribution posée sur un document s'ajoute,
 *       pour ce document, aux droits hérités de chacun de ses emplacements.</li>
 *   <li><b>Accès global</b> (Direction Générale) : les permissions d'un rôle à
 *       accès global valent sur tout nœud, existant ou futur, sans ligne
 *       d'habilitation par nœud ni effet des ruptures.</li>
 *   <li><b>Administration</b> : les permissions d'administration ne s'exercent
 *       qu'en portée globale ; sur un nœud ou un document, seules les
 *       permissions élémentaires comptent.</li>
 *   <li><b>Confidentialité</b> : {@code VOIR_PRIVE} et {@code VOIR_CONFIDENTIEL}
 *       sont portées par le sujet dès qu'un de ses rôles les contient (§12.3 :
 *       « le sujet porte VOIR_PRIVE »).</li>
 * </ol>
 */
public final class ResolveurDroits {

    private static final Set<CodePermission> ELEMENTAIRES = CodePermission.de(CodePermission.Categorie.ELEMENTAIRE);
    private static final Set<CodePermission> ADMINISTRATION = CodePermission.de(CodePermission.Categorie.ADMINISTRATION);

    private ResolveurDroits() {}

    public static DroitsResolus resoudre(Sujet sujet, List<Attribution> attributions, ArbreNoeuds arbre,
                                         long version) {
        EnumSet<CodePermission> global = EnumSet.noneOf(CodePermission.class);
        EnumSet<CodePermission> accesGlobal = EnumSet.noneOf(CodePermission.class);
        EnumSet<CodePermission> toutes = EnumSet.noneOf(CodePermission.class);
        Map<UUID, List<Attribution>> explicites = new HashMap<>();
        Map<UUID, Set<CodePermission>> parDocument = new HashMap<>();
        Set<String> rolesGlobaux = new TreeSet<>();
        Set<String> roles = new TreeSet<>();
        boolean aAccesGlobal = false;

        for (Attribution a : attributions) {
            toutes.addAll(a.permissions());
            if (a.roleCode() != null) {
                roles.add(a.roleCode());
                if (a.globale()) rolesGlobaux.add(a.roleCode());
            }
            if (a.accesGlobal()) {
                aAccesGlobal = true;
                accesGlobal.addAll(elementaires(a.permissions()));
            }
            if (a.globale()) {
                global.addAll(a.permissions());
            } else if (a.noeudId() != null) {
                explicites.computeIfAbsent(a.noeudId(), k -> new ArrayList<>()).add(a);
            } else {
                parDocument.computeIfAbsent(a.documentId(), k -> EnumSet.noneOf(CodePermission.class))
                        .addAll(elementaires(a.permissions()));
            }
        }

        // Parcours descendant : chaque parent est résolu avant ses enfants.
        // Les ensembles hérités sont PARTAGÉS (même instance) : un arbre de
        // plusieurs milliers de nœuds ne coûte qu'une entrée de table par nœud.
        Set<CodePermission> racine = figer(elementaires(global));
        Map<UUID, Set<CodePermission>> sansAccesGlobal = new HashMap<>();
        Map<UUID, Set<CodePermission>> parNoeud = new HashMap<>();
        Map<Set<CodePermission>, Set<CodePermission>> avecAccesGlobal = new HashMap<>();
        for (ArbreNoeuds.Noeud n : arbre.ordonnes()) {
            List<Attribution> ici = explicites.get(n.id());
            Set<CodePermission> base;
            if (ici != null) {
                EnumSet<CodePermission> union = EnumSet.noneOf(CodePermission.class);
                for (Attribution a : ici) union.addAll(elementaires(a.permissions()));
                base = figer(union);
            } else if (n.parentId() != null && sansAccesGlobal.containsKey(n.parentId())) {
                base = sansAccesGlobal.get(n.parentId());
            } else {
                base = racine;
            }
            sansAccesGlobal.put(n.id(), base);
            Set<CodePermission> effectif = accesGlobal.isEmpty() ? base
                    : avecAccesGlobal.computeIfAbsent(base, b -> {
                        EnumSet<CodePermission> u = EnumSet.copyOf(accesGlobal);
                        u.addAll(b);
                        return figer(u);
                    });
            if (!effectif.isEmpty()) parNoeud.put(n.id(), effectif);
        }

        EnumSet<CodePermission> admin = EnumSet.noneOf(CodePermission.class);
        for (CodePermission p : global) if (ADMINISTRATION.contains(p)) admin.add(p);

        Map<UUID, Set<CodePermission>> documents = new HashMap<>();
        parDocument.forEach((id, s) -> documents.put(id, figer(s)));
        return new DroitsResolus(sujet, version, attributions, rolesGlobaux, roles, admin, aAccesGlobal,
                parNoeud, documents, arbre.taille(),
                toutes.contains(CodePermission.VOIR_PRIVE), toutes.contains(CodePermission.VOIR_CONFIDENTIEL));
    }

    /* ------------------------------------------------------------ explication */

    /**
     * Origine d'une permission effective (§12.2.3, consultation des droits effectifs).
     *
     * @param nature           ATTRIBUTION_DIRECTE, HERITAGE, PORTEE_GLOBALE, ACCES_GLOBAL, RUPTURE, DOCUMENT_ISOLE
     * @param noeudAttribution nœud où l'attribution est posée ({@code null} : globale ou document)
     */
    public record Origine(String nature, String roleCode, UUID habilitationId, UUID noeudAttribution,
                          TypeSujet viaType, UUID viaId, String viaLibelle, Set<CodePermission> permissions) {}

    /** Attributions qui déterminent les droits du sujet sur un nœud, avec leur nature. */
    public static List<Origine> expliquerNoeud(List<Attribution> attributions, ArbreNoeuds arbre, UUID noeudId) {
        ArbreNoeuds.Noeud n = arbre.noeud(noeudId);
        if (n == null) return List.of();
        List<UUID> remontee = new ArrayList<>(n.ancetres());
        remontee.add(noeudId);
        Collections.reverse(remontee);

        List<Origine> origines = new ArrayList<>();
        boolean trouve = false;
        for (UUID niveau : remontee) {
            List<Attribution> ici = attributions.stream().filter(a -> niveau.equals(a.noeudId())).toList();
            if (ici.isEmpty()) continue;
            trouve = true;
            for (Attribution a : ici) {
                String nature = a.roleCode() == null && a.rupture() ? "RUPTURE"
                        : niveau.equals(noeudId) ? "ATTRIBUTION_DIRECTE" : "HERITAGE";
                origines.add(origine(nature, a, elementaires(a.permissions())));
            }
            break;
        }
        if (!trouve) {
            for (Attribution a : attributions) {
                if (a.globale() && !elementaires(a.permissions()).isEmpty()) {
                    origines.add(origine("PORTEE_GLOBALE", a, elementaires(a.permissions())));
                }
            }
        }
        for (Attribution a : attributions) {
            if (a.accesGlobal()) origines.add(origine("ACCES_GLOBAL", a, elementaires(a.permissions())));
        }
        return origines;
    }

    /** Attributions posées sur le document lui-même. */
    public static List<Origine> expliquerDocumentIsole(List<Attribution> attributions, UUID documentId) {
        List<Origine> l = new ArrayList<>();
        for (Attribution a : attributions) {
            if (documentId.equals(a.documentId())) l.add(origine("DOCUMENT_ISOLE", a, elementaires(a.permissions())));
        }
        return l;
    }

    private static Origine origine(String nature, Attribution a, Set<CodePermission> perms) {
        return new Origine(nature, a.roleCode(), a.origineId(), a.noeudId(), a.viaType(), a.viaId(),
                a.viaLibelle(), perms);
    }

    /* ------------------------------------------------------------ outillage */

    static Set<CodePermission> elementaires(Set<CodePermission> perms) {
        EnumSet<CodePermission> s = EnumSet.noneOf(CodePermission.class);
        for (CodePermission p : perms) if (ELEMENTAIRES.contains(p)) s.add(p);
        return s;
    }

    private static Set<CodePermission> figer(Set<CodePermission> s) {
        return Collections.unmodifiableSet(s.isEmpty() ? EnumSet.noneOf(CodePermission.class) : EnumSet.copyOf(s));
    }

}
