package com.ipt.ged.journalisation;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Transmet le contexte de journalisation d'une requête aux traitements qu'elle
 * lance en tâche de fond (DAT 7.1 : le {@code [%thread]} du pattern signale
 * précisément ces traitements multi-thread).
 *
 * <p>Sans lui, une tâche asynchrone journalise avec un MDC vide : on perdrait
 * le lien entre l'action de l'utilisateur et ses effets différés. La tâche
 * reçoit un nouveau {@code spanId} dans la même trace, pour distinguer ses
 * lignes de celles de la requête qui l'a lancée.
 *
 * <p>Déclaré comme bean, il est appliqué automatiquement par Spring Boot à
 * l'exécuteur de tâches de l'application ({@code applicationTaskExecutor}).
 */
public class DecorateurTacheMdc implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable tache) {
        Map<String, String> contexte = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> precedent = MDC.getCopyOfContextMap();
            try {
                if (contexte == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(contexte);
                    if (contexte.containsKey(ContexteJournalisation.TRACE_ID)) {
                        MDC.put(ContexteJournalisation.SPAN_ID, TraceW3C.nouveauSpanId());
                    }
                }
                tache.run();
            } finally {
                // Le thread du pool resservira : on lui rend son état d'origine.
                if (precedent == null) MDC.clear(); else MDC.setContextMap(precedent);
            }
        };
    }
}
