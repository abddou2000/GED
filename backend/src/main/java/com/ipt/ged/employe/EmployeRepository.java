package com.ipt.ged.employe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeRepository extends JpaRepository<Employe, Long> {

    /** Les employés ayant un compte utilisateur (candidats approbateurs). */
    List<Employe> findByHasUserTrue();
}
