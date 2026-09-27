package com.ipt.ged.common.erreur;

import com.ipt.ged.journalisation.ContexteJournalisation;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Fabrique unique des réponses d'erreur {@code application/problem+json}
 * (RFC 7807, DAT 5.3.2) : le gestionnaire MVC et les réponses de la chaîne de
 * sécurité (401, 403) passent tous par ici, pour qu'un client lise partout la
 * même forme :
 * <pre>
 * {
 *   "type": "urn:ged:erreur:fichier-infecte",
 *   "title": "Unprocessable Entity",
 *   "status": 422,
 *   "detail": "Le fichier est infecté : dépôt refusé.",
 *   "instance": "/api/v1/documents",
 *   "code": "FICHIER_INFECTE",
 *   "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
 *   "erreurs": { "nom": "obligatoire" }        (400 de validation seulement)
 * }
 * </pre>
 * {@code type} dérive du code : un identifiant stable (URN), pas une page web
 * qui pourrait disparaître. {@code traceId} relie la réponse au journal
 * technique et au journal d'audit (DAT 7.2).
 */
public final class Problemes {

    /** Préfixe des URI {@code type}. */
    public static final String PREFIXE_TYPE = "urn:ged:erreur:";

    private Problemes() {}

    public static ProblemDetail creer(HttpStatusCode statut, String code, String detail, HttpServletRequest requete) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        completer(probleme, code, requete);
        return probleme;
    }

    /** Ajoute code, type, instance et traceId à un {@link ProblemDetail} existant. */
    public static void completer(ProblemDetail probleme, String code, HttpServletRequest requete) {
        probleme.setType(typePour(code));
        probleme.setTitle(titre(probleme.getStatus()));
        if (requete != null) {
            probleme.setInstance(URI.create(cheminSur(requete)));
        }
        probleme.setProperty("code", code);
        String trace = MDC.get(ContexteJournalisation.TRACE_ID);
        if (trace != null) {
            probleme.setProperty("traceId", trace);
        }
    }

    public static void ajouter(ProblemDetail probleme, Map<String, Object> proprietes) {
        proprietes.forEach(probleme::setProperty);
    }

    /** Titre court et stable du statut (RFC 7807 : il ne varie pas d'une occurrence à l'autre). */
    public static String titre(int statut) {
        return switch (statut) {
            case 400 -> "Requête invalide";
            case 401 -> "Authentification requise";
            case 403 -> "Accès refusé";
            case 404 -> "Ressource introuvable";
            case 405 -> "Méthode non autorisée";
            case 406 -> "Format de réponse non acceptable";
            case 409 -> "Conflit";
            case 413 -> "Contenu trop volumineux";
            case 415 -> "Format non pris en charge";
            case 422 -> "Règle métier non respectée";
            case 429 -> "Trop de requêtes";
            case 503 -> "Service indisponible";
            default -> statut >= 500 ? "Erreur interne" : "Requête refusée";
        };
    }

    public static URI typePour(String code) {
        return URI.create(PREFIXE_TYPE + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /* Chemin seul, sans la chaîne de requête : elle peut porter des critères de
       recherche que la réponse d'erreur n'a pas à recopier. Les caractères hors
       ASCII et réservés sont encodés pour rester une URI valide. */
    private static String cheminSur(HttpServletRequest requete) {
        String chemin = requete.getRequestURI();
        if (chemin == null || chemin.isBlank()) return "/";
        StringBuilder sb = new StringBuilder(chemin.length());
        for (byte b : chemin.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            // '%' est conservé : getRequestURI() rend le chemin déjà encodé.
            if (c > 0x20 && c < 0x7f && "\"<>\\^`{|}".indexOf(c) < 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
}
