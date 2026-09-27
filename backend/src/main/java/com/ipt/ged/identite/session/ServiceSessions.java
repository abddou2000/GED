package com.ipt.ged.identite.session;

import com.ipt.ged.common.UuidV7;
import com.ipt.ged.identite.ProprietesIdentite;
import com.ipt.ged.identite.erreur.RenouvellementRefuseException;
import com.ipt.ged.identite.evenement.SessionsRevoquees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Sessions et jetons de renouvellement (dossier technique §3.4.1).
 *
 * <ul>
 *   <li>Jeton <b>opaque</b> de 256 bits tirés au sort ; seule son empreinte
 *       SHA-256 est enregistrée (table {@code session}).</li>
 *   <li><b>Rotation à chaque usage</b> : le jeton présenté est marqué consommé,
 *       un nouveau est émis dans la même famille.</li>
 *   <li><b>Réutilisation</b> d'un jeton déjà consommé : vol présumé, toute la
 *       famille est révoquée.</li>
 *   <li>Durée <b>absolue</b> (8 h par défaut, paramétrable — R26) jamais
 *       prolongée par les renouvellements ; <b>inactivité</b> maximale 30 min.</li>
 *   <li><b>Révocation immédiate</b> : déconnexion, et révocation de toutes les
 *       sessions d'un utilisateur par l'Administrateur. Le filtre des requêtes
 *       vérifie la session à chaque appel : un jeton d'accès d'une session
 *       révoquée cesse aussitôt d'être accepté.</li>
 * </ul>
 */
@Service
public class ServiceSessions {

    private static final Logger log = LoggerFactory.getLogger(ServiceSessions.class);
    private static final SecureRandom ALEA = new SecureRandom();

    /** Jeton émis : la valeur (à remettre au client, jamais stockée) et sa session. */
    public record JetonRenouvellement(String valeur, UUID utilisateurId, UUID familleId, Instant expireLe) {}

    private final SessionRepository sessions;
    private final ProprietesIdentite proprietes;
    private final ApplicationEventPublisher evenements;

    public ServiceSessions(SessionRepository sessions, ProprietesIdentite proprietes,
                           ApplicationEventPublisher evenements) {
        this.sessions = sessions;
        this.proprietes = proprietes;
        this.evenements = evenements;
    }

    /** Ouvre une session (connexion réussie) : nouvelle famille, premier jeton. */
    @Transactional
    public JetonRenouvellement ouvrir(UUID utilisateurId, String adresseIp, String agent) {
        Instant maintenant = Instant.now();
        // Ménage des sessions de cet utilisateur échues depuis plus d'une journée.
        sessions.purgerAnciennes(utilisateurId, maintenant.minus(Duration.ofDays(1)));
        UUID famille = UuidV7.suivant();
        Instant expire = maintenant.plus(proprietes.getSession().getDureeAbsolue());
        return emettre(utilisateurId, famille, expire, maintenant, adresseIp, agent);
    }

    /**
     * Renouvelle : consomme le jeton présenté et en émet un nouveau dans la même
     * famille. Les révocations décidées ici (réutilisation, expiration,
     * inactivité) sont validées même si l'appel se termine par un refus.
     *
     * @throws RenouvellementRefuseException jeton inconnu, révoqué, consommé, expiré
     */
    @Transactional(noRollbackFor = RenouvellementRefuseException.class)
    public JetonRenouvellement renouveler(String valeur, String adresseIp, String agent) {
        if (valeur == null || valeur.isBlank() || valeur.length() > 128) {
            throw new RenouvellementRefuseException();
        }
        SessionUtilisateur s = sessions.findByEmpreintePourMiseAJour(empreinte(valeur))
                .orElseThrow(RenouvellementRefuseException::new);
        Instant maintenant = Instant.now();

        if (s.estRevoquee()) {
            throw new RenouvellementRefuseException();
        }
        if (s.estConsommee()) {
            int n = sessions.revoquerFamille(s.getFamilleId(), MotifRevocation.REUTILISATION, maintenant);
            log.warn("Jeton de renouvellement déjà consommé présenté à nouveau : famille {} révoquée "
                    + "(utilisateur {}, {} ligne(s)).", s.getFamilleId(), s.getUtilisateurId(), n);
            evenements.publishEvent(new SessionsRevoquees(s.getUtilisateurId(), null,
                    MotifRevocation.REUTILISATION, n, maintenant));
            throw new RenouvellementRefuseException();
        }
        if (!maintenant.isBefore(s.getExpireLe())) {
            sessions.revoquerFamille(s.getFamilleId(), MotifRevocation.EXPIRATION, maintenant);
            throw new RenouvellementRefuseException();
        }
        if (maintenant.isAfter(s.getDerniereActiviteLe().plus(proprietes.getSession().getInactivite()))) {
            sessions.revoquerFamille(s.getFamilleId(), MotifRevocation.INACTIVITE, maintenant);
            throw new RenouvellementRefuseException();
        }

        s.setConsommeLe(maintenant);
        sessions.save(s);
        return emettre(s.getUtilisateurId(), s.getFamilleId(), s.getExpireLe(), maintenant, adresseIp, agent);
    }

