package com.ipt.ged.typedocument.retypage;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Travail de re-typologisation d'un lot (§12.7) : type source, type cible,
 * table de correspondance ancien champ → nouveau champ, sélection (tous les
 * documents du type source si vide), état, compteurs et rapport par document.
 */
@Entity
@Table(name = "job_retypage")
@Getter
@Setter
@NoArgsConstructor
public class JobRetypage {

    public enum Statut { EN_ATTENTE, EN_COURS, TERMINE, ECHEC }

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "source_type_document_id", nullable = false)
    private UUID sourceTypeDocumentId;

    @Column(name = "cible_type_document_id", nullable = false)
    private UUID cibleTypeDocumentId;

    /** Ancien code d'index → nouveau code d'index. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correspondance", nullable = false)
    private Map<String, String> correspondance = new HashMap<>();

    /** Identifiants des documents choisis ; {@code null} = tous les documents vivants du type source. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection")
    private List<UUID> selection;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 12)
    private Statut statut = Statut.EN_ATTENTE;

    @Column(nullable = false)
    private int total;

    @Column(nullable = false)
    private int traites;

    @Column(nullable = false)
    private int reussis;

    @Column(nullable = false)
    private int echecs;

    /** Une ligne par document : identifiant, nom, résultat, motif, champs perdus. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rapport", nullable = false)
    private List<Map<String, Object>> rapport = new ArrayList<>();

    @Column(name = "demandeur_utilisateur_id")
    private UUID demandeurUtilisateurId;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe = Instant.now();

    @Column(name = "debut_le")
    private Instant debutLe;

    @Column(name = "fin_le")
    private Instant finLe;
}
