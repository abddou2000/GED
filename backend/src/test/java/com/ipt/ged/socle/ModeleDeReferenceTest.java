package com.ipt.ged.socle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modèle de données de référence du DAT §12.1 (T-025) : les tables principales
 * des sept groupes existent sous leur nom exact dans une base créée par
 * Liquibase, avec les points structurants du tableau (chemin matérialisé,
 * métadonnées JSONB, identité sans mot de passe, audit en INSERT seul).
 */
@SpringBootTest
@ActiveProfiles("test")
class ModeleDeReferenceTest {

    /** Tableau 12.1 du DAT, groupe par groupe, dans l'ordre du dossier. */
    static final Map<String, List<String>> GROUPES = new LinkedHashMap<>();

    static {
        GROUPES.put("Identités et accès",
                List.of("utilisateur", "cache_annuaire", "session", "application", "cle_api", "cle_api_portee"));
        GROUPES.put("Habilitations",
                List.of("role", "permission", "role_permission", "groupe_ged", "groupe_membre", "habilitation"));
        GROUPES.put("Organisation documentaire",
                List.of("noeud", "document", "document_rattachement", "document_confidentiel_designe"));
        GROUPES.put("Typologie", List.of("type_document", "index_def", "plan_indexation", "plan_index"));
        GROUPES.put("Versions et contenu", List.of("version_document", "cle_fichier", "document_texte", "ocr_job"));
        GROUPES.put("Circuits de validation",
                List.of("regle_workflow", "regle_validateur", "circuit", "circuit_validateur", "decision"));
        GROUPES.put("Traçabilité et exploitation", List.of("journal_audit", "journal_audit_scellement",
                "notification", "idempotence_cle", "job_archivage"));
    }

    @Autowired private JdbcTemplate jdbc;

    private boolean table(String nom) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.tables"
                + " WHERE table_schema = current_schema() AND table_name = ?)", Boolean.class, nom));
    }

    private String type(String table, String colonne) {
        return jdbc.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?", String.class, table, colonne).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("Les tables principales des sept groupes du §12.1 existent sous leur nom exact")
    void tablesDuModele() {
        List<String> manquantes = new ArrayList<>();
        GROUPES.forEach((groupe, tables) -> tables.forEach(t -> {
            if (!table(t)) manquantes.add(groupe + " : " + t);
        }));
        assertThat(manquantes).as("tables du §12.1 absentes").isEmpty();
    }

    @Test
    @DisplayName("Points structurants du §12.1 : chemin matérialisé, JSONB, identité sans mot de passe, UUID")
    void pointsStructurants() {
        assertThat(type("noeud", "parent_id")).isEqualTo("uuid");
        assertThat(type("noeud", "chemin")).isNotNull();
        assertThat(type("document", "metadonnees")).isEqualTo("jsonb");
        assertThat(type("utilisateur", "object_guid")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                        + " AND table_name = 'utilisateur' AND (column_name ILIKE '%pass%' OR column_name ILIKE '%mdp%')",
                Long.class)).as("l'identité GED ne porte jamais de mot de passe").isZero();
        // Clés primaires UUID, sauf l'identifiant séquentiel du journal d'audit (§7.4.1).
        GROUPES.values().stream().flatMap(List::stream)
                .filter(t -> !t.equals("journal_audit"))
                .forEach(t -> assertThat(type(t, "id")).as(t + ".id").isEqualTo("uuid"));
    }
}
