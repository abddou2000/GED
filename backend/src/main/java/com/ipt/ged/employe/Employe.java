package com.ipt.ged.employe;

import com.ipt.ged.common.IdentifiantUuid;
import java.util.UUID;
import com.ipt.ged.common.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Employé — la personne métier (propriétaire de dossier, approbateur de workflow…).
 * Distincte du compte utilisateur (User) : un employé n'a pas forcément de compte de connexion.
 */
@Entity
@Table(name = "employe")
@Getter
@Setter
@NoArgsConstructor
public class Employe extends Auditable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    /** L'employé possède-t-il un compte utilisateur (peut se connecter et donc signer) ? */
    @Column(name = "has_user", nullable = false)
    private boolean hasUser = false;

    public Employe(String firstName, String lastName, boolean hasUser) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.hasUser = hasUser;
    }

    @Transient
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
