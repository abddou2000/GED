package com.ipt.ged.stats;

import com.ipt.ged.autorisation.AccessPredicate;
import com.ipt.ged.autorisation.CodePermission;
import com.ipt.ged.document.UploadDocument;
import com.ipt.ged.typedocument.TypeDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Agrégats du tableau de bord calculés sur le seul périmètre autorisé de
 * l'appelant (P5, §12.2.3 : « les compteurs et totaux ne portent que sur le
 * périmètre autorisé »). Le filtre de droits est celui du point d'application
 * unique, appliqué dans la requête d'agrégat elle-même.
 */
@Repository
public class StatistiquesPerimetre {

    /** Une ligne de répartition. */
    public record PartType(String libelle, long total) {}

    @PersistenceContext
    private EntityManager em;

    private final AccessPredicate droits;

    public StatistiquesPerimetre(AccessPredicate droits) {
        this.droits = droits;
    }

    private Specification<UploadDocument> vivantsVisibles() {
        Specification<UploadDocument> vivants = (r, q, cb) -> cb.isFalse(r.get("supprime"));
        return vivants.and(droits.documents(SecurityContextHolder.getContext().getAuthentication(),
                CodePermission.CONSULTER));
    }

    public long documents() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<UploadDocument> d = q.from(UploadDocument.class);
        q.select(cb.count(d)).where(vivantsVisibles().toPredicate(d, q, cb));
        return em.createQuery(q).getSingleResult();
    }

    /** Répartition par type, la plus fournie d'abord ; les documents sans type restent comptés. */
    public List<PartType> parType() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<UploadDocument> d = q.from(UploadDocument.class);
        Join<UploadDocument, TypeDocument> t = d.join("typeDocument", JoinType.LEFT);
        Expression<Long> total = cb.count(d);
        q.multiselect(t.get("typeDeDocument"), total)
                .where(vivantsVisibles().toPredicate(d, q, cb))
                .groupBy(t.get("typeDeDocument"))
                .orderBy(cb.desc(total));
        return em.createQuery(q).getResultList().stream()
                .map(x -> new PartType(x.get(0, String.class), x.get(1, Long.class)))
                .toList();
    }

    /** Dates de création des documents visibles déposés depuis une date. */
    public List<Instant> datesDeCreationDepuis(Instant depuis) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Instant> q = cb.createQuery(Instant.class);
        Root<UploadDocument> d = q.from(UploadDocument.class);
        q.select(d.get("createdAt")).where(cb.and(vivantsVisibles().toPredicate(d, q, cb),
                cb.greaterThanOrEqualTo(d.get("createdAt"), depuis)));
        return em.createQuery(q).getResultList();
    }
}
