package com.ipt.ged.autorisation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface HabilitationRepository extends JpaRepository<Habilitation, UUID> {

    /**
     * Habilitations applicables à une identité GED : les siennes et celles des
     * groupes GED vivants dont elle est membre (§12.2.2, cumul en union). Une
     * appartenance en attente (personne sans identité) n'apporte rien.
     */
    @Query("""
           select h from Habilitation h
            where h.utilisateurId = :utilisateurId
               or h.groupeGedId in (select g.id from AccessGroup g join g.membres m
                                     where m.id = :utilisateurId and g.supprime = false)""")
    List<Habilitation> applicablesA(@Param("utilisateurId") UUID utilisateurId);

    List<Habilitation> findByApplicationId(UUID applicationId);

    List<Habilitation> findByUtilisateurId(UUID utilisateurId);

    List<Habilitation> findByGroupeGedId(UUID groupeGedId);

    List<Habilitation> findByNoeudId(UUID noeudId);

    List<Habilitation> findByDocumentId(UUID documentId);

    List<Habilitation> findByGroupeGedIdAndNoeudIdIsNotNull(UUID groupeGedId);

    List<Habilitation> findByGroupeGedIdInAndNoeudIdIsNotNull(Collection<UUID> groupes);

    List<Habilitation> findByNoeudIdInAndSujetType(Collection<UUID> noeuds, TypeSujet type);

    boolean existsByRoleId(UUID roleId);

    @Query("""
           select count(h) > 0 from Habilitation h left join h.role r
            where h.sujetType = :type
              and (h.utilisateurId = :sujet or h.groupeGedId = :sujet or h.applicationId = :sujet)
              and ((:role is null and r is null) or r.id = :role)
              and ((:noeud is null and h.noeudId is null) or h.noeudId = :noeud)
              and ((:document is null and h.documentId is null) or h.documentId = :document)""")
    boolean existe(@Param("type") TypeSujet type, @Param("sujet") UUID sujet, @Param("role") UUID role,
                   @Param("noeud") UUID noeud, @Param("document") UUID document);
}
