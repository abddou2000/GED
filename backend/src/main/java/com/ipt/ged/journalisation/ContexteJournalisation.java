package com.ipt.ged.journalisation;

/**
 * Clés du contexte de diagnostic (MDC) imposées par le pattern de
 * l'Article 50 (DAT 7.1) :
 * {@code %X{username:-} %X{ip:-} %X{traceId:-}/%X{spanId:-}}.
 *
 * <p>Les noms sont ceux du pattern, à la lettre : un écart de casse
 * ({@code traceid}) produirait une colonne vide sans aucune erreur.
 */
public final class ContexteJournalisation {

    private ContexteJournalisation() {}

    public static final String USERNAME = "username";
    public static final String IP = "ip";
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    /** Valeur des traitements non authentifiés (DAT 7.3 : seul cas admis pour « - »). */
    public static final String ANONYME = "-";

    /* Attributs de requête : ils conservent le contexte d'une requête entre ses
       passages successifs dans la chaîne de filtres (dispatch asynchrone,
       rendu d'erreur), pour que tous ses journaux portent le même traceId. */
    static final String ATTRIBUT_TRACE = ContexteJournalisation.class.getName() + ".trace";
    static final String ATTRIBUT_IP = ContexteJournalisation.class.getName() + ".ip";
    static final String ATTRIBUT_USERNAME = ContexteJournalisation.class.getName() + ".username";
}
