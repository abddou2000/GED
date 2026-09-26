package com.ipt.ged.identite.session;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<SessionUtilisateur, UUID> {

    /**
     * Ligne du jeton présenté, verrouillée : deux renouvellements simultanés avec
     * le même jeton se sérialisent, et le second voit le premier l'avoir consommé.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SessionUtilisateur s where s.empreinte = :empreinte")
    Optional<SessionUtilisateur> findByEmpreintePourMiseAJour(@Param("empreinte") String empreinte);

    /** Vrai tant que la famille a un jeton courant valide : sert au contrôle de chaque requête. */
    @Query("""
           select count(s) > 0 from SessionUtilisateur s
            where s.familleId = :famille and s.revoqueeLe is null
              and s.consommeLe is null and s.expireLe > :maintenant""")
    boolean familleActive(@Param("famille") UUID famille, @Param("maintenant") Instant maintenant);

    @Modifying
    @Query("""
           update SessionUtilisateur s set s.revoqueeLe = :instant, s.motifRevocation = :motif
            where s.familleId = :famille and s.revoqueeLe is null""")
    int revoquerFamille(@Param("famille") UUID famille, @Param("motif") MotifRevocation motif,
                        @Param("instant") Instant instant);

    @Modifying
    @Query("""
           update SessionUtilisateur s set s.revoqueeLe = :instant, s.motifRevocation = :motif
            where s.utilisateurId = :utilisateur and s.revoqueeLe is null""")
    int revoquerUtilisateur(@Param("utilisateur") UUID utilisateur, @Param("motif") MotifRevocation motif,
                            @Param("instant") Instant instant);

    /** Jetons courants encore valables d'un utilisateur : une ligne par session ouverte. */
    @Query("""
           select s from SessionUtilisateur s
            where s.utilisateurId = :utilisateur and s.revoqueeLe is null
              and s.consommeLe is null and s.expireLe > :maintenant
            order by s.creeLe desc""")
    List<SessionUtilisateur> sessionsOuvertes(@Param("utilisateur") UUID utilisateur,
                                              @Param("maintenant") Instant maintenant);

    /** Ménage : les lignes dont la borne absolue est passée depuis longtemps n'apprennent plus rien. */
    @Modifying
    @Query("delete from SessionUtilisateur s where s.utilisateurId = :utilisateur and s.expireLe < :avant")
    int purgerAnciennes(@Param("utilisateur") UUID utilisateur, @Param("avant") Instant avant);
}
