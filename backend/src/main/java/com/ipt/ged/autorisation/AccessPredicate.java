package com.ipt.ged.autorisation;

import com.ipt.ged.document.DocumentConfidentielDesigne;
import com.ipt.ged.document.DocumentRattachement;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.recherche.FragmentSql;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>Point d'application unique des droits</b> (dossier technique §12.2.3).
 *
 * <p>Produit, pour un sujet, (a) l'ensemble des nœuds sur lesquels il détient
 * une permission et (b) le prédicat de confidentialité ; en dérive la fonction
 * de décision {@link #peut(Authentication, CodePermission, UUID)} et les
 * filtres « à la source » — {@link #documents spécification JPA} pour les
 * listes et compteurs, {@link #predicatSql fragment SQL} pour la recherche.
 * Aucun autre module ne réimplémente la règle : ils appellent ce service (ou
 * {@link ControleAcces}, sa façade d'exceptions).
 *
 * <h2>Décision</h2>
 * <p>{@code peut(sujet, permission, document)} est vraie s'il existe au moins
 * un emplacement du document (principal ou rattachement) sur lequel les droits
 * résolus du sujet comprennent la permission — UNION des emplacements, jamais
 * l'intersection —, ou une habilitation sur le document lui-même, ET si le
 * niveau de confidentialité l'autorise (§12.3) : PUBLIC toujours ; PRIVE si le
 * sujet est le déposant ou porte VOIR_PRIVE ; CONFIDENTIEL s'il est désigné ou
 * porte VOIR_CONFIDENTIEL.
 *
 * <h2>Cache</h2>
 * <p>Les droits résolus sont gardés par sujet, avec la valeur de
 * {@code version_habilitations} de leur calcul ; toute modification de droit,
 * de groupe, de rôle ou d'arborescence incrémente ce compteur, et la valeur lue
 * à chaque décision invalide l'entrée : effet immédiat. Le cache est borné
 * ({@code ged.autorisation.cache.taille}, plus ancien usage évincé).
 */
@Service
public class AccessPredicate {

    private final List<SourceHabilitations> sources;
    private final VersionHabilitations version;
    private final JdbcTemplate jdbc;
    private final Map<String, DroitsResolus> cache;
    /** Arbre en cache, avec la version de son chargement (un seul objet : lecture cohérente). */
    private record ArbreVersionne(long version, ArbreNoeuds arbre) {}

    private final AtomicReference<ArbreVersionne> arbre = new AtomicReference<>();

    @PersistenceContext
    private EntityManager em;

    public AccessPredicate(List<SourceHabilitations> sources, VersionHabilitations version, JdbcTemplate jdbc,
                           @Value("${ged.autorisation.cache.taille:1000}") int tailleCache) {
        this.sources = List.copyOf(sources);
        this.version = version;
        this.jdbc = jdbc;
        int max = Math.max(1, tailleCache);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DroitsResolus> eldest) {
                return size() > max;
            }
        });
    }

    /* ================================================================ droits résolus */

    /** Droits de l'appelant de la requête en cours. */
    public DroitsResolus droitsCourants() {
        return droits(SecurityContextHolder.getContext().getAuthentication());
    }

    public DroitsResolus droits(Authentication authentification) {
        synchroniser();
        long v = version.lire();
        Optional<Sujet> sujet = sujet(authentification);
        return sujet.isPresent() ? droits(sujet.get(), v) : DroitsResolus.aucun(v);
    }

    public DroitsResolus droits(Sujet sujet) {
        synchroniser();
        return droits(sujet, version.lire());
    }

    /**
     * Les décisions lisent la base en SQL (compteur, arborescence, faits du
     * document) : les écritures JPA encore en attente dans la transaction
     * courante — un nœud ou un rattachement créé juste avant — doivent y être
     * d'abord poussées, sinon la décision porterait sur un état antérieur.
     */
    private void synchroniser() {
        if (em != null && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && em.isJoinedToTransaction()) {
            em.flush();
        }
    }

    /** Sujet reconnu par la première source qui le connaît. */
    public Optional<Sujet> sujet(Authentication authentification) {
        if (authentification == null || !authentification.isAuthenticated()) return Optional.empty();
        for (SourceHabilitations s : sources) {
            Optional<Sujet> sujet = s.sujet(authentification);
            if (sujet.isPresent()) return sujet;
        }
        return Optional.empty();
    }

    /** Attributions de toutes les sources (union). */
    public List<Attribution> attributions(Sujet sujet) {
        List<Attribution> toutes = new ArrayList<>();
        for (SourceHabilitations s : sources) toutes.addAll(s.attributions(sujet));
        return toutes;
    }

    private DroitsResolus droits(Sujet sujet, long v) {
        DroitsResolus enCache = cache.get(sujet.cle());
        if (enCache != null && enCache.version() == v) return enCache;
        DroitsResolus calcule = ResolveurDroits.resoudre(sujet, attributions(sujet), arbre(v), v);
        cache.put(sujet.cle(), calcule);
        return calcule;
    }

    /** Arborescence courante (vivants et corbeille), rechargée à chaque changement de version. */
    public ArbreNoeuds arbre() {
        return arbre(version.lire());
    }

    private ArbreNoeuds arbre(long v) {
        ArbreVersionne a = arbre.get();
        if (a != null && a.version() == v) return a.arbre();
        List<ArbreNoeuds.Noeud> noeuds = jdbc.query(
                "SELECT id, parent_id, chemin, nom, supprime, status FROM noeud ORDER BY chemin",
                (rs, i) -> new ArbreNoeuds.Noeud(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6)));
        ArbreNoeuds charge = new ArbreNoeuds(noeuds);
        arbre.set(new ArbreVersionne(v, charge));
        return charge;
    }

    /* ================================================================ nœuds */

    /** Nœuds sur lesquels l'appelant détient la permission (§12.2.3 a). */
    public Set<UUID> noeudsAccessibles(Authentication authentification, CodePermission permission) {
        return droits(authentification).noeuds(permission);
    }

    public boolean peutSurNoeud(Authentication authentification, CodePermission permission, UUID noeudId) {
        return droits(authentification).peutSurNoeud(permission, noeudId);
    }

    /**
     * Nœuds que l'arborescence peut montrer (P5) : ceux que couvre une
     * habilitation du sujet (au moins une permission), plus leurs ancêtres —
     * nœuds de PASSAGE, dont seul le libellé est visible.
     *
     * @return pour chaque nœud visible, {@code true} s'il est couvert,
     *         {@code false} s'il n'est qu'un passage
     */
    public Map<UUID, Boolean> noeudsVisibles(Authentication authentification) {
        DroitsResolus d = droits(authentification);
        ArbreNoeuds a = arbre();
        Map<UUID, Boolean> visibles = new HashMap<>();
        for (ArbreNoeuds.Noeud n : a.ordonnes()) {
            if (!d.surNoeud(n.id()).isEmpty()) {
                visibles.put(n.id(), true);
                for (UUID anc : n.ancetres()) visibles.putIfAbsent(anc, false);
            }
        }
        return visibles;
    }

    /* ================================================================ documents */

    /** Ce qui, dans un document, entre dans la décision. */
    private record FaitsDocument(UUID principal, Confidentialite niveau, UUID deposant, List<UUID> rattachements,
                                 boolean designe) {}

    private Optional<FaitsDocument> faits(UUID documentId, UUID utilisateurId) {
        synchroniser();
        List<FaitsDocument> l = jdbc.query("""
                SELECT d.noeud_principal_id, d.confidentialite, d.created_by_employe_id,
                       ARRAY(SELECT r.noeud_id FROM document_rattachement r WHERE r.document_id = d.id),
                       EXISTS (SELECT 1 FROM document_confidentiel_designe x
                                WHERE x.document_id = d.id AND x.utilisateur_id = ?)
                  FROM document d WHERE d.id = ?""",
                (rs, i) -> new FaitsDocument(rs.getObject(1, UUID.class),
                        Confidentialite.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                        uuids(rs.getArray(4)), rs.getBoolean(5)),
                utilisateurId, documentId);
        return l.stream().findFirst();
    }

    private static List<UUID> uuids(Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        Object[] valeurs = (Object[]) a.getArray();
        List<UUID> l = new ArrayList<>(valeurs.length);
        for (Object o : valeurs) l.add((UUID) o);
        return l;
    }

    /** La règle de confidentialité autorise-t-elle ce sujet (§12.3) ? */
    private static boolean confidentialiteAutorise(DroitsResolus d, FaitsDocument f) {
        Sujet s = d.sujet();
        return switch (f.niveau()) {
            case PUBLIC -> true;
            case PRIVE -> d.voirPrive() || (s != null && s.employeId() != null && s.employeId().equals(f.deposant()));
            case CONFIDENTIEL -> d.voirConfidentiel() || f.designe();
        };
    }

    /**
     * Permissions de l'appelant sur un document : union de ses emplacements et
     * des habilitations posées sur le document, SOUS RÉSERVE de la
     * confidentialité. Vide si le document n'existe pas ou est hors périmètre.
     * Un document en corbeille garde ses droits (la corbeille se consulte).
     */
    public Set<CodePermission> permissionsSurDocument(Authentication authentification, UUID documentId) {
        return permissionsSurDocument(droits(authentification), documentId);
    }

    /** Même décision, pour des droits déjà résolus (écran des droits effectifs). */
    public Set<CodePermission> permissionsSurDocument(DroitsResolus d, UUID documentId) {
        if (d.sujet() == null) return Set.of();
        UUID utilisateur = d.sujet().type() == TypeSujet.UTILISATEUR ? d.sujet().id() : null;
        Optional<FaitsDocument> f = faits(documentId, utilisateur);
        if (f.isEmpty() || !confidentialiteAutorise(d, f.get())) return Set.of();
        EnumSet<CodePermission> perms = EnumSet.noneOf(CodePermission.class);
        perms.addAll(d.surNoeud(f.get().principal()));
        for (UUID r : f.get().rattachements()) perms.addAll(d.surNoeud(r));
        perms.addAll(d.surDocumentIsole(documentId));
        return perms;
    }

    /**
     * Verdict de confidentialité, avec la règle qui l'emporte (écran des droits
     * effectifs, P-22).
     *
     * @param motif PUBLIC, DEPOSANT, VOIR_PRIVE, DESIGNE, VOIR_CONFIDENTIEL ou REFUS
     */
    public record VerdictConfidentialite(Confidentialite niveau, boolean autorise, String motif,
                                         UUID principal, List<UUID> rattachements) {}

    public Optional<VerdictConfidentialite> confidentialite(DroitsResolus d, UUID documentId) {
        if (d.sujet() == null) return Optional.empty();
        UUID utilisateur = d.sujet().type() == TypeSujet.UTILISATEUR ? d.sujet().id() : null;
        return faits(documentId, utilisateur).map(f -> {
            Sujet s = d.sujet();
            String motif = switch (f.niveau()) {
                case PUBLIC -> "PUBLIC";
                case PRIVE -> s.employeId() != null && s.employeId().equals(f.deposant()) ? "DEPOSANT"
                        : d.voirPrive() ? "VOIR_PRIVE" : "REFUS";
                case CONFIDENTIEL -> f.designe() ? "DESIGNE" : d.voirConfidentiel() ? "VOIR_CONFIDENTIEL" : "REFUS";
            };
            return new VerdictConfidentialite(f.niveau(), !"REFUS".equals(motif), motif, f.principal(),
                    f.rattachements());
        });
    }

    /** Le document existe-t-il (corbeille comprise) ? Pour distinguer absent et hors périmètre. */
    public boolean existeDocument(UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document WHERE id = ?)",
                Boolean.class, documentId));
    }

    /** Fonction de décision {@code peut(sujet, permission, document)} (§12.2.3). */
    public boolean peut(Authentication authentification, CodePermission permission, UUID documentId) {
        return permissionsSurDocument(authentification, documentId).contains(permission);
    }

    /**
     * Filtre JPA « à la source » des documents : emplacement accessible (ou
     * habilitation du document) ET confidentialité. S'applique aux listes,
     * totaux et compteurs : un document hors périmètre n'est ni listé ni compté.
     */
    public Specification<UploadDocument> documents(Authentication authentification, CodePermission permission) {
        DroitsResolus d = droits(authentification);
        return (root, query, cb) -> {
            if (d.sujet() == null) return cb.disjunction();
            return cb.and(emplacement(d, permission, root, query, cb), confidentialite(d, root, query, cb));
        };
    }

    private static Predicate emplacement(DroitsResolus d, CodePermission p, Root<UploadDocument> root,
                                         jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) {
        if (d.partout(p)) return cb.conjunction();
        Set<UUID> noeuds = d.noeuds(p);
        Set<UUID> isoles = d.documents(p);
        List<Predicate> ou = new ArrayList<>();
        if (!noeuds.isEmpty()) {
            ou.add(root.get("workspace").get("id").in(noeuds));
            Subquery<Integer> r = query.subquery(Integer.class);
            Root<DocumentRattachement> rr = r.from(DocumentRattachement.class);
            r.select(cb.literal(1)).where(cb.equal(rr.get("document"), root),
                    rr.get("noeud").get("id").in(noeuds));
            ou.add(cb.exists(r));
        }
        if (!isoles.isEmpty()) ou.add(root.get("id").in(isoles));
        return ou.isEmpty() ? cb.disjunction() : cb.or(ou.toArray(Predicate[]::new));
    }

    private static Predicate confidentialite(DroitsResolus d, Root<UploadDocument> root,
                                             jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) {
        if (d.voirPrive() && d.voirConfidentiel()) return cb.conjunction();
        List<Predicate> ou = new ArrayList<>();
        ou.add(cb.equal(root.get("confidentialite"), Confidentialite.PUBLIC));
        Sujet s = d.sujet();
        if (d.voirPrive()) {
            ou.add(cb.equal(root.get("confidentialite"), Confidentialite.PRIVE));
        } else if (s.employeId() != null) {
            ou.add(cb.and(cb.equal(root.get("confidentialite"), Confidentialite.PRIVE),
                    cb.equal(root.join("createdBy", jakarta.persistence.criteria.JoinType.LEFT).get("id"),
                            s.employeId())));
        }
        if (d.voirConfidentiel()) {
            ou.add(cb.equal(root.get("confidentialite"), Confidentialite.CONFIDENTIEL));
        } else if (s.type() == TypeSujet.UTILISATEUR) {
            Subquery<Integer> des = query.subquery(Integer.class);
            Root<DocumentConfidentielDesigne> x = des.from(DocumentConfidentielDesigne.class);
            des.select(cb.literal(1)).where(cb.equal(x.get("documentId"), root.get("id")),
                    cb.equal(x.get("utilisateurId"), s.id()));
            ou.add(cb.and(cb.equal(root.get("confidentialite"), Confidentialite.CONFIDENTIEL), cb.exists(des)));
        }
        return cb.or(ou.toArray(Predicate[]::new));
    }

    /**
     * Prédicat SQL « à la source » pour les requêtes natives (recherche plein
     * texte, extraits, totaux) : vrai pour un document VIVANT que le sujet peut
     * exercer avec la permission. Les valeurs passent par des paramètres nommés
     * préfixés {@code droits_} ; les ensembles de nœuds par un seul paramètre
     * tableau, quelle que soit leur taille.
     *
     * <p><b>Forme du SQL</b> (performance, sémantique inchangée) : chaque
     * ensemble est une sous-requête NON corrélée ({@code IN (SELECT unnest(...))},
     * {@code IN (SELECT document_id ...)}), que PostgreSQL évalue une seule fois
     * puis consulte par hachage (semi-jointure). La forme précédente
     * ({@code = ANY (CAST(:p AS uuid[]))} et {@code EXISTS} corrélés) relisait le
     * tableau texte à CHAQUE ligne (la conversion texte → uuid[] n'est pas
     * pré-calculée) et sondait les rattachements document par document : 1 s
     * pour compter les documents d'un utilisateur sur 25 nœuds, 11 s sur 250
     * nœuds, contre 20 ms (50 000 documents, mesure consignée dans
     * {@code docs/conformite/suivi/dev1.md}, tour 2). Les colonnes comparées
     * sont toutes non nulles : IN et EXISTS y ont exactement la même valeur.
     *
     * @param colonneDocumentId expression SQL de l'identifiant du document dans la requête appelante
     */
    public FragmentSql predicatSql(String colonneDocumentId, Authentication authentification,
                                   CodePermission permission) {
        DroitsResolus d = droits(authentification);
        if (d.sujet() == null) return FragmentSql.FAUX;
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("EXISTS (SELECT 1 FROM document droits_d WHERE droits_d.id = ")
                .append(colonneDocumentId).append(" AND droits_d.supprime = false");

        if (!d.partout(permission)) {
            Set<UUID> noeuds = d.noeuds(permission);
            Set<UUID> isoles = d.documents(permission);
            if (noeuds.isEmpty() && isoles.isEmpty()) return FragmentSql.FAUX;
            params.put("droits_noeuds", tableau(noeuds));
            params.put("droits_documents", tableau(isoles));
            sql.append(" AND (droits_d.noeud_principal_id IN (SELECT unnest(CAST(:droits_noeuds AS uuid[])))")
               .append(" OR droits_d.id IN (SELECT droits_r.document_id FROM document_rattachement droits_r")
               .append(" WHERE droits_r.noeud_id IN (SELECT unnest(CAST(:droits_noeuds AS uuid[]))))")
               .append(" OR droits_d.id IN (SELECT unnest(CAST(:droits_documents AS uuid[]))))");
        }
        if (!(d.voirPrive() && d.voirConfidentiel())) {
            Sujet s = d.sujet();
            sql.append(" AND (droits_d.confidentialite = 'PUBLIC'");
            if (d.voirPrive()) {
                sql.append(" OR droits_d.confidentialite = 'PRIVE'");
            } else if (s.employeId() != null) {
                params.put("droits_employe", s.employeId().toString());
                sql.append(" OR (droits_d.confidentialite = 'PRIVE'")
                   .append(" AND droits_d.created_by_employe_id = CAST(:droits_employe AS uuid))");
            }
            if (d.voirConfidentiel()) {
                sql.append(" OR droits_d.confidentialite = 'CONFIDENTIEL'");
            } else if (s.type() == TypeSujet.UTILISATEUR) {
                params.put("droits_utilisateur", s.id().toString());
                sql.append(" OR (droits_d.confidentialite = 'CONFIDENTIEL' AND droits_d.id IN (SELECT")
                   .append(" droits_c.document_id FROM document_confidentiel_designe droits_c")
                   .append(" WHERE droits_c.utilisateur_id = CAST(:droits_utilisateur AS uuid)))");
            }
            sql.append(')');
        }
        sql.append(')');
        return new FragmentSql(sql.toString(), params);
    }

    /** Littéral de tableau PostgreSQL « {a,b} » : un seul paramètre, sans concaténation dans le SQL. */
    private static String tableau(Collection<UUID> ids) {
        StringBuilder sb = new StringBuilder("{");
        boolean premier = true;
        for (UUID id : ids) {
            if (!premier) sb.append(',');
            sb.append(id);
            premier = false;
        }
        return sb.append('}').toString();
    }

    /** Identifiants de documents parmi {@code candidats} sur lesquels l'appelant détient la permission. */
    public Set<UUID> filtrer(Authentication authentification, CodePermission permission, Collection<UUID> candidats) {
        Set<UUID> ok = new HashSet<>();
        for (UUID id : candidats) if (peut(authentification, permission, id)) ok.add(id);
        return ok;
    }
}
