package com.ipt.ged.cleapi;

import com.ipt.ged.common.IdentifiantUuid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Application cliente de l'API (bureau d'ordre digital, gestion des marchés…).
 *
 * <p>Sujet d'authentification au même titre qu'un utilisateur (DAT §5.2,
 * §12.2) : elle agit avec ses clés ({@link CleApi}), dans sa portée, sous ses
 * quotas, et chacun de ses appels est journalisé à son nom.
 */
@Entity
@Table(name = "application")
@Getter
@Setter
@NoArgsConstructor
public class Application {

    @Id
    @IdentifiantUuid
    private UUID id;

    /** Identifiant stable et lisible : minuscules, chiffres, tirets. */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false)
    private String nom;

    @Column(length = 1000)
    private String description;

    /** Adresses ou plages CIDR autorisées, séparées par des virgules ; vide = toute adresse. */
    @Column(name = "adresses_autorisees", length = 2000)
    private String adressesAutorisees;

    @Column(name = "quota_minute", nullable = false)
    private int quotaMinute = 600;

    @Column(name = "quota_jour", nullable = false)
    private int quotaJour = 100_000;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Column(name = "modifie_le")
    private Instant modifieLe;

    public Application(String code, String nom) {
        this.code = code;
        this.nom = nom;
        this.creeLe = Instant.now();
    }

    /** Liste des adresses autorisées ; vide si aucune restriction. */
    public List<String> adresses() {
        if (adressesAutorisees == null || adressesAutorisees.isBlank()) return List.of();
        return Arrays.stream(adressesAutorisees.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void definirAdresses(List<String> adresses) {
        this.adressesAutorisees = adresses == null || adresses.isEmpty() ? null : String.join(",", adresses);
    }
}
