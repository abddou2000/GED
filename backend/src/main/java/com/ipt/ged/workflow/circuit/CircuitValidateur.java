package com.ipt.ged.workflow.circuit;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.employe.Employe;
import com.ipt.ged.identite.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Validateur figé d'un circuit : nommé (employé) ou par rôle sur un périmètre,
 * résolu au moment de la décision. Une réaffectation par l'Administrateur
 * remplace le validateur et garde la trace de l'ancien (D1, QR1).
 */
@Entity
@Table(name = "circuit_validateur")
@Getter
@Setter
@NoArgsConstructor
public class CircuitValidateur {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circuit_id", nullable = false)
    private Circuit circuit;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employe_id")
    private Employe employe;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;

    /** Périmètre du rôle ; {@code null} = l'emplacement principal du document. */
    @Column(name = "perimetre_noeud_id")
    private UUID perimetreNoeudId;

    @Column(nullable = false)
    private String libelle;

    /** Ordre d'affichage seulement (aucun ordre n'est imposé, D7). */
    @Column(nullable = false)
    private int position;

    @Column(name = "reaffecte_de_employe_id")
    private UUID reaffecteDeEmployeId;

    @Column(name = "reaffecte_par")
    private UUID reaffectePar;

    @Column(name = "reaffecte_le")
    private Instant reaffecteLe;

    @Column(name = "motif_reaffectation", length = 500)
    private String motifReaffectation;

    public boolean nomme() {
        return employe != null;
    }
}
