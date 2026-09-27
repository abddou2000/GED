package com.ipt.ged.identite.erreur;

import org.springframework.http.HttpStatus;

/**
 * Erreur métier du module d'identité, portant son statut HTTP et un code stable.
 *
 * <p>Transitoire : quand la classe commune {@code ExceptionMetier} (lot de dev2,
 * format problem+json) sera fusionnée, ces exceptions en deviendront des
 * sous-classes et {@link GestionErreursIdentite} disparaîtra. Les codes ne
 * changeront pas.
 *
 * <table>
 *   <caption>Erreurs du module</caption>
 *   <tr><th>Classe</th><th>HTTP</th><th>Code</th></tr>
 *   <tr><td>IdentifiantsRefusesException</td><td>401</td><td>IDENTIFIANTS_REFUSES</td></tr>
 *   <tr><td>RenouvellementRefuseException</td><td>401</td><td>SESSION_EXPIREE</td></tr>
 *   <tr><td>EnteteCsrfManquantException</td><td>403</td><td>ENTETE_CSRF_MANQUANT</td></tr>
 *   <tr><td>TropDeTentativesException</td><td>429 + Retry-After</td><td>TROP_DE_TENTATIVES</td></tr>
 *   <tr><td>AnnuaireIndisponibleException</td><td>503</td><td>ANNUAIRE_INDISPONIBLE</td></tr>
 * </table>
 */
public abstract class ErreurIdentite extends RuntimeException {

    private final HttpStatus statut;
    private final String code;

    protected ErreurIdentite(HttpStatus statut, String code, String message) {
        super(message);
        this.statut = statut;
        this.code = code;
    }

    public HttpStatus getStatut() { return statut; }

    public String getCode() { return code; }
}
