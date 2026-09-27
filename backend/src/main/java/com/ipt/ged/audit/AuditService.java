package com.ipt.ged.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.common.ActeurCourant;
import com.ipt.ged.journalisation.ContexteJournalisation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Point d'écriture unique du journal d'audit (DAT §7.4).
 *
 * <p><b>Transaction.</b> Un succès est écrit dans la transaction de l'action :
 * si l'action est annulée, sa trace l'est aussi, et si l'écriture de la trace
 * échoue, l'action échoue (une action non tracée n'a pas lieu). Un refus ou un
 * échec est écrit dans une transaction propre : il doit rester au journal
 * alors même que l'opération refusée est annulée.
 *
 * <p><b>Contexte.</b> L'acteur, l'adresse IP et le traceId non fournis par
 * l'événement sont pris dans la requête : identité authentifiée (jamais une
 * donnée envoyée par l'appelant), adresse de confiance et traceId du contexte
 * de journalisation (DAT 7.2 : même identifiant que le journal technique).
 *
 * <p>Il n'existe, dans l'application, aucune méthode de modification ni de
 * suppression du journal (revue technique D11) : seulement {@code INSERT}.
 */
@Service
public class AuditService {

    /** Attribut de requête posé par le filtre des clés d'API (lot intégration) : identifiant de l'application. */
    public static final String ATTRIBUT_APPLICATION = AuditService.class.getName() + ".application";

    private static final Logger journal = LoggerFactory.getLogger(AuditService.class);
    private static final Pattern FORME_ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Pattern TRACE_W3C = Pattern.compile("^[0-9a-f]{32}$");

    private static final String INSERTION = """
            INSERT INTO journal_audit (acteur_utilisateur_id, acteur_application_id, acteur_nom, adresse_ip,
                                       action, objet_type, objet_id, avant, apres, resultat, motif, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactionPropre;
    private final Clock horloge;
    /** Dernier mois dont les partitions (mois courant et suivant) sont assurées. */
    private final AtomicReference<YearMonth> moisAssure = new AtomicReference<>();

    @Autowired
    public AuditService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
        this(jdbc, json, transactions, Clock.systemUTC());
    }

    AuditService(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions, Clock horloge) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactionPropre = new TransactionTemplate(transactions);
        this.transactionPropre.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.horloge = horloge;
    }

    /**
     * Inscrit l'événement : dans la transaction courante pour un succès, dans
     * une transaction propre pour un refus ou un échec.
     */
    public void enregistrer(EvenementAudit evenement) {
        if (evenement.resultat() == ResultatAudit.SUCCES) {
            ecrire(evenement);
        } else {
            enregistrerHorsTransaction(evenement);
        }
    }

    /** Inscrit l'événement dans une transaction propre, quel que soit son résultat. */
    public void enregistrerHorsTransaction(EvenementAudit evenement) {
        transactionPropre.executeWithoutResult(statut -> ecrire(evenement));
    }

    private void ecrire(EvenementAudit e) {
        String action = e.action();
        if (action == null || !FORME_ACTION.matcher(action).matches()) {
            throw new IllegalStateException("Code d'action d'audit invalide : " + action);
        }
        if (!ActionAudit.connu(action)) {
            journal.warn("Code d'action d'audit hors catalogue ActionAudit : {}", action);
        }
        assurerPartitions();
        Contexte c = contexte();
        jdbc.update(INSERTION,
                e.acteurUtilisateurId() != null ? e.acteurUtilisateurId() : c.utilisateurId(),
                e.acteurApplicationId() != null ? e.acteurApplicationId() : c.applicationId(),
                borner(e.acteurNom() != null ? e.acteurNom() : c.nom(), 255),
                borner(e.adresseIp() != null ? e.adresseIp() : c.ip(), 45),
                action,
                borner(e.objetType(), 64),
                e.objetId(),
                versJson(e.avant()),
                versJson(e.apres()),
                e.resultat().name(),
                borner(e.motif(), 1000),
                c.traceId());
    }

    /**
     * Crée d'avance les partitions du mois courant et du suivant, une fois par
     * mois et par instance. Dans une transaction propre : la création ne doit
     * pas dépendre du sort de l'action tracée.
     */
    void assurerPartitions() {
        YearMonth mois = YearMonth.now(horloge.withZone(ZoneOffset.UTC));
        if (mois.equals(moisAssure.get())) return;
        Instant debut = mois.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        transactionPropre.executeWithoutResult(statut -> {
            creerPartition(debut);
            creerPartition(mois.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        });
        moisAssure.set(mois);
    }

    /** Appelle la fonction SECURITY DEFINER de la base ; idempotent. */
    String creerPartition(Instant dans) {
        return jdbc.queryForObject("SELECT journal_audit_creer_partition(?)", String.class,
                java.sql.Timestamp.from(dans));
    }

    private String versJson(Map<String, Object> valeurs) {
        if (valeurs == null) return null;
        try {
            return json.writeValueAsString(valeurs);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Valeurs d'audit non sérialisables", ex);
        }
    }

    private static String borner(String texte, int max) {
        if (texte == null) return null;
        return texte.length() <= max ? texte : texte.substring(0, max);
    }

    /* --------------------------------------------------------------- contexte */

    record Contexte(UUID utilisateurId, UUID applicationId, String nom, String ip, UUID traceId) {}

    Contexte contexte() {
        String nom = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            nom = auth.getName();
        }
        if (nom == null) {
            String duContexte = MDC.get(ContexteJournalisation.USERNAME);
            nom = duContexte != null && !ContexteJournalisation.ANONYME.equals(duContexte) ? duContexte : null;
        }
        return new Contexte(ActeurCourant.employeId(), applicationCourante(), nom,
                MDC.get(ContexteJournalisation.IP), traceCourante());
    }

    private static UUID applicationCourante() {
        RequestAttributes attributs = RequestContextHolder.getRequestAttributes();
        if (attributs == null) return null;
        Object valeur = attributs.getAttribute(ATTRIBUT_APPLICATION, RequestAttributes.SCOPE_REQUEST);
        return valeur instanceof UUID u ? u : null;
    }

    /** traceId W3C (32 caractères hexadécimaux) converti en uuid : même valeur de 128 bits. */
    static UUID traceCourante() {
        String trace = MDC.get(ContexteJournalisation.TRACE_ID);
        if (trace == null || !TRACE_W3C.matcher(trace).matches()) return null;
        return UUID.fromString(trace.substring(0, 8) + "-" + trace.substring(8, 12) + "-"
                + trace.substring(12, 16) + "-" + trace.substring(16, 20) + "-" + trace.substring(20));
    }
}
