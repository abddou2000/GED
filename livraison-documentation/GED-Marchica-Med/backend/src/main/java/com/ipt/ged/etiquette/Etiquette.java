package com.ipt.ged.etiquette;

import com.ipt.ged.common.Auditable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Étiquette : un tag coloré (libellé + couleur) applicable aux documents.
 * Reproduit {@code Etiquette} de CCISTTA.
 */
@Entity
@Table(name = "etiquettes")
@Getter
@Setter
@NoArgsConstructor
public class Etiquette extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Libellé de l'étiquette. */
    @Column(nullable = false)
    private String tag;

    /** Couleur (hex). */
    @Column(nullable = false)
    private String couleur;

    /** Corbeille : true = supprimé de façon réversible. */
    @Column(nullable = false)
    private boolean deleted = false;

    public Etiquette(String code, String tag, String couleur) {
        this.code = code;
        this.tag = tag;
        this.couleur = couleur;
    }
}
