package com.ipt.ged.idempotence;

/** Codes métier stables de l'idempotence (DAT §5.3.2). */
public final class CodesErreurIdempotence {

    private CodesErreurIdempotence() {}

    /** 400 — création sans en-tête Idempotency-Key. */
    public static final String IDEMPOTENCE_CLE_ABSENTE = "IDEMPOTENCE_CLE_ABSENTE";
    /** 400 — Idempotency-Key qui n'est pas un UUID. */
    public static final String IDEMPOTENCE_CLE_INVALIDE = "IDEMPOTENCE_CLE_INVALIDE";
    /** 422 — clé déjà utilisée pour une requête au contenu différent. */
    public static final String IDEMPOTENCE_CONFLIT = "IDEMPOTENCE_CONFLIT";
    /** 409 — une requête avec la même clé est en cours d'exécution. */
    public static final String IDEMPOTENCE_EN_COURS = "IDEMPOTENCE_EN_COURS";
}
