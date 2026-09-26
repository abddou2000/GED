package com.ipt.ged.etiquette;

import com.ipt.ged.common.IdentifiantUuid;
import java.util.UUID;
import com.ipt.ged.common.Supprimable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Étiquette : un tag coloré (libellé + couleur) applicable aux documents.
 * Reproduit {@code Etiquette} de CCISTTA.
 */
@Entity
@Table(name = "etiquette")
@Getter
@Setter
@NoArgsConstructor
public class Etiquette extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Libellé de l'étiquette. */
    @Column(nullable = false)
    private String tag;

    /** Couleur (hex). */
    @Column(nullable = false)
    private String couleur;


    public Etiquette(String code, String tag, String couleur) {
        this.code = code;
        this.tag = tag;
        this.couleur = couleur;
    }
}
