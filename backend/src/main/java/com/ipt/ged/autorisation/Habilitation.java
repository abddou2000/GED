package com.ipt.ged.autorisation;

import com.ipt.ged.common.IdentifiantUuid;
import com.ipt.ged.identite.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Attribution (dossier technique §12.2.1) : un SUJET reçoit un RÔLE sur une
 * CIBLE, avec éventuellement une RUPTURE D'HÉRITAGE.
 *
 * <ul>
 *   <li>sujet : exactement un de {@code utilisateurId}, {@code groupeGedId},
 *       {@code applicationId}, selon {@link #getSujetType()} ;</li>
 *   <li>cible : un nœud, un document (« document isolé »), ou aucune = portée
 *       globale (au-dessus de tous les espaces ; seule portée où s'exercent
 *       les permissions d'administration) ;</li>
 *   <li>rupture : sur un nœud seulement ; une ligne de rupture SANS rôle retire
 *       au sujet, sur ce nœud et en dessous, tout ce qu'il aurait hérité.</li>
 * </ul>
 * Les contraintes {@code ck_habilitation_*} de la base garantissent ces règles
 * quel que soit le chemin d'écriture.
 */
@Entity
@Table(name = "habilitation")
@Getter
@Setter
@NoArgsConstructor
public class Habilitation {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "sujet_type", nullable = false, length = 12)
    private TypeSujet sujetType;

    @Column(name = "utilisateur_id")
    private UUID utilisateurId;

    @Column(name = "groupe_ged_id")
    private UUID groupeGedId;

    @Column(name = "application_id")
    private UUID applicationId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(name = "noeud_id")
    private UUID noeudId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "rupture_heritage", nullable = false)
    private boolean ruptureHeritage;

    /** Identité GED de l'auteur de l'attribution ({@code null} : amorçage, reprise). */
    @Column(name = "cree_par")
    private UUID creePar;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe = Instant.now();

    /** Identifiant du sujet, quelle que soit sa nature. */
    public UUID sujetId() {
        return switch (sujetType) {
            case UTILISATEUR -> utilisateurId;
            case GROUPE -> groupeGedId;
            case APPLICATION -> applicationId;
        };
    }

    public boolean globale() {
        return noeudId == null && documentId == null;
    }

    public static Habilitation pour(TypeSujet type, UUID sujetId) {
        Habilitation h = new Habilitation();
        h.sujetType = type;
        switch (type) {
            case UTILISATEUR -> h.utilisateurId = sujetId;
            case GROUPE -> h.groupeGedId = sujetId;
            case APPLICATION -> h.applicationId = sujetId;
        }
        return h;
    }
}
