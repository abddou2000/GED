package com.ipt.ged.autorisation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    List<Permission> findByCodeIn(Collection<String> codes);

    List<Permission> findAllByOrderByCategorieAscCodeAsc();
}
