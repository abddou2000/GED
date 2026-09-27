package com.ipt.ged.notification;

import com.ipt.ged.audit.ActionAudit;
import com.ipt.ged.audit.AuditService;
import com.ipt.ged.audit.ResultatAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Expédition asynchrone des e-mails de la boîte d'envoi (DAT §12.9).
 *
 * <p><b>Quand.</b> Dès la validation de la transaction qui a écrit les
 * notifications (sur l'exécuteur de tâches, hors du fil de la requête), et par
 * une relève périodique qui reprend les tentatives différées et ce qu'un arrêt
 * aurait interrompu.
 *
 * <p><b>Une seule fois, même à plusieurs.</b> Chaque notification est prise
 * dans sa propre transaction par {@code SELECT … FOR UPDATE SKIP LOCKED}, avec
 * un nouveau contrôle de l'état : deux relèves (deux instances, ou la relève et
 * le déclenchement après validation) ne l'expédient jamais deux fois.
 *
 * <p><b>Reprises.</b> Trois tentatives au total ({@code ged.notification.tentatives-max}),
 * espacées de 1 puis 2 minutes par défaut ; ensuite l'état passe à
 * {@link EtatCourriel#ECHEC}, la notification restant visible dans
 * l'application. Chaque expédition et chaque échec définitif sont audités.
 */
@Component
public class ExpediteurCourriels {

    private static final Logger journal = LoggerFactory.getLogger(ExpediteurCourriels.class);

    private static final String CANDIDATS = """
            SELECT id FROM notification
            WHERE courriel_etat = 'A_ENVOYER' AND courriel_prochain_essai <= ?
            ORDER BY courriel_prochain_essai
            LIMIT ?""";
    private static final String PRISE = """
            SELECT type, destinataire_id, titre, message, lien, courriel_tentatives
            FROM notification
            WHERE id = ? AND courriel_etat = 'A_ENVOYER'
            FOR UPDATE SKIP LOCKED""";

    enum Issue { ENVOYEE, REPORTEE, ECHEC, IGNOREE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<JavaMailSender> relais;
    private final AnnuaireDestinataires annuaire;
    private final ModelesNotification modeles;
    private final ProprietesNotification proprietes;
    private final AuditService audit;
    private final Executor executeur;
    private final Clock horloge;

    @Autowired
    public ExpediteurCourriels(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                               ObjectProvider<JavaMailSender> relais, AnnuaireDestinataires annuaire,
                               ModelesNotification modeles, ProprietesNotification proprietes, AuditService audit,
                               @Qualifier("applicationTaskExecutor") Executor executeur) {
        this(jdbc, transactions, relais, annuaire, modeles, proprietes, audit, executeur, Clock.systemUTC());
    }

    ExpediteurCourriels(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                        ObjectProvider<JavaMailSender> relais, AnnuaireDestinataires annuaire,
                        ModelesNotification modeles, ProprietesNotification proprietes, AuditService audit,
                        Executor executeur, Clock horloge) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.relais = relais;
        this.annuaire = annuaire;
        this.modeles = modeles;
        this.proprietes = proprietes;
        this.audit = audit;
        this.executeur = executeur;
        this.horloge = horloge;
    }

    /** Expédie sans attendre la relève ce que la transaction vient d'écrire. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void apresEcriture(Notifications.NotificationsEcrites evenement) {
        if (!proprietes.expeditionAuto()) return;
        executeur.execute(this::expedierSansErreur);
    }

    @Scheduled(fixedDelayString = "${ged.notification.intervalle:PT30S}", initialDelayString = "PT20S")
    public void releve() {
        if (proprietes.expeditionAuto()) expedierSansErreur();
    }

    private void expedierSansErreur() {
        try {
            expedier();
        } catch (RuntimeException e) {
            // Base momentanément indisponible, par exemple : la relève suivante reprendra.
            journal.warn("Relève des notifications interrompue : {}", e.toString());
        }
    }

    /**
     * Traite les notifications prêtes (un lot au plus).
     *
     * @return nombre d'e-mails acceptés par le relais
     */
    public int expedier() {
        List<UUID> candidats = jdbc.queryForList(CANDIDATS, UUID.class,
                Timestamp.from(horloge.instant()), proprietes.lot());
        int envoyes = 0;
        for (UUID id : candidats) {
            Issue issue = transaction.execute(statut -> traiter(id));
            if (issue == Issue.ENVOYEE) envoyes++;
        }
        return envoyes;
    }

    private Issue traiter(UUID id) {
        List<Map<String, Object>> lignes = jdbc.queryForList(PRISE, id);
        if (lignes.isEmpty()) return Issue.IGNOREE;   // déjà prise ou déjà traitée ailleurs
        Map<String, Object> n = lignes.get(0);
        UUID destinataire = (UUID) n.get("destinataire_id");
        int tentative = ((Number) n.get("courriel_tentatives")).intValue() + 1;
        Instant maintenant = horloge.instant();

        Optional<String> adresse = annuaire.courriel(destinataire);
        if (adresse.isEmpty()) {
            jdbc.update("UPDATE notification SET courriel_etat = 'SANS_ADRESSE', courriel_prochain_essai = NULL, "
                    + "courriel_erreur = ? WHERE id = ?", "Aucune adresse e-mail connue pour le destinataire", id);
            auditer(ActionAudit.NOTIFICATION_ECHEC, id, n, tentative, ResultatAudit.ECHEC, "SANS_ADRESSE");
            return Issue.ECHEC;
        }

        JavaMailSender expediteur = relais.getIfAvailable();
        try {
            if (expediteur == null) {
                throw new org.springframework.mail.MailSendException("Relais SMTP non configuré (spring.mail.host)");
            }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(proprietes.expediteur());
            message.setTo(adresse.get());
            message.setSubject((String) n.get("titre"));
            message.setText(modeles.corpsCourriel((String) n.get("message"), proprietes.url((String) n.get("lien"))));
            expediteur.send(message);
        } catch (MailException e) {
            return echec(id, n, tentative, maintenant, e);
        }
        jdbc.update("UPDATE notification SET courriel_etat = 'ENVOYE', courriel_tentatives = ?, "
                        + "courriel_envoye_le = ?, courriel_prochain_essai = NULL, courriel_erreur = NULL WHERE id = ?",
                tentative, Timestamp.from(maintenant), id);
        auditer(ActionAudit.NOTIFICATION_ENVOYEE, id, n, tentative, ResultatAudit.SUCCES, null);
        return Issue.ENVOYEE;
    }

    private Issue echec(UUID id, Map<String, Object> n, int tentative, Instant maintenant, MailException e) {
        String erreur = borner(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        // Ni adresse ni texte au journal technique : l'identifiant suffit.
        journal.warn("Notification {} : tentative d'e-mail {}/{} en échec ({})", id, tentative,
                proprietes.tentativesMax(), e.getClass().getSimpleName());
        if (tentative >= proprietes.tentativesMax()) {
            jdbc.update("UPDATE notification SET courriel_etat = 'ECHEC', courriel_tentatives = ?, "
                    + "courriel_prochain_essai = NULL, courriel_erreur = ? WHERE id = ?", tentative, erreur, id);
            auditer(ActionAudit.NOTIFICATION_ECHEC, id, n, tentative, ResultatAudit.ECHEC, "TENTATIVES_EPUISEES");
            return Issue.ECHEC;
        }
        Duration delai = proprietes.delaiReprise().multipliedBy(1L << (tentative - 1));
        jdbc.update("UPDATE notification SET courriel_tentatives = ?, courriel_prochain_essai = ?, "
                + "courriel_erreur = ? WHERE id = ?", tentative, Timestamp.from(maintenant.plus(delai)), erreur, id);
        return Issue.REPORTEE;
    }

    private void auditer(ActionAudit action, UUID id, Map<String, Object> n, int tentative,
                         ResultatAudit resultat, String motif) {
        Map<String, Object> apres = new LinkedHashMap<>();
        apres.put("type", n.get("type"));
        apres.put("destinataireId", n.get("destinataire_id"));
        apres.put("canal", "COURRIEL");
        apres.put("tentative", tentative);
        audit.enregistrer(new EvenementNotificationAudit(action.code(), "NOTIFICATION", id, apres, resultat, motif,
                EvenementNotificationAudit.EXPEDITEUR));
    }

    private static String borner(String texte) {
        return texte.length() <= 1000 ? texte : texte.substring(0, 1000);
    }
}
