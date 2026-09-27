package com.ipt.ged.journalisation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Le contexte de journalisation suit les traitements lancés en tâche de fond. */
class DecorateurTacheMdcTest {

    private final DecorateurTacheMdc decorateur = new DecorateurTacheMdc();

    @AfterEach
    void nettoyer() {
        MDC.clear();
    }

    @Test
    @DisplayName("La tâche hérite du MDC de la requête, avec un spanId propre, et rend le thread propre")
    void propagation() throws Exception {
        MDC.put("username", "a.benali");
        MDC.put("ip", "41.250.12.3");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Map<String, String> vu = new HashMap<>();
            pool.submit(decorateur.decorate(() -> vu.putAll(MDC.getCopyOfContextMap()))).get(5, TimeUnit.SECONDS);

            assertThat(vu).containsEntry("username", "a.benali")
                    .containsEntry("ip", "41.250.12.3")
                    .containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(vu.get("spanId")).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");

            // Le thread du pool, une fois la tâche finie, ne garde rien.
            Map<String, String> apres = CompletableFuture.supplyAsync(MDC::getCopyOfContextMap, pool)
                    .get(5, TimeUnit.SECONDS);
            assertThat(apres).isNullOrEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Une tâche lancée hors requête ne reçoit pas le contexte résiduel du thread")
    void sansContexte() throws Exception {
        Runnable decoree = decorateur.decorate(() -> assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Le thread du pool porte un contexte résiduel (tâche non décorée).
            pool.submit(() -> MDC.put("username", "residuel")).get(5, TimeUnit.SECONDS);
            pool.submit(decoree).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