    /** Déconnexion : la famille du jeton présenté tombe. Sans effet sur un jeton inconnu. */
    @Transactional
    public void fermerParJeton(String valeur) {
        if (valeur == null || valeur.isBlank() || valeur.length() > 128) return;
        sessions.findByEmpreintePourMiseAJour(empreinte(valeur))
                .ifPresent(s -> fermerFamille(s.getUtilisateurId(), s.getFamilleId()));
    }

    /** Déconnexion depuis le jeton d'accès (session désignée par {@code sid}). */
    @Transactional
    public void fermerFamille(UUID utilisateurId, UUID familleId) {
        Instant maintenant = Instant.now();
        int n = sessions.revoquerFamille(familleId, MotifRevocation.DECONNEXION, maintenant);
        if (n > 0) {
            evenements.publishEvent(new SessionsRevoquees(utilisateurId, utilisateurId,
                    MotifRevocation.DECONNEXION, n, maintenant));
        }
    }

    /**
     * Révocation manuelle de TOUTES les sessions d'un utilisateur par
     * l'Administrateur (risque R26 : un compte désactivé dans l'annuaire garde
     * sinon sa session jusqu'à la borne absolue).
     *
     * @return nombre de sessions ouvertes fermées
     */
    @Transactional
    public int revoquerToutes(UUID utilisateurId, UUID parUtilisateurId) {
        Instant maintenant = Instant.now();
        int ouvertes = sessions.sessionsOuvertes(utilisateurId, maintenant).size();
        int n = sessions.revoquerUtilisateur(utilisateurId, MotifRevocation.REVOCATION_ADMINISTRATEUR, maintenant);
        evenements.publishEvent(new SessionsRevoquees(utilisateurId, parUtilisateurId,
                MotifRevocation.REVOCATION_ADMINISTRATEUR, n, maintenant));
        return ouvertes;
    }

    @Transactional(readOnly = true)
    public List<SessionUtilisateur> ouvertes(UUID utilisateurId) {
        return sessions.sessionsOuvertes(utilisateurId, Instant.now());
    }

    /** La session d'un jeton d'accès est-elle toujours valable ? Appelé à chaque requête. */
    @Transactional(readOnly = true)
    public boolean active(UUID familleId) {
        return sessions.familleActive(familleId, Instant.now());
    }

    private JetonRenouvellement emettre(UUID utilisateurId, UUID famille, Instant expire, Instant maintenant,
                                        String adresseIp, String agent) {
        byte[] alea = new byte[32];
        ALEA.nextBytes(alea);
        String valeur = Base64.getUrlEncoder().withoutPadding().encodeToString(alea);

        SessionUtilisateur s = new SessionUtilisateur();
        s.setUtilisateurId(utilisateurId);
        s.setFamilleId(famille);
        s.setEmpreinte(empreinte(valeur));
        s.setCreeLe(maintenant);
        s.setDerniereActiviteLe(maintenant);
        s.setExpireLe(expire);
        s.setAdresseIp(tronquer(adresseIp, 45));
        s.setAgentUtilisateur(tronquer(agent, 255));
        sessions.save(s);
        return new JetonRenouvellement(valeur, utilisateurId, famille, expire);
    }

    /** SHA-256 en hexadécimal minuscule : ce qui est stocké, jamais le jeton. */
    static String empreinte(String valeur) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(valeur.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tronquer(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
