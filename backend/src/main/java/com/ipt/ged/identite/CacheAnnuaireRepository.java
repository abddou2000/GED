package com.ipt.ged.identite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CacheAnnuaireRepository extends JpaRepository<EntreeCacheAnnuaire, UUID> {

    Optional<EntreeCacheAnnuaire> findByUtilisateurId(UUID utilisateurId);

    List<EntreeCacheAnnuaire> findByUtilisateurIdIn(Collection<UUID> utilisateurIds);
}
