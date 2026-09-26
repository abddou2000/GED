package com.ipt.ged.employe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmployeRepository extends JpaRepository<Employe, UUID> {

    /** Les employés ayant un compte utilisateur (candidats approbateurs). */
    List<Employe> findByHasUserTrue();
}
