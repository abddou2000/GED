package com.ipt.ged.common.erreur;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Refus ou panne porteur de son statut HTTP et de son code métier stable
 * (DAT 5.3.2). C'est l'exception que les lots lèvent pour produire une erreur
 * d'API : le gestionnaire commun la traduit en {@code application/problem+json}
 * sans qu'aucun lot n'ait à le modifier.
 *
 * <p>Utiliser de préférence les sous-classes, qui fixent le statut
 * ({@link RessourceIntrouvableException}, {@link ConflitException},
 * {@link RegleMetierException}…), avec un code du domaine concerné.
 *
 * <p>Le message devient le {@code detail} de la réponse : il est lu par un
 * utilisateur, il ne contient ni trace technique, ni nom de table, ni donnée
 * qu'un appelant ne doit pas apprendre.
 */
public class ExceptionMetier extends RuntimeException {

    private final HttpStatus statut;
    private final String code;
    private final Map<String, Object> proprietes = new LinkedHashMap<>();

    public ExceptionMetier(HttpStatus statut, String code, String detail) {
        this(statut, code, detail, null);
    }

    public ExceptionMetier(HttpStatus statut, String code, String detail, Throwable cause) {
        super(detail, cause);
        this.statut = Objects.requireNonNull(statut, "statut");
        this.code = Objects.requireNonNull(code, "code");
    }

    public HttpStatus statut() {
        return statut;
    }

    /** Code stable, lisible par un client (ne change pas avec le libellé). */
    public String code() {
        return code;
    }

    /**
     * Ajoute un membre d'extension à la réponse (RFC 7807 §3.2), par exemple la
     * version courante d'un document en conflit. Jamais une donnée hors du
     * périmètre de l'appelant.
     */
    public ExceptionMetier avec(String nom, Object valeur) {
        proprietes.put(nom, valeur);
        return this;
    }

    public Map<String, Object> proprietes() {
        return Collections.unmodifiableMap(proprietes);
    }
}
