package com.ipt.ged.autorisation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Droits résolus d'un sujet (dossier technique §12.2.2), à une version donnée
 * des habilitations. Immuable, partageable entre requêtes : c'est l'objet mis en
 * cache par {@link AccessPredicate}.
 *
 * <p>Ne contient que ce qui dépend du SUJET : les permissions effectives sur
 * chaque nœud (héritage et ruptures appliqués, accès global compris), les
 * habilitations de documents isolés, les permissions d'administration (portée
 * globale) et les deux permissions de confidentialité. Ce qui dépend du
 * DOCUMENT (emplacements, niveau, déposant, désignés) est lu au moment de la
 * décision, en SQL.
 */
public final class DroitsResolus {

    private static final Set<CodePermission> AUCUNE = Collections.unmodifiableSet(EnumSet.noneOf(CodePermission.class));

    private final Sujet sujet;
    private final long version;
    private final List<Attribution> attributions;
    private final Set<String> rolesGlobaux;
    private final Set<String> roles;
    private final Set<CodePermission> administration;
    private final boolean accesGlobal;
    private final Map<UUID, Set<CodePermission>> parNoeud;
    private final Map<UUID, Set<CodePermission>> parDocument;
    private final int nbNoeuds;
    private final boolean voirPrive;
    private final boolean voirConfidentiel;
    private final Map<CodePermission, Set<UUID>> noeudsParPermission = new ConcurrentHashMap<>();

    DroitsResolus(Sujet sujet, long version, List<Attribution> attributions, Set<String> rolesGlobaux,
                  Set<String> roles, Set<CodePermission> administration, boolean accesGlobal,
                  Map<UUID, Set<CodePermission>> parNoeud, Map<UUID, Set<CodePermission>> parDocument,
                  int nbNoeuds, boolean voirPrive, boolean voirConfidentiel) {
        this.sujet = sujet;
        this.version = version;
        this.attributions = List.copyOf(attributions);
        this.rolesGlobaux = Set.copyOf(rolesGlobaux);
        this.roles = Set.copyOf(roles);
        this.administration = Collections.unmodifiableSet(administration.isEmpty()
                ? EnumSet.noneOf(CodePermission.class) : EnumSet.copyOf(administration));
        this.accesGlobal = accesGlobal;
        this.parNoeud = Map.copyOf(parNoeud);
        this.parDocument = Map.copyOf(parDocument);
        this.nbNoeuds = nbNoeuds;
        this.voirPrive = voirPrive;
        this.voirConfidentiel = voirConfidentiel;
    }

    /** Aucun droit : appelant non reconnu. */
    static DroitsResolus aucun(long version) {
        return new DroitsResolus(null, version, List.of(), Set.of(), Set.of(), AUCUNE, false,
                Map.of(), Map.of(), 0, false, false);
    }

    public Sujet sujet() { return sujet; }

    public long version() { return version; }

    public List<Attribution> attributions() { return attributions; }

    /** Rôles détenus en portée globale. */
    public Set<String> rolesGlobaux() { return rolesGlobaux; }

    /** Rôles détenus, toute portée confondue. */
    public Set<String> roles() { return roles; }

    /** Détient un rôle à accès global (Direction Générale). */
    public boolean accesGlobal() { return accesGlobal; }

    /** Permissions effectives sur un nœud. */
    public Set<CodePermission> surNoeud(UUID noeudId) {
        return noeudId == null ? AUCUNE : parNoeud.getOrDefault(noeudId, AUCUNE);
    }

    public boolean peutSurNoeud(CodePermission p, UUID noeudId) {
        return surNoeud(noeudId).contains(p);
    }

    /** Permissions apportées par des habilitations posées sur le document lui-même. */
    public Set<CodePermission> surDocumentIsole(UUID documentId) {
        return documentId == null ? AUCUNE : parDocument.getOrDefault(documentId, AUCUNE);
    }

    /** Nœuds sur lesquels le sujet détient la permission. */
    public Set<UUID> noeuds(CodePermission p) {
        return noeudsParPermission.computeIfAbsent(p, cle -> {
            Set<UUID> s = new HashSet<>();
            parNoeud.forEach((id, perms) -> { if (perms.contains(cle)) s.add(id); });
            return Collections.unmodifiableSet(s);
        });
    }

    /** La permission vaut sur TOUS les nœuds existants : le filtre d'emplacement devient inutile. */
    public boolean partout(CodePermission p) {
        return nbNoeuds > 0 && noeuds(p).size() == nbNoeuds;
    }

    /** Documents isolés sur lesquels le sujet détient la permission. */
    public Set<UUID> documents(CodePermission p) {
        Set<UUID> s = new HashSet<>();
        parDocument.forEach((id, perms) -> { if (perms.contains(p)) s.add(id); });
        return s;
    }

    /** Permissions d'administration (portée globale uniquement). */
    public Set<CodePermission> administration() { return administration; }

    public boolean administre(CodePermission p) { return administration.contains(p); }

    public boolean voirPrive() { return voirPrive; }

    public boolean voirConfidentiel() { return voirConfidentiel; }

    /**
     * Toutes les permissions que le sujet exerce quelque part : ce que
     * l'interface reçoit pour masquer menus et actions (confort seulement ; le
     * serveur décide à chaque requête).
     */
    public Set<CodePermission> permissionsExercees() {
        EnumSet<CodePermission> s = EnumSet.noneOf(CodePermission.class);
        parNoeud.values().forEach(s::addAll);
        parDocument.values().forEach(s::addAll);
        s.addAll(administration);
        if (voirPrive) s.add(CodePermission.VOIR_PRIVE);
        if (voirConfidentiel) s.add(CodePermission.VOIR_CONFIDENTIEL);
        return s;
    }
}
