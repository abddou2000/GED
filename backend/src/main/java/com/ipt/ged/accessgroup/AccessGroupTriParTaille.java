package com.ipt.ged.accessgroup;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * Tri d'un groupe d'accès par le <em>nombre</em> d'espaces couverts ou de membres.
 *
 * <p>Ces deux colonnes sont déclarées triables dans l'application d'origine, mais
 * Spring Data ne sait pas ordonner sur une association N–N : ni un nom de méthode
 * dérivé ni un {@code Sort} classique ne peuvent exprimer {@code size(...)}.
 * D'où cette requête construite à la main.
 *
 * <p>Le nom de la collection ne vient jamais de l'utilisateur : il est confronté
 * à {@link #COLLECTIONS} avant d'entrer dans le JPQL.
 */
@Repository
public class AccessGroupTriParTaille {

    /** Seules associations sur lesquelles le tri par cardinalité est permis. */
    public static final Set<String> COLLECTIONS = Set.of("workspaces", "users");

    @PersistenceContext
    private EntityManager em;

    public Page<AccessGroup> rechercher(String search, boolean supprimes, String collection,
                                        Sort.Direction sens, Pageable pageable) {
        if (!COLLECTIONS.contains(collection)) {
            throw new IllegalArgumentException("Tri non autorisé : " + collection);
        }
        String motif = "%" + (search == null ? "" : search).toLowerCase() + "%";
        String ou = " from AccessGroup g where g.supprime = :supprimes"
                  + " and lower(g.name) like :motif";

        // Les espaces couverts ne sont plus une collection du groupe mais ses
        // habilitations sur des nœuds (lot E3) : on compte ces lignes.
        String taille = "workspaces".equals(collection)
                ? "(select count(distinct h.noeudId) from Habilitation h"
                  + " where h.groupeGedId = g.id and h.noeudId is not null and h.role is not null)"
                : "size(g.users)";
        TypedQuery<AccessGroup> q = em.createQuery(
                "select g" + ou + " order by " + taille + " " + sens.name()
                        // Départage les ex æquo : sans second critère, deux groupes
                        // ayant le même nombre d'espaces pourraient changer de place
                        // d'une page à l'autre et l'un d'eux disparaître.
                        + ", g.id desc", AccessGroup.class);
        q.setParameter("supprimes", supprimes);
        q.setParameter("motif", motif);
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<AccessGroup> contenu = q.getResultList();

        TypedQuery<Long> qc = em.createQuery("select count(g)" + ou, Long.class);
        qc.setParameter("supprimes", supprimes);
        qc.setParameter("motif", motif);

        return new PageImpl<>(contenu, pageable, qc.getSingleResult());
    }
}
