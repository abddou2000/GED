package com.ipt.ged.autorisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Permission livrée (table {@code permission}, données initiales). Lecture seule
 * pour l'application : la liste est fermée (voir {@link CodePermission}).
 */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor
public class Permission {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false)
    private String libelle;

    @Column(nullable = false, length = 20)
    private String categorie;

    /** Code typé ; {@code null} pour une ligne que le code ne connaît pas. */
    public CodePermission codePermission() {
        return CodePermission.depuis(code);
    }
}
