package com.ipt.ged.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Drapeaux des modules métier (T-088, DAT §9.3), sans contexte Spring. */
class ModulesTest {

    @Test
    @DisplayName("Tous les modules sont actifs par défaut ; aucune propriété imposée")
    void actifsParDefaut() {
        MockEnvironment env = new MockEnvironment();
        new ModulesEnvironnement().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().contains(ModulesEnvironnement.SOURCE)).isFalse();
        ModulesActifs modules = new ModulesActifs(env);
        assertThat(modules.etats()).allSatisfy((m, actif) -> assertThat(actif).isTrue());
        assertThat(modules.inactifPour("/api/v1/workflow/a-traiter")).isEmpty();
    }

    @Test
    @DisplayName("Un module inactif impose l'arrêt de ses traitements de fond, même contre un réglage contraire")
    void arretDesTraitements() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ged.modules.cycledevie.actif", "false")
                .withProperty("ged.modules.notifications.actif", "false")
                .withProperty("ged.modules.ocr.actif", "false")
                .withProperty("ged.conservation.alertes.actif", "true")
                .withProperty("ged.ocr.chaine.actif", "true");
        new ModulesEnvironnement().postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getProperty("ged.conservation.alertes.actif")).isEqualTo("false");
        assertThat(env.getProperty("ged.ocr.chaine.actif")).isEqualTo("false");
        assertThat(env.getProperty("ged.notification.active")).isEqualTo("false");
        assertThat(env.getProperty("ged.notification.expedition-auto")).isEqualTo("false");
    }

    @Test
    @DisplayName("Routes fermées : celles du module inactif seulement, socle et autres modules ouverts")
    void routes() {
        ModulesActifs modules = new ModulesActifs(new MockEnvironment()
                .withProperty("ged.modules.cycledevie.actif", "false")
                .withProperty("ged.modules.workflow.actif", "false"));
        assertThat(modules.inactifPour("/api/v1/documents/0192f0a6-1111-7d1e-9f20-3a4b5c6d7e8f/archivage"))
                .contains(ModuleMetier.CYCLEDEVIE);
        assertThat(modules.inactifPour("/api/v1/archivage/jobs")).contains(ModuleMetier.CYCLEDEVIE);
        assertThat(modules.inactifPour("/api/v1/type-documents/retypages")).contains(ModuleMetier.CYCLEDEVIE);
        assertThat(modules.inactifPour("/api/v1/workflow/regles")).contains(ModuleMetier.WORKFLOW);
        assertThat(modules.inactifPour("/api/v1/workflowgeds/12")).contains(ModuleMetier.WORKFLOW);
        // Socle : dépôt, fiche, types de document ; autre module : export.
        assertThat(modules.inactifPour("/api/v1/documents")).isEmpty();
        assertThat(modules.inactifPour("/api/v1/documents/0192f0a6-1111-7d1e-9f20-3a4b5c6d7e8f")).isEmpty();
        assertThat(modules.inactifPour("/api/v1/type-documents")).isEmpty();
        assertThat(modules.inactifPour("/api/v1/exports")).isEmpty();
    }

    @Test
    @DisplayName("Un code de module inconnu fait échouer le démarrage")
    void codeInconnu() {
        MockEnvironment env = new MockEnvironment().withProperty("ged.modules.worklfow.actif", "false");
        assertThatThrownBy(() -> new ModulesActifs(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worklfow");
    }

    @Test
    @DisplayName("Codes uniques ; chaque module a au moins une route et une référence au dossier")
    void catalogue() {
        assertThat(Arrays.stream(ModuleMetier.values()).map(ModuleMetier::code).distinct().count())
                .isEqualTo(ModuleMetier.values().length);
        for (ModuleMetier m : ModuleMetier.values()) {
            assertThat(m.routes()).as(m.code()).isNotEmpty();
            assertThat(m.reference()).as(m.code()).contains("§");
            assertThat(ModuleMetier.parCode(m.code())).isEqualTo(m);
        }
    }
}
