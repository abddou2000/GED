package com.ipt.ged.index;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.common.Supprimable;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Index : un champ de métadonnée configurable, attaché aux documents pour les
 * décrire et les retrouver. Reproduit le modèle {@code Index} de CCISTTA.
 * (Classe nommée {@code IndexField} pour éviter la confusion avec le mot-clé SQL.)
 */
@Entity
@Table(name = "index_def")
@Getter
@Setter
@NoArgsConstructor
public class IndexField extends Supprimable {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "nom_index", nullable = false)
    private String nomIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_champs", nullable = false)
    private IndexFieldType fieldType = IndexFieldType.TEXTE;

    /** Valeurs possibles (séparées par des virgules) — uniquement si type = LISTE. */
    @Column(columnDefinition = "text")
    private String valeurs;

    @Column(name = "valeur_par_defaut")
    private String valeurParDefaut;

    @Column(nullable = false)
    private boolean obligatoire = false;

    @Column(name = "indexe_pour_recherche", nullable = false)
    private boolean indexePourRecherche = false;

    @Column(name = "index_de_groupage", nullable = false)
    private boolean indexDeGroupage = false;


    public IndexField(String code, String nomIndex) {
        this.code = code;
        this.nomIndex = nomIndex;
    }
}
