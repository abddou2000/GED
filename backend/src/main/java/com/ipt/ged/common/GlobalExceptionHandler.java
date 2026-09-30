package com.ipt.ged.common;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.EntreeAudit;
import com.ipt.ged.common.erreur.CodesErreur;
import com.ipt.ged.common.erreur.ExceptionMetier;
import com.ipt.ged.common.erreur.Problemes;
import com.ipt.ged.common.erreur.RessourceIntrouvableException;
import com.ipt.ged.common.erreur.TropDeRequetesException;
import com.ipt.ged.fichier.CodesErreurFichier;
import com.ipt.ged.fichier.ErreurFichierException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Traduit toute erreur d'API en {@code application/problem+json} (RFC 7807,
 * DAT 5.3.2), avec un code métier stable.
 *
 * <p>Forme de chaque réponse : {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance}, {@code code}, {@code traceId}, et pour les
 * 400 de validation le dictionnaire {@code erreurs} indexé par champ (voir
 * {@link Problemes}). Le client décide sur {@code code} ; {@code detail} est un
 * libellé pour l'utilisateur, qui peut évoluer.
 *
 * <p><b>Contrat pour les autres lots</b> : lever une {@link ExceptionMetier}
 * (ou une sous-classe) avec un code du catalogue du domaine. Ce gestionnaire
 * n'a jamais à être modifié pour un nouveau cas de refus.
 *
 * <p>Statuts couverts : 400, 401, 403, 404 (objet absent et objet hors
 * périmètre indiscernables), 405, 406, 409, 413, 415, 422, 429 (avec
 * {@code Retry-After}), 500 et 503. Les 401 et 403 émis par la chaîne de
 * sécurité, avant tout contrôleur, passent par
 * {@link com.ipt.ged.common.erreur.ReponsesSecuriteProblem}.
 *
 * <p>Les exceptions standard de Spring MVC (corps illisible, méthode ou type
 * de contenu non pris en charge, route inconnue…) sont traitées par
 * {@link ResponseEntityExceptionHandler} puis complétées ici.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger journal = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Journal d'audit, pour tracer les refus de droits (DAT §7.4.1) ; absent dans les tests unitaires. */
    private AuditService audit;

    @Autowired(required = false)
    void setAudit(AuditService audit) {
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Exceptions métier : le contrat des lots
    // ------------------------------------------------------------------

    @ExceptionHandler(ExceptionMetier.class)
    public ResponseEntity<ProblemDetail> metier(ExceptionMetier ex, HttpServletRequest requete) {
        HttpHeaders entetes = new HttpHeaders();
        if (ex instanceof TropDeRequetesException trop) {
            entetes.set(HttpHeaders.RETRY_AFTER, Long.toString(trop.secondesAvantReessai()));
        }
        ProblemDetail probleme = probleme(ex.statut(), ex.code(), ex.getMessage(), requete);
        Problemes.ajouter(probleme, ex.proprietes());
        tracer(ex.statut(), ex.code(), ex);
        if (ex.statut() == HttpStatus.FORBIDDEN) {
            tracerRefusDeDroits(ex.code(), ex.getMessage(), requete);
        }
        return ResponseEntity.status(ex.statut()).headers(entetes).body(probleme);
    }

    /**
     * Refus et pannes liés aux fichiers (§6.1.5) : 413, 415, 422
     * {@code FICHIER_INFECTE}, 500 {@code INTEGRITE_COMPROMISE}, 503
     * {@code ANTIVIRUS_INDISPONIBLE}… Codes du lot stockage conservés tels quels.
     */
    @ExceptionHandler(ErreurFichierException.class)
    public ResponseEntity<ProblemDetail> fichier(ErreurFichierException ex, HttpServletRequest requete) {
        tracer(ex.statut(), ex.code(), ex);
        return ResponseEntity.status(ex.statut()).body(probleme(ex.statut(), ex.code(), ex.getMessage(), requete));
    }

    // ------------------------------------------------------------------
    // Exceptions héritées du code existant
    // ------------------------------------------------------------------

    /**
     * Objet introuvable → 404. Le message de l'exception (« Document
     * introuvable : 3f2… ») n'est pas recopié : le même libellé sert pour un
     * objet absent et pour un objet hors périmètre (DAT 5.3.2, P5).
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> introuvable(EntityNotFoundException ex, HttpServletRequest requete) {
        return reponse(HttpStatus.NOT_FOUND, CodesErreur.RESSOURCE_INTROUVABLE, null, requete, ex);
    }

    /** Accès refusé levé dans un contrôleur ou un service → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accesRefuse(AccessDeniedException ex, HttpServletRequest requete) {
        tracerRefusDeDroits(CodesErreur.ACCES_REFUSE, ex.getMessage(), requete);
        return reponse(HttpStatus.FORBIDDEN, CodesErreur.ACCES_REFUSE, "Accès refusé.", requete, ex);
    }

    /** Authentification refusée dans un contrôleur → 401, sans en dire la raison. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> nonAuthentifie(AuthenticationException ex, HttpServletRequest requete) {
        return reponse(HttpStatus.UNAUTHORIZED, CodesErreur.NON_AUTHENTIFIE,
                "Authentification requise : identifiants absents, invalides ou expirés.", requete, ex);
    }

    /**
     * Refus métier levé en {@link IllegalArgumentException} par le code
     * existant (code déjà pris, déplacement invalide…) → 400
     * {@code REQUETE_INVALIDE}. Les nouveaux refus lèvent une
     * {@link ExceptionMetier} au statut juste (409, 422…).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> requeteInvalide(IllegalArgumentException ex, HttpServletRequest requete) {
        return reponse(HttpStatus.BAD_REQUEST, CodesErreur.REQUETE_INVALIDE, ex.getMessage(), requete, ex);
    }

    /** Contraintes Bean Validation sur un paramètre de méthode → 400 avec le détail par champ. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> contraintes(ConstraintViolationException ex, HttpServletRequest requete) {
        Map<String, String> erreurs = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String chemin = v.getPropertyPath().toString();
            erreurs.putIfAbsent(chemin.substring(chemin.lastIndexOf('.') + 1), v.getMessage());
        }
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, CodesErreur.VALIDATION_ECHOUEE,
                "Données invalides.", requete);
        probleme.setProperty("erreurs", erreurs);
        tracer(HttpStatus.BAD_REQUEST, CodesErreur.VALIDATION_ECHOUEE, ex);
        return ResponseEntity.badRequest().body(probleme);
    }

    /**
     * Donnée refusée par le schéma (chaîne trop longue, unicité, colonne non
     * nulle) → 400. Filet pour les colonnes qui échapperaient au contrôle
     * applicatif ; le message SQL brut n'est jamais recopié : il exposerait le
     * schéma.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrite(DataIntegrityViolationException ex, HttpServletRequest requete) {
        return reponse(HttpStatus.BAD_REQUEST, CodesErreur.DONNEE_REFUSEE,
                "Donnée refusée par la base : " + causeLisible(ex), requete, ex);
    }

    /**
     * Deux écritures concurrentes sur la même ressource → 409 : un conflit que
     * l'appelant peut rejouer, pas une panne.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> concurrence(ConcurrencyFailureException ex, HttpServletRequest requete) {
        return reponse(HttpStatus.CONFLICT, CodesErreur.MODIFICATION_CONCURRENTE,
                "Le document est en cours de modification par une autre requête. Réessayez.", requete, ex);
    }

    /**
     * Toute autre erreur → 500 sans détail technique ; le {@code traceId} de la
     * réponse retrouve la pile complète dans le journal.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> interne(Exception ex, HttpServletRequest requete) {
        journal.error("Erreur non prévue sur {} {}", requete.getMethod(), requete.getRequestURI(), ex);
        return ResponseEntity.internalServerError().body(probleme(HttpStatus.INTERNAL_SERVER_ERROR,
                CodesErreur.ERREUR_INTERNE, "Erreur interne. Communiquez le traceId au support.", requete));
    }

    // ------------------------------------------------------------------
    // Exceptions standard de Spring MVC
    // ------------------------------------------------------------------

    /** Validation d'un corps {@code @Valid} → 400 avec le dictionnaire {@code erreurs}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        Map<String, String> erreurs = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            erreurs.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, CodesErreur.VALIDATION_ECHOUEE,
                "Données invalides.", servlet(request));
        probleme.setProperty("erreurs", erreurs);
        tracer(HttpStatus.BAD_REQUEST, CodesErreur.VALIDATION_ECHOUEE, ex);
        return ResponseEntity.badRequest().headers(headers).body(probleme);
    }

    /** Paramètre de mauvais type, typiquement un identifiant qui n'est pas un UUID → 400. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(@NonNull TypeMismatchException ex,
                                                        @NonNull HttpHeaders headers,
                                                        @NonNull HttpStatusCode status,
                                                        @NonNull WebRequest request) {
        String nom = ex.getPropertyName() != null ? ex.getPropertyName() : "paramètre";
        String detail = UUID.class.equals(ex.getRequiredType())
                ? "Identifiant invalide : « " + nom + " » attend un UUID."
                : "Paramètre invalide : « " + nom + " ».";
        tracer(HttpStatus.BAD_REQUEST, CodesErreur.PARAMETRE_INVALIDE, ex);
        return ResponseEntity.badRequest().headers(headers)
                .body(probleme(HttpStatus.BAD_REQUEST, CodesErreur.PARAMETRE_INVALIDE, detail, servlet(request)));
    }

    /**
     * Corps illisible. Cas particulier : champ inconnu d'un corps marqué
     * {@link com.ipt.ged.common.erreur.ChampsInconnusRefuses} → 400
     * {@code PARAMETRE_INCONNU}, avec le chemin du champ (ANO-F-011) ; les autres
     * cas suivent le traitement commun.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(@NonNull HttpMessageNotReadableException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        if (ex.getCause() instanceof UnrecognizedPropertyException inconnu) {
            String chemin = inconnu.getPath().stream()
                    .map(r -> r.getFieldName() != null ? "." + r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(java.util.stream.Collectors.joining()).replaceFirst("^\\.", "");
            String parametre = chemin.isEmpty() ? inconnu.getPropertyName() : chemin;
            ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, CodesErreur.PARAMETRE_INCONNU,
                    "Paramètre inconnu : « " + parametre + " ».", servlet(request));
            probleme.setProperty("parametre", parametre);
            tracer(HttpStatus.BAD_REQUEST, CodesErreur.PARAMETRE_INCONNU, ex);
            return ResponseEntity.badRequest().headers(headers).body(probleme);
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    /** Requête multipart au-delà du plafond de la plateforme → 413, code du lot stockage. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(@NonNull MaxUploadSizeExceededException ex,
                                                                          @NonNull HttpHeaders headers,
                                                                          @NonNull HttpStatusCode status,
                                                                          @NonNull WebRequest request) {
        tracer(HttpStatus.PAYLOAD_TOO_LARGE, CodesErreurFichier.FICHIER_TROP_VOLUMINEUX, ex);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).headers(headers)
                .body(probleme(HttpStatus.PAYLOAD_TOO_LARGE, CodesErreurFichier.FICHIER_TROP_VOLUMINEUX,
                        "Fichier trop volumineux : plafond de la plateforme dépassé.", servlet(request)));
    }

    /**
     * Point de passage des autres exceptions standard (corps illisible, méthode
     * non autorisée, type de contenu, route inconnue,
     * {@code ResponseStatusException}…) : le {@link ProblemDetail} préparé par
     * Spring est complété du code, du traceId, du type et du titre.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception ex, @Nullable Object body,
                                                             @NonNull HttpHeaders headers,
                                                             @NonNull HttpStatusCode statusCode,
                                                             @NonNull WebRequest request) {
        String code = codePourStatut(statusCode);
        ProblemDetail probleme = body instanceof ProblemDetail pd ? pd
                : ProblemDetail.forStatusAndDetail(statusCode, ex.getMessage());
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            probleme.setDetail(RessourceIntrouvableException.DETAIL);
        }
        Problemes.completer(probleme, code, servlet(request));
        tracer(statusCode, code, ex);
        return super.handleExceptionInternal(ex, probleme, headers, statusCode, request);
    }

    // ------------------------------------------------------------------

    /**
     * Refus de droits tracé au journal d'audit (DAT §7.4.1 : « les refus de
     * droits sont tracés »), dans une transaction propre : l'opération refusée
     * est annulée, sa trace demeure. Un échec d'écriture de la trace n'empêche
     * pas la réponse 403.
     */
    private void tracerRefusDeDroits(String code, String motif, HttpServletRequest requete) {
        if (audit == null) return;
        try {
            audit.enregistrer(EntreeAudit.de(ActionAudit.ACCES_REFUSE)
                    .refus(code + " " + (requete != null ? requete.getMethod() + " " + requete.getRequestURI() : "")
                            + (motif != null ? " : " + motif : "")));
        } catch (RuntimeException e) {
            journal.error("Refus de droits non tracé au journal d'audit", e);
        }
    }

    private ResponseEntity<ProblemDetail> reponse(HttpStatus statut, String code, @Nullable String detail,
                                                  HttpServletRequest requete, Exception cause) {
        tracer(statut, code, cause);
        return ResponseEntity.status(statut).body(probleme(statut, code, detail, requete));
    }

    private static ProblemDetail probleme(HttpStatusCode statut, String code, @Nullable String detail,
                                          @Nullable HttpServletRequest requete) {
        // Toute réponse 404 porte le même libellé, quel que soit le lanceur (P5).
        String texte = statut.value() == 404 ? RessourceIntrouvableException.DETAIL : detail;
        return Problemes.creer(statut, code, texte, requete);
    }

    /* Refus attendus : une ligne INFO sans pile (DAT 7.3.1). Pannes (5xx) :
       WARN avec la cause, pour le diagnostic. */
    private static void tracer(HttpStatusCode statut, String code, Exception ex) {
        if (statut.is5xxServerError()) {
            journal.warn("Réponse {} {} : {}", statut.value(), code, ex.getMessage(), ex);
        } else {
            journal.info("Réponse {} {} : {}", statut.value(), code, ex.getMessage());
        }
    }

    static String codePourStatut(HttpStatusCode statut) {
        return switch (statut.value()) {
            case 400 -> CodesErreur.REQUETE_INVALIDE;
            case 401 -> CodesErreur.NON_AUTHENTIFIE;
            case 403 -> CodesErreur.ACCES_REFUSE;
            case 404 -> CodesErreur.RESSOURCE_INTROUVABLE;
            case 405 -> CodesErreur.METHODE_NON_AUTORISEE;
            case 406 -> CodesErreur.FORMAT_REPONSE_NON_ACCEPTABLE;
            case 409 -> CodesErreur.CONFLIT;
            case 413 -> CodesErreur.REQUETE_TROP_VOLUMINEUSE;
            case 415 -> CodesErreur.TYPE_CONTENU_NON_SUPPORTE;
            case 422 -> CodesErreur.REGLE_METIER_VIOLEE;
            case 429 -> CodesErreur.TROP_DE_REQUETES;
            case 503 -> CodesErreur.SERVICE_INDISPONIBLE;
            default -> statut.is5xxServerError() ? CodesErreur.ERREUR_INTERNE : CodesErreur.REQUETE_INVALIDE;
        };
    }

    @Nullable
    private static HttpServletRequest servlet(WebRequest requete) {
        return requete instanceof NativeWebRequest n ? n.getNativeRequest(HttpServletRequest.class) : null;
    }

    /**
     * Cause racine ramenée à une phrase. On ne recopie pas le message SQL brut :
     * il expose le schéma (nom de table, de contrainte) à l'appelant.
     */
    private static String causeLisible(DataIntegrityViolationException ex) {
        Throwable racine = ex.getMostSpecificCause();
        String message = racine.getMessage() != null ? racine.getMessage() : "";
        String bas = message.toLowerCase();
        if (bas.contains("too long") || bas.contains("value too large")
                || bas.contains("data truncation") || bas.contains("trop long")) {
            return "une valeur dépasse la longueur autorisée pour sa colonne ("
                    + Limites.TEXTE + " caractères pour un champ texte).";
        }
        if (bas.contains("unique") || bas.contains("duplicate") || bas.contains("dupliqu")) {
            return "cette valeur est déjà utilisée.";
        }
        if (bas.contains("null")) {
            return "un champ obligatoire n'a pas été renseigné.";
        }
        return "contrainte d'intégrité violée.";
    }
}
