package com.ipt.ged.cleapi;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Portée d'une clé : un nœud (espace ou dossier, sous-arborescence comprise)
 * et les opérations autorisées sur ce nœud (DAT §5.4, table
 * {@code cle_api_portee}). Équivaut à des attributions assorties d'une liste
 * d'opérations (§12.2) ; son évaluation relève du point d'application unique
 * des droits (lot autorisation), branché par {@link ControlePorteeApplication}.
 */
@Entity
@Table(name = "cle_api_portee")
@Getter
@Setter
@NoArgsConstructor
public class PorteeCleApi {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "cle_api_id", nullable = false)
    private UUID cleApiId;

    @Column(name = "noeud_id", nullable = false)
    private UUID noeudId;

    /** Codes {@link OperationApi} séparés par des virgules. */
    @Column(nullable = false, length = 500)
    private String operations;

    public PorteeCleApi(UUID cleApiId, UUID noeudId, Set<OperationApi> operations) {
        this.cleApiId = cleApiId;
        this.noeudId = noeudId;
        definirOperations(operations);
    }

    public Set<OperationApi> operationsAutorisees() {
        if (operations == null || operations.isBlank()) return EnumSet.noneOf(OperationApi.class);
        return Arrays.stream(operations.split(",")).map(String::trim).map(OperationApi::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(OperationApi.class)));
    }

    public void definirOperations(Set<OperationApi> ops) {
        this.operations = ops.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
