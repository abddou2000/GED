package com.ipt.ged.identite.erreur;

import com.ipt.ged.common.erreur.ExceptionMetier;
import org.springframework.http.HttpStatus;

/**
 * Erreur métier du module d'identité, portant son statut HTTP et un code stable.
 *
 * <p>Depuis la fusion du contrat d'erreurs (problem+json, dev2) : une
 * {@link ExceptionMetier}, rendue par le gestionnaire commun ; le conseil
 * provisoire {@code GestionErreursIdentite} est supprimé. Les codes n'ont pas
 * changé.
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
 * {@link TropDeTentativesException} dérive de
 * {@link com.ipt.ged.common.erreur.TropDeRequetesException} : c'est elle qui
 * vaut l'en-tête {@code Retry-After} au gestionnaire commun.
 */
public abstract class ErreurIdentite extends ExceptionMetier {

    protected ErreurIdentite(HttpStatus statut, String code, String message) {
        super(statut, code, message);
    }

    protected ErreurIdentite(HttpStatus statut, String code, String message, Throwable cause) {
        super(statut, code, message, cause);
    }
}
