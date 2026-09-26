package com.ipt.ged.identite.session;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Un jeton de renouvellement émis (table {@code session}, dossier technique §3.4.1).
 *
 * <p>Seule l'empreinte SHA-256 du jeton est stockée : une fuite de la table ne
 * permet pas de rejouer un jeton. Les jetons successifs d'une même connexion
 * forment une <b>famille</b> ; à chaque renouvellement, la ligne courante est
 * marquée consommée et une nouvelle ligne de la même famille est créée.
 */
@Entity
@Table(name = "session")
@Getter
@Setter
@NoArgsConstructor
public class SessionUtilisateur {

    @Id
    @IdentifiantUuid
    private UUID id;

    @Column(name = "utilisateur_id", nullable = false)
    private UUID utilisateurId;

    @Column(name = "famille_id", nullable = false)
    private UUID familleId;

    /** SHA-256 du jeton, en hexadécimal minuscule. */
    @Column(nullable = false, unique = true, length = 64)
    private String empreinte;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Column(name = "derniere_activite_le", nullable = false)
    private Instant derniereActiviteLe;

    /** Borne absolue de la famille : ne se prolonge jamais par renouvellement. */
    @Column(name = "expire_le", nullable = false)
    private Instant expireLe;

    @Column(name = "consomme_le")
    private Instant consommeLe;

    @Column(name = "revoquee_le")
    private Instant revoqueeLe;

    @Enumerated(EnumType.STRING)
    @Column(name = "motif_revocation", length = 30)
    private MotifRevocation motifRevocation;

    @Column(name = "adresse_ip", length = 45)
    private String adresseIp;

    @Column(name = "agent_utilisateur")
    private String agentUtilisateur;

    public boolean estRevoquee() {
        return revoqueeLe != null;
    }

    public boolean estConsommee() {
        return consommeLe != null;
    }

    public void revoquer(MotifRevocation motif, Instant instant) {
        if (revoqueeLe == null) {
            revoqueeLe = instant;
            motifRevocation = motif;
        }
    }
}
