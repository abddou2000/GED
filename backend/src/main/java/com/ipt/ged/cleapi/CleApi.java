package com.ipt.ged.cleapi;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Clé d'API d'une application (DAT §5.4).
 *
 * <p>Format transmis : {@code ged_<env>_<identifiant>_<secret>}. Seuls
 * l'identifiant (public) et l'empreinte SHA-256 du secret sont conservés : le
 * secret, affiché une seule fois à la génération, ne peut plus être relu, même
 * par l'Administrateur.
 */
@Entity
@Table(name = "cle_api")
@Getter
@Setter
@NoArgsConstructor
public class CleApi {

    @Id
    @IdentifiantUuid
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(nullable = false, unique = true, length = 32)
    private String identifiant;

    @Column(nullable = false, length = 16)
    private String environnement;

    /** SHA-256 du secret, hexadécimal minuscule. */
    @Column(nullable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String empreinte;

    /** Attribut « délégation » (§5.5) : autorise l'en-tête X-On-Behalf-Of. */
    @Column(nullable = false)
    private boolean delegation;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Column(name = "expire_le", nullable = false)
    private Instant expireLe;

    @Column(name = "revoquee_le")
    private Instant revoqueeLe;

    @Column(name = "motif_revocation", length = 500)
    private String motifRevocation;

    /** Clé qui remplace celle-ci après régénération (chevauchement en cours). */
    @Column(name = "remplacee_par_cle_api_id")
    private UUID remplaceeParCleApiId;

    @Column(name = "derniere_utilisation")
    private Instant derniereUtilisation;

    @Column(name = "quota_jour_date")
    private LocalDate quotaJourDate;

    @Column(name = "quota_jour_appels", nullable = false)
    private long quotaJourAppels;

    public boolean revoquee() {
        return revoqueeLe != null;
    }

    public boolean expiree(Instant maintenant) {
        return !maintenant.isBefore(expireLe);
    }

    public boolean utilisable(Instant maintenant) {
        return !revoquee() && !expiree(maintenant);
    }
}
